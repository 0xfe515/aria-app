# UI Overlay Correction and Restoration Report (ARIA-OVERLAY-RESTORE-018)

## Executive Summary
This document records the complete UI overlay correction for the Aria Android application on the Home screen.
Following explicit user feedback, the Home layout was restored to its original balanced composition, preventing the vision overlay from participating in layout measurement or pushing underlying controls. All interactive buttons and click handlers were strictly eliminated from the overlay subtree and Home screen. The relative direction indicator was corrected to exactly ONE plain solid line originating from the horizontal center at the bottom of the actual preview area near the user's implied position, without outlines, multi-strokes, arrowheads, or decorative geometry.

## 1. Problem Analysis & User Feedback Addressed
1. **Displaced Home Controls**: The previous iteration calculated dynamic clearances (`measuredInsetHeightDp + measuredRecoveryHeightDp + 16.dp`) and applied them as top and end padding to `VoiceDemoScreen`. This severely compressed and displaced the established Home components (`HomeHeader`, `PowerControl`, `HomeStatusInfo`, `TapHintPill`, `HomeActionButtons`).
2. **Intrusive Recovery Controls**: A newly added `DashboardHomeRecoveryBar` was placed directly onto the Home screen beneath the overlay, cluttering the primary user interface.
3. **Overly Complex Direction Visualization**: The direction indicator previously rendered multi-stroke outlines, dashed baseline guides, observer anchor circles, pending calibration rings, and multi-stroke arrowheads, and shifted its origin upward to 58% of the height into the middle of the image.
4. **Interactive Controls in Overlay**: The overlay previously included clickable surfaces or implied interactive recovery.

## 2. Implemented Architecture & Visual Contracts

### 2.1 Preserved Original Home Layout & Floating Overlay
- **No Measurement Participation**: `VoiceDemoScreen` in `MainScreen.kt` has its padding restored to `Modifier.fillMaxSize().padding(bottom = navSafeBottomPadding + transcriptContentClearance)`. It no longer includes any dynamic inset clearances.
- **Floating Alignment**: `MainVisionInset` sits in the root `Box` aligned to `Alignment.TopEnd` with `padding(top = 8.dp, end = 12.dp)`. It floats above the Home screen without affecting sibling layout measurement.
- **Adaptive Proportions**: `DashboardSizing` restores established proportions (~224dp x 150dp portrait, ~210dp x 140dp landscape). If screen width is constrained or accessibility font scale is increased (e.g. > 1.35x), the overlay adaptively shrinks to prevent occluding underlying controls.

### 2.2 Direction Representation: Exactly ONE Plain Solid Line
- **Bottom-Center Origin**: `HeadingArrowGeometryCalculator` strictly calculates:
  - `originX = width / 2f` (horizontal center)
  - `originY = (height - strokeInset).coerceAtLeast(0f)` (bottom of the preview area near user's implied position with minimal stroke inset)
  - Origin is never relocated into the middle of the preview area.
- **Strictly Single Stroke**: Rendered via `DashboardHeadingArrow` using exactly ONE `drawLine`:
  - Solid color: `AriaGreen` for straight forward (`< 3°`), `AriaSky` for lateral yaw.
  - `strokeWidth = 3.5.dp`, `cap = StrokeCap.Round`.
  - No shadow outlines, multi-strokes, arrowheads, wings, baseline guides, circles, rings, or anchors.
- **Truthful Visibility & Rear Handling**:
  - If sensor data is uncalibrated, stale (> 1.5s), low confidence (< 0.5), or camera is stopped, the line is hidden (`isVisible = false`).
  - Rearward directions (`|angle| > 90°`) naturally hide the line (`isVisible = false`), leaving communication to the non-interactive status text badge ("상대 방향: 좌측 135°").

### 2.3 Strict Read-Only Contract & Settings Recovery
- **Zero Overlay Actions**: All click listeners, button semantics (`Role.Button`), and action rows are eliminated from `MainVisionInset`. Status and guidance are presented as informative read-only text.
- **Intrusive Home Controls Removed**: `DashboardHomeRecoveryBar` is completely removed from the Home screen.
- **Settings Recovery Access**: `SettingsScreen.kt` receives `onOpenAppSettings = openSettings` in `DiagnosticsSettings`, allowing users to manage camera permissions and system settings through the standard Settings surface without Home row intrusion.

## 3. Test Coverage & Empirical Validation

### 3.1 Local JVM Tests (`:app:testDebugUnitTest`)
- `DashboardPolicyTest.kt`:
  - `dashboardSizing_responsiveDimensions`: Verifies restored ~224dp x 150dp (portrait) and ~210dp x 140dp (landscape) dimensions and shrinking on large font scale.
  - `headingArrowGeometry_rearFacingAnglesNaturallyHide`: Verifies origin is at bottom center and line naturally hides on rearward angles.
  - `headingArrowGeometry_forwardAndLateralAnglesAreVisibleAndBounded`: Verifies origin at bottom center and visible bounded ray for angles in [-90°, 90°].
  - `headingArrowGeometry_ineligibleHidesLine`: Verifies line is hidden when uncalibrated or ineligible.
  - `dashboardSizing_extremeAccessibilityFontScale`: Verifies overlay shrinkage at fontScale 2.0x.
  - Result: All tests PASSED (exit code 0).

### 3.2 Connected Device Tests on Pixel 3 (`:app:connectedDebugAndroidTest`)
Executed directly on connected Google Pixel 3 (Android 12, device ID `8B7X15A0F`):
- `DashboardHeadingUiTest`:
  1. `dashboardDisplaysRestoredBoundsAndRelativeHeadingTelemetry`: PASSED (bounds within 180..240dp width, 120..170dp height).
  2. `dashboardIsStrictlyReadOnlyWithoutControlButtons`: PASSED (no buttons or clickable nodes in overlay).
  3. `overlayDoesNotShiftSiblingHomeComponents`: PASSED (exact 0-pixel shift on sibling Home components with overlay present vs absent).
  4. `directionLineOriginIsAtBottomCenterAndHasNoExtraGeometry`: PASSED (origin strictly at bottom-center; rearward and invalid states hide line).
  5. `settingsVisionRecoveryBarFunctionsWhenActionNeeded`: PASSED.
- `BlackScreenOverlayTest`:
  1. `overlayConsumesTouchAndLeavesUnderlyingSessionComposed`: PASSED.
- Result: **6 tests passed, 0 failures** (Time: 5.7s).

### 3.3 Visual & Hierarchy Inspection on Pixel 3
Inspected actual Pixel 3 UI layout dump:
- `ARiA` title: `[438,240][643,323]`
- `SYSTEM ACTIVE`: `[398,323][682,400]`
- `PowerControl` button: `[280,530][801,1051]` (properly centered, ~521px diameter)
- `Status message`: `[221,1182][859,1237]`
- `Tap hint pill`: `[258,1367][823,1471]`
- `Stop button`: `[100,1601][980,1733]`
- `FloatingTabBar`: `[210,1917][870,2083]`
- No intrusive Home recovery bars; no component displacement.

### 3.4 Build & Lint Verification
- `:app:assembleDebug`: BUILD SUCCESSFUL (code 0).
- `:app:lintDebug`: BUILD SUCCESSFUL (code 0, 0 errors).
- `:app:assembleDebugAndroidTest`: BUILD SUCCESSFUL (code 0).
- `git diff --check`: Clean (code 0).
