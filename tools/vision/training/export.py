"""TFLite export and validation tool for Aria native portrait vision models.

Task ID: ARIA-PORTRAIT-RUNNER-001
Consumes published contracts:
- MODEL_CONTRACT.md / model.py / losses.py
- EVALUATION_CONTRACT.md / metrics.py

Features:
- Explicit chosen checkpoint export (no silent fallbacks or random weights substitution)
- Batch 1 mobile input contract: [1, 512, 288, 3] float32 in range [0.0, 1.0]
- True single normalization inside model wrapper: maps [0, 1] directly to [-1, 1] via layers.Rescaling(2.0, -1.0)
- Single-output (Architecture A) and dual-output (Architecture B/C with hazard head) support
- FP16 quantization option (--precision fp16 / supported_types = [tf.float16])
- Fused TransposeConv unfusing transform for GPU delegate compatibility when flatbuffers is present
- Numeric parity verification between Keras export model and TFLite interpreter with strict tolerance
- Multi-output v2 model manifest generation recording tensor names, order, shapes, indices, normalization, hashes, and diagnostic-only hazard thresholds
- Bounded smoke mode (--smoke) for offline and unit test verification
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

# Restrict default threading to prevent CPU oversubscription
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")


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
    import tensorflow as tf
    from tensorflow import keras
    HAS_TF = True
except (ImportError, ModuleNotFoundError):
    tf = None  # type: ignore
    keras = None  # type: ignore
    HAS_TF = False

# Import model architecture functions and constants
from tools.vision.training.model import (
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    IMAGE_CHANNELS,
    INPUT_SHAPE,
    NUM_SEMANTIC_CLASSES,
    NUM_HAZARD_CLASSES,
    SEMANTIC_CLASSES,
    HAZARD_CLASSES,
    build_model_a,
    build_model_b,
    build_model_c,
    build_export_model,
)

# Optional unfusing dependencies
try:
    import flatbuffers  # type: ignore
    from tflite.Model import Model as TfliteModel, ModelT  # type: ignore
    HAS_FLATBUFFERS = True
except ImportError:
    HAS_FLATBUFFERS = False


def _check_runtime_tf():
    if not HAS_TF or not HAS_NUMPY:
        raise RuntimeError(
            "TensorFlow >= 2.19 and NumPy are required for model export. "
            "Please run inside an authorized Python environment with TensorFlow installed."
        )


def compute_file_sha256(path: Path) -> str:
    """Computes SHA-256 digest of a file."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


# ==============================================================================
# Model Loading from Checkpoint
# ==============================================================================

def load_trained_model_from_checkpoint(
    checkpoint_path: Path,
    architecture: Optional[str] = None,
) -> Tuple[keras.Model, Dict[str, Any]]:
    """Loads a trained Keras model from a specific checkpoint directory or metadata file.

    Refuses silent fallback: raises FileNotFoundError if checkpoint is missing.
    """
    checkpoint_p = checkpoint_path.resolve()

    if not checkpoint_p.exists():
        raise FileNotFoundError(f"Specified checkpoint path does not exist: {checkpoint_p}")

    _check_runtime_tf()


    meta_file = checkpoint_p / "metadata.json" if checkpoint_p.is_dir() else checkpoint_p.parent / "metadata.json"
    meta_dict: Dict[str, Any] = {}
    if meta_file.is_file():
        try:
            with meta_file.open("r", encoding="utf-8") as f:
                meta_dict = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            raise ValueError(f"Could not parse checkpoint metadata at {meta_file}: {e}") from e
        if not isinstance(meta_dict, dict):
            raise ValueError(f"Checkpoint metadata at {meta_file} must contain a JSON object.")

    metadata_arch = meta_dict.get("architecture")
    if metadata_arch is not None and metadata_arch not in ("model_a", "model_b", "model_c"):
        raise ValueError(f"Checkpoint metadata contains unsupported architecture {metadata_arch!r}.")
    if architecture is not None and metadata_arch is not None and architecture != metadata_arch:
        raise ValueError(
            f"Requested architecture '{architecture}' conflicts with checkpoint metadata "
            f"architecture '{metadata_arch}'."
        )
    if architecture is None and metadata_arch is None:
        raise ValueError(
            f"Checkpoint metadata at {meta_file} does not specify architecture; pass --architecture explicitly."
        )
    arch = architecture or metadata_arch

    print(f"Instantiating base model architecture '{arch}'...")
    if arch == "model_a":
        model = build_model_a(weights=None, freeze_bn=False, input_range="0_255")
    elif arch == "model_b":
        model = build_model_b(weights=None, freeze_bn=False, input_range="0_255")
    elif arch == "model_c":
        # Check if hazard head was enabled
        has_hazard = meta_dict.get("has_hazard_head", True)
        model = build_model_c(weights=None, freeze_bn=False, input_range="0_255", include_hazard_head=has_hazard)
    else:
        raise ValueError(f"Unknown architecture '{arch}'. Supported: model_a, model_b, model_c")

    # Locate weights file
    target_dir = checkpoint_p if checkpoint_p.is_dir() else checkpoint_p.parent
    weights_h5 = target_dir / "model_weights.weights.h5"
    weights_ckpt = target_dir / "model_weights.ckpt"

    if checkpoint_p.is_file() and checkpoint_p.suffix in (".h5", ".ckpt"):
        load_target = checkpoint_p
    elif weights_h5.is_file():
        load_target = weights_h5
    elif weights_ckpt.is_file() or (target_dir / "model_weights.ckpt.index").is_file():
        load_target = weights_ckpt
    else:
        raise FileNotFoundError(
            f"No weights file found in {target_dir}. Refusing export without explicit weights."
        )

    print(f"Loading weights from {load_target}...")
    model.load_weights(str(load_target))
    return model, meta_dict


# ==============================================================================
# GPU TransposeConv Unfusing
# ==============================================================================

def try_unfuse_transpose_conv(
    tflite_bytes: bytes,
) -> Tuple[bytes, bool, str]:
    """Attempts to transform fused Conv2DTranspose + ReLU nodes for GPU delegate parity.

    Splits fused Conv2DTranspose into Conv2DTranspose (version 3) + explicit ReLU (version 1).
    Requires flatbuffers and tflite schema bindings. Fails closed if unfusing is requested but dependencies missing.
    """
    if not HAS_FLATBUFFERS:
        raise RuntimeError(
            "GPU TransposeConv unfusing requested via --unfuse-transpose-conv, but flatbuffers or "
            "tflite schema bindings are not importable in current environment. Refusing silent fallback."
        )

    try:
        from tools.vision.unfuse_transpose_conv import transform_model
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as in_f, \
             tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as out_f:
            in_path = in_f.name
            out_path = out_f.name
            in_f.write(tflite_bytes)

        try:
            transform_model(in_path, out_path, verify_input_hash=False, verify_output_hash=False)
            unfused_bytes = Path(out_path).read_bytes()
            return unfused_bytes, True, "Successfully unfused Conv2DTranspose + ReLU nodes for GPU delegate compatibility."
        finally:
            if os.path.exists(in_path):
                os.unlink(in_path)
            if os.path.exists(out_path):
                os.unlink(out_path)
    except Exception as e:
        raise RuntimeError(f"GPU TransposeConv unfusing failed: {e}") from e



# ==============================================================================
# Numeric Parity Verification
# ==============================================================================

def verify_keras_tflite_parity(
    keras_model: keras.Model,
    tflite_bytes: bytes,
    precision: str = "fp32",
    sample_count: int = 2,
    seed: int = 42,
) -> Dict[str, Any]:
    """Verifies numeric parity between Keras export model and TFLite interpreter.

    Inputs are normalized float32 in [0.0, 1.0].
    Enforces strict tolerance thresholds:
    - FP32: max absolute difference <= 1e-4
    - FP16: max absolute difference <= 1e-2
    Rejects any NaN or Inf values.
    """
    _check_runtime_tf()
    if precision not in ("fp32", "fp16"):
        raise ValueError(f"Unsupported precision '{precision}'. Supported: fp32, fp16.")
    if not isinstance(sample_count, int) or isinstance(sample_count, bool) or sample_count <= 0:
        raise ValueError("sample_count must be a positive integer.")
    rng = np.random.RandomState(seed)

    interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    if len(input_details) != 1:
        raise ValueError(f"Expected exactly one TFLite input tensor; found {len(input_details)}.")
    expected_input_shape = (1, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_CHANNELS)
    if tuple(int(dim) for dim in input_details[0]["shape"]) != expected_input_shape:
        raise ValueError(
            f"TFLite input shape {list(input_details[0]['shape'])} does not match {list(expected_input_shape)}."
        )
    if input_details[0]["dtype"] != np.float32:
        raise ValueError(f"TFLite input dtype must be float32; found {input_details[0]['dtype']}.")
    if not output_details:
        raise ValueError("TFLite model has no output tensors.")
    output_signatures = set()
    for detail in output_details:
        shape = tuple(int(dim) for dim in detail["shape"])
        if (
            len(shape) != 4
            or shape[:3] != expected_input_shape[:3]
            or shape[-1] not in (NUM_SEMANTIC_CLASSES, NUM_HAZARD_CLASSES)
        ):
            raise ValueError(f"Unsupported TFLite output tensor shape {list(shape)}.")
        if shape in output_signatures:
            raise ValueError(f"Ambiguous duplicate TFLite output signature {shape}.")
        output_signatures.add(shape)
        if not np.issubdtype(detail["dtype"], np.floating):
            raise ValueError(f"TFLite output '{detail['name']}' must use a floating-point dtype.")
    channels = [signature[-1] for signature in output_signatures]
    if channels.count(NUM_SEMANTIC_CLASSES) != 1 or channels.count(NUM_HAZARD_CLASSES) > 1:
        raise ValueError("TFLite outputs must contain exactly one semantic tensor and at most one hazard tensor.")

    max_tolerance = 1e-2 if precision == "fp16" else 1e-4

    results: Dict[str, Any] = {
        "precision": precision,
        "sample_count": sample_count,
        "max_tolerance": max_tolerance,
        "outputs": [],
        "passed": True,
    }

    for sample_idx in range(sample_count):
        # Generate random input in normalized range [0.0, 1.0]
        test_input = rng.uniform(0.0, 1.0, size=(1, IMAGE_HEIGHT, IMAGE_WIDTH, 3)).astype(np.float32)

        # 1. Keras prediction
        keras_preds = keras_model(test_input, training=False)
        if not isinstance(keras_preds, (list, tuple)):
            keras_preds = [keras_preds]
        if len(keras_preds) != len(output_details):
            raise ValueError(
                f"Keras exposes {len(keras_preds)} outputs but TFLite exposes {len(output_details)}."
            )

        # 2. TFLite prediction
        interpreter.set_tensor(input_details[0]["index"], test_input)
        interpreter.invoke()
        tflite_by_signature: Dict[Tuple[int, ...], Tuple[int, Any]] = {}
        for output_idx, detail in enumerate(output_details):
            signature = tuple(int(dim) for dim in detail["shape"])
            if signature in tflite_by_signature:
                raise ValueError(f"Ambiguous duplicate TFLite output signature {signature}.")
            tflite_by_signature[signature] = (
                output_idx,
                interpreter.get_tensor(detail["index"]),
            )

        matched_output_indices = set()
        for k_idx, k_pred in enumerate(keras_preds):
            k_np = np.asarray(k_pred.numpy() if hasattr(k_pred, "numpy") else k_pred)
            signature = tuple(int(dim) for dim in k_np.shape)
            match = tflite_by_signature.get(signature)
            if match is None:
                raise ValueError(
                    f"No matching TFLite output found with shape {k_np.shape} for Keras output {k_idx}"
                )
            tflite_idx, t_np = match
            if tflite_idx in matched_output_indices:
                raise ValueError(f"Multiple Keras outputs matched TFLite output signature {signature}.")
            matched_output_indices.add(tflite_idx)
            t_np = np.asarray(t_np)

            if not np.all(np.isfinite(k_np)) or not np.all(np.isfinite(t_np)):
                raise ValueError(f"Non-finite values (NaN/Inf) detected in sample {sample_idx} output {k_idx}!")

            max_diff = float(np.max(np.abs(k_np - t_np)))
            mae = float(np.mean(np.abs(k_np - t_np)))

            out_stat = {
                "sample_idx": sample_idx,
                "output_idx": k_idx,
                "shape": list(k_np.shape),
                "max_diff": max_diff,
                "mae": mae,
                "passed": max_diff <= max_tolerance,
            }
            results["outputs"].append(out_stat)

            if max_diff > max_tolerance:
                results["passed"] = False
                raise ValueError(
                    f"Parity violation at output {k_idx}: max_diff {max_diff:.6e} exceeds tolerance {max_tolerance:.6e}"
                )

    return results


# ==============================================================================
# Multi-Output v2 Model Manifest Generation
# ==============================================================================

def generate_v2_manifest(
    model_path: Path,
    architecture: str,
    precision: str,
    tflite_bytes: bytes,
    hazard_thresholds_path: Optional[Path] = None,
    checkpoint_metadata: Optional[Dict[str, Any]] = None,
    parity_report: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Generates companion multi-output v2 manifest JSON for Aria Android deployment."""
    if precision not in ("fp32", "fp16"):
        raise ValueError(f"Unsupported precision '{precision}'. Supported: fp32, fp16.")
    if hazard_thresholds_path is not None and not hazard_thresholds_path.is_file():
        raise FileNotFoundError(f"Hazard threshold/evaluation report does not exist: {hazard_thresholds_path}")

    interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    expected_input_shape = [1, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_CHANNELS]
    if len(input_details) != 1:
        raise ValueError(f"Expected exactly one TFLite input tensor; found {len(input_details)}.")
    if [int(dim) for dim in input_details[0]["shape"]] != expected_input_shape:
        raise ValueError(
            f"TFLite input shape {list(input_details[0]['shape'])} does not match {expected_input_shape}."
        )
    if input_details[0]["dtype"] != np.float32:
        raise ValueError(f"TFLite input dtype must be float32; found {input_details[0]['dtype']}.")
    if not output_details:
        raise ValueError("TFLite model has no output tensors.")

    model_sha256 = hashlib.sha256(tflite_bytes).hexdigest()

    # Hazard thresholds policy: disabled unless validated evaluation report authorizes enablement
    hazard_section: Dict[str, Any] = {
        "enabled": False,
        "automatic_warning_enablement": False,
        "warning_level_evidence_contract_supported": False,
        "diagnostic_only": True,
        "candidate_selected": None,
        "thresholds": None,
        "warning_enablement_reason": "warning_level_evidence_not_supported",
        "caveat": (
            "Automatic hazard warnings are always disabled because this evaluator does not support "
            "warning-event evidence; supplied pixel thresholds are diagnostic only."
        ),
    }

    if hazard_thresholds_path is not None:
        try:
            with hazard_thresholds_path.open("r", encoding="utf-8") as f:
                loaded_eval = json.load(f)
            if not isinstance(loaded_eval, dict):
                raise ValueError("Evaluation report must contain a JSON object.")
            warning_contract = loaded_eval.get("warning_level_evidence_contract", {})
            if not isinstance(warning_contract, dict):
                raise ValueError("Evaluation report warning_level_evidence_contract must be a JSON object.")
            eval_model_hash = loaded_eval.get("model_sha256")
            if eval_model_hash is not None and eval_model_hash != model_sha256:
                raise ValueError(
                    f"Evaluation report model_sha256 ({eval_model_hash}) does not match exported model ({model_sha256})."
                )
            hazard_section["candidate_selected"] = loaded_eval.get("candidate_selected")
            hazard_section["thresholds"] = loaded_eval.get("thresholds")
            legacy_thresholds = loaded_eval.get("hazard_thresholds")
            legacy_enabled = (
                loaded_eval.get("enabled") is True
                or isinstance(legacy_thresholds, dict) and legacy_thresholds.get("enabled") is True
            )
            if (
                loaded_eval.get("automatic_warning_enablement") is True
                or warning_contract.get("supported") is True
                or legacy_enabled
            ):
                hazard_section["warning_enablement_reason"] = "warning_level_evidence_not_supported"
                hazard_section["caveat"] = (
                    "The supplied report requests warning enablement, but warning-event evidence is unsupported; "
                    "all supplied pixel thresholds and candidates are retained for diagnostics only."
                )
            elif eval_model_hash is None:
                hazard_section["warning_enablement_reason"] = "evaluation_report_missing_model_sha256"
                hazard_section["caveat"] = (
                    "Evaluation report has no model_sha256; supplied pixel thresholds are retained for "
                    "diagnostics only and cannot be associated with the exported model."
                )
            else:
                hazard_section["caveat"] = (
                    "Evaluation report does not authorize automatic warnings; pixel thresholds are diagnostic only."
                )
        except (OSError, json.JSONDecodeError, ValueError) as e:
            raise ValueError(f"Failed to process hazard thresholds/evaluation report: {e}") from e


    # Build output tensor descriptor list
    outputs_desc = []
    seen_signatures = set()
    for od in output_details:
        shape = [int(dim) for dim in od["shape"]]
        name = str(od["name"])
        index = int(od["index"])
        dtype_str = str(od["dtype"]).replace("<class '", "").replace("'>", "")
        signature = tuple(shape)
        if signature in seen_signatures:
            raise ValueError(f"Ambiguous duplicate TFLite output signature {signature}.")
        seen_signatures.add(signature)
        if (
            len(shape) != 4
            or shape[:3] != [1, IMAGE_HEIGHT, IMAGE_WIDTH]
            or shape[-1] not in (NUM_SEMANTIC_CLASSES, NUM_HAZARD_CLASSES)
        ):
            raise ValueError(f"Unsupported TFLite output tensor shape {shape}.")
        if not np.issubdtype(od["dtype"], np.floating):
            raise ValueError(f"TFLite output '{name}' must use a floating-point dtype.")

        desc: Dict[str, Any] = {
            "tensor_index": index,
            "name": name,
            "shape": shape,
            "dtype": dtype_str,
        }


        # Class names assignment based on channel depth
        if shape[-1] == NUM_SEMANTIC_CLASSES:
            desc["role"] = "semantic_segmentation"
            desc["classes"] = list(SEMANTIC_CLASSES)
            desc["activation"] = "softmax"
            desc["walkable_classes"] = [2, 3]
        elif shape[-1] == NUM_HAZARD_CLASSES:
            desc["role"] = "hazard_estimation"
            desc["classes"] = list(HAZARD_CLASSES)
            desc["activation"] = "sigmoid"
        else:
            desc["role"] = "custom_feature"

        outputs_desc.append(desc)
    roles = [output["role"] for output in outputs_desc]
    if roles.count("semantic_segmentation") != 1 or roles.count("hazard_estimation") > 1:
        raise ValueError("TFLite outputs must contain exactly one semantic tensor and at most one hazard tensor.")

    inp = input_details[0]
    parity_section = _parity_manifest_section(parity_report, precision)
    parity_caveat = {
        "verified": "Numeric Keras/TFLite parity was verified using the recorded sample set.",
        "failed": "Numeric Keras/TFLite parity failed; see the recorded report.",
        "skipped": "Numeric Keras/TFLite parity was not verified.",
    }[parity_section["status"]]
    manifest: Dict[str, Any] = {
        "schema_version": 2,
        "model_name": model_path.stem,
        "model_sha256": model_sha256,
        "architecture": architecture,
        "precision": precision,
        "creation_timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "input": {
            "tensor_index": int(inp["index"]),
            "name": str(inp["name"]),
            "shape": [int(dim) for dim in inp["shape"]],
            "dtype": str(inp["dtype"]).replace("<class '", "").replace("'>", ""),
            "range": [0.0, 1.0],
            "normalization": "internal_rescaling_2x_minus_1",
            "aspect_ratio": "512_height_288_width_upright_portrait",
        },

        "outputs": outputs_desc,
        "hazard_thresholds": hazard_section,
        "parity": parity_section,
        "checkpoint_provenance": checkpoint_metadata or {},
        "device_caveat": (
            "No GPU partition or parity claim is made until tested on target hardware. "
            + parity_caveat
        ),
    }

    return manifest


def _parity_manifest_section(
    parity_report: Optional[Dict[str, Any]],
    precision: str,
) -> Dict[str, Any]:
    if parity_report is None:
        return {"status": "skipped", "report": None}
    if not isinstance(parity_report, dict):
        raise ValueError("parity_report must be a JSON object.")
    if parity_report.get("precision") != precision:
        raise ValueError("Parity report precision does not match the manifest precision.")
    sample_count = parity_report.get("sample_count")
    if not isinstance(sample_count, int) or isinstance(sample_count, bool) or sample_count <= 0:
        raise ValueError("Parity report sample_count must be a positive integer.")
    outputs = parity_report.get("outputs")
    if not isinstance(outputs, list):
        raise ValueError("Parity report outputs must be a list.")
    passed = parity_report.get("passed")
    if not isinstance(passed, bool):
        raise ValueError("Parity report must contain a boolean passed field.")
    if passed and (not outputs or any(not isinstance(item, dict) or item.get("passed") is not True for item in outputs)):
        raise ValueError("Parity report claims success without successful output measurements.")
    return {"status": "verified" if passed else "failed", "report": parity_report}


def _publish_export_artifacts(
    output_path: Path,
    tflite_bytes: bytes,
    manifest_path: Path,
    manifest: Dict[str, Any],
) -> None:
    """Stages and publishes the model and manifest, restoring prior files on publish failure."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_paths: List[Path] = []
    backups: Dict[Path, Optional[Path]] = {}
    staged: Dict[Path, Path] = {}
    targets = (output_path, manifest_path)
    try:
        for target, content in (
            (output_path, tflite_bytes),
            (manifest_path, json.dumps(manifest, indent=2).encode("utf-8")),
        ):
            fd, temp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=target.parent)
            temp_path = Path(temp_name)
            temporary_paths.append(temp_path)
            with os.fdopen(fd, "wb") as stream:
                stream.write(content)
            staged[target] = temp_path

        for target in targets:
            if target.exists():
                fd, backup_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".bak", dir=target.parent)
                os.close(fd)
                backup_path = Path(backup_name)
                temporary_paths.append(backup_path)
                shutil.copy2(target, backup_path)
                backups[target] = backup_path
            else:
                backups[target] = None

        published: List[Path] = []
        try:
            for target in targets:
                os.replace(staged[target], target)
                published.append(target)
        except OSError as publish_error:
            rollback_errors = []
            for target in reversed(published):
                backup_path = backups[target]
                try:
                    if backup_path is None:
                        target.unlink(missing_ok=True)
                    else:
                        os.replace(backup_path, target)
                except OSError as rollback_error:
                    rollback_errors.append(f"{target}: {rollback_error}")
            if rollback_errors:
                raise RuntimeError(
                    f"Artifact publication failed ({publish_error}); rollback also failed: "
                    + "; ".join(rollback_errors)
                ) from publish_error
            raise
    finally:
        for temp_path in temporary_paths:
            temp_path.unlink(missing_ok=True)


# ==============================================================================
# Main Export Routine
# ==============================================================================

def export_model(
    checkpoint_path: Path,
    output_path: Path,
    architecture: Optional[str] = None,
    precision: str = "fp32",
    hazard_thresholds_path: Optional[Path] = None,
    unfuse_transpose_conv: bool = False,
    verify_parity: bool = True,
) -> Path:
    """Exports trained Keras checkpoint to Android-compatible TFLite model and v2 manifest."""
    _check_runtime_tf()
    if precision not in ("fp32", "fp16"):
        raise ValueError(f"Unsupported precision '{precision}'. Supported: fp32, fp16.")
    print("=" * 70)
    print("Aria Vision Model Exporter")
    print(f"Checkpoint: {checkpoint_path}")
    print(f"Output:     {output_path}")
    print(f"Precision:  {precision}")
    print("=" * 70)

    # 1. Load trained model from checkpoint
    trained_model, meta = load_trained_model_from_checkpoint(checkpoint_path, architecture)
    arch = architecture or meta["architecture"]

    # 2. Build export model wrapper with input_range='0_1' and batch_size=1
    print("\n[Export 1/5] Building batch-1 export model (single normalization [0, 1] -> [-1, 1])...")
    export_model_k = build_export_model(trained_model, batch_size=1, name="aria_export_model")

    # 3. Convert to TFLite
    print(f"\n[Export 2/5] Converting to TFLite ({precision.upper()})...")
    converter = tf.lite.TFLiteConverter.from_keras_model(export_model_k)
    if precision == "fp16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]

    tflite_bytes = converter.convert()
    print(f"  Raw TFLite size: {len(tflite_bytes):,} bytes")

    # 4. Optional GPU transposeconv unfusing
    if unfuse_transpose_conv:
        print("\n[Export 3/5] Applying GPU TransposeConv unfusing transform...")
        tflite_bytes, unfused_ok, unfuse_msg = try_unfuse_transpose_conv(tflite_bytes)
        print(f"  Unfuse status: {unfuse_msg}")

    output_path = output_path.resolve()

    # 5. Parity verification
    parity_report = None
    if verify_parity:
        print("\n[Export 4/5] Verifying numeric Keras vs TFLite parity...")
        parity_report = verify_keras_tflite_parity(export_model_k, tflite_bytes, precision=precision)
        print(f"  Numeric parity verified: {parity_report['passed']} (max tolerance: {parity_report['max_tolerance']})")

    # 6. Generate and save companion v2 manifest
    print("\n[Export 5/5] Generating multi-output v2 model manifest...")
    manifest = generate_v2_manifest(
        model_path=output_path,
        architecture=arch,
        precision=precision,
        tflite_bytes=tflite_bytes,
        hazard_thresholds_path=hazard_thresholds_path,
        checkpoint_metadata=meta,
        parity_report=parity_report,
    )
    manifest_path = output_path.parent / f"{output_path.stem}_manifest.json"
    _publish_export_artifacts(output_path, tflite_bytes, manifest_path, manifest)
    tflite_sha = hashlib.sha256(tflite_bytes).hexdigest()
    print(f"  Saved TFLite: {output_path} | SHA256: {tflite_sha}")
    print(f"  Saved v2 manifest: {manifest_path}")

    print("\n" + "=" * 70)
    print("Export Completed Successfully!")
    print(f"Model:    {output_path}")
    print(f"Manifest: {manifest_path}")
    print("=" * 70)
    return output_path


# ==============================================================================
# Smoke Mode for Export
# ==============================================================================

def run_export_smoke(output_dir: Path) -> Dict[str, Any]:
    """Runs a synthetic export smoke test without requiring an existing trained checkpoint."""
    _check_runtime_tf()
    print("=" * 70)
    print("Running Export Smoke Test (Synthetic Model A and Model B)")
    print("=" * 70)

    output_dir = output_dir.resolve()
    smoke_dir = output_dir / "smoke_export"
    smoke_dir.mkdir(parents=True, exist_ok=True)

    # 1. Test Model A export
    print("\n[Smoke 1/2] Exporting synthetic Model A...")
    model_a = build_model_a(weights=None, freeze_bn=False, input_range="0_255")
    exp_a = build_export_model(model_a, batch_size=1, name="smoke_export_a")
    converter_a = tf.lite.TFLiteConverter.from_keras_model(exp_a)
    bytes_a = converter_a.convert()
    path_a = smoke_dir / "smoke_portrait_model_a.tflite"
    path_a.write_bytes(bytes_a)

    parity_a = verify_keras_tflite_parity(exp_a, bytes_a, precision="fp32")
    manifest_a = generate_v2_manifest(path_a, "model_a", "fp32", bytes_a, parity_report=parity_a)
    (smoke_dir / f"{path_a.stem}_manifest.json").write_text(json.dumps(manifest_a, indent=2), encoding="utf-8")

    # 2. Test Model B export (FP16 option)
    print("\n[Smoke 2/2] Exporting synthetic Model B (FP16)...")
    model_b = build_model_b(weights=None, freeze_bn=False, input_range="0_255")
    exp_b = build_export_model(model_b, batch_size=1, name="smoke_export_b")
    converter_b = tf.lite.TFLiteConverter.from_keras_model(exp_b)
    converter_b.optimizations = [tf.lite.Optimize.DEFAULT]
    converter_b.target_spec.supported_types = [tf.float16]
    bytes_b = converter_b.convert()
    path_b = smoke_dir / "smoke_portrait_model_b_fp16.tflite"
    path_b.write_bytes(bytes_b)

    parity_b = verify_keras_tflite_parity(exp_b, bytes_b, precision="fp16")
    manifest_b = generate_v2_manifest(path_b, "model_b", "fp16", bytes_b, parity_report=parity_b)
    (smoke_dir / f"{path_b.stem}_manifest.json").write_text(json.dumps(manifest_b, indent=2), encoding="utf-8")

    report = {
        "status": "SUCCEEDED",
        "model_a_bytes": len(bytes_a),
        "model_a_sha256": compute_file_sha256(path_a),
        "model_a_parity": parity_a["passed"],
        "model_b_bytes": len(bytes_b),
        "model_b_sha256": compute_file_sha256(path_b),
        "model_b_parity": parity_b["passed"],
    }
    report_file = smoke_dir / "export_smoke_report.json"
    report_file.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nExport smoke report saved to: {report_file}")
    return report


# ==============================================================================
# CLI Entry Point
# ==============================================================================

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.vision.training.export",
        description="Aria native portrait segmentation model exporter and parity verifier.",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=None,
        help="Path to trained checkpoint directory or weights file. (Explicit chosen checkpoint; no fallback).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/vision-training/runner/export/walkable_seg_aria_portrait.tflite"),
        help="Path for exported .tflite model (default: build/vision-training/runner/export/walkable_seg_aria_portrait.tflite).",
    )
    parser.add_argument(
        "--architecture",
        type=str,
        default=None,
        choices=["model_a", "model_b", "model_c"],
        help="Model architecture ('model_a', 'model_b', 'model_c'). Inferred from checkpoint if omitted.",
    )
    parser.add_argument(
        "--precision",
        type=str,
        default="fp32",
        choices=["fp32", "fp16"],
        help="Target model precision: fp32 (default) or fp16.",
    )
    parser.add_argument(
        "--hazard-thresholds",
        type=Path,
        default=None,
        help="Optional evaluation report for diagnostic-only pixel thresholds. Automatic warnings remain disabled.",
    )
    parser.add_argument(
        "--unfuse-transpose-conv",
        action="store_true",
        help="Transform fused Conv2DTranspose activations into Conv2DTranspose + ReLU for GPU delegate compatibility.",
    )
    parser.add_argument(
        "--verify-parity",
        action="store_true",
        default=True,
        help="Verify numeric parity between Keras export model and TFLite interpreter (default: True).",
    )
    parser.add_argument(
        "--no-verify-parity",
        dest="verify_parity",
        action="store_false",
        help="Disable numeric parity verification.",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="cpu",
        choices=["cpu", "gpu"],
        help="Execution device: 'cpu' (default) or 'gpu'.",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Run export smoke test on synthetic models without requiring trained checkpoint.",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.device == "cpu":
        os.environ["CUDA_VISIBLE_DEVICES"] = "-1"

    if args.smoke:

        report = run_export_smoke(args.output.parent if args.output else Path("build/vision-training/runner"))
        return 0 if report.get("status") == "SUCCEEDED" else 1

    if args.checkpoint is None:
        parser.error("--checkpoint is required when not running in --smoke mode.")

    export_model(
        checkpoint_path=args.checkpoint,
        output_path=args.output,
        architecture=args.architecture,
        precision=args.precision,
        hazard_thresholds_path=args.hazard_thresholds,
        unfuse_transpose_conv=args.unfuse_transpose_conv,
        verify_parity=args.verify_parity,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
