# ARiA Portrait Vision Model Specification and Graph Transformation

Last Updated: 2026-10-28
Task Reference: `ARIA-PORTRAIT-MODEL-001`

---

## 1. Executive Summary & Objective

The production ARiA vision segmentation model (`walkable_seg_aria5_unfused.tflite`) operates on fixed landscape dimensions ($H=288, W=512$). In upright portrait phone mounting, feeding camera frames into a landscape model requires either:
1. **Clockwise 90° rotation (`portrait_cw90`)**: Distorts upright visual semantics (sky, ground, vertical poles, pedestrians become horizontal), severely degrading segmentation accuracy (e.g. sky misclassified as sidewalk, zero person detection).
2. **Upright letterboxing (`upright_letterbox`)**: Squeezes a 9:16 portrait crop into a 16:9 landscape canvas, wasting over 68% of pixels on black padding and drastically reducing vertical resolution.

This task produces the experimental **same-weight non-rotated native portrait-input model** with:
- **Input shape**: `[1, 512, 288, 3]` (`FLOAT32`, RGB, range `0.0f .. 1.0f`)
- **Output shape**: `[1, 512, 288, 7]` (`FLOAT32`, Softmax probabilities across 7 classes)
- **Zero retraining and zero learned-weight modification**: All 122 learned convolution weights and bias buffers are preserved **100% bit-for-bit identical** (verified by SHA-256 buffer inventory).
- **Zero normalizer / metadata alteration**: All 3 normalizer scalar constants and 3 model metadata buffers are preserved bit-for-bit identical.
- **GPU Delegate compatibility**: Preserves unified single-partition GPU execution (210/210 nodes delegated on Pixel 3 Adreno 630 GPU) by adapting the odd-dimension `VALID + SLICE + RELU` lowering to portrait dimensions ($16 \times 9$).

---

## 2. Artifact Contract & Hashes

| Artifact | Path | Size | SHA-256 | Description |
|---|---|---|---|---|
| **Source Baseline Model** | `app/src/main/assets/models/walkable_seg_aria5_unfused.tflite` | 10,839,936 B | `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847` | Landscape GPU-unfused baseline ($288 \times 512$) |
| **Portrait Target Model** | `build/vision-portrait-model/walkable_seg_aria5_portrait_unfused.tflite` | 10,839,936 B | `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1` | Native portrait model ($512 \times 288$) |
| **Audit Log** | `build/vision-portrait-model/audit.json` | ~120 KB | Recorded in run | Complete per-buffer, per-tensor, and PAD audit |

---

## 3. Upstream Architecture & Geometric Symmetry

The upstream architecture is a MobileNetV2 backbone encoder with a U-Net decoder:
- **Encoder downsamplings**: 5 stages with stride $(2, 2)$ symmetric downsampling.
- **Divisibility**:
  - Landscape: $H = 288 = 32 \times 9$, $W = 512 = 32 \times 16$. Bottleneck is $(9, 16)$.
  - Portrait: $H = 512 = 32 \times 16$, $W = 288 = 32 \times 9$. Bottleneck is $(16, 9)$.
  Because both 288 and 512 are exact integer multiples of 32 ($2^5$), every intermediate encoder feature map and skip connection has strictly integer dimensions with zero spatial truncation or padding drift:

| Stage | Landscape Spatial $(H, W)$ | Portrait Spatial $(H, W)$ | Skip / Activation Tensor |
|---|---|---|---|
| Input | $288 \times 512$ | $512 \times 288$ | Input 0 (`serving_default_keras_tensor_523:0`) |
| Conv1 (stride 2) | $144 \times 256$ | $256 \times 144$ | Skip 1 (Tensor 262, 96 ch) |
| Block 3 (stride 2) | $72 \times 128$ | $128 \times 72$ | Skip 2 (Tensor 269, 144 ch) |
| Block 6 (stride 2) | $36 \times 64$ | $64 \times 36$ | Skip 3 (Tensor 280, 192 ch) |
| Block 13 (stride 2) | $18 \times 32$ | $32 \times 18$ | Skip 4 (Tensor 306, 576 ch) |
| Block 16 Project (stride 2) | $9 \times 16$ | $16 \times 9$ | Bottleneck (Tensor 319, 320 ch) |

---

## 4. Padding Analysis & PAD Operator Inspection

An exhaustive inspection of all 210 operators in `walkable_seg_aria5_unfused.tflite` confirms:
1. **Zero PAD / PADV2 operators exist in the graph**:
   - The MobileNetV2 Keras encoder was compiled with standard `SAME` padding on all 2D convolutions.
   - In TensorFlow Lite, symmetric strides ($(1,1)$ or $(2,2)$) and symmetric square kernels ($1\times 1$ or $3\times 3$) with `SAME` padding are represented natively via `BuiltinOptions.padding = SAME`.
   - No explicit `ZeroPadding2D` or asymmetric Keras `correct_pad` constant arrays are present in the FlatBuffers graph.
2. **Padding Breakdown across all 59 conv/deconv nodes**:
   - `CONV_2D`: 37 with `SAME` padding (stride 1,1), 1 with `SAME` padding (stride 2,2), 1 with `VALID` padding (final $1\times 1$ classification conv where VALID == SAME).
   - `DEPTHWISE_CONV_2D`: 13 with `SAME` padding (stride 1,1), 4 with `SAME` padding (stride 2,2).
   - `TRANSPOSE_CONV`: 4 with `SAME` padding (stride 2,2), 1 with `VALID` padding (Op 189 lowered for GPU delegate odd-dimension workaround).

---

## 5. Decoder Transformation & GPU Delegate Odd-Dimension Resolution

### 5.1 The GPU Delegate Asymmetric Padding Problem
In the landscape model, LiteRT GPU Delegate exhibited a 1-row vertical shift on odd input height $9$:
- Forward convolution padding calculates total pad 2 (top 1, bottom 1) for odd dimension 9 with stride 2 and kernel 3.
- Transpose convolution requires total pad 1 (top 0, bottom 1) to produce output 18.
- To resolve this, Op 189 was lowered in `walkable_seg_aria5_unfused.tflite` to:
  1. `TRANSPOSE_CONV` with `VALID` padding (unpadded output $19 \times 33$)
  2. Explicit `SLICE` (begin `[0, 0, 0, 0]`, size `[1, 18, 32, 256]`)
  3. Explicit `RELU` (v1)

### 5.2 Portrait Odd-Dimension Mapping ($16 \times 9$)
In portrait mode, the bottleneck spatial dimensions swap from $(9, 16)$ to $(16, 9)$:
- Height $H=16$ is **even**.
- Width $W=9$ is **odd**.
With stride 2, kernel $3 \times 3$, and `VALID` padding (pad = 0):
- $H_{\text{out}} = (H_{\text{in}} - 1) \times 2 + 3 = (16 - 1) \times 2 + 3 = 33$
- $W_{\text{out}} = (W_{\text{in}} - 1) \times 2 + 3 = (9 - 1) \times 2 + 3 = 19$

The target deconvolution output size under CPU `SAME` padding is $32 \times 18$:
- Trimming the 33rd row (index 32) leaves 32 rows (indices $0 \dots 31$).
- Trimming the 19th column (index 18) leaves 18 columns (indices $0 \dots 17$).
Thus, the explicit `SLICE` operator begins at `[0, 0, 0, 0]` with size `[1, 32, 18, 256]`, which identically matches CPU `SAME` padding semantics on both CPU and GPU delegates.

### 5.3 Decoder Stages Summary
The 6 shape constant buffers in `model.buffers` are transformed as follows:

| Buffer Index | Op Index | Tensor Name / Role | Landscape Value | Portrait Value |
|---|---|---|---|---|
| **Buf 339** | Op 189 | `tconv_valid_output_shape` | `[1, 19, 33, 256]` | `[1, 33, 19, 256]` |
| **Buf 8** | Op 190 | `conv2d_transpose_1/.../input_sizes` (reused as SLICE sizes) | `[1, 18, 32, 256]` | `[1, 32, 18, 256]` |
| **Buf 7** | Op 193 | `conv2d_transpose_1_2/.../input_sizes` | `[1, 36, 64, 128]` | `[1, 64, 36, 128]` |
| **Buf 6** | Op 197 | `conv2d_transpose_2_1/.../input_sizes` | `[1, 72, 128, 64]` | `[1, 128, 72, 64]` |
| **Buf 5** | Op 201 | `conv2d_transpose_3_1/.../input_sizes` | `[1, 144, 256, 32]` | `[1, 256, 144, 32]` |
| **Buf 4** | Op 205 | `conv2d_transpose_4_1/.../input_sizes` | `[1, 288, 512, 16]` | `[1, 512, 288, 16]` |

Buf 340 (`tconv_slice_starts`) remains `[0, 0, 0, 0]`.

---

## 6. Buffer Classification & Learned Weights Audit

The FlatBuffers model contains 341 buffers:
- **Empty buffers (206)**: Buffer 0 and intermediate activation references.
- **Learned parameter buffers (122)**: FP16/FP32 weights and biases for all convolution and depthwise layers. All 122 buffers are **100% bit-for-bit identical** between source and target models.
- **Normalizer scalar buffers (3)**: Buffers 2, 3, 9 for input rescaling (`Cast_1`, `Cast/x`). Bit-for-bit identical.
- **Model metadata buffers (3)**: Buffers 336, 337, 338 storing TFLite runtime version and metadata tags. Bit-for-bit identical.
- **Modified shape constant buffers (6)**: Buffers 4, 5, 6, 7, 8, 339 (H and W values swapped).
- **Unmodified shape constant buffer (1)**: Buffer 340 (`[0, 0, 0, 0]`).

All 103 4D activation tensors have their shapes swapped from `[1, H, W, C]` to `[1, W, H, C]`. Static `shapeSignature` properties are verified and updated where present.

---

## 7. Safety, Rejection & Immutability Guarantees

- **Source Immutability**: The source model is opened strictly read-only and verified with SHA-256 assertions.
- **Determinism**: Multiple conversions produce identical byte-for-byte output (`312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`).
- **Repeated Conversion Protection**: Passing an already-portrait model as input is explicitly rejected with `ValueError: Repeated conversion rejected`.
- **Path Collision Protection**: Specifying the same file for input and output is rejected to prevent in-place corruption.
- **Unsupported Topology Rejection**: Input models with unexpected dimensions or hashes are rejected.

---

## 8. Actual Device Runtime Evidence (Pixel 3)

The transformed portrait model (`walkable_seg_aria5_portrait_unfused.tflite`, SHA-256 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`) was independently staged, loaded, and executed on a physical Google Pixel 3 (Qualcomm Snapdragon 845 / Adreno 630 GPU, Android 12 API 31, `arm64-v8a`) via `VisionPortraitModelEvaluationTest`:
- **Tensor Input/Output Verification**:
  - Input: `[1, 512, 288, 3]`, `FLOAT32`
  - Output: `[1, 512, 288, 7]`, `FLOAT32`
- **GPU Delegate Compilation & Partitioning**:
  - LiteRT GPU Delegate compiled and delegated **210/210 nodes into a single unified GPU partition** (zero CPU fallback partitions).
- **Strict CPU vs GPU FP32 Parity Verification (`portraitGpuParityStatus: accepted`)**:
  - `synthetic_blank`: maxDiff = $8.74 \times 10^{-5}$, MAE = $2.97 \times 10^{-6}$, class agreement = **100.0%** (147,456 / 147,456), walkable agreement = **100.0%**
  - `synthetic_asymmetric`: maxDiff = $7.79 \times 10^{-5}$, MAE = $1.03 \times 10^{-6}$, class agreement = **100.0%** (147,456 / 147,456), walkable agreement = **100.0%**
  - `real_0001TP_008760`: maxDiff = $4.71 \times 10^{-5}$, MAE = $2.06 \times 10^{-7}$, class agreement = **100.0%** (147,456 / 147,456), walkable agreement = **100.0%**
  - `real_0001TP_009240`: maxDiff = $7.94 \times 10^{-5}$, MAE = $3.72 \times 10^{-7}$, class agreement = **99.9993%** (147,455 / 147,456, single-pixel boundary rounding), walkable agreement = **100.0%** (147,456 / 147,456)
- **Segmentation Quality & Performance (Pixel 3 Measured)**:
  - **Segmentation Accuracy (same-view mIoU on portrait crops)**: Native portrait model achieved **0.591** ($59.1\%$) mIoU, compared to **0.419** ($41.9\%$) for upright landscape letterboxing—a **+17.2 percentage point** improvement with zero retraining and zero learned-weight modification.
  - **Latency (p50 invoke)**: Native portrait GPU invoke was **140.7 ms** (versus 123.4 ms for landscape upright letterboxing in the same run). Both tensors contain 147,456 pixels; this fixed-order experiment did not isolate the cause of the timing difference and does not measure sustained camera FPS.
  - Complete paired 64-condition 4-arm metrics and inverse geometric mapping are documented in `docs/VISION_PORTRAIT_EVALUATION.md`.

---

## 9. Reproduction and Verification Commands

### Host Transformation & Unit Test Suite

Prerequisites are FlatBuffers and the generated TensorFlow Lite Python schema bindings described in [the vision tooling README](../tools/vision/README.md). Pass `--schema-dir` (the directory containing the generated `tflite/` package) and `--pydeps-dir` (the local dependencies directory) to the converter, or set the process-local `TFLITE_SCHEMA_DIR` and `TFLITE_PYDEPS_DIR` variables for both the converter and tests. The commands below assume those dependencies are already discoverable; no TensorFlow training runtime is required.

```powershell
# 1. Transform landscape model to portrait
python tools/vision/portrait/transform_portrait.py `
    --input app/src/main/assets/models/walkable_seg_aria5_unfused.tflite `
    --output build/vision-portrait-model/walkable_seg_aria5_portrait_unfused.tflite `
    --audit build/vision-portrait-model/audit.json

# 2. Run comprehensive unit tests (immutability, determinism, buffer audit, topology)
python tools/vision/portrait/test_portrait_model.py
```

---

## 10. Production App Integration

The measurements and graph-transformation details above describe the portrait-model experiment; they do not imply that the earlier app default was portrait. The app now packages this verified artifact as `models/walkable_seg_aria5_portrait_unfused.tflite` and uses its SHA-256 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1` for both default and optimized provisioning.

On restore, an installed built-in landscape model is migrated only when its file size and computed SHA-256 match one of the two known shipped artifacts. Migration writes the portrait model to the inactive slot, validates and activates it, and only then retires the prior slot; unknown custom files and an explicitly cleared model are not replaced. The original landscape asset files remain packaged for evaluation and compatibility comparisons.
