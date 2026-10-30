#!/usr/bin/env python3
"""Stage and verify full training inputs for the Aria portrait vision model."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict

EXPECTED_BASE_MANIFEST_SHA256 = (
    "e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9"
)
EXPECTED_RECORD_COUNT = 13033
EXPECTED_CONVERTED_COUNT = 52132
EXPECTED_CITY_TRAIN = 2550
EXPECTED_CITY_VAL = 425
EXPECTED_CITY_TEST = 500
WSL_DISTRO = "Ubuntu-26.04"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def run_wsl_cmd(cmd: str) -> str:
    res = subprocess.run(
        ["wsl", "-d", WSL_DISTRO, "-e", "/bin/bash", "-c", cmd],
        capture_output=True,
        text=True,
        check=True,
    )
    return res.stdout.strip()


def stage_inputs_archive(source_dir: Path, guest_dir: str, archive_dir: Path) -> None:
    archive_path = archive_dir / "inputs.tar"
    print(f"Creating staging archive {archive_path} from {source_dir}...")
    subprocess.run(
        [
            "tar.exe",
            "-cf",
            str(archive_path),
            "-C",
            str(source_dir.resolve()),
            "manifest.json",
            "converted",
            "pseudo_full",
        ],
        check=True,
    )
    try:
        run_wsl_cmd(f"mkdir -p {shlex.quote(guest_dir)}")
        wsl_archive_path = run_wsl_cmd(f"wslpath -u {shlex.quote(str(archive_path.resolve()))}")
        print(f"Extracting {wsl_archive_path} to {guest_dir} inside {WSL_DISTRO}...")
        run_wsl_cmd(f"tar -xf {shlex.quote(wsl_archive_path)} -C {shlex.quote(guest_dir)}")
    finally:
        if archive_path.is_file():
            archive_path.unlink()
            print(f"Removed temporary archive {archive_path}")


def verify_and_report(
    source_dir: Path,
    guest_dir: str,
    report_path: Path,
    auto_stage: bool = False,
) -> Dict[str, Any]:
    source_manifest = source_dir / "manifest.json"
    if not source_manifest.is_file():
        raise FileNotFoundError(f"Source manifest not found: {source_manifest}")

    src_hash = sha256_file(source_manifest)
    if src_hash != EXPECTED_BASE_MANIFEST_SHA256:
        raise ValueError(
            f"Source manifest SHA256 mismatch: got {src_hash}, expected {EXPECTED_BASE_MANIFEST_SHA256}"
        )

    # Check if guest manifest exists
    guest_manifest_path = f"{guest_dir}/manifest.json"
    exists_check = subprocess.run(
        ["wsl", "-d", WSL_DISTRO, "-e", "/bin/bash", "-c", f"test -f {shlex.quote(guest_manifest_path)}"],
        check=False,
    )
    if exists_check.returncode != 0:
        if auto_stage:
            stage_inputs_archive(source_dir, guest_dir, report_path.parent)
        else:
            raise FileNotFoundError(f"Guest manifest not found at {guest_manifest_path} (auto-stage disabled)")

    # Check guest manifest hash
    guest_hash_out = run_wsl_cmd(f"sha256sum {shlex.quote(guest_manifest_path)}")
    guest_hash = guest_hash_out.split()[0]
    if guest_hash != EXPECTED_BASE_MANIFEST_SHA256:
        raise ValueError(
            f"Guest manifest SHA256 mismatch: got {guest_hash}, expected {EXPECTED_BASE_MANIFEST_SHA256}"
        )

    # Count guest converted files
    guest_conv_dir = f"{guest_dir}/converted"
    conv_count_out = run_wsl_cmd(f"find {shlex.quote(guest_conv_dir)} -type f | wc -l")
    converted_count = int(conv_count_out.strip())

    # Check for empty (0-byte) converted files (must be 0)
    empty_conv_out = run_wsl_cmd(f"find {shlex.quote(guest_conv_dir)} -type f -empty | wc -l")
    empty_converted_count = int(empty_conv_out.strip())

    # Count guest pseudo files if present
    guest_pseudo_dir = f"{guest_dir}/pseudo_full"
    pseudo_count_out = run_wsl_cmd(
        f"if [ -d {shlex.quote(guest_pseudo_dir)} ]; then find {shlex.quote(guest_pseudo_dir)} -type f | wc -l; else echo 0; fi"
    )
    pseudo_count = int(pseudo_count_out.strip())

    # Check disk space on guest
    df_out = run_wsl_cmd(f"df -B1 {shlex.quote(guest_dir)} | tail -n 1")
    df_parts = df_out.split()
    guest_disk_avail_bytes = int(df_parts[3])

    # Check host disk space
    host_usage = shutil.disk_usage(str(source_dir.resolve()))
    host_free_bytes = host_usage.free

    # Read manifest metadata
    with open(source_manifest, "r", encoding="utf-8") as f:
        manifest_data = json.load(f)
    records = manifest_data.get("records", [])
    total_records = len(records)
    city_train = sum(1 for r in records if r.get("source") == "cityscapes" and r.get("split") == "train")
    city_val = sum(1 for r in records if r.get("source") == "cityscapes" and r.get("split") == "val")
    city_test = sum(1 for r in records if r.get("source") == "cityscapes" and r.get("split") == "test")
    surface_records = sum(1 for r in records if r.get("source") == "surface")

    # Strict validation flags
    checks = {
        "source_manifest_hash_pass": src_hash == EXPECTED_BASE_MANIFEST_SHA256,
        "guest_manifest_hash_pass": guest_hash == EXPECTED_BASE_MANIFEST_SHA256,
        "manifest_byte_identical": src_hash == guest_hash,
        "record_count_pass": total_records == EXPECTED_RECORD_COUNT,
        "city_train_pass": city_train == EXPECTED_CITY_TRAIN,
        "city_val_pass": city_val == EXPECTED_CITY_VAL,
        "city_test_pass": city_test == EXPECTED_CITY_TEST,
        "converted_count_pass": converted_count == EXPECTED_CONVERTED_COUNT,
        "zero_byte_converted_pass": empty_converted_count == 0,
        "guest_headroom_sufficient": guest_disk_avail_bytes > 10 * (1024**3),
    }

    all_passed = all(checks.values())
    status = "PASS" if all_passed else "FAIL"

    report: Dict[str, Any] = {
        "status": status,
        "checks": checks,
        "source_manifest": {
            "path": str(source_manifest.resolve()),
            "sha256": src_hash,
            "matches_expected": checks["source_manifest_hash_pass"],
        },
        "guest_staged_manifest": {
            "distro": WSL_DISTRO,
            "path": guest_manifest_path,
            "sha256": guest_hash,
            "matches_expected": checks["guest_manifest_hash_pass"],
            "byte_identical": checks["manifest_byte_identical"],
        },
        "records": {
            "total": total_records,
            "matches_expected": checks["record_count_pass"],
            "cityscapes": {
                "train": city_train,
                "val": city_val,
                "test": city_test,
                "total": city_train + city_val + city_test,
            },
            "surface": {
                "total": surface_records,
            },
        },
        "artifacts": {
            "converted_files_guest": converted_count,
            "matches_expected_converted": checks["converted_count_pass"],
            "empty_converted_files": empty_converted_count,
            "zero_byte_files_pass": checks["zero_byte_converted_pass"],
            "pseudo_files_guest": pseudo_count,
        },
        "disk_headroom": {
            "guest_avail_bytes": guest_disk_avail_bytes,
            "guest_avail_gib": round(guest_disk_avail_bytes / (1024**3), 2),
            "host_free_bytes": host_free_bytes,
            "host_free_gib": round(host_free_bytes / (1024**3), 2),
        },
    }

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    if not all_passed:
        failed_checks = [k for k, v in checks.items() if not v]
        raise RuntimeError(f"Staging verification failed checks: {failed_checks}")

    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Stage and verify full training inputs.")
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path("build/vision-training/data"),
        help="Source data directory containing manifest.json and converted/",
    )
    parser.add_argument(
        "--guest-dir",
        type=str,
        default="/home/user/.local/share/aria-training/full-training-20261031/data",
        help="Target guest directory",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("build/vision-training/full-training-20261031/staging_report.json"),
        help="Path for output staging report JSON",
    )
    parser.add_argument(
        "--auto-stage",
        action="store_true",
        help="Automatically stage inputs via archive if guest manifest is missing",
    )
    args = parser.parse_args()

    report = verify_and_report(args.source_dir, args.guest_dir, args.report, auto_stage=args.auto_stage)
    print(f"Staging verification completed: status={report['status']}")
    print(f"Report written to: {args.report}")


if __name__ == "__main__":
    main()
