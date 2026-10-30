# Portrait Full-Training Data Preflight & Input Audit

This document defines the preflight validation contract, static audit procedure, and verified dataset accounting for the full 13,033-record Aria portrait vision model training pipeline.

The preflight audit is a **Windows-native, read-only static verification tool** (`tools/vision/training/environment/audit_training_inputs.py`) that executes without GPU or TensorFlow runtime dependencies. It checks manifest and provenance consistency, split disjointness, path safety, nonempty artifact presence, and current disk headroom before GPU full training is launched. It does not decode every artifact, recompute every image content digest, or establish model quality.

---

## 1. Verified Evidence & Artifact Hashes

The input datasets, converted artifacts, and pseudo-labels are immutable. The preflight audit hashes the three JSON artifacts before and after each run. The teacher digest below is checked against their recorded provenance; this audit does not rehash the teacher binary:

| Artifact | Path | Records | Files | Verified SHA-256 Digest | Status |
|---|---|---:|---:|---|---|
| **Base Manifest** | `build/vision-training/data/manifest.json` | 13,033 | - | `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9` | Preserved |
| **Pseudo Manifest** | `build/vision-training/data/pseudo_full/pseudo_manifest.json` | 708 | 1,416 | `221fb979497dc32a97204dcfdeaba8517aabd1e0baee0321f1842a4b1954c8ae` | Preserved |
| **Completion Report** | `build/vision-training/data/pseudo_full/completion_report.json` | 6,647 | - | `a9c8046fed5da8aca6bcd650a915a9b9a14db04a9e383f85002190959e002b3f` | Complete |
| **Teacher Model** | `app/src/main/assets/models/walkable_seg_aria5_portrait_unfused.tflite` | - | - | `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1` | Frozen |

**Immutability Guarantee**: Hashes computed before the audit match the hashes computed after the audit byte-for-byte. The audit makes zero file modifications or rewrites to original or converted data.

---

## 2. Taxonomy and Domain Contracts

### Canonical Ordering
- **Schema Version**: `1`
- **Seed**: `42`
- **Class Names (7 categories)**:
  `["other", "road", "sidewalk", "crosswalk", "person", "vehicle", "pole_sign"]`
- **Hazard Names (8 categories)**:
  `["bike_lane", "damaged_surface", "stairs", "manhole", "tree_zone", "grating", "repair_zone", "speed_bump"]`

### Source and Split Allocation Contract
1. **Surface Dataset (`source: surface`)**:
   - `source_split`: `"surface"`
   - Total records: **9,558**
   - Split distribution:
     - `train`: **6,647** records (69.54%)
     - `val`: **1,457** records (15.24%)
     - `test`: **1,454** records (15.21%)
2. **Cityscapes Dataset (`source: cityscapes`)**:
   - `source_split`:
     - `"city-train"`: **2,975** records partitioned into `train` (**2,550**) and `val` (**425**) (85.7% / 14.3%).
     - `"city-official-val"`: **500** records strictly reserved for `test` (**500**).
   - Total records: **3,475**
   - Split distribution:
     - `train`: **2,550** records
     - `val`: **425** records
     - `test`: **500** records
3. **Combined Dataset Totals**:
   - Total: **13,033** records
   - `train`: **9,197** records (70.57%)
   - `val`: **1,882** records (14.44%)
   - `test`: **1,954** records (14.99%)

---

## 3. Split Disjointness & Zero-Leakage Audit

To prevent data contamination across model training and evaluation:
- **Group Disjointness**: Evaluated across all **151** unique `group_id` values.
  - Every group belongs exclusively to one split (`train`, `val`, or `test`).
  - Cross-split group leakage count: **0**.
- **Image Content Disjointness**: Evaluated across all **13,033** unique `image_sha256` content digests.
  - Every identical image belongs exclusively to one split.
  - Cross-split content hash leakage count: **0**.

---

## 4. Converted and Raw Storage Accounting

Every record specifies 6 relative converted tensor and annotation paths:
- `image_tensor_path` (`converted/<split>/<source>/<id>.png`)
- `semantic_path` (`converted/<split>/<source>/<id>.semantic.png`)
- `hazard_path` (`converted/<split>/<source>/<id>.hazards.npz`)
- `hazard_valid_path` (shares NPZ with `hazard_path`)
- `semantic_source_path` (`converted/<split>/<source>/<id>.provenance.npz`)
- `semantic_weight_path` (shares NPZ with `semantic_source_path`)

### Physical File Existence and Sizing Summary
All referenced files were individually checked via native OS stat calls on Windows:

| File Category | Unique Paths Referenced | Physical Files Present | Missing Files | 0-Byte Corrupted Files | On-Disk Footprint |
|---|---:|---:|---:|---:|---:|
| **Converted Image Tensors** | 13,033 | 13,033 | 0 | 0 | ~620 MiB |
| **Converted Semantic Masks** | 13,033 | 13,033 | 0 | 0 | ~85 MiB |
| **Hazard Arrays (`.hazards.npz`)** | 13,033 | 13,033 | 0 | 0 | ~240 MiB |
| **Provenance Arrays (`.provenance.npz`)** | 13,033 | 13,033 | 0 | 0 | ~110 MiB |
| **Total Converted Artifacts** | **52,132** | **52,132** | **0** | **0** | **1.03 GiB** |
| **Original Raw Images (Surface + Cityscapes)** | **13,033** | **13,033** | **0** | **0** | **17.90 GiB** |
| **Pseudo Label PNGs** | 708 | 708 | 0 | 0 | ~1.4 MiB |
| **Pseudo Weight NPZs** | 708 | 708 | 0 | 0 | ~2.3 MiB |
| **Total Pseudo Artifacts** | **1,416** | **1,416** | **0** | **0** | **3.70 MiB** |

### Path Safety
All 52,132 converted paths and 1,416 pseudo paths are strictly relative paths without leading drive specifiers or directory traversal (`..`) that could escape the dataset root.

---

## 5. Frozen Teacher Pseudo-Label Accounting

The pseudo-label dataset (`build/vision-training/data/pseudo_full/`) augments sparse object categories (classes 4, 5, 6) on Surface training data:

- **Target Eligibility**: Surface `train` records only (**6,647** candidates).
- **Processing Status**: **6,647 / 6,647** processed (100% complete, 0 remaining).
- **Records with Pseudo-Labels**: **708** records containing $\ge 1$ accepted object pixel.
- **Records Filtered / Zero Accepted**: **5,939** records where confidence or margin thresholds were not met.
- **Accepted Object Pixel Breakdown**:
  - `person` (Class 4): **11,446** pixels (weight 0.25, source 2)
  - `vehicle` (Class 5): **373,438** pixels (weight 0.25, source 2)
  - `pole_sign` (Class 6): **5,704** pixels (weight 0.25, source 2)
  - `other`, `road`, `sidewalk`, `crosswalk`: **0** pixels (strictly forbidden as pseudo-labels)
  - **Total Accepted Object Pixels**: **390,588**
- **Strict Held-Out Non-Inference**: Zero pseudo records exist for validation or test splits, or for the Cityscapes dataset.

---

## 6. Host Disk Space & WSL Capacity Analysis

- **Host C: Drive Capacity**:
  - Total: **198.88 GiB**
  - Used: **167.73 GiB**
  - Free: **31.16 GiB**
- **Full-Training Headroom Assessment**:
  - Converted dataset is already materialized on disk (1.03 GiB).
  - Pseudo artifacts are already materialized on disk (3.70 MiB).
  - Model weights per checkpoint: ~15-30 MB.
  - AdamW FP32 optimizer state per checkpoint: ~60-120 MB.
  - Total per saved epoch: ~75-150 MB.
  - TensorBoard event logs: ~100-300 MB.
  - Required safety margin: **10-15 GiB**.
  - Current free space (**31.16 GiB**) provides adequate headroom (~16-20 GiB buffer).

### Operational Caveats for WSL2 Training
1. **Dynamic VHDX Allocation**:
   WSL2 guest allocations dynamically expand the Windows host virtual disk (`ext4.vhdx`). Space is not automatically reclaimed on the Windows host when files are deleted inside WSL.
   - *Requirement*: The training script must configure `save_best_only=True` or retain at most 3 checkpoints to prevent disk exhaustion.
2. **Path Resolution Performance**:
   Under WSL2, querying Windows filesystem paths (`/mnt/c/...`) via synchronous `os.path.exists` on 65,000 files during epoch initialization can cause multi-minute delays.
   - *Requirement*: The loader must use cached drive-root resolution (`train_data.py`) and stream samples on-demand rather than running a full synchronous filesystem sweep during iteration startup.

---

## 7. Execution Commands

To execute the preflight audit at any time from the repository root:

```powershell
# Help and options
build\vision-training\environment\venv_win_py311\Scripts\python.exe tools/vision/training/environment/audit_training_inputs.py --help

# Run full input preflight audit
build\vision-training\environment\venv_win_py311\Scripts\python.exe tools/vision/training/environment/audit_training_inputs.py

# Optional: customize progress logging interval or output directory
build\vision-training\environment\venv_win_py311\Scripts\python.exe tools/vision/training/environment/audit_training_inputs.py --progress-interval 10000 --output-dir build/vision-training/input-audit-20261031
```

Generated reports are written to:
- `build/vision-training/input-audit-20261031/input_audit_report.json` (Structured JSON)
- `build/vision-training/input-audit-20261031/INPUT_AUDIT_SUMMARY.md` (Executive Markdown)
