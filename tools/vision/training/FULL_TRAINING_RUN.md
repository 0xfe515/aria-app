# Aria Native Portrait: Full Training Execution & City Warmup Launch

**Document ID**: `ARIA-FULL-TRAINING-RUN-20261031`
**Run ID**: `run_1de5424dff38` | **Task ID**: `ARIA-CITY-WARMUP-LAUNCH-20261031`
**Active Attempt**: `attempt_02` (`aria-city-warmup-20261031-attempt-02.service`)
**Target Architecture**: MobileNetV2 / U-Net Model A (`portrait_model_a`)
**Target Hardware**: NVIDIA GeForce GTX 1660 SUPER (6 GiB VRAM), WSL2 Ubuntu 26.04
**Interpreter**: `/home/user/.venvs/aria_wsl/bin/python` (Python 3.11.18, TensorFlow 2.19.1)

---

## 1. Scope & Execution Contract

Following coordinator verification and acceptance of native GPU readiness (`NATIVE_GPU_VERIFICATION.md`), preflight data audit (`DATA_PREFLIGHT.md`), and the `train_data.py` parent-directory canonicalization cache (`task_fa96817605aa`), this document records the verified durable launch of Stage 1: **Full-Data City Warmup (`city_warmup`)** under clean attempt `attempt_02`.

### Constraints & Invariants
- **Dataset Manifest Integrity**: Staged manifest (`/home/user/.local/share/aria-training/full-training-20261031/data/manifest.json`) is byte-identical to `build/vision-training/data/manifest.json` (`e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`). No City-only partial manifest is used.
- **Data Invariants**: 13,033 total records; Cityscapes train 2,550, val 425, test 500 reserved (1,275 batches/epoch at batch size 2). 52,132 converted artifacts (~1.03 GiB) staged onto guest ext4 filesystem (`/home/user/.local/share/aria-training/full-training-20261031/data/converted/`). Original raw images (~18 GiB) remain on `/mnt/c/` and are read-only.
- **Model Invariants**: Upright portrait input `[B, 512, 288, 3]`, output `[B, 512, 288, 7]`. MobileNetV2 encoder frozen (`freeze_all`), 50 Batch Normalization layers frozen (`freeze_bn: True`), ImageNet initialization (`--weights imagenet`).
- **Optimization**: Learning rate `0.001`, batch size `2`, accumulation steps `4` (effective batch size 8), clipnorm `1.0`, seed `42`, device `gpu`.
- **Durable Process Ownership**:
  - Guest Linux process tree runs inside transient systemd user unit `aria-city-warmup-20261031-attempt-02.service` (`Supervisor PID 3983`, `Trainer PID 3984`, `/proc/3984/stat` `start_ticks 355647`) with `stdin=DEVNULL` and unbuffered output to `/home/user/.local/share/aria-training/full-training-20261031/attempt_02/city_warmup/training.log`.
  - Host WSL2 VM lifetime is held open by a WMI-spawned Windows keeper process (`Win32_Process.Create`, Windows PID `9048`, child `wsl.exe` PID `11432`, parent `WmiPrvSE.exe` PID `11844`) running `/home/user/.local/share/aria-training/full-training-20261031/attempt_02/city_warmup/keeper.py 3984 355647`, which polls `/proc/3984/stat` every 5 seconds and exits automatically only if PID `3984` exits or its `start_ticks` (`355647`) changes.

---

## 2. Staged Dataset Accounting & Disk Headroom

The staging tool (`tools/vision/training/environment/stage_full_training_inputs.py`) and `build/vision-training/full-training-20261031/staging_report.json` verified 10/10 checks:

| Component | Path | Count / Size | SHA-256 Digest | Status |
| :--- | :--- | ---:| :--- | :--- |
| **Source Base Manifest** | `build/vision-training/data/manifest.json` | 13,033 records | `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9` | MATCH |
| **Guest Staged Manifest** | `/home/user/.local/share/aria-training/full-training-20261031/data/manifest.json` | 13,033 records | `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9` | BYTE-IDENTICAL |
| **Guest Converted Files** | `/home/user/.local/share/aria-training/full-training-20261031/data/converted/` | 52,132 files | 1,105,650,967 B (~1.03 GiB) | VERIFIED (0 empty files) |
| **Guest Pseudo Files** | `/home/user/.local/share/aria-training/full-training-20261031/data/pseudo_full/` | 1,418 files | 3,870,928 B (~3.69 MiB) | VERIFIED |
| **Physical Host Headroom** | Windows `C:\` | **~28–31 GiB physical free** | - | **LIMITING PHYSICAL HEADROOM (Adequate)** |
| **Guest Virtual Capacity** | `/home/user/.local/share/aria-training` | 947+ GiB logical VHDX capacity | - | LOGICAL MAXIMUM (bounded by host disk) |

---

## 3. Preserved Prior Attempt (`attempt_01_pid472_sigtrap`) & Forensic Summary

Before launching `attempt_02`, the prior worker session's failed attempt (`Supervisor PID 471`, `Trainer PID 472`, systemd unit `aria-city-warmup-20261031.service`) was investigated and archived without modifying `/home/user/.local/share/aria-training/full-training-20261031/city_warmup`:

- **Archive Locations**:
  - Guest: `/home/user/.local/share/aria-training/full-training-20261031/attempt_01_pid472_sigtrap/`
  - Host: `build/vision-training/full-training-20261031/attempt_01_pid472_sigtrap/forensic_report.json` (plus `training.log`, `exit_code.txt`, `supervisor.pid`, `trainer.pid`, `supervisor.py`)
- **Observed Timeline & Root Cause**:
  1. `PID 472` spent ~24 minutes (`01:46:32`–`02:10:45` KST) in `PortraitDataset.__init__` resolving 26,066 `/mnt/c` raw paths across 9P (`>1.85M` voluntary context switches in `p9_client_rpc`), then completed `_scan_training_statistics` on guest ext4 and printed the `Staged Trainer: city_warmup (model_a)` banner at `02:13:42` KST.
  2. Immediately after printing the banner, `PID 472` terminated with `exit_code = -5` (`SIGTRAP`, signal 5).
  3. Kernel `dmesg` recorded: `traps: python[6290] trap int3 ip:7f82afb3b221 sp:7ffcb211eb30 error:0 in ld-linux-x86-64.so.2[7f82afb2d000+2f000]` with instruction bytes `80 7d cf 00 74 01 cc <80> 7b 2c 00`.
  4. `readelf -n /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2` identified `0x7f82afb3b220` (`vaddr 0xf220`) as the glibc SystemTap probe `rtld:reloc_complete` (normally `0x90` `NOP` on disk). A prior worker session had attached `gdb -p 472 -batch -ex bt` at `01:54:57` and killed that task without detaching, leaving GDB's internal solib-event software breakpoint (`0xcc`) in `PID 472`'s copy-on-write page. When `PID 472` later invoked `dlopen()` during model/CUDA initialization at `02:13:42`, it executed that `0xcc` with no tracer attached.
  5. **Policy**: No `gdb` or `ptrace` attachment is permitted on any training process.

---

## 4. Active Run (`attempt_02`) Architecture & Verification Evidence

### Process & Keeper Identity (`build/vision-training/full-training-20261031/launch_receipt.json`)
- **Systemd Unit**: `aria-city-warmup-20261031-attempt-02.service`
- **Supervisor PID**: `3983`
- **Trainer PID**: `3984` (`start_ticks`: `355647`)
- **Trainer Command**:
  ```text
  /home/user/.venvs/aria_wsl/bin/python /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py --allow-growth -- --manifest /home/user/.local/share/aria-training/full-training-20261031/data/manifest.json --stage city_warmup --weights imagenet --epochs 5 --learning-rate 0.001 --batch-size 2 --accumulate-steps 4 --seed 42 --device gpu --output-dir /home/user/.local/share/aria-training/full-training-20261031/attempt_02
  ```
- **Windows Keeper (`windows_keeper.json`)**:
  - **Mechanism**: `wmi_win32_process_create` (`Invoke-CimMethod -ClassName Win32_Process -MethodName Create`), `out_of_worker_job: true`, `used_breakaway_from_job: false`.
  - **Host Runtime Observation**: Direct `subprocess.Popen` children spawned inside the worker's `run_command` tool are subject to the tool's Windows Job Object cleanup on command return (and `C:\Windows\System32\wsl.exe` exits immediately with `rc=0` without running the guest command if `DETACHED_PROCESS` `0x08` is passed). Launching `wsl.exe` via WMI `Win32_Process.Create` parents the keeper under `WmiPrvSE.exe` (`PID 11844`) outside the worker Job Object and console session. Final persistence across worker release is independently verified by the coordinator after `worker-release`.
  - **Active Keeper PID**: `9048` (child `wsl.exe` PID `11432`, parent `WmiPrvSE.exe` PID `11844`, created `2026-10-30T17:32:42.476000+00:00`), bound to `trainer_pid=3984` and `start_ticks=355647`.
  - **Isolated WMI Creation & Auto-Exit Probe**: Verified fresh WMI creation branch in `ensure_windows_keeper` against a transient no-GPU `systemd-run --user --collect --unit=aria-wmi-probe-sleep /bin/sleep 8` process (`PID 4888`, `start_ticks 405473`), which spawned Windows keeper `PID 9072`, wrote `windows_keeper.pid` (`9072`) and `windows_keeper.json`, logged `keeper started for pid=4888 start_ticks=405473`, and automatically logged `keeper exiting for pid=4888: proc_gone` and exited cleanly (`is_alive=False`) once `sleep 8` finished.

### Observed Startup, Initial Evaluation & Batch Progress
- **Trainer Banner**: Observed (`Staged Trainer: city_warmup (model_a)`, `Epochs: 5 | LR: 0.001 | Batch: 2 | Accumulate: 4 | Seed: 42`, `Device: gpu | Diagnostic partial: False`, `Encoder policy: freeze_all | Freeze BN: True`).
- **GPU Initialization**: `NVIDIA GeForce GTX 1660 SUPER` (`4661–4663 MiB / 6144 MiB` allocated, `cuDNN 90300`). Non-fatal CUDA autotune allocation warnings during conv algorithm selection were observed as expected.
- **Epoch 0 Initial Validation Evaluation**: Completed across all 425 Cityscapes validation samples and committed to `/home/user/.local/share/aria-training/full-training-20261031/attempt_02/city_warmup/checkpoints/best/metadata.json` (`epoch: 0`, `stage: "city_warmup"`).
- **Advancing Training Batches**:
  - `Epoch 1/5 | Batch 1/1275 | Loss: 3.3415`
  - `Epoch 1/5 | Batch 21/1275 | Loss: 2.2386` (total process elapsed from `02:28:21` launch to `02:41:03` receipt: `12m 41s`; final `--observe-only` window elapsed: `120.66s`)
  - Subsequent observed progress by `02:42:27` KST (`14m 04s` process elapsed): `Batch 41/1275` (`loss 2.0083`), `Batch 61/1275` (`loss 1.8260`), `Batch 81/1275` (`loss 1.7425`), `Batch 101/1275` (`loss 1.3730`), `Batch 121/1275` (`loss 1.1261`).
  - Planned batches verified: `1275` (`2550` Cityscapes training records at `batch_size = 2`), losses finite (`loss_is_finite: true`).

---

## 5. Operational Commands

### Check Job Status
```bash
wsl -d Ubuntu-26.04 -- bash -lc "ps -p 3984 -o pid,vsz,rss,stat,etime,time,command && tail -n 25 /home/user/.local/share/aria-training/full-training-20261031/attempt_02/city_warmup/training.log && /usr/lib/wsl/lib/nvidia-smi"
```

Or via the launcher helper (which reuses existing keeper `9048` without launching duplicates):
```powershell
build\vision-training\environment\venv_win_py311\Scripts\python.exe tools/vision/training/environment/launch_full_training.py --attempt-id attempt_02 --status-only
```

### Resume Training (in case of interruption)

Use this command only after `checkpoints/latest` exists. The initial epoch 0 evaluation checkpoint does not establish completion of a training epoch.
```bash
wsl -d Ubuntu-26.04 -- /home/user/.venvs/aria_wsl/bin/python \
  /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py \
  --allow-growth -- \
  --manifest /home/user/.local/share/aria-training/full-training-20261031/data/manifest.json \
  --stage city_warmup \
  --resume /home/user/.local/share/aria-training/full-training-20261031/attempt_02/city_warmup/checkpoints/latest \
  --epochs 5 \
  --learning-rate 0.001 \
  --batch-size 2 \
  --accumulate-steps 4 \
  --seed 42 \
  --device gpu \
  --output-dir /home/user/.local/share/aria-training/full-training-20261031/attempt_02
```

---

## 6. Limitations & Caveats

1. **Launch Verification Only**: This task establishes verified stable launch, Epoch 0 initial evaluation checkpoint commit, and advancing training batches (`Batch 1/1275` -> `Batch 21/1275+`) with finite loss on GPU. It does **not** claim 5-epoch training completion, final convergence, or model quality.
2. **Resource Ownership**: The durable job (`PID 3984`) retains exclusive GPU ownership until completion of the 5 warmup epochs.
3. **No Automatic OOM Rollback**: In the event of an actual GPU OOM, the process preserves the last epoch-boundary checkpoint and exits; fallback to `--batch-size 1 --accumulate-steps 8` is permitted only into a fresh output directory after proving the prior process exited.
