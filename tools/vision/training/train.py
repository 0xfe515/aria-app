"""Native portrait staged training orchestrator for Aria vision models.

Task ID: ARIA-PORTRAIT-RUNNER-001
Consumes published contracts:
- MODEL_CONTRACT.md / model.py / losses.py
- DATA_CONTRACT.md / data.py / pseudo_labels.py
- EVALUATION_CONTRACT.md / metrics.py

Features:
- Full staged training:
  - city_warmup: 5 epochs, lr=1e-3, BN frozen, Cityscapes train, decoder warmup
  - city_full: 30 epochs, lr=1e-4, BN frozen, Cityscapes train, full encoder
  - mixed_decoder: 5 epochs, lr=3e-4, 50:50 mixed Cityscapes/Surface, decoder only
  - mixed_block13: 20 epochs, lr=1e-5, block13+, patience=5, 50:50 mixed
  - stage_b_head: 3 epochs, lr=3e-4, risk head only, initialized from Model A
  - stage_b_joint: 20 epochs, lr=1e-5, joint semantic + hazard
  - stage_c: lightweight student distillation from Model B teacher
- Explicit from-scratch ImageNet initialization (weights='imagenet' or weights=None)
- PortraitDataset streaming data loading with strict split/provenance validation
- Partial manifest rejection by default; --allow-partial-manifest for bounded verification
- Pseudo-label sparse overlay on matching Surface train records (weight 0.25, content mask)
- Paired data augmentation (50% full field letterbox, 50% upright crop, flip, exposure, contrast, mild blur)
  - Nearest neighbor on categorical and discrete provenance weights {0.0, 0.25, 1.0}
- Float32 precision with gradient accumulation: default batch 2, accumulation 4 (effective 8); OOM fallback batch 1, accumulation 8
- Clipnorm 1.0 applied once to accumulated gradients; proper remainder flush
- Strict epoch-boundary resume with stage/config/manifest/optimizer and accumulator checks
- Bounded CPU smoke test (--smoke) with synthetic inputs, weights=None, parameter update verification, checkpoint roundtrip, and export validation
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import random
import shutil
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Generator, List, Optional, Sequence, Tuple

# Ensure repository root is in sys.path
_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    np = None  # type: ignore
    HAS_NUMPY = False

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None  # type: ignore
    HAS_PIL = False

try:
    import tensorflow as tf
    from tensorflow import keras
    HAS_TF = True
except (ImportError, ModuleNotFoundError):
    tf = None  # type: ignore
    keras = None  # type: ignore
    HAS_TF = False

# Import peer modules
from tools.vision.training.model import (
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    IMAGE_CHANNELS,
    INPUT_SHAPE,
    NUM_SEMANTIC_CLASSES,
    NUM_HAZARD_CLASSES,
    SEMANTIC_CLASSES,
    HAZARD_CLASSES,
    IGNORE_LABEL,
    build_model_a,
    build_model_b,
    build_model_c,
    attach_risk_head,
    freeze_batch_normalization,
    set_encoder_trainability,
    build_export_model,
)
from tools.vision.training.losses import (
    compute_inverse_sqrt_class_weights,
)

if HAS_TF:
    from tools.vision.training.losses import (
        CombinedSemanticLoss,
        MaskedFocalBinaryCrossentropy,
        ModelBJointLoss,
        KLDistillationLoss,
    )
else:
    CombinedSemanticLoss = None  # type: ignore
    MaskedFocalBinaryCrossentropy = None  # type: ignore
    ModelBJointLoss = None  # type: ignore
    KLDistillationLoss = None  # type: ignore

from tools.vision.training.metrics import (
    StreamingConfusionMatrix,
    StreamingRiskMetrics,
    CLASS_NAMES,
    HAZARD_NAMES,
    WALKABLE_CLASS_IDS,
)
from tools.vision.training.train_data import PortraitDataset

# Constants
DEFAULT_SEED: int = 42
DEFAULT_CLIPNORM: float = 1.0
DEFAULT_ACCUMULATE_STEPS: int = 4
DEFAULT_BATCH_SIZE: int = 2
OOM_FALLBACK_BATCH_SIZE: int = 1
OOM_FALLBACK_ACCUMULATE_STEPS: int = 8
MAX_DIAGNOSTIC_SAMPLES: int = 256
PSEUDO_OBJECT_CLASSES: Tuple[int, ...] = (4, 5, 6)

# Stage schedule definitions
STAGE_SPECS: Dict[str, Dict[str, Any]] = {
    "city_warmup": {
        "architecture": "model_a",
        "epochs": 5,
        "learning_rate": 1e-3,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "cityscapes",
        "description": "Warmup decoder on Cityscapes train split with frozen encoder",
    },
    "city_full": {
        "architecture": "model_a",
        "epochs": 30,
        "learning_rate": 1e-4,
        "encoder_policy": "unfreeze_all",
        "freeze_bn": True,
        "dataset_filter": "cityscapes",
        "description": "Full Cityscapes training with frozen BN statistics",
    },
    "mixed_decoder": {
        "architecture": "model_a",
        "epochs": 5,
        "learning_rate": 3e-4,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "mixed",
        "description": "Decoder warmup on 50:50 mixed Cityscapes and Surface data",
    },
    "mixed_block13": {
        "architecture": "model_a",
        "epochs": 20,
        "learning_rate": 1e-5,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
        "patience": 5,
        "description": "Fine-tune block13+ and decoder on 50:50 mixed data with early stopping",
    },
    "stage_b_head": {
        "architecture": "model_b",
        "epochs": 3,
        "learning_rate": 3e-4,
        "encoder_policy": "freeze_all",
        "freeze_bn": True,
        "dataset_filter": "mixed",
        "description": "Train hazard head only on mixed data, keeping semantic backbone and decoder frozen",
    },
    "stage_b_joint": {
        "architecture": "model_b",
        "epochs": 20,
        "learning_rate": 1e-5,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
        "description": "Joint semantic and hazard multi-task training on mixed data",
    },
    "stage_c": {
        "architecture": "model_c",
        "epochs": 25,
        "learning_rate": 1e-4,
        "encoder_policy": "block13_plus",
        "freeze_bn": True,
        "dataset_filter": "mixed",
        "description": "Lightweight student distillation from Model B teacher",
    },
}


def _check_runtime_tf():
    if not HAS_TF or not HAS_NUMPY:
        raise RuntimeError(
            "TensorFlow >= 2.19 and NumPy are required for model training. "
            "Please run inside an authorized Python environment with TensorFlow installed."
        )


def configure_device(device: str = "cpu", threads: int = 2):
    """Selects the requested TensorFlow device without changing process environment."""
    if device == "cpu":
        if HAS_TF:
            try:
                # Re-applying an already-effective selection is a no-op, so repeated
                # in-process calls succeed; a conflicting late change still fails loudly.
                if tf.config.get_visible_devices("GPU"):
                    tf.config.set_visible_devices([], "GPU")
            except RuntimeError as exc:
                raise RuntimeError(
                    "CPU device selection must happen before TensorFlow initializes its runtime."
                ) from exc
            for getter, setter, label in (
                (
                    tf.config.threading.get_intra_op_parallelism_threads,
                    tf.config.threading.set_intra_op_parallelism_threads,
                    "intra-op",
                ),
                (
                    tf.config.threading.get_inter_op_parallelism_threads,
                    tf.config.threading.set_inter_op_parallelism_threads,
                    "inter-op",
                ),
            ):
                if getter() == threads:
                    continue
                try:
                    setter(threads)
                except RuntimeError:
                    # Thread counts only affect speed. Device placement (above) is strict.
                    print(
                        f"[NOTICE] TensorFlow already initialized; {label} thread count stays at "
                        f"{getter()} instead of {threads}."
                    )
    elif device == "gpu":
        if HAS_TF:
            gpus = tf.config.list_physical_devices("GPU")
            if not gpus:
                raise RuntimeError("GPU requested, but TensorFlow detected no physical GPU devices.")
            if not tf.config.get_visible_devices("GPU"):
                try:
                    tf.config.set_visible_devices(gpus, "GPU")
                except RuntimeError as exc:
                    raise RuntimeError(
                        "GPU device selection must happen before TensorFlow initializes its runtime."
                    ) from exc
    else:
        raise ValueError(f"Unknown device: {device}. Must be 'cpu' or 'gpu'.")


def compute_sha256(path: Path) -> str:
    """Computes SHA-256 hash of a file."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


# ==============================================================================
# Data Manifest Consumption & Streaming Loader
# ==============================================================================

@dataclass
class ManifestRecordItem:
    id: str
    source: str
    split: str
    group_id: str
    image_sha256: str
    image_path: Path
    semantic_path: Path
    hazard_path: Optional[Path] = None
    hazard_valid_path: Optional[Path] = None
    semantic_source_path: Optional[Path] = None
    semantic_weight_path: Optional[Path] = None
    pseudo_semantic_path: Optional[Path] = None
    pseudo_weight_path: Optional[Path] = None


def load_manifest_records_filtered(
    manifest_path: Path,
    split: str = "train",
    source_filter: Optional[str] = None,
    pseudo_manifest_path: Optional[Path] = None,
    allow_partial: bool = False,
) -> Tuple[Dict[str, Any], List[ManifestRecordItem]]:
    """Loads manifest.json, strictly filtering by split and validating converted paths.

    Refuses unconverted or missing paths unless allow_partial=True (for bounded smoke only).
    Refuses split contamination.
    Overlays pseudo-labels only for Surface train records matching source, split, and image_sha256.
    """
    manifest_path = manifest_path.resolve()
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Manifest file not found: {manifest_path}")

    try:
        with manifest_path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Failed to parse manifest {manifest_path}: {exc}") from exc

    if data.get("schema_version") != 1:
        raise ValueError(f"Unsupported manifest schema_version: {data.get('schema_version')}, expected 1")

    records_raw = data.get("records")
    if not isinstance(records_raw, list):
        raise ValueError(f"Malformed manifest: 'records' field is not a list in {manifest_path}")

    manifest_dir = manifest_path.parent

    pseudo_map: Dict[str, Dict[str, Any]] = {}
    if pseudo_manifest_path is not None:
        p_path = pseudo_manifest_path.resolve()
        if not p_path.is_file():
            raise FileNotFoundError(f"Pseudo manifest not found: {p_path}")
        with p_path.open("r", encoding="utf-8") as f:
            p_data = json.load(f)
        p_dir = p_path.parent
        for prec in p_data.get("records", []):
            rid = prec.get("id")
            sem_rel = prec.get("semantic_path")
            wt_rel = prec.get("semantic_weight_path")
            if rid and sem_rel and wt_rel:
                pseudo_map[rid] = {
                    "semantic_path": p_dir / sem_rel,
                    "semantic_weight_path": p_dir / wt_rel,
                    "image_sha256": prec.get("image_sha256"),
                    "source": prec.get("source"),
                    "split": prec.get("split"),
                }

    selected: List[ManifestRecordItem] = []
    unconverted_count = 0

    for rec in records_raw:
        rec_split = rec.get("split")
        rec_source = rec.get("source")

        if rec_split != split:
            continue

        if source_filter and source_filter != "mixed" and rec_source != source_filter:
            continue

        img_rel = rec.get("image_tensor_path") or rec.get("generated_image_path")
        sem_rel = rec.get("semantic_path")

        if not img_rel or not sem_rel:
            unconverted_count += 1
            if not allow_partial:
                raise ValueError(
                    f"Full training rejects partial manifest: Record '{rec.get('id')}' in split '{split}' "
                    "lacks converted paths. Conversion must be completed before full training, "
                    "or pass --allow-partial-manifest for bounded smoke."
                )
            continue

        img_full = manifest_dir / img_rel
        sem_full = manifest_dir / sem_rel

        if not img_full.is_file():
            raise FileNotFoundError(f"Converted image file missing: {img_full}")
        if not sem_full.is_file():
            raise FileNotFoundError(f"Converted semantic mask missing: {sem_full}")

        haz_rel = rec.get("hazard_path")
        haz_val_rel = rec.get("hazard_valid_path")
        sem_src_rel = rec.get("semantic_source_path")
        sem_wt_rel = rec.get("semantic_weight_path")

        haz_full = (manifest_dir / haz_rel) if haz_rel else None
        haz_val_full = (manifest_dir / haz_val_rel) if haz_val_rel else None
        sem_src_full = (manifest_dir / sem_src_rel) if sem_src_rel else None
        sem_wt_full = (manifest_dir / sem_wt_rel) if sem_wt_rel else None

        pseudo_sem = None
        pseudo_wt = None
        rid = rec.get("id", "")
        img_sha = rec.get("image_sha256", "")
        if rec_source == "surface" and split == "train" and rid in pseudo_map:
            p_entry = pseudo_map[rid]
            if p_entry["source"] == "surface" and p_entry["split"] == "train":
                if p_entry["image_sha256"] and p_entry["image_sha256"] != img_sha:
                    raise ValueError(
                        f"Pseudo-label hash mismatch for record {rid}: "
                        f"manifest {img_sha} vs pseudo {p_entry['image_sha256']}"
                    )
                pseudo_sem = p_entry["semantic_path"]
                pseudo_wt = p_entry["semantic_weight_path"]

        item = ManifestRecordItem(
            id=rid,
            source=rec_source,
            split=rec_split,
            group_id=rec.get("group_id", ""),
            image_sha256=img_sha,
            image_path=img_full,
            semantic_path=sem_full,
            hazard_path=haz_full,
            hazard_valid_path=haz_val_full,
            semantic_source_path=sem_src_full,
            semantic_weight_path=sem_wt_full,
            pseudo_semantic_path=pseudo_sem,
            pseudo_weight_path=pseudo_wt,
        )
        selected.append(item)

    if allow_partial and unconverted_count > 0:
        print(
            f"[NOTICE] Bounded run loaded {len(selected)} converted records "
            f"({unconverted_count} unconverted records skipped with allow_partial=True). "
            "CAVEAT: NO QUALITY OR CONVERGENCE CLAIM IS MADE FROM PARTIAL DATA."
        )

    return data, selected


# ==============================================================================
# Record Loader with Pseudo Overlay
# ==============================================================================

def load_record_sample(
    record: ManifestRecordItem,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, Optional[np.ndarray], Optional[np.ndarray]]:
    """Loads a single record from disk, applying pseudo overlay where valid."""
    if not HAS_PIL or not HAS_NUMPY:
        raise RuntimeError("NumPy and Pillow are required to load record samples.")

    with Image.open(record.image_path) as img_file:
        image = np.array(img_file.convert("RGB"), dtype=np.float32)

    with Image.open(record.semantic_path) as sem_file:
        semantic = np.array(sem_file, dtype=np.int32)

    if record.semantic_weight_path and record.semantic_weight_path.is_file():
        p_data = np.load(record.semantic_weight_path)
        semantic_weight = p_data["semantic_weight"].astype(np.float32)
    else:
        semantic_weight = np.where(semantic != IGNORE_LABEL, 1.0, 0.0).astype(np.float32)

    if record.pseudo_semantic_path and record.pseudo_semantic_path.is_file():
        with Image.open(record.pseudo_semantic_path) as p_file:
            pseudo_sem = np.array(p_file, dtype=np.int32)

        overlay_mask = (semantic == IGNORE_LABEL) & np.isin(pseudo_sem, PSEUDO_OBJECT_CLASSES)
        semantic[overlay_mask] = pseudo_sem[overlay_mask]
        semantic_weight[overlay_mask] = 0.25

    hazard = None
    hazard_valid = None
    if record.hazard_path and record.hazard_path.is_file():
        h_data = np.load(record.hazard_path)
        hazard = h_data["hazard"].astype(np.float32)
        hazard_valid = h_data["hazard_valid"].astype(np.float32)

    return image, semantic, semantic_weight, hazard, hazard_valid


# ==============================================================================
# Paired Data Augmentation (Nearest Neighbor Weight Preservation)
# ==============================================================================

def apply_paired_augmentation(
    image: np.ndarray,
    semantic: np.ndarray,
    semantic_weight: np.ndarray,
    hazard: Optional[np.ndarray],
    hazard_valid: Optional[np.ndarray],
    rng: random.Random,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, Optional[np.ndarray], Optional[np.ndarray]]:
    """Applies paired geometric and photometric augmentations preserving exact weight values.

    Discrete provenance weights {0.0, 0.25, 1.0} and categorical labels are transformed
    using strictly nearest-neighbor interpolation to prevent fractional weight degradation.
    """
    if not HAS_PIL:
        raise RuntimeError("Pillow is required for paired augmentation.")

    H, W = image.shape[:2]
    if H != IMAGE_HEIGHT or W != IMAGE_WIDTH:
        raise ValueError(f"Expected shape ({IMAGE_HEIGHT}, {IMAGE_WIDTH}), got ({H}, {W})")

    # 1. 50% Full-field / 50% Upright crop
    if rng.random() < 0.5:
        crop_scale = rng.uniform(0.75, 1.0)
        crop_h = int(round(H * crop_scale))
        crop_w = int(round(W * crop_scale))

        max_y = max(0, H - crop_h)
        max_x = max(0, W - crop_w)
        top = rng.randint(0, max_y) if max_y > 0 else 0
        left = rng.randint(0, max_x) if max_x > 0 else 0

        img_crop = image[top : top + crop_h, left : left + crop_w]
        sem_crop = semantic[top : top + crop_h, left : left + crop_w]
        wt_crop = semantic_weight[top : top + crop_h, left : left + crop_w]

        pil_img = Image.fromarray(np.clip(img_crop, 0, 255).astype(np.uint8))
        image = np.array(pil_img.resize((W, H), Image.BILINEAR), dtype=np.float32)

        pil_sem = Image.fromarray(sem_crop.astype(np.uint8))
        semantic = np.array(pil_sem.resize((W, H), Image.NEAREST), dtype=np.int32)

        wt_idx = np.zeros_like(wt_crop, dtype=np.uint8)
        wt_idx[np.isclose(wt_crop, 0.25, atol=0.01)] = 1
        wt_idx[np.isclose(wt_crop, 1.0, atol=0.01)] = 2
        pil_wt_idx = Image.fromarray(wt_idx)
        res_wt_idx = np.array(pil_wt_idx.resize((W, H), Image.NEAREST), dtype=np.uint8)
        table = np.array([0.0, 0.25, 1.0], dtype=np.float32)
        semantic_weight = table[res_wt_idx]

        semantic_weight = np.where(semantic == IGNORE_LABEL, 0.0, semantic_weight)

        if hazard is not None and hazard_valid is not None:
            haz_res = np.zeros((H, W, NUM_HAZARD_CLASSES), dtype=np.float32)
            val_res = np.zeros((H, W, NUM_HAZARD_CLASSES), dtype=np.float32)
            for c in range(NUM_HAZARD_CLASSES):
                p_h = Image.fromarray(hazard[top : top + crop_h, left : left + crop_w, c].astype(np.uint8))
                p_v = Image.fromarray(hazard_valid[top : top + crop_h, left : left + crop_w, c].astype(np.uint8))
                haz_res[..., c] = np.array(p_h.resize((W, H), Image.NEAREST), dtype=np.float32)
                val_res[..., c] = np.array(p_v.resize((W, H), Image.NEAREST), dtype=np.float32)
            hazard = haz_res
            hazard_valid = val_res

    # 2. Horizontal flip (p=0.5)
    if rng.random() < 0.5:
        image = np.ascontiguousarray(np.fliplr(image))
        semantic = np.ascontiguousarray(np.fliplr(semantic))
        semantic_weight = np.ascontiguousarray(np.fliplr(semantic_weight))
        if hazard is not None:
            hazard = np.ascontiguousarray(np.fliplr(hazard))
        if hazard_valid is not None:
            hazard_valid = np.ascontiguousarray(np.fliplr(hazard_valid))

    # 3. Photometric augmentation on image only
    bright_factor = rng.uniform(0.85, 1.15)
    image = image * bright_factor

    contrast_factor = rng.uniform(0.85, 1.15)
    mean_val = np.mean(image, axis=(0, 1), keepdims=True)
    image = (image - mean_val) * contrast_factor + mean_val

    if rng.random() < 0.3:
        kernel = np.ones((3, 3, 1), dtype=np.float32) / 9.0
        pad_img = np.pad(image, ((1, 1), (1, 1), (0, 0)), mode="reflect")
        blurred = np.zeros_like(image)
        for dy in range(3):
            for dx in range(3):
                blurred += pad_img[dy : dy + H, dx : dx + W] * kernel[dy, dx]
        image = blurred

    image = np.clip(image, 0.0, 255.0)
    semantic_weight = np.where(semantic == IGNORE_LABEL, 0.0, semantic_weight)

    return image, semantic, semantic_weight, hazard, hazard_valid


# ==============================================================================
# Synthetic Smoke Generator
# ==============================================================================

def generate_synthetic_smoke_batch(
    batch_size: int = 2,
    include_hazard: bool = True,
    seed: int = 42,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, Optional[np.ndarray], Optional[np.ndarray]]:
    """Generates synthetic labeled batches for bounded CPU smoke testing."""
    rng = np.random.RandomState(seed)

    images = rng.uniform(0.0, 255.0, size=(batch_size, IMAGE_HEIGHT, IMAGE_WIDTH, 3)).astype(np.float32)

    semantics = rng.randint(0, NUM_SEMANTIC_CLASSES + 1, size=(batch_size, IMAGE_HEIGHT, IMAGE_WIDTH)).astype(np.int32)
    semantics = np.where(semantics == NUM_SEMANTIC_CLASSES, IGNORE_LABEL, semantics)

    weights = np.ones((batch_size, IMAGE_HEIGHT, IMAGE_WIDTH), dtype=np.float32)
    pseudo_mask = (semantics == 4) | (semantics == 5)
    weights = np.where(pseudo_mask, 0.25, weights)
    weights = np.where(semantics == IGNORE_LABEL, 0.0, weights)

    if include_hazard:
        hazards = rng.randint(0, 2, size=(batch_size, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES)).astype(np.float32)
        hazard_valids = rng.choice([0.0, 1.0], p=[0.2, 0.8], size=(batch_size, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES)).astype(np.float32)
        hazards = hazards * hazard_valids
    else:
        hazards = None
        hazard_valids = None

    return images, semantics, weights, hazards, hazard_valids


# ==============================================================================
# Resumable Checkpoint State Management (Strict Keras 3 & Hash Enforcement)
# ==============================================================================

@dataclass
class CheckpointMetadata:
    schema_version: int = 1
    stage: str = ""
    epoch: int = 0
    global_step: int = 0
    architecture: str = "model_a"
    seed: int = DEFAULT_SEED
    batch_size: int = DEFAULT_BATCH_SIZE
    accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS
    learning_rate: float = 1e-4
    data_manifest_hash: Optional[str] = None
    pseudo_manifest_hash: Optional[str] = None
    teacher_checkpoint_hash: Optional[str] = None
    initialization_mode: str = "checkpoint"
    class_weights: Optional[List[float]] = None
    clipnorm: float = DEFAULT_CLIPNORM
    patience: Optional[int] = None
    patience_counter: int = 0
    semantic_baseline: Optional[float] = None
    stage_config_hash: Optional[str] = None
    optimizer: str = "Adam"
    accumulation_counter: int = 0
    accumulated_samples: int = 0
    best_val_loss: float = float("inf")
    best_val_miou: float = 0.0
    best_val_semantic_loss: float = float("inf")
    best_val_hazard_loss: Optional[float] = None
    timestamp: str = ""


class CheckpointManager:
    """Manages saving and restoring model weights, optimizer variables, and training metadata."""

    def __init__(self, checkpoint_dir: Path):
        self.checkpoint_dir = checkpoint_dir.resolve()
        self.checkpoint_dir.mkdir(parents=True, exist_ok=True)

    def save_checkpoint(
        self,
        model: keras.Model,
        optimizer: keras.optimizers.Optimizer,
        metadata: CheckpointMetadata,
        accumulated_gradients: Optional[List[tf.Tensor]] = None,
        accumulation_counter: int = 0,
        tag: str = "latest",
    ) -> Path:
        """Durably saves model weights, Keras 3 optimizer variables, and metadata JSON."""
        if accumulation_counter != 0 or metadata.accumulated_samples != 0:
            raise ValueError(
                "Only epoch-boundary checkpoints with a flushed gradient accumulator can be saved."
            )
        if metadata.accumulation_counter != accumulation_counter:
            raise ValueError("Checkpoint metadata and gradient accumulation counters disagree.")
        if accumulated_gradients is None or len(accumulated_gradients) != len(model.trainable_variables):
            raise ValueError("Checkpoint must contain one gradient accumulator per trainable variable.")
        tag_dir = self.checkpoint_dir / tag
        pending_dir = self.checkpoint_dir / f".{tag}.pending"
        previous_dir = self.checkpoint_dir / f".{tag}.previous"
        if pending_dir.exists():
            shutil.rmtree(pending_dir)
        pending_dir.mkdir(parents=True)

        weights_path = pending_dir / "model_weights.weights.h5"
        model.save_weights(str(weights_path))

        opt_vars = optimizer.variables
        opt_dict = {f"opt_{i}": v.numpy() for i, v in enumerate(opt_vars)}
        opt_path = pending_dir / "optimizer_state.npz"
        np.savez_compressed(opt_path, **opt_dict)

        acc_dict = {
            f"acc_{i}": g.numpy()
            for i, g in enumerate(accumulated_gradients or [])
        }
        for index, variable in enumerate(model.trainable_variables):
            value = acc_dict[f"acc_{index}"]
            if tuple(value.shape) != tuple(variable.shape) or not np.all(np.isfinite(value)):
                raise ValueError(f"Invalid accumulated gradient buffer acc_{index}.")
            if np.any(value != 0):
                raise ValueError("Epoch-boundary checkpoint contains unflushed accumulated gradients.")
        acc_dict["accumulation_counter"] = np.array(accumulation_counter, dtype=np.int32)
        acc_dict["accumulated_samples"] = np.array(
            metadata.accumulated_samples, dtype=np.int64
        )
        np.savez_compressed(pending_dir / "accumulator_state.npz", **acc_dict)

        rng_state = {
            "python_rng": random.getstate(),
            "numpy_rng": [x.tolist() if isinstance(x, np.ndarray) else x for x in np.random.get_state()],
        }
        with (pending_dir / "rng_state.json").open("w", encoding="utf-8") as f:
            json.dump(rng_state, f)


        meta_path = pending_dir / "metadata.json"
        metadata.accumulation_counter = accumulation_counter
        metadata.timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        with meta_path.open("w", encoding="utf-8") as f:
            json.dump(asdict(metadata), f, indent=2)

        if previous_dir.exists() and tag_dir.exists():
            shutil.rmtree(previous_dir)
        if tag_dir.exists():
            tag_dir.replace(previous_dir)
        try:
            pending_dir.replace(tag_dir)
        except OSError:
            if previous_dir.exists() and not tag_dir.exists():
                previous_dir.replace(tag_dir)
            raise
        if previous_dir.exists():
            shutil.rmtree(previous_dir)
        return tag_dir

    def restore_checkpoint(
        self,
        model: keras.Model,
        optimizer: Optional[keras.optimizers.Optimizer] = None,
        tag: str = "latest",
        expected_manifest_hash: Optional[str] = None,
        expected_metadata: Optional[Dict[str, Any]] = None,
    ) -> Tuple[CheckpointMetadata, Optional[List[np.ndarray]], int]:
        """Restores model weights, optimizer variables, and validates metadata."""
        tag_dir = self._resolve_checkpoint_dir(tag)
        meta_path = tag_dir / "metadata.json"

        if not meta_path.is_file():
            raise FileNotFoundError(f"Checkpoint metadata not found at {meta_path}")

        with meta_path.open("r", encoding="utf-8") as f:
            meta_dict = json.load(f)
        metadata = CheckpointMetadata(**meta_dict)
        if metadata.schema_version != 1:
            raise ValueError(f"Unsupported checkpoint schema_version: {metadata.schema_version}.")

        if expected_manifest_hash and metadata.data_manifest_hash != expected_manifest_hash:
            raise ValueError(
                f"Checkpoint manifest hash mismatch! Saved checkpoint used {metadata.data_manifest_hash}, "
                f"but current manifest has {expected_manifest_hash}. Refusing resume across different datasets."
            )
        for key, expected_value in (expected_metadata or {}).items():
            saved_value = getattr(metadata, key)
            if saved_value != expected_value:
                raise ValueError(
                    f"Checkpoint {key} mismatch: saved {saved_value!r}, requested "
                    f"{expected_value!r}. Refusing incompatible resume."
                )
        if metadata.accumulation_counter != 0 or metadata.accumulated_samples != 0:
            raise ValueError(
                "Only epoch-boundary checkpoints can be resumed; checkpoint contains an "
                "unflushed gradient accumulation window."
            )

        weights_h5 = tag_dir / "model_weights.weights.h5"
        weights_ckpt = tag_dir / "model_weights.ckpt"
        if weights_h5.is_file():
            model.load_weights(str(weights_h5))
        elif weights_ckpt.is_file() or (tag_dir / "model_weights.ckpt.index").is_file():
            model.load_weights(str(weights_ckpt))
        else:
            raise FileNotFoundError(f"No valid weights file found in {tag_dir}")

        opt_path = tag_dir / "optimizer_state.npz"
        if optimizer is not None:
            if not opt_path.is_file():
                raise FileNotFoundError(f"Optimizer state missing in checkpoint: {opt_path}")

            optimizer.build(model.trainable_variables)

            with np.load(opt_path) as data:
                expected_optimizer_names = {
                    f"opt_{index}" for index in range(len(optimizer.variables))
                }
                if set(data.files) != expected_optimizer_names:
                    raise ValueError(
                        f"Optimizer state keys mismatch in {opt_path}; refusing restore."
                    )
                loaded_arrays = [
                    data[f"opt_{index}"].copy()
                    for index in range(len(optimizer.variables))
                ]

            if len(optimizer.variables) != len(loaded_arrays):
                raise ValueError(
                    f"Optimizer variable count mismatch: model has {len(optimizer.variables)}, "
                    f"checkpoint contains {len(loaded_arrays)}. Refusing resume."
                )

            for var, val in zip(optimizer.variables, loaded_arrays):
                if tuple(var.shape) != tuple(val.shape):
                    raise ValueError(
                        f"Optimizer variable shape mismatch for {var.name}: "
                        f"expected {tuple(var.shape)}, found {tuple(val.shape)}."
                    )
                if not np.all(np.isfinite(val)):
                    raise ValueError(f"Optimizer state {var.name} contains non-finite values.")
                var.assign(val)

        restored_acc = None
        acc_counter = 0
        acc_path = tag_dir / "accumulator_state.npz"
        if not acc_path.is_file():
            raise FileNotFoundError(f"Gradient accumulator state missing in checkpoint: {acc_path}")
        with np.load(acc_path) as acc_data:
            if "accumulation_counter" not in acc_data or "accumulated_samples" not in acc_data:
                raise ValueError(f"Malformed gradient accumulator state in {acc_path}.")
            acc_counter = int(acc_data["accumulation_counter"])
            restored_samples = int(acc_data["accumulated_samples"])
            if (
                acc_counter != metadata.accumulation_counter
                or restored_samples != metadata.accumulated_samples
                or acc_counter != 0
                or restored_samples != 0
            ):
                raise ValueError(
                    "Only epoch-boundary checkpoints can be resumed; accumulator state is not empty."
                )
            expected_acc_names = {f"acc_{i}" for i in range(len(model.trainable_variables))}
            if set(acc_data.files) != expected_acc_names | {
                "accumulation_counter", "accumulated_samples"
            }:
                raise ValueError(f"Gradient accumulator state has missing or unexpected buffers in {acc_path}.")
            restored_acc = []
            for i, var in enumerate(model.trainable_variables):
                value = acc_data[f"acc_{i}"]
                if tuple(value.shape) != tuple(var.shape) or not np.all(np.isfinite(value)):
                    raise ValueError(f"Invalid accumulated gradient buffer acc_{i} in {acc_path}.")
                restored_acc.append(value.copy())

        rng_path = tag_dir / "rng_state.json"
        if not rng_path.is_file():
            raise FileNotFoundError(f"RNG state missing in checkpoint: {rng_path}")
        with rng_path.open("r", encoding="utf-8") as f:
            rng_dict = json.load(f)
        try:
            py_st = rng_dict["python_rng"]
            random.setstate(tuple(tuple(x) if isinstance(x, list) else x for x in py_st))
            np_st = rng_dict["numpy_rng"]
            np.random.set_state(
                (np_st[0], np.array(np_st[1], dtype=np.uint32), int(np_st[2]),
                 int(np_st[3]), float(np_st[4]))
            )
        except (KeyError, TypeError, ValueError, IndexError) as exc:
            raise ValueError(f"Malformed RNG state in checkpoint: {rng_path}") from exc

        return metadata, restored_acc, acc_counter

    def _resolve_checkpoint_dir(self, tag: str) -> Path:
        if (self.checkpoint_dir / "metadata.json").is_file():
            return self.checkpoint_dir
        tagged_dir = self.checkpoint_dir / tag
        if (tagged_dir / "metadata.json").is_file():
            return tagged_dir
        tagged_backup = self.checkpoint_dir / f".{tag}.previous"
        if (tagged_backup / "metadata.json").is_file():
            return tagged_backup
        if self.checkpoint_dir.name == tag:
            parent_backup = self.checkpoint_dir.parent / f".{tag}.previous"
            if (parent_backup / "metadata.json").is_file():
                return parent_backup
        return tagged_dir


# ==============================================================================
# Gradient Accumulation & Custom Training Step
# ==============================================================================

class StagedTrainer:
    """Orchestrates staged training with gradient accumulation, clipping, and evaluation."""

    def __init__(
        self,
        model: keras.Model,
        architecture: str = "model_a",
        stage: str = "city_warmup",
        learning_rate: float = 1e-4,
        clipnorm: float = DEFAULT_CLIPNORM,
        batch_size: int = DEFAULT_BATCH_SIZE,
        accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS,
        class_weights: Optional[Sequence[float]] = None,
        checkpoint_dir: Optional[Path] = None,
        teacher_model: Optional[keras.Model] = None,
    ):
        _check_runtime_tf()
        self.model = model
        self.architecture = architecture
        self.stage = stage
        self.learning_rate = learning_rate
        self.clipnorm = clipnorm
        self.batch_size = batch_size
        self.accumulate_steps = accumulate_steps
        self.class_weights = class_weights
        self.teacher_model = teacher_model

        self.checkpoint_dir = checkpoint_dir or Path("build/vision-training/runner/checkpoints")
        self.ckpt_manager = CheckpointManager(self.checkpoint_dir)

        # Loss functions
        self.semantic_loss_fn = CombinedSemanticLoss(class_weights=self.class_weights)
        self.hazard_loss_fn = MaskedFocalBinaryCrossentropy(gamma=2.0)
        self.joint_loss_fn = ModelBJointLoss(
            semantic_loss_fn=self.semantic_loss_fn,
            hazard_loss_weight=0.5,
            gamma=2.0,
        )
        self.kl_distill_fn = KLDistillationLoss(temperature=2.0, distill_weight=0.2)

        # Optimizer
        self.optimizer = keras.optimizers.Adam(learning_rate=self.learning_rate)
        self.optimizer.build(self.model.trainable_variables)

        # Gradient accumulator buffers
        self.accumulated_gradients: List[tf.Tensor] = []
        self.accumulation_counter: int = 0
        self.accumulated_samples: int = 0
        self._init_accumulator()

    def _init_accumulator(self):
        """Initializes gradient accumulator buffers matching trainable variables."""
        self.accumulated_gradients = [
            tf.zeros_like(var) for var in self.model.trainable_variables
        ]
        self.accumulation_counter = 0
        self.accumulated_samples = 0

    def compute_loss(
        self,
        images: tf.Tensor,
        semantic_true: tf.Tensor,
        semantic_weight: tf.Tensor,
        hazard_true: Optional[tf.Tensor] = None,
        hazard_valid: Optional[tf.Tensor] = None,
        training: bool = True,
    ) -> Tuple[tf.Tensor, Dict[str, float]]:
        """Computes multi-task or single-task loss based on architecture and stage."""
        predictions = self.model(images, training=training)
        loss_metrics: Dict[str, float] = {}

        if self.architecture == "model_a":
            sem_pred = predictions
            total_loss = self.semantic_loss_fn(
                y_true=semantic_true,
                y_pred=sem_pred,
                sample_weight=semantic_weight,
            )
            loss_metrics["semantic_loss"] = float(total_loss.numpy())

        elif self.architecture == "model_b":
            sem_pred, haz_pred = predictions
            total_loss, l_sem, l_haz = self.joint_loss_fn(
                semantic_true=semantic_true,
                hazard_true=hazard_true if hazard_true is not None else tf.zeros_like(haz_pred),
                semantic_pred=sem_pred,
                hazard_pred=haz_pred,
                semantic_weight=semantic_weight,
                hazard_valid=hazard_valid if hazard_valid is not None else tf.zeros_like(haz_pred),
            )
            loss_metrics["semantic_loss"] = float(l_sem.numpy())
            loss_metrics["hazard_loss"] = float(l_haz.numpy())

        elif self.architecture == "model_c":
            if isinstance(predictions, list) and len(predictions) > 1:
                sem_pred, haz_pred = predictions
            else:
                sem_pred = predictions
                haz_pred = None

            l_sem = self.semantic_loss_fn(
                y_true=semantic_true,
                y_pred=sem_pred,
                sample_weight=semantic_weight,
            )
            loss_metrics["semantic_loss"] = float(l_sem.numpy())
            total_loss = l_sem

            if self.teacher_model is not None:
                teacher_preds = self.teacher_model(images, training=False)
                teacher_sem = teacher_preds[0] if isinstance(teacher_preds, list) else teacher_preds
                l_distill = self.kl_distill_fn(
                    teacher_pred=teacher_sem,
                    student_pred=sem_pred,
                    sample_weight=semantic_weight,
                )
                total_loss = total_loss + l_distill
                loss_metrics["distill_loss"] = float(l_distill.numpy())

            if haz_pred is not None and hazard_true is not None and hazard_valid is not None:
                l_haz = self.hazard_loss_fn(
                    y_true=hazard_true,
                    y_pred=haz_pred,
                    sample_weight=hazard_valid,
                )
                total_loss = total_loss + 0.5 * l_haz
                loss_metrics["hazard_loss"] = float(l_haz.numpy())
        else:
            raise ValueError(f"Unknown architecture: {self.architecture}")

        loss_metrics["total_loss"] = float(total_loss.numpy())
        return total_loss, loss_metrics

    def train_micro_step(
        self,
        images: tf.Tensor,
        semantic_true: tf.Tensor,
        semantic_weight: tf.Tensor,
        hazard_true: Optional[tf.Tensor] = None,
        hazard_valid: Optional[tf.Tensor] = None,
        sample_count: Optional[int] = None,
    ) -> Tuple[bool, Dict[str, float]]:
        """Processes one micro-batch, accumulating gradients and stepping optimizer when threshold met."""
        trainable_vars = self.model.trainable_variables
        if len(self.accumulated_gradients) != len(trainable_vars):
            self._init_accumulator()

        with tf.GradientTape() as tape:
            loss, metrics = self.compute_loss(
                images=images,
                semantic_true=semantic_true,
                semantic_weight=semantic_weight,
                hazard_true=hazard_true,
                hazard_valid=hazard_valid,
                training=True,
            )

        grads = tape.gradient(loss, trainable_vars)
        if sample_count is None:
            sample_count = int(tf.shape(images)[0].numpy())
        if isinstance(sample_count, bool) or not isinstance(sample_count, int) or sample_count < 1:
            raise ValueError("sample_count must be a positive integer.")

        for i, (g, v) in enumerate(zip(grads, trainable_vars)):
            if g is not None:
                self.accumulated_gradients[i] = (
                    self.accumulated_gradients[i] + g * tf.cast(sample_count, g.dtype)
                )

        self.accumulation_counter += 1
        self.accumulated_samples += sample_count
        stepped = False

        if self.accumulation_counter >= self.accumulate_steps:
            self._apply_accumulated_gradients()
            stepped = True

        return stepped, metrics

    def flush_accumulated_gradients(self) -> bool:
        """Flushes remaining accumulated gradients at the end of an epoch."""
        if self.accumulation_counter > 0:
            self._apply_accumulated_gradients()
            return True
        return False

    def _apply_accumulated_gradients(self):
        """Scales, clips, and applies accumulated gradients once, then resets buffer."""
        trainable_vars = self.model.trainable_variables
        if self.accumulated_samples <= 0 or self.accumulation_counter <= 0:
            raise RuntimeError("Cannot apply an empty gradient accumulation window.")
        scale = 1.0 / float(self.accumulated_samples)

        scaled_grads = [g * scale for g in self.accumulated_gradients]
        clipped_grads, norm = tf.clip_by_global_norm(scaled_grads, self.clipnorm)
        self.optimizer.apply_gradients(zip(clipped_grads, trainable_vars))
        self._init_accumulator()


# ==============================================================================
# Balanced Training Loop
# ==============================================================================

def _positive_integer(value: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} must be a positive integer.")
    return value


def _resolved_checkpoint_dir(path: Path) -> Path:
    checkpoint_dir = path.expanduser().resolve()
    if (checkpoint_dir / "metadata.json").is_file():
        return checkpoint_dir
    if checkpoint_dir.name in {"latest", "best"}:
        backup = checkpoint_dir.parent / f".{checkpoint_dir.name}.previous"
        if (backup / "metadata.json").is_file():
            return backup
    return checkpoint_dir


def _checkpoint_metadata(path: Path) -> CheckpointMetadata:
    checkpoint_dir = _resolved_checkpoint_dir(path)
    metadata_path = checkpoint_dir / "metadata.json"
    if not metadata_path.is_file():
        raise FileNotFoundError(
            f"Checkpoint directory must contain metadata.json: {checkpoint_dir}"
        )
    try:
        metadata = CheckpointMetadata(**json.loads(metadata_path.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError, TypeError) as exc:
        raise ValueError(f"Invalid checkpoint metadata at {metadata_path}: {exc}") from exc
    if metadata.schema_version != 1:
        raise ValueError(
            f"Unsupported checkpoint schema_version {metadata.schema_version} at {metadata_path}."
        )
    return metadata


def _checkpoint_weights_path(path: Path) -> Path:
    checkpoint_dir = _resolved_checkpoint_dir(path)
    for candidate in (
        checkpoint_dir / "model_weights.weights.h5",
        checkpoint_dir / "model_weights.ckpt",
    ):
        if candidate.is_file() or candidate.with_suffix(candidate.suffix + ".index").is_file():
            return candidate
    raise FileNotFoundError(f"No model weights found in checkpoint directory: {checkpoint_dir}")


def _load_initialized_weights(
    model: keras.Model,
    checkpoint_path: Path,
    expected_stage: str,
    expected_architecture: str,
    manifest_hash: str,
) -> tuple[CheckpointMetadata, str]:
    metadata = _checkpoint_metadata(checkpoint_path)
    if metadata.stage != expected_stage or metadata.architecture != expected_architecture:
        raise ValueError(
            f"Initialization checkpoint must be {expected_stage}/{expected_architecture}; "
            f"found {metadata.stage}/{metadata.architecture}."
        )
    if metadata.data_manifest_hash != manifest_hash:
        raise ValueError(
            "Initialization checkpoint manifest hash does not match the current dataset manifest."
        )
    if metadata.epoch < 1:
        raise ValueError(
            f"Initialization checkpoint for {expected_stage} records no completed training epoch "
            f"(epoch={metadata.epoch}); it holds only that stage's own untrained initial weights. "
            "Use a checkpoint from a stage run that improved at least once."
        )
    weights_path = _checkpoint_weights_path(checkpoint_path)
    model.load_weights(str(weights_path))
    return metadata, compute_sha256(weights_path)


def _iter_limited_batches(
    dataset: PortraitDataset,
    batch_size: int,
    *,
    epoch: int,
    augment: bool,
    balanced: bool,
    max_samples: Optional[int] = None,
):
    remaining = max_samples
    effective_batch_size = min(batch_size, max_samples) if max_samples is not None else batch_size
    batches = dataset.iter_batches(
        effective_batch_size, epoch=epoch, augment=augment, balanced=balanced
    )
    for batch in batches:
        if remaining is None:
            yield batch
            continue
        count = min(len(batch["id"]), remaining)
        if count <= 0:
            break
        yield {
            key: value[:count]
            for key, value in batch.items()
        }
        remaining -= count
        if remaining == 0:
            break


def _scan_training_statistics(
    dataset: PortraitDataset,
    batch_size: int,
    max_samples: Optional[int] = None,
) -> tuple[list[int], dict[str, int]]:
    class_counts = np.zeros(NUM_SEMANTIC_CLASSES, dtype=np.int64)
    source_counts = {"cityscapes": 0, "surface": 0}
    for batch in _iter_limited_batches(
        dataset,
        batch_size,
        epoch=0,
        augment=False,
        balanced=False,
        max_samples=max_samples,
    ):
        for source in batch["source"]:
            source_counts[source] += 1
        real = batch["semantic_source"] == 1
        labels = batch["semantic"][real]
        if labels.size:
            class_counts += np.bincount(labels, minlength=NUM_SEMANTIC_CLASSES)
    if class_counts.shape != (NUM_SEMANTIC_CLASSES,) or not np.all(np.isfinite(class_counts)):
        raise ValueError("Real-label training pixel frequencies are malformed or non-finite.")
    if np.any(class_counts < 0) or not np.any(class_counts > 0):
        raise ValueError(
            "Real-label training data must contain a non-empty, finite semantic class-frequency vector."
        )
    if not any(source_counts.values()):
        raise ValueError("Training dataset contains no records.")
    return class_counts.tolist(), source_counts


def _stage_configuration_hash(
    stage: str,
    stage_spec: Dict[str, Any],
    epochs: int,
    learning_rate: float,
    batch_size: int,
    accumulate_steps: int,
    clipnorm: float,
    class_weights: Sequence[float],
    initialization_mode: str,
) -> str:
    config = {
        "stage": stage,
        "stage_spec": stage_spec,
        "epochs": epochs,
        "learning_rate": learning_rate,
        "batch_size": batch_size,
        "accumulate_steps": accumulate_steps,
        "clipnorm": clipnorm,
        "class_weights": list(class_weights),
        "initialization_mode": initialization_mode,
    }
    return hashlib.sha256(
        json.dumps(config, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    ).hexdigest()


def _validate_stage_c_evidence(
    evidence_path: Path,
    checkpoint_path: Path,
    manifest_hash: str,
) -> tuple[CheckpointMetadata, str]:
    metadata = _checkpoint_metadata(checkpoint_path)
    if metadata.stage != "stage_b_joint" or metadata.architecture != "model_b":
        raise ValueError("Stage C teacher must be a stage_b_joint Model B checkpoint.")
    if metadata.data_manifest_hash != manifest_hash:
        raise ValueError("Stage C teacher checkpoint was trained against a different manifest.")
    weights_path = _checkpoint_weights_path(checkpoint_path)
    checkpoint_hash = compute_sha256(weights_path)
    try:
        evidence = json.loads(evidence_path.expanduser().resolve().read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Unable to read Stage C acceptance evidence {evidence_path}: {exc}") from exc
    if not isinstance(evidence, dict):
        raise ValueError("Stage C acceptance evidence must be a JSON object.")
    if type(evidence.get("schema_version")) is not int or evidence.get("schema_version") != 1:
        raise ValueError("Stage C acceptance evidence schema_version must be integer 1.")
    required = {
        "accepted": True,
        "stage": "stage_b_joint",
        "checkpoint_sha256": checkpoint_hash,
        "manifest_sha256": manifest_hash,
    }
    for key, expected in required.items():
        if evidence.get(key) != expected:
            raise ValueError(
                f"Stage C acceptance evidence field {key!r} must be {expected!r}; "
                f"found {evidence.get(key)!r}."
            )
    evidence_dir = evidence_path.expanduser().resolve().parent

    def evidence_file(path_key: str, hash_key: str, label: str) -> Path:
        value = evidence.get(path_key)
        digest = evidence.get(hash_key)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"Stage C evidence must identify its {label}.")
        if not isinstance(digest, str) or len(digest) != 64 or any(
            character not in "0123456789abcdef" for character in digest
        ):
            raise ValueError(f"Stage C evidence must include the {label} SHA-256.")
        file_path = Path(value)
        if not file_path.is_absolute():
            file_path = evidence_dir / file_path
        if not file_path.is_file() or compute_sha256(file_path) != digest:
            raise ValueError(f"Stage C evidence {label} is missing or its hash does not match.")
        return file_path

    report_path = evidence_file(
        "evaluation_report_path", "evaluation_report_sha256", "B acceptance report"
    )
    export_manifest_path = evidence_file(
        "export_manifest_path", "export_manifest_sha256", "B export manifest"
    )
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        export_manifest = json.loads(export_manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Invalid Stage C evaluator/export evidence: {exc}") from exc
    if not isinstance(report, dict) or not isinstance(export_manifest, dict):
        raise ValueError("Stage C evaluator and export evidence must be JSON objects.")

    model_hash = evidence.get("model_sha256")
    if not isinstance(model_hash, str) or len(model_hash) != 64 or any(
        character not in "0123456789abcdef" for character in model_hash
    ):
        raise ValueError("Stage C evidence must include the exported Model B SHA-256.")
    provenance = export_manifest.get("checkpoint_provenance")
    if (
        type(export_manifest.get("schema_version")) is not int
        or export_manifest.get("schema_version") != 2
        or export_manifest.get("architecture") != "model_b"
        or export_manifest.get("model_sha256") != model_hash
        or not isinstance(provenance, dict)
        or provenance.get("stage") != "stage_b_joint"
        or provenance.get("architecture") != "model_b"
        or provenance.get("data_manifest_hash") != manifest_hash
        or not isinstance(export_manifest.get("parity"), dict)
        or export_manifest["parity"].get("status") != "verified"
    ):
        raise ValueError(
            "Stage C export manifest must identify the parity-verified Model B artifact "
            "and matching stage/manifest checkpoint provenance."
        )
    for key, value in asdict(metadata).items():
        if provenance.get(key) != value:
            raise ValueError(
                f"Stage C export provenance field {key!r} does not match the selected "
                "Model B checkpoint metadata."
            )

    semantic_acceptance = report.get("semantic_model_acceptance")
    warning_evidence = report.get("warning_enablement")
    report_gates = report.get("gates")
    semantic_gates = (
        semantic_acceptance.get("gates")
        if isinstance(semantic_acceptance, dict)
        else None
    )
    if (
        type(report.get("schema_version")) is not int
        or report.get("schema_version") != 1
        or report.get("comparison") != "B_against_baseline"
        or report.get("passed") is not True
        or report.get("trained_model_quality_claimed") is not False
        or not isinstance(report_gates, list)
        or not report_gates
        or any(not isinstance(gate, dict) or gate.get("passed") is not True for gate in report_gates)
        or not isinstance(semantic_acceptance, dict)
        or semantic_acceptance.get("passed") is not True
        or not isinstance(semantic_gates, list)
        or not semantic_gates
        or any(not isinstance(gate, dict) or gate.get("passed") is not True for gate in semantic_gates)
        or not isinstance(warning_evidence, dict)
        or warning_evidence.get("model_sha256") != model_hash
        or warning_evidence.get("model_hash_match") is not True
    ):
        raise ValueError(
            "Stage C requires a passing B-against-baseline held-out evaluator comparison "
            "whose saved-model hash matches the exported Model B artifact."
        )
    return metadata, checkpoint_hash


def _set_stage_trainability(model: keras.Model, stage: str, stage_spec: Dict[str, Any]) -> None:
    if stage == "stage_b_head":
        model.trainable = True
        for layer in model.layers:
            layer.trainable = False
        for name in ("risk_conv3x3", "hazard_output"):
            model.get_layer(name).trainable = True
    else:
        set_encoder_trainability(model, policy=stage_spec["encoder_policy"])
    freeze_batch_normalization(model)
    if stage == "stage_b_head":
        trainable_names = {
            getattr(variable, "path", variable.name).split("/")[0]
            for variable in model.trainable_variables
        }
        if not trainable_names or not trainable_names <= {"risk_conv3x3", "hazard_output"}:
            raise RuntimeError(
                f"Stage B head must train only risk_conv3x3 and hazard_output; found {trainable_names}."
            )


def _versions() -> Dict[str, str]:
    from PIL import __version__ as pillow_version

    return {
        "python": platform.python_version(),
        "tensorflow": str(tf.__version__),
        "numpy": str(np.__version__),
        "pillow": str(pillow_version),
    }


def train_stage(
    stage_name: str,
    manifest_path: Path,
    output_dir: Path,
    pseudo_manifest_path: Optional[Path] = None,
    checkpoint_a_path: Optional[Path] = None,
    teacher_checkpoint_path: Optional[Path] = None,
    resume_path: Optional[Path] = None,
    weights: Optional[str] = "imagenet",
    batch_size: int = DEFAULT_BATCH_SIZE,
    accumulate_steps: int = DEFAULT_ACCUMULATE_STEPS,
    learning_rate: Optional[float] = None,
    epochs: Optional[int] = None,
    clipnorm: float = DEFAULT_CLIPNORM,
    seed: int = DEFAULT_SEED,
    device: str = "cpu",
    allow_partial: bool = False,
    init_checkpoint_path: Optional[Path] = None,
    teacher_evidence_path: Optional[Path] = None,
    verification_samples: Optional[int] = None,
) -> Dict[str, Any]:
    """Runs one real, streamed stage and validates all initialization/resume state."""
    _check_runtime_tf()
    if stage_name not in STAGE_SPECS:
        raise ValueError(f"Unknown training stage {stage_name!r}.")
    _positive_integer(batch_size, "batch_size")
    _positive_integer(accumulate_steps, "accumulate_steps")
    if epochs is not None:
        _positive_integer(epochs, "epochs")
    if verification_samples is not None:
        _positive_integer(verification_samples, "verification_samples")
    if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
        raise ValueError("seed must be a non-negative integer.")
    if not math.isfinite(float(clipnorm)) or clipnorm <= 0:
        raise ValueError("clipnorm must be finite and positive.")
    stage_spec = STAGE_SPECS[stage_name]
    lr = stage_spec["learning_rate"] if learning_rate is None else float(learning_rate)
    if not math.isfinite(lr) or lr <= 0:
        raise ValueError("learning_rate must be finite and positive.")
    if weights not in ("imagenet", None):
        raise ValueError("weights must be 'imagenet' or None.")
    total_epochs = stage_spec["epochs"] if epochs is None else epochs
    if allow_partial:
        if verification_samples is None or verification_samples > MAX_DIAGNOSTIC_SAMPLES:
            raise ValueError(
                f"--allow-partial-manifest requires --verification-samples in "
                f"1..{MAX_DIAGNOSTIC_SAMPLES}."
            )
        if total_epochs != 1:
            raise ValueError("Diagnostic partial-manifest verification is limited to exactly one epoch.")
    elif verification_samples is not None:
        raise ValueError("--verification-samples is valid only with --allow-partial-manifest.")
    if checkpoint_a_path is not None:
        if init_checkpoint_path is not None:
            raise ValueError("Use only --init-checkpoint; --checkpoint-a is its deprecated alias.")
        init_checkpoint_path = checkpoint_a_path
    if resume_path is not None and init_checkpoint_path is not None:
        raise ValueError("--resume and --init-checkpoint are mutually exclusive.")
    if resume_path is not None and stage_name != "stage_c" and teacher_checkpoint_path is not None:
        raise ValueError("A same-stage --resume cannot be combined with a teacher checkpoint outside stage_c.")
    configure_device(device=device)

    architecture = stage_spec["architecture"]
    previous_stage = {
        "city_full": ("city_warmup", "model_a"),
        "mixed_decoder": ("city_full", "model_a"),
        "mixed_block13": ("mixed_decoder", "model_a"),
        "stage_b_head": ("mixed_block13", "model_a"),
        "stage_b_joint": ("stage_b_head", "model_b"),
    }
    if stage_name == "city_warmup" and init_checkpoint_path is not None:
        raise ValueError("city_warmup must start fresh from ImageNet or weights=None, not an init checkpoint.")
    if stage_name == "city_warmup" and weights not in ("imagenet", None):
        raise ValueError("city_warmup supports only fresh ImageNet initialization or weights=None.")
    if stage_name in previous_stage and resume_path is None and init_checkpoint_path is None:
        expected = previous_stage[stage_name][0]
        raise ValueError(f"{stage_name} requires --init-checkpoint from the {expected} stage.")
    if stage_name == "stage_c":
        if teacher_checkpoint_path is None or teacher_evidence_path is None:
            raise ValueError(
                "Stage C is disabled until accepted Model B evidence is available; supply both "
                "--teacher-checkpoint and --teacher-evidence."
            )
    elif teacher_checkpoint_path is not None or teacher_evidence_path is not None:
        raise ValueError("Teacher checkpoint/evidence are valid only for stage_c.")

    manifest_path = manifest_path.expanduser().resolve()
    output_dir = Path(output_dir)
    stage_ckpt_dir = output_dir / stage_name / "checkpoints"
    if resume_path is None and stage_ckpt_dir.exists() and any(stage_ckpt_dir.iterdir()):
        raise FileExistsError(
            f"{stage_ckpt_dir} already contains checkpoint state; a fresh {stage_name} run would "
            "overwrite it. Use --resume to continue it, or choose an empty --output-dir."
        )
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Training manifest not found: {manifest_path}")
    manifest_hash = compute_sha256(manifest_path)
    pseudo_hash = (
        compute_sha256(pseudo_manifest_path.expanduser().resolve())
        if pseudo_manifest_path is not None
        else None
    )

    filter_sources = stage_spec["dataset_filter"]
    train_dataset = PortraitDataset(
        manifest_path,
        split="train",
        sources=filter_sources,
        pseudo_manifest_path=pseudo_manifest_path,
        seed=seed,
        require_complete=not allow_partial,
    )
    val_dataset = PortraitDataset(
        manifest_path,
        split="val",
        sources=filter_sources,
        seed=seed,
        require_complete=not allow_partial,
    )
    if not train_dataset.training_eligible and not allow_partial:
        raise ValueError("Training loader did not mark this complete dataset training-eligible.")
    if allow_partial and train_dataset.training_eligible:
        raise ValueError("Diagnostic partial-manifest mode cannot be reported as complete training.")
    if val_dataset.sample_count() <= 0:
        raise ValueError(f"Stage {stage_name} requires a non-empty validation dataset.")

    class_counts, source_counts = _scan_training_statistics(
        train_dataset, batch_size, verification_samples
    )
    if filter_sources == "mixed" and any(source_counts[source] == 0 for source in source_counts):
        raise ValueError("Mixed training requires at least one complete train record from each source.")
    class_weights = compute_inverse_sqrt_class_weights(class_counts)
    if (
        len(class_weights) != NUM_SEMANTIC_CLASSES
        or not all(math.isfinite(value) and 0.5 <= value <= 3.0 for value in class_weights)
    ):
        raise ValueError("Real-label training class weights must be finite and clipped to [0.5, 3.0].")
    initialization_mode = (
        ("imagenet" if weights == "imagenet" else "none")
        if stage_name in ("city_warmup", "stage_c")
        else "checkpoint"
    )
    config_hash = _stage_configuration_hash(
        stage_name,
        stage_spec,
        total_epochs,
        lr,
        batch_size,
        accumulate_steps,
        clipnorm,
        class_weights,
        initialization_mode,
    )

    print("=" * 70)
    print(f"Staged Trainer: {stage_name} ({architecture})")
    print(f"Epochs: {total_epochs} | LR: {lr} | Batch: {batch_size} | Accumulate: {accumulate_steps}")
    print(f"Device: {device} | Seed: {seed} | Diagnostic partial: {allow_partial}")
    print("=" * 70)

    tf.random.set_seed(seed)
    np.random.seed(seed)
    random.seed(seed)

    initialization_hash = None
    teacher_hash = None
    teacher_model = None
    if architecture == "model_a":
        fresh_weights = (
            weights
            if stage_name == "city_warmup" and resume_path is None
            else None
        )
        model = build_model_a(
            weights=fresh_weights, freeze_bn=False, input_range="0_255"
        )
        if init_checkpoint_path is not None:
            previous, initialization_hash = _load_initialized_weights(
                model,
                init_checkpoint_path,
                *previous_stage[stage_name],
                manifest_hash,
            )
    elif architecture == "model_b":
        if resume_path is not None:
            model = build_model_b(weights=None, freeze_bn=False, input_range="0_255")
        elif stage_name == "stage_b_head":
            model_a = build_model_a(weights=None, freeze_bn=False, input_range="0_255")
            _, initialization_hash = _load_initialized_weights(
                model_a, init_checkpoint_path, "mixed_block13", "model_a", manifest_hash
            )
            model = attach_risk_head(model_a)
        else:
            model = build_model_b(weights=None, freeze_bn=False, input_range="0_255")
            _, initialization_hash = _load_initialized_weights(
                model, init_checkpoint_path, "stage_b_head", "model_b", manifest_hash
            )
    elif architecture == "model_c":
        _, teacher_hash = _validate_stage_c_evidence(
            teacher_evidence_path, teacher_checkpoint_path, manifest_hash
        )
        teacher_model = build_model_b(weights=None, freeze_bn=True, input_range="0_255")
        teacher_model.load_weights(str(_checkpoint_weights_path(teacher_checkpoint_path)))
        teacher_model.trainable = False
        model = build_model_c(
            weights=weights if resume_path is None else None,
            freeze_bn=False,
            input_range="0_255",
            include_hazard_head=True,
        )
    else:
        raise ValueError(f"Unknown architecture: {architecture}")

    _set_stage_trainability(model, stage_name, stage_spec)
    stage_ckpt_dir = output_dir / stage_name / "checkpoints"
    trainer = StagedTrainer(
        model=model,
        architecture=architecture,
        stage=stage_name,
        learning_rate=lr,
        clipnorm=clipnorm,
        batch_size=batch_size,
        accumulate_steps=accumulate_steps,
        class_weights=class_weights,
        checkpoint_dir=stage_ckpt_dir,
        teacher_model=teacher_model,
    )
    patience = stage_spec.get("patience")
    expected_resume = {
        "stage": stage_name,
        "architecture": architecture,
        "seed": seed,
        "batch_size": batch_size,
        "accumulate_steps": accumulate_steps,
        "learning_rate": lr,
        "class_weights": class_weights,
        "clipnorm": clipnorm,
        "patience": patience,
        "pseudo_manifest_hash": pseudo_hash,
        "teacher_checkpoint_hash": teacher_hash,
        "initialization_mode": initialization_mode,
        "stage_config_hash": config_hash,
        "optimizer": "Adam",
    }

    start_epoch = 1
    global_step = 0
    best_val_loss = float("inf")
    best_val_miou = 0.0
    best_semantic_loss = float("inf")
    best_hazard_loss = None
    patience_counter = 0
    semantic_baseline = None
    latest_epoch = 0
    if resume_path is not None:
        resume_manager = CheckpointManager(resume_path.expanduser().resolve())
        metadata, restored_acc, accumulation_counter = resume_manager.restore_checkpoint(
            model=model,
            optimizer=trainer.optimizer,
            expected_manifest_hash=manifest_hash,
            expected_metadata=expected_resume,
        )
        if metadata.epoch < 0 or metadata.epoch >= total_epochs:
            raise ValueError(
                f"Resume checkpoint epoch {metadata.epoch} leaves no epochs in requested "
                f"1..{total_epochs} schedule."
            )
        if patience is not None and metadata.patience_counter >= patience:
            raise ValueError(
                f"Resume checkpoint already reached early stopping "
                f"(patience_counter={metadata.patience_counter} >= {patience}); nothing to resume."
            )
        if restored_acc is None or accumulation_counter != 0:
            raise ValueError("Resume checkpoint lacks complete epoch-boundary accumulator state.")
        trainer.accumulated_gradients = [tf.convert_to_tensor(value) for value in restored_acc]
        trainer.accumulation_counter = 0
        trainer.accumulated_samples = 0
        start_epoch = metadata.epoch + 1
        global_step = metadata.global_step
        best_val_loss = metadata.best_val_loss
        best_val_miou = metadata.best_val_miou
        best_semantic_loss = metadata.best_val_semantic_loss
        best_hazard_loss = metadata.best_val_hazard_loss
        patience_counter = metadata.patience_counter
        semantic_baseline = metadata.semantic_baseline
        latest_epoch = metadata.epoch
    else:
        initial_metrics = evaluate_validation_split(
            trainer, val_dataset, batch_size, epoch=0, max_samples=verification_samples
        )
        best_val_loss = initial_metrics["total_loss"]
        best_val_miou = initial_metrics["mean_iou"]
        best_semantic_loss = initial_metrics["semantic_loss"]
        best_hazard_loss = initial_metrics["hazard_loss"]
        if architecture == "model_b":
            semantic_baseline = initial_metrics["semantic_loss"]
        initial_metadata = CheckpointMetadata(
            stage=stage_name,
            epoch=0,
            architecture=architecture,
            seed=seed,
            batch_size=batch_size,
            accumulate_steps=accumulate_steps,
            learning_rate=lr,
            data_manifest_hash=manifest_hash,
            pseudo_manifest_hash=pseudo_hash,
            teacher_checkpoint_hash=teacher_hash,
            initialization_mode=initialization_mode,
            class_weights=class_weights,
            clipnorm=clipnorm,
            patience=patience,
            semantic_baseline=semantic_baseline,
            stage_config_hash=config_hash,
            best_val_loss=best_val_loss,
            best_val_miou=best_val_miou,
            best_val_semantic_loss=best_semantic_loss,
            best_val_hazard_loss=best_hazard_loss,
        )
        for tag in ("latest", "best"):
            trainer.ckpt_manager.save_checkpoint(
                model,
                trainer.optimizer,
                initial_metadata,
                trainer.accumulated_gradients,
                trainer.accumulation_counter,
                tag=tag,
            )

    if start_epoch > total_epochs:
        raise ValueError("No training epochs remain after restoring this checkpoint.")

    history: List[Dict[str, Any]] = []
    balanced = filter_sources == "mixed"
    if stage_name == "city_warmup":
        initial_weight_option = initialization_mode
        fallback_restart = (
            f"restart fresh with --weights {initial_weight_option} "
            "--batch-size 1 --accumulate-steps 8 --output-dir <new empty directory>"
        )
    elif stage_name == "stage_c":
        fallback_restart = (
            f"restart with --teacher-checkpoint {teacher_checkpoint_path} "
            f"--teacher-evidence {teacher_evidence_path} "
            "--batch-size 1 --accumulate-steps 8 --output-dir <new empty directory>"
        )
    else:
        predecessor_path = str(init_checkpoint_path) if init_checkpoint_path else (
            "<required predecessor-stage best checkpoint>"
        )
        fallback_restart = (
            f"restart from --init-checkpoint {predecessor_path} "
            "--batch-size 1 --accumulate-steps 8 --output-dir <new empty directory>"
        )
    for epoch in range(start_epoch, total_epochs + 1):
        epoch_started = time.time()
        weighted_train_loss = 0.0
        train_sample_count = 0
        batches = _iter_limited_batches(
            train_dataset,
            batch_size,
            epoch=epoch - 1,
            augment=True,
            balanced=balanced,
            max_samples=verification_samples,
        )
        planned_samples = min(
            train_dataset.sample_count(epoch - 1, balanced),
            verification_samples if verification_samples is not None else
            train_dataset.sample_count(epoch - 1, balanced),
        )
        planned_batches = (planned_samples + batch_size - 1) // batch_size
        try:
            for batch_index, batch in enumerate(batches):
                image_batch = tf.convert_to_tensor(batch["image"], dtype=tf.float32)
                semantic_batch = tf.convert_to_tensor(batch["semantic"], dtype=tf.int32)
                weight_batch = tf.convert_to_tensor(batch["semantic_weight"], dtype=tf.float32)
                hazard_batch = tf.convert_to_tensor(batch["hazard"], dtype=tf.float32)
                hazard_valid_batch = tf.convert_to_tensor(batch["hazard_valid"], dtype=tf.float32)
                count = len(batch["id"])
                _, metrics = trainer.train_micro_step(
                    images=image_batch,
                    semantic_true=semantic_batch,
                    semantic_weight=weight_batch,
                    hazard_true=hazard_batch,
                    hazard_valid=hazard_valid_batch,
                    sample_count=count,
                )
                weighted_train_loss += metrics["total_loss"] * count
                train_sample_count += count
                global_step += 1
                if batch_index % 20 == 0:
                    print(
                        f"  Batch {batch_index + 1}/{planned_batches} "
                        f"- loss {metrics['total_loss']:.4f} "
                        f"- accumulate {trainer.accumulation_counter}/{trainer.accumulate_steps}"
                    )
            if train_sample_count == 0:
                raise ValueError(f"Training epoch {epoch} yielded no samples.")
            trainer.flush_accumulated_gradients()
        except tf.errors.ResourceExhaustedError as exc:
            valid_checkpoint = stage_ckpt_dir / "latest"
            raise RuntimeError(
                f"Out of memory; automatic retry was not attempted because optimizer/state rollback "
                f"cannot be guaranteed. Last valid epoch-boundary checkpoint: {valid_checkpoint}. "
                f"Resume with the same batch/configuration via --resume {valid_checkpoint}; to use "
                f"the documented fallback, {fallback_restart}."
            ) from exc

        try:
            validation = evaluate_validation_split(
                trainer, val_dataset, batch_size, epoch=0, max_samples=verification_samples
            )
        except tf.errors.ResourceExhaustedError as exc:
            valid_checkpoint = stage_ckpt_dir / "latest"
            raise RuntimeError(
                f"Out of memory during validation; no automatic retry was attempted. Last valid "
                f"epoch-boundary checkpoint: {valid_checkpoint}; resume only with its saved "
                f"configuration or {fallback_restart}."
            ) from exc

        mean_train_loss = weighted_train_loss / train_sample_count
        nonregressed = (
            architecture != "model_b"
            or validation["semantic_loss"] <= float(semantic_baseline) + 1e-6
        )
        improved = nonregressed and validation["total_loss"] < best_val_loss
        if improved:
            best_val_loss = validation["total_loss"]
            best_val_miou = validation["mean_iou"]
            best_semantic_loss = validation["semantic_loss"]
            best_hazard_loss = validation["hazard_loss"]
            patience_counter = 0
        else:
            patience_counter += 1

        elapsed = time.time() - epoch_started
        epoch_report = {
            "epoch": epoch,
            "train_loss": mean_train_loss,
            "val_total_loss": validation["total_loss"],
            "val_semantic_loss": validation["semantic_loss"],
            "val_hazard_loss": validation["hazard_loss"],
            "val_mean_iou": validation["mean_iou"],
            "val_semantic_nonregressed": nonregressed,
            "validation_metrics": validation["metrics"],
            "elapsed_seconds": elapsed,
        }
        history.append(epoch_report)
        metadata = CheckpointMetadata(
            stage=stage_name,
            epoch=epoch,
            global_step=global_step,
            architecture=architecture,
            seed=seed,
            batch_size=batch_size,
            accumulate_steps=accumulate_steps,
            learning_rate=lr,
            data_manifest_hash=manifest_hash,
            pseudo_manifest_hash=pseudo_hash,
            teacher_checkpoint_hash=teacher_hash,
            initialization_mode=initialization_mode,
            class_weights=class_weights,
            clipnorm=clipnorm,
            patience=patience,
            patience_counter=patience_counter,
            semantic_baseline=semantic_baseline,
            stage_config_hash=config_hash,
            optimizer="Adam",
            best_val_loss=best_val_loss,
            best_val_miou=best_val_miou,
            best_val_semantic_loss=best_semantic_loss,
            best_val_hazard_loss=best_hazard_loss,
        )
        trainer.ckpt_manager.save_checkpoint(
            model, trainer.optimizer, metadata, trainer.accumulated_gradients,
            trainer.accumulation_counter, tag="latest"
        )
        latest_epoch = epoch
        if improved:
            trainer.ckpt_manager.save_checkpoint(
                model, trainer.optimizer, metadata, trainer.accumulated_gradients,
                trainer.accumulation_counter, tag="best"
            )
        print(
            f"Epoch {epoch}/{total_epochs} | train {mean_train_loss:.4f} | "
            f"val semantic {validation['semantic_loss']:.4f} | "
            f"val total {validation['total_loss']:.4f} | mIoU {validation['mean_iou']:.4f}"
        )
        if patience is not None and patience_counter >= patience:
            print(f"Early stopping after {patience} validation epochs without a better checkpoint.")
            break

    summary = {
        "stage": stage_name,
        "architecture": architecture,
        "epochs_completed": latest_epoch,
        "best_val_total_loss": best_val_loss,
        "best_val_semantic_loss": best_semantic_loss,
        "best_val_hazard_loss": best_hazard_loss,
        "semantic_baseline": semantic_baseline,
        "history": history,
        "manifest_sha256": manifest_hash,
        "pseudo_manifest_sha256": pseudo_hash,
        "source_record_counts": source_counts,
        "real_training_class_pixel_counts": class_counts,
        "class_weights": class_weights,
        "seed": seed,
        "initialization_mode": initialization_mode,
        "device": device,
        "software_versions": _versions(),
        "initialization_checkpoint_sha256": initialization_hash,
        "teacher_checkpoint_sha256": teacher_hash,
        "latest_checkpoint_weights_sha256": compute_sha256(
            _checkpoint_weights_path(stage_ckpt_dir / "latest")
        ),
        "best_checkpoint_weights_sha256": compute_sha256(
            _checkpoint_weights_path(stage_ckpt_dir / "best")
        ),
        "trainable_variable_paths": [
            getattr(variable, "path", variable.name)
            for variable in model.trainable_variables
        ],
        "training_eligible": train_dataset.training_eligible,
        "verification_sample_limit": verification_samples,
        "diagnostics": {
            "train": train_dataset.diagnostics,
            "validation": val_dataset.diagnostics,
        },
        "acceptance_claim": (
            "NONE; validation is used only for training-stage selection and no held-out test "
            "evaluation or model acceptance is claimed."
        ),
    }
    summary_path = output_dir / stage_name / "training_summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary["summary_path"] = str(summary_path.resolve())
    summary_path.write_text(json.dumps(summary, indent=2, allow_nan=False), encoding="utf-8")
    return summary


def evaluate_validation_split(
    trainer: StagedTrainer,
    val_dataset: PortraitDataset,
    batch_size: int = DEFAULT_BATCH_SIZE,
    epoch: int = 0,
    max_samples: Optional[int] = None,
) -> Dict[str, Any]:
    """Computes sample-weighted validation losses and real-label domain/object metrics."""
    _positive_integer(batch_size, "batch_size")
    if val_dataset.sample_count(epoch=epoch) <= 0:
        raise ValueError("Validation requires at least one sample; empty validation is an error.")
    metric_by_source: Dict[str, StreamingConfusionMatrix] = {}
    risk_by_source: Dict[str, StreamingRiskMetrics] = {}
    total_loss_sum = semantic_loss_sum = hazard_loss_sum = 0.0
    sample_count = 0
    for batch in _iter_limited_batches(
        val_dataset,
        batch_size,
        epoch=epoch,
        augment=False,
        balanced=False,
        max_samples=max_samples,
    ):
        images = tf.convert_to_tensor(batch["image"], dtype=tf.float32)
        semantic = tf.convert_to_tensor(batch["semantic"], dtype=tf.int32)
        semantic_weight = tf.convert_to_tensor(batch["semantic_weight"], dtype=tf.float32)
        hazard = tf.convert_to_tensor(batch["hazard"], dtype=tf.float32)
        hazard_valid = tf.convert_to_tensor(batch["hazard_valid"], dtype=tf.float32)
        loss, loss_metrics = trainer.compute_loss(
            images=images,
            semantic_true=semantic,
            semantic_weight=semantic_weight,
            hazard_true=hazard,
            hazard_valid=hazard_valid,
            training=False,
        )
        predictions = trainer.model(images, training=False)
        semantic_prediction = predictions[0] if isinstance(predictions, (tuple, list)) else predictions
        predicted_ids = tf.argmax(semantic_prediction, axis=-1).numpy()
        hazard_prediction = predictions[1].numpy() if isinstance(predictions, (tuple, list)) and len(predictions) > 1 else None
        count = len(batch["id"])
        total_loss_sum += float(loss.numpy()) * count
        semantic_loss_sum += loss_metrics["semantic_loss"] * count
        if "hazard_loss" in loss_metrics:
            hazard_loss_sum += loss_metrics["hazard_loss"] * count
        sample_count += count

        for index, source in enumerate(batch["source"]):
            confusion = metric_by_source.setdefault(
                source, StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
            )
            confusion.update(
                truth=batch["semantic"][index].reshape(-1),
                prediction=predicted_ids[index].reshape(-1),
                provenance=batch["semantic_source"][index].reshape(-1),
            )
            if hazard_prediction is not None:
                risk = risk_by_source.setdefault(source, StreamingRiskMetrics())
                risk.update(
                    truth=batch["hazard"][index].reshape(-1),
                    score=hazard_prediction[index].reshape(-1),
                    valid=batch["hazard_valid"][index].reshape(-1),
                    image_id=batch["id"][index],
                    group_id=batch["group_id"][index],
                )
    if sample_count == 0:
        raise ValueError("Validation dataset yielded no samples.")
    overall_confusion = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
    # Merge source matrices so the overall report is derived from the exact per-domain metrics.
    for confusion in metric_by_source.values():
        for truth_id, row in enumerate(confusion.matrix):
            for predicted_id, count in enumerate(row):
                overall_confusion.matrix[truth_id][predicted_id] += count
        overall_confusion.valid_pixels += confusion.valid_pixels
    if overall_confusion.valid_pixels == 0:
        raise ValueError("Validation records contain no real semantic-label pixels.")
    overall = overall_confusion.summary(class_names=CLASS_NAMES)
    mean_iou = overall.get("mean_iou")
    return {
        "total_loss": total_loss_sum / sample_count,
        "semantic_loss": semantic_loss_sum / sample_count,
        "hazard_loss": hazard_loss_sum / sample_count if trainer.architecture == "model_b" else None,
        "mean_iou": float(mean_iou) if mean_iou is not None else 0.0,
        "metrics": {
            "semantic_overall": overall,
            "semantic_by_source": {
                source: confusion.summary(class_names=CLASS_NAMES)
                for source, confusion in sorted(metric_by_source.items())
            },
            "hazard_by_source": {
                source: risk.summary()
                for source, risk in sorted(risk_by_source.items())
            },
        },
    }


# ==============================================================================
# Smoke Test Runner (Bounded CPU verification)
# ==============================================================================

def run_smoke_test(
    output_dir: Path,
    steps: int = 2,
    seed: int = DEFAULT_SEED,
) -> Dict[str, Any]:
    """Executes a bounded CPU smoke test verifying model mechanics and exportability."""
    _check_runtime_tf()
    _positive_integer(steps, "smoke_steps")
    if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
        raise ValueError("seed must be a non-negative integer.")
    configure_device("cpu", threads=2)

    print("=" * 70)
    print("Starting Bounded CPU Vision Training Smoke Test")
    print(f"Target steps: {steps} | Seed: {seed} | Device: CPU (threads=2)")
    print("=" * 70)

    output_dir = output_dir.resolve()
    smoke_dir = output_dir / "smoke"
    smoke_dir.mkdir(parents=True, exist_ok=True)

    tf.random.set_seed(seed)
    np.random.seed(seed)
    random.seed(seed)

    # 1. Build Model A with weights=None
    print("\n[Smoke 1/7] Initializing fresh Architecture A (weights=None)...")
    model_a = build_model_a(weights=None, freeze_bn=True, input_range="0_255")
    freeze_batch_normalization(model_a)

    target_layer = model_a.get_layer("semantic_output")
    initial_weights = [w.numpy().copy() for w in target_layer.trainable_weights]

    # 2. Setup StagedTrainer
    trainer = StagedTrainer(
        model=model_a,
        architecture="model_a",
        stage="city_warmup",
        learning_rate=1e-3,
        clipnorm=1.0,
        batch_size=2,
        accumulate_steps=2,
        checkpoint_dir=smoke_dir / "checkpoints",
    )

    # 3. Run bounded steps
    print(f"\n[Smoke 2/7] Executing {steps} bounded training micro-steps...")
    step_losses = []
    for step_idx in range(steps):
        img_np, sem_np, wt_np, _, _ = generate_synthetic_smoke_batch(
            batch_size=2, include_hazard=False, seed=seed + step_idx
        )
        stepped, metrics = trainer.train_micro_step(
            images=tf.constant(img_np),
            semantic_true=tf.constant(sem_np),
            semantic_weight=tf.constant(wt_np),
        )
        step_losses.append(metrics["total_loss"])
        print(f"  Step {step_idx + 1}/{steps} - Loss: {metrics['total_loss']:.4f} - Optimizer Stepped: {stepped}")

    flushed = trainer.flush_accumulated_gradients()
    if flushed:
        print("  Remainder gradients flushed successfully.")

    # 4. Verify parameter change
    print("\n[Smoke 3/7] Verifying parameter update...")
    updated_weights = [w.numpy() for w in target_layer.trainable_weights]
    weight_diffs = [np.max(np.abs(u - init)) for u, init in zip(updated_weights, initial_weights)]
    max_weight_diff = max(weight_diffs)
    print(f"  Max parameter change in semantic_output: {max_weight_diff:.6f}")
    if max_weight_diff <= 0.0:
        raise RuntimeError("Smoke test failure: Model parameters did not update after training steps!")

    # 5. Save checkpoint
    print("\n[Smoke 4/7] Saving checkpoint...")
    meta = CheckpointMetadata(
        stage="smoke_city_warmup",
        epoch=1,
        global_step=steps,
        architecture="model_a",
        seed=seed,
        batch_size=2,
        accumulate_steps=2,
        learning_rate=1e-3,
        best_val_loss=step_losses[-1],
    )
    ckpt_path = trainer.ckpt_manager.save_checkpoint(
        model=model_a,
        optimizer=trainer.optimizer,
        metadata=meta,
        accumulated_gradients=trainer.accumulated_gradients,
        accumulation_counter=trainer.accumulation_counter,
        tag="smoke_latest",
    )
    print(f"  Checkpoint saved to: {ckpt_path}")

    # 6. Restore checkpoint and verify bit-for-bit weight roundtrip
    print("\n[Smoke 5/7] Restoring checkpoint into fresh model...")
    fresh_model = build_model_a(weights=None, freeze_bn=True, input_range="0_255")
    restored_meta, _, _ = trainer.ckpt_manager.restore_checkpoint(fresh_model, tag="smoke_latest")
    restored_target_weights = [w.numpy() for w in fresh_model.get_layer("semantic_output").trainable_weights]
    roundtrip_diff = max(np.max(np.abs(r - u)) for r, u in zip(restored_target_weights, updated_weights))
    print(f"  Max roundtrip weight discrepancy: {roundtrip_diff:.8f}")
    if roundtrip_diff > 1e-6:
        raise RuntimeError(f"Smoke test failure: Checkpoint weight roundtrip discrepancy {roundtrip_diff} exceeds tolerance!")

    # 7. Export to TFLite and check parity
    print("\n[Smoke 6/7] Building export model and converting to TFLite...")
    export_model = build_export_model(fresh_model, batch_size=1, name="aria_smoke_export")
    converter = tf.lite.TFLiteConverter.from_keras_model(export_model)
    tflite_bytes = converter.convert()
    tflite_path = smoke_dir / "smoke_model_a.tflite"
    tflite_path.write_bytes(tflite_bytes)
    tflite_sha256 = hashlib.sha256(tflite_bytes).hexdigest()
    print(f"  Exported TFLite size: {len(tflite_bytes):,} bytes | SHA256: {tflite_sha256[:16]}...")

    print("\n[Smoke 7/7] Verifying Keras vs TFLite parity on sample input [0..1]...")
    sample_input = np.random.uniform(0.0, 1.0, size=(1, IMAGE_HEIGHT, IMAGE_WIDTH, 3)).astype(np.float32)
    keras_out = export_model(sample_input).numpy()

    interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    interpreter.set_tensor(input_details[0]["index"], sample_input)
    interpreter.invoke()
    tflite_out = interpreter.get_tensor(output_details[0]["index"])

    parity_max_diff = float(np.max(np.abs(keras_out - tflite_out)))
    print(f"  Parity max absolute difference: {parity_max_diff:.6e}")
    if parity_max_diff > 1e-4 or not np.all(np.isfinite(tflite_out)):
        raise RuntimeError(f"Smoke test failure: TFLite parity discrepancy {parity_max_diff} exceeds tolerance!")

    report = {
        "status": "SUCCEEDED",
        "smoke_steps": steps,
        "device": "CPU",
        "threads": 2,
        "parameter_update_max_diff": float(max_weight_diff),
        "checkpoint_roundtrip_diff": float(roundtrip_diff),
        "tflite_bytes": len(tflite_bytes),
        "tflite_sha256": tflite_sha256,
        "parity_max_diff": parity_max_diff,
        "quality_claim": "NONE. Bounded smoke test verifies execution mechanics only; does not establish model accuracy.",
    }
    report_path = smoke_dir / "smoke_report.json"
    with report_path.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    print("\n" + "=" * 70)
    print("Smoke Test Completed Successfully!")
    print(f"Report written to: {report_path}")
    print("CAVEAT: NO QUALITY OR CONVERGENCE CLAIM IS MADE FROM THIS SMOKE TEST.")
    print("=" * 70)
    return report


# ==============================================================================
# CLI Entry Point
# ==============================================================================

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.vision.training.train",
        description="Aria native portrait segmentation staged trainer.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=None,
        help="Path to converted dataset manifest.json.",
    )
    parser.add_argument(
        "--pseudo-manifest",
        type=Path,
        default=None,
        help="Optional path to pseudo_manifest.json for sparse object overlay on Surface train split.",
    )
    parser.add_argument(
        "--stage",
        type=str,
        default="city_warmup",
        choices=list(STAGE_SPECS.keys()),
        help="Training stage to execute (default: city_warmup).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/vision-training/trainer-correction/runner"),
        help=(
            "Output directory for checkpoints, metrics, and logs "
            "(default: build/vision-training/trainer-correction/runner)."
        ),
    )
    parser.add_argument(
        "--weights",
        type=str,
        default="imagenet",
        help="Pretrained backbone weights ('imagenet' or 'none'/'None'). Default: imagenet.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=DEFAULT_BATCH_SIZE,
        help=f"Per-microbatch size (default: {DEFAULT_BATCH_SIZE}; fallback: {OOM_FALLBACK_BATCH_SIZE}).",
    )
    parser.add_argument(
        "--accumulate-steps",
        type=int,
        default=DEFAULT_ACCUMULATE_STEPS,
        help=f"Gradient accumulation step count (default: {DEFAULT_ACCUMULATE_STEPS}; fallback: {OOM_FALLBACK_ACCUMULATE_STEPS}).",
    )
    parser.add_argument(
        "--learning-rate",
        type=float,
        default=None,
        help="Base learning rate (overrides stage default if specified).",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=None,
        help="Total epochs for this stage (overrides stage default if specified).",
    )
    parser.add_argument(
        "--init-checkpoint",
        type=Path,
        default=None,
        help="Full prior-stage checkpoint directory used only to initialize this new stage.",
    )
    parser.add_argument(
        "--checkpoint-a",
        type=Path,
        default=None,
        help="Deprecated alias for --init-checkpoint; requires a checkpoint directory with metadata.",
    )
    parser.add_argument(
        "--teacher-checkpoint",
        type=Path,
        default=None,
        help="Stage B joint checkpoint used as the Stage C distillation teacher.",
    )
    parser.add_argument(
        "--teacher-evidence",
        type=Path,
        default=None,
        help="Hashed held-out evaluator evidence required before Stage C can start.",
    )
    parser.add_argument(
        "--resume",
        type=Path,
        default=None,
        help="Path to checkpoint directory or tag to resume from.",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="cpu",
        choices=["cpu", "gpu"],
        help="Execution device: 'cpu' (default, threads=2) or 'gpu'.",
    )
    parser.add_argument(
        "--allow-partial-manifest",
        action="store_true",
        help="Allow running bounded verification on partial converted manifest records (makes NO quality claim).",
    )
    parser.add_argument(
        "--verification-samples",
        type=int,
        default=None,
        help=(
            f"Bound each train/validation pass to this many samples (1..{MAX_DIAGNOSTIC_SAMPLES}); "
            "valid only with --allow-partial-manifest and exactly one epoch."
        ),
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=DEFAULT_SEED,
        help=f"Deterministic random seed (default: {DEFAULT_SEED}).",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Run bounded CPU synthetic smoke test verifying parameter updates, checkpointing, and export.",
    )
    parser.add_argument(
        "--smoke-steps",
        type=int,
        default=2,
        help="Number of steps for smoke test execution (default: 2).",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.smoke:
        report = run_smoke_test(
            output_dir=args.output_dir,
            steps=args.smoke_steps,
            seed=args.seed,
        )
        return 0 if report.get("status") == "SUCCEEDED" else 1

    if args.manifest is None:
        parser.error("--manifest is required when not running in --smoke mode.")

    weights = None if str(args.weights).lower() in ("none", "") else args.weights

    summary = train_stage(
        stage_name=args.stage,
        manifest_path=args.manifest,
        output_dir=args.output_dir,
        pseudo_manifest_path=args.pseudo_manifest,
        checkpoint_a_path=args.checkpoint_a,
        init_checkpoint_path=args.init_checkpoint,
        teacher_checkpoint_path=args.teacher_checkpoint,
        teacher_evidence_path=args.teacher_evidence,
        resume_path=args.resume,
        weights=weights,
        batch_size=args.batch_size,
        accumulate_steps=args.accumulate_steps,
        learning_rate=args.learning_rate,
        epochs=args.epochs,
        clipnorm=DEFAULT_CLIPNORM,
        seed=args.seed,
        device=args.device,
        allow_partial=args.allow_partial_manifest,
        verification_samples=args.verification_samples,
    )

    print(f"Stage '{args.stage}' completed. Summary saved to {args.output_dir / args.stage}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
