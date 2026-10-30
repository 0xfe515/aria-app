# ARiA Portrait Vision Production Pipeline Integration

Last Updated: 2026-10-28
Task Reference: `ARIA-PORTRAIT-PRODUCTION-PIPELINE-001`

---

## 1. Executive Summary & Objective

The production ARiA vision pipeline previously used a fixed landscape model ($288 \times 512$) with a legacy bottom crop. For a 480×640 upright camera frame, that crop retained 480×270 pixels and discarded about 58% of the frame area. An earlier experiment also evaluated clockwise 90° rotation; it turned upright scene features sideways and was not the app default.

Under task `ARIA-PORTRAIT-PRODUCTION-PIPELINE-001`, the verified same-weight non-rotated native portrait model (`walkable_seg_aria5_portrait_unfused.tflite`, SHA-256 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`) is now applied to the **actual production camera $\rightarrow$ preprocessor $\rightarrow$ segmenter $\rightarrow$ inverse-mapped overlay/hazard pipeline**:
- **Native Portrait Model Tensor**: Input shape `[1, 512, 288, 3]`, output shape `[1, 512, 288, 7]` (`FLOAT32`, range `[0.0f, 1.0f]`).
- **Production Preprocessor**: Default target dimensions updated to `MODEL_INPUT_WIDTH = 288` and `MODEL_INPUT_HEIGHT = 512`, using `FULL_FRAME_UPRIGHT_LETTERBOX` strategy. Full camera field of view is preserved without CW90 rotation or bottom-only cropping.
- **Decoder & Stride Alignment**: Explicit separation of model tensor dimensions ($W=288, H=512$, row stride 288) from canonical upright analysis/overlay grid ($W=512, H=288$, ROI start row 158). Probabilities and class masks are inverse-mapped into the canonical grid with letterbox padding stripped and zero per-pixel allocations.
- **Edge Case Protection**: When `runIfLoaded(transform = null)` is called, the decoder uses implicit full native portrait bounds ($[0, 0, 288, 512]$), mapping correctly into the canonical grid instead of reading flat portrait pixels as landscape rows.
- **Diagnostics**: Model spec and preprocessor dimension labels updated in `VisionDiagnosticsScreen` (`[1, 512, 288, 3]`, $288 \times 512$ preprocessor, $512 \times 288$ canonical upright grid).

---

## 2. Production Architecture & Geometric Pipeline

```
  CameraX YUV_420_888 ImageProxy (e.g. 640x480 @ 90° -> upright 480x640)
                            │
                            ▼
        ┌──────────────────────────────────────────────┐
        │  VisionFramePreprocessor                     │
        │  - Target: 288 x 512 (native portrait tensor)│
        │  - Strategy: FULL_FRAME_UPRIGHT_LETTERBOX    │
        │  - Zero CW90 semantic rotation               │
        │  - Content: 288 x 384, top pad: 64, bot: 64  │
        └──────────────────────────────────────────────┘
                            │
                            ▼ Direct FloatBuffer [1, 512, 288, 3]
        ┌──────────────────────────────────────────────┐
        │  LiteRtVisionSegmenter                       │
        │  - Model: walkable_seg_aria5_portrait_unfused│
        │  - Backend: existing CPU/GPU preference     │
        │  - Stride: 288 cols/row, 512 rows            │
        │  - Output: [1, 512, 288, 7]                  │
        └──────────────────────────────────────────────┘
                            │
                            ▼ FloatArray [1, 512, 288, 7]
        ┌──────────────────────────────────────────────┐
        │  decodeArgmaxAndMask (Inverse Mapping)       │
        │  - Model tensor stride: modelWidth = 288     │
        │  - Letterbox padding stripped                │
        │  - Resampled to Canonical Upright 512 x 288  │
        │  - Zero per-pixel heap allocations           │
        └──────────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
    ┌──────────────────────┐    ┌──────────────────────┐
    │  HazardDetector      │    │  MainVisionInset     │
    │  - 512x288 Mask      │    │  - 512x288 Bitmap    │
    │  - ROI Start Row 158 │    │  - FIT_CENTER Viewport│
    │  - Corridors (L/C/R) │    │  - Live Camera Overlay│
    └──────────────────────┘    └──────────────────────┘
```

### 2.1 Coordinate Mapping & Stride Arithmetic
In row-major tensor layout $[1, H, W, C]$, each element $(b, row, col, class)$ has flat index:
$$\text{offset} = (row \times W + col) \times C + class$$
For the native portrait model:
- $W = \text{modelWidth} = 288$
- $H = \text{modelHeight} = 512$
- $C = \text{numClasses} = 7$

For each canonical upright coordinate $(ux, uy)$ ($ux \in [0, 511]$, $uy \in [0, 287]$):
$$u_{\text{norm}} = (ux + 0.5f) / 512$$
$$v_{\text{norm}} = (uy + 0.5f) / 288$$
Under `FULL_FRAME_UPRIGHT_LETTERBOX`:
$$mCol = \text{clamp}(contentLeft + u_{\text{norm}} \times contentWidth, contentLeft, contentLeft + contentWidth - 1)$$
$$mRow = \text{clamp}(contentTop + v_{\text{norm}} \times contentHeight, contentTop, contentTop + contentHeight - 1)$$
$$\text{safeCol} = \text{clamp}(mCol, 0, \text{modelWidth} - 1)$$
$$\text{safeRow} = \text{clamp}(mRow, 0, \text{modelHeight} - 1)$$
$$\text{modelProbOffset} = (\text{safeRow} \times \text{modelWidth} + \text{safeCol}) \times 7$$

Because $mRow \in [contentTop, contentTop + contentHeight - 1]$, pixels in the top padding ($row < contentTop$) and bottom padding ($row \ge contentTop + contentHeight$) are **never sampled**. The active camera content covers the entire canonical $512 \times 288$ mask and overlay. Content bounds are strictly validated and clamped before index calculation to gracefully handle any malformed transform without per-pixel allocations.

Furthermore, `decodeArgmaxAndMask` parameters default to `modelWidth: Int = width, modelHeight: Int = height`, preserving backward compatibility for test helpers with small synthetic canvases (e.g. $8 \times 6$). Production `runIfLoaded` explicitly supplies `modelWidth = VisionContract.MODEL_INPUT_WIDTH` and `modelHeight = VisionContract.MODEL_INPUT_HEIGHT`.

### 2.2 Asymmetric Corner & ROI Integrity
- **Top-Left Camera Corner** $(u=0, v=0) \rightarrow (mCol = contentLeft, mRow = contentTop) \rightarrow (ux = 0, uy = 0)$
- **Top-Right Camera Corner** $(u=1, v=0) \rightarrow (mCol = contentLeft + contentWidth - 1, mRow = contentTop) \rightarrow (ux = 511, uy = 0)$
- **Bottom-Left Camera Corner** $(u=0, v=1) \rightarrow (mCol = contentLeft, mRow = contentTop + contentHeight - 1) \rightarrow (ux = 0, uy = 287)$
- **Bottom-Right Camera Corner** $(u=1, v=1) \rightarrow (mCol = contentLeft + contentWidth - 1, mRow = contentTop + contentHeight - 1) \rightarrow (ux = 511, uy = 287)$
- **Ground ROI Analysis**: $uy \ge 158$ (bottom 45% of canonical grid) corresponds exactly to $v_{\text{norm}} \ge 0.55$, preserving real-world forward walking ground classification.

---

## 3. Verified Artifacts & Model Identity

| Property | Value |
|---|---|
| **Default Production Model** | `models/walkable_seg_aria5_portrait_unfused.tflite` |
| **Optimized Production Model** | `models/walkable_seg_aria5_portrait_unfused.tflite` |
| **Model Size** | 10,839,936 bytes |
| **SHA-256 Checksum** | `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1` |
| **Input Shape** | `[1, 512, 288, 3]`, `FLOAT32` |
| **Output Shape** | `[1, 512, 288, 7]`, `FLOAT32` |
| **Learned Weights Audit** | 122/122 buffers 100% bit-for-bit identical to baseline |
| **GPU Delegate Nodes** | 210/210 nodes delegated (Qualcomm Adreno 630 GPU) |

---

## 4. Historical Evaluation Reproducibility

To prevent breaking historical reproducibility while enforcing the strict portrait contract in production:
- `VisionContract` preserves legacy constants (`LEGACY_DEFAULT_ASSET_MODEL_*` and `LEGACY_OPTIMIZED_ASSET_MODEL_*`).
- `VisionOrientationEvaluationTest.kt` uses an isolated `LegacyLandscapeRunner` that directly instantiates a raw LiteRT `Interpreter` on `LEGACY_OPTIMIZED_ASSET_MODEL_PATH` and invokes `decodeArgmaxAndMask(..., modelWidth = 512, modelHeight = 288)`.
- Production `LiteRtVisionSegmenter` strictly validates the portrait model spec `[1, 512, 288, C]` and rejects legacy landscape models during production import.

---

## 5. Verification Commands & Test Selectors

All build, install, and test commands are owned and executed exclusively by the coordinator.

### 5.1 Host Unit Tests
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.ariateam.aria.vision.VisionPipelineUnitTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.ariateam.aria.hazard.CameraGeometryTest"
.\gradlew.bat :app:lintDebug
```

### 5.2 Device Instrumentation Tests (Pixel 3, serial `8B7X15A0F`)
```powershell
# 1. New Production Pipeline Device Test (Verifies Preprocessor + LiteRtVisionSegmenter + Parity + Controller)
.\gradlew.bat :app:connectedDebugAndroidTest `
    -Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionPortraitProductionPipelineTest

# 2. Peer Migration Test (Verifies automatic asset provisioning and legacy slot migration)
.\gradlew.bat :app:connectedDebugAndroidTest `
    -Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionPortraitModelMigrationTest

# 3. Model Sanity Test
.\gradlew.bat :app:connectedDebugAndroidTest `
    -Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.VisionModelSanityTest
```

---

## 6. Known Limitations & Device Context

1. **Thermal Throttling Context**:
   - Before integration validation, the connected Pixel 3 reported thermal status `4` after prolonged live-camera use. The coordinator stopped the app and allowed the device to cool. Latency numbers measured during heated conditions should not be interpreted as steady-state performance.
2. **CamVid Fixture Resolution**:
   - CamVid scenes are historical $960 \times 720$ landscape images with derived 9:16 center crops rather than native vertical sensor streams. Real-world camera streams (e.g. $1920 \times 1080$ or $1280 \times 720$) will have zero letterbox padding when operating at native 9:16 aspect ratio.

## 7. Independent Integration Verification — 2026-10-28

The coordinator ran `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` with the Android Studio JDK. The final combined run passed in 17 seconds; all 202 JVM tests passed. Two legacy landscape geometry fixtures initially relied on defaults that are now portrait; their explicit landscape dimensions/strategy were corrected without weakening assertions. The real-image test's YUV chroma allocation was also corrected to ceiling division for the 720×405 sensor fixture.

On Pixel 3 (`8B7X15A0F`, Android 12), direct instrumentation of `VisionPortraitModelMigrationTest`, `VisionPortraitProductionPipelineTest`, `VisionModelSanityTest`, and `MainActivityOrientationTest` passed **19 tests, zero failures, zero ignored**, in 354.441 seconds. Runtime logs confirm that the production preprocessor/segmenter test loaded the staged real CamVid portrait image `0001TP_008760--portrait.png`.

The production GPU comparison confirmed an active GPU delegate and measured maximum probability difference `1.1444092e-5`, mean difference `3.3261813e-7`, and 100% agreement for both the canonical class mask and walkable mask on its deterministic comparison input. The separate four-fixture model sanity comparison had a worst maximum difference of `8.735061e-5`, minimum class agreement of 99.9993%, and 100% walkable agreement. These are numerical parity checks, not a new labeled-data accuracy study or sustained-FPS benchmark. Thermal status was 0 before instrumentation and 1 during the production parity sample.

The installed app was also updated **without clearing its data**: the actual persisted landscape slot (`84364b…`) automatically migrated to portrait slot 1 (`312105…`), and the old slot was retired after activation. The app's diagnostic screen displayed the portrait model name, full hash, input `[1,512,288,3]`, and output `[1,512,288,7]`. The main screen displayed live full-frame portrait inference. Before and after evidence, the device log, and the screenshot are under ignored `build/vision-portrait-production/`; the screenshot was also copied to the Windows desktop, never added to Git. Final persisted preferences retain the portrait model, `user_cleared_model=false`, and the original default CPU preference.

Implementation provenance: the model/store task used the Orca Copilot worker (requested/effective agent `copilot`; observed runtime Auto label `gpt-6-luna`). The pipeline and focused correction used the same Orca Gemini worker whose initial launch requested and effectively selected `gemini-3.8-flash-high`. No quota fallback or coordinator source-code implementation was used. All worker attempts settled and their terminals were released or transferred through the supervised lifecycle.

## 8. GPU-only execution and diagnostic controls — 2026-10-28

The vision diagnostic menu no longer exposes model import, clear, restore, apply, or CPU/GPU selection controls. Production inference always requests the GPU delegate with FP32 precision. Persisted CPU preferences are upgraded to GPU; GPU unsupported or initialization errors stop activation without a CPU inference fallback. Explicit CPU reference execution remains isolated to internal diagnostic test policy. Camera processing and UI work still use the CPU.

Independent validation passed `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` (208 JVM tests; lint: 0 errors, 88 warnings). Pixel 3 / Android 12 instrumentation passed 8 focused tests in 143.852 seconds: removed controls and truthful backend display, CPU preference migration, simulated unsupported GPU, simulated GPU delegate initialization failure, and all four portrait production pipeline tests. Logs confirmed all 210 model nodes delegated to GPU and use of the staged real CamVid fixture. The parity sample reported maximum difference `1.1444092e-5`, mean difference `3.3261813e-7`, and 100% class/walkable agreement. This sample is not a sustained performance benchmark. The updated APK was installed without clearing application data.

UI implementation used the Orca-managed Copilot worker; runtime implementation used the pinned Gemini `gemini-3.8-flash-high` worker. Both tasks reported success, were independently reviewed and verified, and their terminals were released. No source implementation was authored by the coordinator, and no Git commit or push was performed.
