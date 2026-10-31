# Model A Controlled Two-Arm Strategy Experiment Runner

**Experiment ID**: `ARIA-STRATEGY-AB-PILOT-20261031` / `ARIA-RANDOM-SAMPLING-AB-20261031`

**Preregistered Protocols**:
- `docs/VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md`
- `docs/VISION_RANDOM_SAMPLING_EXPERIMENT_2026-10-31.md`

**Runner Module**: `tools/vision/training/strategy_experiment.py`

**Unit Test Suite**: `tools/vision/training/tests/test_strategy_experiment.py`

**Sampling & Exposure Audit**: `tools/vision/training/RANDOM_SAMPLING_AUDIT.md`

## Overview

`strategy_experiment.py` executes the preregistered two-arm training strategy comparison on native portrait Model A (`[B, 512, 288, 3]` -> `[B, 512, 288, 7]`, `FLOAT32`) under an identical 256-update optimization budget:

- **Arm A (`city_pretrain_then_mixed`)**:
  - Phase 1: Cityscapes train only (`64` optimizer updates, `256` microbatches, `512` presentations, `LR = 1e-3`, fresh Adam).
  - Phase 2: Mixed 70% Surface : 30% Cityscapes (`192` optimizer updates, `768` microbatches, `1,536` presentations, `LR = 3e-4`, fresh Adam).
- **Arm B (`mixed_from_start`)**:
  - Phase 1: Mixed 70% Surface : 30% Cityscapes (`64` optimizer updates, `256` microbatches, `512` presentations, `LR = 1e-3`, fresh Adam).
  - Phase 2: Mixed 70% Surface : 30% Cityscapes (`192` optimizer updates, `768` microbatches, `1,536` presentations, `LR = 3e-4`, fresh Adam).

## Enforced Controls & Invariants

1. **Shared Initialization & Frozen Backbone**:
   - Model seed `42` (`TF_DETERMINISTIC_OPS=1`, `TF_CUDNN_DETERMINISTIC=1`).
   - MobileNetV2 ImageNet encoder and all BatchNormalization layers are frozen (`encoder_policy="freeze_all"`, `freeze_bn=True`); only decoder/head parameters (`20` trainable variables) are trained.
   - Supports `--initial-weights-path` to load and verify the immutable untrained `shared_initial.weights.h5` (`file SHA-256: f47c4e3c3e25697595aec2781711ba9d2255f96fe7bac7e0d0999ecdfd716487`, `tensor SHA-256: dbca1ef6451f0078a5fa135121711067ee80f39f97822985ee415e9e44be4fd2`), ensuring exact initial numerical tensor parity across sampling seeds.
   - Both arms verify identical in-memory numerical tensor SHA-256 (`all_weights_tensor_sha256`, `trainable_weights_tensor_sha256`, `non_trainable_weights_tensor_sha256`) and identical `trainable_variable_paths` before Phase 1 starts, and verify `non_trainable_weights_tensor_sha256` is unchanged after Phase 1 and Phase 2.
2. **Decoupled Full-Pool Random Sampling (`--sampling-seed`) & Exposure Audit**:
   - `--sampling-seed` controls `PortraitDataset` full-pool uniform shuffle (`random.Random(sampling_seed + epoch).shuffle(sorted_records)`) independently of model `--seed` (defaulting to `--seed` when omitted).
   - Samples without replacement within each epoch across the full eligible per-source train pools (`2,550` Cityscapes `train`, `6,647` Surface `train`), reshuffling with `sampling_seed + epoch` on epoch exhaustion.
   - Note: `PortraitDataset.iter_samples` uses `random.Random(dataset.seed + epoch)` for both record ordering and sample augmentation; changing `--sampling-seed` changes both sample ordering and augmentation RNG relative to `--seed 42` while keeping Phase 2 matched across Arm A and Arm B.
   - Persists per-phase `ordered_samples.jsonl` and `microbatch_exposure.jsonl`, recording ordered sample IDs, group IDs, pool ranks, pre-augmentation manifest pixel/image counts, converted `.semantic.png` mask verification for selected crosswalk-positive samples, and post-augmentation real supervised pixel/image counts (`semantic_source == 1` and `semantic_weight > 0`).
   - Fails clearly before completion if any mixed phase consumes zero real crosswalk supervision.
3. **Fixed Shared Class Weights**:
   - Computed once from the sum of all real (`semantic_source == 1`) training pixel counts across Cityscapes `train` (`2,550` records) and Surface `train` (`6,647` records) in the verified manifest (`e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`) using `compute_inverse_sqrt_class_weights`.
   - Pseudo-labels are disabled (`pseudo_manifest_path=None`) in both arms and both phases.
4. **Phase 2 Stream & Augmentation Reset**:
   - `PortraitDataset` objects (`city_train`, `surface_train`, `city_val`, `surface_val`) are constructed once and reused across both arms.
   - At the start of Phase 2, both arms reset their source batch streams and augmentation RNGs identically (`start_epoch = 1`, `effective_sampling_rng_seed = sampling_seed + 1`, `phase_seed = seed + 1000`) and verify identical actual Phase 2 input digests (`combined_sha256`, `ids_sha256`, `image_sha256`, `semantic_sha256`, `semantic_weight_sha256`) and identical Phase 2 exposure digests (`ordered_sample_ids_sha256`, `exposure_digest_sha256`).
5. **Single-Forward Validation Evaluation**:
   - Evaluates all `1,882` validation records (`1,457` Surface `val` + `425` Cityscapes `val`) without augmentation using `StreamingConfusionMatrix`.
   - Preserves `null` (`None`) for undefined classes and explicitly reports class-count denominators (`gt_present_mean_iou`: Surface 4 classes, Cityscapes 6 classes).
   - Held-out `test` splits (`1,454` Surface `test` + `500` Cityscapes `test`) are strictly excluded.

## Validation & Execution Commands

### CPU Unit Tests

```powershell
& "build\vision-training\environment\venv_win_py311\Scripts\python.exe" -m unittest discover -s tools/vision/training/tests -p test_strategy_experiment.py -v
```

### WSL2 GPU Execution (Rerun with `--sampling-seed 20261031` via `run_wsl_training.py`)

```bash
/home/user/.venvs/aria_wsl/bin/python \
  /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py \
  --deterministic --allow-growth \
  --module tools.vision.training.strategy_experiment -- \
  --manifest /home/user/.local/share/aria-training/full-training-20261031/data/manifest.json \
  --output-dir /home/user/.local/share/aria-training/ab-random-sampling-20261031/experiment \
  --device gpu \
  --weights imagenet \
  --seed 42 \
  --sampling-seed 20261031 \
  --initial-weights-path /home/user/.local/share/aria-training/ab-strategy-20261031/experiment/shared_init/shared_initial.weights.h5
```
