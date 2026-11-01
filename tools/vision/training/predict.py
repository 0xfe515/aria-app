"""Stream deterministic CPU TFLite semantic predictions for the evaluator."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from .data import CLASS_NAMES, HAZARD_NAMES, IGNORE, OUT_H, OUT_W
from .train_data import PortraitDataset


class PredictionError(ValueError):
    """Invalid model, dataset, or prediction contract."""


def _numpy() -> Any:
    try:
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("TFLite prediction requires NumPy.") from exc
    return np


def _validate_interpreter(interpreter: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    np = _numpy()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise PredictionError(f"Expected one semantic input and output, got {len(inputs)} and {len(outputs)}.")
    input_detail, output_detail = inputs[0], outputs[0]
    expected_input, expected_output = (1, OUT_H, OUT_W, 3), (1, OUT_H, OUT_W, len(CLASS_NAMES))
    if tuple(int(v) for v in input_detail["shape"]) != expected_input:
        raise PredictionError(f"Input tensor must be {expected_input}, got {tuple(input_detail['shape'])}.")
    if tuple(int(v) for v in output_detail["shape"]) != expected_output:
        raise PredictionError(f"Semantic output must be {expected_output}, got {tuple(output_detail['shape'])}.")
    for detail, label in ((input_detail, "input"), (output_detail, "output")):
        dtype = np.dtype(detail["dtype"])
        if not (np.issubdtype(dtype, np.floating) or np.issubdtype(dtype, np.integer)):
            raise PredictionError(f"Unsupported {label} dtype {dtype}.")
        if np.issubdtype(dtype, np.integer):
            scale, _ = detail.get("quantization", (0.0, 0))
            if scale <= 0:
                raise PredictionError(f"Quantized {label} has no quantization scale.")
    return input_detail, output_detail


def _model_input(image: Any, detail: Mapping[str, Any]) -> Any:
    np = _numpy()
    # Converted RGB is uint8 [0,255]; the app model contract consumes [0,1].
    real = np.asarray(image, dtype=np.float32) / 255.0
    dtype = np.dtype(detail["dtype"])
    if np.issubdtype(dtype, np.floating):
        return real.astype(dtype, copy=False)[None, ...]
    scale, zero = detail.get("quantization", (0.0, 0))
    info = np.iinfo(dtype)
    return np.clip(np.rint(real / scale + zero), info.min, info.max).astype(dtype)[None, ...]


def _load_interpreter(model: Path, threads: int) -> Any:
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        try:
            import tensorflow as tf
        except ImportError as exc:
            raise RuntimeError("Inference requires tflite_runtime or TensorFlow.") from exc
        try:
            tf.config.set_visible_devices([], "GPU")
        except RuntimeError as exc:
            raise RuntimeError("TensorFlow initialized before CPU-only inference was configured.") from exc
        Interpreter = tf.lite.Interpreter
    interpreter = Interpreter(model_path=str(model), num_threads=threads)
    interpreter.allocate_tensors()
    _validate_interpreter(interpreter)
    return interpreter


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _record(sample: Mapping[str, Any], interpreter: Any, input_detail: Mapping[str, Any],
            output_detail: Mapping[str, Any], split: str, model_hash: str) -> dict[str, Any]:
    np = _numpy()
    interpreter.set_tensor(input_detail["index"], _model_input(sample["image"], input_detail))
    interpreter.invoke()
    raw = np.asarray(interpreter.get_tensor(output_detail["index"]))
    if raw.shape != (1, OUT_H, OUT_W, len(CLASS_NAMES)):
        raise PredictionError(f"Runtime semantic output has malformed shape {raw.shape}.")
    if not np.isfinite(raw).all():
        raise PredictionError("Semantic output contains non-finite values.")
    semantic_prediction = np.argmax(raw[0], axis=-1).astype(np.uint8)
    truth = np.asarray(sample["semantic"], dtype=np.int32)
    source = np.asarray(sample["semantic_source"])
    if truth.shape != (OUT_H, OUT_W) or source.shape != truth.shape:
        raise PredictionError(f"Record {sample['id']} has missing or malformed semantic labels.")
    record: dict[str, Any] = {
        "record_id": sample["id"], "prediction_evidence": "saved_model_predictions",
        "domain": sample["source"], "split": split, "group_id": sample["group_id"],
        "semantic_truth": truth.reshape(-1).tolist(),
        "semantic_prediction": semantic_prediction.reshape(-1).astype(int).tolist(),
        "semantic_provenance": np.asarray(sample["semantic_weight"], dtype=np.float32).reshape(-1).tolist(),
        "model_sha256": model_hash,
        "preprocessing": {
            "implementation": "PortraitDataset converted RGB; no augmentation or pseudo-label overlay",
            "input_rgb_range": "uint8[0,255] scaled to float[0,1], then tensor dtype/quantization",
            "geometry": "512x288 upright full-frame letterbox from DATA_CONTRACT.md",
            "class_order": list(CLASS_NAMES), "risk_head_supported": False,
        },
    }
    if sample["source"] == "surface":
        hz = np.asarray(sample["hazard"], dtype=np.int32)
        valid = np.asarray(sample["hazard_valid"], dtype=np.int32)
        expected = (OUT_H, OUT_W, len(HAZARD_NAMES))
        if hz.shape != expected or valid.shape != expected:
            raise PredictionError(f"Record {sample['id']} has missing or malformed hazard labels.")
        record.update(risk_truth=hz.reshape(-1).tolist(), risk_prediction=[0.0] * hz.size,
                      risk_valid=valid.reshape(-1).tolist(), risk_prediction_supported=False)
    else:
        record["risk_prediction_supported"] = False
    return record


def generate_predictions(manifest: Path, model: Path, split: str, output: Path, *,
                         threads: int = 1, seed: int = 42,
                         thresholds_from: Path | None = None, limit: int | None = None,
                         interpreter_factory: Callable[[Path, int], Any] | None = None) -> dict[str, Any]:
    if split not in ("validation", "test"):
        raise PredictionError("split must be validation or test")
    if threads < 1 or seed < 0 or (limit is not None and limit < 1):
        raise PredictionError("threads and seed must be non-negative (threads positive); limit must be positive")
    manifest, model, output = manifest.resolve(), model.resolve(), output.resolve()
    if not manifest.is_file() or not model.is_file():
        raise PredictionError("Manifest and model files are required and must exist.")
    if output.exists():
        raise PredictionError(f"Refusing to overwrite existing prediction output: {output}")
    frozen = None
    if split == "test":
        if thresholds_from is None:
            raise PredictionError("test predictions require a frozen candidate validation report")
        try:
            frozen = json.loads(thresholds_from.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PredictionError(f"Cannot read validation report: {exc}") from exc
        if (frozen.get("schema_version") != 1
                or frozen.get("report_kind") != "saved_prediction_evaluation"
                or frozen.get("split") != "validation"
                or frozen.get("model_kind") != "candidate"):
            raise PredictionError("test predictions require a candidate validation report")
    elif thresholds_from is not None:
        try:
            frozen = json.loads(thresholds_from.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PredictionError(f"Cannot read validation report: {exc}") from exc
        if (frozen.get("schema_version") != 1
                or frozen.get("report_kind") != "saved_prediction_evaluation"
                or frozen.get("split") != "validation"
                or frozen.get("model_kind") != "candidate"):
            raise PredictionError("threshold metadata must be a candidate validation report")
    model_hash = _sha256(model)
    if frozen:
        if frozen.get("model_sha256") != model_hash:
            raise PredictionError("validation thresholds must match this model SHA-256")
        selection = frozen.get("risk_threshold_selection")
        if not isinstance(selection, dict) or set(selection) != set(HAZARD_NAMES):
            raise PredictionError("validation report has no complete frozen risk threshold metadata")
        if not isinstance(frozen.get("evaluation_fingerprint"), str):
            raise PredictionError("validation report has no evaluation fingerprint")
        membership = frozen.get("split_membership")
        if not isinstance(membership, dict) or not isinstance(membership.get("record_keys"), list):
            raise PredictionError("validation report has no split membership evidence")
    factory = interpreter_factory or _load_interpreter
    interpreter = factory(model, threads)
    input_detail, output_detail = _validate_interpreter(interpreter)
    dataset = PortraitDataset(manifest, "val" if split == "validation" else "test",
                              seed=seed, require_complete=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    opener = gzip.open if output.suffix.lower() == ".gz" else open
    count = 0
    # Exclusive create prevents replacing evidence if another process races this run.
    with opener(output, "xt", encoding="utf-8", newline="\n") as stream:
        for sample in dataset.iter_samples(augment=False, balanced=False):
            if limit is not None and count >= limit:
                break
            record = _record(sample, interpreter, input_detail, output_detail, split, model_hash)
            stream.write(json.dumps(record, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n")
            count += 1
    if not count:
        output.unlink(missing_ok=True)
        raise PredictionError("No records were emitted.")
    return {"records": count, "model_sha256": model_hash, "diagnostic_only": limit is not None,
            "acceptance_eligible": limit is None, "risk_head_supported": False}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--split", choices=("validation", "test"), required=True)
    parser.add_argument("--output", required=True, help="JSONL or .jsonl.gz; existing files are preserved")
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--thresholds-from", help="candidate validation report; required for test")
    parser.add_argument("--limit", type=int, help="diagnostic bounded inference; never acceptance eligible")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        result = generate_predictions(Path(args.manifest), Path(args.model), args.split,
            Path(args.output), threads=args.threads, seed=args.seed,
            thresholds_from=Path(args.thresholds_from) if args.thresholds_from else None, limit=args.limit)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        parser_error = f"prediction failed: {exc}"
        print(parser_error, file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
