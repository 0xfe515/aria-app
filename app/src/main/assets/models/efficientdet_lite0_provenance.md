# EfficientDet-Lite0 int8 — packaged auxiliary object-detection model

Bundled helper model for the optional auxiliary object-detection sidecar
(`com.ariateam.aria.vision.od.LiteRtObjectDetector`). It is **not** part of the walkability
segmentation contract and never replaces the existing segmentation asset.

## Pinned artifact

| Field | Value |
| --- | --- |
| Source URL | `https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite` |
| Upstream family | TensorFlow Lite Task Library — EfficientDet-Lite0 (int8, 320x320) |
| Downloaded (UTC) | 2026-11-07 |
| HTTP result | `200 OK` |
| Size | `4,563,519` bytes |
| SHA-256 | `2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b` |
| Labels | `efficientdet_lite0_labels.txt`, 90 COCO category lines, SHA-256 `f8803ef7900160c629d570848dfda4175e21667bf7b71f73f8ece4938c9f2bf2`, from `https://storage.googleapis.com/mediapipe-tasks/object_detector/labelmap.txt` |
| License | Apache License 2.0 (see below) |

### License basis

The artifact's own embedded TFLite metadata buffer carries the license string
`Apache License. Version 2.0 http://www.apache.org/licenses/LICENSE-2.0.` together with the author
`TensorFlow` and the model description `EfficientDet Lite0 V1`, and the input description
"each value in the tensor is a single byte between 0 and 255". The embedded metadata only references
the license by URL, so the complete Apache-2.0 text is packaged alongside the binary at
`assets/models/efficientdet_lite0_LICENSE.txt` (identical to the repository copy at
`assets/licenses/apache-2.0.txt`). Attribution is to TensorFlow / the TensorFlow Lite Task Library,
which publishes this model under Apache-2.0. Redistribution keeps this provenance file, the license
text and the label map next to the binary.

No license field is present for the *raw-output* MediaPipe variant
(`mediapipe-models/object_detector/efficientdet_lite0/int8/latest`), and that variant was rejected
for this feature anyway: it has no built-in detection postprocess and would have required a bespoke
19,206-anchor decoder.

## Verified tensor contract (read from the packaged bytes)

Input (1 tensor):

- Shape `[1, 320, 320, 3]`, dtype `UINT8`
- Quantization: `scale = 0.0078125` (1/128), `zero_point = 127`
- Image normalization from metadata: mean `127.5`, std `127.5` over 0..255 channels, i.e.
  `real = (pixel - 127.5) / 127.5` in `[-1, 1]`. For the app's `[0, 1]` RGB floats:
  `q = clamp(round(value * 256 - 1), 0, 255)`
  (equivalently `q = round(real / scale) + zero_point`).

Outputs (4 tensors, `FLOAT32`, produced by the model's built-in
`TFLite_Detection_PostProcess` op — NMS and top-k happen inside the graph):

| # | Shape | Meaning |
| --- | --- | --- |
| 0 | `[1, 25, 4]` | boxes, normalized `ymin, xmin, ymax, xmax` over the full 320x320 tensor |
| 1 | `[1, 25]` | class category index (float-encoded integer) |
| 2 | `[1, 25]` | confidence score in `[0, 1]` |
| 3 | `[1]` | number of valid detections (<= 25) |

`LiteRtObjectDetector` re-verifies, at construction: asset byte count, SHA-256, presence of the
built-in `TFLite_Detection_PostProcess` op, the label-map category ids, input shape/dtype/
quantization, and the four pinned outputs by index (`0` boxes, `1` classes, `2` scores, `3` count)
validated by shape, dtype and the artifact's declared tensor name. It then performs a single
warm-up invocation. Nothing about the output layout is inferred from runtime values.

## Category indices

`classId` values are zero-based indices into the 90-line COCO label map (which has gaps), not a
compacted 0..79 index. The helper reports only: person `0`, bicycle `1`, car `2`, motorcycle `3`,
bus `5`, truck `7`; every other category is filtered out. No crosswalk (segmentation class) label is
inferred from this model.

## Runtime policy

- `org.tensorflow.lite.Interpreter` from the existing LiteRT dependency, CPU only, one thread
  (`setNumThreads(1)`), no GPU/NPU delegate. XNNPACK stays enabled because the reference CPU
  kernels take ~1.9 s per run on this build host versus ~40 ms with XNNPACK; with a single
  configured thread the host-measured CPU/wall ratio stays ~0.8-1.2 (4 threads: ~3.2-5.0), so the
  single-thread bound is what actually applies. This is host evidence only, not an Android
  threading or latency claim.
- Created, invoked, and closed on the auxiliary sidecar's single worker thread only.
- Public latency figures for this model family (for example the Pixel 6 benchmark in Google's
  MediaPipe documentation) are **not** claims about this app or about Fold5-class hardware. No device
  benchmark was run in this iteration.
