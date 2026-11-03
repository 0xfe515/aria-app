# Ground-Contact Distance Screen & Widget Integration Report

- **Date:** 2026-11-01
- **Task ID:** `ARIA-DISTANCE-WIDGET-002`
- **Target Repository:** `C:/Users/felice/orca/aria-app`
- **Executing Worker Agent:** Antigravity Worker (Gemini 3.8 Flash High)
- **Coordinator:** Codex (`term_4ddc7465-ec13-416c-a953-e5c7d0831a64`)

---

## 1. Executive Summary

This report documents the resolution of the user-reported distance measurement integration bug. Previously, the dedicated distance mode (`DistanceActivity`) was exclusively coupled to Google Play Services for AR (ARCore), requiring an ARCore `Session` and `RAW_DEPTH_ONLY` hardware support. Consequently, devices lacking Raw Depth (including the connected Pixel 3) failed to enter distance tracking, presenting unrecoverable error banners (`ERROR_AR_UNAVAILABLE`, `ERROR_DEPTH_UNSUPPORTED`). Furthermore, the existing CameraX-based `VisionPipelineController` ground-contact distance output (`GroundContactDistance`) only reached the small `MainVisionInset`, never populating the primary large distance widget on `DistanceScreen`.

To resolve this issue while keeping changes tightly bounded:
1. **Dedicated Monocular State Adapter (`GroundContactDistanceViewModel`):** Implemented an adapter bridging CameraX `VisionPipelineController` and `GroundContactDistanceEstimator` directly to `DistanceUiState`, feeding the large metric distance card without any ARCore or depth hardware gate.
2. **CameraX Lifecycle Ownership & Exclusive Handoff:** Replaced `GLSurfaceView` in `DistanceActivity` with a CameraX `PreviewView`, establishing `DistanceActivity` as the exclusive camera lifecycle owner while in foreground and cleanly returning camera control to `MainActivity` on pause/finish.
3. **Robust Gating & Stale Fencing:**
   - Numerical distance display is gated on running camera, valid body-mount profile, model readiness, sensor tracking reliability (`isSensorTracking`), positive finite distance, and frame freshness ($0 \le \text{age} \le 1.5\text{ s}$).
   - Periodic 300ms watchdog ages out stale measurements when camera frames stop.
   - Independent collection of `BodyMountProfileStore.profile` flow immediately clears stale values on any profile change.
   - Stop and recalibrate routines apply a monotonic timestamp fence (`minValidTimestampNanos`) to reject queued older frames.
4. **Honest Copy and Readiness Presentation:**
   - Added honest label (`"보행 복도 전방 추정 거리"`) above the large 56sp distance metric display.
   - Updated readiness card to honestly display monocular AI status, IMU sensor tracking status, and body mount profile configuration, completely eliminating false claims of floor detection or raw depth active in normal monocular mode.
   - Added direct `"키 설정"` button and dialog access to configure user stature without leaving the screen.
5. **Legacy Compatibility:** Preserved legacy AR depth classes (`DistanceViewModel`, `DepthDistanceEstimator`, `FloorPlaneTracker`, `RawDepthProcessor`, `DistanceRenderer`) and error states to maintain backward compatibility for existing tests.

---

## 2. Component Changes and Architecture

### 2.1 Monocular State Adapter (`GroundContactDistanceViewModel.kt`)
- Acts as the ViewModel for `DistanceActivity`.
- Owns a dedicated `VisionPipelineController` instance with existing background model restore flow.
- Collects `pipelineController.uiState` and processes updates through pure JVM helper `GroundContactDistanceEvaluator`.
- Collects `pipelineController.bodyMountProfileStore.profile` to immediately fence numeric output when the user updates height or position.
- Enforces existing distance thresholds:
  - Urgent: $d \le 1.50\text{ m}$ (`DepthDistanceEstimator.URGENT_THRESHOLD_METERS`)
  - Caution: $1.50\text{ m} < d \le 3.00\text{ m}$ (`DepthDistanceEstimator.CAUTION_THRESHOLD_METERS`)
  - Clear: $d > 3.00\text{ m}$ or corridor clear condition
- Alerts `DistanceAlertVibrator` with cooldown hysteresis on valid distance; cancels alerts immediately on null/degraded distance.

### 2.2 Activity & Camera Lifecycle (`DistanceActivity.kt`)
- Uses `GroundContactDistanceViewModel` and CameraX `PreviewView`.
- Requests CAMERA permission directly; sets `DistanceSetupStage.TRACKING` immediately when granted, avoiding all ARCore installation and capability checks.
- On pause and destroy: calls `viewModel.stopCamera()`, cleanly unbinding CameraX use cases before returning to `MainActivity`.
- Hosts `BodyMountProfileDialog` directly when triggered from `DistanceScreen`.

### 2.3 UI Presentation (`DistanceScreen.kt`)
- Added honest label `"보행 복도 전방 추정 거리"` (`R.string.distance_estimated_label`) above the large numeric widget.
- Added `"키 설정"` header button and clickable inline setup chip in `ReadinessCard`.
- In monocular mode (`isMonocularMode && !rawDepthActive`), displays:
  - Profile status: `"키·착용 위치 프로필 설정 완료"` / `"키·착용 위치 미설정 (탭하여 설정)"`
  - Sensor status: `"기기 기울기 및 중력 센서 정상"` / `"기기 센서 안정화 대기 중"`
  - AI pipeline status: `"지면 접촉 기반 거리 추정 동작 중"` / `"비전 파이프라인 및 센서 준비 중…"`
- Retains legacy AR depth card branches when `rawDepthActive == true` or error stages are set.

### 2.4 Resources & Copy (`distance_strings.xml`)
- Extended strings for monocular readiness, profile setup access, honest estimation label, and updated onboarding instructions emphasizing body mounting and stature configuration rather than stationary floor surface scanning.

---

## 3. Physical Limitations & Model Assumptions

1. **Horizontal Ground Plane Assumption:**
   - Monocular ground-contact estimation assumes the walking surface is planar and level at depth $h = \text{estimatedCameraHeight}$ below the camera lens. Slopes, curbs, stairs, or depressions systematically distort distance estimation.
2. **Adult Population Anthropometric Ratios:**
   - Eye/shoulder/chest heights are derived from AIST 2003 adult statistics (shoulder ratio: 0.8035428, chest ratio: 0.7164686). Individual deviations from standard proportions introduce proportional distance scale errors.
3. **Physical Walking Verification Status:**
   - Automated testing verifies synthetic pipeline integration, mathematical geometric models, lifecycle handoff, and UI rendering. Walking accuracy across physical targets (1m, 2m, 3m, 5m) remains **UNVERIFIED in physical test space** without physical walking harness calibration.

---

## 4. Verification Commands and Concrete Results

All validation commands executed using Android Studio JDK (`C:\Program Files\Android\Android Studio\jbr`) and repository Gradle wrapper:

| Validation Command | Target / Scope | Result | Details |
|---|---|---|---|
| `.\gradlew.bat :app:compileDebugKotlin` | Kotlin Compilation | **BUILD SUCCESSFUL** (24s) | Clean compilation with zero compiler warnings or errors. |
| `.\gradlew.bat :app:testDebugUnitTest` | JVM Unit Tests | **BUILD SUCCESSFUL** (19s) | 27 tasks; all unit tests passed, including `GroundContactDistanceEvaluatorTest` (10 test cases: urgent/caution/clear thresholds, 1.5s TTL expiry, pre-stop timestamp fencing, degraded sensor fencing, camera/model errors, non-finite values, and corridor clear conditions). |
| `.\gradlew.bat :app:lintDebug` | Android Lint | **BUILD SUCCESSFUL** (1m 58s) | 0 lint errors, 0 lint warnings across all modules. |
| `.\gradlew.bat :app:assembleDebug` | Debug APK Build | **BUILD SUCCESSFUL** (19s) | Debug APK packaged successfully. |
| `adb -s 8B7X15A0F devices` | Device Discovery | **DETECTED** | Physical Google Pixel 3 (Android 12, API 31, Build SP1A.210812.016.C2) connected. |
| `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.distance.DistanceActivitySmokeTest"` | Activity & Distance Screen UI Tests | **BUILD SUCCESSFUL** (38s) | 6 of 6 tests passed on Pixel 3 with 0 failures, 0 errors: <br>- `testSyntheticGroundContactValueReachesActualDistanceScreenMetricDisplay` (PASSED 1.174s)<br>- `testNormalDistanceEntryDoesNotGateOnARCore` (PASSED 1.851s)<br>- `testDistanceActivityLaunchAndPauseResume` (PASSED 1.823s)<br>- `testDistanceActivityManifestDeclaration` (PASSED 0.910s)<br>- `testDistanceScreenRendersTrackingState` (PASSED 0.668s)<br>- `testDistanceScreenRendersErrorState` (PASSED 0.651s) |
| `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.distance.BodyMountProfileUiTest,com.ariateam.aria.distance.MainVisionInsetUiTest"` | Distance Profile & Inset UI Tests | **BUILD SUCCESSFUL** (1m 2s) | 11 of 11 tests passed on Pixel 3 with 0 failures, 0 errors across profile storage and inset rendering. |

---

## 5. Conclusion

The actual user-reported integration bug has been completely resolved. Normal distance mode now operates seamlessly on CameraX monocular vision without gating on ARCore or Raw Depth hardware, and live `GroundContactDistance` outputs directly reach the primary large distance card on `DistanceScreen`.
