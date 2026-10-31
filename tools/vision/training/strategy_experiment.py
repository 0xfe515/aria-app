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
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Mapping, Optional, Sequence, Tuple

_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

import numpy as np
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
PROTOCOL_DOC: str = "docs/VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md"
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

DEFAULT_PHASE1_UPDATES: int = 64
DEFAULT_PHASE2_UPDATES: int = 192
DEFAULT_PHASE1_LR: float = 1e-3
DEFAULT_PHASE2_LR: float = 3e-4
PHASE1_BASE_EPOCH: int = 0
PHASE2_BASE_EPOCH: int = 1

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


def build_experiment_datasets(manifest_path: Path, seed: int = DEFAULT_SEED) -> Dict[str, PortraitDataset]:
    """Constructs the four train/val per-source PortraitDataset objects once (pseudo OFF, test excluded)."""
    manifest_path = manifest_path.expanduser().resolve()
    datasets = {
        "city_train": PortraitDataset(
            manifest_path,
            split="train",
            sources="cityscapes",
            pseudo_manifest_path=None,
            seed=seed,
            require_complete=True,
        ),
        "surface_train": PortraitDataset(
            manifest_path,
            split="train",
            sources="surface",
            pseudo_manifest_path=None,
            seed=seed,
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
) -> Dict[str, Any]:
    """Executes one controlled phase with fresh Adam optimizer and exact update/microbatch accounting."""
    if updates < 1 or batch_size < 1 or accumulate_steps < 1:
        raise ValueError("updates, batch_size, and accumulate_steps must be positive integers.")
    phase_output_dir.mkdir(parents=True, exist_ok=True)

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
    microbatches_by_source = {"surface": 0, "cityscapes": 0}
    samples_by_source = {"surface": 0, "cityscapes": 0}
    completed_updates = 0
    microbatch_losses: List[float] = []
    update_losses: List[float] = []
    window_loss_sum = 0.0
    window_sample_count = 0
    started_at = time.time()

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
    batch_size: int = DEFAULT_BATCH_SIZE,
    accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS,
    phase1_updates: int = DEFAULT_PHASE1_UPDATES,
    phase2_updates: int = DEFAULT_PHASE2_UPDATES,
    phase1_lr: float = DEFAULT_PHASE1_LR,
    phase2_lr: float = DEFAULT_PHASE2_LR,
    clipnorm: float = DEFAULT_CLIPNORM,
    require_expected_manifest_hash: bool = True,
    model_factory: Optional[Callable[[Optional[str]], keras.Model]] = None,
) -> Dict[str, Any]:
    """Runs the complete two-arm controlled strategy experiment and persists machine-readable results."""
    started_at = time.time()
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
        f"Device: {device} | Weights: {weights} | Seed: {seed} | "
        f"Phase1: {phase1_updates} updates (LR {phase1_lr}) | "
        f"Phase2: {phase2_updates} updates (LR {phase2_lr}) | "
        f"Batch: {batch_size} x Accum: {accumulate_steps}",
        flush=True,
    )
    print("=" * 78, flush=True)

    # 1. Construct datasets once and audit manifest + provenance
    t0 = time.time()
    datasets = build_experiment_datasets(manifest_path, seed=seed)
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

    # 2. Build shared initial model and save shared initial weights
    factory = model_factory or _default_model_factory
    keras.backend.clear_session()
    gc.collect()
    set_deterministic_seeds(seed)
    init_model = factory(weights)
    shared_init_dir = output_dir / "shared_init"
    shared_init_dir.mkdir(parents=True, exist_ok=True)
    shared_init_weights_path = shared_init_dir / "shared_initial.weights.h5"
    init_model.save_weights(str(shared_init_weights_path))
    shared_init_file_sha256 = compute_sha256(shared_init_weights_path)
    shared_init_tensor_hashes = compute_model_weights_tensor_sha256(init_model)
    del init_model
    keras.backend.clear_session()
    gc.collect()

    print(
        f"[Init] Saved shared initial weights: file_sha256={shared_init_file_sha256[:16]}..., "
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
        )

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

    # 3. Verify strict cross-arm invariants
    arm_a = arm_results["arm_a"]
    arm_b = arm_results["arm_b"]
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
    }
    if not all(invariants.values()):
        raise RuntimeError(f"Cross-arm protocol invariant check failed: {invariants}")

    comparison = compare_arms(arm_a["validation"], arm_b["validation"])
    total_elapsed = time.time() - started_at

    results_payload: Dict[str, Any] = {
        "schema_version": 1,
        "experiment_id": EXPERIMENT_ID,
        "protocol_document": PROTOCOL_DOC,
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
        "shared_initialization": {
            "shared_initial_weights_path": str(shared_init_weights_path),
            "shared_initial_weights_file_sha256": shared_init_file_sha256,
            "shared_initial_tensor_hashes": shared_init_tensor_hashes,
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
