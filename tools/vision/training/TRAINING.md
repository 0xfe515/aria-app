# Native Portrait Training

The staged trainer consumes converted version-1 manifests through
`PortraitDataset` (`train_data.py`). It does not use a duplicate record loader:
the loader streams `image`, `semantic`, `semantic_weight`, `semantic_source`,
`hazard`, and `hazard_valid` arrays, retains partial final batches, and rejects
incomplete data by default. `--allow-partial-manifest` is diagnostic-only; its
summary marks `training_eligible: false` and makes no completeness or quality
claim. Training requires non-empty train and validation selections.

## Stage initialization

Each stage starts a fresh optimizer and epoch numbering at 1. `--init-checkpoint`
initializes weights from the required preceding stage; `--resume` restores the
same stage and is never a substitute for stage initialization.

| Stage | Required initialization |
|---|---|
| `city_warmup` | Fresh `--weights imagenet` (default) or `--weights none`; no init checkpoint |
| `city_full` | `city_warmup` Model A |
| `mixed_decoder` | `city_full` Model A |
| `mixed_block13` | `mixed_decoder` Model A |
| `stage_b_head` | `mixed_block13` Model A, then attach/train only the risk head |
| `stage_b_joint` | `stage_b_head` Model B |
| `stage_c` | Fresh student plus an accepted `stage_b_joint` Model B teacher and evaluator evidence |

Use the full `latest` or `best` checkpoint directory, which contains model
weights and `metadata.json`. The trainer checks the expected preceding stage,
architecture, and exact dataset-manifest hash, and that it records at least one
completed epoch, before loading initialization weights. A `best` checkpoint that never
improved on its stage's untrained epoch-0 baseline therefore cannot initialize a later
stage; the run fails clearly instead. Examples from the repository root follow.
Use `build/vision-training/environment/venv_win_py311/Scripts/python.exe` for
the `python` command below, or an equivalent prepared GPU environment. Mixed
stages use the completed `pseudo_full` manifest, not the older diagnostic subset.

```powershell
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --stage city_warmup --weights imagenet --output-dir build\vision-training\trainer-correction\runner --device cpu
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --stage city_full --init-checkpoint build\vision-training\trainer-correction\runner\city_warmup\checkpoints\best --output-dir build\vision-training\trainer-correction\runner --device cpu
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --pseudo-manifest build\vision-training\data\pseudo_full\pseudo_manifest.json --stage mixed_decoder --init-checkpoint build\vision-training\trainer-correction\runner\city_full\checkpoints\best --output-dir build\vision-training\trainer-correction\runner --device cpu
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --pseudo-manifest build\vision-training\data\pseudo_full\pseudo_manifest.json --stage mixed_block13 --init-checkpoint build\vision-training\trainer-correction\runner\mixed_decoder\checkpoints\best --output-dir build\vision-training\trainer-correction\runner --device cpu
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --pseudo-manifest build\vision-training\data\pseudo_full\pseudo_manifest.json --stage stage_b_head --init-checkpoint build\vision-training\trainer-correction\runner\mixed_block13\checkpoints\best --output-dir build\vision-training\trainer-correction\runner --device cpu
python -m tools.vision.training.train --manifest build\vision-training\data\manifest.json --pseudo-manifest build\vision-training\data\pseudo_full\pseudo_manifest.json --stage stage_b_joint --init-checkpoint build\vision-training\trainer-correction\runner\stage_b_head\checkpoints\best --output-dir build\vision-training\trainer-correction\runner --device cpu
```

Stage C refuses to start unless both `--teacher-checkpoint` and
`--teacher-evidence` are supplied. Evidence must bind the accepted B-joint
checkpoint SHA-256 and manifest SHA-256, the exported Model B hash, a hashed
version-2 export manifest, and a hashed B-against-baseline report from
`tools.vision.training.evaluate compare`. The export manifest must identify
Model B, have verified parity, and carry checkpoint provenance exactly matching
the selected checkpoint metadata. The comparison must have
`semantic_model_acceptance.passed: true`, all gates passing, and a matching
exported-model hash. An `accepted: true` flag or checkpoint filename alone is
insufficient. No tool currently emits this bound evidence wrapper; until a real
held-out evaluator/export chain supplies it, C fails closed.
The same checkpoint-bound teacher and evidence must be supplied again to resume
Stage C; the evidence is revalidated against the current manifest and teacher
checkpoint before the student state is restored.

## Data, validation, and checkpoint behavior

- Mixed stages use deterministic, balanced `PortraitDataset.iter_batches`;
  per-epoch ordering and augmentation are seeded. Validation uses the same
  stage source selection without augmentation.
- Semantic class weights are computed from real (`semantic_source == 1`)
  training pixels only. Missing/non-finite class frequencies are fatal;
  inverse-square-root weights are clipped to `[0.5, 3.0]`. Pseudo labels retain
  their loader-provided weight `0.25` and never count as real class frequency.
- Every checkpoint records the stage, architecture, seed, manifest and optional
  pseudo-manifest hashes, class weights, optimizer/configuration, batch and
  accumulation metadata, patience state, and validation statistics. Optimizer
  variables use Keras 3 `optimizer.variables`; optimizer slots are built before
  restore without applying a dummy update.
- A fresh (non-`--resume`) run refuses an output directory whose stage checkpoint
  directory is non-empty, so existing state is never silently overwritten.
- `--resume` uses exactly the supplied checkpoint path and requires the same stage, architecture, seed, manifest, optimizer,
  class weights, learning rate, epoch schedule, batch/accumulation settings,
  clip norm, pseudo-manifest, and patience configuration. Missing or
  incompatible model, optimizer, accumulator, or RNG state is fatal. Resume is
  supported only at flushed epoch boundaries, not in the middle of a batch; the
  deterministic per-epoch loader makes the next epoch reproducible, and tests
  verify a resumed run matches an uninterrupted run. A checkpoint that already hit
  early stopping is refused. The summary's `history` covers only epochs run by the
  current invocation.
- Stage B records semantic and hazard validation losses separately. A B
  checkpoint can become best only when semantic validation loss does not exceed
  the initial A-derived semantic baseline; a hazard-loss improvement alone
  cannot select a semantically degraded checkpoint. Validation metrics include
  semantic per-class/domain results and risk metrics by source where available.
  Final acceptance gates belong to the held-out evaluator, not trainer
  validation.
- The trainer does not automatically retry on OOM because optimizer and
  in-flight state rollback cannot be guaranteed. It fails and preserves the
  last valid epoch-boundary checkpoint. Resume it with its saved configuration,
  or restart from the stage's unchanged predecessor checkpoint using
  `--batch-size 1 --accumulate-steps 8` and a new empty `--output-dir` (resume
  itself requires identical batch settings).
- `--device cpu` selects CPU through TensorFlow without changing environment
  variables. `--device gpu` errors if TensorFlow has no GPU. Both require
  device selection before TensorFlow initializes its runtime.
- The summary at `<output-dir>/<stage>/training_summary.json` records software
  versions, source record counts, class frequencies/weights, seeds and hashes.
  It does not claim model acceptance or held-out test quality.

## Bounded checks

`--allow-partial-manifest` additionally requires `--verification-samples N` (1..256) and
exactly one epoch; it bounds each train/validation pass, reports
`training_eligible: false`, and is for diagnostics only.

The synthetic smoke mode verifies mechanics only, not training quality:

```powershell
python -m tools.vision.training.train --smoke --smoke-steps 2 --device cpu
```

Run trainer tests with the isolated Python 3.11 / TensorFlow environment:

```powershell
& "build\vision-training\environment\venv_win_py311\Scripts\python.exe" -m unittest discover -s tools/vision/training/tests -p test_train.py -v
& "build\vision-training\environment\venv_win_py311\Scripts\python.exe" -m unittest discover -s tools/vision/training/tests -p test_train_stage.py -v
```

## Known limitations

- No tool yet emits the checkpoint-bound Stage C acceptance wrapper, so Stage C cannot
  start on real data until the held-out evaluator/export chain produces it. Stage C
  training and resume are exercised only with a stubbed validator and tiny models.
- Trainer validation is for stage selection only; per-source/class metrics are reported
  but accuracy gates and model acceptance belong to the held-out evaluator.
- Real-model (MobileNet) multi-epoch training has not been run here; the staged tests use
  tiny Keras models with converter-shaped PNG/NPZ fixtures and make no quality claim.
- OOM is not auto-retried; mid-epoch resume is unsupported.
