# Frozen Teacher Pseudo-Label Execution Report

## 1. Executive Summary

- **Task**: `ARIA-PORTRAIT-PSEUDO-FULL-002` (Orca Task ID: `task_e3eafd9b16e8`, Dispatch ID: `ctx_bf0d23851d1f`).
- **Target**: Frozen teacher pseudo-label generation for all eligible Surface training records in `build/vision-training/data/manifest.json`.
- **Status**: Completed (`complete: true`, `status: "complete"`).
- **Teacher Model**: `app/src/main/assets/models/walkable_seg_aria5_portrait_unfused.tflite`
  - Verified SHA-256: `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`
  - Input: single `[1, 512, 288, 3]` float32 (RGB normalized 0..1).
  - Output: single `[1, 512, 288, 7]` float32 normalized probabilities.
- **Output Artifacts**:
  - Full production: `build/vision-training/data/pseudo_full/`
    - `pseudo_manifest.json` (708 records, schema version 1)
    - `completion_report.json` (complete accounting of all 6,647 records)
    - `labels/`: 708 uint8 PNG label masks (`[512, 288]`)
    - `weights/`: 708 compressed NPZ archives (`semantic_source`, `semantic_weight`, `confidence`)
    - Total generated files: 1,416 label and weight artifacts.
  - Bounded benchmark: `build/vision-training/pseudo-benchmark/`
    - `serial-16/`: 16-sample serial run (`workers=1`)
    - `parallel4-16/`: 16-sample parallel run (`workers=4`)

## 2. Dataset Processing Accounting

| Metric | Count | Details |
|---|---|---|
| Total Manifest Records | 13,033 | Full joint dataset inventory |
| Surface Train Records | 6,647 | Only eligible source/split subset |
| Converted Records Processed | 6,647 | 100% processed without errors |
| Unconverted Train Records | 0 | All Surface train records converted |
| Held-Out (Val/Test) Records Inferred | 0 | Strict non-inference on val/test |
| Records With Accepted Pseudo-Labels | 708 | >= 1 accepted object pixel |
| Records Without Accepted Pseudo-Labels | 5,939 | Filtered by conservative policy |
| Skipped Fully Annotated Records | 0 | All inspected had ignore regions |

### Accepted Pixel Statistics by Class

| Class ID | Class Name | Accepted Pixels | Supervised Weight | Supervised Source ID |
|---|---|---|---|---|
| 0 | `other` | 0 | 0.0 | 0 |
| 1 | `road` | 0 | 0.0 | 0 |
| 2 | `sidewalk` | 0 | 0.0 | 0 |
| 3 | `crosswalk` | 0 | 0.0 | 0 |
| 4 | `person` | 11,446 | 0.25 | 2 |
| 5 | `vehicle` | 373,438 | 0.25 | 2 |
| 6 | `pole_sign` | 5,704 | 0.25 | 2 |
| **Total** | **Objects (4/5/6)** | **390,588** | **0.25** | **2** |

## 3. Conservative Agreement Policy

In accordance with `DATA_CONTRACT.md` and repository guidelines:
1. **Input Geometry**: Upright NHWC float32 `[1, 512, 288, 3]`, RGB normalized in `[0, 1]`.
2. **Dual-Pass Flip Agreement**:
   - Original image inference: $P_{\text{orig}} \in [0, 1]^{512 \times 288 \times 7}$.
   - Horizontally flipped image inference: $P_{\text{flip}} \in [0, 1]^{512 \times 288 \times 7}$.
   - Spatial reflection back to original coordinates: $P_{\text{refl}}(y, x, c) = P_{\text{flip}}(y, W - 1 - x, c)$.
   - Top-1 class agreement required: $\arg\max_c P_{\text{orig}}(y, x, c) == \arg\max_c P_{\text{refl}}(y, x, c)$.
3. **Restricted Class Scope**: Classes $\{4, 5, 6\}$ (`person`, `vehicle`, `pole_sign`) only. All background/surface classes (`road`, `sidewalk`, `crosswalk`, `other`) are never emitted as pseudo-labels.
4. **Confidence and Margin Thresholds**:
   - Both passes must satisfy confidence: $\max_c P_{\text{orig}} \ge 0.90$ and $\max_c P_{\text{refl}} \ge 0.90$.
   - Both passes must satisfy margin: $P_{\text{top1}} - P_{\text{top2}} \ge 0.20$.
5. **Mask Restrictions**:
   - Original ground-truth mask must be `IGNORE` (255) at the pixel (no overriding human or converted labels).
   - Pixel must fall within `content_valid` bounds determined by `letterbox_geometry` from the original aspect ratio (black padding is strictly excluded and ignored).
6. **Provenance & Weighting**:
   - Accepted pixels receive `semantic_source = 2` and `semantic_weight = 0.25`.
   - All other pixels receive `semantic_source = 0` and `semantic_weight = 0.0`.
   - Teacher minimum confidence $\min(C_{\text{orig}}, C_{\text{refl}})$ is retained per pixel in `confidence`.

## 4. Concurrency Architecture & Benchmark Parity

- **Worker Concurrency**: `--workers 4` bounded thread pool using `ThreadPoolExecutor`.
- **Interpreter Isolation**: Thread-local storage (`threading.local`) guarantees each worker thread allocates and invokes its own private `Interpreter(num_threads=1)` instance. Tensors and invocations are never shared across threads.
- **Process Thread Limits**: Configured via TensorFlow CPU runtime controls:
  - `tf.config.set_visible_devices([], "GPU")`
  - `tf.config.threading.set_intra_op_parallelism_threads(2)`
  - `tf.config.threading.set_inter_op_parallelism_threads(2)`
- **Bounded Buffer Pipeline**: Work queue bounded at `workers * 2` active futures; records are loaded, processed, saved, and freed incrementally to ensure constant memory footprint without RAM caching.
- **Deterministic Parity**:
  - Evaluated on 16 Surface training samples (`serial-16` vs `parallel4-16`).
  - The two runs have identical empty pseudo-record lists and processing counts; their completion reports differ in the expected `workers` field (1 versus 4). All 16 samples produced no accepted pseudo pixels, so this real-data check does not establish equivalence for nonempty output masks.
  - Unit tests separately verify private-interpreter ownership and identical nonempty output arrays with a controlled fake interpreter. No accuracy or convergence claim follows from either check.

## 5. Validation Commands

1. **Unit Tests**:
   ```powershell
   build/vision-training/environment/venv_win_py311/Scripts/python.exe -m unittest discover -s tools/vision/training/tests -p test_pseudo_labels.py -v
   ```
   *Result*: 10 passed, 0 failures, 0 errors in 1.158s.

2. **CLI Inspection**:
   ```powershell
   build/vision-training/environment/venv_win_py311/Scripts/python.exe -m tools.vision.training.pseudo_labels --help
   ```
   *Result*: Valid options documented (`--manifest`, `--model`, `--output`, `--workers`, `--limit`, `--progress-interval`).

3. **Artifact Integrity Verification**:
   - Model SHA-256 matches: `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`.
   - 708 PNG label files and 708 NPZ weight files verified in `build/vision-training/data/pseudo_full/`.
   - 100% of pseudo manifest records are from source `surface` and split `train`.
   - Non-pseudo pixels strictly masked to 255 with 0.0 weight; accepted pixels strictly in classes 4..6 with 0.25 weight and confidence $\ge 0.90$.
