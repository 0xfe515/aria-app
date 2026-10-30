from __future__ import annotations

import argparse
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
import json
from pathlib import Path
import threading
from typing import Any, Callable

from .data import (
    CLASS_NAMES,
    DatasetError,
    IGNORE,
    OUT_H,
    OUT_W,
    letterbox_geometry,
    load_manifest_records,
    sha256_file,
)


CONFIDENCE_THRESHOLD = 0.90
MARGIN_THRESHOLD = 0.20
PSEUDO_WEIGHT = 0.25
OBJECT_CLASSES = frozenset((4, 5, 6))
MAX_WORKERS = 4
TF_INTRA_OP_THREADS = 2
TF_INTER_OP_THREADS = 2


class PseudoLabelError(ValueError):
    pass


def select_converted_surface_training_records(
    records: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], int]:
    eligible: list[dict[str, Any]] = []
    unconverted = 0
    for record in records:
        if record.get("source") != "surface" or record.get("split") != "train":
            continue
        image_path = record.get("image_tensor_path")
        semantic_path = record.get("semantic_path")
        if not image_path and not semantic_path:
            unconverted += 1
            continue
        if not image_path or not semantic_path:
            raise PseudoLabelError(
                f"Manifest record {record.get('id')} has only one of image_tensor_path and semantic_path."
            )
        eligible.append(record)
    return eligible, unconverted


def _numpy_and_pillow() -> tuple[Any, Any]:
    try:
        import numpy as np
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "Pseudo-label generation requires NumPy and Pillow in the isolated training environment."
        ) from exc
    return np, Image


def select_agreed_object_pixels(
    original_probabilities: Any,
    flipped_probabilities: Any,
    semantic_ground_truth: Any,
    content_valid_mask: Any,
    confidence_threshold: float = CONFIDENCE_THRESHOLD,
    margin_threshold: float = MARGIN_THRESHOLD,
) -> tuple[Any, Any]:
    np, _ = _numpy_and_pillow()
    original = np.asarray(original_probabilities, dtype=np.float32)
    reflected = np.flip(np.asarray(flipped_probabilities, dtype=np.float32), axis=1)
    ground_truth = np.asarray(semantic_ground_truth, dtype=np.uint8)
    content_valid = np.asarray(content_valid_mask, dtype=np.bool_)
    if original.shape != reflected.shape or original.ndim != 3 or original.shape[-1] != len(CLASS_NAMES):
        raise PseudoLabelError(
            f"Teacher output must have matching [H,W,{len(CLASS_NAMES)}] probabilities."
        )
    if ground_truth.shape != original.shape[:2]:
        raise PseudoLabelError("Semantic labels and teacher output must have matching spatial dimensions.")
    if content_valid.shape != original.shape[:2]:
        raise PseudoLabelError("Content-valid mask and teacher output must have matching spatial dimensions.")
    if not np.isfinite(original).all() or not np.isfinite(reflected).all():
        raise PseudoLabelError("Teacher output contains non-finite probabilities.")
    if (original < 0).any() or (original > 1).any() or (reflected < 0).any() or (reflected > 1).any():
        raise PseudoLabelError("Teacher output is not normalized probability data.")
    if not np.allclose(original.sum(axis=-1), 1.0, atol=0.05) or not np.allclose(
        reflected.sum(axis=-1), 1.0, atol=0.05
    ):
        raise PseudoLabelError("Teacher output probabilities do not sum to one.")

    original_top = np.argmax(original, axis=-1)
    reflected_top = np.argmax(reflected, axis=-1)
    original_two = np.partition(original, -2, axis=-1)[..., -2:]
    reflected_two = np.partition(reflected, -2, axis=-1)[..., -2:]
    original_confidence = np.max(original, axis=-1)
    reflected_confidence = np.max(reflected, axis=-1)
    original_margin = original_two[..., 1] - original_two[..., 0]
    reflected_margin = reflected_two[..., 1] - reflected_two[..., 0]
    object_prediction = np.isin(original_top, tuple(OBJECT_CLASSES))
    accepted = (
        (original_top == reflected_top)
        & object_prediction
        & (original_confidence >= confidence_threshold)
        & (reflected_confidence >= confidence_threshold)
        & (original_margin >= margin_threshold)
        & (reflected_margin >= margin_threshold)
        & (ground_truth == IGNORE)
        & content_valid
    )
    labels = np.full(ground_truth.shape, IGNORE, dtype=np.uint8)
    labels[accepted] = original_top[accepted].astype(np.uint8)
    confidence = np.zeros(ground_truth.shape, dtype=np.float32)
    confidence[accepted] = np.minimum(
        original_confidence[accepted], reflected_confidence[accepted]
    )
    return labels, confidence


def pseudo_supervision_arrays(labels: Any) -> tuple[Any, Any]:
    np, _ = _numpy_and_pillow()
    labels = np.asarray(labels, dtype=np.uint8)
    accepted = labels != IGNORE
    if np.any(accepted & ~np.isin(labels, tuple(OBJECT_CLASSES))):
        raise PseudoLabelError("Pseudo targets may contain only person, vehicle, pole_sign, and ignore.")
    source = np.zeros(labels.shape, dtype=np.uint8)
    source[accepted] = 2
    weight = np.zeros(labels.shape, dtype=np.float32)
    weight[accepted] = PSEUDO_WEIGHT
    return source, weight


def _validate_interpreter(interpreter: Any) -> Any:
    np, _ = _numpy_and_pillow()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise PseudoLabelError(
            f"Expected one teacher input and one semantic output, got {len(inputs)} and {len(outputs)}."
        )
    expected_input = (1, OUT_H, OUT_W, 3)
    expected_output = (1, OUT_H, OUT_W, len(CLASS_NAMES))
    for details, expected, tensor_name in (
        (inputs[0], expected_input, "input"),
        (outputs[0], expected_output, "output"),
    ):
        if tuple(int(dimension) for dimension in details["shape"]) != expected:
            raise PseudoLabelError(
                f"Teacher {tensor_name} must be {expected}, got {tuple(details['shape'])}."
            )
        dtype = np.dtype(details["dtype"])
        if not (np.issubdtype(dtype, np.floating) or np.issubdtype(dtype, np.integer)):
            raise PseudoLabelError(f"Teacher {tensor_name} has unsupported tensor dtype {dtype}.")
        if np.issubdtype(dtype, np.integer):
            scale, _ = details.get("quantization", (0.0, 0))
            if scale <= 0:
                raise PseudoLabelError(
                    f"Quantized teacher {tensor_name} has no quantization scale."
                )
    return interpreter


_tensorflow_cpu_configured = False
_tensorflow_config_lock = threading.Lock()


def _load_interpreter(model_path: Path) -> Any:
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        try:
            import tensorflow as tf
        except ImportError as tensorflow_error:
            raise RuntimeError(
                "Frozen TFLite inference needs tflite_runtime or TensorFlow in the isolated "
                f"training environment; no pseudo-label inference was run ({tensorflow_error})."
            ) from tensorflow_error
        global _tensorflow_cpu_configured
        with _tensorflow_config_lock:
            if not _tensorflow_cpu_configured:
                try:
                    tf.config.set_visible_devices([], "GPU")
                    tf.config.threading.set_intra_op_parallelism_threads(TF_INTRA_OP_THREADS)
                    tf.config.threading.set_inter_op_parallelism_threads(TF_INTER_OP_THREADS)
                except RuntimeError as exc:
                    raise RuntimeError(
                        "TensorFlow CPU-only inference must be configured before its runtime initializes."
                    ) from exc
                _tensorflow_cpu_configured = True
        Interpreter = tf.lite.Interpreter
    interpreter = Interpreter(model_path=str(model_path), num_threads=1)
    interpreter.allocate_tensors()
    return _validate_interpreter(interpreter)


def _quantize_input(image: Any, details: dict[str, Any]) -> Any:
    np, _ = _numpy_and_pillow()
    dtype = details["dtype"]
    if np.issubdtype(dtype, np.floating):
        return image.astype(dtype, copy=False)
    scale, zero_point = details.get("quantization", (0.0, 0))
    if scale <= 0:
        raise PseudoLabelError("Quantized teacher input has no quantization scale.")
    bounds = np.iinfo(dtype)
    return np.clip(np.rint(image / scale + zero_point), bounds.min, bounds.max).astype(dtype)


def _dequantize_output(values: Any, details: dict[str, Any]) -> Any:
    np, _ = _numpy_and_pillow()
    tensor = np.asarray(values)
    if np.issubdtype(tensor.dtype, np.floating):
        probabilities = tensor.astype(np.float32, copy=False)
    else:
        scale, zero_point = details.get("quantization", (0.0, 0))
        if scale <= 0:
            raise PseudoLabelError("Quantized teacher output has no quantization scale.")
        probabilities = (tensor.astype(np.float32) - zero_point) * scale
    probabilities = probabilities[0]
    sums = probabilities.sum(axis=-1)
    if (
        not np.isfinite(probabilities).all()
        or (probabilities < -1e-4).any()
        or (probabilities > 1.0001).any()
        or not np.allclose(sums, 1.0, atol=0.05)
    ):
        raise PseudoLabelError(
            "Teacher output is not a normalized seven-class probability tensor; refusing to "
            "treat logits or an unknown output contract as confidence."
        )
    return probabilities


def _infer(interpreter: Any, image: Any) -> Any:
    np, _ = _numpy_and_pillow()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    interpreter.set_tensor(inputs[0]["index"], _quantize_input(image[None, ...], inputs[0]))
    interpreter.invoke()
    return _dequantize_output(
        interpreter.get_tensor(outputs[0]["index"]), outputs[0]
    )


def generate_pseudo_labels(
    manifest_path: Path,
    model_path: Path,
    output_dir: Path,
    *,
    workers: int = 1,
    limit: int | None = None,
    progress_interval: int = 100,
    interpreter_factory: Callable[[Path], Any] | None = None,
) -> dict[str, Any]:
    np, Image = _numpy_and_pillow()
    if not 1 <= workers <= MAX_WORKERS:
        raise PseudoLabelError(f"workers must be between 1 and {MAX_WORKERS}.")
    if limit is not None and limit <= 0:
        raise PseudoLabelError("limit must be a positive integer.")
    if progress_interval <= 0:
        raise PseudoLabelError("progress_interval must be a positive integer.")
    if output_dir.exists():
        raise PseudoLabelError(
            f"Refusing to overwrite existing pseudo-label output directory: {output_dir}"
        )
    _, records = load_manifest_records(manifest_path)
    if not model_path.is_file():
        raise PseudoLabelError(f"Teacher model does not exist: {model_path}")
    model_path = model_path.resolve()
    model_hash = sha256_file(model_path)

    eligible, unconverted = select_converted_surface_training_records(records)
    if not eligible:
        raise PseudoLabelError(
            "No converted Surface training records are eligible for pseudo-labeling; "
            "run --convert before pseudo-label generation."
        )

    eligible.sort(key=lambda record: record["id"])
    selected = eligible if limit is None else eligible[:limit]
    output_dir.mkdir(parents=True, exist_ok=False)
    pseudo_records: list[dict[str, Any]] = []
    class_pixel_counts = {name: 0 for name in CLASS_NAMES}
    skipped_ground_truth = 0
    processed_records = 0
    records_without_pseudo_labels = 0
    local_state = threading.local()
    factory = interpreter_factory or _load_interpreter

    def incomplete_report(status: str, error: str | None = None) -> dict[str, Any]:
        report: dict[str, Any] = {
            "status": status,
            "complete": False,
            "teacher_model_sha256": model_hash,
            "eligible_surface_train_records": len(eligible),
            "selected_surface_train_records": len(selected),
            "records_processed": processed_records,
            "records_remaining": len(eligible) - processed_records,
            "unconverted_surface_train_records": unconverted,
            "records_with_pseudo_labels": len(pseudo_records),
            "records_without_pseudo_labels": records_without_pseudo_labels,
            "skipped_fully_annotated_records": skipped_ground_truth,
            "accepted_pixels_by_class": class_pixel_counts,
            "pseudo_manifest": "pseudo_manifest.json",
            "evaluation_ground_truth_created": False,
            "workers": workers,
            "limit": limit,
        }
        if error is not None:
            report["error"] = error
        return report

    (output_dir / "completion_report.json").write_text(
        json.dumps(incomplete_report("in_progress"), indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    def process_record(record: dict[str, Any]) -> dict[str, Any]:
        base_dir = manifest_path.resolve().parent
        image_path = (base_dir / record["image_tensor_path"]).resolve()
        semantic_path = (base_dir / record["semantic_path"]).resolve()
        if not image_path.is_file() or not semantic_path.is_file():
            raise PseudoLabelError(
                f"Converted files are missing for manifest record {record['id']}."
            )
        with Image.open(image_path) as source:
            rgb = np.asarray(source.convert("RGB"), dtype=np.float32) / 255.0
        with Image.open(semantic_path) as source:
            ground_truth = np.asarray(source.convert("L"), dtype=np.uint8)
        if (
            not np.isfinite(rgb).all()
            or rgb.min() < 0.0
            or rgb.max() > 1.0
            or ((ground_truth > 6) & (ground_truth != IGNORE)).any()
        ):
            raise PseudoLabelError(
                f"Converted sample {record['id']} contains invalid image or semantic values."
            )
        if rgb.shape != (OUT_H, OUT_W, 3) or ground_truth.shape != (OUT_H, OUT_W):
            raise PseudoLabelError(
                f"Converted sample {record['id']} does not match the upright 512x288 contract."
            )
        if (ground_truth != IGNORE).all():
            return {"fully_annotated": True, "accepted_pixels": 0}
        original_image_path = Path(record["image_path"])
        if not original_image_path.is_file():
            raise PseudoLabelError(
                f"Original image is missing for pseudo-label geometry: {original_image_path}"
            )
        with Image.open(original_image_path) as source:
            left, top, content_width, content_height = letterbox_geometry(
                source.width, source.height, OUT_W, OUT_H
            )
        content_valid = np.zeros((OUT_H, OUT_W), dtype=np.bool_)
        content_valid[top : top + content_height, left : left + content_width] = True

        interpreter = getattr(local_state, "interpreter", None)
        if interpreter is None:
            interpreter = _validate_interpreter(factory(model_path))
            local_state.interpreter = interpreter
        original = _infer(interpreter, rgb)
        flipped_input = np.flip(rgb, axis=1).copy()
        flipped = _infer(interpreter, flipped_input)
        labels, confidence = select_agreed_object_pixels(
            original, flipped, ground_truth, content_valid
        )
        accepted = labels != IGNORE
        if not accepted.any():
            return {"fully_annotated": False, "accepted_pixels": 0}

        record_id = record["id"]
        semantic_rel = Path("labels") / f"{record_id}.pseudo.png"
        weights_rel = Path("weights") / f"{record_id}.pseudo.npz"
        semantic_out = output_dir / semantic_rel
        weights_out = output_dir / weights_rel
        semantic_out.parent.mkdir(parents=True, exist_ok=True)
        weights_out.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(labels, mode="L").save(semantic_out, format="PNG")
        semantic_source, weights = pseudo_supervision_arrays(labels)
        np.savez_compressed(
            weights_out,
            semantic_source=semantic_source,
            semantic_weight=weights,
            confidence=confidence,
        )
        pseudo_record = {
            "id": record_id,
            "source": record["source"],
            "group_id": record.get("group_id"),
            "split": record["split"],
            "image_sha256": record.get("image_sha256"),
            "semantic_path": semantic_rel.as_posix(),
            "semantic_weight_path": weights_rel.as_posix(),
            "accepted_pixels": int(accepted.sum()),
            "mean_confidence": float(confidence[accepted].mean()),
        }
        pixel_counts = {
            class_name: int(np.count_nonzero(labels == class_id))
            for class_id, class_name in enumerate(CLASS_NAMES)
        }
        return {
            "fully_annotated": False,
            "accepted_pixels": int(accepted.sum()),
            "pseudo_record": pseudo_record,
            "pixel_counts": pixel_counts,
        }

    try:
        records_iter = iter(selected)
        pending: set[Future[dict[str, Any]]] = set()
        with ThreadPoolExecutor(max_workers=workers) as executor:
            for _ in range(min(len(selected), workers * 2)):
                pending.add(executor.submit(process_record, next(records_iter)))
            while pending:
                completed, pending = wait(pending, return_when=FIRST_COMPLETED)
                for future in completed:
                    result = future.result()
                    processed_records += 1
                    if result["fully_annotated"]:
                        skipped_ground_truth += 1
                    elif result["accepted_pixels"]:
                        pseudo_records.append(result["pseudo_record"])
                        for class_name, count in result["pixel_counts"].items():
                            class_pixel_counts[class_name] += count
                    else:
                        records_without_pseudo_labels += 1
                while len(pending) < workers * 2 and processed_records + len(pending) < len(selected):
                    pending.add(executor.submit(process_record, next(records_iter)))
                if processed_records % progress_interval == 0 or processed_records == len(selected):
                    print(
                        f"Processed {processed_records}/{len(selected)} selected train records "
                        f"(workers={workers}).",
                        flush=True,
                    )

                progress = incomplete_report("in_progress")
                (output_dir / "completion_report.json").write_text(
                    json.dumps(progress, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
                    encoding="utf-8",
                )
    except Exception as exc:
        failed = incomplete_report("failed", str(exc))
        (output_dir / "completion_report.json").write_text(
            json.dumps(failed, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        raise

    pseudo_records.sort(key=lambda record: record["id"])
    pseudo_manifest = {
        "schema_version": 1,
        "source_manifest": str(manifest_path.resolve()),
        "teacher_model_path": str(model_path),
        "teacher_model_sha256": model_hash,
        "thresholds": {
            "confidence": CONFIDENCE_THRESHOLD,
            "margin": MARGIN_THRESHOLD,
            "semantic_weight": PSEUDO_WEIGHT,
            "classes": [CLASS_NAMES[index] for index in sorted(OBJECT_CLASSES)],
        },
        "records": pseudo_records,
        "note": (
            "Automatic teacher pseudo-labels are training augmentation only, not human or objective "
            "Korean object ground truth; official test records are never inferred or emitted."
        ),
    }
    (output_dir / "pseudo_manifest.json").write_text(
        json.dumps(pseudo_manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    complete = limit is None and processed_records == len(eligible)
    report = incomplete_report("complete" if complete else "partial")
    report["complete"] = complete
    (output_dir / "completion_report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Produce conservative, flip-agreement TFLite object pseudo-labels for Surface train data."
    )
    parser.add_argument("--manifest", type=Path, required=True, help="Converted data manifest.")
    parser.add_argument(
        "--model",
        type=Path,
        required=True,
        help="Explicit frozen native-portrait semantic TFLite teacher.",
    )
    parser.add_argument("--output", type=Path, required=True, help="Pseudo-label output directory.")
    parser.add_argument(
        "--workers",
        type=int,
        choices=range(1, MAX_WORKERS + 1),
        default=1,
        help=f"Bounded CPU inference workers (1-{MAX_WORKERS}; each uses its own interpreter).",
    )
    parser.add_argument(
        "--limit",
        type=int,
        help="Process at most this many sorted train records and mark the report partial.",
    )
    parser.add_argument(
        "--progress-interval",
        type=int,
        default=100,
        help="Print and persist progress every N completed records.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        report = generate_pseudo_labels(
            args.manifest,
            args.model,
            args.output,
            workers=args.workers,
            limit=args.limit,
            progress_interval=args.progress_interval,
        )
    except (DatasetError, PseudoLabelError, OSError, RuntimeError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
