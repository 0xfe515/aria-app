"""Offline class-3 supervision and Surface validation diagnostic."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from tools.vision.training.metrics import CLASS_NAMES, StreamingConfusionMatrix
from tools.vision.training.model import build_model_a, build_model_b, build_model_c
from tools.vision.training.train_data import PortraitDataset


def class_pixel_counts(mask: Any, provenance: Any) -> list[int]:
    """Count real-label pixels in the canonical seven-class order."""
    import numpy as np

    labels = np.asarray(mask)
    source = np.asarray(provenance)
    if labels.shape != source.shape:
        raise ValueError("semantic labels and provenance must have identical shapes")
    real = (source == 1) & (labels >= 0) & (labels < len(CLASS_NAMES))
    return np.bincount(labels[real].astype(np.int64), minlength=len(CLASS_NAMES)).tolist()


def gradient_summary(gradient: Any) -> dict[str, float | int | bool]:
    import numpy as np

    values = np.asarray(gradient)
    finite = bool(np.isfinite(values).all())
    return {
        "finite": finite,
        "nonzero": bool(finite and np.any(values != 0)),
        "element_count": int(values.size),
        "nonzero_count": int(np.count_nonzero(values)) if finite else 0,
        "l2_norm": float(np.linalg.norm(values.ravel())) if finite else None,
        "max_abs": float(np.max(np.abs(values))) if finite and values.size else None,
    }


def class3_positive_nll(logits: Any, labels: Any, semantic_weight: Any):
    """Mean -log(P(class 3)) over real, class-3 pixels only."""
    import tensorflow as tf

    labels = tf.cast(labels, tf.int32)
    semantic_weight = tf.cast(semantic_weight, tf.float32)
    positive_mask = tf.logical_and(tf.equal(labels, 3), tf.equal(semantic_weight, 1.0))
    positive_count = int(tf.math.count_nonzero(positive_mask).numpy())
    if positive_count <= 0:
        raise ValueError("class-3 objective has zero real crosswalk supervision")
    probabilities = tf.nn.softmax(logits, axis=-1)
    positive_probability = tf.boolean_mask(probabilities[..., 3], positive_mask)
    loss = -tf.reduce_mean(tf.math.log(tf.clip_by_value(positive_probability, 1e-7, 1.0)))
    if not bool(tf.math.is_finite(loss).numpy()):
        raise ValueError("class-3 objective is non-finite")
    return loss, positive_count


def _sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _build(architecture: str):
    return {"model_a": build_model_a, "model_b": build_model_b, "model_c": build_model_c}[architecture](weights=None, freeze_bn=True, input_range="0_255")


def run_diagnostic(manifest: str | Path, architecture: str, checkpoint: str | Path,
                   device: str, sample_count: int, output: str | Path) -> dict[str, Any]:
    import numpy as np
    import tensorflow as tf
    from tools.vision.training.losses import CombinedSemanticLoss

    manifest_path = Path(manifest).expanduser().resolve()
    checkpoint_path = Path(checkpoint).expanduser().resolve()
    if not manifest_path.is_file():
        raise ValueError(f"manifest does not exist: {manifest_path}")
    if not checkpoint_path.is_file():
        raise ValueError(f"checkpoint does not exist: {checkpoint_path}")
    if architecture not in ("model_a", "model_b", "model_c"):
        raise ValueError("architecture must be model_a, model_b, or model_c")
    if isinstance(sample_count, bool) or sample_count < 1 or sample_count > 32:
        raise ValueError("sample count must be between 1 and 32")
    try:
        tf.config.set_visible_devices([], "GPU") if device == "cpu" else None
        with tf.device(device):
            model = _build(architecture)
            model.load_weights(str(checkpoint_path))
    except Exception as error:
        raise ValueError(f"unable to load checkpoint/device/model: {error}") from error

    # No pseudo overlay is passed: selection and evaluation are real labels only.
    train = PortraitDataset(manifest_path, split="train", sources="surface", require_complete=True)
    validation = PortraitDataset(manifest_path, split="val", sources="surface", require_complete=True)
    positives = []
    for sample in train.iter_samples(epoch=0, augment=False, balanced=False):
        counts = class_pixel_counts(sample["semantic"], sample["semantic_source"])
        if counts[3] > 0:
            positives.append({"sample": sample, "counts": counts})
            if len(positives) == sample_count:
                break
    if not positives:
        raise ValueError("no real-label Surface training record has class-3 crosswalk supervision")

    batch = {key: np.stack([row["sample"][key] for row in positives]) for key in ("image", "semantic", "semantic_weight")}
    if not np.any((batch["semantic"] == 3) & (batch["semantic_weight"] == 1.0)):
        raise ValueError("selected batch has empty real class-3 supervision")
    semantic_layer = model.get_layer("semantic_output")
    # Retain logits as a named, differentiable tensor while preserving the model's
    # exact trained softmax and output probabilities.
    feature_model = tf.keras.Model(model.inputs, [semantic_layer.input, model.output])
    loss_fn = CombinedSemanticLoss()
    with tf.GradientTape(persistent=True) as tape:
        features, _model_prediction = feature_model(batch["image"], training=False)
        logits = tf.nn.conv2d(features, semantic_layer.kernel, strides=1, padding="SAME")
        if semantic_layer.use_bias:
            logits = tf.nn.bias_add(logits, semantic_layer.bias)
        prediction = tf.nn.softmax(logits, axis=-1)
        whole_loss = loss_fn(batch["semantic"], prediction, sample_weight=batch["semantic_weight"])
        class3_loss, class3_pixels = class3_positive_nll(
            logits, batch["semantic"], batch["semantic_weight"]
        )
    whole_logit_gradient = tape.gradient(whole_loss, logits)
    whole_parameter_gradients = tape.gradient(whole_loss, model.trainable_variables)
    class3_logit_gradient = tape.gradient(class3_loss, logits)
    class3_parameter_gradients = tape.gradient(class3_loss, model.trainable_variables)
    del tape
    if class3_logit_gradient is None or whole_logit_gradient is None:
        raise ValueError("semantic objectives did not produce logit gradients")
    whole_param_summaries = [gradient_summary(g.numpy()) for g in whole_parameter_gradients if g is not None]
    class3_param_summaries = [gradient_summary(g.numpy()) for g in class3_parameter_gradients if g is not None]
    class3_channel_gradient = class3_logit_gradient[..., 3]
    whole_finite_loss = bool(np.isfinite(float(whole_loss.numpy())))
    class3_finite_loss = bool(np.isfinite(float(class3_loss.numpy())))
    if not whole_finite_loss or not class3_finite_loss:
        raise ValueError("whole semantic or class-3 objective loss is not finite")

    confusion = StreamingConfusionMatrix()
    validation_records = 0
    predicted_class3 = 0
    for sample in validation.iter_samples(epoch=0, augment=False, balanced=False):
        output_value = model(sample["image"][None, ...], training=False)
        semantic_probs = output_value[0] if isinstance(output_value, (tuple, list, dict)) else output_value
        predicted = tf.argmax(semantic_probs[0], axis=-1).numpy().reshape(-1)
        truth_flat = sample["semantic"].reshape(-1)
        weight_flat = sample["semantic_weight"].reshape(-1)
        real_truth = (truth_flat != 255) & (weight_flat == 1.0)
        predicted_class3 += int(np.count_nonzero((predicted == 3) & real_truth))
        confusion.update(sample["semantic"].reshape(-1), predicted,
                         weight_flat)
        validation_records += 1
    summary = confusion.summary()
    report = {
        "schema_version": 1,
        "status": "pass" if gradient_summary(class3_channel_gradient.numpy())["finite"] and gradient_summary(class3_channel_gradient.numpy())["nonzero"] and any(g["finite"] and g["nonzero"] for g in class3_param_summaries) and summary["valid_pixels"] > 0 else "fail",
        "inputs": {"manifest": str(manifest_path), "manifest_sha256": _sha256(manifest_path),
                   "architecture": architecture, "checkpoint": str(checkpoint_path),
                   "checkpoint_sha256": _sha256(checkpoint_path), "device": device,
                   "sample_count_requested": sample_count, "sample_count_selected": len(positives)},
        "class_mapping": {str(i): name for i, name in enumerate(CLASS_NAMES)},
        "training_positive_samples": [{"id": row["sample"]["id"], "class_pixel_counts": dict(zip(CLASS_NAMES, row["counts"]))} for row in positives],
        "gradient_diagnostic": {
            "objective_semantics": {
                "whole_semantic": "existing CombinedSemanticLoss over real semantic supervision",
                "class3_only": "mean negative log probability of class 3 over pixels where semantic==3 and semantic_weight==1.0",
                "class3_supervised_pixels": class3_pixels,
            },
            "whole_semantic_objective": {
                "loss": float(whole_loss.numpy()), "loss_finite": whole_finite_loss,
                "all_channel_logit_gradient": gradient_summary(whole_logit_gradient.numpy()),
                "trainable_parameter_gradients": {
                    "finite": all(g["finite"] for g in whole_param_summaries),
                    "nonzero": any(g["nonzero"] for g in whole_param_summaries),
                    "tensors": len(whole_param_summaries), "summaries": whole_param_summaries,
                },
            },
            "class3_only_objective": {
                "loss": float(class3_loss.numpy()), "loss_finite": class3_finite_loss,
                "class3_logit_gradient": gradient_summary(class3_channel_gradient.numpy()),
                "trainable_parameter_gradients": {
                    "finite": all(g["finite"] for g in class3_param_summaries),
                    "nonzero": any(g["nonzero"] for g in class3_param_summaries),
                    "tensors": len(class3_param_summaries), "summaries": class3_param_summaries,
                },
            },
        },
        "surface_validation": {"split": "validation", "records": validation_records,
                               "class_metrics": summary,
                               "crosswalk_iou": summary["class_iou"]["crosswalk"],
                               "crosswalk_prediction_pixels": predicted_class3,
                               "confusion_matrix_truth_rows_prediction_columns": summary["confusion_matrix"]},
        "city_crosswalk_metrics": None,
    }
    report_path = Path(output).expanduser().resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return report


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--architecture", required=True, choices=("model_a", "model_b", "model_c"))
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--device", default="/CPU:0")
    parser.add_argument("--sample-count", type=int, default=4)
    parser.add_argument("--output", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        report = run_diagnostic(args.manifest, args.architecture, args.checkpoint,
                                args.device, args.sample_count, args.output)
    except Exception as error:
        print(json.dumps({"status": "fail", "error": str(error)}, sort_keys=True))
        return 2
    print(json.dumps({"status": report["status"], "output": str(Path(args.output).resolve())}, sort_keys=True))
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
