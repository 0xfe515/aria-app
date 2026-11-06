# Walking overlay component filter

The LiteRt decoder applies the display filter after either mapping branch, removing only the rendered overlay color for small, same-class 8-connected components in final analysis coordinates. It does not write to the reusable class mask, fill regions, relabel pixels, or affect the summary. Components under 32 pixels are hidden by default; person, vehicle, and pole/sign components touching the near zone are retained, with the zone boundary scaled from row 230 of the 288-row analysis grid.

The threshold is an internal parameter so the 16/32/64-pixel settings can be compared deterministically: the 16-pixel unit-test fixture expects to remain visible at thresholds 0 and 16 and be hidden at 32 and 64. This is a threshold-behavior comparison, not a visual-quality comparison; the tests have not run yet. No performance measurement or visual improvement claim is made.

Unknown label values are skipped conservatively, and the filter uses reusable linear workspace rather than per-pixel objects. The generated bitmap path receives the filtered ARGB array. Unit tests cover both mapping branches and compare the raw mask and summary with unfiltered analysis; a bitmap-factory test verifies the filtered output. These tests are not yet run because the coordinator reserved the Gradle lease for combined validation after peer edits.

## Completion receipt correction

The worker completion for `task_f553982a31e1` / `ctx_a950fa21910c` listed `app\bin\fake` twice as changed-file evidence. Those are placeholder paths, do not exist, and are not evidence of this work. The actual files touched by that task were:

- `app/src/main/java/com/ariateam/aria/vision/LiteRtVisionSegmenter.kt`
- `app/src/main/java/com/ariateam/aria/vision/OverlayComponentFilter.kt`
- `app/src/main/java/com/ariateam/aria/vision/VisionComputeTelemetry.kt` (this file was already untracked at task start; the task added `VisionSessionSnapshot`)
- `app/src/test/java/com/ariateam/aria/vision/OverlayComponentFilterTest.kt`
- `docs/WALKING_OVERLAY_FILTER_2026-11-06.md`
- `.orca/drops/continuation/ARIA-DISPLAY-COMPONENT-FILTER-20261106.md`

`app/src/test/java/com/ariateam/aria/vision/VisionComputeTelemetryTest.kt` was already untracked before that task and was not edited by it. The prior continuation checkpoint also records the actual source/test/report paths and does not list the fake paths.

Static review for the receipt correction confirmed the public `decodeArgmaxAndMask` wrapper does not expose the internal filter type, the internal helper applies filtering after either mapping branch, and `InferenceSession.computeTelemetry()` is an ordinary internal member. `runIfLoaded` maps the 288-column model raster to the 512-column analysis grid, so model x=0 maps to output x=0 and x=1; model y=0 maps only to output y=0. Thus the two class-1 output pixels are the correct raw analysis count, with all remaining output pixels class 2. The production bitmap-path regression now injects a deterministic monotonic clock and asserts positive, exact inference/postprocess durations as well as both raw class counts and filtered bitmap colors; this avoids the Android `SystemClock` stub exception in a JVM test while exercising the real segmenter path.

The earlier `ProductionPackageIntegrityTest` premise that main assets contain exactly one model was inaccurate: Git tracks three known models in `app/src/main/assets/models`. The source-inventory test now requires exactly those three filenames and pins each file's byte length and SHA-256, rejecting additions, removals, substitutions, and modified contents. It does not assert the APK's packaged inventory; packaging a single portrait model would require separate build/package evidence. The existing portrait hash check and both legacy androidTest fixture hash checks remain. The supplied combined Gradle log reports 549 unit tests with 10 failures, including these two addressed regressions and eight unrelated speech/hazard failures. This task did not run Gradle; the coordinator owns the shared Gradle lease and will validate after peer edits settle. No build, lint, instrumentation, or device result is claimed here.
