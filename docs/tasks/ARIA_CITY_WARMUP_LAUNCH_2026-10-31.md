# ARIA-CITY-WARMUP-LAUNCH-20261031

TASK_ID: ARIA-CITY-WARMUP-LAUNCH-20261031

## Target / Goal / Change
Launch the real full-data `city_warmup` stage for five epochs on the verified WSL GPU, and hand over a durable, observable running job. This Task completes at verified stable launch, not at completion of the five epochs. Do not claim training completion or model quality from launch evidence.

## Context / Read first
Read AGENTS.md, docs/ANTIGRAVITY_DELEGATION.md, docs/VISION_TRAINING_RETRY_2026-10-30.md, docs/VISION_PORTRAIT_TRAINING_EXECUTION.md, tools/vision/training/TRAINING.md, DATA_PREFLIGHT.md and NATIVE_GPU_VERIFICATION.md. The retry document's prohibition on full training applies to its original retry Tasks; this explicit new Task authorizes the full City warmup after coordinator acceptance of native verification.

The full input audit independently passed 10/10 checks: 13,033 records, City train 2,550 / val 425 / reserved test 500, Surface 9,558, 52,132 converted files totaling about 1.03 GiB. Base manifest SHA256 is e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9. Preserve it byte-for-byte for later stages. Pseudo generation already completed and must never be repeated. WSL Python is /home/user/.venvs/aria_wsl/bin/python. Use the process-local CUDA launcher, not plain activation. Windows /mnt/c full path validation was very slow; guest-native converted staging is preferred.

## Ownership / Dependencies / Peers / Allowed changes
Dependency: coordinator accepted native GPU verification and input audit. This worker exclusively owns WSL GPU and full-training process management. No peer may run GPU jobs. Allowed new automation: tools/vision/training/environment/launch_full_training.py and/or stage_full_training_inputs.py; documentation tools/vision/training/FULL_TRAINING_RUN.md; outputs build/vision-training/full-training-20261031/**; guest /home/user/.local/share/aria-training/full-training-20261031/**. Existing launcher may be read, not edited without coordinator agreement. Coordinator owns independent verification and Git integration. No other source edits.

## Constraints / Forbidden
No raw conversion, pseudo generation, held-out test use, app asset replacement, other training stages, credentials, global settings, kernel/driver/WSL restart, reboot, commit, push, blanket deletion or dataset mutation. Preserve all earlier outputs. Do not use a City-only replacement manifest because subsequent stages require identical manifest hashes. Do not copy the raw 18 GiB unnecessarily. Do not silently fall back to CPU or change batch configuration on resume. No competing training processes.

## Requirements
1. Stage the byte-identical full manifest and its relative converted paths on guest-native storage, preferably Windows-native archive creation then guest extraction to avoid 52k /mnt/c path calls. Inspect loader resolution first; preserve safe relative paths. Copy pseudo only if needed, retaining original bytes/provenance. Raw absolute Windows paths remain unchanged. Verify staged full manifest digest and staged artifacts, record byte/file counts and disk headroom. Use original read-only sources.
2. Launch actual trainer with --stage city_warmup --weights imagenet --epochs 5 --learning-rate 0.001 --batch-size 2 --accumulate-steps 4 --seed 42 --device gpu and the full manifest. No partial-manifest or verification-samples options. Keep encoder and BN frozen per original trainer contract.
3. Use durable Linux process/session ownership (detached stdin, persistent logs, exact PID and command identity, exit-code marker/status, no dependence on worker PTY). Record reproducible resume command using same total epochs and configuration. Do not fabricate training completion. Startup must prove actual trainer, GPU device, correct full City train/val counts, successful initial evaluation and advancing real training batches or completed first epoch. Observe memory/disk and finite loss. If existing logs lack per-batch progress, use process/GPU/output evidence plus first epoch, not a fake progress label.
4. Actual GPU OOM permits a fresh distinct output directory with batch1/accum8 after the failed process is proven exited; record the evidence. No speculative fallback. Other failures: preserve logs, diagnose within allowed files, ask coordinator if trainer edits are necessary.
5. Provide machine-readable launch receipt: exact process IDs/start identity, commands/env, input hashes/counts, log/checkpoint paths, status/exit-marker path, memory and timing observations, resume/status commands. Record that the durable job retains exclusive GPU ownership after worker_done. A live PID alone is insufficient acceptance; verify substantive trainer progress.

## Validation commands / Owner
Worker: py_compile new Python helpers with the installed Windows venv; run narrow staging/hash checks and actual launch verification in the specified WSL Python. Coordinator independently reads the launch receipt, process command identity, logs, manifest/config/counts, GPU status and git diff --check. No Android files changed, so no Gradle is required for this Task.

## Observable acceptance / Output format
Full input bytes and split boundaries preserved, real full City warmup advances on GPU with finite results, durable job survives worker settlement, exact status/resume instructions are available, and no unsupported quality/completion claim is made. Report files changed, checks/results, running process identity, output paths, remaining duration uncertainty and limitations. Follow live Orca preamble IDs/cadence, check+ack inbox at checkpoints, send worker_done exactly once with true outcome and three-sentence summary, then idle. Escalate promptly if blocked.
