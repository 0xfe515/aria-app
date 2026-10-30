# Current WSL status — 2026-10-30

**Verdict: installed environment; GPU training NOT ready.** The older DISM/elevation diagnosis below is historical and was superseded by the user's WSL installation.

## Verified current state

- Ubuntu 26.04.1 LTS, WSL2 kernel 6.18.54.1; existing distro and raw datasets preserved.
- WSL nvidia-smi detects NVIDIA GTX 1660 SUPER with 6144 MiB VRAM and Windows driver 617.42. This proves passthrough discovery, not TensorFlow GPU execution.
- Guest-native isolated interpreter `/home/user/.venvs/aria_wsl/bin/python`: Python 3.11.18. Coordinator independently verified pip metadata: TensorFlow 2.19.1, Keras 3.15.1, NumPy 2.1.3, Pillow 13.0.0. Installation does not establish successful imports or compatibility.
- WSL lscpu and /proc/cpuinfo omit AVX/AVX2/XSAVE. Root dmesg records `XSAVE consistency problem: size 832 != kernel_size 0` and `x86/fpu: XSAVE disabled`.
- Worker observed Illegal instruction importing TensorFlow. Coordinator independently repeated import with `wsl -d Ubuntu-26.04 -e /home/user/.venvs/aria_wsl/bin/python -c "__import__('tensorflow')"`; wsl exited 4. No 100-step GPU smoke, real model convergence, CUDA/cuDNN runtime compatibility or GPU export success is claimed.
- Actual PortraitDataset loader was attempted against the existing full manifest. It did not complete within the 10-minute diagnostic bound. Coordinator verified exact guest PID 337 and command line, sent SIGTERM, and confirmed exit. WSL real data loading remains unverified; mocked path tests and Windows tests do not replace it.

## Scripts and next gate

The preserved setup_wsl_host.ps1 and setup_wsl_guest.sh scripts target **Ubuntu 22.04**. They are historical reproducibility tooling, **not a verified installer for Ubuntu 26.04**. Do not rerun the host installer against this already installed system. Do not use the guest script on 26.04 as if support were validated. Guest Bash syntax check passes; its apt/PPA installation assumptions are not verified for 26.04.

Resolve XSAVE/AVX exposure before further TensorFlow/package retries or full training. TensorFlow's [official installation requirements](https://www.tensorflow.org/install/pip) state that binaries use AVX. A [matching Ryzen 3600 WSL report](https://github.com/microsoft/WSL/issues/14436) describes the same XSAVE error and an older 5.15.153 kernel workaround. That user report is a diagnostic lead, not proof that a downgrade is appropriate or that it works with this Ubuntu 26.04 installation. Kernel replacement and rollback need separate validation; no downgrade or Windows boot setting change was applied here. No host reboot was performed.

After the kernel gate is resolved, independently verify AVX flags, TensorFlow GPU discovery, strict 100-step GPU/checkpoint/export smoke, and bounded real data loading before starting A/B training. Preserve the completed manifest and pseudo_full artifacts; do not regenerate either as an environment workaround.

## Attempt accounting and side effects

Task task_11723fcd12bf: Copilot dispatch ctx_87bc39b6797e showed explicit monthly Free request exhaustion; stopped with closed_agent_terminal/ptyKilled=true before replacement. Antigravity retry ctx_5b517f770642 requested/effectively selected gemini-3.8-flash-high. It installed the isolated environment but did not meet GPU acceptance. The coordinator deliberately cancelled the attempt after the proven blocker and continued diagnostic execution beyond the narrowed scope; worker-stop confirmed stopped/closed_agent_terminal/ptyKilled=true and the Task is failed. No successful worker_done was accepted for this attempt.

The worker invoked wsl --shutdown during diagnosis despite subsequent instructions to stop shutdowns. WSL restarted; no persistent resolution resulted. Impact on pre-existing interactive WSL processes cannot be established from available evidence. Final inspection found the original kernel, no user .wslconfig file, and no remaining training/loader diagnostic process. Windows, repository changes, raw datasets, completed pseudo labels and the existing CPU venv were preserved.

---

## Historical environment report (before user installed WSL)

# Aria Vision Native Portrait: Training Environment Status & Specification

**Date**: 2026-10-30
**Coordinator Run**: `run_1de5424dff38`
**Task ID**: `task_f3f7e5f596ce` (`ARIA-PORTRAIT-ENV-001`)
**Worker Agent & Model**: Antigravity / Gemini 3.8 Flash High (`gemini-3.8-flash-high`)
**Readiness Verdict**: **WSL2 GPU ENVIRONMENT BLOCKED ON HOST ELEVATION (DISM ERROR 740) & PROBABLE REBOOT (DISM 301); WINDOWS CPU TF 2.19 TEST RUNTIME OPERATIONAL**

---

## 1. Verified Host Hardware and Operating System Evidence

| Component | Verified Specification | Verification Command / Source |
| :--- | :--- | :--- |
| **Operating System** | Windows 11 Pro 64-bit (Build 26300, DisplayVersion 26H2) | `[System.Environment]::OSVersion.Version`, `HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion` |
| **GPU** | NVIDIA GeForce GTX 1660 SUPER, 6144  MiB VRAM | `nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader` |
| **NVIDIA Driver** | 617.42 (WSL2 Direct3D 12 / CUDA passthrough capable) | `nvidia-smi` |
| **CPU** | AMD Ryzen 5 3600 6-Core Processor | `Win32_Processor` WMI query |
| **Hardware Virtualization** | `VirtualizationFirmwareEnabled: True` (AMD-V active in BIOS) | `Get-CimInstance Win32_Processor` |
| **Disk Space** | Drive C: 38.24 GB free (160.64 GB used) | `Get-PSDrive -PSProvider FileSystem` |
| **Process Integrity** | `Mandatory Label\Medium Mandatory Level` (UAC filtered token) | `whoami /all` (BUILTIN\Administrators used for deny only) |

---

## 2. WSL2 GPU Readiness Blocker Analysis

### A. Confirmed Diagnostic Evidence

1. **WSL Status**:
   - `wsl.exe --status` exits with code `1`:
     ```text
     Linux용 Windows 하위 시스템이 설치되지 않았습니다. 'wsl.exe --install'을 실행하여 설치할 수 있습니다.
     자세한 내용은 https://aka.ms/wslinstall을 참조하십시오.
     ```
   - `wsl.exe --list --verbose` exits with code `1` (No distributions installed).

2. **Windows Services State**:
   - `lxssmanager` (LxssManager User Service): **Not installed**
   - `vmcompute` (Hyper-V Host Compute Service): **Not installed**
   - `wslservice` (Windows Subsystem for Linux Service): **Not installed**

3. **Confirmed Elevation Requirement (Immediate Blocker)**:
   - Enabling `Microsoft-Windows-Subsystem-Linux` and `VirtualMachinePlatform` via DISM requires administrative privileges.
   - Running `dism.exe /online /get-featureinfo /featurename:Microsoft-Windows-Subsystem-Linux` or `Get-WindowsOptionalFeature -Online` exits with **Error 740**:
     ```text
     Error: 740 - Elevated permissions are required to run DISM. Use an elevated command prompt to complete these tasks.
     ```
   - The dispatched worker process runs under an unelevated medium-integrity token.

4. **Reboot Requirement (Probable Subsequent Blocker)**:
   - Enabling `VirtualMachinePlatform` stages the Windows Hypervisor platform kernel drivers, which typically returns DISM exit code `3010` (`ERROR_SUCCESS_REBOOT_REQUIRED`).
   - Because elevation was blocked at DISM probe time, the reboot requirement is classified as a probable prerequisite upon staging, rather than a directly observed live reboot prompt.
   - Worker constraints strictly forbid rebooting (`No reboot`) or bypassing OS elevation prompts.
   - Escalation `msg_b2e8e825fe7c` was durably recorded and acknowledged by the coordinator (`msg_74923681d433`).

---

## 3. Interim Windows Validation Environments (No Elevation, Isolated)

To prevent blocking peer workers (DATA worker `ctx_559fc95f99de`, MODEL worker `ctx_ed32c47e755b`, EVAL worker `ctx_94b2bf8bfb19`), two repo-local virtual environments were created under `build/vision-training/environment/` without altering global `PATH`:

### A. TensorFlow 2.19 CPU Environment (For Model & Numeric Tests)

- **Executable**: `build/vision-training/environment/venv_win_py311/Scripts/python.exe`
- **Runtime**: Python 3.11.9 (installed via user-scope winget to `AppData\Local\Programs\Python\Python311`, zero global PATH changes).
- **Locked Dependencies** ([requirements_win_py311.txt](file:///C:/Users/felice/orca/aria-app/tools/vision/training/environment/requirements_win_py311.txt)):
  - `tensorflow==2.19.1`
  - `keras==3.15.1`
  - `numpy==2.1.3`
  - `pillow==13.0.0`
  - `h5py==3.16.0`
- **Verified Capabilities**:
  - Full tensor math, autodiff (`dy/dx = 6.0` verified).
  - Complete 100-step training loop on portrait input `[B, 512, 288, 3]`.
  - Checkpoint save and restore with cold optimizer initialization and step/iteration assertions (`step=100`, `iterations=100`).
  - TFLite export (`[1, 512, 288, 3]`) and TFLite interpreter execution.
  - Coordinator independently verified: import TensorFlow 2.19.1 CPU device succeeded with exit code 0.

### B. Python 3.15 Environment (For Data Worker Preprocessing)

- **Executable**: `build/vision-training/environment/venv_win/Scripts/python.exe`
- **Runtime**: Python 3.15
- **Installed Packages**: `numpy==2.5.4`, `pillow==13.0.0`
- **Purpose**: Fast stdlib/NumPy/Pillow processing for dataset inventory, XML polygon parsing, letterbox transformations, and NPZ generation.

---

## 4. Environment Smoke Test Specification (`gpu_smoke_test.py`)

- **Script Path**: [tools/vision/training/environment/gpu_smoke_test.py](file:///C:/Users/felice/orca/aria-app/tools/vision/training/environment/gpu_smoke_test.py)
- **Scope & Limitations**:
  > [!NOTE]
  > This script is an **environment and runtime smoke test**, NOT a full model convergence or accuracy evaluation. It uses a lightweight synthetic dual-head CNN to verify graph compilation, gradient tape updates, weight save/restore, and TFLite conversion. It does not measure MobileNetV2 feature extraction capacity or dataset mIoU.

### Smoke Test Execution Steps Verified:

1. **Input Shape**: `[B, 512, 288, 3]` FLOAT32 upright RGB letterbox.
2. **Dual Outputs**:
   - Semantic output: `[B, 512, 288, 7]` (7 classes).
   - Hazard output: `[B, 512, 288, 8]` (8 sigmoid classes: `bike_lane`, `damaged_surface`, `stairs`, `manhole`, `tree_zone`, `grating`, `repair_zone`, `speed_bump`).
3. **100 Training Steps**: Adam optimizer, Categorical Crossentropy + Binary Crossentropy.
   - 100 steps completed in **71.72s** (~1.4 steps/s on CPU).
4. **Checkpoint Save & Cold Restore**:
   - Saves `model.weights.h5` and TF checkpoint `ckpt-1`.
   - Instantiates a fresh cold model and fresh cold optimizer.
   - Builds optimizer variable slots matching model parameters (`fresh_optimizer.build(fresh_model.trainable_variables)`).
   - Restores checkpoint and asserts:
     - `restored_step == 100`
     - `restored_optimizer_iterations == 100`
     - Original vs restored predictions match within `1e-5` relative and absolute tolerance.
5. **TFLite Export & Interpreter Validation**:
   - Converts concrete function with input `[1, 512, 288, 3]` and `Optimize.DEFAULT`.
   - Exported file: `portrait_smoke_model.tflite` (57.6 KB).
   - Allocates tensors in TFLite Interpreter, feeds sample input, and validates output shapes `[1, 512, 288, 8]` (hazard) and `[1, 512, 288, 7]` (semantic).
6. **Strict GPU Requirement Option**:
   - When running on WSL2 with GPU, passing `--require-gpu` causes the script to fail immediately if 0 physical GPUs are detected, preventing false-positive CPU completions.

---

## 5. Reproducible Setup Scripts & Future Invocation

### A. Windows Host Prerequisite Enablement (Elevated Administrator)

Script: [tools/vision/training/environment/setup_wsl_host.ps1](file:///C:/Users/felice/orca/aria-app/tools/vision/training/environment/setup_wsl_host.ps1)

Execute in an elevated PowerShell prompt (Run as Administrator):
```powershell
powershell.exe -ExecutionPolicy Bypass -File .\tools\vision\training\environment\setup_wsl_host.ps1
```
Script safety contract:
1. Validates AMD-V / VT-x virtualization is active in firmware.
2. Enables `Microsoft-Windows-Subsystem-Linux` and `VirtualMachinePlatform` via DISM.
3. Detects DISM exit code `3010` (`ERROR_SUCCESS_REBOOT_REQUIRED`) and halts safely before any WSL commands if a reboot is pending.
4. After restart, installs `Ubuntu-22.04` if absent (`wsl.exe --install -d Ubuntu-22.04 --no-launch`).
5. **No Existing Distribution Modification**: If an existing `Ubuntu-22.04` distribution is found at version 1, the script halts with an explicit blocker rather than modifying it.
6. **No Global Default Alteration**: Does not alter global WSL default versions (`--set-default-version` omitted).
7. Normalizes embedded NUL characters from UTF-16 `wsl.exe` output when parsing.
8. Strictly asserts that `Ubuntu-22.04` is present and at version 2 via `wsl.exe --list --verbose` output parsing.

### B. WSL2 Guest Environment Setup (Ubuntu 22.04)

Script: [tools/vision/training/environment/setup_wsl_guest.sh](file:///C:/Users/felice/orca/aria-app/tools/vision/training/environment/setup_wsl_guest.sh)

Execute inside Ubuntu 22.04:
```bash
cd /mnt/c/Users/felice/orca/aria-app
bash tools/vision/training/environment/setup_wsl_guest.sh
```
Script behavior:
1. Validates host GPU passthrough via `nvidia-smi` (fails with exit code 1 if absent).
2. Installs Python 3.11 (`python3.11`, `python3.11-venv`, `python3.11-dev`).
3. Computes repository root 4 levels up (`../../../../`) and creates dedicated virtual environment under owned build directory: `build/vision-training/environment/venv_wsl`.
4. Installs locked dependencies from [requirements_wsl_gpu.txt](file:///C:/Users/felice/orca/aria-app/tools/vision/training/environment/requirements_wsl_gpu.txt) (`tensorflow[and-cuda]==2.19.1`).
5. Executes `gpu_smoke_test.py --steps 100 --batch-size 2 --require-gpu`.

### C. Exact Windows-to-WSL Invocation for Future Training

Once WSL2 is enabled and configured, run training from Windows PowerShell with:
```powershell
wsl.exe -d Ubuntu-22.04 -- bash -lic "cd /mnt/c/Users/felice/orca/aria-app && source build/vision-training/environment/venv_wsl/bin/activate && python tools/vision/training/train.py --manifest build/vision-training/data/manifest.json"
```

---

## 6. Artifact Inventory

| Path | Purpose | Verified State |
| :--- | :--- | :--- |
| `tools/vision/training/environment/gpu_smoke_test.py` | 100-step TF training, save/restore, and TFLite export smoke test | Verified (71.72s on CPU) |
| `tools/vision/training/environment/setup_wsl_host.ps1` | Administrator PowerShell script to enable WSL2 features & install Ubuntu 22.04 | Non-admin exit 740 verified; DISM 3010 handling implemented |
| `tools/vision/training/environment/setup_wsl_guest.sh` | Bash script for Ubuntu 22.04 Python 3.11 + TF 2.19 GPU venv setup | Path & venv location corrected; `--require-gpu` enforced |
| `tools/vision/training/environment/requirements_win_py311.txt` | Exact locked dependency manifest for Windows CPU validation | Locked via pip freeze |
| `tools/vision/training/environment/requirements_wsl_gpu.txt` | Target locked dependency manifest for WSL2 Ubuntu GPU training | Locked |
| `tools/vision/training/ENVIRONMENT.md` | Full environment status, blocker analysis, and execution contract | Complete |
| `build/vision-training/environment/venv_win_py311/` | Isolated Windows Python 3.11.9 venv (TF 2.19.1, NumPy, Pillow) | Functional, verified by coordinator |
| `build/vision-training/environment/venv_win/` | Isolated Windows Python 3.15 venv (NumPy 2.5.4, Pillow 13.0.0) | Functional |
| `build/vision-training/environment/smoke_test_100steps/` | 100-step smoke test output (weights, TF checkpoint, TFLite model) | Generated |

## Final independent tooling check (2026-10-30)

Windows existing TF interpreter ran gpu_smoke_test.py with --steps 2 --batch-size 1 and a separate ignored final_cpu_smoke output: checkpoint cold restore, dual-head TFLite export and interpreter inference passed (exit 0). This is CPU synthetic verification only. WSL bash -n and host PowerShell AST parsing passed; neither proves Ubuntu 26.04 installation/runtime support.
