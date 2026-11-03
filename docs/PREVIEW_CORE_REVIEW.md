# Aria Outdoor Preview Core Review (2026-11-04)

## Executive Summary
This document provides a comprehensive verification and review report for the Aria Android application ahead of the 2026-11-04 physical outdoor walking preview test. The review covers the 7 core functional areas (lifecycle/rebinding/foreground permissions, frame lifecycle and snapshot closure, screen reveal/blackout policies, motion and sensor sanity guards, heading geometry arrow clipping, adaptive layout/font scaling, and recovery resilience), plus the final integrated Cloud Question coordinator fix.

All required checks and physical device instrumented tests passed on a physical Google Pixel 3 (Android 12, API 31). The build configuration was updated to `versionCode = 2`, `versionName = "1.0.0-preview.20261104"`. The debug-signed build (`app-debug.apk`) is the verified, installable preview artifact.

---

## 1. Core Architectural & Functional Review

### 1.1 Lifecycle, Rebinding & Foreground Permissions
- **Foreground Service Architecture**: `VisionForegroundService` is declared in `AndroidManifest.xml` with `android:foregroundServiceType="camera"`. Permission `android.permission.FOREGROUND_SERVICE_CAMERA` is properly gated and checked before service start.
- **Service & Activity Rebinding**: `VisionPipelineController` binds to `VisionSession` and manages analyzer attachments. If the UI activity enters the background (e.g., Home or Settings navigation), `VisionBackgroundSessionTest` confirms that the background camera session remains active without rebinding churn until explicitly stopped.
- **Microphone & Speech Lifecycle**: Wake-word recognition (`SherpaAriaWakeWordRecognizer`) and `IVoiceService` run independently of camera foreground state, allowing voice navigation and voice commands even while camera analysis continues.

### 1.2 Snapshot Allocation & Frame Closure
- **Deterministic Frame Closure**: In `LiteRtVisionSegmenter` and `VisionPipelineController`, `imageProxy.close()` is placed strictly within `finally` blocks, ensuring no camera buffer exhaustion or pipeline starvation even when frame preprocessing throws.
- **Buffer Reuse**: Pre-allocated ARGB arrays and direct buffers are reused across successive frames, preventing excessive garbage collection pauses during real-time segmentation.
- **UI Bitmap Throttling**: Bitmaps delivered to the Compose UI for inset presentation are throttled to a 250ms cadence and explicitly recycled upon newer frame availability.
- **Disposal Safety**: When the vision pipeline is closed or stopped, all pending frame callbacks fail safe by returning `null`, triggering immediate cleanup.

### 1.3 Screen Hide & Reveal Policy
- **Idle Timeout & Black Screen**: `BlackScreenPolicy` enforces a 30-second user inactivity timer. Once expired, `BlackScreenOverlay` covers the screen with pure black (`Color.Black`) to minimize OLED battery draw and OLED burn-in during walking.
- **Wake-Word & Audio Preservation**: While the screen is blacked out, wake-word listening ("헤이 아리아") remains fully active. Background audio cues and telemetry continue without interruption.
- **Multi-Modal Reveal**: The display instantly reveals upon user screen tap, wake-word detection, or critical actionable hazard/system warnings.
- **Instrumentation Verified**: `BlackScreenOverlayTest` verifies that the overlay consumes touch events and maintains the underlying composed session state.

### 1.4 Invalid Sensor Data & Degraded State Guards
- **Sanity Checks in Motion Tracking**: `MotionTracker`, `MotionFusionEngine`, and `GroundContactMotionTracker` rigorously validate incoming sensor values against `Float.isNaN()`, `Float.isInfinite()`, and unrealistic shock thresholds.
- **Safe Fallback**: When sensor readings are non-finite, stale (> 1000ms), or anomalous, motion tracking smoothly degrades to neutral velocity and stationary confidence rather than propagating corrupted heading or step counts.
- **Sensor Rate Throttling**: Accelerometer and gyroscope data streams are bounded to avoid flooding the main event thread.

### 1.5 Relative Heading Arrow Geometry (Defect Identified & Resolved)
- **Defect Identified**: Previously, the relative heading arrow's pivot origin was pinned directly to the bottom inset baseline (`originY = height - bottomInset`). For rear-facing relative angles (-90° to -180° and +90° to +180°), the calculated vector pointed downwards (`dy > 0`), clipping up to 50dp past the canvas bottom and overlapping the telemetry text bar.
- **Root Cause**: Absence of bounding calculation for downward-pointing vectors with fixed origin placement.
- **Solution Implemented**:
  - Authored `HeadingArrowGeometry` and `HeadingArrowGeometryCalculator` in `DashboardPolicy.kt`.
  - Re-anchored the arrow origin at `availableHeight * 0.58f`, providing ample clearance for both forward and reverse headings.
  - Implemented 4-sided ray-bounding to clamp arrowhead endpoints and wings strictly within the canvas boundaries `[padding, width - padding] x [padding, availableHeight]`.
  - Updated `DashboardHeadingArrow.kt` and `MainVisionInset.kt` to use the bounded geometry.
- **Regression Verification**: `DashboardPolicyTest` verifies zero boundary clipping across all 360 degrees in 1-degree increments. `DashboardHeadingUiTest` passed on the physical Pixel 3.

### 1.6 Adaptive Layout & Font Scale Resilience
- **Dynamic Sizing**: In `DashboardPolicy.kt`, `DashboardSizing` dynamically adjusts dashboard layout dimensions for accessibility font scales up to 2.0x.
- **Height Expansion**: Portrait height smoothly scales from 170dp up to 240dp when system font scaling exceeds 1.25x.
- **Telemetry Inset Accommodation**: In `MainVisionInset.kt`, dynamic bottom inset padding (up to 44dp) is passed to `DashboardHeadingArrow`, ensuring text never obscures the direction arrow.

### 1.7 Restart, Permission & Profile Recovery
- **Permission Revocation Handling**: If camera or microphone permissions are revoked while the app is active, `MainActivity` and `MainScreen` gracefully transition to an explicit permission request prompt without crashing.
- **Recovery Controls**: `DashboardRecoveryControls` provides clear actionable prompts to re-grant permissions or restart services if a hardware fault occurs.
- **Instance State Preservation**: Core state variables (active voice session, current heading, hazard indicators) are retained across configuration changes and process recreations.

---

## 2. Integrated Cloud Question Coordinator Review

### 2.1 Double-Dispatch Cancellation Race Fix
- **Codex Direct Review Finding**: In `CloudQuestionCoordinator.kt`, when `askPhotoQuestion` launched a coroutine on `mainDispatcher`, `request.capturedBitmap` was extracted into local `workingBitmap` (clearing `request.capturedBitmap = null`). Then `withContext(ioDispatcher)` was invoked. If the request was cancelled or the coordinator disposed while `ioDispatcher` was queued (prior to IO block execution), `withContext` was cancelled before entering the block. The previous inner `try ... finally { safeRecycle(workingBitmap) }` was inside the IO block, causing `workingBitmap` to leak un-recycled.
- **Fix Applied**: Wrapped `withContext(ioDispatcher)` in an outer `try { withContext(ioDispatcher) { ... } } finally { safeRecycle(workingBitmap) }`.
  - If cancelled before entering IO, the outer `finally` immediately recycles `workingBitmap`.
  - If IO is executing `compressBitmapToBase64(workingBitmap)`, the inner `try ... finally` recycles `workingBitmap` immediately upon compression completion.
  - While compression is actively running on IO, the outer `finally` does not execute, guaranteeing no concurrent recycling during encoding.
- **Regression Tests Added & Verified**:
  - `CloudQuestionDeviceTest.cancelledQuestionWhenMainRunsLaunchButIoQueuedRecyclesRealBitmapAndEmitsNoCallback`: Verifies real Android Bitmap is recycled when cancelled at the IO dispatch boundary and no API call is made.
  - `CloudQuestionDeviceTest.disposedCoordinatorWhenMainRunsLaunchButIoQueuedRecyclesRealBitmapAndEmitsNoCallback`: Verifies real Android Bitmap is recycled on disposal at the IO dispatch boundary and no API call is made.
  - Both tests passed on the physical Pixel 3 device (10/10 tests passing in `CloudQuestionDeviceTest`).

---

## 3. Build Configuration & Signing Policy

### 3.1 Version Increment
In `app/build.gradle.kts`:
- `versionCode`: Incremented from `1` to `2`.
- `versionName`: Updated from `"1.0"` to `"1.0.0-preview.20261104"`.

### 3.2 Signing Inspection & Artifact Verification
- **Release Signing**: No production keystore or release `signingConfigs` exist in the repository (following security hygiene rules). Consequently, `:app:assembleRelease` produces an unsigned APK:
  - Path: `app/build/outputs/apk/release/app-release-unsigned.apk`
  - Size: 399,294,584 bytes (~380.8 MB)
  - SHA-256: `0BB6213C9617976E033B2C36D2C2EAAFD61C798319FD662A70902C67D03A6AE8`
  - Status: Valid build, but unsigned (not installable on devices). **Must not be uploaded/published as an installable release.**
- **Debug Preview Signing**: Built using standard Android debug keystore:
  - Path: `app/build/outputs/apk/debug/app-debug.apk`
  - Size: 411,201,336 bytes (~392.1 MB)
  - SHA-256: `4CE394796983E87B39E75C59C946FC834904F9A1F516DB5492751471AF8F83AB`
  - Status: **Signed and installable on physical Android devices.** This is the verified preview artifact for tomorrow's outdoor test.

---

## 4. Verification & Test Evidence Summary

| Command / Suite | Exit Code | Scope / Class Counts | Status |
|:---|:---:|:---|:---:|
| `git diff --check` | 0 | Clean diff across all files | PASSED |
| `:app:lintDebug` | 0 | Android Lint clean report generated | PASSED |
| `:app:testDebugUnitTest` | 0 | 374 JVM unit tests executed, 0 failures, 0 skipped | PASSED |
| `:app:assembleDebug` | 0 | Build debug APK (`app-debug.apk`) | PASSED |
| `:app:assembleRelease` | 0 | Build release APK (`app-release-unsigned.apk`) | PASSED |
| `:app:assembleDebugAndroidTest` | 0 | Build instrumentation test APK | PASSED |
| `:app:connectedDebugAndroidTest` (`DashboardHeadingUiTest`) | 0 | 3/3 tests passed on physical Pixel 3 | PASSED |
| `:app:connectedDebugAndroidTest` (`CloudQuestionDeviceTest`) | 0 | 10/10 tests passed on physical Pixel 3 | PASSED |
| `:app:connectedDebugAndroidTest` (`BlackScreenOverlayTest`) | 0 | 1/1 tests passed on physical Pixel 3 | PASSED |
| `:app:connectedDebugAndroidTest` (`VisionBackgroundSessionTest`) | 0 | 3/3 tests passed on physical Pixel 3 | PASSED |
| `:app:connectedDebugAndroidTest` (`ExternalNavigationIntegrationTest`) | 0 | 4/4 tests passed on physical Pixel 3 | PASSED |
| `:app:connectedDebugAndroidTest` (`CommandSnapshotBundleTest`) | 0 | 4/4 tests passed on physical Pixel 3 | PASSED |
| **Total Connected Tests** | **0** | **25/25 tests passed on Pixel 3 (100% success)** | **PASSED** |

---

## 5. Truthful Hardware & Environment Limitations for Outdoor Testing

1. **Pixel 3 Sensor Noise & Outdoor Brightness**:
   - The test device is a Google Pixel 3 (Android 12, Snapdragon 845). In bright outdoor direct sunlight, auto-exposure transitions can cause temporary high-contrast saturation in the camera feed.
   - Ground contact distance evaluation relies on monocular camera pitch/IMU orientation; uneven outdoor terrain (grass, gravel, slopes) will yield greater distance variance than flat indoor flooring.
2. **Offline Speech Recognition Model**:
   - On-device speech recognition utilizes Sherpa-ONNX Zipformer Korean model (`sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile`). Outdoor ambient noise (wind, traffic) may reduce wake-word sensitivity; speaking clearly into the microphone is recommended.
3. **Cloud Gemini API Connectivity**:
   - Cloud photo and text questions require active cellular data or Wi-Fi connectivity and a valid Gemini API key registered in Settings. Without network connectivity or a key, the app gracefully falls back to clear Korean diagnostic messages.
4. **Samsung Galaxy / Non-Pixel Hardware**:
   - Connected tests were performed exclusively on Google Pixel 3 (`8B7X15A0F`). Vendor-specific camera optimizations or aggressive background process limits on Samsung Galaxy or OEM ROMs may require disabling battery optimization for continuous background vision sessions.
