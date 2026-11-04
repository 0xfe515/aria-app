# Aria Field Runtime & Camera Recovery Fixes (ARIA-FIELD-RUNTIME-019)

## 1. Executive Summary

Task `ARIA-FIELD-RUNTIME-019` addresses camera transient failure recovery, eliminates persistent stopped-state latch bugs across reboots, unifies voice and vision foreground service notifications into a single ongoing notification with a global Stop action, removes demonstrated runtime allocations and thread blocking, eliminates local conversational LLM gating on voice/command startup, instruments bounded stage timings across 8 critical pipeline stages, and verifies integrated UI settings changes with the UI peer without performing connected hardware execution.

All changes have been validated using the Android Studio JDK (`C:\Program Files\Android\Android Studio\jbr`):
- `./gradlew :app:testDebugUnitTest` (PASS, 0 failures, including `CameraRecoveryAndNotificationTest` and `SettingsPolicyTest`)
- `./gradlew :app:lintDebug` (PASS, 0 errors)
- `./gradlew :app:assembleDebug` (PASS, APK generated)
- `./gradlew :app:assembleDebugAndroidTest` (PASS, test APKs compiled)
- `git diff --check` (PASS, clean formatting and whitespace)

---

## 2. Root Cause Analysis

### 2.1 Reboot-Persistent Camera Stopped State Investigation
- **Symptom**: Vision pipeline remained persistently stopped across device reboots or app launches, previously hypothesized as an external screen recorder or background process permanently holding the camera sensor.
- **Code-Supported Hypothesis**: Investigation of SharedPreferences (`aria_vision_session.xml`) and startup call sites revealed that prior explicit stop actions persisted `KEY_USER_STOPPED = true`. On subsequent app launches, `MainScreen` and `VisionForegroundService.requestStart()` checked `requiresExplicitRestart(context)`. Because auto-start calls passed `explicitRestart = false`, startup was aborted whenever the persisted latch remained set. Without physical hardware reproduction, the external recorder conflict remains an unverified hypothesis, while the code demonstrates a reproducible preference-gated stop latch path.
- **Fix**: Persisted latch `KEY_USER_STOPPED` is strictly reserved for explicit user stop commands and is never set on runtime/hardware faults. It is not unconditionally cleared on incidental provider access or Activity recreation/rotation; only an explicit user start/retry action clears the latch. Main activity entry shows restart guidance when stopped, without silently resuming the camera.

### 2.2 Camera Error Observer Unconditionally Triggering Terminal Failure
- **Symptom**: Any transient camera error (e.g., `ERROR_CAMERA_IN_USE` code 1 or temporary camera busy) immediately transitioned `cameraStatus` to `CameraSessionStatus.Error`, permanently shutting down the vision pipeline without retry.
- **Fix**: Implemented `CameraErrorClassifier` to categorize errors into:
  1. `TransientInUse` (`ERROR_CAMERA_IN_USE`, `ERROR_MAX_CAMERAS_IN_USE`, `ERROR_OTHER_RECOVERABLE_ERROR`, `ERROR_DO_NOT_DISTURB_MODE_ENABLED`, busy exceptions): Transitions to `CameraSessionStatus.CameraUnavailable`, updates notification, registers `CameraManager.AvailabilityCallback`, and schedules bounded exponential backoff retries (500ms, 1000ms, 2000ms, 4000ms, 8000ms up to 5 attempts) with generation fencing (`sessionTicket`).
  2. `Permission` (`SecurityException`, `ERROR_CAMERA_DISABLED`): Sets `CameraSessionStatus.PermissionDenied`, posts auto-canceling error notification, prompts user action.
  3. `Fatal` (`ERROR_CAMERA_FATAL_ERROR`, `ERROR_STREAM_CONFIG`): Reports truthful failure with error details, allowing restart from app UI.

### 2.3 Premature `Running` Status
- **Symptom**: `VisionPipelineController.bindUseCases()` immediately published `cameraStatus = CameraSessionStatus.Running` before any frame had arrived from the camera.
- **Fix**: Controller keeps status as `CameraSessionStatus.Starting`. Transition to `Running` occurs strictly upon arrival and processing of the first fresh image frame from `ImageAnalysis` or `Preview`.

### 2.4 Immutable Bitmap Publication for Compose UI
- **Symptom**: In-place mutation of a shared Bitmap buffer while Jetpack Compose is asynchronously rendering can cause visual tearing, race conditions, or illegal state exceptions if recycled during close.
- **Fix**: Published overlay Bitmaps are created as immutable per-frame snapshots throttled to the UI publication rate (4 FPS / 250ms interval) only when the UI is visible. Segmenter internal pixel decoders use reusable arrays, but published Bitmaps are handed off cleanly to Compose without in-place mutation or unsafe manual `recycle()` on segmenter close.

### 2.5 Synchronous Thread Blocking
- **Symptom**: `analyzerExecutor` submitted a `Callable` to `AriaInferenceThread` and executed `Future.get()`, blocking the analyzer thread synchronously and introducing thread hop latency.
- **Fix**: Analyzer tasks run directly on `pipelineExecutor` (`AriaInferenceThread`) with non-blocking lifecycle guards.

### 2.6 Dual-Service Notification Collision
- **Symptom**: `VoiceService` (in `:wakeword` process) and `VisionForegroundService` (in main process) posted independent notifications on different channels and IDs (`1` vs `1001`), leading to confusing multiple notifications and leaving one running when the other stopped.
- **Fix**: Unified notification handling via `AriaUnifiedNotificationCoordinator`:
  - Common channel: `aria_foreground_service` ("Aria 백그라운드 서비스", `IMPORTANCE_LOW`).
  - Common ongoing notification ID: `1001`.
  - Truthful aggregated status:
    - Both active: "음성 인식 및 비전 모니터링 동작 중" (or "음성 인식 중 · 카메라 일시 사용 중 (재연결 대기)").
    - Vision only: "카메라로 주변 위험을 모니터링 중입니다.".
    - Voice only: "음성 대기 중입니다.".
  - Single Stop action: PendingIntent to `AriaUnifiedStopReceiver` (`ACTION_STOP_ALL`), which broadcasts stop to both services, marks explicit stop, and cleans up active flags.
  - Symmetrical service detach: If one service stops while the other is still running, it calls `stopForeground(STOP_FOREGROUND_DETACH)` and updates the ongoing notification so the remaining service maintains it. When the last service stops, it calls `stopForeground(STOP_FOREGROUND_REMOVE)`.
  - Auto-canceling error notification ID: `1002`, isolated from ongoing notification.

### 2.7 Local Conversational LLM Gating Removal
- **Symptom**: Conversational queries were gated behind downloading a 2.59 GB Gemma model. If Gemma was uninstalled or preparing, questions were rejected with "지원하는 명령을 확인해 주세요...". Startup policy also entered `StartupStage.LanguagePrepare` if Gemma was preparing, blocking the entire app UI.
- **Fix**:
  - Decoupled voice and command startup: offline commands (Home, Settings, Sound, Status, Help, Cancel, Stop, Naver/Kakao map navigation) operate 100% locally and offline without Gemma.
  - Conversational questions without local Gemma are routed directly to Gemini cloud text questions (`handleCloudTextQuestion()`), preserving user experience without multi-gigabyte prerequisite downloads.
  - Startup policy no longer blocks on Gemma background loading.

---

## 3. 8-Stage Runtime Timing Instrumentation

Added `AriaRuntimeTimingTracker` providing thread-safe, allocation-free, bounded sample rings (100 samples) computing min, max, mean, p50, and p95 across 8 pipeline stages:
1. `start`: Camera session startup latency (time from `startCamera` to first delivered frame).
2. `recognition`: Speech recognition latency (time from user speech detection to finalized command).
3. `analyzer`: Total camera analyzer frame duration.
4. `preprocess`: Image cropping, rotation, and color conversion.
5. `invoke`: Model inference duration (`LiteRtVisionSegmenter`).
6. `post`: Segmentation mask extraction and corridor occupancy calculation.
7. `UI`: Result publication duration to UI state flow.
8. `alerts`: Hazard detection and alert coordinator evaluation duration.

---

## 4. Modified & Created Files

### Exclusive Path Changes (Runtime Worker):
- `app/src/main/AndroidManifest.xml`: Declared `AriaRuntimeProvider` and `AriaUnifiedStopReceiver`.
- `app/src/main/java/com/ariateam/aria/runtime/AriaRuntimeProvider.kt`: ContentProvider initialization and `AriaRuntimeStateBridge` for cross-process state coordination.
- `app/src/main/java/com/ariateam/aria/runtime/AriaUnifiedNotificationCoordinator.kt`: Unified foreground notification builder, channel manager, and error notification poster.
- `app/src/main/java/com/ariateam/aria/runtime/AriaUnifiedStopReceiver.kt`: Broadcast receiver for `ACTION_STOP_ALL`.
- `app/src/main/java/com/ariateam/aria/runtime/AriaRuntimeTimingTracker.kt`: 8-stage bounded timing tracker.
- `app/src/main/java/com/ariateam/aria/vision/CameraErrorClassifier.kt`: Error classifier for CameraX / CameraAccessException codes and exceptions.
- `app/src/main/java/com/ariateam/aria/vision/VisionSession.kt`: Cold-start reconstruction and explicit stop latching.
- `app/src/main/java/com/ariateam/aria/vision/VisionForegroundService.kt`: Unified notification integration, symmetrical detachment, error handling.
- `app/src/main/java/com/ariateam/aria/vision/VisionPipelineController.kt`: Transient retry handling, availability callback, starting status latch, timing recordings.
- `app/src/main/java/com/ariateam/aria/vision/LiteRtVisionSegmenter.kt`: Immutable Bitmap publication per published frame without mutating live bitmaps or unsafe close recycling.
- `app/src/main/java/com/ariateam/aria/vision/VisionTimingTracker.kt`: Percentile and ring buffer extensions.
- `app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt`: Explicit camera start event wiring for diagnostic/demo screens and explicit retry recovery.
- `app/src/main/java/com/ariateam/aria/speech/VoiceService.kt`: Unified notification channel, ongoing notification ID 1001, cross-process active registration.
- `app/src/main/java/com/ariateam/aria/speech/VoiceListeningSession.kt`: Recognition latency instrumentation.
- `app/src/main/java/com/ariateam/aria/command/CommandCoordinator.kt`: Conversational question routing to Gemini cloud, decoupling startup from local Gemma.
- `app/src/test/java/com/ariateam/aria/vision/CameraRecoveryAndNotificationTest.kt`: JVM unit regression suite.

### Integrated UI Peer Changes (Validated, Not Modified):
- `app/build.gradle.kts`: Preview version bump to `1.0.0-preview.20261104.1` (versionCode 3).
- `app/src/main/java/com/ariateam/aria/ui/screen/SettingsScreen.kt`: Reorganized settings categories (일반, 계정, 진단, 데모, 앱 정보).
- `app/src/main/java/com/ariateam/aria/ui/screen/BodyMountProfileDialog.kt`: Concise mount card with 어깨 / 가슴 choices without confusing subtitles.
- `app/src/test/java/com/ariateam/aria/ui/screen/SettingsPolicyTest.kt`: Category ordering and mount policy unit tests.

---

## 5. Verification Commands & Results

All verification commands executed cleanly on Windows using the repository Gradle wrapper:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 1. Run JVM unit regression tests
.\gradlew.bat :app:testDebugUnitTest
# Result: BUILD SUCCESSFUL (all test suites passed, including CameraRecoveryAndNotificationTest and SettingsPolicyTest)

# 2. Run Android Lint
.\gradlew.bat :app:lintDebug
# Result: BUILD SUCCESSFUL (0 lint errors)

# 3. Assemble Debug APK
.\gradlew.bat :app:assembleDebug
# Result: BUILD SUCCESSFUL (app-debug.apk assembled)

# 4. Compile instrumented AndroidTest targets
.\gradlew.bat :app:assembleDebugAndroidTest
# Result: BUILD SUCCESSFUL (all AndroidTest sources compiled successfully)

# 5. Check git diff hygiene
git diff --check
# Result: Exit code 0 (clean formatting, no trailing whitespace or merge conflict markers)
```

No real-device installation (`installDebug`) or connected instrumentation runs (`connectedDebugAndroidTest`) were performed, strictly adhering to the current task guidelines. Hardware recovery, real camera sensor latencies, and device frame rates will be measured upon physical deployment.
