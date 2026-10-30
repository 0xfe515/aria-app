# Native portrait model execution contract

Authorized by the user on 2026-10-30. Coordinator Run: `run_1de5424dff38`.

**RESUMED by explicit user instruction on 2026-10-30. Efficient parallel Gemini/Copilot sessions are authorized; retain exclusive ownership and supervised lifecycle.** The resumable plan and verified stop state are saved in [the temporary Korean plan](VISION_MODEL_IMPROVEMENT_PLAN_TEMP.md).

## Immutable requirements

- Train from the beginning with NHWC input `[B,512,288,3]`, upright RGB. Do not train landscape and convert shapes afterward. Semantic output `[B,512,288,7]` retains the current seven-class order. V2 additionally provides hazard sigmoid output `[B,512,288,8]`.
- Accuracy first; local GTX 1660 SUPER 6 GiB; automatic pseudo-label augmentation only, no claimed human review. No cloud uploads or paid compute. Preserve the existing app working tree and original bundled models. No commit/push.
- Data root: Windows Desktop `dataset`, containing `cityscapes` and `Surface_1`. All originals read-only. Upstream reviewed source is `build/model-review/upstream`, commit `bfb11e64153d5131c4aaf07c27f907f65fd59461`. No Keras checkpoint found; default is ImageNet initialization and fresh training, not TFLite training-state recovery.
- Implementation belongs to Orca-managed Gemini `gemini-3.8-flash-high` or configured Copilot. Coordinator writes specifications/docs, reviews and independently validates. Never substitute coordinator implementation or a non-Orca subagent. Respect live preamble lifecycle IDs/cadence; worker_done exactly once.

## Shared interfaces

Training source lives under `tools/vision/training/`, generated output under ignored `build/vision-training/`. This keeps the independently registered original model checkout unchanged and source reviewable in this authorized workspace.

Data preparation owns `data.py`, `prepare_data.py`, `pseudo_labels.py`, `tests/test_data.py`, `tests/test_pseudo_labels.py`, `DATA_CONTRACT.md`. Environment owns only `environment/` and `ENVIRONMENT.md`. Model/training owner later owns all other new training files. App owner later owns explicitly assigned Kotlin/resources/tests; no app ownership in the first wave. Coordinator owns this document and combined builds/device checks. Do not modify peer files.

Data manifest version 1: top-level `schema_version`, `seed`, `class_names`, `hazard_names`, `records`. Each record includes stable `id`, `source` (`cityscapes`/`surface`), `group_id`, `split` (`train`/`val`/`test`), absolute original `image_path`, annotation paths, original SHA256s, and generated relative `semantic_path`, `hazard_path`, `hazard_valid_path`. Paths resolve relative to manifest directory. Semantic PNG is uint8 0..6 with 255 ignore. Hazard and hazard_valid are separate uint8 arrays `[512,288,8]` stored in NPZ, values 0/1. Manifest paths may remain null in inventory-only mode and must be populated before training. A semantic source/weight array marks real (1.0), pseudo (0.25), ignore (0.0) supervision; document serialization in DATA_CONTRACT.md before model owner consumes it. Do not create uncontrolled dense cached images in RAM.

Hazard channel order: `bike_lane`, `damaged_surface`, `stairs`, `manhole`, `tree_zone`, `grating`, `repair_zone`, `speed_bump`. XML attributes damaged on sidewalk/braille/alley map to damaged_surface. Rasterize polygons by z_order. Known annotation regions supervise risk; uncovered/ambiguous regions do not create invented negatives. Retain XML-provided hazards even if surface semantics is other.

Group Surface by XML recording identifiers plus Surface directory; merge groups connected by duplicate image hashes. Stable seed 42 grouped split approximately 70/15/15, never individual-frame split. Cityscapes official train: city-group 85/15 internal train/val; official val is final test. No official test labels used for training. Output group-disjoint audit and label frequencies. Real historical baseline may have seen new Surface test; state this limit explicitly.

Images are upright, resized preserving aspect ratio into W288,H512 with black letterbox; label nearest-neighbor; padding ignore in all losses/metrics. Match existing app full-field transform. Training augmentation uses 50% full field and 50% upright crop; maintain paired transforms. Original Surface masks are RGB and live in `Surface_###/MASK/`; XML polygons augment them. Unknown colors cannot silently become other. Preserve Cityscapes void vs real other.

Pseudo objects: frozen existing native-portrait TFLite teacher and inverse-mapped horizontal flip prediction must agree, top probability >=0.90 and margin >=0.20; classes person/vehicle/pole_sign only. Only train/val-independent original annotations are ground truth. Pseudo labels carry provenance/weight and must never serve as Korean object accuracy ground truth. Handle uncovered Surface background as uncertain, not forced other. Teacher inference is single-owner, only after environment readiness; data worker initially implements/tests the producer but does not claim full inference without dependencies.

## Training sequence and runtime constraints

- WSL2 Ubuntu 22.04, Python 3.11, TensorFlow 2.19 compatible dependencies locked. Use local venv, do not change global PATH/credentials. Do not reboot, shut down Orca or change existing distributions. If Windows features require reboot, record exact evidence and stop that installation attempt while independent code work continues.
- FLOAT32, batch2 accumulation4 (OOM fallback batch1 accumulation8), clipnorm1; final batch1 export input 0..1 RGB; training preprocessing may use 0..255 but normalization must be applied exactly once, inside model export wrapper.
- A: same MobileNetV2/U-Net architecture, weighted masked CE + masked Dice, frequency inverse-sqrt class weights clipped .5..3. City warmup5 at 1e-3 then full30 at1e-4; BN statistics frozen. Mixed City/Surface50:50, decoder5 at3e-4 then unfreeze block13+ for20 at1e-5, patience5 validation. Ignore absent supervised Dice classes. Checkpoints and optimizer state resumable; seed42 first.
- B: A checkpoint, risk head Conv3x3/16 + Conv1x1/8 sigmoid on last decoder features; total semantic loss +.5 masked focal BCE gamma2. Head3 epochs then joint20; monitor semantic nonregression alongside risk metrics.
- C only after B accuracy passes: same encoder, upsample + projected skips + separable conv decoder channels128/64/32/16, supervised loss +.2 KL distillation T2 from B. Retain B if C fails accuracy/speed gates.

## Acceptance and future Android integration

Do not replace production assets on smoke-test success. Surface real-label mIoU >= baseline+3pp, nonwalkable->walkable false positive rate <=.8*baseline; City person/vehicle/pole IoU loss <=1pp. Final selected candidate repeated seeds42/43/44, test reserved for final candidate evaluation. Pseudo objects only diagnostic on Korea.

Risk per-class held-out precision>=.90 and recall>=.70, >=50 positive images and >=5 groups before automatic warning enablement. Tune threshold grid .50:.05:.95 on val; no suitable threshold means diagnostic-only. Multi-output model manifest version2 names tensors, order, dimensions, preprocessing, hashes and enabled thresholds. App must retain old one-output compatibility.

Risk predictions map to canonical512x288; active-corridor near rows230..287, occupancy>=1%, connected component>=32 pixels; two consecutive capture frames, cooldown2500ms, expiry1500ms, session fencing. Preserve semantic class meaning. Risk warning priority stairs, repair, damage, bump, manhole, grating, tree, bicycle; use existing vibration/Toast. No unsafe directions or distance claims. Unknown/unreliable orientation suppresses warnings.

Device owner coordinator: actual full-pipeline p95 <=1.10*baseline, C invoke p50 <=.80*B with <=.5pp mIoU loss and no false-safe increase. Revalidate CPU/GPU parity, tensors, delegate partitioning after every export. Android assembleDebug/testDebugUnitTest/lintDebug plus available connected tests required for app changes.

## First-wave task acceptance

ENV: diagnose/install authorized WSL2 prereqs without reboot, isolate Python GPU environment if possible, report GPU smoke 100steps/checkpoint/export or precise OS blocker. Exact shell commands and logs required. DATA: implement inventory/group split/full-field conversion/XML risk/ignore/pseudo interface and focused fixtures; run actual inventory against9558 Surface pairs and Cityscapes; run stdlib tests now and dependency tests when environment exists. All workers must report changed paths, validation, remaining blockers, effective launch evidence; keep no worker live without lifecycle accounting.
