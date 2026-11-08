# Binary Vision Display & Unified Presentation Overlay (2026-11-08)

## 1. Executive Summary

This document describes the unified binary presentation architecture for Aria's vision semantic segmentation overlays. To maximize accessibility, clarity, and safety for low-vision and visually impaired users:
- All walkable surfaces (sidewalk, crosswalk, and crosswalk gap-filled pavement) are rendered in **GREEN** (`0x8039FF39`).
- All non-walkable surfaces, obstacles, road pavement, and background are rendered in **RED** (`0x80FF3939`).
- Both colors share an identical alpha channel (`0x80`), eliminating transparent holes inside the active image area.
- Raw model inference outputs (7-class probability tensors, semantic class masks, class counts, corridor walkable ratios, dominant obstacle IDs, depth fusion, and hazard assessments) remain **100% byte-for-byte identical**.

---

## 2. Centralized Palette & Contract Specification

In `VisionContract.kt`:
- `OVERLAY_COLOR_GREEN`: `0x8039FF39.toInt()`
- `OVERLAY_COLOR_RED`: `0x80FF3939.toInt()`
- Class Mapping:
  - Class 0 (`other` / background): `OVERLAY_COLOR_RED`
  - Class 1 (`road`): `OVERLAY_COLOR_RED`
  - Class 2 (`sidewalk`): `OVERLAY_COLOR_GREEN`
  - Class 3 (`crosswalk`): `OVERLAY_COLOR_GREEN`
  - Class 4 (`person`): `OVERLAY_COLOR_RED`
  - Class 5 (`vehicle`): `OVERLAY_COLOR_RED`
  - Class 6 (`pole_sign`): `OVERLAY_COLOR_RED`
  - Any unknown / invalid class index: `OVERLAY_COLOR_RED`

Upstream model architecture, class order, and walkable flags are preserved identically:
- `walkable = true` for classes 2 and 3.
- `walkable = false` for classes 0, 1, 4, 5, 6.

---

## 3. Display Processing Pipeline & Component Noise Filtering

### Legacy Issue
The legacy `OverlayComponentFilter` suppressed small components (< 32 pixels) by setting `overlayArgb[i] = 0x00000000` (`TRANSPARENT`), creating transparent holes in both green walkable areas and red road/obstacle regions.

### Binary Pipeline Behavior
1. **Binary Base Initialization**: Every output pixel is initialized from `classMask`: classes 2 and 3 become GREEN; all other classes and out-of-range labels become RED.
2. **Crosswalk Supplementary Gap Filling**:
   When `CrosswalkRegionEstimator` is provided, candidate zebra stripes are clustered into crossing envelopes. Inter-stripe road/background gaps inside the envelope are filled with GREEN. Known obstacles (classes 4, 5, 6) and invalid IDs strictly remain RED obstacle holes.
3. **Component Noise Filtering (`OverlayComponentFilter`)**:
   - Only connected components of GREEN pixels are evaluated.
   - If a green component has fewer than `minimumComponentPixels` (default 32), it is identified as noise/speckle and suppressed to RED (`OVERLAY_COLOR_RED`).
   - Red pixels (obstacles, road, background) are never suppressed to transparent or turned green; obstacles remain RED even when tiny (1 pixel).
   - Zero transparent pixels exist inside the valid image grid.

### Crosswalk Geometry Approximation
`CrosswalkRegionEstimator` acts as a conservative presentation supplement. It bridges bounded inter-stripe gaps between validated, elongated crosswalk zebra components while strictly preserving obstacle holes (classes 4, 5, 6). While perspective geometry and extreme angles may cause the supplementary estimator alone to reject highly distorted or isolated stripes, raw model crosswalk stripes (class 3) always remain GREEN via base mapping. The filled envelope is a bounded display approximation designed to aid visual scanning without altering model semantics or ground-truth walkability metrics.

---

## 4. Route Inventory Audit

An exhaustive audit of all segmentation overlay consumers was performed across the codebase:

1. **`LiteRtVisionSegmenter.runIfLoaded`**:
   - Primary live camera inference route.
   - Pre-allocates single-instance reusable buffers: `reusableClassMask` (ByteArray), `reusableOverlayArgb` (IntArray), `reusableCrosswalkRegion` (BooleanArray), single-instance `BinaryVisionOverlayRenderer`, and `CrosswalkRegionEstimator`.
   - Reusable overlay scratch buffers operate in steady-state without per-frame overlay scratch re-allocations (general inference pipeline may have upstream framework allocations, but overlay post-processing avoids heap churn).
2. **`LiteRtVisionSegmenter.decodeArgmaxAndMask` (Public Static / Tool Route)**:
   - Preserves public 10-parameter signature without exposing internal types.
   - Internally provisions `staticEstimator` and dynamically sized reusable region buffer to guarantee complete display parity between live and static/import routes.
3. **`MainVisionInset.kt` (Line 543)**:
   - Primary Home screen overlay. Consumes `activeOverlayBitmap` (`visionUiState.latestOverlayBitmap`).
   - Strict layout invariance preserved: vision overlay floats in container, does not push other UI elements, overlay subtree contains no interactive buttons/actions, and exact single bottom-center direction line is preserved.
4. **`VisionDiagnosticsScreen.kt` (Line 271)**:
   - Developer and field diagnostics screen. Consumes `uiState.latestOverlayBitmap` via `VisionCameraPreviewBox`.
   - Displays real-time binary segmentation overlay paired with auxiliary object detection bounding boxes.
5. **`PresentationDemoScreen.kt` (Line 156)**:
   - Live camera presentation viewport. Consumes `visionUiState.latestOverlayBitmap` via `VisionCameraPreviewBox`.

---

## 5. Verification & Test Suite

The implementation is verified by dedicated JVM regression and unit tests:
- `BinaryVisionOverlayRendererTest`:
  - `allSevenClassesAndInvalidClassesMapToBinaryColorsWithoutTransparency`: Asserts all 7 classes + out-of-range IDs map strictly to GREEN or RED with 0x80 alpha.
  - `noisyGreenComponentSuppressedToRedWhileObstaclesRemainRedEvenTiny`: Verifies noise speckles turn red while tiny 1-pixel obstacles stay red.
  - `largeGreenComponentPreservedAboveThreshold`: Verifies valid walkable areas stay green.
  - `crosswalkEstimatorGapFillingFillsPavementAndPreservesObstacleHoles`: Verifies zebra stripes and filled road gaps are green while obstacles inside the crosswalk remain red holes.
  - `semanticOutputInvarianceWithOverlayNullVsEnabled`: Verifies byte-for-byte identity of class masks and statistical summaries.
- `OverlayComponentFilterTest`:
  - Updated expectations from legacy transparent (0) to unified binary RED/GREEN.
- `CrosswalkOverlayRegressionTest`:
  - Updated expectations to verify binary color retention and noise suppression.
- `VisionOrientationEvaluationTest` (AndroidTest compile target):
  - Updated `CLASS_OVERLAY_ARGB` assertion to match unified binary palette.
