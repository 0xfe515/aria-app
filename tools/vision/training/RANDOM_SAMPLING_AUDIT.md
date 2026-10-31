# Random Sampling & Crosswalk Exposure Audit (`ARIA-RANDOM-SAMPLING-AB-20261031`)

## 1. Purpose and Scope

This document records the pre-GPU static audit and sampling contract for the controlled A/B pilot rerun (`seed=42`, `sampling_seed=20261031`) in `tools/vision/training/strategy_experiment.py`. All figures below are taken directly from `build/vision-training/ab-random-sampling-20261031/pre_gpu_sampling_audit.json`.

Key findings established before the GPU rerun:
1. **The prior pilot (`seed=42`) already used a full-pool uniform pseudo-random shuffle, not sequential original filename order.** `PortraitDataset._ordered_records(epoch, balanced=False)` sorts the eligible `(source, split="train")` records by `record_id` and shuffles the entire source pool in place using `random.Random(self.seed + epoch).shuffle(ordered)`.
2. **Pre-augmentation crosswalk exposure in the prior `seed=42` run was non-zero.** Reconstructing the exact `seed=42` sample order from `build/vision-training/data/manifest.json` (`sha256 = e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`) reproduces the recorded GPU `input_digest.ids_sha256` hashes from `build/vision-training/ab-strategy-20261031/experiment/experiment_results.json` 100% (`old_run_recorded_ids_sha256_match_verified = true`) and shows:
   - Old Arm B Phase 1 (`358` Surface presentations across `85` Surface groups, `manifest_source_index` `10..6633`): `39` unique crosswalk-positive images across `30` groups in `35` microbatches (`695,818` pre-augmentation crosswalk pixels).
   - Old Common Phase 2 (`1,076` Surface presentations across `87` Surface groups, `manifest_source_index` `6..6638`): `111` unique crosswalk-positive images across `61` groups in `104` microbatches (`1,428,290` pre-augmentation crosswalk pixels).
3. **Why the rerun is needed:** The prior pilot logged only aggregate sample counts and phase `input_digest` hashes, not the ordered sample list or post-augmentation class pixel counts actually supervised after random scale-crop (`scale in [0.75, 1.0]` at `50%` probability, resized to `512x288` HxW) and random horizontal flip (`50%`). The rerun adds:
   - Explicit `--sampling-seed` (`20261031`) separated from model/framework `--seed` (`42`),
   - Controlled initialization (`--initial-weights-path`) loading the immutable prior untrained `shared_initial.weights.h5` (`file_sha256 = f47c4e3c3e25697595aec2781711ba9d2255f96fe7bac7e0d0999ecdfd716487`, `all_weights_tensor_sha256 = dbca1ef6451f0078a5fa135121711067ee80f39f97822985ee415e9e44be4fd2`),
   - Complete pre-augmentation and post-augmentation exposure ledgers (`ordered_samples.jsonl`, `microbatch_exposure.jsonl`, `phase_summary.json`, and `experiment_results.json`).

---

## 2. Sampler Contract and Augmentation RNG Boundary

### 2.1 Full-Pool Uniform Sampling Without Replacement
Without modifying `tools/vision/training/train_data.py`, `strategy_experiment.py` constructs the four `PortraitDataset` instances once (`city_train_ds`, `surf_train_ds` with `seed=resolved_sampling_seed`; `city_val_ds`, `surf_val_ds` with `seed=seed`) and reuses them across both arms:
- **Eligible train pools (`split == "train"` only):**
  - `cityscapes`: `2,550` records across `15` groups (`0` crosswalk-positive records, `0` crosswalk pixels).
  - `surface`: `6,647` records across `87` groups (`579` crosswalk-positive records across `86` groups, `7,350,939` crosswalk pixels).
- **Non-train exclusion:** Validation (`425` Cityscapes + `1,457` Surface = `1,882` records) and test (`500` Cityscapes + `1,454` Surface = `1,954` records) splits are excluded by `split == "train"` filtering and group/hash disjointness.
- **Pseudo-labels:** `pseudo_manifest_path=None`, so all non-ignored supervised pixels are real (`semantic_source == 1` and `semantic_weight == 1.0`, while letterbox padding and ignored pixels have `semantic_source == 0`, `semantic == 255`, and `semantic_weight == 0.0`).
- **Within-epoch sampling:** Each source pool is sorted by `record_id` and shuffled in place via `random.Random(self.seed + epoch).shuffle(ordered)`. Phase 1 starts at `start_epoch=0` (`effective_rng_seed = sampling_seed + 0`) and Phase 2 starts at `start_epoch=1` (`effective_rng_seed = sampling_seed + 1`). Within a single epoch (`<= 2,550` Cityscapes or `<= 6,647` Surface presentations), sampling is strictly without replacement (`duplicate_presentations_within_epoch == 0`).
- **Epoch exhaustion:** When a stream exhausts its current epoch, `iter_source_microbatches` increments `epoch` by `+1` and calls `dataset.iter_batches(batch_size, epoch=epoch, augment=True, balanced=False)`, which reshuffles the full source pool deterministically with `random.Random(self.seed + epoch)`.

### 2.2 Augmentation RNG Coupling (`train_data.py` Unchanged)
Because `tools/vision/training/train_data.py` is unchanged in this task:
1. `PortraitDataset._ordered_records(epoch, balanced=False)` creates a `random.Random(self.seed + epoch)` instance to shuffle the sorted record list.
2. `PortraitDataset.iter_samples(epoch=epoch, augment=True, balanced=False)` calls `self._ordered_records(epoch, balanced)` and then creates a **separate** `random.Random(self.seed + epoch)` instance with the same seed (`self.seed + epoch`), passing that second RNG instance into `_augment_sample(...)` for each record loaded by `self._load_sample(record)`.
3. `_augment_sample(...)` applies random scale-crop (`50%` chance, `scale = rng.uniform(0.75, 1.0)`, cropped from `(512, 288)` HxW and resized back to `(288, 512)` WxH / `(512, 288)` HxW), random horizontal flip (`50%` chance), brightness/contrast jitter (`0.85..1.15`), and Gaussian blur (`30%` chance, `radius=0.5`).

Consequently:
- Changing `sampling_seed` from `42` to `20261031` changes **both** the full-pool sample presentation order and the per-sample augmentation RNG stream, while keeping model/framework initialization identical (`seed=42` + `--initial-weights-path`).
- Within the rerun (`sampling_seed=20261031`), **Common Phase 2 remains 100% matched between Arm A and Arm B**: both arms reuse `city_train_ds` and `surf_train_ds` starting at fresh `start_epoch=1` (`effective_rng_seed = 20261032`), producing the exact same sample sequence (`ids_sha256`) and exact same augmented `PhaseInputDigestTracker` batch digests (`combined_sha256`, `ids_sha256`, `image_sha256`, `semantic_sha256`, `semantic_weight_sha256`, and post-augmentation pixel counts).
- Comparisons between the old (`seed=42`) and new (`sampling_seed=20261031`) runs represent **single sampling-seed + augmentation-RNG sensitivity**, not a causal isolation of presentation order alone.

---

## 3. Pre-Augmentation Reconstruction: Old (`seed=42`) vs New (`sampling_seed=20261031`)

Reconstructed directly from `build/vision-training/data/manifest.json` (`sha256 = e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`) under the locked schedule (`batch_size=2`, `accumulate_steps=4`, `64` Phase 1 updates = `512` presentations, `192` Phase 2 updates = `1,536` presentations, mixed microbatch cadence `[S, S, C, S, S, C, S, S, C, S]`):

| Phase / Metric | Old (`sampling_seed=42`) | New (`sampling_seed=20261031`) |
| :--- | :--- | :--- |
| **Arm A Phase 1 (`city_only`, 64 updates, `start_epoch=0`)** | | |
| Effective RNG seed | `42` | `20261031` |
| Presentations (`cityscapes` / `surface`) | `512` / `0` | `512` / `0` |
| Unique groups (`cityscapes` / `surface`) | `15` / `0` | `15` / `0` |
| Cityscapes `manifest_source_index` min..max (median) | `0..2544` (`1255.0`) | `10..2547` (`1154.0`) |
| Cityscapes `sorted_pool_rank` min..max (median) | `0..2543` (`1314.5`) | `3..2546` (`1163.0`) |
| Reconstructed `ids_sha256` | `b4b1fb878bac173bf835668044fa9effd593e71f3cf2365a08e22f7c8ee2837d` | `b283572ec86176b74fd80c5e69bf22c68a1107783846e1de7225ead584d859be` |
| Matches prior GPU `input_digest.ids_sha256` | Yes (`True`) | N/A (new seed) |
| **Arm B Phase 1 (`mixed_70_30`, 64 updates, `start_epoch=0`)** | | |
| Effective RNG seed | `42` | `20261031` |
| Presentations (`surface` / `cityscapes`) | `358` / `154` | `358` / `154` |
| Unique groups (`surface` / `cityscapes`) | `85` / `15` | `86` / `15` |
| Surface `manifest_source_index` min..max (median) | `10..6633` (`3301.0`) | `101..6632` (`3445.0`) |
| Surface `sorted_pool_rank` min..max (median) | `1..6637` (`3187.5`) | `11..6617` (`3460.0`) |
| Surface manifest-order quartile counts | `[87, 93, 88, 90]` | `[83, 89, 101, 85]` |
| Surface sorted-pool quartile counts | `[103, 87, 83, 85]` | `[78, 96, 106, 78]` |
| Reconstructed `ids_sha256` | `98ae8349ce0fceaed8626712279600a6f0162f16685af2de48749cf89333be38` | `b46498987afc91ef0ea3a53bf8a4cc7337580f8ff7abc2a169e00cbd4bad0d04` |
| Matches prior GPU `input_digest.ids_sha256` | Yes (`True`) | N/A (new seed) |
| Pre-aug crosswalk-positive presentations / unique images | `39` / `39` | `31` / `31` |
| Pre-aug crosswalk-positive groups / microbatches | `30` / `35` | `29` / `29` |
| Pre-aug crosswalk pixels | `695,818` | `349,296` |
| **Common Phase 2 (`mixed_70_30`, 192 updates, `start_epoch=1`)** | | |
| Effective RNG seed | `43` | `20261032` |
| Presentations (`surface` / `cityscapes`) | `1,076` / `460` | `1,076` / `460` |
| Unique groups (`surface` / `cityscapes`) | `87` / `15` | `87` / `15` |
| Surface `manifest_source_index` min..max (median) | `6..6638` (`3382.0`) | `5..6640` (`3239.5`) |
| Surface `sorted_pool_rank` min..max (median) | `12..6646` (`3350.5`) | `23..6642` (`3215.5`) |
| Surface manifest-order quartile counts | `[251, 275, 277, 273]` | `[294, 256, 266, 260]` |
| Surface sorted-pool quartile counts | `[267, 266, 263, 280]` | `[279, 272, 266, 259]` |
| Reconstructed `ids_sha256` | `1c8f1200c496a65bba06d6a84399fb217f1abf4267745fb0caf71cc68564edcb` | `6a578d906b6fa42b772491479d01472dd6a59d38377b10c12d45626dcb35f713` |
| Matches prior GPU `input_digest.ids_sha256` | Yes (`True`) | N/A (new seed) |
| Pre-aug crosswalk-positive presentations / unique images | `111` / `111` | `94` / `94` |
| Pre-aug crosswalk-positive groups / microbatches | `61` / `104` | `56` / `86` |
| Pre-aug crosswalk pixels | `1,428,290` | `1,201,430` |
| **Combined Arm Totals (Phase 1 + Phase 2 Pre-Augmentation)** | | |
| Arm A Surface presentations / unique images | `1,076` / `1,076` | `1,076` / `1,076` |
| Arm A crosswalk presentations / unique images (groups) | `111` / `111` (`61` groups) | `94` / `94` (`56` groups) |
| Arm A crosswalk total pixels / unique-image pixels | `1,428,290` / `1,428,290` | `1,201,430` / `1,201,430` |
| Arm B Surface presentations / unique images | `1,434` / `1,372` | `1,434` / `1,383` |
| Arm B crosswalk presentations / unique images (groups) | `150` / `145` (`66` groups) | `125` / `121` (`63` groups) |
| Arm B crosswalk total pixels / unique-image pixels | `2,124,108` / `2,073,062` | `1,550,726` / `1,464,315` |

### Cross-Seed Overlap (`42` vs `20261031` from `seed_comparison`)
- **Phase 1 Surface unique ID overlap:** `24` shared Surface images (out of `358` each).
- **Phase 2 Surface unique ID overlap:** `187` shared Surface images (out of `1,076` each).
- **Crosswalk-positive union across old & new Phase 1/2:** `237` unique crosswalk-positive images.
- **Crosswalk-positive overlap between old & new Phase 1/2:** `29` shared crosswalk-positive images.

---

## 4. Converted `.semantic.png` Mask Integrity Verification

Every unique crosswalk-positive record selected by old (`seed=42`) Phase 1/Phase 2 and new (`sampling_seed=20261031`) Phase 1/Phase 2 was decoded directly from its converted `.semantic.png` file on disk and compared against the manifest `class_pixel_counts["crosswalk"]` (`class_id == 3`):

- **Unique crosswalk-positive converted masks verified:** `237` (`verified_records_sha256 = 28f68c27e54bf3ef20a16c91039dad153f412a71b5bbd0e641ecaf3fd7d96855`)
- **Total verified pixels across those 237 masks:** `other = 207,059`, `road = 1,405,893`, `sidewalk = 4,632,463`, `crosswalk = 3,213,787`, `person = 0`, `vehicle = 0`, `pole_sign = 0`
- **Manifest crosswalk pixels across those 237 records:** `3,213,787`
- **All verified masks match manifest 100% (`all_verified_masks_match_manifest`):** `True`

---

## 5. CPU Unit Test Verification

Validation command:
```powershell
& "build\vision-training\environment\venv_win_py311\Scripts\python.exe" -m unittest discover -s tools/vision/training/tests -p test_strategy_experiment.py -v
```

All 12 unit tests in `tools/vision/training/tests/test_strategy_experiment.py` pass (`54.2s - 56.3s`, exit code `0`), covering:
1. Full-pool uniform sampling without replacement across the entire eligible train pool and deterministic reshuffling on epoch exhaustion (`sampling_seed + epoch`).
2. `sampling_seed` vs `seed` separation (`sampling_seed` omission defaults to `seed`; changing `sampling_seed` changes sample order while `--initial-weights-path` locks identical initial tensors).
3. Pre-augmentation and post-augmentation class pixel counting (strictly distinguishing `semantic_source == 1` & `semantic_weight > 0` from ignored `255`/`weight == 0` and pseudo `semantic_source == 2`), `ordered_samples.jsonl` / `microbatch_exposure.jsonl` generation, non-train split exclusion, and fail-fast `RuntimeError` when a mixed phase receives zero real crosswalk supervision.
4. Converted `.semantic.png` mask verification and old (`42`) vs new (`20261031`) reconstruction digest matching.
5. All prior A/B pilot invariants (fresh Phase 2 Adam, BN freeze + 20 trainable decoder tensors, `null` vs `0.0` IoU semantics, and explicit mIoU denominators).
