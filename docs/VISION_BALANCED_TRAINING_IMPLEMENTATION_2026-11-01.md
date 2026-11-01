# Seven-Class Balanced Vision Training: Implementation Record

**Status:** Pilot evaluated; no deployable candidate
**Orca Run:** `run_6e465bb6be1b`
**Priority:** Improve crosswalk learning while preserving the other six semantic classes across Surface and Cityscapes.

## Evidence Before This Run

- Full-pool randomized A/B training exposed both models to `1,304,885` real crosswalk pixels during the common mixed phase. Neither predicted a crosswalk pixel on the Surface validation set, which contains `1,399,674` real crosswalk pixels. The failure is not explained by missing crosswalk records. See [random sampling experiment](VISION_RANDOM_SAMPLING_EXPERIMENT_2026-10-31.md).
- Those A/B arms had different total Surface/City exposure and only one seed. Their scores do not isolate the effect of curriculum or establish a final model.
- City warmup `attempt_02` did complete five epochs with exit code `0`, but used outdated weights `[0.5, 0.5, 0.5, 3.0, 0.5, 0.5, 0.5]`. Its City validation mIoU was `66.72%`; it is retained as historical evidence and is not a corrected production predecessor. See [warmup completion audit](../tools/vision/training/FULL_TRAINING_RUN.md#7-completion-audit-2026-11-01).
- Training manifest SHA-256: `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`.
- The current Android portrait baseline is `walkable_seg_aria5_portrait_unfused.tflite`, SHA-256 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`.

## Current Protocol

1. Verify that class ID 3 is decoded and supervised correctly; measure its loss/gradient and evaluate existing checkpoints on the complete Surface validation split. Never use test labels for diagnosis.
2. Give record ordering and augmentation separate deterministic seeds, with augmentation fixed per record and epoch so changing sampling order cannot alter the transform.
3. Retrain `city_warmup` with the corrected class weights into a fresh attempt directory. Preserve historical artifacts. Use the mixed 50:50 stages only after the diagnostic and review per-domain, per-class validation metrics between stages; do not run the old 30-epoch City-only stage without a Surface check.
4. Export the candidate and compare it with the app baseline on identical real-label validation records. The comparison must include seven-class IoU, Surface false-safe rate, and City person/vehicle/pole-sign IoU. Keep the test split untouched until a candidate is selected.
5. Apply FP16/INT8 only if both semantic quality and measured Pixel 3 latency/memory improve. Replace app assets only after the candidate passes the existing evaluation contract.

Existing evaluation guardrails apply where supported: Surface mIoU improvement of at least `0.03`, false-safe rate at most `0.8 ×` baseline, and no more than `0.01` City IoU loss for person, vehicle, or pole/sign. Report crosswalk IoU separately because City has no crosswalk ground truth. Do not treat nonzero crosswalk IoU alone as deployment approval.

## Execution Status

- [x] Confirmed the prior City warmup is complete and its class-weight configuration is outdated.
- [x] Confirmed the current app model path and SHA-256; Pixel 3 `8B7X15A0F` is connected.
- [x] Dispatched isolated Orca tasks for the crosswalk gradient diagnostic, RNG separation, and TFLite validation prediction adapter. Coordinator owns GPU training and device work.
- [x] Independently review worker diffs and run affected Python tests.
- [x] Diagnose both preserved pilot checkpoints against Surface validation.
- [x] Complete corrected `city_warmup` (5/5 epochs; attempt_03 exit code 0; epoch-5 City mIoU 67.20%).
- [x] Complete the `city_full` 1-epoch bridge and `mixed_decoder` 1-epoch pilot; compare all 1,882 validation records and apply the per-domain guardrails. The mixed candidate fails the City object IoU guardrail.
- [x] Compare the corrected warmup export and baseline on all 1,882 validation records (semantic metrics).
- [ ] Run Pixel 3 model and full-pipeline benchmarks; compression decision.
- [ ] Freeze candidate, perform final test evaluation, and complete report.

## Validation Limitations

The implementation layer is independently verified: `test_train_data.py` passed
34 tests (6 symlink-related skips), `test_strategy_experiment.py` passed 12,
`test_crosswalk_diagnostic.py` passed 5, and `test_predict.py` passed 7. All
four changed Python modules compile. The RNG update preserves the exact legacy
augmentation stream when `augmentation_seed` is omitted and uses stable
per-record streams only when it is explicit. Full-set diagnostics completed
for both preserved pilot checkpoints. On all 1,457 Surface validation records,
arm A reached 16.87% mIoU and arm B 20.80%; neither predicted a crosswalk pixel,
despite finite, nonzero class-3-only logit and trainable-parameter gradients on
real crosswalk pixels (40,416 pixels in the selected training example for each
arm). These are short experimental pilots, not full-training candidates. The
app baseline's full validation inference and evaluation have completed. On the
1,457-image Surface validation split, the baseline reached 28.52% mIoU,
crosswalk IoU 44.51%, and nonwalkable-to-walkable false-positive rate 19.52%;
City mIoU was 58.92%, with person/vehicle/pole-sign IoU 65.25%/82.24%/31.22%.
This historical-baseline comparison carries the documented possible test-data
exposure caveat and is not a clean holdout. Corrected warmup `attempt_03`
completed all five epochs with exit code 0. Epoch 5 recorded train loss
0.8126, City validation loss 0.7878, and mIoU 0.6720. Corrected City-only
class weights were `[0.5, 0.5, 0.9090, 1.0, 1.9989, 0.7958, 1.5898]` in
canonical class order. Its best checkpoint exported to
`/home/user/.local/share/aria-training/full-training-20261031/attempt_03/warmup_model_a_fp32.tflite`
(SHA-256 `e8ee8b7070fda27727c3e493e20330539313452d18309202f0601d3d4c51ba02`);
Keras-to-TFLite numeric parity passed at tolerance 1e-4. Full validation
prediction ran over all 1,882 validation records from the evaluation-only
offline manifest; no test records were used. A vectorized semantic-only
recalculation of the saved predictions matched the official baseline evaluator
exactly. Corrected warmup results were Surface mIoU 7.77%, crosswalk IoU 0%,
and false-safe 10.72%; City mIoU 67.20%, with person/vehicle/pole-sign IoU
57.46%/77.30%/27.48%. Relative to the baseline this is a severe Surface
regression and City object IoU losses of 7.79, 4.94, and 3.75 percentage
points, respectively, so the warmup export is rejected as a candidate. CUDA
workspace allocation warnings during export were non-fatal.

Because the warmup export failed the Surface acceptance check, full 30-epoch
City training is skipped. A single `city_full` bridge epoch is running in
`aria-city-full-attempt-03.service` (WSL PID 565, start ticks 8422); Windows
keeper PID 13640 is confirmed alive and bound to that exact process identity.
The bridge initializes only from the corrected `city_warmup` best checkpoint
and writes to the fresh `attempt_03/city_full` directory. After completion, the
planned next step is one 50:50 `mixed_decoder` pilot epoch, followed by a
Surface and City validation gate. No mobile asset changes or compression are
supported by the current evidence.

For offline evaluation only, a derived manifest at
`data/manifest_offline_eval_20261101.json` retains all 13,033 records, labels,
groups, split assignments, converted tensor paths, and image hashes from the
base manifest (`e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`).
It changes only `image_path` to the record's ext4 converted image, allowing
`augment=False` inference without reopening raw files through the slow WSL
Windows mount. Its SHA-256 is
`1c48156c449177ddab4cb80b35428e065b36576e1d5ca108bcf34569169424c1`; training
continues to use the unmodified base manifest.

The initial Pixel 3 benchmark attempt was blocked by Gradle errors in then-existing uncommitted Android edits. Those unrelated user changes were preserved. The checkout later built successfully, and a controlled CPU4 baseline/candidate device benchmark was completed on 2026-11-02; see the device benchmark section below.

The continuation launcher now supports explicit stages and bounded epoch counts, validates predecessor checkpoint metadata, and protects existing run directories. Its 25 focused unit tests and Python compilation check passed under the repository Python 3.11 environment. The City bridge process remained alive at the latest check (20:25 KST, WSL PID 565, same start ticks 8422); it was using CPU/GPU and reading data, but had not yet emitted an epoch progress line or exit code. The bridge is left running, and the mixed decoder pilot must wait for its checkpoint and completion status.

Follow-up at approximately 20:29 KST: initial validation has completed and generated both `city_full/checkpoints/latest` and `best` with metadata epoch 0. The process is still active (PID 565, start ticks 8422) and has not yet logged its first training batch; no epoch-1 checkpoint or exit code exists yet.

The `city_full` bridge has now finished with exit code 0 after 1,275 training batches. It recorded train loss 0.7617, City validation loss 0.7588, City validation mIoU 0.6845, and checkpoint metadata epoch 1/global_step 1275. This is a stage-selection metric only; it does not establish a crosswalk or full validation-set improvement. The `mixed_decoder` pilot remains gated on the launcher filename fix and will then use this checkpoint.

The first `mixed_decoder` launch was safely refused before starting because the global `pgrep` probe matched its own shell wrapper; an accompanying Windows cp949 decode error affected diagnostic output. The launcher now uses the self-match-safe `[r]un_wsl_training.py` regex and explicitly decodes WSL text as UTF-8 with replacement for malformed bytes. Added regression coverage for both, plus the actual `model_weights.weights.h5` checkpoint name. The focused launcher suite now passes 34 tests and `py_compile` succeeds in the root checkout.

The one-epoch `mixed_decoder` pilot has since started from the validated `city_full` epoch-1 checkpoint at learning rate 0.0003. At launch, the durable systemd unit was `aria-mixed-decoder-20261031-attempt-03.service`, WSL trainer PID 489/start ticks 1546, and Windows keeper PID 10424 was alive and bound to that exact trainer identity. No output checkpoint or validation result exists yet; GPU use is exclusive until this stage ends.

At 23:02 KST the mixed-stage baseline checkpoint records epoch 0/global_step 0 with validation loss 3.9462 and mixed validation mIoU 0.3136. The one training epoch is active at batch 1,241/6,647; recent train micro-batch losses vary, and no epoch result is available yet. GPU memory remains around 4.7 GiB on the 6 GiB GTX 1660 SUPER, with the durable keeper still active.

The mixed decoder epoch has advanced to 2,941/6,647 batches (approximately 44%) at 23:25 KST. Its initial mixed validation mIoU remains only the epoch-0 reference (31.36%); the post-training validation and full held validation-set inference are still pending.

The one-epoch `mixed_decoder` stage completed with exit code 0 at epoch 1/global_step 6647. Train loss was 0.8901, mixed validation loss 0.7689, and native mixed validation mIoU 0.6266 (up from the stage's epoch-0 reference 0.3136). The best checkpoint exported to FP32 TFLite at 21,515,372 bytes, SHA-256 `81499aca17a0c55111da93a8e214b50985215be162af13c8064f467abcf1bb92`; Keras-to-TFLite parity passed tolerance 1e-4. Full offline evaluation prediction over the 1,882 validation records is running on CPU; no compression or app model replacement has been made.


## Mixed Decoder Pilot: Full Validation Gate (2026-11-02)

The `mixed_decoder` epoch completed with exit code 0, 6,647 optimizer micro-steps, train loss `0.8901`, and native mixed validation loss/mIoU `0.7689`/`62.66%`. Its FP32 TFLite export is `21,515,372` bytes (about `20.5 MiB`), SHA-256 `81499aca17a0c55111da93a8e214b50985215be162af13c8064f467abcf1bb92`; export parity passed at tolerance `1e-4`.

The acceptance-eligible offline prediction file contains all 1,882 validation records (1,457 Surface, 425 Cityscapes); no test records were used. Semantic metrics use real-label provenance. The Surface and City values below are evaluated independently on the same validation split as the historical baseline:

| Metric | Existing app model | Mixed decoder | Change | Gate |
|---|---:|---:|---:|---|
| Surface mIoU | 28.5209% | 33.4960% | +4.9751 pp | Pass (minimum +3 pp) |
| Surface crosswalk IoU | 44.5104% | 45.2481% | +0.7377 pp | Reported separately; City has no crosswalk ground truth |
| Surface false-safe rate | 19.5161% | 14.6943% | −4.8218 pp; 0.753× baseline | Pass (maximum 0.8× baseline) |
| City mIoU | 58.9226% | 57.6518% | −1.2708 pp | Overall semantic score declined |
| City person IoU | 65.2506% | 55.5631% | −9.6875 pp | Fail (allowed loss ≤1 pp) |
| City vehicle IoU | 82.2367% | 79.4430% | −2.7937 pp | Fail (allowed loss ≤1 pp) |
| City pole/sign IoU | 31.2234% | 29.0242% | −2.1992 pp | Fail (allowed loss ≤1 pp) |

This pilot corrects the warmup's zero-crosswalk result and passes both Surface gates, but fails all three City object-retention limits. Therefore it is **not an app replacement candidate**; do not advance to `mixed_block13`, quantize it, or replace the current TFLite asset based on this run. The candidate architecture has no risk head, so risk-head calibration and risk metrics remain unsupported. The file is useful evidence for the next training iteration, not deployment approval.

FP16/INT8 compression remains unevaluated. The later Pixel 3 CPU4 benchmark found the candidate substantially slower and larger than the existing app model; see the device benchmark section. The production app source and model asset remain unchanged.

## Pixel 3 CPU4 Runtime Benchmark (2026-11-02)

Both models were measured with the same `VisionPipelineController` camera path on Pixel 3 (Android 12 / API 31), LiteRT CPU FP32 with XNNPACK and four threads, 5-second warmup, and a 60-second steady window. The candidate was imported from its separately staged TFLite file; its SHA-256 matched the training export. Voice service was stopped for both runs. Thermal status began at `0` for both; baseline ended at `1` (battery 31.1°C to 35.4°C), candidate remained at `0` (30.5°C to 33.0°C).

| Metric | Existing app model | Mixed decoder candidate | Candidate change |
|---|---:|---:|---:|
| Model file size | 10,839,936 B | 21,515,372 B | 1.99× |
| Analyzed segmentation throughput | 7.04 FPS | 0.95 FPS | 7.4× slower |
| Model invoke latency, median / p95 | 89.93 / 102.37 ms | 1,003.02 / 1,019.33 ms | 11.2× / 10.0× slower |
| Full analyzer latency, median / p95 | 126.55 / 141.70 ms | 1,024.61 / 1,041.08 ms | 8.1× / 7.4× slower |
| Camera-to-result age, median / p95 | 281.25 / 318.44 ms | 1,163.86 / 1,205.21 ms | 4.1× / 3.8× slower |
| App process PSS | 320.59 MiB | 416.32 MiB | +95.73 MiB (+29.9%) |
| Sensor frame rate during run | 15.53 FPS | 14.95 FPS | −3.7% |

The baseline was `walkable_seg_aria5_portrait_unfused.tflite` (SHA-256 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`). The candidate was `mixed_decoder_model_a_fp32.tflite` (SHA-256 `81499aca17a0c55111da93a8e214b50985215be162af13c8064f467abcf1bb92`). Both instrumentation runs passed the benchmark assertions. The baseline result was taken from the `BASELINE_CPU4_JSON` instrumentation log marker; the device's Download report file was stale from an earlier run, so it was not used as baseline evidence.

**Decision:** the candidate does not meet mobile runtime needs on this Pixel 3 CPU path. The candidate processes about one segmentation frame per second and uses roughly 96 MiB more process memory; combined with its validation regressions, it should not replace the current app model. Quantization was not tested and cannot be assumed to close a roughly 7.4× throughput gap.