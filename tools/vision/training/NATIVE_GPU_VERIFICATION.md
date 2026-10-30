# Aria Vision Native Portrait: GPU Training & Multi-Epoch Cold Resume Verification

**Document ID**: `ARIA-NATIVE-GPU-VERIFICATION-20261031`
**Run ID**: `run_1de5424dff38` | **Dispatch ID**: `ctx_0508b2624f2e` | **Task ID**: `task_7fb98f87e080` (`ARIA-NATIVE-GPU-TRAIN-VERIFY-20261031`)
**Status**: **VERIFIED & ACCEPTED (24/24 Checks PASS)**
**Implementation Worker**: Antigravity / Gemini 3.8 Flash High (`gemini-3.8-flash-high`)
**Scope**: Native GPU training verification for MobileNetV2/U-Net Model A, process-local CUDA entrypoint, multi-epoch training, epoch-boundary cold resume, and tensor parity on real bounded Cityscapes data.

---

## 1. Executive Summary & Verdict

Native GPU training of the **actual MobileNetV2/U-Net Model A** on NVIDIA GeForce GTX 1660 SUPER (6 GiB) inside WSL2 Ubuntu 26.04 is **fully verified and passed**.

The staged trainer (`tools.vision.training.train`), running under the process-local CUDA entrypoint (`tools/vision/training/environment/run_wsl_training.py`), successfully completed in accepted run `native_run_05`:
1. **Model A Architecture & ImageNet Parity**: Built full Model A (5,414,279 parameters) with upright NHWC portrait input `[B, 512, 288, 3]`, output `[B, 512, 288, 7]`. Truncated encoder weights match official Keras MobileNetV2 ImageNet reference across all 101 weighted layers (251 tensors); all 50 Batch Normalization layers are frozen; exactly 20 variables outside the encoder (decoder + semantic head) are trainable.
2. **Uninterrupted 2-Epoch Training**: Completed 2 full epochs on real bounded diagnostic data (24 train, 6 val) on `GPU:0` in 65.59 seconds with finite losses and metrics (Epoch 1: train 2.9244, val 2.4755, mIoU 0.0957; Epoch 2: train 2.3366, val 2.3106, mIoU 0.1309).
3. **Controlled Interruption & Clean Epoch Boundary**: A deliberate interruption at validation evaluation call 3 (after epoch 2 training, before epoch 2 checkpoint commit) exited cleanly with code 75, preserving the verified epoch 1 checkpoint and leaving no corrupt pending directories.
4. **Cold Resume Equivalence**: Cold resume from the preserved epoch 1 checkpoint executed epoch 2 only in 41.20 seconds, achieving **bitwise identical weights** (`weights_files_identical_sha256 = true`, max abs diff `0.0`), **bitwise identical optimizer variables** (42 arrays, max abs diff `0.0`), and identical validation loss/mIoU (`relative difference = 0.0`).
5. **GPU Placement & Memory Safety**: Verified physical tensor placement on `/device:GPU:0` for batch inputs, activations, trainable variables, accumulators, and optimizer slots. Peak VRAM was ~1,536 MiB with `allow_growth=True`, well within the 6 GiB physical budget, and guest disk footprint was 340 MB.

---

## 2. Adaptive Model & Quota Switch Accounting

Per `AGENTS.md` (2026-10-30 adaptive model policy override):
- The initial verification attempt under Claude Sonnet 5.5 High (`claude-sonnet-5-5-high`) was stopped cleanly (`ptyKilled=true`) after provider allowance was reached.
- In accordance with the project failover protocol, the task context, workspace, and durable artifacts were preserved. No orphan WSL processes were left running.
- Dispatched worker `gemini-3.8-flash-high` resumed the task in fresh dispatch `ctx_0508b2624f2e`. The coordinator confirmed independent verification of preserved checkpoints, and final verification was completed without redundant compute or unauthorized full-training launches.
- Preserved run `native_run_04` completed continuous 2 epochs, interruption, and resume; its report is preserved as a superseded classification check, while `native_run_05` represents the final accepted verification run with complete 251-tensor encoder inspection.

---

## 3. Environment & Hardware Verification

| Component | Specification | Verification Source / Command |
| :--- | :--- | :--- |
| **Host OS** | Windows 11 Pro 64-bit | System Environment |
| **GPU** | NVIDIA GeForce GTX 1660 SUPER, 6144 MiB VRAM | `/usr/lib/wsl/lib/nvidia-smi` |
| **WSL2 Distro** | Ubuntu 26.04.1 LTS | `lsb_release -a` |
| **WSL2 Kernel** | `5.15.167.4-microsoft-standard-WSL2` (Official Microsoft override) | `uname -r`, restores AVX/AVX2/XSAVE/FMA |
| **Guest Python** | `/home/user/.venvs/aria_wsl/bin/python` (Python 3.11.18) | Isolated guest-native venv |
| **Frameworks** | TensorFlow `2.19.1`, Keras `3.15.1`, NumPy `2.1.3` | `verify_wsl_gpu.py` query |
| **CUDA / cuDNN** | CUDA runtime `12.5.1`, cuDNN `90300` (pip `nvidia/*` wheels) | Loaded dynamically via `run_wsl_training.py` |
| **Device Discovery** | `/physical_device:GPU:0` (`NVIDIA GeForce GTX 1660 SUPER`, CC 7.5) | `tf.config.get_visible_devices("GPU")` |

---

## 4. Process-Local CUDA Runner (`run_wsl_training.py`)

A reproducible process-local entrypoint was established at [run_wsl_training.py](environment/run_wsl_training.py):
- **Dynamic Linker Preparation**: The runner discovers the interpreter's `site-packages/nvidia/*/lib` and `bin` directories plus `/usr/lib/wsl/lib` (the WSL Direct3D/CUDA shim), prepends them to `LD_LIBRARY_PATH` and `PATH`, and re-executes itself once via `os.execve` before any TensorFlow module is imported.
- **Process Isolation**: All modifications apply exclusively to the current process and its children. Nothing is written to shell profiles (`~/.bashrc`), `/etc/environment`, system directories, or `.wslconfig`.
- **Trainer Interface Transparency**: All arguments following `--` are forwarded directly to `tools.vision.training.train` (or custom `--module`), preserving existing CLI flags and device selection semantics:
  ```bash
  /home/user/.venvs/aria_wsl/bin/python \
      tools/vision/training/environment/run_wsl_training.py --allow-growth --deterministic -- \
      --manifest <MANIFEST> --stage city_warmup --weights imagenet --output-dir <DIR> --device gpu ...
  ```

---

## 5. Model A Architecture & ImageNet Weights Verification

Model A was built and inspected via `verify_native_training.py model-info` ([model_info.json](../../build/vision-training/native-gpu-20261031/native_run_05/model_info.json)):

```text
Model: portrait_model_a (MobileNetV2 encoder + U-Net decoder)
Input shape:  [None, 512, 288, 3] (upright RGB)
Output shape: [None, 512, 288, 7] (7 semantic classes)
Total parameters:        5,414,279
Trainable parameters:    3,572,295
Non-trainable params:    1,841,984
Batch Normalization:     50 layers (0 trainable; moving statistics frozen)
Encoder trainability:    freeze_all (0 trainable parameters in encoder)
Trainable variables:     20 (decoder conv/transpose layers + semantic 1x1 head)
```

- **ImageNet Initialization Parity**: Truncated encoder weights were compared against the standard Keras `MobileNetV2(weights="imagenet", include_top=False)` reference. All 101 weighted layers and 251 weight tensors matched bitwise (`imagenet_encoder_weights_equal_keras_application: true`).
- **Initial Divergence from Random**: `encoder.Conv1` weights were compared to random initialization, confirming standard pre-trained ImageNet weights were active (`encoder_conv1_differs_from_random_init: true`).

---

## 6. Diagnostic Real-Data Subset

To verify training mechanics without expensive or unauthorized full runs:
- Derived subset manifest: `manifest_subset.json` built from base `manifest.json` (`e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`).
- Base manifest was verified unchanged (SHA-256 identical before and after).
- **Counts**: 24 Cityscapes training records, 6 Cityscapes validation records, 0 test records.
- **Split Invariants**: Train and validation subsets are strictly group-disjoint (no shared `group_id` or image hashes).
- **Diagnostic Scope**: Marked `diagnostic_subset: true`. No model convergence or production quality claim is made.

---

## 7. Multi-Epoch Training and Cold-Resume Verification (`native_run_05`)

Full verification run `native_run_05` completed all 24 required checks:

### A. Execution Steps & Timings

| Step | Command / Target | Wall Clock | Exit Code | Outcome |
| :--- | :--- | :--- | :--- | :--- |
| **Model Info** | `verify_native_training.py model-info` | 13.92 s | 0 | Model A verified; ImageNet weights verified |
| **A: Uninterrupted** | 2 epochs `city_warmup` (batch 2, accum 4) | 65.59 s | 0 | Epochs 1 & 2 completed on GPU; losses finite |
| **B: Interrupted** | Deliberate interrupt at validation call 3 | 55.86 s | 75 | Clean diagnostic interrupt; Epoch 1 checkpoint intact |
| **B2: Cold Resume** | Cold resume from Epoch 1 snapshot to Epoch 2 | 41.20 s | 0 | Resumed Epoch 2 only; loss identical to Run A |

### B. Loss & Metric Trajectory

| Epoch | Run A (Uninterrupted) Train Loss | Run A Val Loss | Run A Val mIoU | Resumed Run B2 Train Loss | Resumed Run B2 Val Loss | Resumed Run B2 Val mIoU |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | 2.9244 | 2.4755 | 0.0957 | *(Restored from saved checkpoint)* | *(Restored from saved checkpoint)* | *(Restored from saved checkpoint)* |
| **2** | 2.3366 | 2.3106 | 0.1309 | 2.3366 | 2.3106 | 0.1309 |

- Relative difference between uninterrupted and cold-resumed epoch 2 training loss: **0.0000** (exact match).
- Relative difference between uninterrupted and cold-resumed epoch 2 validation total loss: **0.0000** (exact match).

### C. Checkpoint & Optimizer State Parity

Comparison between Run A `latest` (epoch 2) and Run B2 `latest` (epoch 2):
- **Model Weights**: 271 arrays compared; maximum absolute difference = **0.0**, maximum relative difference = **0.0**.
- **SHA-256 Bitwise Match**: `weights_files_identical_sha256 = true` (`model_weights.weights.h5`).
- **Optimizer State**: 42 arrays compared (including slot variables and iteration counter `opt_0 = 6`); maximum absolute difference = **0.0**.
- **Encoder Invariance**: All 251 encoder tensors remained completely unchanged between initial ImageNet initialization, epoch 1, and epoch 2 (max abs diff = **0.0**).
- **Decoder Evolution**: Non-encoder weights advanced continuously from initialization to epoch 1 (max abs diff = 0.0030) and epoch 2 (max abs diff = 0.0030).

### D. Verification Checklist (24/24 PASS)

```text
[PASS] A_two_epochs_completed: true
[PASS] A_device_gpu: true
[PASS] A_losses_finite: true
[PASS] A_weights_finite: true
[PASS] A_optimizer_iterations_expected: true (6 iterations)
[PASS] interrupted_epoch1_checkpoint_preserved: true (epoch 1 intact)
[PASS] interrupted_accumulator_empty: true
[PASS] resumed_epoch_is_2: true
[PASS] resumed_only_ran_epoch2: true
[PASS] resumed_losses_finite: true
[PASS] resumed_weights_finite: true
[PASS] resumed_optimizer_iterations_expected: true (6 iterations)
[PASS] same_manifest_hash: true
[PASS] same_stage_config_hash: true
[PASS] same_class_weights: true
[PASS] frozen_encoder_unchanged_in_training: true (251 encoder tensors diff = 0.0)
[PASS] decoder_updated_epoch1: true (diff > 0.0)
[PASS] decoder_updated_epoch2_after_resume: true (diff > 0.0)
[PASS] A_final_weights_vs_resumed_max_abs_diff_le_1e-4: true (exact 0.0)
[PASS] A_vs_resumed_optimizer_max_abs_diff_le_1e-4: true (exact 0.0)
[PASS] A_vs_resumed_epoch2_losses_rel_diff_le_1e-3: true (exact 0.0)
[PASS] ImageNet_encoder_init_verified: true
[PASS] gpu_tensor_placement_verified: true (all on /device:GPU:0)
[PASS] base_manifest_unchanged: true
```

---

## 8. Resource Utilization & Recommendations for Full Training

- **GPU Memory**: Peak allocated VRAM during batch 2 accumulation 4 training was ~1,536 MiB. The NVIDIA GTX 1660 SUPER 6 GiB provides comfortable headroom (~4.5 GiB free) under `TF_FORCE_GPU_ALLOW_GROWTH=true`.
- **I/O Considerations**: Path translation and random reads from `/mnt/c/` across 52k files can introduce latency. For the upcoming 5-epoch full `city_warmup` run, staging the full converted inputs (`build/vision-training/data/`) onto the guest-native filesystem (`/home/user/.local/share/aria-training/`) is recommended, as authorized in `ARIA_CITY_WARMUP_LAUNCH_2026-10-31.md`.
- **Full Warmup Invocation Template**:
  ```bash
  /home/user/.venvs/aria_wsl/bin/python \
      /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py \
      --allow-growth -- \
      --manifest <STAGED_MANIFEST> \
      --stage city_warmup \
      --weights imagenet \
      --epochs 5 \
      --learning-rate 0.001 \
      --batch-size 2 \
      --accumulate-steps 4 \
      --seed 42 \
      --device gpu \
      --output-dir /home/user/.local/share/aria-training/full-training-20261031/city_warmup
  ```
