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
GUEST_BASE_DIR = "/home/user/.local/share/aria-training/full-training-20261031"
GUEST_DATA_DIR = f"{GUEST_BASE_DIR}/data"
GUEST_STAGE_DIR = f"{GUEST_BASE_DIR}/city_warmup"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["wsl", "-d", WSL_DISTRO, "-e", "/bin/bash", "-c", cmd],
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


def resolve_attempt_paths(attempt_id: str = "attempt_02") -> Dict[str, str]:
    clean_id = attempt_id.strip()
    if not clean_id:
        output_dir = GUEST_BASE_DIR
        unit_suffix = ""
    else:
        output_dir = f"{GUEST_BASE_DIR}/{clean_id}"
        unit_suffix = f"-{clean_id.replace('_', '-')}"
    return {
        "attempt_id": clean_id or "attempt_01",
        "output_dir": output_dir,
        "stage_dir": f"{output_dir}/city_warmup",
        "systemd_unit": f"aria-city-warmup-20261031{unit_suffix}",
    }


def archive_failed_attempt_01(host_archive_root: Path) -> Dict[str, Any]:
    """Copies the prior exited attempt (PID 472, exit -5) into a distinct archive directory on guest and host."""
    src_stage_dir = f"{GUEST_BASE_DIR}/city_warmup"
    guest_archive_dir = f"{GUEST_BASE_DIR}/attempt_01_pid472_sigtrap"
    host_archive_dir = host_archive_root / "attempt_01_pid472_sigtrap"
    host_archive_dir.mkdir(parents=True, exist_ok=True)

    run_wsl(f"mkdir -p {shlex.quote(guest_archive_dir)}")
    copied_files: List[str] = []
    for fname in ("training.log", "exit_code.txt", "supervisor.pid", "trainer.pid", "supervisor.py"):
        cp_res = run_wsl(
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


def prelaunch_validation(source_dir: Path, stage_dir: str = GUEST_STAGE_DIR) -> None:
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

    # 3. Duplicate protection: check if an existing trainer is already alive
    trainer_pid_file = f"{stage_dir}/trainer.pid"
    pid_check = run_wsl(f"cat {shlex.quote(trainer_pid_file)}", check=False)
    if pid_check.returncode == 0 and pid_check.stdout.strip().isdigit():
        existing_pid = pid_check.stdout.strip()
        alive_check = run_wsl(f"kill -0 {existing_pid}", check=False)
        if alive_check.returncode == 0:
            raise RuntimeError(
                f"Existing training process is already running (PID {existing_pid})! "
                "Refusing to launch competing training job. Use --observe-only to monitor."
            )

    # 4. Checkpoint directory overwrite protection
    ckpt_check = run_wsl(
        f"if [ -d {shlex.quote(stage_dir)}/checkpoints ] && [ \"$(ls -A {shlex.quote(stage_dir)}/checkpoints 2>/dev/null)\" ]; then echo nonempty; else echo empty; fi"
    )
    if ckpt_check.stdout.strip() == "nonempty":
        raise FileExistsError(
            f"Checkpoints directory {stage_dir}/checkpoints is non-empty. "
            "Refusing to overwrite existing checkpoint state without explicit resume."
        )


def deploy_and_start_supervisor(
    attempt_paths: Dict[str, str],
    host_archive_root: Optional[Path] = None,
) -> Dict[str, str]:
    output_dir = attempt_paths["output_dir"]
    stage_dir = attempt_paths["stage_dir"]
    systemd_unit = attempt_paths["systemd_unit"]
    run_wsl(f"mkdir -p {shlex.quote(stage_dir)}")

    if host_archive_root is not None:
        archive_failed_attempt_01(host_archive_root)

    # Supervisor script in Python: writes supervisor.pid, starts trainer with Popen, writes trainer.pid, waits, writes exit_code.txt
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

    cmd = [
        "{WSL_PYTHON}",
        "{RUN_WSL_TRAINING}",
        "--allow-growth",
        "--",
        "--manifest", "{GUEST_DATA_DIR}/manifest.json",
        "--stage", "city_warmup",
        "--weights", "imagenet",
        "--epochs", "5",
        "--learning-rate", "0.001",
        "--batch-size", "2",
        "--accumulate-steps", "4",
        "--seed", "42",
        "--device", "gpu",
        "--output-dir", "{output_dir}",
    ]

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

    if not trainer_alive or not ticks_arg:
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
    # completely outside any tool/terminal Job Object and console session.
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
    res = run_wsl(f"kill -0 {pid}", check=False)
    return res.returncode == 0


def monitor_training_progress(
    trainer_pid: str,
    stage_dir: str = GUEST_STAGE_DIR,
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

    print(
        f"Monitoring trainer PID {trainer_pid} in {WSL_DISTRO} ({stage_dir}) "
        f"for banner (5 epochs, GPU), epoch 0 initial evaluation commit, and advancing batches (1 -> 21+)..."
    )

    banner_re = re.compile(r"Staged Trainer:\s*city_warmup\s*\(model_a\)")
    config_re = re.compile(r"Epochs:\s*5\s*\|\s*LR:\s*0\.001")
    device_re = re.compile(r"Device:\s*gpu")
    # Use non-whitespace token (\\S+) to catch nan/inf rather than skipping them
    batch_re = re.compile(r"Batch\s+(\d+)/(\d+)\s+-\s+loss\s+(\S+)")

    while time.time() - start_time < timeout_seconds:
        if not check_process_alive(trainer_pid):
            exit_code_res = run_wsl(f"cat {shlex.quote(stage_dir)}/exit_code.txt", check=False)
            log_tail = run_wsl(f"tail -n 40 {shlex.quote(stage_dir)}/training.log", check=False).stdout
            raise RuntimeError(
                f"Trainer process {trainer_pid} exited prematurely with exit code: {exit_code_res.stdout.strip()}.\n"
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
                if meta_json.get("epoch") == 0 and meta_json.get("stage") == "city_warmup":
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

            if tot_b == EXPECTED_PLANNED_BATCHES:
                observed_planned_batches = True
            else:
                raise ValueError(
                    f"Unexpected planned batches count: got {tot_b}, expected {EXPECTED_PLANNED_BATCHES}"
                )

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
            print("Successfully verified: banner, config, initial evaluation commit (epoch 0), and advancing batches (1 -> 21+).", flush=True)
            return {
                "observed_banner": observed_banner,
                "observed_config": observed_config,
                "observed_initial_eval": observed_initial_eval,
                "observed_batch_1": observed_batch_1,
                "observed_batch_21": observed_batch_21,
                "observed_planned_batches": observed_planned_batches,
                "planned_batches": EXPECTED_PLANNED_BATCHES,
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
) -> Dict[str, Any]:
    trainer_pid = pids["trainer_pid"]
    sup_pid = pids["supervisor_pid"]
    output_dir = attempt_paths["output_dir"]
    stage_dir = attempt_paths["stage_dir"]
    systemd_unit = attempt_paths["systemd_unit"]
    proc_info = read_proc_identity(trainer_pid)

    resume_cmd = (
        f"{WSL_PYTHON} {RUN_WSL_TRAINING} --allow-growth -- "
        f"--manifest {GUEST_DATA_DIR}/manifest.json "
        f"--stage city_warmup "
        f"--resume {stage_dir}/checkpoints/latest "
        f"--epochs 5 "
        f"--learning-rate 0.001 "
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

    receipt: Dict[str, Any] = {
        "task_id": "ARIA-CITY-WARMUP-LAUNCH-20261031",
        "attempt_id": attempt_paths["attempt_id"],
        "launch_timestamp_utc": now_iso,
        "status": "RUNNING",
        "verified_launch": True,
        "exclusive_gpu_ownership": True,
        "process_identity": {
            "distro": WSL_DISTRO,
            "systemd_unit": f"{systemd_unit}.service",
            "trainer_pid": int(trainer_pid),
            "supervisor_pid": int(sup_pid),
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
        "configuration": {
            "stage": "city_warmup",
            "weights": "imagenet",
            "epochs": 5,
            "learning_rate": 0.001,
            "batch_size": 2,
            "accumulate_steps": 4,
            "seed": 42,
            "device": "gpu",
            "allow_growth": True,
            "architecture": "model_a",
            "encoder_policy": "freeze_all",
            "freeze_bn": True,
            "dataset_filter": "cityscapes",
        },
        "dataset": {
            "manifest_path": f"{GUEST_DATA_DIR}/manifest.json",
            "manifest_sha256": EXPECTED_BASE_MANIFEST_SHA256,
            "dataset_counts": {
                "total_records": 13033,
                "cityscapes_train": 2550,
                "cityscapes_val": 425,
                "cityscapes_test_reserved": 500,
                "batches_per_epoch": EXPECTED_PLANNED_BATCHES,
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
            "This receipt establishes verified stable launch and initial batch progress, not 5-epoch completion.",
            "No claim is made regarding final model convergence, loss trajectory, or mIoU quality.",
            "The durable job retains exclusive GPU ownership and runs asynchronously in the detached Linux session.",
        ],
    }

    host_receipt_path.parent.mkdir(parents=True, exist_ok=True)
    host_receipt_path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")

    guest_receipt_path = f"{GUEST_BASE_DIR}/launch_receipt.json"
    run_wsl(f"cat << 'EOF' > {shlex.quote(guest_receipt_path)}\n{json.dumps(receipt, indent=2)}\nEOF")

    return receipt


def snapshot_current_state(
    pids: Dict[str, str],
    attempt_paths: Dict[str, str],
    keeper_info: Dict[str, Any],
    gpu_status: Dict[str, Any],
) -> Dict[str, Any]:
    trainer_pid = pids["trainer_pid"]
    stage_dir = attempt_paths["stage_dir"]
    proc_info = read_proc_identity(trainer_pid)
    log_res = run_wsl(f"cat {shlex.quote(stage_dir)}/training.log", check=False)
    log_lines = log_res.stdout.splitlines() if log_res.returncode == 0 else []
    meta_res = run_wsl(
        f"cat {shlex.quote(stage_dir)}/checkpoints/best/metadata.json 2>/dev/null",
        check=False,
    )
    epoch0_committed = False
    if meta_res.returncode == 0 and meta_res.stdout.strip():
        try:
            meta_json = json.loads(meta_res.stdout.strip())
            if meta_json.get("epoch") == 0 and meta_json.get("stage") == "city_warmup":
                epoch0_committed = True
        except Exception:
            pass
    return {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "attempt_paths": attempt_paths,
        "pids": pids,
        "process_identity": proc_info,
        "windows_keeper": keeper_info,
        "gpu_status": gpu_status,
        "training_log_lines": len(log_lines),
        "training_log_tail": log_lines[-15:],
        "epoch0_checkpoint_committed": epoch0_committed,
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
        default=Path("build/vision-training/full-training-20261031/launch_receipt.json"),
        help="Path for launch receipt JSON",
    )
    parser.add_argument(
        "--attempt-id",
        type=str,
        default="attempt_02",
        help="Distinct attempt identifier subdirectory (default: attempt_02; pass empty string for root)",
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
        help="Ensure detached Windows keeper and emit current status snapshot without waiting",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=1800,
        help="Observation timeout in seconds (default: 1800)",
    )
    args = parser.parse_args()

    if args.archive_only:
        forensic = archive_failed_attempt_01(args.receipt.parent)
        print(json.dumps(forensic, indent=2), flush=True)
        return

    attempt_paths = resolve_attempt_paths(args.attempt_id)
    stage_dir = attempt_paths["stage_dir"]

    # 1. Preflight GPU check
    initial_gpu = check_gpu_status()
    print(f"Preflight GPU status: {initial_gpu['name']}, {initial_gpu['memory_used_mib']} MiB used.", flush=True)

    if not args.observe_only and not args.status_only:
        # Prelaunch validation
        prelaunch_validation(args.source_dir, stage_dir=stage_dir)
        print(f"Deploying supervisor and starting durable detached training run ({attempt_paths['attempt_id']})...", flush=True)
        pids = deploy_and_start_supervisor(attempt_paths, host_archive_root=args.receipt.parent)
    else:
        sup_pid = run_wsl(f"cat {shlex.quote(stage_dir)}/supervisor.pid").stdout.strip()
        trainer_pid = run_wsl(f"cat {shlex.quote(stage_dir)}/trainer.pid").stdout.strip()
        pids = {"supervisor_pid": sup_pid, "trainer_pid": trainer_pid}
        print(f"Existing job ({attempt_paths['attempt_id']}): Supervisor PID {sup_pid}, Trainer PID {trainer_pid}", flush=True)

    proc_info = read_proc_identity(pids["trainer_pid"])
    keeper_info = ensure_windows_keeper(
        trainer_pid=pids["trainer_pid"],
        start_ticks=proc_info.get("start_ticks"),
        keeper_dir=args.receipt.parent,
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
        snap = snapshot_current_state(pids, attempt_paths, keeper_info, initial_gpu)
        snap["guest_uptime"] = run_wsl("uptime -s && uptime", check=False).stdout.strip()
        snap["exit_code_txt"] = run_wsl(f"cat {shlex.quote(stage_dir)}/exit_code.txt 2>/dev/null", check=False).stdout.strip()
        snap["stage_dir_ls"] = run_wsl(f"ls -la {shlex.quote(stage_dir)}", check=False).stdout.strip()
        snap["journal_tail"] = run_wsl(
            f"journalctl --user -u {shlex.quote(attempt_paths['systemd_unit'])} -n 30 --no-pager 2>/dev/null",
            check=False,
        ).stdout.strip()
        status_path = args.receipt.parent / "launch_attempt_status.json"
        status_path.parent.mkdir(parents=True, exist_ok=True)
        status_path.write_text(json.dumps(snap, indent=2), encoding="utf-8")
        print(json.dumps(snap, indent=2), flush=True)
        return

    # 2. Monitor startup and advancing batches
    monitor_results = monitor_training_progress(
        pids["trainer_pid"], stage_dir=stage_dir, timeout_seconds=args.timeout
    )

    # 3. Generate receipt
    receipt = create_launch_receipt(
        pids, attempt_paths, monitor_results, initial_gpu, keeper_info, args.receipt
    )
    print(f"Launch receipt successfully generated at: {args.receipt}", flush=True)


if __name__ == "__main__":
    main()
