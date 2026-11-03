# Ground Contact Distance Feature Audit and Validation Report

- **Date:** 2026-11-01
- **Task ID:** `ARIA-GROUND-VERIFY-001`
- **Target Repository:** `C:/Users/felice/orca/aria-app`
- **Executing Worker Agent:** Antigravity Worker (Gemini 3.8 Flash High)
- **Coordinator:** Codex (`term_4ddc7465-ec13-416c-a953-e5c7d0831a64`)

---

## 1. Executive Summary

This report documents the architectural and implementation audit of Aria's body-mounted monocular ground-contact distance estimation system. The existing pipeline—spanning `BodyMountProfile`, `GroundContactDistanceEstimator`, `GroundContactMotionTracker`, `CameraGeometry`, `VisionPipelineController`, and `MainVisionInset`—was thoroughly inspected against geometric models, sensor synchronization contracts, lifecycle boundaries, and UI presentation criteria.

Per coordinator guidance and task constraints:
1. **Intentionally Rejected Conditions:** Bottom-clipped obstacle masks (`maxY >= height - 2`) and unsupported floating masks are intentionally excluded from ground-contact distance calculation because the true physical intersection with the ground plane is either occluded by the field-of-view boundary or ungrounded.
2. **Preservation of Core Contracts:** Stature-to-lens height estimation ratios (`0.8035428` shoulder, `0.7164686` chest), fixed +/-0.45m walking corridor, target object classes (person=4, vehicle=5, pole=6), and pure stature input (no measured height input) are strictly maintained.
3. **No Speculative Rewrites:** No production defect was established by this focused audit. Passing tests and verified contracts were preserved without unnecessary code churning.
4. **Physical Measurement Classification:** Physical 1m, 2m, 3m, 5m walking/stationary tests are explicitly classified as **Unverified in Physical Space** (headless execution without physical walking harness; no fabricated accuracy values).

---

## 2. In-Depth Component Audit Findings

### 2.1 Body Mount Profile & Stature Height Model (`BodyMountProfile.kt`)
- **Anthropometric Ratios:** Derived from AIST 2003 adult body dimension statistics:
  - Shoulder ratio: `0.8035428f` ($127680.3\text{ mm} / 158896.7\text{ mm}$).
  - Chest ratio: `0.7164686f` ($113844.5\text{ mm} / 158896.7\text{ mm}$).
- **Input Boundaries:** Strictly validates stature $120\text{ cm} \le \text{stature} \le 220\text{ cm}$ with finite check. Disallows zero, negative, NaN, infinite, or out-of-range inputs.
- **Privacy & Safety:** Height is estimated exclusively from stature and mount location; no measured height input is exposed or accepted.
- **Persistence (`BodyMountProfileStore`):** Uses dedicated `SharedPreferences` (`aria_body_mount_profile`). Defaults to `null` until user explicitly configures stature, ensuring no unverified default height is assumed.

### 2.2 IMU Sensor Alignment & Motion Tracking (`GroundContactMotionTracker.kt`)
- **Coordinate Transformation:** Accurately transforms raw Android accelerometer and gyroscope coordinates into upright camera space across all display orientations:
  - `ROTATION_0`: $(a_x, -a_y, -a_z)$
  - `ROTATION_90`: $(-a_y, -a_x, -a_z)$
  - `ROTATION_180`: $(-a_x, a_y, -a_z)$
  - `ROTATION_270`: $(a_y, a_x, -a_z)$
- **Magnetometer-Free Design:** Operates purely on gravity reaction force, eliminating magnetic declination errors and indoor magnetic distortion.
- **Low-Pass Gravity Filter:** Implements an exponential moving average (IIR) filter with time constant $\tau = 0.20\text{ s}$ to attenuate walking stride tremors.
- **Dynamic Rejection & Fault Latching:**
  - Freefall rejection: Accelerometer magnitude $< 4.5\text{ m/s}^2$.
  - Extreme shock rejection: Accelerometer magnitude $> 16.5\text{ m/s}^2$.
  - Rapid motion rejection: Gyroscope angular velocity $> 45^\circ/\text{s}$ triggers a $200\text{ ms}$ hold that marks orientation degraded (`"급격한 기기 움직임 감지"`).
  - Stale sensor tolerance: Rejects samples with timestamp latency $> 200\text{ ms}$ relative to the camera capture frame (`"센서 데이터 지연"`).
- **Session Lifecycle Isolation:** Listener generation monotonic counter fences out delayed callbacks across sensor stop/start cycles.

### 2.3 Ray Geometry & Plane Intersection (`CameraGeometry.kt`)
- **Sensor Modeling:** Reads physical focal length and active sensor dimensions from Camera2 metadata. Computes uncropped upright sensor dimensions for both portrait and landscape orientations.
- **Full Upright Optical Axis Mapping (`FullUprightCameraModel`):** Correctly maps full-frame normalized upright coordinates $(u, v) \in [0, 1] \times [0, 1]$ directly to physical rays without assuming symmetric crop:
  $$x = (u - 0.5) \cdot \frac{W_{\text{sensor}}}{f}, \quad y = (v - 0.5) \cdot \frac{H_{\text{sensor}}}{f}, \quad z = 1.0$$
- **Ground Plane Intersection:**
  - Enforces ray depression angle: Rejects rays within $1^\circ$ of or above the horizon ($\text{denom} \ge -0.0175$).
  - Bounded walking ground distance: Validates forward distance $0.1\text{ m} < d_{\text{fwd}} \le 35\text{ m}$ and 3D ray distance $0.2\text{ m} < t \le 50\text{ m}$.
  - Computes orthogonal forward and lateral planar distances on the ground plane relative to the camera nadir point.

### 2.4 Monocular Distance Estimation (`GroundContactDistanceEstimator.kt`)
- **Semantic Target Extraction:** Inspects semantic classes: person (4), vehicle (5), and pole/sign (6).
- **Connected Component Analysis:** Breadth-first search clusters contiguous target pixels with `minClusterPixels = 20`.
- **Bottom Boundary Clipping:** Flags components with $y_{\max} \ge \text{height} - 2$ as boundary-clipped (`isBottomClipped = true`).
- **Ground Support Gating:** Analyzes 4 rows immediately beneath the component bottom ($y_{\max} + 1 \dots \min(\text{height} - 1, y_{\max} + 4)$) across road (1), sidewalk (2), and crosswalk (3). Objects floating in the top 35% of the frame or lacking $\ge 3$ ground pixels are flagged as floating (`isFloatingUnsupported = true`).
- **Walking Corridor Filtering:** Strictly enforces lateral threshold $|d_{\text{lat}}| \le 0.45\text{ m}$ to focus exclusively on the user's forward walking path.
- **Nearest Selection & Temporal Filter:** Selects candidate with minimum forward distance in the corridor. Employs a 2-frame alpha smoothing filter ($0.5 \cdot \text{prev} + 0.5 \cdot \text{raw}$) when consecutive delta $< 1.2\text{ m}$.
- **Degraded Condition Clearing:** Resets smoothing filter and clears numeric distance whenever sensor clock is non-realtime, orientation is unreliable, corridor is clear, or contact is clipped/unsupported.

### 2.5 Pipeline Integration & Inset UI (`VisionPipelineController.kt`, `MainVisionInset.kt`)
- **Lifecycle Invalidation:** Immediately resets estimator filter upon camera stop/start, profile modification, display rotation change, and preprocessing strategy changes.
- **UI Freshness TTL Gate:** Rejects measurements older than $1.5\text{ s}$ ($1,500,000,000\text{ ns}$), expiring stale distances if camera streaming halts.
- **Accessibility & Guidance:** Provides prominent Korean visual badge and TalkBack accessibility content descriptions (`"추정 약 X.X미터"`, `"키·착용 위치 설정"`, `"비전 중지됨"`, `"거리 확인 중"`).

---

## 3. Explicit Model Assumptions and Physical Limitations

1. **Flat Horizontal Ground Plane Assumption:**
   - The estimator assumes the walking surface directly beneath the user and in front of the user is a single, continuous, planar horizontal surface at depth $h = \text{lensHeight}$ below the camera lens.
   - Any departure from this assumption—such as ascending or descending staircases, ramps, roadside curbs, steep hills, potholes, or subway escalators—causes systematic over- or under-estimation of distance. The system accurately marks out-of-horizon and out-of-bounds rays as invalid rather than returning fabricated distance numbers.
2. **Fixed Anatomical Ratio Assumption:**
   - Lens height is derived from standard adult population ratios. Users with non-standard torso-to-leg proportions, thick winter footwear, or slouched postures may experience proportional distance scale error; its magnitude has not been measured.
3. **Physical Distance Calibration Status:**
   - Stationary and dynamic walking distance measurements at 1m, 2m, 3m, and 5m targets are categorized as **UNVERIFIED in physical test space** due to headless automation execution constraints. Synthetic geometric tests validate the tested mathematical cases, not physical measurement accuracy.

---

## 4. Verification Commands and Concrete Results

All validation commands were executed using the Android Studio JDK (`C:\Program Files\Android\Android Studio\jbr`) and repository Gradle wrapper:

| Validation Command | Scope | Result | Details |
|---|---|---|---|
| `.\gradlew.bat :app:testDebugUnitTest` | Unit Tests | **BUILD SUCCESSFUL** | All unit tests passed (including `GroundContactDistanceEstimatorTest`, `GroundContactMotionTrackerTest`, `CameraGeometryTest`, `BodyMountProfileTest`). |
| `.\gradlew.bat :app:lintDebug` | Android Lint | **BUILD SUCCESSFUL** | 0 lint errors, 0 lint warnings in distance/geometry/vision modules. |
| `.\gradlew.bat :app:assembleDebug` | Debug APK Build | **BUILD SUCCESSFUL** | Debug APK compiled and packaged cleanly. |
| `adb -s 8B7X15A0F devices` | Device Discovery | **DETECTED** | Physical Google Pixel 3 (Android 12, API 31, Build SP1A.210812.016.C2) discovered. |
| `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.distance.BodyMountProfileUiTest"` | Instrumented UI Tests | **BUILD SUCCESSFUL** | 6 of 6 Compose UI interaction tests passed on Pixel 3 device in 42s. |
| `.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.distance.MainVisionInsetUiTest,com.ariateam.aria.distance.DistanceActivitySmokeTest"` | Inset & Activity Tests | **BUILD SUCCESSFUL** | Retained XML independently confirms 5 MainVisionInsetUiTest cases with zero failures/errors. DistanceActivitySmokeTest execution is not established by that XML. |

---

## 5. Conclusion

The existing implementation of body-mounted real-time nearest ground-contact obstacle distance estimation passed the checks described above; real walking accuracy remains unverified. Per coordinator review and confirmed system design, bottom-clipped rejection is intentional and necessary for user safety. No speculative rewrites were made; all existing dirty and untracked vision training files were preserved intact.


## Coordinator verification
Independent wrapper run of :app:assembleDebug :app:testDebugUnitTest :app:lintDebug succeeded. No app source changes were made. Physical walking accuracy remains unverified.
