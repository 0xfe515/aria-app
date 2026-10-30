# Aria Offline Vision Integration Guide

> 2026-10-30 clarification: This guide records the initial integration stage, including the original Cityscapes-only crosswalk mapping and pre-bundling model availability. The user confirmed subsequent `Surface_1` fine-tuning for Korean sidewalk environments; see [training data provenance](VISION_TRAINING_DATA.md). For later bundled artifacts and current working-tree portrait integration, see [model specifications](VISION_MODEL.md) and [portrait production integration](VISION_PORTRAIT_PRODUCTION.md).

## 1. Overview & Scope

This document describes the on-device camera and offline semantic segmentation integration in Aria (`com.ariateam.aria.vision` and `Settings > Debug` screens).

- **Current Model Availability**: As documented in [`docs/VISION_MODEL.md`](VISION_MODEL.md), the upstream repository (`https://github.com/tulg580/vision_model` at commit `c3830f315922c2d41b0d87eaa7439d09f833fb10`) contains training and TFLite export scripts (`export_for_aria.py`) but does not publish a trained `.tflite` binary or GitHub Release artifact.
- **No Fabricated Model or Hazard Alerts**: Aria does **not** bundle a random-weight dummy `.tflite` file, does **not** fabricate model inference latency or segmentation accuracy when no model is loaded, and does **not** issue automated hazard/obstacle navigation alerts.
- **Ready-to-Run Runtime + Measured Camera Pipeline**: The CameraX + YUV-to-RGB 512×288 preprocessing + LiteRT (`org.tensorflow.lite.Interpreter`) inference + 7-class argmax/ROI mask overlay integration is implemented, unit-tested on JVM, and benchmarked in camera-only mode on a physical Pixel 3 (`8B7X15A0F`, Android 12 / API 31). Because no trained `.tflite` artifact is available yet, live `Interpreter.run` model execution latency and end-to-end segmentation quality on real scenes remain unverified until a compatible `walkable_seg_aria.tflite` file is imported via the in-app picker.

---

## 2. Upstream Model Contract (`VisionContract`)

Implemented in [`VisionContract.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionContract.kt), matching `export_for_aria.py` and upstream `README.md`:

| Property | Contract Value |
|---|---|
| Architecture | MobileNetV2 encoder + U-Net decoder semantic segmentation |
| Input tensor count | `1` |
| Input tensor shape | `[1, 288, 512, 3]` (`NHWC`: height `288`, width `512`, channels `3`) |
| Input tensor dtype & range | `FLOAT32` (`442,368` floats = `1,769,472` bytes), RGB channel order normalized to `[0.0, 1.0]` |
| Output tensor count | `1` |
| Output tensor shape | `[1, 288, 512, 7]` (`NHWC`: height `288`, width `512`, classes `7`) |
| Output tensor dtype & semantics | `FLOAT32` (`1,032,192` floats = `4,128,768` bytes), per-pixel softmax probabilities across 7 classes |

### Class IDs & Walkable Surface Mapping

| Class ID | Key | Korean Label | Walkable Surface (`CLASS_WALKABLE`) | Upstream Note |
|---:|---|---|---|---|
| `0` | `other` | 기타·배경 | `false` | Background / sky / buildings |
| `1` | `road` | 도로 | `false` | Vehicular roadway (non-walkable for pedestrian sidewalk guidance) |
| `2` | `sidewalk` | 인도 | `true` | Primary pedestrian sidewalk |
| `3` | `crosswalk` | 횡단보도(미학습) | `true` | Defined in upstream schema (`NUM_CLASSES = 7`), but Cityscapes training mapping leaves class `3` untrained |
| `4` | `person` | 사람 | `false` | Dynamic foreground |
| `5` | `vehicle` | 차량 | `false` | Dynamic foreground |
| `6` | `pole_sign` | 기둥·표지판 | `false` | Static vertical obstacle |

---

## 3. Architecture & Module Responsibilities

| File | Responsibility |
|---|---|
| [`VisionContract.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionContract.kt) | Model tensor constants, 7-class definitions, `validateModelSpec(...)` contract validator, and `VisionModelState` / `VisionModelMetadata` state models. |
| [`VisionFramePreprocessor.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionFramePreprocessor.kt) | Single-pass fused `YUV_420_888` to `[1, 288, 512, 3]` `FLOAT32` RGB `[0, 1]` preprocessor with rotation, bottom-aligned `512:288` crop, precomputed 4-tap `BILINEAR` (default) / `NEAREST` LUTs, canonical ITU-R `BT601_LIMITED` (default) / `BT601_FULL` color conversion, strict plane bounds validation, and `FIT_CENTER` overlay viewport mapping. |
| [`LiteRtVisionSegmenter.kt`](../app/src/main/java/com/ariateam/aria/vision/LiteRtVisionSegmenter.kt) | Thread-safe LiteRT CPU (`XNNPACK`, 4 threads) `Interpreter` wrapper with lazy direct output/mask buffers, single-instance `prepareModelSession(file)`, transactional `swapPreparedSession(session)`, and pure `decodeArgmaxAndMask(...)` postprocessing. |
| [`VisionModelStore.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionModelStore.kt) | Offline `.tflite` importer and store in `context.noBackupFilesDir/vision` with bounded stream copy (`<= 64 MiB`), SHA-256 calculation, two-slot atomic replacement (`walkable_seg_aria_slot0.tflite` / `slot1.tflite`), transactional `SharedPreferences` commit + session swap with rollback, and synchronized terminal `close()`. |
| [`VisionTimingTracker.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionTimingTracker.kt) | Bounded ring-buffer (`DEFAULT_RING_CAPACITY = 1,024`) timing collector separating `5,000 ms` warmup from steady-state, tracking sensor capture cadence, analyzer delivery cadence, preprocess, model invoke, postprocess, total analyzer time, actual state publication delay, and hardware-verified `REALTIME` camera-to-result age. |
| [`VisionPipelineController.kt`](../app/src/main/java/com/ariateam/aria/vision/VisionPipelineController.kt) | CameraX `Preview` + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_YUV_420_888`, `DEFAULT_BACK_CAMERA`) controller with `sessionGeneration` lifecycle fencing and lazy `VisionViewModel` initialization. |
| [`MainActivity.kt`](../app/src/main/java/com/ariateam/aria/MainActivity.kt) | Hosts clean Home (`VoiceDemoScreen`), `SettingsScreen`, and the three debug destinations (`VisionDiagnosticsScreen`, `VoiceDiagnosticsScreen`, `PresentationDemoScreen`) guarded by `PendingCameraStartGate`. |

---

## 4. CameraX & Preprocessing Pipeline Details

1. **Explicit Start Only & Lazy Initialization**:
   - `VisionViewModel` does **not** instantiate `VisionPipelineController`, `VisionModelStore`, `VisionFramePreprocessor`, or `LiteRtVisionSegmenter` during normal Home startup or on `SettingsScreen`.
   - `visionModel.ensureController()` is invoked only when the user navigates into `Settings > Debug > 카메라 / 비전 진단` or `Settings > Debug > 통합 시연 데모 (발표용)`.
   - Entering a debug screen does **not** auto-start the camera; the user must tap `카메라 시작`.
   - `PendingCameraStartGate` binds every camera permission launch to a monotonic request token and the requesting `DebugDestination`. Navigating away (Back, tab switch, voice command navigation, orientation change, or backgrounding) invalidates the token so a delayed permission grant can never start the camera on Home or Settings.
2. **Backpressure & Resource Discipline**:
   - `ImageAnalysis` uses `STRATEGY_KEEP_ONLY_LATEST` on a single dedicated daemon thread (`aria-vision-analyzer`) with a `640×480` (`4:3`) target resolution selector.
   - `imageProxy.close()` is guaranteed inside `finally`.
   - Large fixed input (`1,769,472` B direct `ByteBuffer` and float array), output (`4,128,768` B direct `ByteBuffer` and float array), and class/ARGB mask buffers (`147,456` B + `589,824` B) are reused across frames without JPEG compression or full-frame intermediate `Bitmap` copies; lightweight `VisionTimingSnapshot` updates (and overlay `Bitmap` creation when a model is loaded) are throttled to `250 ms` UI publication cadence.
3. **Orientation, Bottom-Aligned `512:288` Crop, Strides, and Color Math**:
   - Given sensor buffer dimensions `(bufferWidth, bufferHeight)` and `imageProxy.imageInfo.rotationDegrees` (`0`, `90`, `180`, `270`), `computeUprightCropRect(...)` computes the upright dimensions `(uprightWidth, uprightHeight)` and extracts a `512:288` (`16:9`) region that is **bottom-aligned** (`top = uprightHeight - cropHeight`) when the upright frame is taller than `16:9` (e.g., `480×640` portrait crops to `left = 0, top = 370, width = 480, height = 270`), matching upstream `README.md` and `test_video_multi.py` (`crop_to_aspect`).
   - `validateFrameAndPlaneBounds(...)` strictly validates positive dimensions, valid rotation, positive pixel/row strides, and minimum required backing bytes `(height - 1) * rowStride + (width - 1) * pixelStride + 1` for Y, U, and V planes in both `ByteBuffer` and `ByteArray` entry points.
   - Precomputed lookup tables map each `512×288` output pixel back to unrotated sensor Y (4-corner 2×2 bilinear weights) and UV plane byte offsets.
   - By default, `YuvColorRange.BT601_LIMITED` applies canonical ITU-R BT.601 studio-swing coefficients (`Y=16 -> 0.0`, `Y=235 -> 1.0`) and writes normalized `FLOAT32` RGB (`R`, `G`, `B` in `[0.0f, 1.0f]`) into the reused direct `inputByteBuffer`.

---

## 5. Offline `.tflite` Model Import & Transactional Storage

1. **System File Picker**:
   - Tapping `모델 파일(.tflite) 가져오기` in `VisionDiagnosticsScreen` launches `ActivityResultContracts.OpenDocument`.
2. **Bounded Stream Copy & SHA-256**:
   - `VisionModelStore` copies the selected stream on a single background thread (`aria-vision-model-store`) into `context.noBackupFilesDir/vision/walkable_seg_aria.staging.tflite` with a hard limit of `64 MiB` (`MAX_MODEL_BYTES`), computing the lowercase hex SHA-256 digest while streaming. Empty (`0 B`) or oversized (`> 64 MiB`) streams are rejected and deleted immediately.
3. **Two-Slot Atomic Move & Single-Interpreter Validation**:
   - Because an active model is memory-mapped (`FileChannel.MapMode.READ_ONLY`), overwriting an active file in place could corrupt or crash the running `Interpreter`.
   - `VisionModelStore` alternates between `walkable_seg_aria_slot0.tflite` and `walkable_seg_aria_slot1.tflite` inside `context.noBackupFilesDir/vision`.
   - The staged file is moved into the **inactive** slot via `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` before `LiteRtVisionSegmenter.prepareModelSession(candidateSlot)` memory-maps it and validates exact input/output tensor counts, shapes (`[1, 288, 512, 3]` -> `[1, 288, 512, 7]`), and `FLOAT32` data types.
4. **Transactional Commit & Rollback**:
   - `SharedPreferences` metadata (`active_slot_file`, `display_name`, `size_bytes`, `sha256`, `imported_at`) is committed synchronously first; then `segmenter.swapPreparedSession(preparedSession)` swaps the live `Interpreter` under lock and returns the previous `Interpreter` to be closed only after both steps succeed.
   - If copy, atomic move, tensor validation, `SharedPreferences.commit()`, or session swap fails, the candidate session is closed, the inactive slot file is deleted, `SharedPreferences` is restored to the previous active slot metadata, and the existing valid model remains active (`VisionModelState.Ready` with `rejectedImportNotice`).

---

## 6. Runtime Backend & Dependency Coexistence

- **Dependencies**:
  - `com.google.ai.edge.litert:litert:1.4.2` (vision `.tflite` runtime, packaging `libtensorflowlite_jni.so`)
  - `com.google.ai.edge.litertlm:litertlm-android:0.16.1` (existing voice/Gemma runtime, packaging `liblitertlm_jni.so`)
  - `androidx.camera:camera-{core,camera2,lifecycle,view}:1.6.2`
- **No Native or Class Conflicts**: Verified via `:app:checkDebugDuplicateClasses` and APK packaging inspection (`lib/arm64-v8a/` contains disjoint native libraries `libtensorflowlite_jni.so`, `liblitertlm_jni.so`, and `libimage_processing_util_jni.so`).
- **Backend Configuration**: Uses LiteRT CPU execution with `XNNPACK` enabled (`setUseXNNPACK(true)`) and `4` threads (`DEFAULT_CPU_THREADS = 4`). A GPU delegate is intentionally omitted from the baseline to avoid shader-compilation stalls and driver-specific float32 precision divergences before a trained `.tflite` binary is available for device-level accuracy regression testing.
