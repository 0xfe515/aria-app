# Portrait training evaluation contract

This module evaluates already-saved predictions and labels. It does not import
TensorFlow, load a model, run inference, launch training, or claim that any
candidate has been trained or improved. Reports set
`trained_model_quality_claimed` to `false`; missing required metrics,
unit-test fixtures, or validation-only reports cannot pass the acceptance
gates.

## Evidence and denominators

The semantic order is the app-verified seven-class order:
`other, road, sidewalk, crosswalk, person, vehicle, pole_sign`. Only
`sidewalk` and `crosswalk` (IDs 2 and 3) are walkable. Semantic confusion
matrices are truth-row / prediction-column and are accumulated independently
for each domain. Per-class IoU is `TP / (GT + predicted - TP)`; mIoU is the
arithmetic mean over classes with a nonzero union. Undefined class IoUs are
reported as `null`, not zero or silently imputed.

Ignore label `255` is excluded. The data contract's semantic weight/provenance
is also applied: only weight `1.0` (original, real annotation) counts toward
evaluation; `0.25` pseudo labels and `0.0` unlabeled/ignore regions do not.
Thus automatic teacher labels can never count as Korean object ground truth.
The confusion matrix reports object recall for person, vehicle, and pole_sign
only over those real, non-ignore pixels.

`nonwalkable_to_walkable_false_positive_rate` is the number of real,
non-ignore pixels whose ground truth is one of `other`, `road`, `person`,
`vehicle`, or `pole_sign` but whose prediction is sidewalk or crosswalk,
divided by all real, non-ignore pixels in those five nonwalkable ground-truth
classes. `walkable_precision` counts any prediction in IDs 2/3 as correct only
when the ground truth is also in IDs 2/3; it does not require the exact
sidewalk-versus-crosswalk class to match.

The eight risk channels follow `DATA_CONTRACT.md`. Risk metrics are evaluated
on Surface records, where XML annotations provide valid hazard supervision.
Every risk precision/recall value is explicitly a **pixel** metric. Positives
count only where the corresponding `hazard_valid` channel is set; uncovered
regions do not become negative examples. These pixel metrics and selected
thresholds are diagnostic candidates only and never establish precision or
recall for frame-level warnings.

## Validation thresholds and final test

Validation reports evaluate pixel thresholds `0.50, 0.55, ..., 0.95`. Per
hazard, the tool selects a diagnostic candidate only if validation has at
least 50 positive images and 5 positive groups and the chosen threshold has
pixel precision at least 0.90 and pixel recall at least 0.70. Of eligible
thresholds it selects highest F1, then pixel precision, then threshold, with
deterministic tie-breaking. Otherwise that class has no candidate and an
explicit reason. The report uses `candidate_selected` and
`diagnostic_only: true`; neither authorizes automatic warnings.

Test evaluation requires a validation report and reuses its frozen selections;
it never searches a test threshold grid. A class without a validation
candidate remains disabled. A validation candidate is retained for pixel
diagnostics only if test pixel precision/recall still meet 0.90/0.70 and the
test set contains at least 50 positive images from 5 groups. Otherwise it is
disabled with an explicit reason. Test pixel metrics remain diagnostic, and
the report rejects any test record or group overlapping the validation report.

## Frame-warning enablement (fail-closed)

The evaluator does **not** accept warning-event labels or compute
warning-level metrics. Every evaluation and compare report therefore sets
`automatic_warning_enablement` to `false` with reason
`warning_level_evidence_not_supported`, regardless of pixel scores, semantic
acceptance, or supplied report fields. A missing warning flag in an older
report is also false. Semantic model acceptance and warning enablement are
separate decisions; a semantic pass is not permission to enable warnings.

Reports expose a machine-readable `warning_level_evidence_contract` (schema
version 1, `supported: false`) to define what a future implementation must
validate before it can enable warnings. Its evidence must be real held-out
application warning-event labels (`split: "test"`), not unit-test fixtures,
synthetic counts, or counts inferred from risk-pixel arrays. Per hazard it
must carry integer true-positive, false-positive, and false-negative event
counts, plus distinct positive image/group IDs whose unique counts match the
reported counts. Counts must be internally consistent (`TP + FN` equals the
number of positive image-level decisions), bounded by the evaluated frame
universe, and meet event precision >= 0.90 and recall >= 0.70 with at least
50 positive images from 5 groups.

Evidence must link to the same test `evaluation_fingerprint`, the producing
model's lowercase SHA-256, the exact validation `threshold_provenance`, and
the exact warning policy metadata in the report contract. The policy includes
near-ROI rows 230..287 inclusive, corridor occupancy >= 1%, connected
component area >= 32 pixels, two consecutive frames, 1500 ms expiry, 2500 ms
cooldown, session fencing, and suppression when orientation is unreliable.
Validation alone selects thresholds; test data cannot tune them. Until an
event-label input and its validation are implemented, the evaluator never
produces an enabled warning flag.

When `--model-sha256` is provided, it must be a lowercase 64-character SHA-256
digest of the model that produced the saved predictions. For test evaluation,
an explicitly supplied model hash must match the hash in the validation report
from which thresholds came; reports predating this field remain usable for
semantic and pixel diagnostics but cannot satisfy a warning consumer's model
hash check.
Acceptance comparisons require reports whose split is `test`. Validation is
for selection and cannot establish final acceptance.

## Streaming JSONL input

The CLI consumes saved predictions in UTF-8 JSON Lines (`.jsonl`) or gzip
(`.jsonl.gz`), one record at a time. Arrays are flattened row-major. The
semantic arrays contain one value per `[512,288]` pixel. Risk arrays contain
channel-last values for `[512,288,8]` (`pixel0`'s eight hazards, then
`pixel1`, etc.). No model framework is required to produce or unit-test this
input format.

Required fields:

| Field | Meaning |
|---|---|
| `record_id` | Unique stable image ID |
| `prediction_evidence` | `saved_model_predictions` for reports eligible for gates, or `unit_test_fixture` |
| `domain` | `surface` or `cityscapes` |
| `split` | `validation` or `test`; must match the CLI split |
| `group_id` | Stable source recording/city group for group counts |
| `semantic_truth` | Flat integer class IDs, including ignore 255 |
| `semantic_prediction` | Flat integer predicted class IDs 0..6 |
| `semantic_provenance` | Flat real/pseudo/ignore weights 1.0/0.25/0.0 |
| `risk_truth` | Surface only: flat binary `[pixel, hazard]` ground truth |
| `risk_prediction` | Surface only: flat finite probabilities in `[0,1]` |
| `risk_valid` | Surface only: flat binary validity mask matching risk arrays |

`risk_*` arrays must have eight entries per semantic pixel. Every Surface
record must include them, including all-zero validity where no annotation is
available. Cityscapes hazard arrays are not mixed into Surface risk metrics.
Optional `timing_ms` has `invoke` and `pipeline` values in milliseconds. Such
values count toward device gates only with `timing_source: "device"`,
`device_model`, and `runtime_backend`; synthetic or unidentified timings are
not accepted as device evidence. Device measurements must be finite and
positive. Candidate C's invoke comparison requires B and C to identify the
same device model/backend.

The CLI streams records and retains confusion/risk counters, unique record and
group IDs for coverage counts, and scalar timing samples for exact percentiles.
It does not retain a dataset's raster collection. Each report includes a
deterministic fingerprint over sorted record IDs, groups, semantic truth,
provenance, hazard truth, and hazard validity, excluding predictions. Baseline,
candidate, and B/C reports must have identical fingerprints. Adapters may
construct records from the converted semantic PNG/provenance NPZ and hazard
NPZ in `DATA_CONTRACT.md`; inference and that conversion adapter are
intentionally outside this utility.

## Reports and gates

Reports are deterministic JSON with schema version 1. They include per-domain
metrics, diagnostic pixel-threshold candidates, timing evidence, the
class/denominator contract, the warning evidence contract, and
`trained_model_quality_claimed: false`. The compare report separates
`semantic_model_acceptance` from `warning_enablement`; automatic warning
enablement remains false until real warning-level evidence is supported.
Mark historical
baseline evaluation with `--model-kind historical_baseline`; its report adds
the caveat that the historical model may have been exposed to the current
Surface test data, so it is not an independent historical holdout.

`compare` accepts a historical-baseline and candidate **test** report from
saved model predictions with the same fingerprint. Unit-test fixture reports
cannot pass. For A/B/C it fails
closed unless Surface mIoU improves by at least 0.03, Surface false-safe rate
is at most `0.8 * baseline`, and each Cityscapes person/vehicle/pole_sign IoU
loses no more than 0.01. Candidate C must additionally lose no more than 0.005
overall mIoU versus B (the equal macro-average of the two domain mIoUs), not
increase Surface false-safe rate versus B, and achieve same-device invoke p50
no greater than `0.8 * B`. Every A/B/C comparison also requires same-device
full-pipeline p95 no greater than `1.10 * baseline`. Missing, non-finite,
out-of-range, or non-device evidence fails the corresponding gate.

Example commands (after a prediction stream and reports actually exist):

```powershell
python -m tools.vision.training.evaluate evaluate --predictions build\vision-training\evaluation\candidate-val.jsonl.gz --split validation --output build\vision-training\evaluation\candidate-val.json
python -m tools.vision.training.evaluate evaluate --predictions build\vision-training\evaluation\candidate-test.jsonl.gz --split test --thresholds-from build\vision-training\evaluation\candidate-val.json --output build\vision-training\evaluation\candidate-test.json
python -m tools.vision.training.evaluate evaluate --predictions build\vision-training\evaluation\historical-baseline-test.jsonl.gz --split test --model-kind historical_baseline --thresholds-from build\vision-training\evaluation\candidate-val.json --output build\vision-training\evaluation\baseline-test.json
python -m tools.vision.training.evaluate compare --baseline build\vision-training\evaluation\baseline-test.json --candidate build\vision-training\evaluation\candidate-test.json --candidate-name A --output build\vision-training\evaluation\acceptance.json
```

Public APIs are `StreamingConfusionMatrix.update()/summary()`,
`StreamingRiskMetrics.update()/summary()`, `select_risk_thresholds()`,
`evaluate_records()`, and `acceptance_gates()`.
