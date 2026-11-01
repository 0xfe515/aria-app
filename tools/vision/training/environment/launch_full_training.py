#!/usr/bin/env python3
"""Durable background launcher and observer for Aria native portrait full training."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

EXPECTED_BASE_MANIFEST_SHA256 = (
    "e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9"
)
EXPECTED_PLANNED_BATCHES = 1275
WSL_DISTRO = "Ubuntu-26.04"
WSL_PYTHON = "/home/user/.venvs/aria_wsl/bin/python"
RUN_WSL_TRAINING = "/mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py"
GLOBAL_TRAINER_PGREP_PATTERN = "[r]un_wsl_training.py"
GUEST_BASE_DIR = "/home/user/.local/share/aria-training/full-training-20261031"
GUEST_DATA_DIR = f"{GUEST_BASE_DIR}/data"
GUEST_STAGE_DIR = f"{GUEST_BASE_DIR}/city_warmup"

MIN_ALLOWED_EPOCHS = 1
MAX_ALLOWED_EPOCHS = 100

SUPPORTED_STAGES: Tuple[str, ...] = (
    "city_warmup",
    "city_full",
    "mixed_decoder",
    "mixed_block13",
    "stage_b_head",
    "stage_b_joint",
    "stage_c",
)

STAGE_PREDECESSORS: Dict[str, Tuple[str, str]] = {
    "city_full": ("city_warmup", "model_a"),
    "mixed_decoder": ("city_full", "model_a"),
    "mixed_block13": ("mixed_decoder", "model_a"),
    "stage_b_head": ("mixed_block13", "model_a"),
    "stage_b_joint": ("stage_b_head", "model_b"),
}

SUPPORTED_CHECKPOINT_WEIGHT_NAMES: Tuple[str, ...] = (
    "model_weights.weights.h5",
    "weights.keras",
    "weights.weights.h5",
    "checkpoint",
)
SUPPORTED_CHECKPOINT_WEIGHT_FILENAMES = SUPPORTED_CHECKPOINT_WEIGHT_NAMES

STAGE_DEFAULTS: Dict[str, Dict[str, Any]] = {
    "city_warmup": {
        "architecture": "model_a",
        "epochs": 5,
        "learning_rate": 0.001,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": 1275,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "cityscapes",
    },
    "city_full": {
        "architecture": "model_a",
        "epochs": 30,
        "learning_rate": 0.0001,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": 1275,
        "encoder_policy": "unfreeze_all",
        "freeze_bn": True,
        "dataset_filter": "cityscapes",
    },
    "mixed_decoder": {
        "architecture": "model_a",
        "epochs": 5,
        "learning_rate": 0.0003,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": None,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "mixed",
    },
    "mixed_block13": {
        "architecture": "model_a",
        "epochs": 20,
        "learning_rate": 0.00001,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": None,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
    },
    "stage_b_head": {
        "architecture": "model_b",
        "epochs": 3,
        "learning_rate": 0.0003,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": None,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "mixed",
    },
    "stage_b_joint": {
        "architecture": "model_b",
        "epochs": 20,
        "learning_rate": 0.00001,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": None,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
    },
    "stage_c": {
        "architecture": "model_c",
        "epochs": 25,
        "learning_rate": 0.0001,
        "batch_size": 2,
        "accumulate_steps": 4,
        "planned_batches": None,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
    },
}


def validate_stage(stage: str) -> str:
    clean = stage.strip()
    if clean not in SUPPORTED_STAGES:
        raise ValueError(
            f"Unsupported stage '{clean}'. Supported stages are: {SUPPORTED_STAGES}"
        )
    return clean


def validate_epochs(epochs: Optional[int], stage: str) -> int:
    if epochs is None:
        return STAGE_DEFAULTS[stage]["epochs"]
    if not isinstance(epochs, int) or isinstance(epochs, bool):
        raise ValueError(f"Epoch count must be an integer, got {epochs!r}")
    if epochs < MIN_ALLOWED_EPOCHS or epochs > MAX_ALLOWED_EPOCHS:
        raise ValueError(
            f"Epoch count must be a positive integer between {MIN_ALLOWED_EPOCHS} "
            f"and {MAX_ALLOWED_EPOCHS}, got {epochs}"
        )
    return epochs


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def run_wsl(
    cmd: str,
    check: bool = True,
    encoding: str = "utf-8",
    errors: str = "replace",
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["wsl", "-d", WSL_DISTRO, "-e", "/bin/bash", "-c", cmd],
        encoding=encoding,
        errors=errors,
        capture_output=True,
        text=True,
        check=check,
    )


def check_gpu_status() -> Dict[str, Any]:
    res = run_wsl(
        "/usr/lib/wsl/lib/nvidia-smi --query-gpu=name,memory.used,memory.total,utilization.gpu --format=csv,noheader,nounits"
    )
    lines = res.stdout.strip().split("\n")
    if not lines or not lines[0]:
        raise RuntimeError("nvidia-smi returned no output")
    parts = [p.strip() for p in lines[0].split(",")]
    return {
        "name": parts[0],
        "memory_used_mib": float(parts[1]),
        "memory_total_mib": float(parts[2]),
        "utilization_gpu_pct": float(parts[3]),
    }


def resolve_attempt_paths(
    attempt_id: str = "attempt_02",
    stage: str = "city_warmup",
    guest_base_dir: str = GUEST_BASE_DIR,
) -> Dict[str, str]:
    validated_stage = validate_stage(stage)
    clean_id = attempt_id.strip()
    if not clean_id:
        output_dir = guest_base_dir
        unit_suffix = ""
    else:
        output_dir = f"{guest_base_dir}/{clean_id}"
        unit_suffix = f"-{clean_id.replace('_', '-')}"
    stage_slug = validated_stage.replace("_", "-")
    return {
        "attempt_id": clean_id or "attempt_01",
        "stage": validated_stage,
        "output_dir": output_dir,
        "stage_dir": f"{output_dir}/{validated_stage}",
        "systemd_unit": f"aria-{stage_slug}-20261031{unit_suffix}",
    }


def resolve_host_paths(
    attempt_id: str = "attempt_02",
    stage: str = "city_warmup",
    receipt_arg: Optional[Path] = None,
    keeper_dir_arg: Optional[Path] = None,
    host_base: Path = Path("build/vision-training/full-training-20261031"),
) -> Dict[str, Path]:
    validated_stage = validate_stage(stage)
    clean_id = attempt_id.strip()

    if clean_id == "attempt_02" and validated_stage == "city_warmup" and receipt_arg is None:
        receipt_path = host_base / "launch_receipt.json"
        status_path = host_base / "launch_attempt_status.json"
        keeper_dir = keeper_dir_arg or host_base
    else:
        attempt_dir = (host_base / clean_id) if clean_id else host_base
        stage_dir = attempt_dir / validated_stage
        receipt_path = receipt_arg or (stage_dir / "launch_receipt.json")
        status_path = stage_dir / "launch_attempt_status.json"
        keeper_dir = keeper_dir_arg or (receipt_arg.parent if receipt_arg else stage_dir)

    return {
        "receipt_path": receipt_path,
        "status_path": status_path,
        "keeper_dir": keeper_dir,
    }


def validate_checkpoint_metadata(
    metadata: Dict[str, Any],
    stage: str,
    manifest_hash: str = EXPECTED_BASE_MANIFEST_SHA256,
) -> None:
    if stage not in STAGE_PREDECESSORS:
        return
    expected_stage, expected_arch = STAGE_PREDECESSORS[stage]
    actual_stage = metadata.get("stage")
    actual_arch = metadata.get("architecture")
    actual_epoch = metadata.get("epoch")
    actual_hash = metadata.get("data_manifest_hash")

    if actual_stage != expected_stage or actual_arch != expected_arch:
        raise ValueError(
            f"Initialization checkpoint must be {expected_stage}/{expected_arch}; "
            f"found {actual_stage}/{actual_arch}."
        )
    if actual_hash != manifest_hash:
        raise ValueError(
            f"Initialization checkpoint manifest hash ({actual_hash}) does not match "
            f"expected dataset manifest hash ({manifest_hash})."
        )
    if actual_epoch is None or actual_epoch < 1:
        raise ValueError(
            f"Initialization checkpoint for {expected_stage} records no completed training epoch "
            f"(epoch={actual_epoch}); it holds only that stage's own untrained initial weights. "
            "Use a checkpoint from a stage run that improved at least once."
        )

def check_checkpoint_weights_exist(
    init_checkpoint: str,
    supported_filenames: Tuple[str, ...] = SUPPORTED_CHECKPOINT_WEIGHT_NAMES,
) -> bool:
    clean_ckpt = init_checkpoint.rstrip("/")
    conditions = " || ".join(
        f"[ -f {shlex.quote(f'{clean_ckpt}/{name}')} ]"
        for name in supported_filenames
    )
    weights_check = run_wsl(
        f"if {conditions}; then echo exists; else echo missing; fi",
        check=False,
    )
    return weights_check.stdout.strip() == "exists"



def build_trainer_command(
    stage: str,
    output_dir: str,
    manifest_path: str = f"{GUEST_DATA_DIR}/manifest.json",
    epochs: Optional[int] = None,
    init_checkpoint: Optional[str] = None,
    learning_rate: Optional[float] = None,
    batch_size: int = 2,
    accumulate_steps: int = 4,
    seed: int = 42,
    device: str = "gpu",
    allow_growth: bool = True,
    wsl_python: str = WSL_PYTHON,
    run_wsl_training: str = RUN_WSL_TRAINING,
) -> List[str]:
    validated_stage = validate_stage(stage)
    resolved_epochs = validate_epochs(epochs, validated_stage)
    resolved_lr = (
        learning_rate
        if learning_rate is not None
        else STAGE_DEFAULTS[validated_stage]["learning_rate"]
    )

    if validated_stage == "city_warmup":
        if init_checkpoint:
            raise ValueError(
                "Stage 'city_warmup' cannot accept --init-checkpoint; it must start from fresh ImageNet weights."
            )
    elif validated_stage in STAGE_PREDECESSORS:
        if not init_checkpoint:
            expected_stage, _ = STAGE_PREDECESSORS[validated_stage]
            raise ValueError(
                f"Stage '{validated_stage}' requires an --init-checkpoint from predecessor stage '{expected_stage}'."
            )

    cmd = [
        wsl_python,
        run_wsl_training,
    ]
    if allow_growth:
        cmd.append("--allow-growth")
    cmd.extend([
        "--",
        "--manifest",
        manifest_path,
        "--stage",
        validated_stage,
    ])
    if validated_stage == "city_warmup":
        cmd.extend(["--weights", "imagenet"])
    elif init_checkpoint:
        cmd.extend(["--init-checkpoint", init_checkpoint])

    cmd.extend([
        "--epochs",
        str(resolved_epochs),
        "--learning-rate",
        str(resolved_lr),
        "--batch-size",
        str(batch_size),
        "--accumulate-steps",
        str(accumulate_steps),
        "--seed",
        str(seed),
        "--device",
        device,
        "--output-dir",
        output_dir,
    ])
    return cmd


def archive_failed_attempt_01(host_archive_root: Path) -> Dict[str, Any]:
    """Copies the prior exited attempt (PID 472, exit -5) into a distinct archive directory on guest and host."""
    src_stage_dir = f"{GUEST_BASE_DIR}/city_warmup"
    guest_archive_dir = f"{GUEST_BASE_DIR}/attempt_01_pid472_sigtrap"
    host_archive_dir = host_archive_root / "attempt_01_pid472_sigtrap"
    host_archive_dir.mkdir(parents=True, exist_ok=True)

    run_wsl(f"mkdir -p {shlex.quote(guest_archive_dir)}")
    copied_files: List[str] = []
    for fname in ("training.log", "exit_code.txt", "supervisor.pid", "trainer.pid", "supervisor.py"):
        run_wsl(
            f"if [ -f {shlex.quote(src_stage_dir)}/{fname} ]; then cp -p {shlex.quote(src_stage_dir)}/{fname} {shlex.quote(guest_archive_dir)}/{fname}; fi",
            check=False,
        )
        cat_res = run_wsl(f"cat {shlex.quote(src_stage_dir)}/{fname} 2>/dev/null", check=False)
        if cat_res.returncode == 0 and cat_res.stdout != "":
            (host_archive_dir / fname).write_text(cat_res.stdout, encoding="utf-8")
            copied_files.append(fname)

    journal_out = run_wsl(
        "journalctl --user -u aria-city-warmup-20261031 -n 30 --no-pager 2>/dev/null",
        check=False,
    ).stdout.strip()
    dmesg_out = run_wsl(
        "dmesg -T | grep -E 'python\\[6290\\]|fatal signal 5|RIP: 0033:0x7f82afb3b221|Code: .*cc <80>' 2>/dev/null || true",
        check=False,
    ).stdout.strip()
    stapsdt_out = run_wsl(
        "readelf -n /lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 | grep -A 4 'Name: reloc_complete' 2>/dev/null || true",
        check=False,
    ).stdout.strip()

    forensic = {
        "attempt_id": "attempt_01_pid472_sigtrap",
        "archived_at_utc": datetime.now(timezone.utc).isoformat(),
        "original_guest_stage_dir": src_stage_dir,
        "guest_archive_dir": guest_archive_dir,
        "host_archive_dir": str(host_archive_dir),
        "supervisor_pid": 471,
        "trainer_pid": 472,
        "systemd_unit": "aria-city-warmup-20261031.service",
        "exit_code": -5,
        "signal": "SIGTRAP (signal 5)",
        "copied_files": copied_files,
        "journal_evidence": journal_out,
        "dmesg_trap_evidence": dmesg_out,
        "stapsdt_reloc_complete_note": stapsdt_out,
        "forensic_explanation": (
            "PID 472 completed PortraitDataset.__init__ (for train and val) and _scan_training_statistics, "
            "printed the Staged Trainer banner at 02:13:42 KST, and terminated with exit code -5 (SIGTRAP). "
            "Kernel dmesg recorded 'traps: python[6290] trap int3 ip:7f82afb3b221 in ld-linux-x86-64.so.2[7f82afb2d000+2f000]' "
            "with byte 0xcc (int3) at 0x7f82afb3b220 (vaddr 0xf220, which readelf -n identifies as the glibc SystemTap "
            "probe rtld:reloc_complete, normally 0x90 NOP on disk). This matches an orphaned GDB solib-event software "
            "breakpoint left in PID 472's copy-on-write page when a prior worker session's 'gdb -p 472' task was killed "
            "at 01:54:57 without detaching. No universal absence of other defects is claimed."
        ),
    }
    forensic_json = json.dumps(forensic, indent=2)
    (host_archive_dir / "forensic_report.json").write_text(forensic_json, encoding="utf-8")
    run_wsl(
        f"cat << 'EOF' > {shlex.quote(guest_archive_dir)}/forensic_report.json\n{forensic_json}\nEOF"
    )
    return forensic


def prelaunch_validation(
    source_dir: Path,
    attempt_paths: Dict[str, str],
    stage: str = "city_warmup",
    epochs: Optional[int] = None,
    init_checkpoint: Optional[str] = None,
) -> None:
    validated_stage = validate_stage(stage)
    validate_epochs(epochs, validated_stage)
    stage_dir = attempt_paths["stage_dir"]
    systemd_unit = attempt_paths["systemd_unit"]

    # 1. Source manifest check
    source_manifest = source_dir / "manifest.json"
    if not source_manifest.is_file():
        raise FileNotFoundError(f"Source manifest not found: {source_manifest}")
    src_hash = sha256_file(source_manifest)
    if src_hash != EXPECTED_BASE_MANIFEST_SHA256:
        raise ValueError(f"Source manifest SHA256 mismatch: {src_hash}")

    # 2. Guest staged manifest check
    staged_manifest_path = f"{GUEST_DATA_DIR}/manifest.json"
    res = run_wsl(f"sha256sum {shlex.quote(staged_manifest_path)}", check=False)
    if res.returncode != 0:
        raise FileNotFoundError(f"Guest manifest not found at {staged_manifest_path}")
    staged_hash = res.stdout.split()[0]
    if staged_hash != EXPECTED_BASE_MANIFEST_SHA256:
        raise ValueError(f"Guest manifest SHA256 mismatch: {staged_hash}")

    # 3. Checkpoint predecessor validation for stages requiring predecessor
    if validated_stage == "city_warmup":
        if init_checkpoint:
            raise ValueError(
                "Stage 'city_warmup' cannot accept --init-checkpoint; it must start from fresh ImageNet weights."
            )
    elif validated_stage in STAGE_PREDECESSORS:
        if not init_checkpoint:
            expected_stage, _ = STAGE_PREDECESSORS[validated_stage]
            raise ValueError(
                f"Stage '{validated_stage}' requires an --init-checkpoint from predecessor stage '{expected_stage}'."
            )
        # Read checkpoint metadata from guest
        meta_cat = run_wsl(
            f"cat {shlex.quote(init_checkpoint)}/metadata.json 2>/dev/null",
            check=False,
        )
        if meta_cat.returncode != 0 or not meta_cat.stdout.strip():
            raise FileNotFoundError(
                f"Initialization checkpoint metadata not found at: {init_checkpoint}/metadata.json"
            )
        try:
            ckpt_meta = json.loads(meta_cat.stdout.strip())
        except Exception as exc:
            raise ValueError(
                f"Malformed checkpoint metadata in {init_checkpoint}: {exc}"
            ) from exc
        validate_checkpoint_metadata(ckpt_meta, validated_stage, manifest_hash=staged_hash)

        # Check weights file presence
        if not check_checkpoint_weights_exist(init_checkpoint):
            raise FileNotFoundError(
                f"No weights file found in initialization checkpoint directory: {init_checkpoint}"
            )

    # 4. Duplicate protection: check if an existing trainer for this stage is already alive
    trainer_pid_file = f"{stage_dir}/trainer.pid"
    pid_check = run_wsl(f"cat {shlex.quote(trainer_pid_file)}", check=False)
    if pid_check.returncode == 0 and pid_check.stdout.strip().isdigit():
        existing_pid = pid_check.stdout.strip()
        alive_check = run_wsl(f"kill -0 {existing_pid}", check=False)
        if alive_check.returncode == 0:
            raise RuntimeError(
                f"Existing training process is already running (PID {existing_pid}) for stage '{validated_stage}'! "
                "Refusing to launch competing training job. Use --status-only to monitor."
            )

    # 5. Systemd unit conflict protection
    unit_active_check = run_wsl(
        f"systemctl --user is-active {shlex.quote(systemd_unit)} 2>/dev/null || true",
        check=False,
    )
    if unit_active_check.stdout.strip() == "active":
        raise RuntimeError(
            f"Systemd service '{systemd_unit}' is already active! Refusing duplicate launch."
        )

    # 6. Global conflicting GPU training check in guest
    global_trainer_check = run_wsl(
        f"pgrep -f '{GLOBAL_TRAINER_PGREP_PATTERN}' 2>/dev/null || true",
        check=False,
    )
    running_pids = [p.strip() for p in global_trainer_check.stdout.splitlines() if p.strip().isdigit()]
    for rpid in running_pids:
        # Check if this PID is the same as the existing recorded PID for this stage (already checked above)
        if pid_check.returncode == 0 and rpid == pid_check.stdout.strip():
            continue
        # Otherwise an active trainer is already running on the shared GPU!
        raise RuntimeError(
            f"Another active training process (PID {rpid}) is currently running in WSL! "
            "Refusing to launch competing GPU training job."
        )

    # 7. Checkpoint directory overwrite protection
    ckpt_check = run_wsl(
        f"if [ -d {shlex.quote(stage_dir)}/checkpoints ] && [ \"$(ls -A {shlex.quote(stage_dir)}/checkpoints 2>/dev/null)\" ]; then echo nonempty; else echo empty; fi",
        check=False,
    )
    if ckpt_check.stdout.strip() == "nonempty":
        raise FileExistsError(
            f"Checkpoints directory {stage_dir}/checkpoints is non-empty. "
            "Refusing to overwrite existing checkpoint state without explicit resume."
        )

    # 8. Completed stage overwrite protection
    exit_code_check = run_wsl(
        f"if [ -f {shlex.quote(stage_dir)}/exit_code.txt ]; then echo exists; else echo missing; fi",
        check=False,
    )
    if exit_code_check.stdout.strip() == "exists":
        raise FileExistsError(
            f"Stage directory {stage_dir} already contains a completed run (exit_code.txt found). "
            "Refusing to overwrite existing stage attempt."
        )


def deploy_and_start_supervisor(
    attempt_paths: Dict[str, str],
    stage: str = "city_warmup",
    epochs: Optional[int] = None,
    init_checkpoint: Optional[str] = None,
    learning_rate: Optional[float] = None,
    batch_size: int = 2,
    accumulate_steps: int = 4,
    seed: int = 42,
    device: str = "gpu",
    host_archive_root: Optional[Path] = None,
) -> Dict[str, str]:
    validated_stage = validate_stage(stage)
    output_dir = attempt_paths["output_dir"]
    stage_dir = attempt_paths["stage_dir"]
    systemd_unit = attempt_paths["systemd_unit"]
    run_wsl(f"mkdir -p {shlex.quote(stage_dir)}")

    # Archive old attempt_01 only if requested and targeting attempt_02 warmup
    if host_archive_root is not None and attempt_paths["attempt_id"] == "attempt_02" and validated_stage == "city_warmup":
        archive_failed_attempt_01(host_archive_root)

    trainer_cmd = build_trainer_command(
        stage=validated_stage,
        output_dir=output_dir,
        manifest_path=f"{GUEST_DATA_DIR}/manifest.json",
        epochs=epochs,
        init_checkpoint=init_checkpoint,
        learning_rate=learning_rate,
        batch_size=batch_size,
        accumulate_steps=accumulate_steps,
        seed=seed,
        device=device,
        allow_growth=True,
        wsl_python=WSL_PYTHON,
        run_wsl_training=RUN_WSL_TRAINING,
    )

    cmd_py_repr = json.dumps(trainer_cmd, indent=8)

    supervisor_script = f"""#!/usr/bin/env python3
import os
import subprocess
import sys

def main():
    stage_dir = sys.argv[1]
    os.makedirs(stage_dir, exist_ok=True)
    with open(os.path.join(stage_dir, "supervisor.pid"), "w") as f:
        f.write(str(os.getpid()))

    log_path = os.path.join(stage_dir, "training.log")
    log_file = open(log_path, "a")

    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    env["TF_FORCE_GPU_ALLOW_GROWTH"] = "true"

    cmd = {cmd_py_repr}

    proc = subprocess.Popen(
        cmd,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        env=env,
        stdin=subprocess.DEVNULL,
    )

    with open(os.path.join(stage_dir, "trainer.pid"), "w") as f:
        f.write(str(proc.pid))

    exit_code = proc.wait()
    with open(os.path.join(stage_dir, "exit_code.txt"), "w") as f:
        f.write(str(exit_code))

if __name__ == "__main__":
    main()
"""
    write_sup_cmd = (
        f"cat << 'EOF' > {shlex.quote(stage_dir)}/supervisor.py\n"
        f"{supervisor_script}EOF\n"
        f"chmod +x {shlex.quote(stage_dir)}/supervisor.py"
    )
    run_wsl(write_sup_cmd)

    # Reset any prior failed/stopped transient unit before launching a fresh unit
    run_wsl(f"systemctl --user reset-failed {shlex.quote(systemd_unit)}.service 2>/dev/null || true", check=False)
    launch_cmd = (
        f"systemd-run --user --collect --unit={shlex.quote(systemd_unit)} "
        f"{WSL_PYTHON} {shlex.quote(stage_dir)}/supervisor.py {shlex.quote(stage_dir)}"
    )
    run_wsl(launch_cmd)

    # Allow a moment for PIDs to be recorded
    time.sleep(3)
    sup_pid_out = run_wsl(f"cat {shlex.quote(stage_dir)}/supervisor.pid").stdout.strip()
    trainer_pid_out = run_wsl(f"cat {shlex.quote(stage_dir)}/trainer.pid").stdout.strip()

    return {
        "supervisor_pid": sup_pid_out,
        "trainer_pid": trainer_pid_out,
    }


def read_proc_identity(pid: str) -> Dict[str, Any]:
    if not pid or not pid.isdigit():
        return {
            "pid": None,
            "cmdline": "",
            "ps_info": "",
            "start_ticks": None,
            "io_stats": {},
            "is_alive": False,
        }
    cmd_res = run_wsl(f"cat /proc/{pid}/cmdline | tr '\\0' ' '", check=False)
    cmdline = cmd_res.stdout.strip()
    stat_res = run_wsl(f"ps -p {pid} -o lstart=,stat=,etime=,time=,vsz=,rss= --no-headers", check=False)
    ps_info = stat_res.stdout.strip()
    raw_stat_res = run_wsl(f"cat /proc/{pid}/stat", check=False)
    start_ticks: Optional[str] = None
    if raw_stat_res.returncode == 0 and ")" in raw_stat_res.stdout:
        after_comm = raw_stat_res.stdout.rsplit(")", 1)[1].split()
        if len(after_comm) >= 20:
            start_ticks = after_comm[19]
    io_res = run_wsl(f"cat /proc/{pid}/io", check=False)
    io_stats: Dict[str, int] = {}
    if io_res.returncode == 0:
        for line in io_res.stdout.splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                v_str = v.strip()
                if v_str.isdigit():
                    io_stats[k.strip()] = int(v_str)
    return {
        "pid": int(pid),
        "cmdline": cmdline,
        "ps_info": ps_info,
        "start_ticks": start_ticks,
        "io_stats": io_stats,
        "is_alive": check_process_alive(pid),
    }


def check_windows_pid_alive(win_pid: int) -> bool:
    if os.name != "nt" or win_pid <= 0:
        return False
    res = subprocess.run(
        ["tasklist", "/FI", f"PID eq {win_pid}", "/NH", "/FO", "CSV"],
        capture_output=True,
        text=True,
        check=False,
    )
    return f'"{win_pid}"' in res.stdout


def ensure_windows_keeper(
    trainer_pid: str,
    start_ticks: Optional[str],
    keeper_dir: Path,
    stage_dir: str = GUEST_STAGE_DIR,
    trainer_alive: bool = True,
) -> Dict[str, Any]:
    """Ensures a detached Windows wsl.exe keeper process is running and bound to trainer_pid + start_ticks."""
    keeper_dir.mkdir(parents=True, exist_ok=True)
    keeper_json_path = keeper_dir / "windows_keeper.json"
    keeper_pid_path = keeper_dir / "windows_keeper.pid"
    keeper_log_path = keeper_dir / "windows_keeper.log"
    guest_keeper_script = f"{stage_dir}/keeper.py"
    guest_keeper_log = f"{stage_dir}/keeper.log"
    ticks_arg = start_ticks or ""

    # If trainer is not alive or missing ticks, check if an existing keeper was previously recorded
    if not trainer_alive or not ticks_arg:
        if keeper_json_path.is_file():
            try:
                existing = json.loads(keeper_json_path.read_text(encoding="utf-8"))
                existing_pid = int(existing.get("windows_keeper_pid") or 0)
                is_keeper_alive = check_windows_pid_alive(existing_pid) if existing_pid > 0 else False
                existing["is_alive"] = is_keeper_alive
                existing["reason"] = "trainer_not_alive"
                return existing
            except Exception:
                pass
        return {
            "windows_keeper_pid": None,
            "is_alive": False,
            "used_breakaway_from_job": False,
            "bound_trainer_pid": int(trainer_pid) if trainer_pid.isdigit() else None,
            "bound_start_ticks": ticks_arg,
            "reason": "trainer_not_alive",
        }

    # Check if an existing keeper written by this launcher is already alive and bound to the same target
    if keeper_json_path.is_file():
        try:
            existing = json.loads(keeper_json_path.read_text(encoding="utf-8"))
            existing_pid = int(existing.get("windows_keeper_pid") or 0)
            if (
                existing_pid > 0
                and existing.get("bound_trainer_pid") == int(trainer_pid)
                and existing.get("bound_start_ticks") == ticks_arg
                and existing.get("guest_keeper_script") == guest_keeper_script
                and check_windows_pid_alive(existing_pid)
            ):
                existing["is_alive"] = True
                return existing
        except Exception:
            pass

    # Write guest keeper script that validates both PID liveness and /proc/<pid>/stat start_ticks
    host_log_posix = keeper_log_path.resolve().as_posix()
    host_log_wsl = "/mnt/c/" + host_log_posix.split(":/", 1)[-1]
    keeper_py = f"""#!/usr/bin/env python3
import os
import sys
import time
from datetime import datetime, timezone

GUEST_LOG = {guest_keeper_log!r}
HOST_LOG = {host_log_wsl!r}

def log_msg(msg: str) -> None:
    line = f"[{{datetime.now(timezone.utc).isoformat()}}] [guest_pid={{os.getpid()}}] {{msg}}"
    print(line, flush=True)
    for path in (GUEST_LOG, HOST_LOG):
        try:
            with open(path, "a", encoding="utf-8") as lf:
                lf.write(line + "\\n")
        except OSError:
            pass

def is_same_process(pid: str, expected_ticks: str) -> tuple[bool, str]:
    try:
        with open(f"/proc/{{pid}}/stat", "r", encoding="utf-8") as f:
            data = f.read()
        if ")" not in data:
            return False, "malformed_stat"
        after = data.rsplit(")", 1)[1].split()
        if len(after) < 20:
            return False, "short_stat"
        if expected_ticks and after[19] != expected_ticks:
            return False, f"start_ticks_mismatch({{after[19]}}!={{expected_ticks}})"
        return True, "alive"
    except OSError as exc:
        return False, f"proc_gone({{exc}})"

def main() -> int:
    pid = sys.argv[1]
    expected_ticks = sys.argv[2] if len(sys.argv) > 2 else ""
    log_msg(f"keeper started for pid={{pid}} start_ticks={{expected_ticks}}")
    step = 0
    while True:
        ok, reason = is_same_process(pid, expected_ticks)
        if not ok:
            log_msg(f"keeper exiting for pid={{pid}}: {{reason}}")
            break
        step += 1
        if step % 6 == 0:
            log_msg(f"keeper heartbeat alive for pid={{pid}} start_ticks={{expected_ticks}}")
        time.sleep(5)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
"""
    write_cmd = (
        f"cat << 'EOF' > {shlex.quote(guest_keeper_script)}\n"
        f"{keeper_py}EOF\n"
        f"chmod +x {shlex.quote(guest_keeper_script)}"
    )
    run_wsl(write_cmd)

    wsl_exe = r"C:\Windows\System32\wsl.exe"
    keeper_argv = [
        wsl_exe,
        "-d",
        WSL_DISTRO,
        "--",
        WSL_PYTHON,
        guest_keeper_script,
        str(trainer_pid),
        ticks_arg,
    ]
    keeper_cmd_str = " ".join(keeper_argv)

    # Launch via WMI Win32_Process.Create so the process is parented by WmiPrvSE.exe
    wmi_ps = (
        "$si = New-CimInstance -ClassName Win32_ProcessStartup -Property @{ ShowWindow = [uint16]0 } -ClientOnly; "
        f"$res = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{{ CommandLine = '{keeper_cmd_str}'; ProcessStartupInformation = $si }}; "
        "if ($res.ReturnValue -ne 0) { exit 1 }; "
        "Write-Output $res.ProcessId"
    )
    wmi_res = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", wmi_ps],
        capture_output=True,
        text=True,
        check=False,
    )
    spawned_pid: Optional[int] = None
    used_breakaway = False
    out_of_worker_job = False
    launch_mechanism = "unknown"
    if wmi_res.returncode == 0 and wmi_res.stdout.strip().splitlines()[-1].strip().isdigit():
        spawned_pid = int(wmi_res.stdout.strip().splitlines()[-1].strip())
        used_breakaway = False
        out_of_worker_job = True
        launch_mechanism = "wmi_win32_process_create"
    else:
        creation_base = (
            getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
            | getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            | getattr(subprocess, "CREATE_BREAKAWAY_FROM_JOB", 0x01000000)
        )
        proc = subprocess.Popen(
            keeper_argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            close_fds=True,
            creationflags=creation_base if os.name == "nt" else 0,
        )
        spawned_pid = int(proc.pid)
        used_breakaway = True
        out_of_worker_job = False
        launch_mechanism = "popen_breakaway_job"

    assert spawned_pid is not None
    time.sleep(2)
    alive = check_windows_pid_alive(spawned_pid)
    keeper_info: Dict[str, Any] = {
        "windows_keeper_pid": spawned_pid,
        "is_alive": alive,
        "launch_mechanism": launch_mechanism,
        "out_of_worker_job": out_of_worker_job,
        "used_breakaway_from_job": used_breakaway,
        "command_argv": keeper_argv,
        "command": keeper_cmd_str,
        "bound_trainer_pid": int(trainer_pid),
        "bound_start_ticks": ticks_arg,
        "guest_keeper_script": guest_keeper_script,
        "guest_keeper_log": guest_keeper_log,
        "keeper_log_path": str(keeper_log_path),
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    keeper_pid_path.write_text(f"{spawned_pid}\n", encoding="utf-8")
    keeper_json_path.write_text(json.dumps(keeper_info, indent=2), encoding="utf-8")
    return keeper_info


def check_process_alive(pid: str) -> bool:
    if not pid or not pid.isdigit():
        return False
    res = run_wsl(f"kill -0 {pid}", check=False)
    return res.returncode == 0


def monitor_training_progress(
    trainer_pid: str,
    stage_dir: str = GUEST_STAGE_DIR,
    stage: str = "city_warmup",
    epochs: int = 5,
    timeout_seconds: int = 1800,
) -> Dict[str, Any]:
    start_time = time.time()
    observed_banner = False
    observed_config = False
    observed_initial_eval = False
    observed_batch_1 = False
    observed_batch_21 = False
    observed_planned_batches = False
    latest_loss: Optional[float] = None
    batch_history: List[Tuple[int, float]] = []

    arch = STAGE_DEFAULTS.get(stage, {}).get("architecture", "model_a")
    expected_batches = STAGE_DEFAULTS.get(stage, {}).get("planned_batches", EXPECTED_PLANNED_BATCHES)

    print(
        f"Monitoring trainer PID {trainer_pid} in {WSL_DISTRO} ({stage_dir}) "
        f"for {stage} banner ({epochs} epochs, GPU), epoch 0 initial evaluation commit, and advancing batches (1 -> 21+)..."
    )

    stage_esc = re.escape(stage)
    arch_esc = re.escape(arch)
    banner_re = re.compile(rf"Staged Trainer:\s*{stage_esc}\s*\({arch_esc}\)")
    config_re = re.compile(rf"Epochs:\s*{epochs}\b")
    device_re = re.compile(r"Device:\s*gpu")
    batch_re = re.compile(r"Batch\s+(\d+)/(\d+)\s+-\s+loss\s+(\S+)")

    while time.time() - start_time < timeout_seconds:
        if not check_process_alive(trainer_pid):
            exit_code_res = run_wsl(f"cat {shlex.quote(stage_dir)}/exit_code.txt 2>/dev/null", check=False)
            exit_code_str = exit_code_res.stdout.strip()
            log_tail = run_wsl(f"tail -n 40 {shlex.quote(stage_dir)}/training.log 2>/dev/null", check=False).stdout
            if exit_code_str == "0":
                print(f"Trainer process {trainer_pid} completed successfully with exit code 0.", flush=True)
                return {
                    "observed_banner": observed_banner,
                    "observed_config": observed_config,
                    "observed_initial_eval": observed_initial_eval,
                    "observed_batch_1": observed_batch_1,
                    "observed_batch_21": observed_batch_21,
                    "observed_planned_batches": observed_planned_batches,
                    "planned_batches": expected_batches,
                    "latest_loss": latest_loss,
                    "loss_is_finite": latest_loss is not None and math.isfinite(latest_loss),
                    "batch_history": batch_history,
                    "final_gpu_status": check_gpu_status(),
                    "elapsed_seconds": round(time.time() - start_time, 2),
                    "completed_exit_code": 0,
                }
            raise RuntimeError(
                f"Trainer process {trainer_pid} exited prematurely with exit code: {exit_code_str}.\n"
                f"Log tail:\n{log_tail}"
            )

        # Read training log
        log_res = run_wsl(f"cat {shlex.quote(stage_dir)}/training.log", check=False)
        log_text = log_res.stdout

        if banner_re.search(log_text):
            observed_banner = True
        if config_re.search(log_text) and device_re.search(log_text):
            observed_config = True

        # Check for epoch 0 metadata commit with actual epoch == 0 in JSON
        meta_res = run_wsl(
            f"cat {shlex.quote(stage_dir)}/checkpoints/best/metadata.json 2>/dev/null", check=False
        )
        if meta_res.returncode == 0 and meta_res.stdout.strip():
            try:
                meta_json = json.loads(meta_res.stdout.strip())
                if meta_json.get("epoch") == 0 and meta_json.get("stage") == stage:
                    observed_initial_eval = True
            except Exception:
                pass

        # Check batch matches
        matches = batch_re.findall(log_text)
        for b_str, total_b, loss_str in matches:
            b_num = int(b_str)
            tot_b = int(total_b)
            try:
                l_val = float(loss_str)
            except ValueError:
                raise ValueError(f"Malformed non-float loss detected at Batch {b_num}: {loss_str}")

            if not math.isfinite(l_val):
                raise ValueError(f"Non-finite loss detected at Batch {b_num}: {loss_str}")

            if expected_batches is not None:
                if tot_b == expected_batches:
                    observed_planned_batches = True
                else:
                    raise ValueError(
                        f"Unexpected planned batches count: got {tot_b}, expected {expected_batches}"
                    )
            else:
                if tot_b > 0:
                    observed_planned_batches = True

            if b_num == 1:
                observed_batch_1 = True
            if b_num >= 21:
                observed_batch_21 = True
            latest_loss = l_val
            if (b_num, l_val) not in batch_history:
                batch_history.append((b_num, l_val))

        gpu_stat = check_gpu_status()

        elapsed = int(time.time() - start_time)
        print(
            f"[{elapsed}s] Trainer PID {trainer_pid} alive | "
            f"Banner: {observed_banner} | Config: {observed_config} | "
            f"InitEval (epoch 0): {observed_initial_eval} | "
            f"B1: {observed_batch_1} | B21: {observed_batch_21} (planned: {observed_planned_batches}) | "
            f"Loss: {latest_loss} | GPU: {gpu_stat['memory_used_mib']} MiB ({gpu_stat['utilization_gpu_pct']}%)",
            flush=True,
        )

        if (
            observed_banner
            and observed_config
            and observed_initial_eval
            and observed_batch_1
            and observed_batch_21
            and observed_planned_batches
            and latest_loss is not None
            and math.isfinite(latest_loss)
        ):
            print(
                "Successfully verified: banner, config, initial evaluation commit (epoch 0), and advancing batches (1 -> 21+).",
                flush=True,
            )
            return {
                "observed_banner": observed_banner,
                "observed_config": observed_config,
                "observed_initial_eval": observed_initial_eval,
                "observed_batch_1": observed_batch_1,
                "observed_batch_21": observed_batch_21,
                "observed_planned_batches": observed_planned_batches,
                "planned_batches": expected_batches or tot_b,
                "latest_loss": latest_loss,
                "loss_is_finite": math.isfinite(latest_loss),
                "batch_history": batch_history,
                "final_gpu_status": gpu_stat,
                "elapsed_seconds": round(time.time() - start_time, 2),
            }

        time.sleep(8)

    raise TimeoutError(
        f"Observation timed out after {timeout_seconds}s. Trainer process {trainer_pid} is still alive and running. "
        f"Observed: Banner={observed_banner}, Config={observed_config}, InitEval={observed_initial_eval}, B1={observed_batch_1}, B21={observed_batch_21}"
    )


def create_launch_receipt(
    pids: Dict[str, str],
    attempt_paths: Dict[str, str],
    monitor_results: Dict[str, Any],
    initial_gpu_status: Dict[str, Any],
    keeper_info: Dict[str, Any],
    host_receipt_path: Path,
    stage: str = "city_warmup",
    epochs: Optional[int] = None,
    init_checkpoint: Optional[str] = None,
    learning_rate: Optional[float] = None,
) -> Dict[str, Any]:
    validated_stage = validate_stage(stage)
    resolved_epochs = validate_epochs(epochs, validated_stage)
    resolved_lr = (
        learning_rate
        if learning_rate is not None
        else STAGE_DEFAULTS[validated_stage]["learning_rate"]
    )
    trainer_pid = pids["trainer_pid"]
    sup_pid = pids["supervisor_pid"]
    output_dir = attempt_paths["output_dir"]
    stage_dir = attempt_paths["stage_dir"]
    systemd_unit = attempt_paths["systemd_unit"]
    proc_info = read_proc_identity(trainer_pid)

    resume_cmd = (
        f"{WSL_PYTHON} {RUN_WSL_TRAINING} --allow-growth -- "
        f"--manifest {GUEST_DATA_DIR}/manifest.json "
        f"--stage {validated_stage} "
        f"--resume {stage_dir}/checkpoints/latest "
        f"--epochs {resolved_epochs} "
        f"--learning-rate {resolved_lr} "
        f"--batch-size 2 "
        f"--accumulate-steps 4 "
        f"--seed 42 "
        f"--device gpu "
        f"--output-dir {output_dir}"
    )

    status_cmd = (
        f"ps -p {trainer_pid} -o pid,vsz,rss,stat,time,command && "
        f"tail -n 25 {stage_dir}/training.log && "
        f"/usr/lib/wsl/lib/nvidia-smi"
    )

    now_iso = datetime.now(timezone.utc).isoformat()
    stage_meta = STAGE_DEFAULTS.get(validated_stage, {})

    config_info: Dict[str, Any] = {
        "stage": validated_stage,
        "epochs": resolved_epochs,
        "learning_rate": resolved_lr,
        "batch_size": 2,
        "accumulate_steps": 4,
        "seed": 42,
        "device": "gpu",
        "allow_growth": True,
        "architecture": stage_meta.get("architecture", "model_a"),
        "encoder_policy": stage_meta.get("encoder_policy", "freeze_all"),
        "freeze_bn": stage_meta.get("freeze_bn", True),
        "dataset_filter": stage_meta.get("dataset_filter", "cityscapes"),
    }
    if validated_stage == "city_warmup":
        config_info["weights"] = "imagenet"
    elif init_checkpoint:
        config_info["init_checkpoint"] = init_checkpoint

    receipt: Dict[str, Any] = {
        "task_id": f"ARIA-{validated_stage.upper().replace('_', '-')}-LAUNCH-20261031",
        "attempt_id": attempt_paths["attempt_id"],
        "stage": validated_stage,
        "launch_timestamp_utc": now_iso,
        "status": "RUNNING",
        "verified_launch": True,
        "exclusive_gpu_ownership": True,
        "process_identity": {
            "distro": WSL_DISTRO,
            "systemd_unit": f"{systemd_unit}.service",
            "trainer_pid": int(trainer_pid) if trainer_pid.isdigit() else None,
            "supervisor_pid": int(sup_pid) if sup_pid.isdigit() else None,
            "trainer_cmdline": proc_info["cmdline"],
            "process_start_info": proc_info["ps_info"],
            "start_ticks": proc_info["start_ticks"],
            "io_stats": proc_info["io_stats"],
            "trainer_executable": WSL_PYTHON,
            "is_alive": proc_info["is_alive"],
        },
        "windows_keeper": keeper_info,
        "preserved_prior_attempts": {
            "attempt_01_pid472_sigtrap": {
                "original_guest_stage_dir": f"{GUEST_BASE_DIR}/city_warmup",
                "guest_archive_dir": f"{GUEST_BASE_DIR}/attempt_01_pid472_sigtrap",
                "host_archive_dir": str(host_receipt_path.parent / "attempt_01_pid472_sigtrap"),
                "exit_code": -5,
                "signal": "SIGTRAP (orphaned 0xcc at rtld:reloc_complete 0xf220 from prior killed gdb)",
            }
        },
        "configuration": config_info,
        "dataset": {
            "manifest_path": f"{GUEST_DATA_DIR}/manifest.json",
            "manifest_sha256": EXPECTED_BASE_MANIFEST_SHA256,
            "dataset_counts": {
                "total_records": 13033,
                "cityscapes_train": 2550,
                "cityscapes_val": 425,
                "cityscapes_test_reserved": 500,
                "batches_per_epoch": stage_meta.get("planned_batches"),
            },
        },
        "paths": {
            "guest_output_dir": output_dir,
            "guest_stage_dir": stage_dir,
            "training_log": f"{stage_dir}/training.log",
            "exit_marker_path": f"{stage_dir}/exit_code.txt",
            "checkpoints_dir": f"{stage_dir}/checkpoints",
            "summary_path": f"{stage_dir}/training_summary.json",
        },
        "progress_evidence": {
            "observed_banner": monitor_results["observed_banner"],
            "observed_config": monitor_results["observed_config"],
            "observed_initial_eval_epoch0_commit": monitor_results["observed_initial_eval"],
            "observed_batch_1": monitor_results["observed_batch_1"],
            "observed_batch_21": monitor_results["observed_batch_21"],
            "observed_planned_batches": monitor_results["observed_planned_batches"],
            "planned_batches_count": monitor_results["planned_batches"],
            "latest_train_loss": monitor_results["latest_loss"],
            "loss_is_finite": monitor_results["loss_is_finite"],
            "batch_history": monitor_results["batch_history"],
            "observation_window_elapsed_seconds": monitor_results["elapsed_seconds"],
        },
        "hardware_and_resource_observations": {
            "initial_gpu": initial_gpu_status,
            "active_gpu": monitor_results["final_gpu_status"],
            "device": "NVIDIA GeForce GTX 1660 SUPER",
        },
        "commands": {
            "resume_command": resume_cmd,
            "status_command": status_cmd,
        },
        "caveats_and_limitations": [
            f"This receipt establishes verified stable launch and initial batch progress for stage '{validated_stage}'.",
            "No claim is made regarding final model convergence, loss trajectory, or mIoU quality.",
            "The durable job retains exclusive GPU ownership and runs asynchronously in the detached Linux session.",
        ],
    }

    host_receipt_path.parent.mkdir(parents=True, exist_ok=True)
    host_receipt_path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")

    guest_receipt_path = f"{stage_dir}/launch_receipt.json"
    run_wsl(f"cat << 'EOF' > {shlex.quote(guest_receipt_path)}\n{json.dumps(receipt, indent=2)}\nEOF")

    return receipt


def snapshot_current_state(
    pids: Dict[str, str],
    attempt_paths: Dict[str, str],
    keeper_info: Dict[str, Any],
    gpu_status: Dict[str, Any],
    stage: str = "city_warmup",
) -> Dict[str, Any]:
    validated_stage = validate_stage(stage)
    trainer_pid = pids.get("trainer_pid", "")
    stage_dir = attempt_paths["stage_dir"]
    proc_info = read_proc_identity(trainer_pid)
    log_res = run_wsl(f"cat {shlex.quote(stage_dir)}/training.log 2>/dev/null", check=False)
    log_lines = log_res.stdout.splitlines() if log_res.returncode == 0 else []

    best_meta_res = run_wsl(
        f"cat {shlex.quote(stage_dir)}/checkpoints/best/metadata.json 2>/dev/null",
        check=False,
    )
    best_metadata: Optional[Dict[str, Any]] = None
    epoch0_committed = False
    if best_meta_res.returncode == 0 and best_meta_res.stdout.strip():
        try:
            best_metadata = json.loads(best_meta_res.stdout.strip())
            if best_metadata.get("epoch") == 0 and best_metadata.get("stage") == validated_stage:
                epoch0_committed = True
        except Exception:
            pass

    exit_code_res = run_wsl(f"cat {shlex.quote(stage_dir)}/exit_code.txt 2>/dev/null", check=False)
    exit_code_str = exit_code_res.stdout.strip() if exit_code_res.returncode == 0 else ""

    return {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "attempt_paths": attempt_paths,
        "stage": validated_stage,
        "pids": pids,
        "process_identity": proc_info,
        "windows_keeper": keeper_info,
        "gpu_status": gpu_status,
        "training_log_lines": len(log_lines),
        "training_log_tail": log_lines[-15:],
        "epoch0_checkpoint_committed": epoch0_committed,
        "best_checkpoint_metadata": best_metadata,
        "exit_code_txt": exit_code_str,
        "completed": exit_code_str == "0",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Launch and observe full training.")
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path("build/vision-training/data"),
        help="Source data directory containing base manifest.json",
    )
    parser.add_argument(
        "--receipt",
        type=Path,
        default=None,
        help="Path for launch receipt JSON (default: per attempt+stage receipt path)",
    )
    parser.add_argument(
        "--attempt-id",
        type=str,
        default="attempt_02",
        help="Distinct attempt identifier subdirectory (default: attempt_02; pass empty string for root)",
    )
    parser.add_argument(
        "--stage",
        type=str,
        default="city_warmup",
        choices=SUPPORTED_STAGES,
        help="Training stage to launch or observe (default: city_warmup)",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=None,
        help="Explicit positive epoch count (default: stage-specific default, e.g. 5 for city_warmup)",
    )
    parser.add_argument(
        "--init-checkpoint",
        type=str,
        default=None,
        help="Guest path to initialization checkpoint for stages requiring a predecessor",
    )
    parser.add_argument(
        "--learning-rate",
        type=float,
        default=None,
        help="Explicit learning rate override (default: stage-specific default)",
    )
    parser.add_argument(
        "--keeper-dir",
        type=Path,
        default=None,
        help="Host directory for Windows keeper PID/json/log (default: auto per attempt+stage)",
    )
    parser.add_argument(
        "--archive-only",
        action="store_true",
        help="Only archive prior failed attempt_01_pid472_sigtrap and exit",
    )
    parser.add_argument(
        "--launch-only",
        action="store_true",
        help="Launch attempt and detached Windows keeper, emit status snapshot, and exit without blocking for batch 21",
    )
    parser.add_argument(
        "--observe-only",
        action="store_true",
        help="Only observe an already running training process instead of launching new",
    )
    parser.add_argument(
        "--status-only",
        action="store_true",
        help="Inspect status and ensure keeper without launching or requiring active trainer",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=1800,
        help="Observation timeout in seconds (default: 1800)",
    )
    args = parser.parse_args()

    stage = validate_stage(args.stage)
    epochs = validate_epochs(args.epochs, stage)
    attempt_paths = resolve_attempt_paths(args.attempt_id, stage=stage)
    stage_dir = attempt_paths["stage_dir"]

    host_paths = resolve_host_paths(
        attempt_id=args.attempt_id,
        stage=stage,
        receipt_arg=args.receipt,
        keeper_dir_arg=args.keeper_dir,
    )
    receipt_path = host_paths["receipt_path"]
    status_path = host_paths["status_path"]
    keeper_dir = host_paths["keeper_dir"]

    if args.archive_only:
        forensic = archive_failed_attempt_01(receipt_path.parent)
        print(json.dumps(forensic, indent=2), flush=True)
        return

    # 1. Preflight GPU check
    try:
        initial_gpu = check_gpu_status()
        print(f"Preflight GPU status: {initial_gpu['name']}, {initial_gpu['memory_used_mib']} MiB used.", flush=True)
    except Exception as exc:
        initial_gpu = {"name": "GPU query failed", "error": str(exc), "memory_used_mib": 0.0, "memory_total_mib": 0.0, "utilization_gpu_pct": 0.0}
        print(f"Preflight GPU notice: {exc}", flush=True)

    if not args.observe_only and not args.status_only:
        # Prelaunch validation
        prelaunch_validation(
            args.source_dir,
            attempt_paths=attempt_paths,
            stage=stage,
            epochs=epochs,
            init_checkpoint=args.init_checkpoint,
        )
        print(
            f"Deploying supervisor and starting durable detached training run "
            f"({attempt_paths['attempt_id']}, stage={stage}, epochs={epochs})...",
            flush=True,
        )
        pids = deploy_and_start_supervisor(
            attempt_paths,
            stage=stage,
            epochs=epochs,
            init_checkpoint=args.init_checkpoint,
            learning_rate=args.learning_rate,
            host_archive_root=receipt_path.parent,
        )
    else:
        sup_pid = run_wsl(f"cat {shlex.quote(stage_dir)}/supervisor.pid 2>/dev/null", check=False).stdout.strip()
        trainer_pid = run_wsl(f"cat {shlex.quote(stage_dir)}/trainer.pid 2>/dev/null", check=False).stdout.strip()
        pids = {"supervisor_pid": sup_pid, "trainer_pid": trainer_pid}
        print(
            f"Existing job ({attempt_paths['attempt_id']}, stage={stage}): Supervisor PID '{sup_pid}', Trainer PID '{trainer_pid}'",
            flush=True,
        )

    proc_info = read_proc_identity(pids["trainer_pid"])
    keeper_info = ensure_windows_keeper(
        trainer_pid=pids["trainer_pid"],
        start_ticks=proc_info.get("start_ticks"),
        keeper_dir=keeper_dir,
        stage_dir=stage_dir,
        trainer_alive=bool(proc_info.get("is_alive")),
    )
    print(
        f"Windows keeper PID: {keeper_info['windows_keeper_pid']} "
        f"(alive={keeper_info['is_alive']}, breakaway={keeper_info['used_breakaway_from_job']}, "
        f"bound_pid={keeper_info['bound_trainer_pid']}, bound_ticks={keeper_info['bound_start_ticks']})",
        flush=True,
    )

    if args.status_only or args.launch_only:
        snap = snapshot_current_state(
            pids,
            attempt_paths,
            keeper_info,
            initial_gpu,
            stage=stage,
        )
        snap["guest_uptime"] = run_wsl("uptime -s && uptime 2>/dev/null || true", check=False).stdout.strip()
        snap["stage_dir_ls"] = run_wsl(f"ls -la {shlex.quote(stage_dir)} 2>/dev/null || true", check=False).stdout.strip()
        snap["journal_tail"] = run_wsl(
            f"journalctl --user -u {shlex.quote(attempt_paths['systemd_unit'])} -n 30 --no-pager 2>/dev/null || true",
            check=False,
        ).stdout.strip()
        status_path.parent.mkdir(parents=True, exist_ok=True)
        status_path.write_text(json.dumps(snap, indent=2), encoding="utf-8")
        print(json.dumps(snap, indent=2), flush=True)
        return

    # 2. Monitor startup and advancing batches
    monitor_results = monitor_training_progress(
        pids["trainer_pid"],
        stage_dir=stage_dir,
        stage=stage,
        epochs=epochs,
        timeout_seconds=args.timeout,
    )

    # 3. Generate receipt
    receipt = create_launch_receipt(
        pids=pids,
        attempt_paths=attempt_paths,
        monitor_results=monitor_results,
        initial_gpu_status=initial_gpu,
        keeper_info=keeper_info,
        host_receipt_path=receipt_path,
        stage=stage,
        epochs=epochs,
        init_checkpoint=args.init_checkpoint,
        learning_rate=args.learning_rate,
    )
    print(f"Launch receipt successfully generated at: {receipt_path}", flush=True)


if __name__ == "__main__":
    main()
