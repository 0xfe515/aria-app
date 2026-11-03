# Dashboard & Relative IMU Heading Validation Report

- Task ID: `ARIA-DASHBOARD-013` (`task_3707db0a99e7`)
- Dispatch ID: `ctx_8254bd02868d`
- Worktree: `C:\Users\felice\orca\aria-app`
- Date: 2026-11-04
- Target Device: Google Pixel 3 (Android 12, API 31, Build SP1A.210812.016.C2, serial `8B7X15A0F`)

---

## 1. Executive Summary

This task implements the enlarged, read-only Home vision dashboard with a truthful relative IMU heading indicator originating at its bottom center.

Key accomplishments:
1. **Enlarged Usable Area**: Replaced the constrained ~210..224dp x 140..150dp inset with an adaptively sized (~270..340dp wide, ~180..230dp tall in portrait; ~250..320dp wide, ~160..220dp tall in landscape) dashboard via `DashboardSizing`. It adapts to portrait/landscape, small screens, and large accessibility font scaling, while dynamically adjusting `homeTopInsetClearance` and `homeEndInsetClearance` so `VoiceDemoScreen` content is never occluded.
2. **Read-Only Dashboard Contract**: Removed all interactive control buttons and click handlers from the dashboard surface (`MainVisionInset`). Relocated required recovery actions (camera permission request, explicit camera restart, profile setup) to `DashboardHomeRecoveryBar` on the Home screen outside the dashboard and Settings screen, maintaining full recovery capabilities and voice command routing.
3. **Truthful Relative IMU Heading**:
   - Implemented `DashboardHeadingArrow` canvas overlay originating at bottom center `(width / 2, height - bottomInset)`.
   - Utilizes existing reliable `MotionState.relativeHeadingDeg` from `MotionFusionEngine`.
   - In Aria's coordinate frame, positive heading indicates left yaw, negative heading indicates right yaw, and 0° indicates calibrated forward.
   - Authoritative arrow rendering is strictly gated: only calibrated, fresh (sample age <= 1.5s), and non-degraded states with confidence >= 0.5f draw the directional arrow.
   - Stale, uncalibrated (`CALIBRATION_PENDING`), or degraded sensor states display truthful guidance text (e.g., "상대 방향: 보정 대기 중", "상대 방향: 신호 지연") and avoid drawing an authoritative arrow.
   - Clear Korean labels ("상대 방향") and TalkBack descriptions explicitly state that the heading represents relative device orientation, not physical walking trajectory or absolute compass north.
   - Comprehensive angle wrapping via `normalizeHeadingAngleDeg` safely binds heading to `[-180.0°, 180.0°]`.

---

## 2. Architecture & File Ownership

All changes adhere strictly to the exclusive ownership boundaries defined in the task specification:

| File Path | Role |
| :--- | :--- |
| `app/src/main/java/com/ariateam/aria/ui/screen/DashboardPolicy.kt` | Pure evaluation logic for heading normalization, truthful status evaluation (`evaluateHeadingPresentation`), and responsive dashboard sizing (`DashboardSizing`). |
| `app/src/main/java/com/ariateam/aria/ui/screen/DashboardHeadingArrow.kt` | Canvas composable drawing the bottom-center relative heading arrow, 0° baseline reference, observer origin anchor, and non-authoritative pending indicators with high-contrast outlines. |
| `app/src/main/java/com/ariateam/aria/ui/screen/DashboardRecoveryControls.kt` | Accessible `DashboardHomeRecoveryBar` providing relocated recovery actions on Home outside of the vision dashboard. |
| `app/src/main/java/com/ariateam/aria/ui/screen/MainVisionInset.kt` | Enlarged, strictly read-only dashboard layout integrating camera preview, segmentation overlay, bottom-center heading arrow, cadence/latency telemetry, and distance/heading readouts. |
| `app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt` | Home screen integration narrowly handling dynamic inset clearance calculations and hosting `DashboardHomeRecoveryBar`. |
| `app/src/main/res/values/dashboard_strings.xml` | Dedicated dashboard and recovery string resources. |
| `app/src/test/java/com/ariateam/aria/ui/screen/DashboardPolicyTest.kt` | JVM unit test suite covering angle wrapping, degraded reasons, reliable forward/left/right labeling, and responsive sizing. |
| `app/src/androidTest/java/com/ariateam/aria/ui/screen/DashboardHeadingUiTest.kt` | Instrumented test suite on Google Pixel 3 validating enlarged bounds, relative heading telemetry, read-only dashboard semantics, and relocated recovery buttons. |
| `docs/DASHBOARD_VALIDATION.md` | This verification report. |
| `.orca/drops/continuation/ARIA-DASHBOARD-013.md` | Task continuation checkpoint. |

Existing uncommitted changes from prior tasks (cloud questions, navigation, background camera session, black screen overlay, vision model compatibility) were strictly preserved without modification or regression.

---

## 3. Validation Matrix & Exact Exit Codes

All validations executed using the repository Gradle wrapper with Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`) on Windows:

| Command | Exit Code | Outcome | Details |
| :--- | :---: | :---: | :--- |
| `.\gradlew.bat :app:assembleDebug` | **0** | SUCCESS | Debug APK compiled and packaged cleanly. |
| `.\gradlew.bat :app:assembleDebugAndroidTest` | **0** | SUCCESS | AndroidTest APK compiled and packaged cleanly. |
| `.\gradlew.bat :app:testDebugUnitTest` | **0** | SUCCESS | All 359 local JVM tests passed with 0 failures, including `DashboardPolicyTest` (8 tests) and `MainVisionInsetTest` (11 tests). |
| `.\gradlew.bat :app:lintDebug` | **0** | SUCCESS | Android Lint passed with 0 errors and 0 warnings. |
| `.\gradlew.bat :app:connectedDebugAndroidTest` (`DashboardHeadingUiTest`) | **0** | SUCCESS | All 3 focused dashboard tests passed on physical Google Pixel 3 (Android 12). |
| `git diff --check` | **0** | SUCCESS | Zero whitespace errors or trailing spaces. |

---

## 4. Connected Device Test Detail (Google Pixel 3)

The instrumented test `DashboardHeadingUiTest` was executed directly on the attached Google Pixel 3 device (`8B7X15A0F`):

```xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="3" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-11-03T19:35:11" hostname="localhost">
  <testsuite name="com.ariateam.aria.ui.screen.DashboardHeadingUiTest" tests="3" failures="0" errors="0" skipped="0" time="3.374" timestamp="2026-11-03T19:35:11" hostname="localhost">
    <properties>
      <property name="device" value="8B7X15A0F" />
    </properties>
    <testcase name="dashboardDisplaysEnlargedBoundsAndRelativeHeadingTelemetry" classname="com.ariateam.aria.ui.screen.DashboardHeadingUiTest" time="1.384" />
    <testcase name="dashboardIsStrictlyReadOnlyWithoutControlButtons" classname="com.ariateam.aria.ui.screen.DashboardHeadingUiTest" time="0.916" />
    <testcase name="homeRecoveryBarProvidesRelocatedControlsWhenActionNeeded" classname="com.ariateam.aria.ui.screen.DashboardHeadingUiTest" time="1.074" />
  </testsuite>
</testsuites>
```

Observations on Device:
- `dashboardDisplaysEnlargedBoundsAndRelativeHeadingTelemetry`: Confirmed unclipped dashboard dimensions of width >= 250dp and height >= 170dp, display of "추정" tag, truthful "상대 방향: 좌측 15°" badge, and successful composition of `dashboardHeadingCanvas`.
- `dashboardIsStrictlyReadOnlyWithoutControlButtons`: Confirmed that in stopped/error states, the dashboard displays read-only informative status and that no control buttons (`mainVisionPermissionRecoveryButton`, `mainVisionRetryButton`, `mainVisionExplicitRestartButton`) exist inside the dashboard.
- `homeRecoveryBarProvidesRelocatedControlsWhenActionNeeded`: Confirmed that relocated recovery actions on Home render properly and respond to clicks for restarting monitoring and configuring body mount profile.

---

## 5. Non-Fabrication Notice

In accordance with repository guidelines:
- No walking claims or field trajectories are fabricated. The arrow strictly represents relative IMU device yaw orientation relative to forward calibration.
- Absolute compass headings (magnetic or true north) are never displayed or claimed.
- When sensors are uncalibrated or sample delays exceed tolerance (> 1.5s), the dashboard truthfully avoids authoritative directional arrows and reports guidance reasons.

---

## 6. Non-Finite Numeric Input Hardening (`ARIA-DASHBOARD-FINITE-014` / `task_cabe0844ffeb`)

During coordinator review of `ARIA-DASHBOARD-013`, a subtle numeric boundary edge case was identified:
- IEEE-754 floating point comparisons evaluate `Float.NaN < 0.5f` to `false` and `Float.NaN >= 0.5f` to `false`. Without explicit non-finite checks, `Float.NaN` could bypass confidence threshold guards.
- Furthermore, `normalizeHeadingAngleDeg` maps non-finite values (`Float.NaN`, `+Infinity`, `-Infinity`) to `0f` to protect graphics pipelines. If a non-finite heading bypassed evaluation, it could inadvertently render an authoritative forward-facing arrow (`0°`).

### 6.1 Changes Applied
1. **Explicit Non-Finite Rejection**:
   - `evaluateHeadingPresentation` explicitly checks `!motionState.relativeHeadingDeg.isFinite() || !motionState.confidence.isFinite()` before angle normalization or confidence thresholding.
   - If non-finite values are encountered, it fails closed:
     - `isReliable = false`
     - `isAuthoritativeArrowEligible = false`
     - `normalizedHeadingDeg = 0f`
     - `badgeText = "상대 방향: 센서 수치 이상"`
     - `guidanceReason = MotionDegradedReason.NON_FINITE_DATA.koreanMessage` (`"센서 수치 이상"`)
     - `talkBackDescription` contains non-walking guidance and diagnostic text.
2. **Confidence Range Contract**:
   - Added validation enforcing that `confidence` must be finite and within `[0.5f, 1.0f]` to be considered reliable.
   - Out-of-contract confidence values (`< 0.5f` or `> 1.0f`) fail closed with `isReliable = false`, `isAuthoritativeArrowEligible = false`, `badgeText = "상대 방향: 신뢰도 부족"`.
3. **Comprehensive Regression Coverage**:
   - Added `evaluateHeadingPresentation_nonFiniteInputsFailClosed` and `evaluateHeadingPresentation_confidenceRangeContract` to `DashboardPolicyTest.kt`.
   - Verified that NaN, +Infinity, and -Infinity on relative heading and confidence, as well as negative or >1.0 confidence, strictly fail closed without authoritative arrows or directional text.

### 6.2 Hardening Validation Matrix

| Command | Exit Code | Result | Scope Verified |
| :--- | :---: | :---: | :--- |
| `.\gradlew.bat :app:testDebugUnitTest` | **0** | SUCCESS | All unit tests passed (361 total, 10 `DashboardPolicyTest` tests). |
| `.\gradlew.bat :app:lintDebug` | **0** | SUCCESS | Android Lint passed cleanly with 0 errors and 0 warnings. |
| `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest` | **0** | SUCCESS | Debug and androidTest APKs compiled and packaged cleanly. |
| `git diff --check` | **0** | SUCCESS | Zero whitespace errors. |
