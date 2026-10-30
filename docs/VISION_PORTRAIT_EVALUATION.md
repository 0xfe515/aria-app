# Portrait-model evaluation

This evaluates the unchanged optimized landscape model against the same-weight
portrait-shape export on the existing eight CamVid test scenes. Each scene is
run in its landscape source form and its existing upright 9:16 center crop,
giving 16 orientation fixtures and 64 model conditions across four arms:

| Arm | Model input geometry | Tensor shape |
|---|---|---|
| `legacy_bottom_crop` | Landscape model; legacy crop | `[1,288,512,3]` |
| `portrait_cw90_full_frame_letterbox` | Landscape model; rotate only portrait samples | `[1,288,512,3]` |
| `upright_full_frame_letterbox` | Landscape model; do not rotate | `[1,288,512,3]` |
| `portrait_model_upright_letterbox` | Portrait model; keep source upright | `[1,512,288,3]` |

Both models must return one float32 tensor with seven classes in the same Aria
class order. The portrait model and its `audit.json` are produced separately;
this evaluation does not retrain, modify the bundled model, or change production
preprocessing defaults. Existing fixtures and their manifest are reused, with no
dataset download:

```text
build\vision-orientation-evaluation\fixtures\
build\vision-portrait-model\walkable_seg_aria5_portrait_unfused.tflite
build\vision-portrait-model\audit.json
```

## Device run

The coordinator owns all Gradle, adb, install, and device operations. First
build and validate the app and instrumentation APKs; stage the existing
fixtures, portrait model, and audit; then invoke the one focused test directly.
Pull app-specific results before running any connected-test task or uninstall
that could erase them:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell mkdir -p /sdcard/Android/data/com.ariateam.aria/files/vision-orientation-evaluation/fixtures
adb push build\vision-orientation-evaluation\fixtures\. /sdcard/Android/data/com.ariateam.aria/files/vision-orientation-evaluation/fixtures/
adb shell mkdir -p /sdcard/Android/data/com.ariateam.aria/files/vision-portrait-evaluation/model
adb push build\vision-portrait-model\walkable_seg_aria5_portrait_unfused.tflite /sdcard/Android/data/com.ariateam.aria/files/vision-portrait-evaluation/model/portrait.tflite
adb push build\vision-portrait-model\audit.json /sdcard/Android/data/com.ariateam.aria/files/vision-portrait-evaluation/model/audit.json
adb shell am instrument -w -r -e class com.ariateam.aria.vision.VisionPortraitModelEvaluationTest com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.ariateam.aria/files/vision-portrait-evaluation/results build\vision-portrait-evaluation\results
```

The instrumentation test verifies all staged fixture hashes and upright center
crops, the baseline asset SHA-256, the portrait model hash against its audit,
and the actual input/output tensor shapes. It performs three warmups and five
measured invocations per condition, recording direct LiteRT invocation,
per-pixel argmax, and bitmap-plus-RGB-input preparation timings separately.
It records device/runtime versions, model hashes and shapes, actual backend,
precision mode, thread count, raw timings, model inputs, and argmax masks.

The portrait model is compared on CPU and GPU in FP32 for a blank synthetic
input, an asymmetric synthetic input, and upright crops from two real scenes.
GPU is accepted only when every comparison has maximum absolute output delta
at most `0.001`, mean absolute delta at most `0.0001`, class agreement at least
`99.99%`, and walkable/non-walkable agreement at least `99.99%`, matching the
existing FP32 GPU validation tolerance. If GPU is unavailable
or fails parity, the run records why and uses CPU FP32 as the explicit portrait
evaluation fallback. The test does not claim GPU delegation from delegate
construction alone; check device logcat for delegation evidence.

## Host scoring and interpretation

Use the existing evaluation virtual environment, which already has NumPy and
Pillow installed:

```powershell
build\vision-orientation-evaluation\.venv\Scripts\python.exe tools\vision\portrait_evaluation\evaluate_portrait.py self-test
build\vision-orientation-evaluation\.venv\Scripts\python.exe tools\vision\portrait_evaluation\evaluate_portrait.py summarize
```

The host scorer rechecks fixture provenance and crops, model shapes and hashes,
the audit reference, per-arm tensor dimensions, source rectangles, padding
bounds, and the old-model landscape control equivalence. It maps all four
argmax masks independently into the original scene coordinates and computes
full-visible and four-arm common-visible mIoU, per-class IoU/precision/recall,
coverage, and paired per-scene common-region mIoU deltas versus legacy.
Letterbox padding is excluded from scoring. The output is
`build\vision-portrait-evaluation\results\summary.json` plus one four-arm visual
contact sheet per scene and orientation.

CamVid grayscale labels use the existing evaluation mapping: Sky/Building/
Tree/Fence to `other`; Road to `road`; Sidewalk/Pavement to `sidewalk`;
Pedestrian to `person`; Car to `vehicle`; Pole/SignSymbol to `pole_sign`; Void
is ignored; Bicyclist and the unavailable separate crosswalk class are excluded.
The host tests use synthetic asymmetric markers to verify portrait dimensions,
upright source geometry, inverse mapping, intersection across all four arms,
and exclusion of padding.

Timing is descriptive only: the four arms execute in fixed order with five
samples after three warmups, on a small device sample. It cannot establish a
statistically reliable speedup, sustained frame rate, or CameraX/YUV end-to-end
performance. The eight portrait fixtures are derived center crops rather than
native portrait recordings, and CamVid labels do not establish Aria model
accuracy or production readiness.

## Paired device results

The direct Pixel 3 run (Android 12 / API 31) passed all three instrumentation
tests in 104.167 seconds. Both models used LiteRT GPU FP32 with precision loss
disabled and four configured CPU threads; coordinator logcat reported all 210
nodes delegated in one GPU partition. The landscape baseline SHA-256 remained
`84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847`; the
portrait model SHA-256 is
`312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`. Its
final audit (`b0641134f6f970a80b884d478a12e94316f9f2b0bbdddff7121ec0bd1436f088`)
records unchanged learned and normalization weights and unchanged model
metadata, with input/output tensors `[1,512,288,3]` and `[1,512,288,7]`.

All four portrait CPU/GPU FP32 parity fixtures passed the strict thresholds:
synthetic blank, synthetic asymmetric, and two real upright portrait crops.
Maximum observed output delta was `0.000087351`; maximum MAE was
`0.000002975`; minimum class agreement was `99.99932%`; walkable agreement was
`100%`. GPU was therefore accepted for the portrait-model evaluation.

The table reports aggregate mIoU over mapped CamVid labels. Full-field coverage
is valid source-label coverage; common-region mIoU uses the same source
coordinates visible to all four arms (74.4% of mapped labels for landscape,
13.2% for portrait). Timing p50/p95 is in milliseconds; invocation excludes
output decoding, argmax is measured separately on raw model output, and
transform includes Android bitmap preparation plus normalized RGB conversion.

| Orientation | Arm | Full-field mIoU (coverage) | Common-region mIoU | Invoke p50/p95 | Argmax p50/p95 | Transform p50/p95 |
|---|---|---:|---:|---:|---:|---:|
| Landscape | Legacy bottom crop | 0.652 (74.4%) | 0.652 | 103.7 / 135.1 | 56.0 / 117.2 | 45.4 / 48.9 |
| Landscape | CW90-if-portrait letterbox | 0.560 (100%) | 0.555 | 103.6 / 121.6 | 56.0 / 115.4 | 44.7 / 45.6 |
| Landscape | Upright letterbox | 0.560 (100%) | 0.555 | 108.9 / 125.2 | 56.1 / 114.3 | 44.7 / 44.9 |
| Landscape | Portrait model, upright | 0.489 (100%) | 0.495 | 144.9 / 152.8 | 55.9 / 118.0 | 44.2 / 44.3 |
| Portrait crop | Legacy bottom crop | 0.083 (13.2%) | 0.083 | 102.9 / 125.5 | 56.1 / 118.6 | 43.4 / 43.9 |
| Portrait crop | CW90 letterbox | 0.131 (41.9%) | 0.059 | 103.6 / 111.7 | 56.0 / 114.5 | 47.8 / 48.0 |
| Portrait crop | Upright letterbox | 0.419 (41.9%) | 0.234 | 123.4 / 133.6 | 56.0 / 115.2 | 44.4 / 44.7 |
| Portrait crop | Portrait model, upright | 0.591 (41.9%) | 0.458 | 140.7 / 159.6 | 56.0 / 118.3 | 44.9 / 45.2 |

The portrait model is the strongest arm on the portrait crops in this sample:
its common-region mIoU is 0.458 versus 0.234 for the old model's upright
letterbox, a `+0.224` difference on the same visible pixels. The portrait model
beats legacy common-region mIoU on all eight portrait scenes (unweighted mean
per-scene delta `+0.446`), but loses to legacy on all eight landscape scenes
(mean `-0.156`). Its landscape common-region mIoU is 0.495 versus 0.555 for
the landscape model's upright letterbox. These results suggest orientation
specialization, not a universal replacement; no production default changed.

Per-scene common-region mIoU deltas below compare the portrait model with the
legacy arm on the same source scene and valid pixels:

| Source scene | Landscape delta | Portrait-crop delta |
|---|---:|---:|
| `0001TP_008760` | -0.104 | +0.534 |
| `0001TP_009240` | -0.105 | +0.205 |
| `0001TP_009690` | -0.191 | +0.555 |
| `0001TP_010170` | -0.285 | +0.400 |
| `Seq05VD_f00630` | -0.097 | +0.697 |
| `Seq05VD_f01920` | -0.096 | +0.354 |
| `Seq05VD_f03180` | -0.067 | +0.478 |
| `Seq05VD_f04470` | -0.302 | +0.346 |

The complete summary includes per-scene deltas for all arms and per-class
IoU/precision/recall for both full-field and common-region scores. In the
portrait model's common region, per-class `(IoU / precision / recall)` was:
`other` `0.012 / 0.012 / 0.571`, `road` `0.937 / 0.990 / 0.946`,
`sidewalk` `0.455 / 0.545 / 0.733`, `person` `0.378 / 0.477 / 0.647`,
`vehicle` `0.605 / 0.934 / 0.632`, and `pole_sign` `0.360 / 0.446 / 0.652`.
Crosswalk remains excluded because CamVid has no separate compatible label.

The run used a fixed arm order and five invocations per condition; these
timings do not support a reliable speedup claim. All raw device data,
hashes, transforms, parity evidence, masks, and the 16 contact sheets are in
ignored `build\vision-portrait-evaluation\results`. The sample is small and
the common portrait region is only 13.2% of labeled pixels, so the apparent
portrait recognition improvement needs confirmation on broader, native
portrait data before any product decision.
