"""Controlled two-arm training strategy experiment for Aria Model A.

Task ID: ARIA-STRATEGY-AB-PILOT-20261031
Preregistered protocol: docs/VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md

Compares under an identical 256-update budget (microbatch 2 x accumulate 4 = 2,048 presentations):
- Arm A (city_pretrain_then_mixed): Phase 1 Cityscapes 64 updates (LR 1e-3) -> Phase 2 Mixed 70:30 192 updates (LR 3e-4)
- Arm B (mixed_from_start):         Phase 1 Mixed 70:30 64 updates (LR 1e-3) -> Phase 2 Mixed 70:30 192 updates (LR 3e-4)

Invariants enforced:
- Shared seed 42, ImageNet MobileNetV2 encoder + BN frozen, identical initial weights (verified by file SHA-256 and in-memory tensor SHA-256).
- Fixed shared class weights computed once from all real training pixel counts (Cityscapes train + Surface train) using the corrected helper; pseudo-labels OFF in both arms.
- Fresh Adam optimizer at the Phase 1 -> Phase 2 boundary in both arms.
- Deterministic 70% Surface : 30% Cityscapes microbatch schedule (7 Surface, 3 Cityscapes per 10 microbatches).
- Identical Phase 2 stream/augmentation reset across both arms, verified by actual Phase 2 input digest (IDs, image, semantic, semantic_weight).
- Single-forward evaluation over all 1,882 validation records (1,457 Surface val + 425 Cityscapes val) using StreamingConfusionMatrix; test split is strictly excluded.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import math
import platform
import random
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Mapping, Optional, Sequence, Set, Tuple

_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

import numpy as np
from PIL import Image
import tensorflow as tf
from tensorflow import keras

from tools.vision.training.losses import (
    CombinedSemanticLoss,
    compute_inverse_sqrt_class_weights,
)
from tools.vision.training.metrics import (
    CLASS_NAMES,
    IGNORE_INDEX,
    OBJECT_CLASS_NAMES,
    REAL_PROVENANCE,
    WALKABLE_CLASS_IDS,
    StreamingConfusionMatrix,
)
from tools.vision.training.model import (
    HAZARD_CLASSES,
    IGNORE_LABEL,
    NUM_SEMANTIC_CLASSES,
    build_model_a,
    freeze_batch_normalization,
    set_encoder_trainability,
)
from tools.vision.training.train import (
    DEFAULT_ACCUMULATE_STEPS,
    DEFAULT_BATCH_SIZE,
    DEFAULT_CLIPNORM,
    DEFAULT_SEED,
    StagedTrainer,
    compute_sha256,
    configure_device,
)
from tools.vision.training.train_data import PortraitDataset

EXPERIMENT_ID: str = "ARIA-STRATEGY-AB-PILOT-20261031"
RANDOM_SAMPLING_TASK_ID: str = "ARIA-RANDOM-SAMPLING-AB-20261031"
PROTOCOL_DOC: str = "docs/VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md"
RANDOM_SAMPLING_PROTOCOL_DOC: str = "docs/VISION_RANDOM_SAMPLING_EXPERIMENT_2026-10-31.md"
EXPECTED_MANIFEST_SHA256: str = (
    "e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9"
)
EXPECTED_SPLIT_COUNTS: Dict[str, int] = {
    "cityscapes_train": 2550,
    "surface_train": 6647,
    "cityscapes_val": 425,
    "surface_val": 1457,
    "cityscapes_test": 500,
    "surface_test": 1454,
    "total_records": 13033,
}

DEFAULT_SAMPLING_SEED_RERUN: int = 20261031
PRIOR_SHARED_INIT_FILE_SHA256: str = (
    "f47c4e3c3e25697595aec2781711ba9d2255f96fe7bac7e0d0999ecdfd716487"
)
PRIOR_SHARED_INIT_ALL_TENSOR_SHA256: str = (
    "dbca1ef6451f0078a5fa135121711067ee80f39f97822985ee415e9e44be4fd2"
)
PRIOR_SHARED_INIT_TRAINABLE_TENSOR_SHA256: str = (
    "b2e9037bb1c9cd9a24307452ac35a80a601aa52ddf3220ae431bd4ad4dbc013d"
)
PRIOR_SHARED_INIT_NON_TRAINABLE_TENSOR_SHA256: str = (
    "882c42262ec798ba6fac2ea5f14e2737fa7856bd68ba754eadb51b4da9bf3b39"
)
PRIOR_SEED42_PHASE_IDS_SHA256: Dict[str, str] = {
    "arm_a_phase1": "b4b1fb878bac173bf835668044fa9effd593e71f3cf2365a08e22f7c8ee2837d",
    "arm_b_phase1": "98ae8349ce0fceaed8626712279600a6f0162f16685af2de48749cf89333be38",
    "phase2": "1c8f1200c496a65bba06d6a84399fb217f1abf4267745fb0caf71cc68564edcb",
}

DEFAULT_PHASE1_UPDATES: int = 64
DEFAULT_PHASE2_UPDATES: int = 192
DEFAULT_PHASE1_LR: float = 1e-3
DEFAULT_PHASE2_LR: float = 3e-4
PHASE1_BASE_EPOCH: int = 0
PHASE2_BASE_EPOCH: int = 1
CROSSWALK_CLASS_NAME: str = "crosswalk"
CROSSWALK_CLASS_INDEX: int = CLASS_NAMES.index("crosswalk")

WALKABLE_CLASS_NAMES: Tuple[str, ...] = ("sidewalk", "crosswalk")
MIXED_SCHEDULE_10: Tuple[str, ...] = (
    "surface",
    "surface",
    "cityscapes",
    "surface",
    "surface",
    "cityscapes",
    "surface",
    "surface",
    "cityscapes",
    "surface",
)


def _validate_non_negative_seed(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer, got {value!r}.")
    return int(value)


def resolve_sampling_seed(seed: int, sampling_seed: Optional[int] = None) -> int:
    """Resolves explicit sampling_seed, defaulting to model seed when omitted."""
    _validate_non_negative_seed(seed, "seed")
    if sampling_seed is None:
        return int(seed)
    return _validate_non_negative_seed(sampling_seed, "sampling_seed")


def set_deterministic_seeds(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def compute_model_weights_tensor_sha256(model: keras.Model) -> Dict[str, Any]:
    """Computes in-memory numerical tensor SHA-256 digests and checks finiteness."""
    all_hasher = hashlib.sha256()
    trainable_hasher = hashlib.sha256()
    non_trainable_hasher = hashlib.sha256()
    trainable_ids = {id(v) for v in model.trainable_variables}

    for var in model.weights:
        arr = np.ascontiguousarray(var.numpy())
        if not np.all(np.isfinite(arr)):
            raise ValueError(f"Non-finite values detected in model variable {getattr(var, 'path', var.name)}.")
        var_id = getattr(var, "path", var.name)
        header = f"{var_id}|{tuple(arr.shape)}|{arr.dtype}|".encode("utf-8")
        payload = arr.tobytes()
        all_hasher.update(header)
        all_hasher.update(payload)
        if id(var) in trainable_ids:
            trainable_hasher.update(header)
            trainable_hasher.update(payload)
        else:
            non_trainable_hasher.update(header)
            non_trainable_hasher.update(payload)

    return {
        "all_weights_tensor_sha256": all_hasher.hexdigest(),
        "trainable_weights_tensor_sha256": trainable_hasher.hexdigest(),
        "non_trainable_weights_tensor_sha256": non_trainable_hasher.hexdigest(),
        "total_variable_count": len(model.weights),
        "trainable_variable_count": len(model.trainable_variables),
        "non_trainable_variable_count": len(model.non_trainable_variables),
        "trainable_variable_paths": [
            getattr(v, "path", v.name) for v in model.trainable_variables
        ],
    }


def audit_manifest_and_compute_shared_weights(
    manifest_path: Path,
    *,
    require_expected_manifest_hash: bool = True,
    sample_provenance_datasets: Optional[Mapping[str, PortraitDataset]] = None,
    sample_provenance_records_per_source: int = 2,
) -> Dict[str, Any]:
    """Audits manifest schema/splits/hashes and computes fixed shared class weights from real train counts."""
    manifest_path = manifest_path.expanduser().resolve()
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")

    manifest_sha256 = compute_sha256(manifest_path)
    if require_expected_manifest_hash and manifest_sha256 != EXPECTED_MANIFEST_SHA256:
        raise ValueError(
            f"Manifest SHA-256 mismatch: expected {EXPECTED_MANIFEST_SHA256}, got {manifest_sha256}."
        )

    raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or type(raw.get("schema_version")) is not int or raw.get("schema_version") != 1:
        raise ValueError("Manifest must be a schema_version 1 JSON object.")
    if raw.get("class_names") != list(CLASS_NAMES):
        raise ValueError(f"Manifest class_names must match {list(CLASS_NAMES)}.")
    if raw.get("hazard_names") != list(HAZARD_CLASSES):
        raise ValueError(f"Manifest hazard_names must match {list(HAZARD_CLASSES)}.")
    records = raw.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("Manifest records must be a non-empty list.")

    split_counts: Dict[str, int] = {
        "cityscapes_train": 0,
        "surface_train": 0,
        "cityscapes_val": 0,
        "surface_val": 0,
        "cityscapes_test": 0,
        "surface_test": 0,
        "total_records": len(records),
    }
    group_split: Dict[str, str] = {}
    hash_split: Dict[str, str] = {}
    counts_by_source: Dict[str, List[int]] = {
        "cityscapes": [0] * NUM_SEMANTIC_CLASSES,
        "surface": [0] * NUM_SEMANTIC_CLASSES,
    }
    expected_class_keys = set(CLASS_NAMES)
    record_counts_by_id: Dict[str, List[int]] = {}

    for idx, rec in enumerate(records):
        if not isinstance(rec, dict):
            raise ValueError(f"Record {idx} is not a JSON object.")
        rec_id = rec.get("id")
        source = rec.get("source")
        split = rec.get("split")
        group_id = rec.get("group_id")
        image_sha = rec.get("image_sha256")
        if source not in ("cityscapes", "surface") or split not in ("train", "val", "test"):
            raise ValueError(f"Record {rec_id!r} has invalid source={source!r} or split={split!r}.")
        split_counts[f"{source}_{split}"] += 1

        prev_group_split = group_split.setdefault(str(group_id), split)
        if prev_group_split != split:
            raise ValueError(f"Group {group_id!r} crosses splits {prev_group_split!r} and {split!r}.")
        prev_hash_split = hash_split.setdefault(str(image_sha), split)
        if prev_hash_split != split:
            raise ValueError(f"Image hash {image_sha!r} crosses splits {prev_hash_split!r} and {split!r}.")

        if split != "train":
            continue

        px_map = rec.get("class_pixel_counts")
        if not isinstance(px_map, dict) or set(px_map.keys()) != expected_class_keys:
            raise ValueError(
                f"Train record {rec_id!r} has missing or malformed class_pixel_counts keys."
            )
        rec_vec: List[int] = []
        for c_idx, c_name in enumerate(CLASS_NAMES):
            val = px_map[c_name]
            if isinstance(val, bool) or not isinstance(val, int) or val < 0:
                raise ValueError(
                    f"Train record {rec_id!r} class_pixel_counts[{c_name!r}] must be a non-negative int."
                )
            counts_by_source[source][c_idx] += val
            rec_vec.append(val)
        record_counts_by_id[str(rec_id)] = rec_vec

    if require_expected_manifest_hash and split_counts != EXPECTED_SPLIT_COUNTS:
        raise ValueError(
            f"Manifest split counts {split_counts} do not match expected {EXPECTED_SPLIT_COUNTS}."
        )
    if split_counts["cityscapes_train"] <= 0 or split_counts["surface_train"] <= 0:
        raise ValueError("Both cityscapes_train and surface_train must be non-empty.")
    if split_counts["cityscapes_val"] <= 0 or split_counts["surface_val"] <= 0:
        raise ValueError("Both cityscapes_val and surface_val must be non-empty.")

    total_train_counts = [
        counts_by_source["cityscapes"][i] + counts_by_source["surface"][i]
        for i in range(NUM_SEMANTIC_CLASSES)
    ]
    if sum(total_train_counts) <= 0:
        raise ValueError("Total real training pixel counts must be positive.")

    provenance_checks: List[Dict[str, Any]] = []
    if sample_provenance_datasets and sample_provenance_records_per_source > 0:
        for source_name in ("cityscapes", "surface"):
            ds = sample_provenance_datasets.get(source_name)
            if ds is None:
                continue
            for sample_idx, sample in enumerate(ds.iter_samples(epoch=0, augment=False, balanced=False)):
                if sample_idx >= sample_provenance_records_per_source:
                    break
                rid = str(sample["id"])
                if sample["split"] != "train" or sample["source"] != source_name:
                    raise ValueError(f"Unexpected split/source in provenance check for {rid}.")
                sem = sample["semantic"]
                src = sample["semantic_source"]
                wt = sample["semantic_weight"]
                if np.any(~np.isin(src, (0, 1))):
                    raise ValueError(f"Record {rid} contains non-real provenance when pseudo is OFF.")
                if np.any((src == 1) != (sem != IGNORE_LABEL)):
                    raise ValueError(f"Record {rid} semantic_source==1 disagrees with semantic!=255.")
                if np.any((src == 1) & (wt != 1.0)) or np.any((src == 0) & (wt != 0.0)):
                    raise ValueError(f"Record {rid} semantic_weight disagrees with real provenance contract.")
                decoded_counts = np.bincount(sem[src == 1], minlength=NUM_SEMANTIC_CLASSES).tolist()
                manifest_counts = record_counts_by_id.get(rid)
                if decoded_counts != manifest_counts:
                    raise ValueError(
                        f"Record {rid} decoded real pixel counts {decoded_counts} != manifest {manifest_counts}."
                    )
                provenance_checks.append(
                    {"id": rid, "source": source_name, "decoded_counts": decoded_counts}
                )

    shared_class_weights = compute_inverse_sqrt_class_weights(total_train_counts)
    if (
        len(shared_class_weights) != NUM_SEMANTIC_CLASSES
        or not all(math.isfinite(w) and 0.5 <= w <= 3.0 for w in shared_class_weights)
    ):
        raise ValueError(f"Invalid computed shared_class_weights: {shared_class_weights}")

    return {
        "manifest_path": str(manifest_path),
        "manifest_sha256": manifest_sha256,
        "split_record_counts": split_counts,
        "group_disjoint_verified": True,
        "image_hash_disjoint_verified": True,
        "test_split_excluded_from_training_and_validation": True,
        "pseudo_labels_enabled": False,
        "real_training_class_pixel_counts_by_source": {
            source: dict(zip(CLASS_NAMES, counts))
            for source, counts in counts_by_source.items()
        },
        "real_training_class_pixel_counts_total": dict(zip(CLASS_NAMES, total_train_counts)),
        "real_training_class_pixel_counts_vector": total_train_counts,
        "shared_class_weights": shared_class_weights,
        "shared_class_weights_by_name": dict(zip(CLASS_NAMES, shared_class_weights)),
        "provenance_spot_checks": provenance_checks,
    }


def build_manifest_train_catalog(
    manifest_path: Path,
    *,
    raw_manifest: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """Builds an in-memory catalog of eligible train records and split-disjointness sets from manifest JSON."""
    manifest_path = manifest_path.expanduser().resolve()
    raw = dict(raw_manifest) if raw_manifest is not None else json.loads(manifest_path.read_text(encoding="utf-8"))
    records = raw.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("Manifest records must be a non-empty list.")

    expected_class_keys = set(CLASS_NAMES)
    non_train_ids: Set[str] = set()
    non_train_groups: Set[str] = set()
    non_train_hashes: Set[str] = set()
    train_groups: Set[str] = set()
    train_hashes: Set[str] = set()

    manifest_order_by_source: Dict[str, List[Dict[str, Any]]] = {
        "cityscapes": [],
        "surface": [],
    }

    for rec_idx, rec in enumerate(records):
        if not isinstance(rec, dict):
            raise ValueError(f"Record {rec_idx} is not a JSON object.")
        rec_id = str(rec.get("id", ""))
        source = str(rec.get("source", ""))
        split = str(rec.get("split", ""))
        group_id = str(rec.get("group_id", ""))
        image_sha = str(rec.get("image_sha256", ""))
        if source not in ("cityscapes", "surface") or split not in ("train", "val", "test"):
            raise ValueError(f"Record {rec_id!r} has invalid source={source!r} or split={split!r}.")

        if split != "train":
            non_train_ids.add(rec_id)
            non_train_groups.add(group_id)
            non_train_hashes.add(image_sha)
            continue

        train_groups.add(group_id)
        train_hashes.add(image_sha)
        px_map = rec.get("class_pixel_counts")
        if not isinstance(px_map, dict) or set(px_map.keys()) != expected_class_keys:
            raise ValueError(f"Train record {rec_id!r} has malformed class_pixel_counts.")

        px_dict: Dict[str, int] = {}
        px_vec: List[int] = []
        for c_name in CLASS_NAMES:
            val = px_map[c_name]
            if isinstance(val, bool) or not isinstance(val, int) or val < 0:
                raise ValueError(
                    f"Train record {rec_id!r} class_pixel_counts[{c_name!r}] must be a non-negative int."
                )
            px_dict[c_name] = int(val)
            px_vec.append(int(val))

        src_idx = len(manifest_order_by_source[source])
        entry: Dict[str, Any] = {
            "id": rec_id,
            "source": source,
            "split": "train",
            "group_id": group_id,
            "image_sha256": image_sha,
            "semantic_path": str(rec.get("semantic_path", "")),
            "semantic_source_path": str(rec.get("semantic_source_path", "")),
            "semantic_weight_path": str(rec.get("semantic_weight_path", "")),
            "class_pixel_counts": px_dict,
            "class_pixel_counts_vector": px_vec,
            "manifest_record_index": rec_idx,
            "manifest_source_index": src_idx,
            "sorted_pool_rank": -1,
            "crosswalk_pool_rank": None,
        }
        manifest_order_by_source[source].append(entry)

    if train_groups & non_train_groups:
        overlap = sorted(train_groups & non_train_groups)[:5]
        raise ValueError(f"Train/val-test group_id overlap detected: {overlap}")
    if train_hashes & non_train_hashes:
        overlap = sorted(train_hashes & non_train_hashes)[:5]
        raise ValueError(f"Train/val-test image_sha256 overlap detected: {overlap}")

    sorted_by_source: Dict[str, List[Dict[str, Any]]] = {}
    train_records_by_id: Dict[str, Dict[str, Any]] = {}
    pool_summary: Dict[str, Any] = {}

    for source in ("cityscapes", "surface"):
        src_list = manifest_order_by_source[source]
        sorted_list = sorted(src_list, key=lambda r: r["id"])
        cw_rank = 0
        cw_groups: Set[str] = set()
        all_groups: Set[str] = set()
        cw_total_px = 0

        for rank_idx, item in enumerate(sorted_list):
            item["sorted_pool_rank"] = rank_idx
            all_groups.add(item["group_id"])
            cw_px = item["class_pixel_counts"][CROSSWALK_CLASS_NAME]
            if cw_px > 0:
                item["crosswalk_pool_rank"] = cw_rank
                cw_rank += 1
                cw_groups.add(item["group_id"])
                cw_total_px += cw_px
            train_records_by_id[item["id"]] = item

        sorted_by_source[source] = sorted_list
        pool_summary[source] = {
            "eligible_record_count": len(sorted_list),
            "eligible_group_count": len(all_groups),
            "crosswalk_positive_record_count": cw_rank,
            "crosswalk_positive_group_count": len(cw_groups),
            "crosswalk_total_pixels": cw_total_px,
            "first_manifest_id": src_list[0]["id"] if src_list else None,
            "last_manifest_id": src_list[-1]["id"] if src_list else None,
            "first_sorted_id": sorted_list[0]["id"] if sorted_list else None,
            "last_sorted_id": sorted_list[-1]["id"] if sorted_list else None,
        }

    return {
        "manifest_path": str(manifest_path),
        "manifest_dir": str(manifest_path.parent),
        "train_records_by_id": train_records_by_id,
        "manifest_order_by_source": manifest_order_by_source,
        "sorted_train_records_by_source": sorted_by_source,
        "non_train_ids": non_train_ids,
        "non_train_groups": non_train_groups,
        "non_train_hashes": non_train_hashes,
        "pool_summary": pool_summary,
    }


def verify_converted_masks_against_manifest(
    manifest_path: Path,
    catalog: Mapping[str, Any],
    record_ids: Sequence[str],
    *,
    cache: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Decodes converted .semantic.png masks for selected record IDs and verifies exact match with manifest counts."""
    manifest_dir = manifest_path.expanduser().resolve().parent
    train_by_id: Mapping[str, Mapping[str, Any]] = catalog["train_records_by_id"]

    unique_ids: List[str] = []
    seen: Set[str] = set()
    for rid in record_ids:
        s_rid = str(rid)
        if s_rid not in seen:
            seen.add(s_rid)
            unique_ids.append(s_rid)

    total_vec = [0] * NUM_SEMANTIC_CLASSES
    digest_hasher = hashlib.sha256()
    sample_verified: List[Dict[str, Any]] = []

    for rid in unique_ids:
        if rid not in train_by_id:
            raise ValueError(f"Cannot verify mask for non-train or unknown record ID {rid!r}.")
        rec = train_by_id[rid]
        if cache is not None and rid in cache:
            decoded_vec = list(cache[rid]["decoded_counts"])
        else:
            sem_rel = rec.get("semantic_path")
            if not sem_rel:
                raise ValueError(f"Record {rid!r} has no semantic_path in manifest.")
            sem_path = (manifest_dir / str(sem_rel)).resolve()
            if not sem_path.is_file():
                raise FileNotFoundError(f"Converted semantic mask missing for {rid!r}: {sem_path}")
            with Image.open(sem_path) as sem_img:
                sem_arr = np.asarray(sem_img)
            if sem_arr.dtype != np.uint8 or sem_arr.ndim != 2:
                raise ValueError(f"Invalid semantic mask array shape/dtype for {rid!r}: {sem_arr.shape}, {sem_arr.dtype}")
            valid_sem = sem_arr[sem_arr != IGNORE_LABEL]
            if np.any((valid_sem < 0) | (valid_sem >= NUM_SEMANTIC_CLASSES)):
                raise ValueError(f"Out-of-range semantic class in mask for {rid!r}.")
            decoded_vec = np.bincount(valid_sem, minlength=NUM_SEMANTIC_CLASSES).tolist()
            if cache is not None:
                cache[rid] = {"id": rid, "decoded_counts": list(decoded_vec)}

        expected_vec = list(rec["class_pixel_counts_vector"])
        if decoded_vec != expected_vec:
            raise ValueError(
                f"Converted mask pixel counts mismatch for {rid!r}: decoded {decoded_vec} != manifest {expected_vec}."
            )
        for c_idx in range(NUM_SEMANTIC_CLASSES):
            total_vec[c_idx] += int(decoded_vec[c_idx])
        digest_hasher.update(
            f"{rid}|cw={decoded_vec[CROSSWALK_CLASS_INDEX]}|vec={','.join(str(x) for x in decoded_vec)}\n".encode(
                "utf-8"
            )
        )
        if len(sample_verified) < 10:
            sample_verified.append(
                {
                    "id": rid,
                    "source": rec["source"],
                    "group_id": rec["group_id"],
                    "manifest_source_index": rec["manifest_source_index"],
                    "sorted_pool_rank": rec["sorted_pool_rank"],
                    "crosswalk_pool_rank": rec["crosswalk_pool_rank"],
                    "manifest_crosswalk_pixels": int(expected_vec[CROSSWALK_CLASS_INDEX]),
                    "decoded_mask_crosswalk_pixels": int(decoded_vec[CROSSWALK_CLASS_INDEX]),
                }
            )

    return {
        "verified_unique_mask_count": len(unique_ids),
        "all_verified_masks_match_manifest": True,
        "verified_total_pixels_by_class": dict(zip(CLASS_NAMES, total_vec)),
        "verified_crosswalk_pixels_sum": int(total_vec[CROSSWALK_CLASS_INDEX]),
        "verified_records_sha256": digest_hasher.hexdigest(),
        "verified_record_ids": unique_ids,
        "sample_verified_records": sample_verified,
    }


def _compute_quartile_counts(ranks: Sequence[int], pool_size: int) -> List[int]:
    if pool_size <= 0 or not ranks:
        return [0, 0, 0, 0]
    counts = [0, 0, 0, 0]
    for r in ranks:
        q = min(3, max(0, int((r * 4) // pool_size)))
        counts[q] += 1
    return counts


def _summarize_rank_distribution(
    sorted_ranks: Sequence[int],
    manifest_indices: Sequence[int],
    pool_size: int,
    presentations_count: int,
) -> Dict[str, Any]:
    if not sorted_ranks or pool_size <= 0:
        return {
            "pool_size": pool_size,
            "presentations": 0,
            "sorted_pool_rank_min": None,
            "sorted_pool_rank_max": None,
            "sorted_pool_rank_median": None,
            "manifest_source_index_min": None,
            "manifest_source_index_max": None,
            "manifest_source_index_median": None,
            "sorted_pool_quartile_counts": [0, 0, 0, 0],
            "manifest_order_quartile_counts": [0, 0, 0, 0],
            "sampled_beyond_sorted_prefix_count": 0,
            "sampled_beyond_manifest_prefix_count": 0,
        }
    s_sorted = sorted(int(x) for x in sorted_ranks)
    m_sorted = sorted(int(x) for x in manifest_indices)
    mid = len(s_sorted) // 2
    s_med = (
        float(s_sorted[mid])
        if len(s_sorted) % 2 == 1
        else 0.5 * (s_sorted[mid - 1] + s_sorted[mid])
    )
    m_med = (
        float(m_sorted[mid])
        if len(m_sorted) % 2 == 1
        else 0.5 * (m_sorted[mid - 1] + m_sorted[mid])
    )
    return {
        "pool_size": pool_size,
        "presentations": presentations_count,
        "sorted_pool_rank_min": s_sorted[0],
        "sorted_pool_rank_max": s_sorted[-1],
        "sorted_pool_rank_median": s_med,
        "manifest_source_index_min": m_sorted[0],
        "manifest_source_index_max": m_sorted[-1],
        "manifest_source_index_median": m_med,
        "sorted_pool_quartile_counts": _compute_quartile_counts(s_sorted, pool_size),
        "manifest_order_quartile_counts": _compute_quartile_counts(m_sorted, pool_size),
        "sampled_beyond_sorted_prefix_count": sum(1 for x in s_sorted if x >= presentations_count),
        "sampled_beyond_manifest_prefix_count": sum(1 for x in m_sorted if x >= presentations_count),
    }


def compute_batch_post_augmentation_exposure(batch: Mapping[str, Any]) -> Dict[str, Any]:
    """Computes per-sample and batch-total post-augmentation class pixel counts using semantic_source==1 and weight>0."""
    sem_batch = np.asarray(batch["semantic"], dtype=np.int32)
    src_batch = np.asarray(batch["semantic_source"], dtype=np.uint8)
    wt_batch = np.asarray(batch["semantic_weight"], dtype=np.float32)

    if sem_batch.ndim != 3 or src_batch.shape != sem_batch.shape or wt_batch.shape != sem_batch.shape:
        raise ValueError(
            f"Batch semantic/source/weight shapes must match (B, H, W), got "
            f"{sem_batch.shape}, {src_batch.shape}, {wt_batch.shape}."
        )
    if np.any(~np.isin(src_batch, (0, 1, 2))):
        raise ValueError("Batch semantic_source contains unknown provenance values outside (0, 1, 2).")

    batch_size = int(sem_batch.shape[0])
    per_sample: List[Dict[str, Any]] = []
    batch_pixels_vec = [0] * NUM_SEMANTIC_CLASSES
    batch_pos_images_vec = [0] * NUM_SEMANTIC_CLASSES
    batch_real_pixels = 0
    batch_ignored_pixels = 0
    batch_pseudo_pixels = 0

    for idx in range(batch_size):
        sem_i = sem_batch[idx]
        src_i = src_batch[idx]
        wt_i = wt_batch[idx]

        real_mask = (src_i == 1) & (wt_i > 0.0)
        pseudo_mask = (src_i == 2) & (wt_i > 0.0)
        ignored_mask = ~real_mask & ~pseudo_mask

        if np.any(sem_i[real_mask] == IGNORE_LABEL):
            raise ValueError("Real supervised pixel (semantic_source==1 and weight>0) has IGNORE_LABEL (255).")
        if np.any((sem_i[real_mask] < 0) | (sem_i[real_mask] >= NUM_SEMANTIC_CLASSES)):
            raise ValueError("Real supervised pixel has out-of-range semantic class ID.")

        counts_vec = np.bincount(sem_i[real_mask], minlength=NUM_SEMANTIC_CLASSES).tolist()
        real_px = int(sum(counts_vec))
        pseudo_px = int(np.sum(pseudo_mask))
        ignored_px = int(np.sum(ignored_mask))

        for c_idx in range(NUM_SEMANTIC_CLASSES):
            px_val = int(counts_vec[c_idx])
            batch_pixels_vec[c_idx] += px_val
            if px_val > 0:
                batch_pos_images_vec[c_idx] += 1

        batch_real_pixels += real_px
        batch_ignored_pixels += ignored_px
        batch_pseudo_pixels += pseudo_px

        per_sample.append(
            {
                "class_pixel_counts_vector": [int(x) for x in counts_vec],
                "class_pixel_counts": dict(zip(CLASS_NAMES, (int(x) for x in counts_vec))),
                "crosswalk_pixels": int(counts_vec[CROSSWALK_CLASS_INDEX]),
                "real_supervised_pixels": real_px,
                "ignored_pixels": ignored_px,
                "pseudo_pixels": pseudo_px,
            }
        )

    return {
        "per_sample": per_sample,
        "batch_class_pixel_counts_vector": batch_pixels_vec,
        "batch_class_pixel_counts": dict(zip(CLASS_NAMES, batch_pixels_vec)),
        "batch_class_positive_image_counts": dict(zip(CLASS_NAMES, batch_pos_images_vec)),
        "batch_crosswalk_pixels": int(batch_pixels_vec[CROSSWALK_CLASS_INDEX]),
        "batch_crosswalk_positive_images": int(batch_pos_images_vec[CROSSWALK_CLASS_INDEX]),
        "batch_real_supervised_pixels": batch_real_pixels,
        "batch_ignored_pixels": batch_ignored_pixels,
        "batch_pseudo_pixels": batch_pseudo_pixels,
    }


def build_experiment_datasets(
    manifest_path: Path,
    seed: int = DEFAULT_SEED,
    sampling_seed: Optional[int] = None,
) -> Dict[str, PortraitDataset]:
    """Constructs the four train/val per-source PortraitDataset objects once (pseudo OFF, test excluded).

    Train datasets use effective sampling_seed (defaulting to seed when omitted); validation datasets use
    model seed so validation batch ordering remains invariant across sampling-seed experiments.
    """
    manifest_path = manifest_path.expanduser().resolve()
    effective_sampling_seed = resolve_sampling_seed(seed, sampling_seed)
    datasets = {
        "city_train": PortraitDataset(
            manifest_path,
            split="train",
            sources="cityscapes",
            pseudo_manifest_path=None,
            seed=effective_sampling_seed,
            require_complete=True,
        ),
        "surface_train": PortraitDataset(
            manifest_path,
            split="train",
            sources="surface",
            pseudo_manifest_path=None,
            seed=effective_sampling_seed,
            require_complete=True,
        ),
        "city_val": PortraitDataset(
            manifest_path,
            split="val",
            sources="cityscapes",
            pseudo_manifest_path=None,
            seed=seed,
            require_complete=True,
        ),
        "surface_val": PortraitDataset(
            manifest_path,
            split="val",
            sources="surface",
            pseudo_manifest_path=None,
            seed=seed,
            require_complete=True,
        ),
    }
    if not datasets["city_train"].training_eligible or not datasets["surface_train"].training_eligible:
        raise ValueError("Train datasets must be complete and training_eligible.")
    return datasets


def _slice_batch(batch: Mapping[str, Any], start: int, end: int) -> Dict[str, Any]:
    return {key: val[start:end] for key, val in batch.items()}


def _concat_batches(first: Mapping[str, Any], second: Mapping[str, Any]) -> Dict[str, Any]:
    merged: Dict[str, Any] = {}
    for key in first:
        if isinstance(first[key], np.ndarray):
            merged[key] = np.concatenate([first[key], second[key]], axis=0)
        else:
            merged[key] = list(first[key]) + list(second[key])
    return merged


def iter_source_microbatches(
    dataset: PortraitDataset,
    batch_size: int,
    *,
    start_epoch: int,
    augment: bool,
    stats_out: Optional[Dict[str, Any]] = None,
) -> Iterator[Dict[str, Any]]:
    """Streams fixed-size microbatches from dataset.iter_batches, reshuffling with epoch+1 on exhaustion."""
    if batch_size < 1:
        raise ValueError("batch_size must be >= 1.")
    epoch = start_epoch
    carry: Optional[Dict[str, Any]] = None
    if stats_out is not None:
        stats_out["start_epoch"] = start_epoch
        stats_out["epochs_started"] = 0
        stats_out["reshuffles_on_exhaustion"] = 0
        stats_out["last_epoch"] = start_epoch

    while True:
        if stats_out is not None:
            if stats_out["epochs_started"] > 0:
                stats_out["reshuffles_on_exhaustion"] += 1
            stats_out["epochs_started"] += 1
            stats_out["last_epoch"] = epoch

        for batch in dataset.iter_batches(
            batch_size=batch_size,
            epoch=epoch,
            augment=augment,
            balanced=False,
        ):
            current = batch if carry is None else _concat_batches(carry, batch)
            carry = None
            while len(current["id"]) >= batch_size:
                yield _slice_batch(current, 0, batch_size)
                remaining = len(current["id"]) - batch_size
                if remaining > 0:
                    current = _slice_batch(current, batch_size, batch_size + remaining)
                else:
                    current = None  # type: ignore[assignment]
                    break
            if current is not None and len(current["id"]) > 0:
                carry = current
        epoch += 1


def planned_schedule_sources(mode: str, total_microbatches: int) -> List[str]:
    if total_microbatches < 1:
        raise ValueError("total_microbatches must be positive.")
    if mode == "city_only":
        return ["cityscapes"] * total_microbatches
    if mode == "mixed_70_30":
        return [MIXED_SCHEDULE_10[i % len(MIXED_SCHEDULE_10)] for i in range(total_microbatches)]
    raise ValueError(f"Unknown phase schedule mode: {mode!r}")


class PhaseExposureTracker:
    """Tracks ordered sample IDs, pre-augmentation manifest counts, and post-augmentation real supervision."""

    def __init__(
        self,
        *,
        catalog: Mapping[str, Any],
        manifest_path: Path,
        phase_output_dir: Path,
        start_epoch: int,
        accumulate_steps: int,
        mask_verification_cache: Optional[Dict[str, Dict[str, Any]]] = None,
    ) -> None:
        self.catalog = catalog
        self.manifest_path = manifest_path.expanduser().resolve()
        self.phase_output_dir = phase_output_dir
        self.start_epoch = start_epoch
        self.accumulate_steps = accumulate_steps
        self.mask_verification_cache = mask_verification_cache

        self.train_by_id: Mapping[str, Mapping[str, Any]] = catalog["train_records_by_id"]
        self.non_train_ids: Set[str] = set(catalog["non_train_ids"])
        self.non_train_groups: Set[str] = set(catalog["non_train_groups"])
        self.non_train_hashes: Set[str] = set(catalog["non_train_hashes"])
        self.pool_summary: Mapping[str, Any] = catalog["pool_summary"]

        self.ordered_samples_path = phase_output_dir / "ordered_samples.jsonl"
        self.microbatch_exposure_path = phase_output_dir / "microbatch_exposure.jsonl"
        self._samples_fh = self.ordered_samples_path.open("w", encoding="utf-8")
        self._microbatches_fh = self.microbatch_exposure_path.open("w", encoding="utf-8")

        self.ordered_ids_hasher = hashlib.sha256()
        self.exposure_digest_hasher = hashlib.sha256()

        self.total_presentations = 0
        self.total_microbatches = 0
        self.non_train_split_ids_encountered = 0
        self.duplicate_presentations_within_epoch = 0

        self.presentations_by_source: Dict[str, int] = {"surface": 0, "cityscapes": 0}
        self.unique_ids_all: Set[str] = set()
        self.unique_groups_all: Set[str] = set()
        self.unique_ids_by_source: Dict[str, Set[str]] = {"surface": set(), "cityscapes": set()}
        self.unique_groups_by_source: Dict[str, Set[str]] = {"surface": set(), "cityscapes": set()}
        self.seen_in_epoch_by_source: Dict[Tuple[str, int], Set[str]] = {}
        self.epochs_touched_by_source: Dict[str, Set[int]] = {"surface": set(), "cityscapes": set()}
        self.sorted_ranks_by_source: Dict[str, List[int]] = {"surface": [], "cityscapes": []}
        self.manifest_indices_by_source: Dict[str, List[int]] = {"surface": [], "cityscapes": []}
        self.ordered_presentation_records: List[Dict[str, Any]] = []

        self.pre_aug_pixels_vec = [0] * NUM_SEMANTIC_CLASSES
        self.pre_aug_pos_presentations_vec = [0] * NUM_SEMANTIC_CLASSES
        self.pre_aug_pos_unique_ids: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
        self.pre_aug_pos_unique_groups: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
        self.pre_aug_pos_microbatches_vec = [0] * NUM_SEMANTIC_CLASSES

        self.post_aug_pixels_vec = [0] * NUM_SEMANTIC_CLASSES
        self.post_aug_pos_presentations_vec = [0] * NUM_SEMANTIC_CLASSES
        self.post_aug_pos_unique_ids: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
        self.post_aug_pos_unique_groups: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
        self.post_aug_pos_microbatches_vec = [0] * NUM_SEMANTIC_CLASSES
        self.post_aug_real_supervised_pixels = 0
        self.post_aug_ignored_pixels = 0
        self.post_aug_pseudo_pixels = 0

        self.cw_selected_unique_ids_ordered: List[str] = []
        self.cw_selected_unique_ids_set: Set[str] = set()
        self.cw_surface_sorted_ranks: List[int] = []
        self.cw_surface_manifest_indices: List[int] = []
        self.cw_pool_ranks: List[int] = []
        self.cw_presentations_zeroed_by_aug = 0

    def update(self, microbatch_index: int, batch: Mapping[str, Any]) -> None:
        ids = [str(x) for x in batch["id"]]
        sources = [str(x) for x in batch["source"]]
        splits = [str(x) for x in batch["split"]]
        group_ids = [str(x) for x in batch["group_id"]]
        update_index = microbatch_index // self.accumulate_steps

        post_batch = compute_batch_post_augmentation_exposure(batch)
        per_sample_post = post_batch["per_sample"]

        mb_pre_vec = [0] * NUM_SEMANTIC_CLASSES
        mb_pre_pos_imgs = [0] * NUM_SEMANTIC_CLASSES
        mb_post_vec = list(post_batch["batch_class_pixel_counts_vector"])
        mb_post_pos_imgs = [
            int(post_batch["batch_class_positive_image_counts"][c_name]) for c_name in CLASS_NAMES
        ]

        for slot_idx, rid in enumerate(ids):
            src = sources[slot_idx]
            sp = splits[slot_idx]
            gid = group_ids[slot_idx]

            if sp != "train" or rid in self.non_train_ids or gid in self.non_train_groups:
                self.non_train_split_ids_encountered += 1
                raise RuntimeError(
                    f"Non-train record or group encountered in training stream: id={rid!r}, split={sp!r}, group={gid!r}."
                )
            if rid not in self.train_by_id:
                raise RuntimeError(f"Training record ID {rid!r} not found in manifest train catalog.")

            rec = self.train_by_id[rid]
            if rec["image_sha256"] in self.non_train_hashes:
                self.non_train_split_ids_encountered += 1
                raise RuntimeError(f"Training record {rid!r} shares image_sha256 with val/test split.")
            if rec["source"] != src or rec["group_id"] != gid:
                raise RuntimeError(f"Record {rid!r} metadata mismatch against manifest catalog.")

            pool_size = int(self.pool_summary[src]["eligible_record_count"])
            src_ord = self.presentations_by_source[src]
            sample_epoch = self.start_epoch + (src_ord // pool_size if pool_size > 0 else 0)
            ord_in_epoch = src_ord % pool_size if pool_size > 0 else src_ord

            epoch_set = self.seen_in_epoch_by_source.setdefault((src, sample_epoch), set())
            if rid in epoch_set:
                self.duplicate_presentations_within_epoch += 1
                raise RuntimeError(
                    f"Duplicate sample {rid!r} within source={src!r} epoch={sample_epoch} before exhaustion!"
                )
            epoch_set.add(rid)
            self.epochs_touched_by_source[src].add(sample_epoch)

            self.presentations_by_source[src] += 1
            self.unique_ids_all.add(rid)
            self.unique_groups_all.add(gid)
            self.unique_ids_by_source[src].add(rid)
            self.unique_groups_by_source[src].add(gid)

            sorted_rank = int(rec["sorted_pool_rank"])
            manifest_idx = int(rec["manifest_source_index"])
            cw_rank = rec["crosswalk_pool_rank"]
            self.sorted_ranks_by_source[src].append(sorted_rank)
            self.manifest_indices_by_source[src].append(manifest_idx)

            pre_vec = list(rec["class_pixel_counts_vector"])
            post_sample = per_sample_post[slot_idx]
            post_vec = list(post_sample["class_pixel_counts_vector"])

            for c_idx in range(NUM_SEMANTIC_CLASSES):
                pre_px = int(pre_vec[c_idx])
                post_px = int(post_vec[c_idx])
                self.pre_aug_pixels_vec[c_idx] += pre_px
                mb_pre_vec[c_idx] += pre_px
                if pre_px > 0:
                    self.pre_aug_pos_presentations_vec[c_idx] += 1
                    self.pre_aug_pos_unique_ids[c_idx].add(rid)
                    self.pre_aug_pos_unique_groups[c_idx].add(gid)
                    mb_pre_pos_imgs[c_idx] += 1

                self.post_aug_pixels_vec[c_idx] += post_px
                if post_px > 0:
                    self.post_aug_pos_presentations_vec[c_idx] += 1
                    self.post_aug_pos_unique_ids[c_idx].add(rid)
                    self.post_aug_pos_unique_groups[c_idx].add(gid)

            pre_cw = int(pre_vec[CROSSWALK_CLASS_INDEX])
            post_cw = int(post_vec[CROSSWALK_CLASS_INDEX])
            if pre_cw > 0:
                if rid not in self.cw_selected_unique_ids_set:
                    self.cw_selected_unique_ids_set.add(rid)
                    self.cw_selected_unique_ids_ordered.append(rid)
                if src == "surface":
                    self.cw_surface_sorted_ranks.append(sorted_rank)
                    self.cw_surface_manifest_indices.append(manifest_idx)
                if cw_rank is not None:
                    self.cw_pool_ranks.append(int(cw_rank))
                if post_cw == 0:
                    self.cw_presentations_zeroed_by_aug += 1

            self.post_aug_real_supervised_pixels += int(post_sample["real_supervised_pixels"])
            self.post_aug_ignored_pixels += int(post_sample["ignored_pixels"])
            self.post_aug_pseudo_pixels += int(post_sample["pseudo_pixels"])

            pres_idx = self.total_presentations
            self.total_presentations += 1

            self.ordered_ids_hasher.update(
                f"pres={pres_idx}|mb={microbatch_index}|slot={slot_idx}|epoch={sample_epoch}|id={rid}|src={src}|grp={gid}\n".encode(
                    "utf-8"
                )
            )
            self.exposure_digest_hasher.update(
                f"pres={pres_idx}|id={rid}|src={src}|pre={','.join(str(x) for x in pre_vec)}|post={','.join(str(x) for x in post_vec)}\n".encode(
                    "utf-8"
                )
            )

            sample_row = {
                "presentation_index": pres_idx,
                "update_index": update_index,
                "microbatch_index": microbatch_index,
                "slot_in_microbatch": slot_idx,
                "source_epoch": sample_epoch,
                "ordinal_in_source_epoch": ord_in_epoch,
                "id": rid,
                "source": src,
                "group_id": gid,
                "split": sp,
                "sorted_pool_rank": sorted_rank,
                "manifest_source_index": manifest_idx,
                "crosswalk_pool_rank": cw_rank,
                "pre_augmentation_class_pixel_counts": dict(zip(CLASS_NAMES, pre_vec)),
                "post_augmentation_class_pixel_counts": dict(zip(CLASS_NAMES, post_vec)),
                "pre_aug_crosswalk_pixels": pre_cw,
                "post_aug_crosswalk_pixels": post_cw,
                "post_aug_real_supervised_pixels": int(post_sample["real_supervised_pixels"]),
                "post_aug_ignored_pixels": int(post_sample["ignored_pixels"]),
                "post_aug_pseudo_pixels": int(post_sample["pseudo_pixels"]),
            }
            self._samples_fh.write(json.dumps(sample_row) + "\n")
            self.ordered_presentation_records.append(
                {
                    "id": rid,
                    "source": src,
                    "group_id": gid,
                    "pre_vec": pre_vec,
                    "post_vec": post_vec,
                    "sorted_pool_rank": sorted_rank,
                    "manifest_source_index": manifest_idx,
                    "crosswalk_pool_rank": cw_rank,
                }
            )

        for c_idx in range(NUM_SEMANTIC_CLASSES):
            if mb_pre_pos_imgs[c_idx] > 0:
                self.pre_aug_pos_microbatches_vec[c_idx] += 1
            if mb_post_pos_imgs[c_idx] > 0:
                self.post_aug_pos_microbatches_vec[c_idx] += 1

        mb_row = {
            "microbatch_index": microbatch_index,
            "update_index": update_index,
            "source": sources[0] if len(set(sources)) == 1 else "mixed",
            "ids": ids,
            "group_ids": group_ids,
            "pre_augmentation_class_pixel_counts": dict(zip(CLASS_NAMES, mb_pre_vec)),
            "pre_augmentation_class_positive_image_counts": dict(zip(CLASS_NAMES, mb_pre_pos_imgs)),
            "post_augmentation_class_pixel_counts": dict(zip(CLASS_NAMES, mb_post_vec)),
            "post_augmentation_class_positive_image_counts": dict(zip(CLASS_NAMES, mb_post_pos_imgs)),
            "pre_aug_crosswalk_images": int(mb_pre_pos_imgs[CROSSWALK_CLASS_INDEX]),
            "pre_aug_crosswalk_pixels": int(mb_pre_vec[CROSSWALK_CLASS_INDEX]),
            "post_aug_crosswalk_images": int(mb_post_pos_imgs[CROSSWALK_CLASS_INDEX]),
            "post_aug_crosswalk_pixels": int(mb_post_vec[CROSSWALK_CLASS_INDEX]),
            "post_aug_real_supervised_pixels": int(post_batch["batch_real_supervised_pixels"]),
            "post_aug_ignored_pixels": int(post_batch["batch_ignored_pixels"]),
            "post_aug_pseudo_pixels": int(post_batch["batch_pseudo_pixels"]),
        }
        self._microbatches_fh.write(json.dumps(mb_row) + "\n")
        self.total_microbatches += 1

    def finalize(self) -> Dict[str, Any]:
        if not self._samples_fh.closed:
            self._samples_fh.flush()
            self._samples_fh.close()
        if not self._microbatches_fh.closed:
            self._microbatches_fh.flush()
            self._microbatches_fh.close()

        ordered_samples_sha256 = compute_sha256(self.ordered_samples_path)
        microbatch_exposure_sha256 = compute_sha256(self.microbatch_exposure_path)

        mask_verification = verify_converted_masks_against_manifest(
            self.manifest_path,
            self.catalog,
            self.cw_selected_unique_ids_ordered,
            cache=self.mask_verification_cache,
        )

        surf_pool_size = int(self.pool_summary["surface"]["eligible_record_count"])
        city_pool_size = int(self.pool_summary["cityscapes"]["eligible_record_count"])
        cw_pool_size = int(self.pool_summary["surface"]["crosswalk_positive_record_count"])
        cw_unique_px_sum = int(mask_verification["verified_crosswalk_pixels_sum"])
        pre_cw_total_px = int(self.pre_aug_pixels_vec[CROSSWALK_CLASS_INDEX])
        post_cw_total_px = int(self.post_aug_pixels_vec[CROSSWALK_CLASS_INDEX])

        by_source_summary: Dict[str, Any] = {}
        for src, p_size in (("surface", surf_pool_size), ("cityscapes", city_pool_size)):
            by_source_summary[src] = {
                "eligible_pool_size": p_size,
                "eligible_group_count": int(self.pool_summary[src]["eligible_group_count"]),
                "presentations": self.presentations_by_source[src],
                "unique_sample_ids": len(self.unique_ids_by_source[src]),
                "unique_group_ids": len(self.unique_groups_by_source[src]),
                "epochs_touched": sorted(self.epochs_touched_by_source[src]),
                "rank_distribution": _summarize_rank_distribution(
                    self.sorted_ranks_by_source[src],
                    self.manifest_indices_by_source[src],
                    p_size,
                    self.presentations_by_source[src],
                ),
            }

        cw_idx = CROSSWALK_CLASS_INDEX
        surf_pres = self.presentations_by_source["surface"]
        crosswalk_exposure = {
            "eligible_surface_pool_records": surf_pool_size,
            "eligible_crosswalk_positive_pool_records": cw_pool_size,
            "eligible_crosswalk_positive_pool_groups": int(
                self.pool_summary["surface"]["crosswalk_positive_group_count"]
            ),
            "eligible_crosswalk_positive_pool_pixels": int(
                self.pool_summary["surface"]["crosswalk_total_pixels"]
            ),
            "pre_augmentation": {
                "presentations": int(self.pre_aug_pos_presentations_vec[cw_idx]),
                "unique_images": len(self.pre_aug_pos_unique_ids[cw_idx]),
                "unique_groups": len(self.pre_aug_pos_unique_groups[cw_idx]),
                "microbatches": int(self.pre_aug_pos_microbatches_vec[cw_idx]),
                "total_pixels": pre_cw_total_px,
                "unique_image_pixels": cw_unique_px_sum,
                "surface_sorted_pool_rank_min": (
                    min(self.cw_surface_sorted_ranks) if self.cw_surface_sorted_ranks else None
                ),
                "surface_sorted_pool_rank_max": (
                    max(self.cw_surface_sorted_ranks) if self.cw_surface_sorted_ranks else None
                ),
                "surface_manifest_index_min": (
                    min(self.cw_surface_manifest_indices) if self.cw_surface_manifest_indices else None
                ),
                "surface_manifest_index_max": (
                    max(self.cw_surface_manifest_indices) if self.cw_surface_manifest_indices else None
                ),
                "crosswalk_pool_rank_min": min(self.cw_pool_ranks) if self.cw_pool_ranks else None,
                "crosswalk_pool_rank_max": max(self.cw_pool_ranks) if self.cw_pool_ranks else None,
                "surface_pool_quartile_counts": _compute_quartile_counts(
                    self.cw_surface_sorted_ranks, surf_pool_size
                ),
                "manifest_order_quartile_counts": _compute_quartile_counts(
                    self.cw_surface_manifest_indices, surf_pool_size
                ),
                "sampled_beyond_surface_prefix_count": sum(
                    1 for r in self.cw_surface_manifest_indices if r >= surf_pres
                ),
            },
            "post_augmentation": {
                "presentations": int(self.post_aug_pos_presentations_vec[cw_idx]),
                "unique_images": len(self.post_aug_pos_unique_ids[cw_idx]),
                "unique_groups": len(self.post_aug_pos_unique_groups[cw_idx]),
                "microbatches": int(self.post_aug_pos_microbatches_vec[cw_idx]),
                "total_pixels": post_cw_total_px,
                "presentations_zeroed_by_augmentation": self.cw_presentations_zeroed_by_aug,
                "pixel_retention_ratio_vs_pre_aug": (
                    float(post_cw_total_px) / float(pre_cw_total_px) if pre_cw_total_px > 0 else None
                ),
            },
            "converted_mask_verification": mask_verification,
        }

        return {
            "ordered_samples_jsonl_path": str(self.ordered_samples_path),
            "ordered_samples_jsonl_sha256": ordered_samples_sha256,
            "microbatch_exposure_jsonl_path": str(self.microbatch_exposure_path),
            "microbatch_exposure_jsonl_sha256": microbatch_exposure_sha256,
            "ordered_sample_ids_sha256": self.ordered_ids_hasher.hexdigest(),
            "exposure_digest_sha256": self.exposure_digest_hasher.hexdigest(),
            "total_presentations": self.total_presentations,
            "total_microbatches": self.total_microbatches,
            "unique_sample_ids_total": len(self.unique_ids_all),
            "unique_group_ids_total": len(self.unique_groups_all),
            "duplicate_presentations_within_epoch": self.duplicate_presentations_within_epoch,
            "non_train_split_ids_encountered": self.non_train_split_ids_encountered,
            "by_source": by_source_summary,
            "pre_augmentation_by_class": {
                "pixel_counts": dict(zip(CLASS_NAMES, self.pre_aug_pixels_vec)),
                "positive_presentation_counts": dict(
                    zip(CLASS_NAMES, self.pre_aug_pos_presentations_vec)
                ),
                "positive_unique_image_counts": {
                    c_name: len(self.pre_aug_pos_unique_ids[i])
                    for i, c_name in enumerate(CLASS_NAMES)
                },
                "positive_unique_group_counts": {
                    c_name: len(self.pre_aug_pos_unique_groups[i])
                    for i, c_name in enumerate(CLASS_NAMES)
                },
                "positive_microbatch_counts": dict(
                    zip(CLASS_NAMES, self.pre_aug_pos_microbatches_vec)
                ),
            },
            "post_augmentation_by_class": {
                "pixel_counts": dict(zip(CLASS_NAMES, self.post_aug_pixels_vec)),
                "positive_presentation_counts": dict(
                    zip(CLASS_NAMES, self.post_aug_pos_presentations_vec)
                ),
                "positive_unique_image_counts": {
                    c_name: len(self.post_aug_pos_unique_ids[i])
                    for i, c_name in enumerate(CLASS_NAMES)
                },
                "positive_unique_group_counts": {
                    c_name: len(self.post_aug_pos_unique_groups[i])
                    for i, c_name in enumerate(CLASS_NAMES)
                },
                "positive_microbatch_counts": dict(
                    zip(CLASS_NAMES, self.post_aug_pos_microbatches_vec)
                ),
                "total_real_supervised_pixels": self.post_aug_real_supervised_pixels,
                "total_ignored_pixels": self.post_aug_ignored_pixels,
                "total_pseudo_pixels": self.post_aug_pseudo_pixels,
            },
            "crosswalk_exposure": crosswalk_exposure,
        }


def aggregate_arm_exposure_audit(
    phase1_tracker: PhaseExposureTracker,
    phase2_tracker: PhaseExposureTracker,
    phase1_exposure: Mapping[str, Any],
    phase2_exposure: Mapping[str, Any],
) -> Dict[str, Any]:
    """Combines Phase 1 and Phase 2 exposure tracking into an arm-wide audit with true unique sets."""
    combined_records = (
        phase1_tracker.ordered_presentation_records + phase2_tracker.ordered_presentation_records
    )
    unique_ids_all = {r["id"] for r in combined_records}
    unique_groups_all = {r["group_id"] for r in combined_records}
    unique_ids_by_source = {
        src: {r["id"] for r in combined_records if r["source"] == src}
        for src in ("surface", "cityscapes")
    }
    unique_groups_by_source = {
        src: {r["group_id"] for r in combined_records if r["source"] == src}
        for src in ("surface", "cityscapes")
    }

    pre_px_vec = [0] * NUM_SEMANTIC_CLASSES
    pre_pres_vec = [0] * NUM_SEMANTIC_CLASSES
    pre_uids: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
    pre_ugroups: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]

    post_px_vec = [0] * NUM_SEMANTIC_CLASSES
    post_pres_vec = [0] * NUM_SEMANTIC_CLASSES
    post_uids: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
    post_ugroups: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]

    for r in combined_records:
        rid = r["id"]
        gid = r["group_id"]
        for c_idx in range(NUM_SEMANTIC_CLASSES):
            pre_val = int(r["pre_vec"][c_idx])
            post_val = int(r["post_vec"][c_idx])
            pre_px_vec[c_idx] += pre_val
            if pre_val > 0:
                pre_pres_vec[c_idx] += 1
                pre_uids[c_idx].add(rid)
                pre_ugroups[c_idx].add(gid)
            post_px_vec[c_idx] += post_val
            if post_val > 0:
                post_pres_vec[c_idx] += 1
                post_uids[c_idx].add(rid)
                post_ugroups[c_idx].add(gid)

    cw_idx = CROSSWALK_CLASS_INDEX
    train_by_id = phase1_tracker.train_by_id
    cw_unique_px = sum(
        int(train_by_id[rid]["class_pixel_counts"][CROSSWALK_CLASS_NAME])
        for rid in pre_uids[cw_idx]
    )

    return {
        "total_presentations": len(combined_records),
        "total_microbatches": int(
            phase1_exposure["total_microbatches"] + phase2_exposure["total_microbatches"]
        ),
        "unique_sample_ids_total": len(unique_ids_all),
        "unique_group_ids_total": len(unique_groups_all),
        "unique_sample_ids_by_source": {k: len(v) for k, v in unique_ids_by_source.items()},
        "unique_group_ids_by_source": {k: len(v) for k, v in unique_groups_by_source.items()},
        "duplicate_presentations_within_epoch": int(
            phase1_exposure["duplicate_presentations_within_epoch"]
            + phase2_exposure["duplicate_presentations_within_epoch"]
        ),
        "non_train_split_ids_encountered": int(
            phase1_exposure["non_train_split_ids_encountered"]
            + phase2_exposure["non_train_split_ids_encountered"]
        ),
        "pre_augmentation_by_class": {
            "pixel_counts": dict(zip(CLASS_NAMES, pre_px_vec)),
            "positive_presentation_counts": dict(zip(CLASS_NAMES, pre_pres_vec)),
            "positive_unique_image_counts": {
                c_name: len(pre_uids[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
            "positive_unique_group_counts": {
                c_name: len(pre_ugroups[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
        },
        "post_augmentation_by_class": {
            "pixel_counts": dict(zip(CLASS_NAMES, post_px_vec)),
            "positive_presentation_counts": dict(zip(CLASS_NAMES, post_pres_vec)),
            "positive_unique_image_counts": {
                c_name: len(post_uids[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
            "positive_unique_group_counts": {
                c_name: len(post_ugroups[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
            "total_real_supervised_pixels": int(
                phase1_exposure["post_augmentation_by_class"]["total_real_supervised_pixels"]
                + phase2_exposure["post_augmentation_by_class"]["total_real_supervised_pixels"]
            ),
            "total_ignored_pixels": int(
                phase1_exposure["post_augmentation_by_class"]["total_ignored_pixels"]
                + phase2_exposure["post_augmentation_by_class"]["total_ignored_pixels"]
            ),
            "total_pseudo_pixels": int(
                phase1_exposure["post_augmentation_by_class"]["total_pseudo_pixels"]
                + phase2_exposure["post_augmentation_by_class"]["total_pseudo_pixels"]
            ),
        },
        "crosswalk_exposure": {
            "pre_augmentation": {
                "presentations": int(pre_pres_vec[cw_idx]),
                "unique_images": len(pre_uids[cw_idx]),
                "unique_groups": len(pre_ugroups[cw_idx]),
                "microbatches": int(
                    phase1_exposure["crosswalk_exposure"]["pre_augmentation"]["microbatches"]
                    + phase2_exposure["crosswalk_exposure"]["pre_augmentation"]["microbatches"]
                ),
                "total_pixels": int(pre_px_vec[cw_idx]),
                "unique_image_pixels": cw_unique_px,
            },
            "post_augmentation": {
                "presentations": int(post_pres_vec[cw_idx]),
                "unique_images": len(post_uids[cw_idx]),
                "unique_groups": len(post_ugroups[cw_idx]),
                "microbatches": int(
                    phase1_exposure["crosswalk_exposure"]["post_augmentation"]["microbatches"]
                    + phase2_exposure["crosswalk_exposure"]["post_augmentation"]["microbatches"]
                ),
                "total_pixels": int(post_px_vec[cw_idx]),
                "presentations_zeroed_by_augmentation": int(
                    phase1_exposure["crosswalk_exposure"]["post_augmentation"][
                        "presentations_zeroed_by_augmentation"
                    ]
                    + phase2_exposure["crosswalk_exposure"]["post_augmentation"][
                        "presentations_zeroed_by_augmentation"
                    ]
                ),
                "pixel_retention_ratio_vs_pre_aug": (
                    float(post_px_vec[cw_idx]) / float(pre_px_vec[cw_idx])
                    if pre_px_vec[cw_idx] > 0
                    else None
                ),
            },
        },
    }


def reconstruct_phase_sampling_from_manifest(
    catalog: Mapping[str, Any],
    *,
    sampling_seed: int,
    mode: str,
    updates: int,
    accumulate_steps: int,
    batch_size: int,
    start_epoch: int,
) -> Dict[str, Any]:
    """Reconstructs exact PortraitDataset._ordered_records sampling and pre-augmentation exposure from manifest."""
    _validate_non_negative_seed(sampling_seed, "sampling_seed")
    if updates < 1 or accumulate_steps < 1 or batch_size < 1:
        raise ValueError("updates, accumulate_steps, and batch_size must be positive.")

    total_microbatches = updates * accumulate_steps
    schedule = planned_schedule_sources(mode, total_microbatches)
    sorted_by_source: Mapping[str, Sequence[Mapping[str, Any]]] = catalog[
        "sorted_train_records_by_source"
    ]
    pool_summary: Mapping[str, Any] = catalog["pool_summary"]

    def _source_record_stream(
        source_name: str,
        stats: Dict[str, Any],
    ) -> Iterator[Tuple[int, Mapping[str, Any]]]:
        pool = list(sorted_by_source[source_name])
        if not pool:
            raise ValueError(f"Empty train pool for source={source_name!r}")
        ep = start_epoch
        stats["start_epoch"] = start_epoch
        stats["epochs_started"] = 0
        stats["reshuffles_on_exhaustion"] = 0
        stats["last_epoch"] = start_epoch
        while True:
            if stats["epochs_started"] > 0:
                stats["reshuffles_on_exhaustion"] += 1
            stats["epochs_started"] += 1
            stats["last_epoch"] = ep
            ordered = list(pool)
            random.Random(sampling_seed + ep).shuffle(ordered)
            for rec in ordered:
                yield ep, rec
            ep += 1

    city_stats: Dict[str, Any] = {}
    surf_stats: Dict[str, Any] = {}
    city_gen = _source_record_stream("cityscapes", city_stats)
    surf_gen = _source_record_stream("surface", surf_stats) if mode == "mixed_70_30" else None

    ids_hasher = hashlib.sha256()
    ordered_ids_hasher = hashlib.sha256()

    presentations_by_source = {"surface": 0, "cityscapes": 0}
    microbatches_by_source = {"surface": 0, "cityscapes": 0}
    unique_ids_by_source: Dict[str, Set[str]] = {"surface": set(), "cityscapes": set()}
    unique_groups_by_source: Dict[str, Set[str]] = {"surface": set(), "cityscapes": set()}
    seen_in_epoch: Dict[Tuple[str, int], Set[str]] = {}
    duplicate_within_epoch = 0

    sorted_ranks_by_source: Dict[str, List[int]] = {"surface": [], "cityscapes": []}
    manifest_indices_by_source: Dict[str, List[int]] = {"surface": [], "cityscapes": []}

    pre_px_vec = [0] * NUM_SEMANTIC_CLASSES
    pre_pos_pres_vec = [0] * NUM_SEMANTIC_CLASSES
    pre_pos_uids: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
    pre_pos_ugroups: List[Set[str]] = [set() for _ in range(NUM_SEMANTIC_CLASSES)]
    pre_pos_mbs_vec = [0] * NUM_SEMANTIC_CLASSES

    cw_unique_ids_ordered: List[str] = []
    cw_unique_ids_set: Set[str] = set()
    cw_surface_sorted_ranks: List[int] = []
    cw_surface_manifest_indices: List[int] = []
    cw_pool_ranks: List[int] = []
    ordered_ids_list: List[str] = []

    pres_idx = 0
    for mb_idx, src_name in enumerate(schedule):
        gen = city_gen if src_name == "cityscapes" else surf_gen
        assert gen is not None
        batch_items = [next(gen) for _ in range(batch_size)]
        ids = [str(r["id"]) for _, r in batch_items]
        sources = [str(r["source"]) for _, r in batch_items]
        header = f"step={mb_idx}|ids={','.join(ids)}|sources={','.join(sources)}\n".encode("utf-8")
        ids_hasher.update(header)
        microbatches_by_source[src_name] += 1

        mb_pos = [0] * NUM_SEMANTIC_CLASSES
        for slot_idx, (ep, rec) in enumerate(batch_items):
            rid = str(rec["id"])
            gid = str(rec["group_id"])
            ep_set = seen_in_epoch.setdefault((src_name, ep), set())
            if rid in ep_set:
                duplicate_within_epoch += 1
            ep_set.add(rid)

            presentations_by_source[src_name] += 1
            unique_ids_by_source[src_name].add(rid)
            unique_groups_by_source[src_name].add(gid)
            ordered_ids_list.append(rid)

            s_rank = int(rec["sorted_pool_rank"])
            m_idx = int(rec["manifest_source_index"])
            sorted_ranks_by_source[src_name].append(s_rank)
            manifest_indices_by_source[src_name].append(m_idx)

            vec = rec["class_pixel_counts_vector"]
            for c_idx in range(NUM_SEMANTIC_CLASSES):
                px = int(vec[c_idx])
                pre_px_vec[c_idx] += px
                if px > 0:
                    pre_pos_pres_vec[c_idx] += 1
                    pre_pos_uids[c_idx].add(rid)
                    pre_pos_ugroups[c_idx].add(gid)
                    mb_pos[c_idx] += 1

            cw_px = int(vec[CROSSWALK_CLASS_INDEX])
            if cw_px > 0:
                if rid not in cw_unique_ids_set:
                    cw_unique_ids_set.add(rid)
                    cw_unique_ids_ordered.append(rid)
                if src_name == "surface":
                    cw_surface_sorted_ranks.append(s_rank)
                    cw_surface_manifest_indices.append(m_idx)
                if rec["crosswalk_pool_rank"] is not None:
                    cw_pool_ranks.append(int(rec["crosswalk_pool_rank"]))

            ordered_ids_hasher.update(
                f"pres={pres_idx}|mb={mb_idx}|slot={slot_idx}|epoch={ep}|id={rid}|src={src_name}|grp={gid}\n".encode(
                    "utf-8"
                )
            )
            pres_idx += 1

        for c_idx in range(NUM_SEMANTIC_CLASSES):
            if mb_pos[c_idx] > 0:
                pre_pos_mbs_vec[c_idx] += 1

    train_by_id = catalog["train_records_by_id"]
    cw_unique_px_sum = sum(
        int(train_by_id[rid]["class_pixel_counts"][CROSSWALK_CLASS_NAME])
        for rid in cw_unique_ids_ordered
    )
    surf_pool_size = int(pool_summary["surface"]["eligible_record_count"])
    city_pool_size = int(pool_summary["cityscapes"]["eligible_record_count"])
    surf_pres = presentations_by_source["surface"]
    cw_idx = CROSSWALK_CLASS_INDEX

    return {
        "sampling_seed": sampling_seed,
        "schedule_mode": mode,
        "start_epoch": start_epoch,
        "effective_rng_seed": sampling_seed + start_epoch,
        "updates": updates,
        "accumulate_steps": accumulate_steps,
        "batch_size": batch_size,
        "total_microbatches": total_microbatches,
        "total_presentations": pres_idx,
        "ids_sha256": ids_hasher.hexdigest(),
        "ordered_sample_ids_sha256": ordered_ids_hasher.hexdigest(),
        "duplicate_presentations_within_epoch": duplicate_within_epoch,
        "microbatches_by_source": microbatches_by_source,
        "presentations_by_source": presentations_by_source,
        "unique_sample_ids_by_source": {k: len(v) for k, v in unique_ids_by_source.items()},
        "unique_group_ids_by_source": {k: len(v) for k, v in unique_groups_by_source.items()},
        "stream_exhaustion_stats": {
            "cityscapes": city_stats,
            "surface": surf_stats if mode == "mixed_70_30" else None,
        },
        "source_rank_distribution": {
            "surface": _summarize_rank_distribution(
                sorted_ranks_by_source["surface"],
                manifest_indices_by_source["surface"],
                surf_pool_size,
                presentations_by_source["surface"],
            ),
            "cityscapes": _summarize_rank_distribution(
                sorted_ranks_by_source["cityscapes"],
                manifest_indices_by_source["cityscapes"],
                city_pool_size,
                presentations_by_source["cityscapes"],
            ),
        },
        "pre_augmentation_by_class": {
            "pixel_counts": dict(zip(CLASS_NAMES, pre_px_vec)),
            "positive_presentation_counts": dict(zip(CLASS_NAMES, pre_pos_pres_vec)),
            "positive_unique_image_counts": {
                c_name: len(pre_pos_uids[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
            "positive_unique_group_counts": {
                c_name: len(pre_pos_ugroups[i]) for i, c_name in enumerate(CLASS_NAMES)
            },
            "positive_microbatch_counts": dict(zip(CLASS_NAMES, pre_pos_mbs_vec)),
        },
        "crosswalk_pre_augmentation": {
            "presentations": int(pre_pos_pres_vec[cw_idx]),
            "unique_images": len(cw_unique_ids_ordered),
            "unique_groups": len(pre_pos_ugroups[cw_idx]),
            "microbatches": int(pre_pos_mbs_vec[cw_idx]),
            "total_pixels": int(pre_px_vec[cw_idx]),
            "unique_image_pixels": cw_unique_px_sum,
            "selected_unique_ids": cw_unique_ids_ordered,
            "surface_sorted_pool_rank_min": (
                min(cw_surface_sorted_ranks) if cw_surface_sorted_ranks else None
            ),
            "surface_sorted_pool_rank_max": (
                max(cw_surface_sorted_ranks) if cw_surface_sorted_ranks else None
            ),
            "surface_manifest_index_min": (
                min(cw_surface_manifest_indices) if cw_surface_manifest_indices else None
            ),
            "surface_manifest_index_max": (
                max(cw_surface_manifest_indices) if cw_surface_manifest_indices else None
            ),
            "crosswalk_pool_rank_min": min(cw_pool_ranks) if cw_pool_ranks else None,
            "crosswalk_pool_rank_max": max(cw_pool_ranks) if cw_pool_ranks else None,
            "surface_pool_quartile_counts": _compute_quartile_counts(
                cw_surface_sorted_ranks, surf_pool_size
            ),
            "manifest_order_quartile_counts": _compute_quartile_counts(
                cw_surface_manifest_indices, surf_pool_size
            ),
            "sampled_beyond_surface_prefix_count": sum(
                1 for r in cw_surface_manifest_indices if r >= surf_pres
            ),
        },
        "_surface_unique_ids_set": unique_ids_by_source["surface"],
        "_ordered_ids_list": ordered_ids_list,
    }


def _strip_internal_keys(d: Mapping[str, Any]) -> Dict[str, Any]:
    return {k: v for k, v in d.items() if not k.startswith("_")}


def audit_and_compare_sampling_seeds(
    manifest_path: Path,
    *,
    old_sampling_seed: int = DEFAULT_SEED,
    new_sampling_seed: int = DEFAULT_SAMPLING_SEED_RERUN,
    phase1_updates: int = DEFAULT_PHASE1_UPDATES,
    phase2_updates: int = DEFAULT_PHASE2_UPDATES,
    accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS,
    batch_size: int = DEFAULT_BATCH_SIZE,
    verify_converted_masks: bool = True,
    catalog: Optional[Mapping[str, Any]] = None,
    mask_verification_cache: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Reconstructs and compares old (seed 42) and new (sampling_seed 20261031) pre-augmentation sampling orders."""
    manifest_path = manifest_path.expanduser().resolve()
    cat = dict(catalog) if catalog is not None else build_manifest_train_catalog(manifest_path)

    def _reconstruct_seed(s_seed: int) -> Dict[str, Any]:
        a_p1 = reconstruct_phase_sampling_from_manifest(
            cat,
            sampling_seed=s_seed,
            mode="city_only",
            updates=phase1_updates,
            accumulate_steps=accumulate_steps,
            batch_size=batch_size,
            start_epoch=PHASE1_BASE_EPOCH,
        )
        b_p1 = reconstruct_phase_sampling_from_manifest(
            cat,
            sampling_seed=s_seed,
            mode="mixed_70_30",
            updates=phase1_updates,
            accumulate_steps=accumulate_steps,
            batch_size=batch_size,
            start_epoch=PHASE1_BASE_EPOCH,
        )
        common_p2 = reconstruct_phase_sampling_from_manifest(
            cat,
            sampling_seed=s_seed,
            mode="mixed_70_30",
            updates=phase2_updates,
            accumulate_steps=accumulate_steps,
            batch_size=batch_size,
            start_epoch=PHASE2_BASE_EPOCH,
        )
        b_cw_union_ids = list(
            dict.fromkeys(
                b_p1["crosswalk_pre_augmentation"]["selected_unique_ids"]
                + common_p2["crosswalk_pre_augmentation"]["selected_unique_ids"]
            )
        )
        train_by_id = cat["train_records_by_id"]
        b_cw_union_groups = {train_by_id[rid]["group_id"] for rid in b_cw_union_ids}
        b_cw_union_px = sum(
            int(train_by_id[rid]["class_pixel_counts"][CROSSWALK_CLASS_NAME])
            for rid in b_cw_union_ids
        )
        b_surf_union_ids = b_p1["_surface_unique_ids_set"] | common_p2["_surface_unique_ids_set"]

        return {
            "sampling_seed": s_seed,
            "arm_a_phase1_city_only": _strip_internal_keys(a_p1),
            "arm_b_phase1_mixed": _strip_internal_keys(b_p1),
            "common_phase2_mixed": _strip_internal_keys(common_p2),
            "arm_a_combined_pre_augmentation": {
                "surface_presentations": common_p2["presentations_by_source"]["surface"],
                "surface_unique_images": common_p2["unique_sample_ids_by_source"]["surface"],
                "crosswalk_presentations": common_p2["crosswalk_pre_augmentation"]["presentations"],
                "crosswalk_unique_images": common_p2["crosswalk_pre_augmentation"]["unique_images"],
                "crosswalk_unique_groups": common_p2["crosswalk_pre_augmentation"]["unique_groups"],
                "crosswalk_total_pre_aug_pixels": common_p2["crosswalk_pre_augmentation"][
                    "total_pixels"
                ],
                "crosswalk_unique_image_pixels": common_p2["crosswalk_pre_augmentation"][
                    "unique_image_pixels"
                ],
            },
            "arm_b_combined_pre_augmentation": {
                "surface_presentations": (
                    b_p1["presentations_by_source"]["surface"]
                    + common_p2["presentations_by_source"]["surface"]
                ),
                "surface_unique_images": len(b_surf_union_ids),
                "crosswalk_presentations": (
                    b_p1["crosswalk_pre_augmentation"]["presentations"]
                    + common_p2["crosswalk_pre_augmentation"]["presentations"]
                ),
                "crosswalk_unique_images": len(b_cw_union_ids),
                "crosswalk_unique_groups": len(b_cw_union_groups),
                "crosswalk_total_pre_aug_pixels": (
                    b_p1["crosswalk_pre_augmentation"]["total_pixels"]
                    + common_p2["crosswalk_pre_augmentation"]["total_pixels"]
                ),
                "crosswalk_unique_image_pixels": b_cw_union_px,
            },
            "_cw_union_ids": b_cw_union_ids,
            "_b_p1_surf_ids": b_p1["_surface_unique_ids_set"],
            "_p2_surf_ids": common_p2["_surface_unique_ids_set"],
        }

    old_rec = _reconstruct_seed(old_sampling_seed)
    new_rec = _reconstruct_seed(new_sampling_seed)

    union_cw_ids = list(dict.fromkeys(old_rec["_cw_union_ids"] + new_rec["_cw_union_ids"]))
    mask_verification = (
        verify_converted_masks_against_manifest(
            manifest_path,
            cat,
            union_cw_ids,
            cache=mask_verification_cache,
        )
        if verify_converted_masks
        else None
    )

    is_prod_default_shape = (
        old_sampling_seed == DEFAULT_SEED
        and phase1_updates == DEFAULT_PHASE1_UPDATES
        and phase2_updates == DEFAULT_PHASE2_UPDATES
        and accumulate_steps == DEFAULT_ACCUMULATE_STEPS
        and batch_size == DEFAULT_BATCH_SIZE
        and cat["pool_summary"]["surface"]["eligible_record_count"]
        == EXPECTED_SPLIT_COUNTS["surface_train"]
        and cat["pool_summary"]["cityscapes"]["eligible_record_count"]
        == EXPECTED_SPLIT_COUNTS["cityscapes_train"]
    )
    prior_ids_digest_verified = False
    if is_prod_default_shape:
        prior_ids_digest_verified = (
            old_rec["arm_a_phase1_city_only"]["ids_sha256"]
            == PRIOR_SEED42_PHASE_IDS_SHA256["arm_a_phase1"]
            and old_rec["arm_b_phase1_mixed"]["ids_sha256"]
            == PRIOR_SEED42_PHASE_IDS_SHA256["arm_b_phase1"]
            and old_rec["common_phase2_mixed"]["ids_sha256"]
            == PRIOR_SEED42_PHASE_IDS_SHA256["phase2"]
        )
        if not prior_ids_digest_verified:
            raise RuntimeError(
                "Reconstructed seed=42 phase ids_sha256 did not match recorded ARIA-STRATEGY-AB-PILOT-20261031 digests!"
            )

    old_cw_set = set(old_rec["_cw_union_ids"])
    new_cw_set = set(new_rec["_cw_union_ids"])

    return {
        "manifest_path": str(manifest_path),
        "eligible_train_pools": cat["pool_summary"],
        "sampler_contract": {
            "algorithm": (
                "PortraitDataset._ordered_records: sort full source train pool by record_id, "
                "then random.Random(sampling_seed + epoch).shuffle(ordered) without replacement"
            ),
            "epoch_exhaustion_policy": (
                "Increment epoch by +1 and reshuffle full source train pool with "
                "random.Random(sampling_seed + epoch)"
            ),
            "sequential_manifest_prefix_used": False,
            "stratified_or_label_biased_oversampling_used": False,
            "augmentation_rng_note": (
                "PortraitDataset.iter_samples uses random.Random(dataset.seed + epoch) for both "
                "record ordering and sample augmentation; changing sampling_seed changes both sample "
                "order and augmentation RNG relative to old seed=42 while keeping Phase 2 matched "
                "across Arm A and Arm B."
            ),
            "old_run_reconstruction_scope": (
                "Manifest and converted-mask pre-augmentation reconstruction only (cryptographically "
                "matched to old GPU run via phase input_digest.ids_sha256); old post-augmentation "
                "tensors were not logged as per-class pixel counts during the prior run and are not claimed here."
            ),
        },
        "old_seed_audit": _strip_internal_keys(old_rec),
        "new_seed_audit": _strip_internal_keys(new_rec),
        "old_run_recorded_ids_sha256_match_verified": prior_ids_digest_verified,
        "seed_comparison": {
            "old_sampling_seed": old_sampling_seed,
            "new_sampling_seed": new_sampling_seed,
            "phase1_arm_a_ids_sha256_changed": (
                old_rec["arm_a_phase1_city_only"]["ids_sha256"]
                != new_rec["arm_a_phase1_city_only"]["ids_sha256"]
            ),
            "phase1_arm_b_ids_sha256_changed": (
                old_rec["arm_b_phase1_mixed"]["ids_sha256"]
                != new_rec["arm_b_phase1_mixed"]["ids_sha256"]
            ),
            "phase2_common_ids_sha256_changed": (
                old_rec["common_phase2_mixed"]["ids_sha256"]
                != new_rec["common_phase2_mixed"]["ids_sha256"]
            ),
            "phase1_surface_overlap_unique_ids": len(
                old_rec["_b_p1_surf_ids"] & new_rec["_b_p1_surf_ids"]
            ),
            "phase2_surface_overlap_unique_ids": len(
                old_rec["_p2_surf_ids"] & new_rec["_p2_surf_ids"]
            ),
            "crosswalk_positive_union_unique_images": len(union_cw_ids),
            "crosswalk_positive_overlap_unique_images": len(old_cw_set & new_cw_set),
        },
        "union_converted_mask_verification": mask_verification,
    }


class PhaseInputDigestTracker:
    """Computes deterministic SHA-256 digests over actual phase inputs (IDs, image, semantic, weight)."""

    def __init__(self) -> None:
        self.combined_hasher = hashlib.sha256()
        self.ids_hasher = hashlib.sha256()
        self.image_hasher = hashlib.sha256()
        self.semantic_hasher = hashlib.sha256()
        self.weight_hasher = hashlib.sha256()
        self.microbatches_hashed = 0
        self.samples_hashed = 0

    def update(self, microbatch_index: int, batch: Mapping[str, Any]) -> None:
        ids = [str(x) for x in batch["id"]]
        sources = [str(x) for x in batch["source"]]
        header = f"step={microbatch_index}|ids={','.join(ids)}|sources={','.join(sources)}\n".encode(
            "utf-8"
        )
        img_bytes = np.ascontiguousarray(batch["image"], dtype=np.float32).tobytes()
        sem_bytes = np.ascontiguousarray(batch["semantic"], dtype=np.int32).tobytes()
        wt_bytes = np.ascontiguousarray(batch["semantic_weight"], dtype=np.float32).tobytes()

        self.ids_hasher.update(header)
        self.image_hasher.update(img_bytes)
        self.semantic_hasher.update(sem_bytes)
        self.weight_hasher.update(wt_bytes)

        self.combined_hasher.update(header)
        self.combined_hasher.update(img_bytes)
        self.combined_hasher.update(sem_bytes)
        self.combined_hasher.update(wt_bytes)

        self.microbatches_hashed += 1
        self.samples_hashed += len(ids)

    def summary(self) -> Dict[str, Any]:
        return {
            "combined_sha256": self.combined_hasher.hexdigest(),
            "ids_sha256": self.ids_hasher.hexdigest(),
            "image_sha256": self.image_hasher.hexdigest(),
            "semantic_sha256": self.semantic_hasher.hexdigest(),
            "semantic_weight_sha256": self.weight_hasher.hexdigest(),
            "microbatches_hashed": self.microbatches_hashed,
            "samples_hashed": self.samples_hashed,
        }


def run_training_phase(
    *,
    model: keras.Model,
    arm_name: str,
    phase_name: str,
    mode: str,
    updates: int,
    learning_rate: float,
    batch_size: int,
    accumulate_steps: int,
    clipnorm: float,
    shared_class_weights: Sequence[float],
    city_train_ds: PortraitDataset,
    surface_train_ds: PortraitDataset,
    start_epoch: int,
    seed: int,
    phase_output_dir: Path,
    log_every_updates: int = 8,
    catalog: Optional[Mapping[str, Any]] = None,
    mask_verification_cache: Optional[Dict[str, Dict[str, Any]]] = None,
    require_crosswalk_in_mixed: bool = True,
    tracker_out: Optional[List[PhaseExposureTracker]] = None,
) -> Dict[str, Any]:
    """Executes one controlled phase with fresh Adam optimizer and exact update/microbatch/exposure accounting."""
    if updates < 1 or batch_size < 1 or accumulate_steps < 1:
        raise ValueError("updates, batch_size, and accumulate_steps must be positive integers.")
    phase_output_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = city_train_ds.manifest_path
    cat = (
        dict(catalog)
        if catalog is not None
        else build_manifest_train_catalog(manifest_path)
    )
    sampling_seed = int(city_train_ds.seed)

    # Reset RNG state identically for the phase start
    phase_seed = seed + (0 if phase_name == "phase1" else 1000)
    set_deterministic_seeds(phase_seed)

    # Fresh StagedTrainer -> fresh Adam optimizer at phase boundary + zeroed accumulator
    trainer = StagedTrainer(
        model=model,
        architecture="model_a",
        stage="city_warmup" if mode == "city_only" else "mixed_decoder",
        learning_rate=learning_rate,
        clipnorm=clipnorm,
        batch_size=batch_size,
        accumulate_steps=accumulate_steps,
        class_weights=shared_class_weights,
        checkpoint_dir=phase_output_dir / "checkpoints",
    )
    initial_opt_iterations = int(trainer.optimizer.iterations.numpy())
    if initial_opt_iterations != 0:
        raise RuntimeError(
            f"Expected fresh Adam optimizer with iterations=0 at {arm_name}/{phase_name} start, "
            f"got {initial_opt_iterations}."
        )

    initial_tensor_hashes = compute_model_weights_tensor_sha256(model)
    total_microbatches = updates * accumulate_steps
    schedule = planned_schedule_sources(mode, total_microbatches)

    city_stream_stats: Dict[str, Any] = {}
    surface_stream_stats: Dict[str, Any] = {}
    city_stream = iter_source_microbatches(
        city_train_ds,
        batch_size=batch_size,
        start_epoch=start_epoch,
        augment=True,
        stats_out=city_stream_stats,
    )
    surface_stream = (
        iter_source_microbatches(
            surface_train_ds,
            batch_size=batch_size,
            start_epoch=start_epoch,
            augment=True,
            stats_out=surface_stream_stats,
        )
        if mode == "mixed_70_30"
        else None
    )

    digest_tracker = PhaseInputDigestTracker()
    exposure_tracker = PhaseExposureTracker(
        catalog=cat,
        manifest_path=manifest_path,
        phase_output_dir=phase_output_dir,
        start_epoch=start_epoch,
        accumulate_steps=accumulate_steps,
        mask_verification_cache=mask_verification_cache,
    )
    if tracker_out is not None:
        tracker_out.append(exposure_tracker)

    microbatches_by_source = {"surface": 0, "cityscapes": 0}
    samples_by_source = {"surface": 0, "cityscapes": 0}
    completed_updates = 0
    microbatch_losses: List[float] = []
    update_losses: List[float] = []
    window_loss_sum = 0.0
    window_sample_count = 0
    started_at = time.time()

    try:
        for micro_idx, source_name in enumerate(schedule):
            if source_name == "cityscapes":
                batch = next(city_stream)
            elif source_name == "surface":
                assert surface_stream is not None
                batch = next(surface_stream)
            else:
                raise ValueError(f"Unexpected source {source_name!r}")

            count = len(batch["id"])
            if count != batch_size:
                raise RuntimeError(f"Expected microbatch size {batch_size}, got {count}.")
            if any(s != source_name for s in batch["source"]):
                raise RuntimeError(f"Batch source mismatch: expected {source_name}, got {batch['source']}.")
            if any(sp != "train" for sp in batch["split"]):
                raise RuntimeError(f"Non-train record encountered in training phase: {batch['split']}.")
            if np.any(~np.isin(batch["semantic_source"], (0, 1))):
                raise RuntimeError("Pseudo-label source detected during training; pseudo must be OFF.")

            digest_tracker.update(micro_idx, batch)
            exposure_tracker.update(micro_idx, batch)
            microbatches_by_source[source_name] += 1
            samples_by_source[source_name] += count

            image_t = tf.convert_to_tensor(batch["image"], dtype=tf.float32)
            semantic_t = tf.convert_to_tensor(batch["semantic"], dtype=tf.int32)
            weight_t = tf.convert_to_tensor(batch["semantic_weight"], dtype=tf.float32)

            stepped, metrics = trainer.train_micro_step(
                images=image_t,
                semantic_true=semantic_t,
                semantic_weight=weight_t,
                sample_count=count,
            )
            loss_val = float(metrics["total_loss"])
            if not math.isfinite(loss_val):
                raise FloatingPointError(
                    f"Non-finite training loss {loss_val} at {arm_name}/{phase_name} microbatch {micro_idx + 1}."
                )
            microbatch_losses.append(loss_val)
            window_loss_sum += loss_val * count
            window_sample_count += count

            if stepped:
                completed_updates += 1
                if trainer.accumulation_counter != 0 or trainer.accumulated_samples != 0:
                    raise RuntimeError("Accumulator was not reset after optimizer update step.")
                update_loss = window_loss_sum / window_sample_count
                update_losses.append(update_loss)
                window_loss_sum = 0.0
                window_sample_count = 0
                if (
                    completed_updates == 1
                    or completed_updates % log_every_updates == 0
                    or completed_updates == updates
                ):
                    elapsed_so_far = time.time() - started_at
                    print(
                        f"[{arm_name}][{phase_name}] Update {completed_updates}/{updates} "
                        f"(microbatch {micro_idx + 1}/{total_microbatches}) "
                        f"| loss={update_loss:.4f} | elapsed={elapsed_so_far:.1f}s",
                        flush=True,
                    )
    finally:
        exposure_audit = exposure_tracker.finalize()

    if (
        mode == "mixed_70_30"
        and require_crosswalk_in_mixed
        and int(cat["pool_summary"]["surface"]["crosswalk_positive_record_count"]) > 0
    ):
        pre_cw_px = int(exposure_audit["crosswalk_exposure"]["pre_augmentation"]["total_pixels"])
        post_cw_px = int(exposure_audit["crosswalk_exposure"]["post_augmentation"]["total_pixels"])
        post_cw_pres = int(exposure_audit["crosswalk_exposure"]["post_augmentation"]["presentations"])
        if pre_cw_px <= 0 or post_cw_px <= 0 or post_cw_pres <= 0:
            raise RuntimeError(
                f"Mixed training phase {arm_name}/{phase_name} consumed zero real crosswalk supervision "
                f"(pre_aug_pixels={pre_cw_px}, post_aug_pixels={post_cw_px}, post_aug_presentations={post_cw_pres}); "
                "failing clearly without label-biased resampling."
            )

    if completed_updates != updates:
        raise RuntimeError(f"Expected {updates} optimizer updates, completed {completed_updates}.")
    final_opt_iterations = int(trainer.optimizer.iterations.numpy())
    if final_opt_iterations != updates:
        raise RuntimeError(
            f"Optimizer iterations mismatch in {arm_name}/{phase_name}: "
            f"optimizer.iterations={final_opt_iterations}, expected {updates}."
        )
    if trainer.accumulation_counter != 0 or trainer.accumulated_samples != 0:
        raise RuntimeError(
            f"Unflushed gradient accumulation remainder at end of {arm_name}/{phase_name}: "
            f"counter={trainer.accumulation_counter}, samples={trainer.accumulated_samples}."
        )

    for opt_var in trainer.optimizer.variables:
        if not np.all(np.isfinite(opt_var.numpy())):
            raise FloatingPointError(
                f"Non-finite optimizer variable {getattr(opt_var, 'path', opt_var.name)} in {arm_name}/{phase_name}."
            )

    final_tensor_hashes = compute_model_weights_tensor_sha256(model)
    if (
        final_tensor_hashes["non_trainable_weights_tensor_sha256"]
        != initial_tensor_hashes["non_trainable_weights_tensor_sha256"]
    ):
        raise RuntimeError(
            f"Frozen encoder/BN weights changed during {arm_name}/{phase_name}!"
        )

    weights_path = phase_output_dir / f"{arm_name}_{phase_name}.weights.h5"
    model.save_weights(str(weights_path))
    weights_file_sha256 = compute_sha256(weights_path)
    elapsed_seconds = time.time() - started_at
    del trainer
    gc.collect()

    total_samples = sum(samples_by_source.values())
    phase_summary: Dict[str, Any] = {
        "experiment_id": EXPERIMENT_ID,
        "checkpoint_kind": "experimental_budgeted_pilot",
        "production_stage_predecessor_eligible": False,
        "arm": arm_name,
        "phase": phase_name,
        "schedule_mode": mode,
        "start_epoch": start_epoch,
        "sampling_seed": sampling_seed,
        "effective_sampling_rng_seed": sampling_seed + start_epoch,
        "phase_seed": phase_seed,
        "learning_rate": learning_rate,
        "fresh_adam_optimizer_initialized": True,
        "optimizer_iterations_initial": initial_opt_iterations,
        "optimizer_iterations_final": final_opt_iterations,
        "optimizer_updates_target": updates,
        "optimizer_updates_completed": completed_updates,
        "batch_size": batch_size,
        "accumulate_steps": accumulate_steps,
        "effective_batch_size": batch_size * accumulate_steps,
        "microbatches_completed": total_microbatches,
        "sample_presentations_completed": total_samples,
        "microbatches_by_source": microbatches_by_source,
        "sample_presentations_by_source": samples_by_source,
        "actual_source_fractions": {
            "surface": samples_by_source["surface"] / total_samples if total_samples else 0.0,
            "cityscapes": samples_by_source["cityscapes"] / total_samples if total_samples else 0.0,
        },
        "stream_exhaustion_stats": {
            "cityscapes": city_stream_stats,
            "surface": surface_stream_stats if mode == "mixed_70_30" else None,
        },
        "input_digest": digest_tracker.summary(),
        "exposure_audit": exposure_audit,
        "initial_weights_tensor_hashes": initial_tensor_hashes,
        "final_weights_tensor_hashes": final_tensor_hashes,
        "saved_weights_path": str(weights_path),
        "saved_weights_file_sha256": weights_file_sha256,
        "loss_summary": {
            "first_microbatch_loss": microbatch_losses[0],
            "last_microbatch_loss": microbatch_losses[-1],
            "mean_microbatch_loss": sum(microbatch_losses) / len(microbatch_losses),
            "first_update_loss": update_losses[0],
            "last_update_loss": update_losses[-1],
            "mean_update_loss": sum(update_losses) / len(update_losses),
            "min_update_loss": min(update_losses),
            "max_update_loss": max(update_losses),
            "all_losses_finite": True,
            "all_weights_and_optimizer_finite": True,
            "update_losses": update_losses,
        },
        "elapsed_seconds": elapsed_seconds,
    }
    summary_path = phase_output_dir / "phase_summary.json"
    summary_path.write_text(json.dumps(phase_summary, indent=2, allow_nan=False), encoding="utf-8")
    return phase_summary


def update_confusion_matrix_fast(
    confusion: StreamingConfusionMatrix,
    truth: np.ndarray,
    prediction: np.ndarray,
    provenance: Optional[np.ndarray] = None,
    *,
    ignore_index: int = IGNORE_INDEX,
    real_provenance: float = REAL_PROVENANCE,
) -> None:
    """Vectorized numpy update into StreamingConfusionMatrix matching StreamingConfusionMatrix.update()."""
    truth_flat = np.asarray(truth).reshape(-1)
    pred_flat = np.asarray(prediction).reshape(-1)
    if truth_flat.shape != pred_flat.shape:
        raise ValueError("semantic arrays have different lengths")

    if provenance is not None:
        prov_flat = np.asarray(provenance).reshape(-1)
        if prov_flat.shape != truth_flat.shape:
            raise ValueError("semantic provenance array has different length")
        valid_mask = (truth_flat != ignore_index) & (prov_flat == real_provenance)
    else:
        valid_mask = truth_flat != ignore_index

    if not np.any(valid_mask):
        return

    valid_truth = truth_flat[valid_mask].astype(np.int64, copy=False)
    valid_pred = pred_flat[valid_mask].astype(np.int64, copy=False)
    class_count = confusion.class_count

    if np.any((valid_truth < 0) | (valid_truth >= class_count)):
        bad = int(valid_truth[(valid_truth < 0) | (valid_truth >= class_count)][0])
        raise ValueError(f"semantic truth class {bad} is outside the class range")
    if np.any((valid_pred < 0) | (valid_pred >= class_count)):
        bad = int(valid_pred[(valid_pred < 0) | (valid_pred >= class_count)][0])
        raise ValueError(f"semantic prediction class {bad} is outside the class range")

    flat_indices = valid_truth * class_count + valid_pred
    counts = np.bincount(flat_indices, minlength=class_count * class_count).reshape(
        (class_count, class_count)
    )
    for t_idx in range(class_count):
        row = confusion.matrix[t_idx]
        c_row = counts[t_idx]
        for p_idx in range(class_count):
            row[p_idx] += int(c_row[p_idx])
    confusion.valid_pixels += int(valid_truth.size)


def compute_subset_macro_iou(
    class_iou: Mapping[str, Optional[float]],
    target_classes: Sequence[str],
) -> Dict[str, Any]:
    """Computes macro IoU over a subset of classes while preserving None and explicit denominators."""
    per_class: Dict[str, Optional[float]] = {}
    defined_classes: List[str] = []
    undefined_classes: List[str] = []
    defined_values: List[float] = []

    for c_name in target_classes:
        val = class_iou.get(c_name)
        if val is None:
            per_class[c_name] = None
            undefined_classes.append(c_name)
        else:
            f_val = float(val)
            per_class[c_name] = f_val
            defined_classes.append(c_name)
            defined_values.append(f_val)

    denominator = len(defined_values)
    macro_iou = (sum(defined_values) / denominator) if denominator > 0 else None
    all_defined = denominator == len(target_classes) and len(target_classes) > 0
    return {
        "target_classes": list(target_classes),
        "per_class_iou": per_class,
        "defined_classes": defined_classes,
        "undefined_classes": undefined_classes,
        "denominator": denominator,
        "target_class_count": len(target_classes),
        "all_target_classes_defined": all_defined,
        "macro_iou": macro_iou,
        "strict_macro_iou": macro_iou if all_defined else None,
    }


def summarize_domain_confusion(
    confusion: StreamingConfusionMatrix,
    *,
    domain: str,
    images_evaluated: int,
    mean_semantic_loss: float,
) -> Dict[str, Any]:
    """Formats domain metrics from StreamingConfusionMatrix with explicit denominators and null handling."""
    base = confusion.summary(class_names=CLASS_NAMES)
    class_iou: Dict[str, Optional[float]] = base["class_iou"]  # type: ignore[assignment]
    matrix: List[List[int]] = base["confusion_matrix"]  # type: ignore[assignment]

    gt_pixels_by_class = {
        c_name: int(sum(matrix[c_idx])) for c_idx, c_name in enumerate(CLASS_NAMES)
    }
    pred_pixels_by_class = {
        c_name: int(sum(row[c_idx] for row in matrix))
        for c_idx, c_name in enumerate(CLASS_NAMES)
    }
    defined_classes = [c for c in CLASS_NAMES if class_iou.get(c) is not None]
    undefined_classes = [c for c in CLASS_NAMES if class_iou.get(c) is None]
    gt_present_classes = [c for c in CLASS_NAMES if gt_pixels_by_class[c] > 0]
    gt_absent_classes = [c for c in CLASS_NAMES if gt_pixels_by_class[c] == 0]

    gt_present_defined_vals = [
        float(class_iou[c])
        for c in gt_present_classes
        if class_iou.get(c) is not None
    ]
    gt_present_mean_iou = (
        sum(gt_present_defined_vals) / len(gt_present_defined_vals)
        if gt_present_defined_vals
        else None
    )

    walkable_macro = compute_subset_macro_iou(class_iou, WALKABLE_CLASS_NAMES)
    object_macro = compute_subset_macro_iou(class_iou, OBJECT_CLASS_NAMES)

    return {
        "domain": domain,
        "images_evaluated": images_evaluated,
        "valid_pixels": int(base["valid_pixels"]),
        "semantic_loss": mean_semantic_loss,
        "class_iou": class_iou,
        "defined_class_mean_iou": base["mean_iou"],
        "defined_class_count": len(defined_classes),
        "defined_iou_classes": defined_classes,
        "undefined_iou_classes": undefined_classes,
        "gt_present_mean_iou": gt_present_mean_iou,
        "gt_present_class_count": len(gt_present_defined_vals),
        "gt_present_classes": gt_present_classes,
        "gt_absent_classes": gt_absent_classes,
        "ground_truth_pixels_by_class": gt_pixels_by_class,
        "predicted_pixels_by_class": pred_pixels_by_class,
        "walkable_sidewalk_crosswalk": walkable_macro,
        "object_person_vehicle_polesign": object_macro,
        "nonwalkable_to_walkable_false_positive_rate": base[
            "nonwalkable_to_walkable_false_positive_rate"
        ],
        "nonwalkable_pixels": int(base["nonwalkable_pixels"]),
        "nonwalkable_to_walkable_pixels": int(base["nonwalkable_to_walkable_pixels"]),
        "walkable_precision": base["walkable_precision"],
        "predicted_walkable_pixels": int(base["predicted_walkable_pixels"]),
        "object_recall": base["object_recall"],
        "object_iou": base["object_iou"],
        "object_ground_truth_pixels": base["object_ground_truth_pixels"],
        "confusion_matrix": matrix,
    }


def evaluate_model_a_validation(
    *,
    model: keras.Model,
    surface_val_ds: PortraitDataset,
    city_val_ds: PortraitDataset,
    shared_class_weights: Sequence[float],
    batch_size: int = DEFAULT_BATCH_SIZE,
    require_expected_counts: bool = True,
) -> Dict[str, Any]:
    """Single-forward validation evaluator over all Surface val (1,457) and Cityscapes val (425) records."""
    if batch_size < 1:
        raise ValueError("batch_size must be positive.")
    started_at = time.time()
    loss_fn = CombinedSemanticLoss(class_weights=shared_class_weights)

    domain_datasets = (
        ("surface", surface_val_ds),
        ("cityscapes", city_val_ds),
    )
    domain_confusions: Dict[str, StreamingConfusionMatrix] = {}
    domain_images: Dict[str, int] = {}
    domain_loss_sums: Dict[str, float] = {}
    overall_confusion = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
    forward_passes = 0

    for domain_name, ds in domain_datasets:
        domain_started = time.time()
        confusion = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
        img_count = 0
        next_log_count = 100
        loss_sum = 0.0
        for batch in ds.iter_batches(batch_size=batch_size, epoch=0, augment=False, balanced=False):
            if any(sp != "val" for sp in batch["split"]):
                raise RuntimeError(f"Non-val split encountered during validation: {batch['split']}")
            if any(src != domain_name for src in batch["source"]):
                raise RuntimeError(f"Unexpected source in {domain_name} validation: {batch['source']}")
            if np.any(~np.isin(batch["semantic_source"], (0, 1))):
                raise RuntimeError("Validation records must contain only real annotations (0 or 1).")

            images_t = tf.convert_to_tensor(batch["image"], dtype=tf.float32)
            semantic_t = tf.convert_to_tensor(batch["semantic"], dtype=tf.int32)
            weight_t = tf.convert_to_tensor(batch["semantic_weight"], dtype=tf.float32)

            # Single forward pass per batch
            sem_pred = model(images_t, training=False)
            forward_passes += 1
            batch_loss = float(
                loss_fn(y_true=semantic_t, y_pred=sem_pred, sample_weight=weight_t).numpy()
            )
            if not math.isfinite(batch_loss):
                raise FloatingPointError(f"Non-finite validation loss {batch_loss} on {domain_name}.")

            pred_ids = tf.argmax(sem_pred, axis=-1).numpy()
            count = len(batch["id"])
            img_count += count
            loss_sum += batch_loss * count

            update_confusion_matrix_fast(
                confusion,
                truth=batch["semantic"],
                prediction=pred_ids,
                provenance=batch["semantic_source"],
            )
            if img_count >= next_log_count:
                print(
                    f"  [Val][{domain_name}] {img_count} images evaluated "
                    f"| mean_loss={loss_sum / img_count:.4f} "
                    f"| elapsed={time.time() - domain_started:.1f}s",
                    flush=True,
                )
                next_log_count = ((img_count // 100) + 1) * 100

        if img_count <= 0 or confusion.valid_pixels <= 0:
            raise ValueError(f"Validation domain {domain_name} yielded no valid samples/pixels.")
        print(
            f"  [Val][{domain_name}] Completed {img_count} images "
            f"| mean_loss={loss_sum / img_count:.4f} "
            f"| elapsed={time.time() - domain_started:.1f}s",
            flush=True,
        )
        domain_confusions[domain_name] = confusion
        domain_images[domain_name] = img_count
        domain_loss_sums[domain_name] = loss_sum

        for t_idx, row in enumerate(confusion.matrix):
            for p_idx, val in enumerate(row):
                overall_confusion.matrix[t_idx][p_idx] += val
        overall_confusion.valid_pixels += confusion.valid_pixels

    if require_expected_counts:
        if domain_images["surface"] != EXPECTED_SPLIT_COUNTS["surface_val"]:
            raise ValueError(
                f"Surface validation count mismatch: expected {EXPECTED_SPLIT_COUNTS['surface_val']}, "
                f"evaluated {domain_images['surface']}."
            )
        if domain_images["cityscapes"] != EXPECTED_SPLIT_COUNTS["cityscapes_val"]:
            raise ValueError(
                f"Cityscapes validation count mismatch: expected {EXPECTED_SPLIT_COUNTS['cityscapes_val']}, "
                f"evaluated {domain_images['cityscapes']}."
            )

    total_images = domain_images["surface"] + domain_images["cityscapes"]
    total_loss_mean = (domain_loss_sums["surface"] + domain_loss_sums["cityscapes"]) / total_images

    surface_summary = summarize_domain_confusion(
        domain_confusions["surface"],
        domain="surface",
        images_evaluated=domain_images["surface"],
        mean_semantic_loss=domain_loss_sums["surface"] / domain_images["surface"],
    )
    city_summary = summarize_domain_confusion(
        domain_confusions["cityscapes"],
        domain="cityscapes",
        images_evaluated=domain_images["cityscapes"],
        mean_semantic_loss=domain_loss_sums["cityscapes"] / domain_images["cityscapes"],
    )
    overall_summary = summarize_domain_confusion(
        overall_confusion,
        domain="overall_pooled",
        images_evaluated=total_images,
        mean_semantic_loss=total_loss_mean,
    )

    elapsed = time.time() - started_at
    return {
        "split": "val",
        "augmentation_enabled": False,
        "pseudo_labels_enabled": False,
        "single_forward_pass_per_batch": True,
        "forward_passes_executed": forward_passes,
        "total_validation_images": total_images,
        "images_by_domain": domain_images,
        "semantic_loss_overall": total_loss_mean,
        "by_domain": {
            "surface": surface_summary,
            "cityscapes": city_summary,
        },
        "overall_pooled_diagnostic": overall_summary,
        "elapsed_seconds": elapsed,
        "hazard_evaluation": None,
        "hazard_claim": "NONE (Model A semantic-only experiment; no hazard head attached or evaluated).",
    }


def _safe_diff(b_val: Optional[float], a_val: Optional[float]) -> Optional[float]:
    if b_val is None or a_val is None:
        return None
    return float(b_val) - float(a_val)


def compare_arms(
    arm_a_eval: Mapping[str, Any],
    arm_b_eval: Mapping[str, Any],
) -> Dict[str, Any]:
    """Compares Arm A and Arm B under the preregistered protocol criteria."""
    surf_a = arm_a_eval["by_domain"]["surface"]
    surf_b = arm_b_eval["by_domain"]["surface"]
    city_a = arm_a_eval["by_domain"]["cityscapes"]
    city_b = arm_b_eval["by_domain"]["cityscapes"]

    walk_macro_a = surf_a["walkable_sidewalk_crosswalk"]["macro_iou"]
    walk_macro_b = surf_b["walkable_sidewalk_crosswalk"]["macro_iou"]
    obj_macro_a = city_a["object_person_vehicle_polesign"]["macro_iou"]
    obj_macro_b = city_b["object_person_vehicle_polesign"]["macro_iou"]

    false_safe_a = surf_a["nonwalkable_to_walkable_false_positive_rate"]
    false_safe_b = surf_b["nonwalkable_to_walkable_false_positive_rate"]
    walk_prec_a = surf_a["walkable_precision"]
    walk_prec_b = surf_b["walkable_precision"]

    walk_macro_diff_b_minus_a = _safe_diff(walk_macro_b, walk_macro_a)
    obj_macro_diff_b_minus_a = _safe_diff(obj_macro_b, obj_macro_a)
    false_safe_diff_b_minus_a = _safe_diff(false_safe_b, false_safe_a)

    # Check protocol reference thresholds (1 percentage point = 0.01)
    practical_threshold = 0.01
    b_exceeds_walkable_1pp = (
        walk_macro_diff_b_minus_a is not None and walk_macro_diff_b_minus_a >= practical_threshold
    )
    a_exceeds_walkable_1pp = (
        walk_macro_diff_b_minus_a is not None and (-walk_macro_diff_b_minus_a) >= practical_threshold
    )

    b_object_degradation_gt_1pp = any(
        _safe_diff(city_b["class_iou"].get(c), city_a["class_iou"].get(c)) is not None
        and float(_safe_diff(city_b["class_iou"].get(c), city_a["class_iou"].get(c))) < -practical_threshold
        for c in OBJECT_CLASS_NAMES
    )
    a_object_degradation_gt_1pp = any(
        _safe_diff(city_a["class_iou"].get(c), city_b["class_iou"].get(c)) is not None
        and float(_safe_diff(city_a["class_iou"].get(c), city_b["class_iou"].get(c))) < -practical_threshold
        for c in OBJECT_CLASS_NAMES
    )
    b_false_safe_worse = (
        false_safe_diff_b_minus_a is not None and false_safe_diff_b_minus_a > 0.0
    )
    a_false_safe_worse = (
        false_safe_diff_b_minus_a is not None and false_safe_diff_b_minus_a < 0.0
    )

    if b_exceeds_walkable_1pp and not b_object_degradation_gt_1pp and not b_false_safe_worse:
        pilot_assessment = "arm_b_favored_on_pilot_reference_criteria"
    elif a_exceeds_walkable_1pp and not a_object_degradation_gt_1pp and not a_false_safe_worse:
        pilot_assessment = "arm_a_favored_on_pilot_reference_criteria"
    else:
        pilot_assessment = "inconclusive_or_tradeoff_under_single_seed_pilot_criteria"

    return {
        "practical_difference_reference_pp": 1.0,
        "practical_difference_reference_iou": practical_threshold,
        "surface_metrics": {
            "walkable_macro_iou": {
                "arm_a": walk_macro_a,
                "arm_b": walk_macro_b,
                "diff_b_minus_a": walk_macro_diff_b_minus_a,
                "diff_a_minus_b": _safe_diff(walk_macro_a, walk_macro_b),
            },
            "sidewalk_iou": {
                "arm_a": surf_a["class_iou"]["sidewalk"],
                "arm_b": surf_b["class_iou"]["sidewalk"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["class_iou"]["sidewalk"], surf_a["class_iou"]["sidewalk"]
                ),
            },
            "crosswalk_iou": {
                "arm_a": surf_a["class_iou"]["crosswalk"],
                "arm_b": surf_b["class_iou"]["crosswalk"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["class_iou"]["crosswalk"], surf_a["class_iou"]["crosswalk"]
                ),
            },
            "road_iou": {
                "arm_a": surf_a["class_iou"]["road"],
                "arm_b": surf_b["class_iou"]["road"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["class_iou"]["road"], surf_a["class_iou"]["road"]
                ),
            },
            "other_iou": {
                "arm_a": surf_a["class_iou"]["other"],
                "arm_b": surf_b["class_iou"]["other"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["class_iou"]["other"], surf_a["class_iou"]["other"]
                ),
            },
            "defined_class_mean_iou": {
                "arm_a": surf_a["defined_class_mean_iou"],
                "arm_b": surf_b["defined_class_mean_iou"],
                "arm_a_denominator": surf_a["defined_class_count"],
                "arm_b_denominator": surf_b["defined_class_count"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["defined_class_mean_iou"], surf_a["defined_class_mean_iou"]
                ),
            },
            "gt_present_mean_iou": {
                "arm_a": surf_a["gt_present_mean_iou"],
                "arm_b": surf_b["gt_present_mean_iou"],
                "arm_a_denominator": surf_a["gt_present_class_count"],
                "arm_b_denominator": surf_b["gt_present_class_count"],
                "diff_b_minus_a": _safe_diff(
                    surf_b["gt_present_mean_iou"], surf_a["gt_present_mean_iou"]
                ),
            },
            "nonwalkable_to_walkable_false_positive_rate": {
                "arm_a": false_safe_a,
                "arm_b": false_safe_b,
                "diff_b_minus_a": false_safe_diff_b_minus_a,
                "lower_is_better": True,
            },
            "walkable_precision": {
                "arm_a": walk_prec_a,
                "arm_b": walk_prec_b,
                "diff_b_minus_a": _safe_diff(walk_prec_b, walk_prec_a),
                "higher_is_better": True,
            },
            "semantic_loss": {
                "arm_a": surf_a["semantic_loss"],
                "arm_b": surf_b["semantic_loss"],
                "diff_b_minus_a": _safe_diff(surf_b["semantic_loss"], surf_a["semantic_loss"]),
            },
        },
        "cityscapes_metrics": {
            "object_macro_iou": {
                "arm_a": obj_macro_a,
                "arm_b": obj_macro_b,
                "diff_b_minus_a": obj_macro_diff_b_minus_a,
                "diff_a_minus_b": _safe_diff(obj_macro_a, obj_macro_b),
            },
            "person_iou": {
                "arm_a": city_a["class_iou"]["person"],
                "arm_b": city_b["class_iou"]["person"],
                "diff_b_minus_a": _safe_diff(
                    city_b["class_iou"]["person"], city_a["class_iou"]["person"]
                ),
            },
            "vehicle_iou": {
                "arm_a": city_a["class_iou"]["vehicle"],
                "arm_b": city_b["class_iou"]["vehicle"],
                "diff_b_minus_a": _safe_diff(
                    city_b["class_iou"]["vehicle"], city_a["class_iou"]["vehicle"]
                ),
            },
            "pole_sign_iou": {
                "arm_a": city_a["class_iou"]["pole_sign"],
                "arm_b": city_b["class_iou"]["pole_sign"],
                "diff_b_minus_a": _safe_diff(
                    city_b["class_iou"]["pole_sign"], city_a["class_iou"]["pole_sign"]
                ),
            },
            "defined_class_mean_iou": {
                "arm_a": city_a["defined_class_mean_iou"],
                "arm_b": city_b["defined_class_mean_iou"],
                "arm_a_denominator": city_a["defined_class_count"],
                "arm_b_denominator": city_b["defined_class_count"],
                "diff_b_minus_a": _safe_diff(
                    city_b["defined_class_mean_iou"], city_a["defined_class_mean_iou"]
                ),
            },
            "semantic_loss": {
                "arm_a": city_a["semantic_loss"],
                "arm_b": city_b["semantic_loss"],
                "diff_b_minus_a": _safe_diff(city_b["semantic_loss"], city_a["semantic_loss"]),
            },
        },
        "guardrail_flags": {
            "b_exceeds_walkable_macro_1pp": b_exceeds_walkable_1pp,
            "a_exceeds_walkable_macro_1pp": a_exceeds_walkable_1pp,
            "b_object_class_degradation_gt_1pp": b_object_degradation_gt_1pp,
            "a_object_class_degradation_gt_1pp": a_object_degradation_gt_1pp,
            "b_false_safe_worse_than_a": b_false_safe_worse,
            "a_false_safe_worse_than_b": a_false_safe_worse,
        },
        "pilot_assessment": pilot_assessment,
        "scope_and_limitations": {
            "pilot_budget_updates_per_arm": 256,
            "pilot_sample_presentations_per_arm": 2048,
            "paired_seeds_evaluated": 1,
            "full_convergence_claimed": False,
            "statistical_significance_claimed": False,
            "korean_object_accuracy_claimed": False,
            "hazard_accuracy_claimed": False,
            "deployment_acceptance_claimed": False,
        },
    }


def query_gpu_resource_snapshot() -> Dict[str, Any]:
    """Queries TF memory info and nvidia-smi when available."""
    info: Dict[str, Any] = {
        "tf_visible_gpus": [d.name for d in tf.config.get_visible_devices("GPU")],
        "tf_memory_info": None,
        "nvidia_smi": None,
    }
    if info["tf_visible_gpus"]:
        try:
            mem = tf.config.experimental.get_memory_info("GPU:0")
            info["tf_memory_info"] = {
                "current_bytes": int(mem.get("current", 0)),
                "peak_bytes": int(mem.get("peak", 0)),
                "current_mib": round(float(mem.get("current", 0)) / (1024.0 * 1024.0), 2),
                "peak_mib": round(float(mem.get("peak", 0)) / (1024.0 * 1024.0), 2),
            }
        except Exception:
            pass
    for smi_bin in ("/usr/lib/wsl/lib/nvidia-smi", "nvidia-smi"):
        try:
            res = subprocess.run(
                [
                    smi_bin,
                    "--query-gpu=name,memory.used,memory.total,utilization.gpu",
                    "--format=csv,noheader,nounits",
                ],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
            if res.returncode == 0 and res.stdout.strip():
                parts = [p.strip() for p in res.stdout.strip().splitlines()[0].split(",")]
                if len(parts) >= 4:
                    info["nvidia_smi"] = {
                        "name": parts[0],
                        "memory_used_mib": float(parts[1]),
                        "memory_total_mib": float(parts[2]),
                        "utilization_gpu_pct": float(parts[3]),
                    }
                    break
        except Exception:
            continue
    return info


def software_and_source_provenance() -> Dict[str, Any]:
    from PIL import __version__ as pillow_version

    tracked_files = (
        "tools/vision/training/strategy_experiment.py",
        "tools/vision/training/losses.py",
        "tools/vision/training/model.py",
        "tools/vision/training/train.py",
        "tools/vision/training/train_data.py",
        "tools/vision/training/metrics.py",
    )
    file_hashes: Dict[str, Optional[str]] = {}
    for rel in tracked_files:
        p = _REPO_ROOT / rel
        file_hashes[rel] = compute_sha256(p) if p.is_file() else None

    return {
        "python": platform.python_version(),
        "tensorflow": str(tf.__version__),
        "numpy": str(np.__version__),
        "pillow": str(pillow_version),
        "platform": platform.platform(),
        "source_file_sha256": file_hashes,
    }


def _default_model_factory(weights: Optional[str]) -> keras.Model:
    model = build_model_a(weights=weights, freeze_bn=True, input_range="0_255")
    set_encoder_trainability(model, policy="freeze_all")
    freeze_batch_normalization(model)
    return model


def run_strategy_experiment(
    *,
    manifest_path: Path,
    output_dir: Path,
    device: str = "gpu",
    weights: Optional[str] = "imagenet",
    seed: int = DEFAULT_SEED,
    sampling_seed: Optional[int] = None,
    initial_weights_path: Optional[Path] = None,
    expected_initial_weights_file_sha256: Optional[str] = None,
    expected_initial_tensor_sha256: Optional[str] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS,
    phase1_updates: int = DEFAULT_PHASE1_UPDATES,
    phase2_updates: int = DEFAULT_PHASE2_UPDATES,
    phase1_lr: float = DEFAULT_PHASE1_LR,
    phase2_lr: float = DEFAULT_PHASE2_LR,
    clipnorm: float = DEFAULT_CLIPNORM,
    require_expected_manifest_hash: bool = True,
    require_crosswalk_in_mixed: bool = True,
    model_factory: Optional[Callable[[Optional[str]], keras.Model]] = None,
) -> Dict[str, Any]:
    """Runs the complete two-arm controlled strategy experiment and persists machine-readable results."""
    started_at = time.time()
    effective_sampling_seed = resolve_sampling_seed(seed, sampling_seed)
    output_dir = output_dir.expanduser().resolve()
    if output_dir.exists() and any(output_dir.iterdir()):
        raise FileExistsError(
            f"Experiment output directory {output_dir} is non-empty; refusing to overwrite prior outputs."
        )
    output_dir.mkdir(parents=True, exist_ok=True)

    configure_device(device=device)
    set_deterministic_seeds(seed)

    print("=" * 78, flush=True)
    print(f"Strategy Experiment: {EXPERIMENT_ID}", flush=True)
    print(f"Manifest: {manifest_path}", flush=True)
    print(f"Output Dir: {output_dir}", flush=True)
    print(
        f"Device: {device} | Weights: {weights} | Model Seed: {seed} | "
        f"Sampling Seed: {effective_sampling_seed} | "
        f"Phase1: {phase1_updates} updates (LR {phase1_lr}) | "
        f"Phase2: {phase2_updates} updates (LR {phase2_lr}) | "
        f"Batch: {batch_size} x Accum: {accumulate_steps}",
        flush=True,
    )
    print("=" * 78, flush=True)

    # 1. Build manifest train catalog, static old/new sampling audit, and PortraitDataset splits once
    catalog = build_manifest_train_catalog(manifest_path)
    mask_verification_cache: Dict[str, Dict[str, Any]] = {}
    sampling_audit = audit_and_compare_sampling_seeds(
        manifest_path,
        old_sampling_seed=DEFAULT_SEED,
        new_sampling_seed=effective_sampling_seed,
        phase1_updates=phase1_updates,
        phase2_updates=phase2_updates,
        accumulate_steps=accumulate_steps,
        batch_size=batch_size,
        verify_converted_masks=True,
        catalog=catalog,
        mask_verification_cache=mask_verification_cache,
    )
    (output_dir / "sampling_seed_audit.json").write_text(
        json.dumps(sampling_audit, indent=2, allow_nan=False), encoding="utf-8"
    )

    t0 = time.time()
    datasets = build_experiment_datasets(
        manifest_path,
        seed=seed,
        sampling_seed=effective_sampling_seed,
    )
    dataset_init_seconds = time.time() - t0
    print(f"[Init] Constructed 4 PortraitDataset splits in {dataset_init_seconds:.2f}s.", flush=True)

    manifest_audit = audit_manifest_and_compute_shared_weights(
        manifest_path,
        require_expected_manifest_hash=require_expected_manifest_hash,
        sample_provenance_datasets={
            "cityscapes": datasets["city_train"],
            "surface": datasets["surface_train"],
        },
        sample_provenance_records_per_source=2,
    )
    shared_class_weights = manifest_audit["shared_class_weights"]
    print(
        f"[Init] Audited manifest ({manifest_audit['manifest_sha256'][:16]}...); "
        f"shared class weights = {[round(w, 4) for w in shared_class_weights]}",
        flush=True,
    )

    # 2. Build shared initial model and save/verify shared initial weights
    factory = model_factory or _default_model_factory
    keras.backend.clear_session()
    gc.collect()
    set_deterministic_seeds(seed)

    shared_init_dir = output_dir / "shared_init"
    shared_init_dir.mkdir(parents=True, exist_ok=True)
    shared_init_weights_path = shared_init_dir / "shared_initial.weights.h5"
    source_initial_weights_path_str: Optional[str] = None
    source_initial_weights_file_sha256: Optional[str] = None

    if initial_weights_path is not None:
        src_init_path = Path(initial_weights_path).expanduser().resolve()
        if not src_init_path.is_file():
            raise FileNotFoundError(f"Initial weights file not found: {src_init_path}")
        source_initial_weights_path_str = str(src_init_path)
        source_initial_weights_file_sha256 = compute_sha256(src_init_path)
        expected_file_sha = expected_initial_weights_file_sha256 or (
            PRIOR_SHARED_INIT_FILE_SHA256 if require_expected_manifest_hash else None
        )
        if expected_file_sha and source_initial_weights_file_sha256 != expected_file_sha:
            raise ValueError(
                f"Initial weights file SHA-256 mismatch: expected {expected_file_sha}, "
                f"got {source_initial_weights_file_sha256}."
            )
        init_model = factory(None)
        init_model.load_weights(str(src_init_path))
        shared_init_tensor_hashes = compute_model_weights_tensor_sha256(init_model)
        expected_tensor_sha = expected_initial_tensor_sha256 or (
            PRIOR_SHARED_INIT_ALL_TENSOR_SHA256 if require_expected_manifest_hash else None
        )
        if (
            expected_tensor_sha
            and shared_init_tensor_hashes["all_weights_tensor_sha256"] != expected_tensor_sha
        ):
            raise ValueError(
                f"Initial weights tensor SHA-256 mismatch: expected {expected_tensor_sha}, "
                f"got {shared_init_tensor_hashes['all_weights_tensor_sha256']}."
            )
        if require_expected_manifest_hash and expected_initial_tensor_sha256 is None:
            if (
                shared_init_tensor_hashes["trainable_weights_tensor_sha256"]
                != PRIOR_SHARED_INIT_TRAINABLE_TENSOR_SHA256
                or shared_init_tensor_hashes["non_trainable_weights_tensor_sha256"]
                != PRIOR_SHARED_INIT_NON_TRAINABLE_TENSOR_SHA256
            ):
                raise ValueError(
                    "Initial weights trainable/non-trainable tensor SHA-256 mismatch against prior shared init."
                )
        if src_init_path != shared_init_weights_path:
            shutil.copyfile(src_init_path, shared_init_weights_path)
        shared_init_file_sha256 = compute_sha256(shared_init_weights_path)
    else:
        init_model = factory(weights)
        init_model.save_weights(str(shared_init_weights_path))
        shared_init_file_sha256 = compute_sha256(shared_init_weights_path)
        shared_init_tensor_hashes = compute_model_weights_tensor_sha256(init_model)
        if (
            expected_initial_weights_file_sha256
            and shared_init_file_sha256 != expected_initial_weights_file_sha256
        ):
            raise ValueError(
                f"Initial weights file SHA-256 mismatch: expected {expected_initial_weights_file_sha256}, "
                f"got {shared_init_file_sha256}."
            )
        if (
            expected_initial_tensor_sha256
            and shared_init_tensor_hashes["all_weights_tensor_sha256"] != expected_initial_tensor_sha256
        ):
            raise ValueError(
                f"Initial weights tensor SHA-256 mismatch: expected {expected_initial_tensor_sha256}, "
                f"got {shared_init_tensor_hashes['all_weights_tensor_sha256']}."
            )

    del init_model
    keras.backend.clear_session()
    gc.collect()

    print(
        f"[Init] Shared initial weights ready: file_sha256={shared_init_file_sha256[:16]}..., "
        f"tensor_sha256={shared_init_tensor_hashes['all_weights_tensor_sha256'][:16]}...",
        flush=True,
    )

    arms_config = (
        ("arm_a", "city_pretrain_then_mixed", "city_only", "mixed_70_30"),
        ("arm_b", "mixed_from_start", "mixed_70_30", "mixed_70_30"),
    )
    arm_results: Dict[str, Any] = {}

    for arm_key, arm_label, phase1_mode, phase2_mode in arms_config:
        arm_started = time.time()
        arm_dir = output_dir / arm_key
        arm_dir.mkdir(parents=True, exist_ok=True)

        keras.backend.clear_session()
        gc.collect()
        set_deterministic_seeds(seed)
        model = factory(None)
        model.load_weights(str(shared_init_weights_path))
        arm_init_hashes = compute_model_weights_tensor_sha256(model)

        if (
            arm_init_hashes["all_weights_tensor_sha256"]
            != shared_init_tensor_hashes["all_weights_tensor_sha256"]
        ):
            raise RuntimeError(
                f"{arm_key} initial tensor hash {arm_init_hashes['all_weights_tensor_sha256']} "
                f"!= shared initial tensor hash {shared_init_tensor_hashes['all_weights_tensor_sha256']}."
            )
        if (
            arm_init_hashes["trainable_variable_paths"]
            != shared_init_tensor_hashes["trainable_variable_paths"]
        ):
            raise RuntimeError(f"{arm_key} trainable_variable_paths mismatch.")

        print(f"\n--- Starting {arm_key} ({arm_label}) ---", flush=True)
        p1_trackers: List[PhaseExposureTracker] = []
        phase1_summary = run_training_phase(
            model=model,
            arm_name=arm_key,
            phase_name="phase1",
            mode=phase1_mode,
            updates=phase1_updates,
            learning_rate=phase1_lr,
            batch_size=batch_size,
            accumulate_steps=accumulate_steps,
            clipnorm=clipnorm,
            shared_class_weights=shared_class_weights,
            city_train_ds=datasets["city_train"],
            surface_train_ds=datasets["surface_train"],
            start_epoch=PHASE1_BASE_EPOCH,
            seed=seed,
            phase_output_dir=arm_dir / "phase1",
            catalog=catalog,
            mask_verification_cache=mask_verification_cache,
            require_crosswalk_in_mixed=require_crosswalk_in_mixed,
            tracker_out=p1_trackers,
        )

        p2_trackers: List[PhaseExposureTracker] = []
        phase2_summary = run_training_phase(
            model=model,
            arm_name=arm_key,
            phase_name="phase2",
            mode=phase2_mode,
            updates=phase2_updates,
            learning_rate=phase2_lr,
            batch_size=batch_size,
            accumulate_steps=accumulate_steps,
            clipnorm=clipnorm,
            shared_class_weights=shared_class_weights,
            city_train_ds=datasets["city_train"],
            surface_train_ds=datasets["surface_train"],
            start_epoch=PHASE2_BASE_EPOCH,
            seed=seed,
            phase_output_dir=arm_dir / "phase2",
            catalog=catalog,
            mask_verification_cache=mask_verification_cache,
            require_crosswalk_in_mixed=require_crosswalk_in_mixed,
            tracker_out=p2_trackers,
        )

        arm_exposure_audit = aggregate_arm_exposure_audit(
            p1_trackers[0],
            p2_trackers[0],
            phase1_summary["exposure_audit"],
            phase2_summary["exposure_audit"],
        )

        print(f"[{arm_key}] Evaluating full validation split...", flush=True)
        val_summary = evaluate_model_a_validation(
            model=model,
            surface_val_ds=datasets["surface_val"],
            city_val_ds=datasets["city_val"],
            shared_class_weights=shared_class_weights,
            batch_size=batch_size,
            require_expected_counts=require_expected_manifest_hash,
        )
        val_path = arm_dir / "validation_summary.json"
        val_path.write_text(json.dumps(val_summary, indent=2, allow_nan=False), encoding="utf-8")

        gpu_snapshot = query_gpu_resource_snapshot()
        total_arm_updates = (
            phase1_summary["optimizer_updates_completed"]
            + phase2_summary["optimizer_updates_completed"]
        )
        total_arm_microbatches = (
            phase1_summary["microbatches_completed"] + phase2_summary["microbatches_completed"]
        )
        total_arm_samples = (
            phase1_summary["sample_presentations_completed"]
            + phase2_summary["sample_presentations_completed"]
        )
        total_surface_samples = (
            phase1_summary["sample_presentations_by_source"]["surface"]
            + phase2_summary["sample_presentations_by_source"]["surface"]
        )
        total_city_samples = (
            phase1_summary["sample_presentations_by_source"]["cityscapes"]
            + phase2_summary["sample_presentations_by_source"]["cityscapes"]
        )

        arm_payload: Dict[str, Any] = {
            "arm": arm_key,
            "label": arm_label,
            "initial_weights_tensor_hashes": arm_init_hashes,
            "final_weights_tensor_hashes": phase2_summary["final_weights_tensor_hashes"],
            "final_weights_path": phase2_summary["saved_weights_path"],
            "final_weights_file_sha256": phase2_summary["saved_weights_file_sha256"],
            "total_optimizer_updates": total_arm_updates,
            "total_microbatches": total_arm_microbatches,
            "total_sample_presentations": total_arm_samples,
            "total_sample_presentations_by_source": {
                "surface": total_surface_samples,
                "cityscapes": total_city_samples,
            },
            "total_source_fractions": {
                "surface": total_surface_samples / total_arm_samples if total_arm_samples else 0.0,
                "cityscapes": total_city_samples / total_arm_samples if total_arm_samples else 0.0,
            },
            "exposure_audit": arm_exposure_audit,
            "phase1": phase1_summary,
            "phase2": phase2_summary,
            "validation": val_summary,
            "gpu_resource_snapshot": gpu_snapshot,
            "elapsed_seconds": time.time() - arm_started,
        }
        arm_summary_path = arm_dir / "arm_summary.json"
        arm_summary_path.write_text(
            json.dumps(arm_payload, indent=2, allow_nan=False), encoding="utf-8"
        )
        arm_payload["arm_summary_path"] = str(arm_summary_path)
        arm_results[arm_key] = arm_payload
        del model
        keras.backend.clear_session()
        gc.collect()

    # 3. Verify strict cross-arm invariants (preserving all 9 original keys + exposure audit controls)
    arm_a = arm_results["arm_a"]
    arm_b = arm_results["arm_b"]
    a_p1_exp = arm_a["phase1"]["exposure_audit"]
    b_p1_exp = arm_b["phase1"]["exposure_audit"]
    a_p2_exp = arm_a["phase2"]["exposure_audit"]
    b_p2_exp = arm_b["phase2"]["exposure_audit"]
    has_pool_crosswalk = int(catalog["pool_summary"]["surface"]["crosswalk_positive_record_count"]) > 0

    invariants = {
        "shared_initial_tensor_sha256_equal": (
            arm_a["initial_weights_tensor_hashes"]["all_weights_tensor_sha256"]
            == arm_b["initial_weights_tensor_hashes"]["all_weights_tensor_sha256"]
            == shared_init_tensor_hashes["all_weights_tensor_sha256"]
        ),
        "trainable_variable_paths_equal": (
            arm_a["initial_weights_tensor_hashes"]["trainable_variable_paths"]
            == arm_b["initial_weights_tensor_hashes"]["trainable_variable_paths"]
        ),
        "frozen_non_trainable_weights_unchanged_both_arms": (
            arm_a["final_weights_tensor_hashes"]["non_trainable_weights_tensor_sha256"]
            == arm_b["final_weights_tensor_hashes"]["non_trainable_weights_tensor_sha256"]
            == shared_init_tensor_hashes["non_trainable_weights_tensor_sha256"]
        ),
        "phase2_input_digest_equal": (
            arm_a["phase2"]["input_digest"] == arm_b["phase2"]["input_digest"]
        ),
        "phase1_input_digest_distinct": (
            arm_a["phase1"]["input_digest"]["combined_sha256"]
            != arm_b["phase1"]["input_digest"]["combined_sha256"]
        ),
        "total_updates_equal_target": (
            arm_a["total_optimizer_updates"]
            == arm_b["total_optimizer_updates"]
            == (phase1_updates + phase2_updates)
        ),
        "optimizer_iterations_equal_target": (
            arm_a["phase1"]["optimizer_iterations_final"] == phase1_updates
            and arm_b["phase1"]["optimizer_iterations_final"] == phase1_updates
            and arm_a["phase2"]["optimizer_iterations_final"] == phase2_updates
            and arm_b["phase2"]["optimizer_iterations_final"] == phase2_updates
        ),
        "total_sample_presentations_equal_target": (
            arm_a["total_sample_presentations"]
            == arm_b["total_sample_presentations"]
            == (phase1_updates + phase2_updates) * accumulate_steps * batch_size
        ),
        "validation_images_equal": (
            arm_a["validation"]["images_by_domain"] == arm_b["validation"]["images_by_domain"]
        ),
        "phase2_exposure_audit_equal": (
            a_p2_exp["ordered_sample_ids_sha256"] == b_p2_exp["ordered_sample_ids_sha256"]
            and a_p2_exp["exposure_digest_sha256"] == b_p2_exp["exposure_digest_sha256"]
            and a_p2_exp["ordered_samples_jsonl_sha256"] == b_p2_exp["ordered_samples_jsonl_sha256"]
            and a_p2_exp["microbatch_exposure_jsonl_sha256"]
            == b_p2_exp["microbatch_exposure_jsonl_sha256"]
        ),
        "phase1_exposure_audit_distinct": (
            a_p1_exp["ordered_sample_ids_sha256"] != b_p1_exp["ordered_sample_ids_sha256"]
            and a_p1_exp["exposure_digest_sha256"] != b_p1_exp["exposure_digest_sha256"]
        ),
        "no_within_epoch_duplicate_samples": (
            arm_a["exposure_audit"]["duplicate_presentations_within_epoch"] == 0
            and arm_b["exposure_audit"]["duplicate_presentations_within_epoch"] == 0
        ),
        "no_val_or_test_samples_in_training": (
            arm_a["exposure_audit"]["non_train_split_ids_encountered"] == 0
            and arm_b["exposure_audit"]["non_train_split_ids_encountered"] == 0
        ),
        "mixed_phases_have_real_crosswalk_supervision": (
            (not require_crosswalk_in_mixed or not has_pool_crosswalk)
            or (
                b_p1_exp["crosswalk_exposure"]["post_augmentation"]["total_pixels"] > 0
                and a_p2_exp["crosswalk_exposure"]["post_augmentation"]["total_pixels"] > 0
                and b_p2_exp["crosswalk_exposure"]["post_augmentation"]["total_pixels"] > 0
                and b_p1_exp["crosswalk_exposure"]["converted_mask_verification"][
                    "all_verified_masks_match_manifest"
                ]
                and a_p2_exp["crosswalk_exposure"]["converted_mask_verification"][
                    "all_verified_masks_match_manifest"
                ]
            )
        ),
    }
    if not all(invariants.values()):
        raise RuntimeError(f"Cross-arm protocol invariant check failed: {invariants}")

    comparison = compare_arms(arm_a["validation"], arm_b["validation"])
    total_elapsed = time.time() - started_at

    results_payload: Dict[str, Any] = {
        "schema_version": 1,
        "experiment_id": EXPERIMENT_ID,
        "random_sampling_task_id": RANDOM_SAMPLING_TASK_ID,
        "protocol_document": PROTOCOL_DOC,
        "random_sampling_protocol_document": RANDOM_SAMPLING_PROTOCOL_DOC,
        "status": "COMPLETED",
        "exit_code": 0,
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "configuration": {
            "architecture": "model_a",
            "precision": "FLOAT32",
            "input_shape": [512, 288, 3],
            "num_semantic_classes": NUM_SEMANTIC_CLASSES,
            "weights": weights,
            "encoder_policy": "freeze_all",
            "freeze_bn": True,
            "seed": seed,
            "model_seed": seed,
            "sampling_seed": effective_sampling_seed,
            "sampling_seed_explicit": sampling_seed is not None,
            "sampler_algorithm": sampling_audit["sampler_contract"]["algorithm"],
            "epoch_exhaustion_policy": sampling_audit["sampler_contract"]["epoch_exhaustion_policy"],
            "eligible_train_pool_counts": {
                "cityscapes": int(catalog["pool_summary"]["cityscapes"]["eligible_record_count"]),
                "surface": int(catalog["pool_summary"]["surface"]["eligible_record_count"]),
            },
            "device": device,
            "batch_size": batch_size,
            "accumulate_steps": accumulate_steps,
            "effective_batch_size": batch_size * accumulate_steps,
            "phase1_updates": phase1_updates,
            "phase2_updates": phase2_updates,
            "total_updates_per_arm": phase1_updates + phase2_updates,
            "total_microbatches_per_arm": (phase1_updates + phase2_updates) * accumulate_steps,
            "total_sample_presentations_per_arm": (
                (phase1_updates + phase2_updates) * accumulate_steps * batch_size
            ),
            "phase1_learning_rate": phase1_lr,
            "phase2_learning_rate": phase2_lr,
            "optimizer": "Adam (fresh at phase1 and phase2 boundaries)",
            "clipnorm": clipnorm,
            "mixed_schedule_10": list(MIXED_SCHEDULE_10),
            "pseudo_labels_enabled": False,
        },
        "manifest_and_class_weights": manifest_audit,
        "sampling_seed_audit": sampling_audit,
        "shared_initialization": {
            "initialization_mode": (
                "loaded_immutable_shared_init"
                if initial_weights_path is not None
                else "fresh_seed_initialized"
            ),
            "checkpoint_kind": "untrained_shared_initialization",
            "production_stage_predecessor_eligible": False,
            "source_initial_weights_path": source_initial_weights_path_str,
            "source_initial_weights_file_sha256": source_initial_weights_file_sha256,
            "shared_initial_weights_path": str(shared_init_weights_path),
            "shared_initial_weights_file_sha256": shared_init_file_sha256,
            "shared_initial_tensor_hashes": shared_init_tensor_hashes,
            "matches_prior_pilot_shared_init_file_sha256": (
                shared_init_file_sha256 == PRIOR_SHARED_INIT_FILE_SHA256
            ),
            "matches_prior_pilot_shared_init_tensor_sha256": (
                shared_init_tensor_hashes["all_weights_tensor_sha256"]
                == PRIOR_SHARED_INIT_ALL_TENSOR_SHA256
            ),
        },
        "invariants_verified": invariants,
        "arms": arm_results,
        "comparison": comparison,
        "software_and_source_provenance": software_and_source_provenance(),
        "timing": {
            "dataset_init_seconds": dataset_init_seconds,
            "arm_a_elapsed_seconds": arm_a["elapsed_seconds"],
            "arm_b_elapsed_seconds": arm_b["elapsed_seconds"],
            "total_elapsed_seconds": total_elapsed,
        },
    }

    results_path = output_dir / "experiment_results.json"
    results_path.write_text(
        json.dumps(results_payload, indent=2, allow_nan=False), encoding="utf-8"
    )
    (output_dir / "exit_code.txt").write_text("0\n", encoding="utf-8")
    results_payload["results_path"] = str(results_path)
    print(f"\n[Done] Experiment results saved to: {results_path}", flush=True)
    return results_payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.vision.training.strategy_experiment",
        description="Controlled two-arm training strategy experiment for Aria Model A.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        required=True,
        help="Path to converted dataset manifest.json.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Fresh output directory for checkpoints, phase summaries, and experiment_results.json.",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="gpu",
        choices=["cpu", "gpu"],
        help="Execution device ('gpu' or 'cpu'). Default: gpu.",
    )
    parser.add_argument(
        "--weights",
        type=str,
        default="imagenet",
        help="Backbone initialization ('imagenet' or 'none'). Default: imagenet.",
    )
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument(
        "--sampling-seed",
        type=int,
        default=None,
        help="Explicit dataset sampling/augmentation seed (defaults to --seed when omitted).",
    )
    parser.add_argument(
        "--initial-weights-path",
        type=Path,
        default=None,
        help="Optional immutable untrained shared_initial.weights.h5 to load and verify before Phase 1.",
    )
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--accumulate-steps", type=int, default=DEFAULT_ACCUMULATE_STEPS)
    parser.add_argument("--phase1-updates", type=int, default=DEFAULT_PHASE1_UPDATES)
    parser.add_argument("--phase2-updates", type=int, default=DEFAULT_PHASE2_UPDATES)
    parser.add_argument("--phase1-lr", type=float, default=DEFAULT_PHASE1_LR)
    parser.add_argument("--phase2-lr", type=float, default=DEFAULT_PHASE2_LR)
    parser.add_argument("--clipnorm", type=float, default=DEFAULT_CLIPNORM)
    parser.add_argument(
        "--allow-test-manifest",
        action="store_true",
        help="Allow non-production manifest hash/counts (for unit tests only).",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    weights = None if str(args.weights).lower() in ("none", "") else args.weights

    run_strategy_experiment(
        manifest_path=args.manifest,
        output_dir=args.output_dir,
        device=args.device,
        weights=weights,
        seed=args.seed,
        sampling_seed=args.sampling_seed,
        initial_weights_path=args.initial_weights_path,
        batch_size=args.batch_size,
        accumulate_steps=args.accumulate_steps,
        phase1_updates=args.phase1_updates,
        phase2_updates=args.phase2_updates,
        phase1_lr=args.phase1_lr,
        phase2_lr=args.phase2_lr,
        clipnorm=args.clipnorm,
        require_expected_manifest_hash=not args.allow_test_manifest,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
