# ARiA Vision Graph Optimization Tool

## Overview
`unfuse_transpose_conv.py` optimizes the ARiA walkability segmentation model (`walkable_seg_aria5.tflite`) for full hardware acceleration on the **Google LiteRT GPU Delegate** (OpenCL / Vulkan / OpenGL ES compute shaders).

## Problem and Root Cause
In `walkable_seg_aria5.tflite` (exported from Keras / TensorFlow with FP16 weights), the 5 decoder `Conv2DTranspose` layers (operators 189, 192, 195, 198, 201) are fused with ReLU activation.
In TensorFlow Lite flatbuffers, fused activation on `TRANSPOSE_CONV` requires operator version 4.

However, the LiteRT GPU delegate registration explicitly caps `TRANSPOSE_CONV` at version 3:
```cpp
CheckMaxSupportedOpVersion(registration, 3)
```
Consequently, LiteRT's GPU delegate rejected all 5 `TRANSPOSE_CONV` nodes. This caused the delegate to partition the graph into 3 fragmented subgraphs, delegating only 64 out of 204 nodes to GPU and falling back 140 nodes to CPU.
On the Pixel 3 (Snapdragon 845 / Adreno 630), ping-ponging memory buffers between CPU and GPU resulted in **823.22 ms** inference latency—yielding no benefit over pure CPU (858 ms).

## Transformation Solution
The tool performs a structural FlatBuffers transformation without retraining or touching weights:
1. Verifies the input model SHA-256 (`3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7`).
2. Finds the 5 fused `TRANSPOSE_CONV` operators.
3. Sets `fused_activation_function = NONE` on each `TRANSPOSE_CONV` and sets the `TRANSPOSE_CONV` opcode version to 3.
4. For the first `TRANSPOSE_CONV` with odd input height 9 (18x32 desired output), resolves LiteRT GPU delegate's asymmetric padding bug by lowering to `VALID` padding (19x33 output) followed by explicit `SLICE` (starts [0,0,0,0], sizes [1,18,32,256]) and explicit `RELU` (v1).
5. For the remaining 4 `TRANSPOSE_CONV` operators with even spatial dimensions, diverts output to intermediate FP32 tensor followed by explicit `RELU` (v1).
6. Preserves all weights bit-for-bit with 100.0% CPU parity (maxDiff 0.0, MAE 0.0, argmax 100.0%, walkable 100.0%) on three synthetic fixtures. Production GPU FP32 passes the four-fixture tolerance (maxDiff < 0.001, MAE < 0.0001, class/walkable agreement >= 99.99%); see docs/VISION_VALIDATION.md for the observed two-pixel blank-input difference.
7. Atomically writes `walkable_seg_aria5_unfused.tflite` (SHA-256: `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847`).

## Benchmarked Results (Google Pixel 3)
| Model & Backend | Delegate Nodes Delegated | Invoke Latency (p50) | Effective Microprobe Rate | Precision & Parity Note |
|---|---|---|---|---|
| Original CPU (4 threads, XNNPACK) | 204 / 204 on CPU | 867.1 ms | ~1.1 FPS | Baseline reference |
| Original GPU Delegate | 64 / 204 on GPU (3 partitions) | 823.2 ms | ~1.2 FPS | 140 CPU fallback nodes |
| **Transformed GPU Delegate (FP32, Production Default)** | **209 / 209 on GPU (1 partition)** | **~90.1 ms (Sustained) / ~103.5 ms** | **~9.6 - 11.1 FPS** (8.4x - 9.6x speedup) | **100.0% argmax/walkable parity on 3 fixtures, >99.998% on blank vs CPU reference (four-fixture maxDiff < 0.001)** |
| Transformed GPU Delegate (FP16, Diagnostic Probe) | 209 / 209 on GPU (1 partition) | ~65.6 ms | ~15.2 FPS (13.2x speedup) | Fast diagnostic probe (argmax agreement 98.6%~99.8%) |

*Note: Pure model invoke latency is distinct from sustained CameraX pipeline throughput which includes sensor delivery cadence, upright 16:9 bottom crop, and argmax postprocessing.*

## Runtime and Tool Dependencies
- ARiA App Runtime: Google LiteRT 1.4.2 (`com.google.ai.edge.litert:litert:1.4.2`, `litert-gpu:1.4.2`, `litert-gpu-api:1.4.2`)
- Transformation Tool:
  - Python 3.10+
  - `flatbuffers==25.12.19`
  - `flatc==25.12.19rc0`
  - Official TensorFlow schema: `tensorflow/compiler/mlir/lite/schema/schema.fbs` (v2.19.0, SHA-256 `20685cadade475c2a5cccf29339ecd2bf27755da50dc3d2c092f1ee2e21ff7d3`)

```bash
# 1. Generate python flatbuffer classes
flatc --python --gen-object-api schema.fbs

# 2. Run transformation
python tools/vision/unfuse_transpose_conv.py \
  --input app/src/main/assets/models/walkable_seg_aria5.tflite \
  --output app/src/main/assets/models/walkable_seg_aria5_unfused.tflite
```
