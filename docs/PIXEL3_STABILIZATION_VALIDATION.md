# Pixel 3 Stabilization Validation Package (Non-Executed Specification)

**Status**: Prepared for Future Execution (NO device execution performed in current iteration per policy).  
**Target Hardware**: Google Pixel 3 (Android 12 / API 31, Snapdragon 845, Adreno 630).  
**Repository Baseline**: `main` (branch `main`).  
**Authoring Task**: `ARIA-STABILIZE-RUNTIME-022` (`task_05a5d622fb7d`).

---

> [!IMPORTANT]
> This document specifies test scenarios, templates, and manual execution procedures for future on-device validation.
> In accordance with the current field-fix iteration policy, **no device commands, installations, or automated execution scripts were run**.
> Execution must occur only when a compatible physical Pixel 3 device or emulator is explicitly connected and authorized.

---

## 1. Build Artifact & Hardware Verification Template

Future operators must record exact artifact hashes and device environment before running validations.

```markdown
### Validation Run Header
- Date / Time: __________________________ (YYYY-MM-DD HH:MM KST)
- Operator: _____________________________
- Git Commit SHA: _______________________ (`git rev-parse HEAD`)
- Git Status Clean: [ ] Yes  [ ] No (explain: ________________)
- APK Path: `app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: __________________________ (`Get-FileHash -Algorithm SHA256 <path>` / `sha256sum <path>`)
- Target Device Model: Google Pixel 3 (`blueline`)
- Android OS Version: Android 12 (API 31)
- Build Fingerprint: ____________________ (`adb shell getprop ro.build.fingerprint`)
- Battery Level & Temp: _____ %, _____ °C (`adb shell dumpsys battery`)
```

---

## 2. Class-Separated Future Instrumentation Commands

To prevent timeouts, memory pressure, and camera contention across disparate test suites, run instrumented tests class-by-class. Do not run the full suite in a single invocation.

### 2.1 Camera & Vision Device Tests
```bash
# 1. Verify bitmap buffer memory ownership and immutability across segmenter cycles
adb shell am instrument -w -r -e class com.ariateam.aria.vision.BitmapOwnershipDeviceTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 2. Verify GPU presentation pipeline and surface lifecycle
adb shell am instrument -w -r -e class com.ariateam.aria.vision.GpuPresentationEvidenceTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 3. Verify background camera session stability and lifecycle detach
adb shell am instrument -w -r -e class com.ariateam.aria.vision.VisionBackgroundSessionTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 4. Measure camera frame rate, capture latency, and drop rates
adb shell am instrument -w -r -e class com.ariateam.aria.vision.VisionCameraBenchmarkTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
```

### 2.2 Orientation & UI Overlay Verification Tests
```bash
# 5. Verify portrait orientation locking and activity recreate safety
adb shell am instrument -w -r -e class com.ariateam.aria.MainActivityOrientationTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 6. Verify black screen overlay mode and low-power rendering
adb shell am instrument -w -r -e class com.ariateam.aria.ui.screen.BlackScreenOverlayTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 7. Verify dashboard heading and single-line directional guidance overlay
adb shell am instrument -w -r -e class com.ariateam.aria.ui.screen.DashboardHeadingUiTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 8. Verify Settings navigation and tab order (일반, 계정, 진단, 데모, 앱 정보)
adb shell am instrument -w -r -e class com.ariateam.aria.ui.screen.SettingsScreenTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
```

### 2.3 Speech & Navigation Integration Tests
```bash
# 9. Verify offline speech recognizer probe and microphone capabilities
adb shell am instrument -w -r -e class com.ariateam.aria.speech.SpeechCapabilityProbeTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 10. Verify cloud question fallback and timeout handling
adb shell am instrument -w -r -e class com.ariateam.aria.cloud.CloudQuestionDeviceTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 11. Verify external navigation intent launching (Google Maps / KakaoMap / Naver Map)
adb shell am instrument -w -r -e class com.ariateam.aria.navigation.ExternalNavigationIntegrationTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner

# 12. Verify cross-process hazard alert bridge to speech service
adb shell am instrument -w -r -e class com.ariateam.aria.speech.HazardSpeechIntegrationDeviceTest \
  com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
```

---

## 3. Manual Device Verification Scenarios

### Scenario A: Screen Recording and UI Constraint Conformance
- **Goal**: Confirm 2026-11-04 UI correction rules on physical screen.
- **Preconditions**: Pixel 3 unlocked, portrait orientation, ambient lighting.
- **Steps**:
  1. Start device screen recording: `adb shell screenrecord --time-limit 60 /sdcard/aria_ui_check.mp4`
  2. Launch Aria from launcher: `adb shell am start -n com.ariateam.aria/.MainActivity`
  3. Grant Camera and Audio permissions if prompted.
  4. Observe Home screen layout:
     - Vision overlay is positioned in the upper portion of the screen.
     - Overlay view may shrink but **must never push or displace** other UI components.
     - The vision overlay subtree contains **no buttons, clickable surfaces, toggles, profile selectors, or recovery buttons**.
     - Direction visualization consists of **exactly one plain line** originating from the actual overlay image's **bottom center** (near the implied user position).
     - Confirm **NO arrows, circles, rings, dashed guides, secondary strokes**, or origin shifted to the image center.
  5. Pull screenrecord to host: `adb pull /sdcard/aria_ui_check.mp4 ./evidence/pixel3/`
  6. Pass Criteria: Visual inspect confirms all UI constraint rules strictly respected.

### Scenario B: Camera Contention & Filtered Availability Recovery
- **Goal**: Verify that camera in-use errors enter `CameraUnavailable` status, ignore non-target cameras, and retry cleanly upon release.
- **Steps**:
  1. With Aria running in foreground or background, launch the native Camera app:
     `adb shell am start -a android.media.action.IMAGE_CAPTURE`
  2. Observe notification:
     - Notification text updates to: `"음성 인식 중 · 카메라 일시 사용 중 (재연결 대기)"` or `"카메라 일시 사용 중 (재연결 대기)"`.
     - App UI reflects `CameraUnavailable`.
  3. Verify logs:
     - `AriaVisionPipeline`: Transient in-use error logged (`CameraState.ERROR_CAMERA_IN_USE`).
     - Backoff timer scheduled (500ms, 1000ms, 2000ms...).
     - CameraManager `AvailabilityCallback` registered for `targetCameraId` (e.g. `"0"`).
     - Any front camera (`"1"`) availability events are filtered and ignored.
     - No premature `Running` status is posted.
  4. Press Home or close native Camera app:
     `adb shell am start -n com.ariateam.aria/.MainActivity`
  5. Observe camera recovery:
     - Target camera `"0"` becomes available.
     - Exactly one retry executes for the active attempt.
     - Upon receiving fresh frame, notification transitions back to `"음성 인식 및 비전 모니터링 동작 중"`.
     - UI state returns to `Running`.

### Scenario C: Back-Forward Navigation & Tab Traversals
- **Goal**: Ensure navigation between screens does not interrupt background services or leak camera resources.
- **Steps**:
  1. From Home screen, tap Settings icon.
  2. Verify Settings screen tab order: **일반, 계정, 진단, 데모, 앱 정보**.
  3. Under **일반**: Verify posture/mount profile options (어깨 / 가슴 only).
  4. Under **계정**: Verify Gemini API configuration (local conversation LLM UI removed; local ASR and vision preserved).
  5. Under **진단**: Inspect pipeline diagnostics snapshot (completed frames, analyzer entries, backend description).
  6. Navigate back to Home screen via Android Back gesture / Back button.
  7. Pass Criteria: Camera preview remains continuous or smoothly resumes; no foreground service re-creation or audio pops.

### Scenario D: Whole-Stop Consistency via Notification
- **Goal**: Verify single persistent notification Stop action cleanly shuts down both Voice and Vision services.
- **Steps**:
  1. Start both Voice and Vision services. Verify ongoing notification `ID 1001` with action label `"모두 중지"`.
  2. Pull down notification shade. Tap `"모두 중지"`.
  3. Verify execution via logcat:
     - `AriaUnifiedStopReceiver` receives `ACTION_STOP_ALL`.
     - `VisionSession.markExplicitStop(context)` persists user-stop latch to SharedPreferences.
     - `VisionForegroundService.requestStop(context)` calls `context.stopService(...)`.
     - `context.stopService(Intent(context, VoiceService::class.java))` terminates voice service without `startService` exceptions.
     - Both services execute `onDestroy()`.
     - `AriaRuntimeStateBridge.setVisionActive(false)` and `setVoiceActive(false)` update upon actual lifecycle destruction.
     - Notification `1001` is cancelled.
  4. Launch Aria from launcher:
     - Persistent user stop latch prevents unexpected auto-revival until explicit user interaction.

### Scenario E: Service Process Death and LMK Reconciliation
- **Goal**: Verify Binder death recipient reconciliation when `:wakeword` process is killed.
- **Steps**:
  1. Start Aria with both Voice and Vision active.
  2. Query initial runtime state:
     `adb shell dumpsys activity service VoiceService`
     `adb shell content call --uri content://com.ariateam.aria.runtime.provider --method getState`
  3. Kill the `:wakeword` process to simulate OS Low Memory Killer (LMK):
     `adb shell pkill -9 -f com.ariateam.aria:wakeword`
  4. Observe main process logcat:
     - `AriaRuntimeBridge`: `"Voice process death detected via IBinder death recipient"`.
     - `localVoiceActive` immediately set to `false`.
     - `AriaUnifiedNotificationCoordinator`: Updates notification to vision-only status (`"카메라로 주변 위험을 모니터링 중입니다."`, stop label `"모니터링 중지"`).
  5. Verify no crash occurs in main process.
  6. Verify provider does not revive the killed service automatically.

---

## 4. Redacted Log and Timing Collection Procedure

When capturing device logs, use strict component tags and sanitize sensitive information:

```bash
# Capture filtered and bounded logcat
adb logcat -d -v time \
  AriaVisionPipeline:D \
  AriaRuntimeBridge:D \
  AriaUnifiedNotif:D \
  AriaUnifiedStopReceiver:D \
  HazardAlertCoordinator:D \
  *:S > ./evidence/pixel3/stabilization_run.log
```

### Log Redaction Checklist
Prior to committing or archiving any device log:
- [ ] Redact Google Gemini API keys (`AIzaSy...`).
- [ ] Redact device hardware serials (`ro.serialno`, MAC addresses, IMEI).
- [ ] Redact user account emails, Google Account IDs, or personal directory paths.
- [ ] Confirm no audio transcript fragments or private GPS coordinate data are embedded.

### Timing Snapshot Verification
Inspect the 8-stage pipeline metrics from `AriaRuntimeTimingTracker`:
- **Stage 1 (Start)**: Camera open to first frame latency.
- **Stage 2 (Recognition)**: ASR / wake-word latency.
- **Stage 3 (Analyzer)**: CameraX ImageAnalysis dispatch delay.
- **Stage 4 (Preprocess)**: Letterbox / rotation transform time.
- **Stage 5 (Invoke)**: Segmentation model inference latency.
- **Stage 6 (Post)**: Mask decoding and argmax time.
- **Stage 7 (UI)**: Main thread overlay publish time.
- **Stage 8 (Alerts)**: Hazard detection policy evaluation & dispatch time.
Ensure sample count, p50, and p95 percentiles are recorded without inventing hypothetical numbers.
