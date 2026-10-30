# Vision orientation evaluation

This evaluation compares the bundled Aria LiteRT model against pixel labels on a small, reproducible CamVid test subset. Each of eight source scenes is evaluated as both its original landscape frame and an upright 9:16 portrait center crop; all three strategies run on both orientations for 48 model conditions. It separates recognition quality from geometric coverage and does not treat agreement between two model runs as accuracy.

## Reproduce

The host evaluator uses only NumPy and Pillow. It downloads eight RGB frames and their 11-class grayscale labels from the CamVid test split, then creates matching portrait center crops from each frame and label without rotating them. Frames are sampled evenly across the sequences present in that split. These are paired derived orientations, not native portrait recordings. The host has no TensorFlow Lite execution runtime, so the real-model inference runs on the Pixel 3 instead of being substituted with host inference.

```powershell
py -3.15 -m venv build\vision-orientation-evaluation\.venv
build\vision-orientation-evaluation\.venv\Scripts\python.exe -m pip install -r tools\vision\evaluation\requirements.txt
build\vision-orientation-evaluation\.venv\Scripts\python.exe tools\vision\evaluation\evaluate_orientation.py self-test
build\vision-orientation-evaluation\.venv\Scripts\python.exe tools\vision\evaluation\evaluate_orientation.py download --count 8
```

The connected-test Gradle task may uninstall the app during cleanup, deleting its app-specific external files before they can be pulled. To preserve the outputs, build both APKs first, install them explicitly, stage fixtures, invoke the instrumentation runner directly, and pull results before any uninstall:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell mkdir -p /sdcard/Android/data/com.ariateam.aria/files/vision-orientation-evaluation/fixtures
adb push build\vision-orientation-evaluation\fixtures\. /sdcard/Android/data/com.ariateam.aria/files/vision-orientation-evaluation/fixtures/
adb shell am instrument -w -r -e class com.ariateam.aria.vision.VisionOrientationEvaluationTest com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.ariateam.aria/files/vision-orientation-evaluation/results build\vision-orientation-evaluation
```

The direct instrumentation command preserves app-specific external results long enough to pull them; do not run the uninstalling connected-test task between instrumentation and retrieval.

The test uses the optimized bundled model `walkable_seg_aria5_unfused.tflite` through `LiteRtVisionSegmenter`, requests GPU FP32, and records the backend that actually initialized (including an explicit CPU fallback). It performs three warmups and five measured model invokes for each of the 16 orientation fixtures and three strategies, writes the exact 512x288 inputs and grayscale argmax masks, and stores device timings in `results\run.json`. For each condition it also measures the production `LiteRtVisionSegmenter.decodeArgmaxAndMask` on actual model probabilities, using the matching `UprightCropRect` from `VisionFramePreprocessor.computeUprightCropRect`. The run manifest records 8 source scenes, 16 orientation fixtures, and 48 conditions. Place the pulled results under `build\vision-orientation-evaluation\results`, then run:

```powershell
build\vision-orientation-evaluation\.venv\Scripts\python.exe tools\vision\evaluation\evaluate_orientation.py summarize
```

The host writes `results\summary.json` and one source/input/inverse-mapped-overlay contact sheet per orientation fixture. Metrics and device timing are reported separately for landscape and portrait-center-crop inputs. Host scoring/rendering time is not inference timing; model and transform timing in the report is measured on the Android device.

## Strategies and scoring

* `legacy_bottom_crop`: independently apply the production 16:9 crop geometry to each orientation and resize it to 512x288. Vertical crops are bottom-aligned; horizontal crops are centered.
* `portrait_cw90_full_frame_letterbox`: rotate an input clockwise only when its height exceeds its width, then letterbox the full frame. The portrait center crop is rotated for model input and inverse-mapped counterclockwise into its original upright crop; landscape inputs are not rotated.
* `upright_full_frame_letterbox`: letterbox each input without rotation. On landscape inputs this is an equivalent control to `portrait_cw90_full_frame_letterbox`; the test verifies the exact model inputs match.

These three arms use the production `LEGACY_CROP`, `FULL_FRAME_ROTATED_LANDSCAPE`, and `FULL_FRAME_UPRIGHT_LETTERBOX` strategies and the exact `UprightCropRect` generated for each input. Crop dimensions use the production integer division and centering; letterbox content dimensions use nearest-integer rounding. Predictions are mapped into the original landscape scene coordinates using the recorded model-content and source rectangles plus inverse rotation and the portrait crop offset. Letterbox padding is excluded from all metrics. The report includes full visible-field scores and scores over the intersection visible to all three strategies within each orientation, per-class IoU/precision/recall, valid-pixel coverage, and separate device p50/p95 timings for raw model invocation and production argmax/decode. Landscape and portrait-center-crop results are reported separately. Bitmap fixture transform timing includes bitmap geometry plus conversion to normalized RGB float input, but excludes PNG I/O and CameraX `YUV_420_888` preprocessing; no YUV end-to-end benchmark is claimed. Production decode timing uses actual model probabilities and does not include output-buffer copying or overlay-bitmap allocation. Android bitmap interpolation may differ slightly from Pillow's resampling; the reported rectangles are checked against production geometry. Source images use decoded RGB pixels rather than CameraX YUV, so camera-specific YUV conversion is not measured. Synthetic tests verify asymmetric orientation transforms, paired landscape controls, portrait inverse mapping, content bounds, the Aria palette, and exclusion of padding from metrics.

CamVid grayscale IDs are `0 Sky`, `1 Building`, `2 Pole`, `3 Road`, `4 Sidewalk`, `5 Tree`, `6 SignSymbol`, `7 Fence`, `8 Car`, `9 Pedestrian`, `10 Bicyclist`, and `255 Void`. Mapping to the Aria contract is: Sky/Building/Tree/Fence to `other`; Road to `road`; Sidewalk to `sidewalk`; Pedestrian to `person`; Car to `vehicle`; Pole/SignSymbol to `pole_sign`; Void and Bicyclist ignored. CamVid does not provide a separate crosswalk label, so Aria's `crosswalk` class is marked unavailable and excluded from macro IoU. Sidewalk may include paved areas and `other` aggregates four CamVid labels. These cross-dataset category compromises and the small sample preclude a trained-accuracy claim.

## Data provenance and rights

The selected files are fetched from the CamVid mirror repository [`lih627/CamVid`](https://github.com/lih627/CamVid), pinned to commit `79ae0071a75d90331a62b5a2aaf6d53cc69fd9f6`; the list is its `camvid_test.txt`. Each downloaded image and label URL plus SHA-256 is recorded in the generated fixture manifest. The mirror repository includes an MIT license, but that license does not independently establish the licensing terms of the underlying Cambridge CamVid imagery; the dataset is used here for local evaluation only and is not bundled into the repository.

## Results

The actual focused instrumentation run passed both tests in 83.183 seconds on a Pixel 3 (Android 12 / API 31). The optimized `walkable_seg_aria5_unfused.tflite` model (`84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847`) ran on the LiteRT GPU backend; logcat reported all 210/210 nodes delegated in one GPU partition. The results contain 8 scenes, 16 orientation fixtures, 48 conditions, three warmups and five measured runs per condition. The JSON, PNG inputs/masks, and 16 contact sheets are generated under ignored `build\vision-orientation-evaluation\results` and are intentionally not committed with the dataset imagery.

**Compare strategies on the same visible pixels.** The full-field column has different coverage for crop and letterbox strategies and is descriptive, not a fair direct accuracy comparison. Use common-region mIoU to compare strategies within an orientation; every strategy is scored against the same source-coordinate pixels in that region. Coverage is the share of all mapped source-label pixels visible to a strategy (or to all three for the common region).

| Orientation | Strategy | Full-field mIoU (coverage) | Common-region mIoU (coverage) |
|---|---|---:|---:|
| Landscape | Legacy bottom crop | 0.652 (74.4%) | 0.652 (74.4%) |
| Landscape | CW90-if-portrait letterbox | 0.560 (100%) | 0.555 (74.4%) |
| Landscape | Upright letterbox | 0.560 (100%) | 0.555 (74.4%) |
| Portrait center crop | Legacy bottom crop | 0.083 (13.2%) | 0.083 (13.2%) |
| Portrait center crop | CW90 letterbox | 0.131 (41.9%) | 0.059 (13.2%) |
| Portrait center crop | Upright letterbox | 0.419 (41.9%) | 0.234 (13.2%) |

On landscape, the two letterbox controls produced identical inputs and prediction masks for all eight scenes. Against legacy on the shared 74.4% region, letterboxing was 9.6 mIoU points lower overall, while exposing an additional 25.6 percentage points of labeled source area; this is a measured coverage/segmentation tradeoff, not evidence that one strategy is universally better. In the portrait center crops, the common region is only 13.2% of mapped labels because the portrait legacy arm bottom-crops before inference. CW90 scored 0.059 there, below legacy at 0.083; upright letterboxing scored 0.234. The full-field portrait scores must not be read as a same-area comparison.

The per-class common-region IoUs explain the portrait difference:

| Orientation / strategy | Other | Road | Sidewalk | Person | Vehicle | Pole/sign |
|---|---:|---:|---:|---:|---:|---:|
| Landscape legacy | 0.887 | 0.832 | 0.652 | 0.475 | 0.763 | 0.301 |
| Landscape letterbox (both controls) | 0.808 | 0.818 | 0.611 | 0.254 | 0.582 | 0.259 |
| Portrait legacy | 0.002 | 0.459 | 0.018 | 0.000 | 0.018 | 0.000 |
| Portrait CW90 | 0.001 | 0.348 | 0.000 | 0.000 | 0.001 | 0.000 |
| Portrait upright | 0.007 | 0.608 | 0.016 | 0.000 | 0.672 | 0.098 |

The portrait CW90 contact sheets visibly show sky classified as sidewalk and poor vehicle recovery versus the upright arm. Upright portrait is therefore the stronger candidate of these two portrait transforms in this small sample; the requested CW90 correction is not supported by these results. This does not establish that upright portrait is production-ready: person and sidewalk IoU remain weak, only 8 derived center crops were evaluated, and CamVid labels are an imperfect cross-dataset match.

Paired per-scene deltas below are each alternative's common-region mIoU minus legacy common-region mIoU. They compare the same scene and the same valid pixels; the per-scene mean is unweighted.

| Source scene | Landscape letterbox delta | Portrait CW90 delta | Portrait upright delta |
|---|---:|---:|---:|
| 0001TP_008760 | -0.108 | -0.074 | +0.248 |
| 0001TP_009240 | -0.095 | -0.083 | +0.079 |
| 0001TP_009690 | -0.115 | -0.065 | +0.232 |
| 0001TP_010170 | -0.145 | -0.064 | -0.002 |
| Seq05VD_f00630 | -0.005 | +0.389 | +0.159 |
| Seq05VD_f01920 | +0.034 | -0.183 | -0.097 |
| Seq05VD_f03180 | +0.048 | -0.067 | +0.241 |
| Seq05VD_f04470 | -0.207 | -0.167 | +0.044 |
| **Mean delta; scenes won** | **-0.074; 2/8** | **-0.039; 1/8** | **+0.113; 6/8** |

Device timing is shown as p50/p95 milliseconds over five invocations for each of eight scenes (40 samples per orientation/strategy); transform timing has eight samples. The test ran strategies sequentially in a fixed order on a small sample. Treat timing as descriptive only: it does not demonstrate a statistically reliable speed change, sustained FPS, or end-to-end CameraX performance.

| Orientation / strategy | Raw model invoke | Bitmap fixture transform | Production argmax/decode |
|---|---:|---:|---:|
| Landscape legacy | 98.3 / 116.8 | 44.8 / 125.7 | 9.1 / 19.0 |
| Landscape CW90 control | 91.5 / 110.6 | 44.6 / 45.2 | 12.2 / 37.0 |
| Landscape upright control | 92.0 / 133.9 | 44.6 / 45.0 | 13.2 / 37.7 |
| Portrait legacy | 98.2 / 118.2 | 44.0 / 47.2 | 8.7 / 18.2 |
| Portrait CW90 | 90.7 / 118.1 | 47.7 / 50.3 | 21.4 / 60.7 |
| Portrait upright | 90.6 / 117.2 | 44.4 / 45.0 | 11.8 / 36.7 |

Raw invocation excludes output decoding. Production argmax/decode measures `decodeArgmaxAndMask` on actual model probabilities, with the corresponding production `UprightCropRect`, and excludes output-buffer copying and overlay-bitmap allocation. Bitmap fixture transform is not CameraX YUV preprocessing. No default preprocessing strategy was changed by this evaluation.

Contact sheets, including the reviewed landscape and portrait examples, are `build\vision-orientation-evaluation\results\contact_sheets\<scene>--<orientation>.jpg`. Full per-frame and per-class counts, confusion matrices, timings, model metadata, and checksums are in `summary.json` and `run.json`.
