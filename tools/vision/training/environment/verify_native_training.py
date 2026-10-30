#!/usr/bin/env python3
"""Bounded native GPU verification of the real MobileNetV2/U-Net Model A trainer (WSL2).

DIAGNOSTIC ONLY. This tool trains the *actual* ``tools.vision.training.train`` trainer
(unchanged ``city_warmup`` stage, real MobileNetV2/U-Net Model A, native 512x288 FLOAT32,
ImageNet initialization) on a small *derived* real-data Cityscapes subset to check that
GPU multi-epoch training and epoch-boundary cold resume work. The subset is never
full-data acceptance, even though the loader marks a self-consistent subset manifest as
``training_eligible``; no model-quality claim is made.

Subcommands (run with the guest GPU interpreter, e.g. /home/user/.venvs/aria_wsl/bin/python):

    prepare   Build a derived city-only train/val subset (no test records) from the immutable
              full manifest, copying converted files into a fresh guest-native directory.
    model-info
              (Runs through run_wsl_training.py --module.) Build the real Model A exactly as
              the trainer does and report parameter/layer counts, shapes, trainability, BN
              freeze state and ImageNet-weight equality.
    run       Full workflow: model info, uninterrupted 2-epoch run, interrupted-after-epoch-1
              run, cold resume from the epoch-1 checkpoint, and weight/optimizer/loss comparison.
    compare   Compare two checkpoint directories (weights, optimizer state, metadata).
    instrumented-train
              (Runs through run_wsl_training.py --module.) Calls the real ``train.main`` with
              passive instrumentation and an optional deliberate interruption.

Nothing here edits trainer code or the original manifests; training outputs always go to
fresh directories.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[3]
RUNNER = HERE / "run_wsl_training.py"
SELF_MODULE = "tools.vision.training.environment.verify_native_training"
DEFAULT_BASE_MANIFEST = REPO_ROOT / "build" / "vision-training" / "data" / "manifest.json"
DEFAULT_REPORT_ROOT = REPO_ROOT / "build" / "vision-training" / "native-gpu-20261031"
DEFAULT_GUEST_ROOT = Path("/home/user/.local/share/aria-training/native-gpu-20261031")
INTERRUPT_EXIT_CODE = 75
PATH_KEYS = (
    "image_tensor_path",
    "semantic_path",
    "hazard_path",
    "hazard_valid_path",
    "semantic_source_path",
    "semantic_weight_path",
)
DIAGNOSTIC_NOTE = (
    "DIAGNOSTIC-ONLY derived Cityscapes subset (train+val only, no test records) for native GPU "
    "multi-epoch/resume mechanics. Not full-data acceptance and makes no model-quality claim, "
    "even though the loader may report training_eligible=true for this self-consistent subset."
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dir_size_bytes(path: Path) -> int:
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, allow_nan=False) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# Derived subset
# ---------------------------------------------------------------------------

def prepare_subset(base_manifest: Path, out_dir: Path, train_count: int, val_count: int) -> dict[str, Any]:
    """Builds a fresh derived Cityscapes subset; never touches the base manifest."""
    if out_dir.exists() and any(out_dir.iterdir()):
        raise FileExistsError(f"{out_dir} is not empty; derived subsets use fresh directories.")
    base_sha_before = sha256_file(base_manifest)
    base = json.loads(base_manifest.read_text(encoding="utf-8"))

    def pick(split: str, count: int, excluded_groups: set[str], excluded_hashes: set[str]) -> list[dict]:
        by_city: dict[str, list[dict]] = {}
        for record in base["records"]:
            if record.get("source") != "cityscapes" or record.get("split") != split:
                continue
            if not all(record.get(key) for key in PATH_KEYS):
                continue
            if not (base_manifest.parent / record["image_tensor_path"]).is_file():
                continue
            by_city.setdefault(str(record.get("city")), []).append(record)
        selected: list[dict] = []
        groups, hashes = set(excluded_groups), set(excluded_hashes)  # groups: cross-split exclusion only
        cities = sorted(by_city)
        index = 0
        while len(selected) < count and any(by_city.values()):
            city = cities[index % len(cities)]
            index += 1
            queue = by_city[city]
            while queue:
                record = queue.pop(0)
                if record["group_id"] in groups or record["image_sha256"] in hashes:
                    continue
                hashes.add(record["image_sha256"])
                selected.append(record)
                break
        if len(selected) < count:
            raise RuntimeError(f"Only {len(selected)} cityscapes {split} records available, need {count}.")
        return selected

    train = pick("train", train_count, set(), set())
    val = pick(
        "val", val_count,
        {record["group_id"] for record in train}, {record["image_sha256"] for record in train},
    )
    records = train + val
    out_dir.mkdir(parents=True, exist_ok=True)
    copied = 0
    for record in records:
        for key in PATH_KEYS:
            relative = record[key]
            destination = out_dir / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                shutil.copy2(base_manifest.parent / relative, destination)
                copied += 1
    manifest = {
        "schema_version": base["schema_version"],
        "seed": base["seed"],
        "class_names": base["class_names"],
        "hazard_names": base["hazard_names"],
        "diagnostic_subset": True,
        "diagnostic_note": DIAGNOSTIC_NOTE,
        "source_manifest_sha256": base_sha_before,
        "records": records,
    }
    manifest_path = out_dir / "manifest_subset.json"
    write_json(manifest_path, manifest)
    base_sha_after = sha256_file(base_manifest)
    if base_sha_before != base_sha_after:
        raise RuntimeError("Base manifest changed during subset preparation.")
    info = {
        "diagnostic_only": True,
        "note": DIAGNOSTIC_NOTE,
        "base_manifest": str(base_manifest),
        "base_manifest_sha256": base_sha_after,
        "base_manifest_unchanged": True,
        "subset_manifest": str(manifest_path),
        "subset_manifest_sha256": sha256_file(manifest_path),
        "train_records": len(train),
        "val_records": len(val),
        "test_records": 0,
        "train_cities": sorted({str(record.get("city")) for record in train}),
        "val_cities": sorted({str(record.get("city")) for record in val}),
        "train_ids": [record["id"] for record in train],
        "val_ids": [record["id"] for record in val],
        "group_disjoint_train_val": not (
            {record["group_id"] for record in train} & {record["group_id"] for record in val}
        ),
        "copied_files": copied,
        "subset_bytes": dir_size_bytes(out_dir),
    }
    if not info["group_disjoint_train_val"]:
        raise RuntimeError("Derived train/val subsets share a group.")
    return info


# ---------------------------------------------------------------------------
# Checkpoint inspection and comparison (no TensorFlow needed)
# ---------------------------------------------------------------------------

def _load_h5_arrays(path: Path) -> dict[str, Any]:
    import h5py

    arrays: dict[str, Any] = {}
    with h5py.File(path, "r") as handle:
        def visit(name: str, obj: Any) -> None:
            if isinstance(obj, h5py.Dataset):
                arrays[name] = obj[()]
        handle.visititems(visit)
    return arrays


def _resolve_checkpoint(path: Path) -> Path:
    if (path / "metadata.json").is_file():
        return path
    if (path / "latest" / "metadata.json").is_file():
        return path / "latest"
    raise FileNotFoundError(f"No checkpoint metadata under {path}")


def _is_encoder(key: str) -> bool:
    # Keras 3 .weights.h5 stores nested Functional sub-models under ``layers/functional``
    # (the MobileNetV2 encoder); decoder/head layers are top-level ``conv2d*`` layers.
    return key.startswith("layers/functional/") or "mobilenetv2_encoder" in key


def checkpoint_facts(path: Path) -> dict[str, Any]:
    import numpy as np

    path = _resolve_checkpoint(path)
    metadata = json.loads((path / "metadata.json").read_text(encoding="utf-8"))
    weights = _load_h5_arrays(path / "model_weights.weights.h5")
    with np.load(path / "optimizer_state.npz") as data:
        optimizer = {name: data[name] for name in data.files}
    iteration = optimizer.get("opt_0")
    return {
        "path": str(path),
        "metadata": {key: metadata.get(key) for key in (
            "stage", "epoch", "global_step", "architecture", "seed", "batch_size", "accumulate_steps",
            "learning_rate", "clipnorm", "initialization_mode", "data_manifest_hash", "stage_config_hash",
            "class_weights", "patience", "patience_counter", "accumulation_counter", "accumulated_samples",
            "optimizer", "best_val_loss", "best_val_miou", "timestamp",
        )},
        "optimizer_iterations": None if iteration is None else int(iteration),
        "optimizer_variable_count": len(optimizer),
        "weight_array_count": len(weights),
        "weights_all_finite": all(bool(np.all(np.isfinite(value))) for value in weights.values()),
        "optimizer_all_finite": all(bool(np.all(np.isfinite(value))) for value in optimizer.values()),
        "sha256": {
            name: sha256_file(path / name)
            for name in ("model_weights.weights.h5", "optimizer_state.npz", "accumulator_state.npz",
                         "rng_state.json", "metadata.json")
            if (path / name).is_file()
        },
        "bytes": dir_size_bytes(path),
    }


def compare_arrays(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    import numpy as np

    if set(a) != set(b):
        return {"keys_match": False, "only_in_a": sorted(set(a) - set(b))[:10], "only_in_b": sorted(set(b) - set(a))[:10]}
    result: dict[str, Any] = {"keys_match": True, "array_count": len(a)}
    for label, selector in (("encoder", _is_encoder), ("non_encoder", lambda key: not _is_encoder(key)), ("all", lambda key: True)):
        max_abs = 0.0
        max_rel = 0.0
        count = 0
        for key in a:
            if not selector(key):
                continue
            left = np.asarray(a[key], dtype=np.float64)
            right = np.asarray(b[key], dtype=np.float64)
            if left.shape != right.shape:
                return {"keys_match": True, "shape_mismatch": key}
            diff = float(np.max(np.abs(left - right))) if left.size else 0.0
            scale = float(np.max(np.abs(left))) if left.size else 0.0
            max_abs = max(max_abs, diff)
            max_rel = max(max_rel, diff / scale if scale > 0 else diff)
            count += 1
        result[f"{label}_max_abs_diff"] = max_abs
        result[f"{label}_max_rel_diff"] = max_rel
        result[f"{label}_arrays"] = count
    return result


def compare_checkpoints(first: Path, second: Path) -> dict[str, Any]:
    import numpy as np

    first, second = _resolve_checkpoint(first), _resolve_checkpoint(second)
    weights = compare_arrays(
        _load_h5_arrays(first / "model_weights.weights.h5"), _load_h5_arrays(second / "model_weights.weights.h5")
    )
    with np.load(first / "optimizer_state.npz") as left, np.load(second / "optimizer_state.npz") as right:
        optimizer = compare_arrays({n: left[n] for n in left.files}, {n: right[n] for n in right.files})
    meta_a = json.loads((first / "metadata.json").read_text(encoding="utf-8"))
    meta_b = json.loads((second / "metadata.json").read_text(encoding="utf-8"))
    ignored = {"timestamp"}
    metadata_diff = {
        key: {"a": meta_a.get(key), "b": meta_b.get(key)}
        for key in sorted(set(meta_a) | set(meta_b))
        if key not in ignored and meta_a.get(key) != meta_b.get(key)
    }
    return {
        "a": str(first), "b": str(second),
        "weights": weights, "optimizer": optimizer, "metadata_differences": metadata_diff,
        "weights_files_identical_sha256": sha256_file(first / "model_weights.weights.h5") == sha256_file(second / "model_weights.weights.h5"),
    }


# ---------------------------------------------------------------------------
# Process execution with timestamps and GPU sampling
# ---------------------------------------------------------------------------

class GpuSampler(threading.Thread):
    """Polls nvidia-smi once per interval for total GPU memory use and utilization."""

    def __init__(self, interval: float = 1.0) -> None:
        super().__init__(daemon=True)
        self.interval = interval
        self.stop_event = threading.Event()
        self.samples: list[tuple[float, int, int]] = []
        self.binary = shutil.which("nvidia-smi") or "/usr/lib/wsl/lib/nvidia-smi"

    def query(self) -> tuple[int, int] | None:
        try:
            out = subprocess.run(
                [self.binary, "--query-gpu=memory.used,utilization.gpu", "--format=csv,noheader,nounits", "-i", "0"],
                capture_output=True, text=True, timeout=15, check=True,
            ).stdout.strip().split(",")
            return int(out[0]), int(out[1])
        except (OSError, subprocess.SubprocessError, ValueError, IndexError):
            return None

    def run(self) -> None:
        while not self.stop_event.is_set():
            sample = self.query()
            if sample is not None:
                self.samples.append((time.time(), sample[0], sample[1]))
            self.stop_event.wait(self.interval)

    def summary(self, baseline_mib: int | None) -> dict[str, Any]:
        if not self.samples:
            return {"available": False}
        used = [s[1] for s in self.samples]
        util = [s[2] for s in self.samples]
        return {
            "available": True,
            "samples": len(self.samples),
            "baseline_memory_used_mib_before_run": baseline_mib,
            "peak_total_gpu_memory_used_mib": max(used),
            "peak_delta_over_baseline_mib": None if baseline_mib is None else max(used) - baseline_mib,
            "max_gpu_utilization_percent": max(util),
            "mean_gpu_utilization_percent": round(sum(util) / len(util), 1),
            "samples_with_utilization_ge_20": sum(1 for value in util if value >= 20),
        }


EVENT_PATTERNS = {
    "trainer_banner": re.compile(r"^Staged Trainer:"),
    "first_batch": re.compile(r"^\s+Batch 1/"),
    "epoch_line": re.compile(r"^Epoch \d+/\d+ \|"),
}


def run_logged(command: list[str], log_path: Path, timeout_seconds: int) -> dict[str, Any]:
    """Runs a command, timestamping each output line into ``log_path``."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    sampler = GpuSampler()
    baseline = sampler.query()
    sampler.start()
    started = time.time()
    events: list[dict[str, Any]] = []
    process = subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, cwd=REPO_ROOT,
        errors="replace", env={**os.environ, "PYTHONUNBUFFERED": "1"},
    )
    timed_out = threading.Event()

    def kill() -> None:
        timed_out.set()
        process.kill()

    timer = threading.Timer(timeout_seconds, kill)
    timer.start()
    with log_path.open("w", encoding="utf-8") as log:
        log.write(f"# command: {' '.join(command)}\n# started: {utc_now()}\n")
        assert process.stdout is not None
        for line in process.stdout:
            elapsed = time.time() - started
            log.write(f"[+{elapsed:8.2f}s] {line}")
            log.flush()
            for name, pattern in EVENT_PATTERNS.items():
                if pattern.search(line):
                    events.append({"event": name, "t_seconds": round(elapsed, 2), "line": line.strip()})
        return_code = process.wait()
        wall = time.time() - started
        log.write(f"# finished: {utc_now()} exit={return_code} wall={wall:.2f}s timed_out={timed_out.is_set()}\n")
    timer.cancel()
    sampler.stop_event.set()
    sampler.join(timeout=20)
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    return {
        "command": command,
        "exit_code": return_code,
        "timed_out": timed_out.is_set(),
        "wall_seconds": round(wall, 2),
        "log": str(log_path),
        "events": events,
        "out_of_memory_detected": bool(re.search(r"ResourceExhausted|Out of memory|OOM when allocating", log_text)),
        "gpu_nvidia_smi": sampler.summary(baseline[0] if baseline else None),
    }


def runner_command(
    train_args: list[str], *, module: str | None = None, module_args: list[str] | None = None
) -> list[str]:
    command = [sys.executable, str(RUNNER), "--deterministic", "--allow-growth"]
    if module:
        command += ["--module", module, "--"] + (module_args or []) + ["--"] + train_args
    else:
        command += ["--"] + train_args
    return command


def train_arguments(manifest: Path, output_dir: Path, args: argparse.Namespace, resume: Path | None = None) -> list[str]:
    command = [
        "--manifest", str(manifest), "--stage", "city_warmup", "--weights", "imagenet",
        "--output-dir", str(output_dir), "--batch-size", str(args.batch_size),
        "--accumulate-steps", str(args.accumulate_steps), "--epochs", str(args.epochs),
        "--seed", str(args.seed), "--device", "gpu",
    ]
    if resume is not None:
        command += ["--resume", str(resume)]
    return command


# ---------------------------------------------------------------------------
# Instrumented train / model info (executed through run_wsl_training.py --module)
# ---------------------------------------------------------------------------

class DiagnosticInterrupt(RuntimeError):
    """Deliberate verification interruption (not a trainer defect)."""


def _count_layers(model: Any) -> dict[str, int]:
    total = 0
    batch_norm = 0
    trainable_batch_norm = 0

    def walk(layer: Any) -> None:
        nonlocal total, batch_norm, trainable_batch_norm
        for inner in getattr(layer, "layers", []):
            total += 1
            if "batch_normalization" in inner.name.lower() or type(inner).__name__ == "BatchNormalization":
                batch_norm += 1
                trainable_batch_norm += int(inner.trainable)
            walk(inner)

    walk(model)
    return {"layers_recursive": total, "batch_norm_layers": batch_norm, "batch_norm_layers_trainable": trainable_batch_norm}


def model_info(args: argparse.Namespace) -> int:
    from tools.vision.training import train as T
    import numpy as np
    import tensorflow as tf
    from tensorflow import keras

    T.configure_device(device="gpu")
    started = time.time()
    model = T.build_model_a(weights="imagenet", freeze_bn=False, input_range="0_255")
    build_seconds = time.time() - started
    T._set_stage_trainability(model, "city_warmup", T.STAGE_SPECS["city_warmup"])
    encoder = model.get_layer("mobilenetv2_encoder")
    base = keras.applications.MobileNetV2(input_shape=T.INPUT_SHAPE, include_top=False, weights="imagenet")
    compared_layers = 0
    compared_tensors = 0
    imagenet_equal = True
    reference_layers = {layer.name: layer for layer in base.layers}
    for encoder_layer in encoder.layers:
        encoder_layer_weights = encoder_layer.get_weights()
        if not encoder_layer_weights:
            continue
        reference_layer = reference_layers.get(encoder_layer.name)
        if reference_layer is None:
            imagenet_equal = False
            continue
        base_layer_weights = reference_layer.get_weights()
        compared_layers += 1
        compared_tensors += len(encoder_layer_weights)
        if len(base_layer_weights) != len(encoder_layer_weights) or not all(
            left.shape == right.shape and np.array_equal(left, right)
            for left, right in zip(base_layer_weights, encoder_layer_weights)
        ):
            imagenet_equal = False
    imagenet_equal = imagenet_equal and compared_layers > 0
    random_reference = keras.applications.MobileNetV2(input_shape=T.INPUT_SHAPE, include_top=False, weights=None)
    differs_from_random = any(
        not np.array_equal(left, right)
        for left, right in zip(random_reference.get_layer("Conv1").get_weights(), encoder.get_layer("Conv1").get_weights())
    )
    non_encoder_trainable = sum(int(np.prod(v.shape)) for v in model.trainable_variables)
    encoder_trainable = sum(int(np.prod(v.shape)) for v in encoder.trainable_variables)
    info = {
        "model": "portrait_model_a (actual MobileNetV2 encoder + U-Net decoder)",
        "model_name": model.name,
        "encoder_layer": encoder.name,
        "encoder_layer_count": len(encoder.layers),
        "top_level_layer_count": len(model.layers),
        **_count_layers(model),
        "total_params": int(model.count_params()),
        "trainable_params": int(sum(int(np.prod(v.shape)) for v in model.trainable_variables)),
        "non_trainable_params": int(sum(int(np.prod(v.shape)) for v in model.non_trainable_variables)),
        "trainable_variable_count": len(model.trainable_variables),
        "trainable_variables_in_encoder": encoder_trainable,
        "trainable_params_outside_encoder": non_encoder_trainable - encoder_trainable,
        "input_shape": [list(t.shape) for t in model.inputs],
        "output_shape": [list(t.shape) for t in model.outputs],
        "variable_dtype": str(model.variables[0].dtype),
        "dtype_policy": str(keras.config.dtype_policy()),
        "trainability_policy": "city_warmup: encoder_policy=freeze_all, BatchNorm frozen; decoder + semantic head trainable",
        "imagenet_encoder_weights_equal_keras_application": bool(imagenet_equal),
        "imagenet_layers_compared": compared_layers,
        "imagenet_tensors_compared": compared_tensors,
        "imagenet_reference_layer_count_full_keras_mobilenetv2": len(base.layers),
        "imagenet_comparison_scope": (
            "every weighted layer of the actual truncated encoder vs the same-named layer in the full "
            "Keras MobileNetV2 ImageNet reference (reference layers past the encoder endpoints are not compared)"
        ),
        "encoder_conv1_differs_from_random_init": bool(differs_from_random),
        "build_seconds": round(build_seconds, 2),
        "tensorflow": tf.__version__,
        "keras": keras.__version__,
        "visible_gpus": [d.name for d in tf.config.get_visible_devices("GPU")],
        "TF_DETERMINISTIC_OPS": os.environ.get("TF_DETERMINISTIC_OPS"),
        "TF_CUDNN_DETERMINISTIC": os.environ.get("TF_CUDNN_DETERMINISTIC"),
        "TF_FORCE_GPU_ALLOW_GROWTH": os.environ.get("TF_FORCE_GPU_ALLOW_GROWTH"),
        "LD_LIBRARY_PATH_has_nvidia": "site-packages/nvidia" in os.environ.get("LD_LIBRARY_PATH", ""),
    }
    out = Path(args.evidence_json)
    write_json(out, info)
    print(json.dumps(info, indent=2))
    return 0


def instrumented_train(args: argparse.Namespace, train_argv: list[str]) -> int:
    """Calls the real train.main; hooks are passive except the optional deliberate interrupt."""
    from tools.vision.training import train as T
    import tensorflow as tf

    evidence_dir = Path(args.evidence_dir)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    state: dict[str, Any] = {"evaluation_calls": 0, "interrupted": False, "placement": None}
    real_eval = T.evaluate_validation_split
    real_step = T.StagedTrainer.train_micro_step

    def patched_eval(trainer: Any, *a: Any, **kw: Any) -> Any:
        state["evaluation_calls"] += 1
        call = state["evaluation_calls"]
        if call == 1:
            trainer.model.save_weights(str(evidence_dir / "initial_weights.weights.h5"))
            state["initial_weights_saved"] = True
        if args.interrupt_on_evaluation and call == args.interrupt_on_evaluation:
            state["interrupted"] = True
            state["interrupted_at_evaluation_call"] = call
            raise DiagnosticInterrupt(
                f"Deliberate verification interruption at validation evaluation call {call} "
                "(after epoch-2 training, before the epoch-2 checkpoint is written)."
            )
        return real_eval(trainer, *a, **kw)

    def patched_step(self: Any, *a: Any, **kw: Any) -> Any:
        result = real_step(self, *a, **kw)
        if state["placement"] is None:
            images = a[0] if a else kw.get("images")
            state["placement"] = {
                "input_batch_device": images.device,
                "model_output_device": self.model(images, training=False).device,
                "first_trainable_variable_device": self.model.trainable_variables[0].value.device,
                "last_trainable_variable_device": self.model.trainable_variables[-1].value.device,
                "frozen_encoder_variable_device": self.model.non_trainable_variables[0].value.device,
                "accumulator_device": self.accumulated_gradients[0].device,
                "optimizer_first_slot_device": self.optimizer.variables[-1].value.device,
                "logical_gpus": [d.name for d in tf.config.list_logical_devices("GPU")],
                "step_loss_finite": bool(math.isfinite(result[1]["total_loss"])),
                "step_loss": result[1]["total_loss"],
            }
        return result

    T.evaluate_validation_split = patched_eval
    T.StagedTrainer.train_micro_step = patched_step
    started = time.time()
    exit_code = 0
    failure = None
    try:
        exit_code = int(T.main(train_argv))
    except DiagnosticInterrupt as exc:
        exit_code = INTERRUPT_EXIT_CODE
        failure = str(exc)
    except BaseException as exc:
        exit_code = 1
        failure = f"UNEXPECTED {type(exc).__name__}: {exc}"
        raise
    finally:
        memory = None
        try:
            memory = {k: int(v) for k, v in tf.config.experimental.get_memory_info("GPU:0").items()}
        except (ValueError, RuntimeError):
            pass
        write_json(evidence_dir / "instrumentation.json", {
            "recorded_utc": utc_now(),
            "wall_seconds": round(time.time() - started, 2),
            "exit_code": exit_code,
            "deliberate_interrupt": failure,
            "state": state,
            "tf_allocator_gpu_memory_bytes": memory,
            "tf_allocator_peak_mib": None if not memory else round(memory["peak"] / 1048576, 1),
        })
    return exit_code


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def _summary_of(output_dir: Path) -> dict[str, Any] | None:
    path = output_dir / "city_warmup" / "training_summary.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def _finite_history(summary: dict[str, Any]) -> bool:
    keys = ("train_loss", "val_total_loss", "val_semantic_loss", "val_mean_iou")
    return all(math.isfinite(float(entry[key])) for entry in summary["history"] for key in keys)


def _relative_difference(a: float, b: float) -> float:
    return abs(a - b) / max(abs(a), 1e-12)


def run_workflow(args: argparse.Namespace) -> int:
    tag = args.tag or time.strftime("run_%Y%m%dT%H%M%SZ", time.gmtime())
    guest = Path(args.guest_root) / tag
    report_dir = Path(args.report_root) / tag
    if guest.exists() or report_dir.exists():
        raise FileExistsError(f"Fresh directories required; {guest} or {report_dir} already exists.")
    report_dir.mkdir(parents=True)
    guest.mkdir(parents=True)
    report: dict[str, Any] = {
        "task": "ARIA-NATIVE-GPU-TRAIN-VERIFY-20261031",
        "diagnostic_only": True,
        "claim_scope": DIAGNOSTIC_NOTE,
        "started_utc": utc_now(),
        "tag": tag,
        "guest_dir": str(guest),
        "report_dir": str(report_dir),
        "interpreter": sys.executable,
        "python": platform.python_version(),
        "kernel": platform.release(),
        "config": {
            "stage": "city_warmup", "epochs": args.epochs, "batch_size": args.batch_size,
            "accumulate_steps": args.accumulate_steps, "seed": args.seed, "weights": "imagenet",
            "device": "gpu", "deterministic_ops": True, "gpu_allow_growth": True,
            "train_records": args.train_count, "val_records": args.val_count,
        },
        "steps": {},
    }
    report_path = report_dir / "native_gpu_report.json"

    def save() -> None:
        write_json(report_path, report)

    def fail(step: str, message: str) -> int:
        report["status"] = "FAILED"
        report["failed_step"] = step
        report["failure"] = message
        report["finished_utc"] = utc_now()
        save()
        print(f"FAILED at {step}: {message}", file=sys.stderr)
        return 1

    # 1. Derived subset
    began = time.time()
    subset_dir = guest / "subset"
    if args.reuse_subset:
        reused = Path(args.reuse_subset)
        manifest_file = reused / "manifest_subset.json"
        if not manifest_file.is_file():
            raise FileNotFoundError(f"{manifest_file} not found")
        subset_manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
        subset = {
            "diagnostic_only": True, "note": DIAGNOSTIC_NOTE, "reused_existing_subset": str(reused),
            "base_manifest": str(args.base_manifest),
            "base_manifest_sha256": sha256_file(Path(args.base_manifest)),
            "subset_manifest": str(manifest_file), "subset_manifest_sha256": sha256_file(manifest_file),
            "train_records": sum(1 for r in subset_manifest_data["records"] if r["split"] == "train"),
            "val_records": sum(1 for r in subset_manifest_data["records"] if r["split"] == "val"),
            "test_records": sum(1 for r in subset_manifest_data["records"] if r["split"] == "test"),
        }
    else:
        subset = prepare_subset(Path(args.base_manifest), subset_dir, args.train_count, args.val_count)
    subset["seconds"] = round(time.time() - began, 2)
    report["steps"]["subset"] = subset
    save()
    manifest = Path(subset["subset_manifest"])

    # 2. Real model facts + ImageNet cache prefetch
    info_json = report_dir / "model_info.json"
    result = run_logged(
        runner_command([], module=SELF_MODULE, module_args=["model-info", "--evidence-json", str(info_json)]),
        report_dir / "logs" / "model_info.log", args.run_timeout,
    )
    report["steps"]["model_info"] = result
    if result["exit_code"] != 0 or not info_json.is_file():
        return fail("model_info", "model-info subprocess failed; see log.")
    report["model"] = json.loads(info_json.read_text(encoding="utf-8"))
    save()

    # 3. Uninterrupted run A (pure original entrypoint)
    run_a = guest / "A_uninterrupted"
    result = run_logged(runner_command(train_arguments(manifest, run_a, args)), report_dir / "logs" / "A_uninterrupted.log", args.run_timeout)
    summary_a = _summary_of(run_a)
    result["summary_present"] = summary_a is not None
    report["steps"]["A_uninterrupted"] = result
    save()
    if result["exit_code"] != 0 or summary_a is None:
        return fail("A_uninterrupted", "Uninterrupted run failed" + (" (OOM detected)" if result["out_of_memory_detected"] else "") + "; see log and any epoch-boundary checkpoint under " + str(run_a))
    result["summary"] = {
        key: summary_a[key] for key in (
            "epochs_completed", "device", "training_eligible", "initialization_mode", "source_record_counts",
            "class_weights", "software_versions", "latest_checkpoint_weights_sha256",
        )
    }
    result["history"] = summary_a["history"]
    result["history_all_finite"] = _finite_history(summary_a)
    result["checkpoint_latest"] = checkpoint_facts(run_a / "city_warmup" / "checkpoints" / "latest")
    result["disk_bytes"] = dir_size_bytes(run_a)
    save()

    # 4. Interrupted run B (instrumented real train.main, deliberate interrupt at evaluation 3)
    run_b = guest / "B_interrupted"
    evidence_b = report_dir / "B_instrumentation"
    result = run_logged(
        runner_command(
            train_arguments(manifest, run_b, args), module=SELF_MODULE,
            module_args=["instrumented-train", "--evidence-dir", str(evidence_b), "--interrupt-on-evaluation", "3"],
        ),
        report_dir / "logs" / "B_interrupted.log", args.run_timeout,
    )
    report["steps"]["B_interrupted"] = result
    save()
    instrumentation = json.loads((evidence_b / "instrumentation.json").read_text(encoding="utf-8")) if (evidence_b / "instrumentation.json").is_file() else None
    result["instrumentation"] = instrumentation
    if result["exit_code"] != INTERRUPT_EXIT_CODE or not instrumentation or not instrumentation["state"].get("interrupted"):
        return fail("B_interrupted", f"Expected deliberate interrupt exit {INTERRUPT_EXIT_CODE}, got {result['exit_code']} (OOM detected: {result['out_of_memory_detected']}).")
    latest_b = run_b / "city_warmup" / "checkpoints" / "latest"
    interrupted_facts = checkpoint_facts(latest_b)
    result["checkpoint_after_interrupt"] = interrupted_facts
    result["no_pending_checkpoint_dir"] = not (run_b / "city_warmup" / "checkpoints" / ".latest.pending").exists()
    if interrupted_facts["metadata"]["epoch"] != 1:
        return fail("B_interrupted", f"Preserved checkpoint is epoch {interrupted_facts['metadata']['epoch']}, expected 1.")
    snapshot = guest / "B_epoch1_latest_snapshot"
    shutil.copytree(latest_b, snapshot)
    result["epoch1_snapshot"] = checkpoint_facts(snapshot)
    save()

    # 5. Cold resume B2 (pure entrypoint, fresh process, identical schedule/config)
    run_b2 = guest / "B_resumed"
    result = run_logged(
        runner_command(train_arguments(manifest, run_b2, args, resume=snapshot)),
        report_dir / "logs" / "B_resumed.log", args.run_timeout,
    )
    summary_b2 = _summary_of(run_b2)
    report["steps"]["B_resumed"] = result
    save()
    if result["exit_code"] != 0 or summary_b2 is None:
        return fail("B_resumed", "Cold resume failed; see log.")
    result["summary"] = {key: summary_b2[key] for key in ("epochs_completed", "device", "training_eligible", "initialization_mode")}
    result["history_this_invocation"] = summary_b2["history"]
    result["history_all_finite"] = _finite_history(summary_b2)
    result["checkpoint_latest"] = checkpoint_facts(run_b2 / "city_warmup" / "checkpoints" / "latest")
    result["disk_bytes"] = dir_size_bytes(run_b2)
    save()

    # 6. Comparisons
    final_a = run_a / "city_warmup" / "checkpoints" / "latest"
    final_b2 = run_b2 / "city_warmup" / "checkpoints" / "latest"
    initial_weights = _load_h5_arrays(evidence_b / "initial_weights.weights.h5")
    epoch1_weights = _load_h5_arrays(snapshot / "model_weights.weights.h5")
    final_a_weights = _load_h5_arrays(final_a / "model_weights.weights.h5")
    final_b2_weights = _load_h5_arrays(final_b2 / "model_weights.weights.h5")
    comparison = {
        "final_A_vs_final_resumed": compare_checkpoints(final_a, final_b2),
        "B_initial_vs_B_epoch1": compare_arrays(initial_weights, epoch1_weights),
        "B_epoch1_vs_B_resumed_final": compare_arrays(epoch1_weights, final_b2_weights),
        "B_initial_vs_A_final": compare_arrays(initial_weights, final_a_weights),
    }
    expected_updates_per_epoch = math.ceil(math.ceil(args.train_count / args.batch_size) / args.accumulate_steps)
    expected_iterations = expected_updates_per_epoch * args.epochs
    history_a = report["steps"]["A_uninterrupted"]["history"]
    history_b2 = report["steps"]["B_resumed"]["history_this_invocation"]
    loss_checks = {
        "epoch2_train_loss_A": history_a[1]["train_loss"],
        "epoch2_train_loss_resumed": history_b2[0]["train_loss"],
        "epoch2_val_total_loss_A": history_a[1]["val_total_loss"],
        "epoch2_val_total_loss_resumed": history_b2[0]["val_total_loss"],
        "epoch2_val_miou_A": history_a[1]["val_mean_iou"],
        "epoch2_val_miou_resumed": history_b2[0]["val_mean_iou"],
        "epoch1_val_total_loss_A": history_a[0]["val_total_loss"],
        "epoch1_best_val_loss_in_interrupted_checkpoint": interrupted_facts["metadata"]["best_val_loss"],
    }
    loss_checks["epoch2_train_loss_relative_difference"] = _relative_difference(loss_checks["epoch2_train_loss_A"], loss_checks["epoch2_train_loss_resumed"])
    loss_checks["epoch2_val_total_loss_relative_difference"] = _relative_difference(loss_checks["epoch2_val_total_loss_A"], loss_checks["epoch2_val_total_loss_resumed"])
    weights_all = comparison["final_A_vs_final_resumed"]["weights"]
    facts_a = report["steps"]["A_uninterrupted"]["checkpoint_latest"]
    facts_b2 = report["steps"]["B_resumed"]["checkpoint_latest"]
    checks = {
        "A_two_epochs_completed": summary_a["epochs_completed"] == args.epochs,
        "A_device_gpu": summary_a["device"] == "gpu",
        "A_losses_finite": report["steps"]["A_uninterrupted"]["history_all_finite"],
        "A_weights_finite": facts_a["weights_all_finite"] and facts_a["optimizer_all_finite"],
        "A_optimizer_iterations_expected": facts_a["optimizer_iterations"] == expected_iterations,
        "interrupted_epoch1_checkpoint_preserved": interrupted_facts["metadata"]["epoch"] == 1,
        "interrupted_accumulator_empty": interrupted_facts["metadata"]["accumulation_counter"] == 0,
        "resumed_epoch_is_2": facts_b2["metadata"]["epoch"] == args.epochs,
        "resumed_only_ran_epoch2": [entry["epoch"] for entry in history_b2] == [2],
        "resumed_losses_finite": report["steps"]["B_resumed"]["history_all_finite"],
        "resumed_weights_finite": facts_b2["weights_all_finite"] and facts_b2["optimizer_all_finite"],
        "resumed_optimizer_iterations_expected": facts_b2["optimizer_iterations"] == expected_iterations,
        "same_manifest_hash": facts_a["metadata"]["data_manifest_hash"] == facts_b2["metadata"]["data_manifest_hash"] == interrupted_facts["metadata"]["data_manifest_hash"],
        "same_stage_config_hash": facts_a["metadata"]["stage_config_hash"] == facts_b2["metadata"]["stage_config_hash"] == interrupted_facts["metadata"]["stage_config_hash"],
        "same_class_weights": facts_a["metadata"]["class_weights"] == facts_b2["metadata"]["class_weights"],
        "frozen_encoder_unchanged_in_training": comparison["B_initial_vs_B_epoch1"].get("encoder_arrays", 0) == report["model"]["imagenet_tensors_compared"] and comparison["B_initial_vs_B_epoch1"].get("encoder_max_abs_diff") == 0.0 and comparison["B_epoch1_vs_B_resumed_final"].get("encoder_max_abs_diff") == 0.0,
        "decoder_updated_epoch1": comparison["B_initial_vs_B_epoch1"].get("non_encoder_max_abs_diff", 0.0) > 0.0,
        "decoder_updated_epoch2_after_resume": comparison["B_epoch1_vs_B_resumed_final"].get("non_encoder_max_abs_diff", 0.0) > 0.0,
        "A_final_weights_vs_resumed_max_abs_diff_le_1e-4": weights_all.get("all_max_abs_diff", float("inf")) <= 1e-4,
        "A_vs_resumed_optimizer_max_abs_diff_le_1e-4": comparison["final_A_vs_final_resumed"]["optimizer"].get("all_max_abs_diff", float("inf")) <= 1e-4,
        "A_vs_resumed_epoch2_losses_rel_diff_le_1e-3": loss_checks["epoch2_train_loss_relative_difference"] <= 1e-3 and loss_checks["epoch2_val_total_loss_relative_difference"] <= 1e-3,
        "ImageNet_encoder_init_verified": bool(report["model"]["imagenet_encoder_weights_equal_keras_application"]) and bool(report["model"]["encoder_conv1_differs_from_random_init"]),
        "gpu_tensor_placement_verified": bool(instrumentation["state"]["placement"]) and all(
            "GPU:0" in str(instrumentation["state"]["placement"][key])
            for key in ("model_output_device", "first_trainable_variable_device", "last_trainable_variable_device", "frozen_encoder_variable_device", "accumulator_device", "optimizer_first_slot_device")
        ),
        "base_manifest_unchanged": sha256_file(Path(args.base_manifest)) == subset["base_manifest_sha256"],
    }
    report["comparison"] = comparison
    report["loss_checks"] = loss_checks
    report["expected_optimizer_iterations"] = expected_iterations
    report["tolerance_notes"] = (
        "Weights/optimizer: deterministic-ops GPU float32; pass threshold 1e-4 absolute (bitwise equality "
        "reported separately). Losses: relative difference <= 1e-3. Any larger difference is a failure to explain."
    )
    report["checks"] = checks
    report["disk_bytes_total_guest"] = dir_size_bytes(guest)
    report["status"] = "PASS" if all(checks.values()) else "CHECK_FAILURES"
    report["finished_utc"] = utc_now()
    save()
    print(json.dumps({"status": report["status"], "checks": checks}, indent=2))
    return 0 if report["status"] == "PASS" else 2


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    train_argv: list[str] = []
    if "--" in argv:
        index = argv.index("--")
        argv, train_argv = argv[:index], argv[index + 1:]
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    prep = sub.add_parser("prepare", help="Build a fresh derived Cityscapes train/val diagnostic subset.")
    prep.add_argument("--base-manifest", default=str(DEFAULT_BASE_MANIFEST))
    prep.add_argument("--out-dir", required=True)
    prep.add_argument("--train-count", type=int, default=24)
    prep.add_argument("--val-count", type=int, default=6)

    run = sub.add_parser("run", help="Full native GPU 2-epoch / interrupt / cold-resume verification.")
    run.add_argument("--base-manifest", default=str(DEFAULT_BASE_MANIFEST))
    run.add_argument("--guest-root", default=str(DEFAULT_GUEST_ROOT))
    run.add_argument("--report-root", default=str(DEFAULT_REPORT_ROOT))
    run.add_argument("--tag", default="")
    run.add_argument("--reuse-subset", default="", help="Reuse an already prepared derived subset directory (read-only).")
    run.add_argument("--train-count", type=int, default=24)
    run.add_argument("--val-count", type=int, default=6)
    run.add_argument("--epochs", type=int, default=2)
    run.add_argument("--batch-size", type=int, default=2)
    run.add_argument("--accumulate-steps", type=int, default=4)
    run.add_argument("--seed", type=int, default=42)
    run.add_argument("--run-timeout", type=int, default=3600, help="Per-process wall-clock bound in seconds.")

    cmp_ = sub.add_parser("compare", help="Compare two checkpoint directories.")
    cmp_.add_argument("checkpoint_a")
    cmp_.add_argument("checkpoint_b")

    info = sub.add_parser("model-info", help="(via run_wsl_training.py --module) Report real Model A facts.")
    info.add_argument("--evidence-json", required=True)

    inst = sub.add_parser("instrumented-train", help="(via run_wsl_training.py --module) Real train.main plus hooks.")
    inst.add_argument("--evidence-dir", required=True)
    inst.add_argument("--interrupt-on-evaluation", type=int, default=0)

    args = parser.parse_args(argv)
    if args.command == "prepare":
        print(json.dumps(prepare_subset(Path(args.base_manifest), Path(args.out_dir), args.train_count, args.val_count), indent=2))
        return 0
    if args.command == "run":
        return run_workflow(args)
    if args.command == "compare":
        print(json.dumps(compare_checkpoints(Path(args.checkpoint_a), Path(args.checkpoint_b)), indent=2))
        return 0
    if args.command == "model-info":
        return model_info(args)
    return instrumented_train(args, train_argv)


if __name__ == "__main__":
    sys.exit(main())
