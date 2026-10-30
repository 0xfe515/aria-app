#!/usr/bin/env python3
"""
Bounded WSL2 real-data PortraitDataset verification script using a derived subset manifest.

SCOPE & CONSTRAINTS:
- Does NOT modify the original manifest.json, pseudo_manifest.json, converted files, or raw dataset.
- Creates a small derived subset manifest (12 records across train/val/test and cityscapes/surface,
  including surface train records with pseudo-labels) under an owned work directory.
- Copies only those 12 records' converted artifacts under the owned work directory while preserving
  their exact relative paths (converted/... and labels/..., weights/...) and their exact original
  Windows raw image_path (C:\\Users\\felice\\Desktop\\dataset\\...), exercising real WSL wslpath
  translation, raw image dimension checks, NPZ/PNG decoding, pseudo-label overlay, and batching.
- Explicitly labeled as a diagnostic subset check; makes no full-dataset or model quality claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify PortraitDataset real-data loading on WSL2 using a derived subset.")
    parser.add_argument(
        "--base-manifest",
        type=str,
        default=str(REPO_ROOT / "build" / "vision-training" / "data" / "manifest.json"),
        help="Path to the immutable full dataset manifest.json",
    )
    parser.add_argument(
        "--pseudo-manifest",
        type=str,
        default=str(REPO_ROOT / "build" / "vision-training" / "data" / "pseudo_full" / "pseudo_manifest.json"),
        help="Path to the immutable pseudo_manifest.json",
    )
    parser.add_argument(
        "--work-dir",
        type=str,
        required=True,
        help="Owned output directory for derived subset manifest, copied converted files, and report JSON",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    start_time = time.time()

    base_manifest_path = Path(args.base_manifest).resolve()
    pseudo_manifest_path = Path(args.pseudo_manifest).resolve()
    work_dir = Path(args.work_dir).resolve()
    work_dir.mkdir(parents=True, exist_ok=True)

    base_sha_before = _sha256_file(base_manifest_path)
    pseudo_sha_before = _sha256_file(pseudo_manifest_path)

    base_data = json.loads(base_manifest_path.read_text(encoding="utf-8"))
    pseudo_data = json.loads(pseudo_manifest_path.read_text(encoding="utf-8"))

    pseudo_by_id = {row["id"]: row for row in pseudo_data["records"]}

    selected_records: list[dict] = []
    used_groups: set[str] = set()
    used_hashes: set[str] = set()

    buckets = [
        ("cityscapes", "train", False, 2),
        ("surface", "train", True, 2),
        ("surface", "train", False, 2),
        ("cityscapes", "val", False, 2),
        ("surface", "val", False, 2),
        ("surface", "test", False, 2),
    ]

    for src, split, want_pseudo, count in buckets:
        found = 0
        for rec in base_data["records"]:
            if rec.get("source") != src or rec.get("split") != split:
                continue
            has_pseudo = rec["id"] in pseudo_by_id
            if want_pseudo != has_pseudo:
                continue
            if rec["group_id"] in used_groups or rec["image_sha256"] in used_hashes:
                continue
            if not all(
                rec.get(k)
                for k in (
                    "image_tensor_path",
                    "semantic_path",
                    "hazard_path",
                    "hazard_valid_path",
                    "semantic_source_path",
                    "semantic_weight_path",
                )
            ):
                continue
            selected_records.append(rec)
            used_groups.add(rec["group_id"])
            used_hashes.add(rec["image_sha256"])
            found += 1
            if found >= count:
                break
        if found < count:
            raise RuntimeError(f"Could not find {count} records for bucket {(src, split, want_pseudo)}")

    subset_root = work_dir / "derived_subset"
    subset_pseudo_root = subset_root / "pseudo_full"
    subset_root.mkdir(parents=True, exist_ok=True)
    subset_pseudo_root.mkdir(parents=True, exist_ok=True)

    copied_files = 0
    for rec in selected_records:
        for k in (
            "image_tensor_path",
            "semantic_path",
            "hazard_path",
            "hazard_valid_path",
            "semantic_source_path",
            "semantic_weight_path",
        ):
            rel = rec[k]
            src_file = base_manifest_path.parent / rel
            dst_file = subset_root / rel
            dst_file.parent.mkdir(parents=True, exist_ok=True)
            if not dst_file.exists():
                shutil.copy2(src_file, dst_file)
                copied_files += 1

    selected_pseudo_rows: list[dict] = []
    for rec in selected_records:
        if rec["id"] in pseudo_by_id:
            prow = pseudo_by_id[rec["id"]]
            selected_pseudo_rows.append(prow)
            for pk in ("semantic_path", "semantic_weight_path"):
                rel = prow[pk]
                src_file = pseudo_manifest_path.parent / rel
                dst_file = subset_pseudo_root / rel
                dst_file.parent.mkdir(parents=True, exist_ok=True)
                if not dst_file.exists():
                    shutil.copy2(src_file, dst_file)
                    copied_files += 1

    derived_manifest = {
        "schema_version": base_data["schema_version"],
        "seed": base_data["seed"],
        "class_names": base_data["class_names"],
        "hazard_names": base_data["hazard_names"],
        "diagnostic_subset": True,
        "diagnostic_note": (
            "Bounded 12-record diagnostic subset derived from build/vision-training/data/manifest.json "
            "for WSL2 real-data loader verification; not a full dataset completeness or model quality claim."
        ),
        "records": selected_records,
    }
    derived_manifest_path = subset_root / "manifest_subset.json"
    derived_manifest_path.write_text(json.dumps(derived_manifest, indent=2) + "\n", encoding="utf-8")

    derived_pseudo_manifest = {
        "schema_version": pseudo_data["schema_version"],
        "diagnostic_subset": True,
        "note": pseudo_data.get("note", ""),
        "records": selected_pseudo_rows,
    }
    derived_pseudo_path = subset_pseudo_root / "pseudo_manifest_subset.json"
    derived_pseudo_path.write_text(json.dumps(derived_pseudo_manifest, indent=2) + "\n", encoding="utf-8")

    from tools.vision.training.train_data import PortraitDataset

    train_ds = PortraitDataset(
        manifest_path=derived_manifest_path,
        split="train",
        sources="mixed",
        pseudo_manifest_path=derived_pseudo_path,
        seed=42,
        require_complete=True,
    )
    val_ds = PortraitDataset(
        manifest_path=derived_manifest_path,
        split="val",
        sources="mixed",
        seed=42,
        require_complete=True,
    )
    test_ds = PortraitDataset(
        manifest_path=derived_manifest_path,
        split="test",
        sources="surface",
        seed=42,
        require_complete=True,
    )

    train_batches = list(train_ds.iter_batches(batch_size=2, epoch=0, augment=False, balanced=False))
    train_aug_bal_batches = list(train_ds.iter_batches(batch_size=2, epoch=1, augment=True, balanced=True))
    val_samples = list(val_ds.iter_samples(epoch=0, augment=False, balanced=False))
    test_samples = list(test_ds.iter_samples(epoch=0, augment=False, balanced=False))

    pseudo_pixels_total = sum(int((b["semantic_source"] == 2).sum()) for b in train_batches)
    if pseudo_pixels_total <= 0:
        raise RuntimeError("Expected positive pseudo-label pixel count (semantic_source == 2) in train subset.")

    first_batch = train_batches[0]
    assert first_batch["image"].shape == (2, 512, 288, 3), f"Unexpected image batch shape: {first_batch['image'].shape}"
    assert first_batch["semantic"].shape == (2, 512, 288), f"Unexpected semantic batch shape: {first_batch['semantic'].shape}"
    assert first_batch["hazard"].shape == (2, 512, 288, 8), f"Unexpected hazard batch shape: {first_batch['hazard'].shape}"

    base_sha_after = _sha256_file(base_manifest_path)
    pseudo_sha_after = _sha256_file(pseudo_manifest_path)
    assert base_sha_before == base_sha_after, "Base manifest was modified!"
    assert pseudo_sha_before == pseudo_sha_after, "Pseudo manifest was modified!"

    elapsed = time.time() - start_time
    report = {
        "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "diagnostic_subset": True,
        "claim_scope": (
            "Bounded 12-record WSL2 real-data PortraitDataset loader & wslpath verification only; "
            "no full-dataset completeness or model quality claim."
        ),
        "python_executable": sys.executable,
        "elapsed_seconds": round(elapsed, 3),
        "base_manifest_path": str(base_manifest_path),
        "base_manifest_sha256_unchanged": base_sha_before == base_sha_after,
        "base_manifest_sha256": base_sha_after,
        "pseudo_manifest_path": str(pseudo_manifest_path),
        "pseudo_manifest_sha256_unchanged": pseudo_sha_before == pseudo_sha_after,
        "pseudo_manifest_sha256": pseudo_sha_after,
        "derived_manifest_path": str(derived_manifest_path),
        "derived_pseudo_manifest_path": str(derived_pseudo_path),
        "selected_record_count": len(selected_records),
        "selected_record_ids": [r["id"] for r in selected_records],
        "selected_pseudo_record_ids": [r["id"] for r in selected_pseudo_rows],
        "sample_original_image_paths_resolved": [
            str(r.original_image) for r in train_ds._records[:4]
        ],
        "train_sample_count": train_ds.sample_count(epoch=0, balanced=False),
        "train_balanced_sample_count": train_ds.sample_count(epoch=1, balanced=True),
        "train_batch_count": len(train_batches),
        "train_aug_balanced_batch_count": len(train_aug_bal_batches),
        "val_sample_count": len(val_samples),
        "test_sample_count": len(test_samples),
        "pseudo_supervision_pixels_verified": pseudo_pixels_total,
        "first_batch_shapes": {
            "image": list(first_batch["image"].shape),
            "semantic": list(first_batch["semantic"].shape),
            "semantic_weight": list(first_batch["semantic_weight"].shape),
            "semantic_source": list(first_batch["semantic_source"].shape),
            "hazard": list(first_batch["hazard"].shape),
            "hazard_valid": list(first_batch["hazard_valid"].shape),
        },
        "status": "PASS",
    }

    report_path = work_dir / "loader_report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
