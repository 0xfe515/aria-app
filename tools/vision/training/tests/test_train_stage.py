"""True staged-runner tests with tiny Keras models and converted image fixtures."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

import numpy as np
import tensorflow as tf
from PIL import Image
from tensorflow import keras

from tools.vision.training import train as train_module
from tools.vision.training.data import CLASS_NAMES, HAZARD_NAMES, letterbox_geometry
from tools.vision.training.train import (
    CheckpointManager,
    CheckpointMetadata,
    STAGE_SPECS,
    _scan_training_statistics,
    _set_stage_trainability,
    _validate_stage_c_evidence,
    train_stage,
)


HEIGHT = 512
WIDTH = 288


def _tiny_model_a(weights=None, freeze_bn=True, input_range="0_255"):
    image = keras.Input(shape=(HEIGHT, WIDTH, 3), name="image_input")
    encoded = keras.layers.Conv2D(4, 1, activation="relu", name="encoder_conv")(image)
    encoder = keras.Model(image, encoded, name="mobilenetv2_encoder")
    features = keras.layers.Conv2D(
        4, 1, activation="relu", name="decoder_final_features"
    )(encoder(image))
    semantic = keras.layers.Conv2D(
        len(CLASS_NAMES), 1, activation="softmax", name="semantic_output"
    )(features)
    return keras.Model(image, semantic, name="portrait_model_a")


def _tiny_model_b(weights=None, freeze_bn=True, input_range="0_255"):
    model_a = _tiny_model_a(weights=weights, freeze_bn=freeze_bn, input_range=input_range)
    return train_module.attach_risk_head(model_a)


def _write_record(root: Path, record_id: str, source: str, split: str) -> dict[str, object]:
    original_dir = root / "original"
    converted_dir = root / "converted"
    original_dir.mkdir(parents=True, exist_ok=True)
    converted_dir.mkdir(parents=True, exist_ok=True)
    original_path = original_dir / f"{record_id}.jpg"
    Image.new("RGB", (4, 4), (70, 90, 110)).save(original_path)

    left, top, content_width, content_height = letterbox_geometry(4, 4)
    image = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    image[top:top + content_height, left:left + content_width] = (70, 90, 110)
    image_path = converted_dir / f"{record_id}.png"
    Image.fromarray(image, mode="RGB").save(image_path)

    semantic = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
    source_ids = np.zeros((HEIGHT, WIDTH), dtype=np.uint8)
    for class_id in range(len(CLASS_NAMES)):
        start = top + class_id * content_height // len(CLASS_NAMES)
        end = top + (class_id + 1) * content_height // len(CLASS_NAMES)
        semantic[start:end, left:left + content_width] = class_id
        source_ids[start:end, left:left + content_width] = 1
    semantic_path = converted_dir / f"{record_id}.semantic.png"
    Image.fromarray(semantic, mode="L").save(semantic_path)

    provenance_path = converted_dir / f"{record_id}.provenance.npz"
    np.savez_compressed(
        provenance_path,
        semantic_source=source_ids,
        semantic_weight=source_ids.astype(np.float32),
    )
    hazard = np.zeros((HEIGHT, WIDTH, len(HAZARD_NAMES)), dtype=np.uint8)
    hazard_valid = np.zeros_like(hazard)
    hazard_valid[top:top + content_height, left:left + content_width] = 1
    hazard[top:top + content_height // 8, left:left + content_width // 8, 0] = 1
    hazard_path = converted_dir / f"{record_id}.hazards.npz"
    np.savez_compressed(hazard_path, hazard=hazard, hazard_valid=hazard_valid)

    return {
        "id": record_id,
        "source": source,
        "group_id": f"group-{record_id}",
        "split": split,
        "source_split": "surface" if source == "surface" else "city-train",
        "image_path": str(original_path.resolve()),
        "image_sha256": hashlib.sha256(record_id.encode("utf-8")).hexdigest(),
        "image_tensor_path": image_path.relative_to(root).as_posix(),
        "semantic_path": semantic_path.relative_to(root).as_posix(),
        "hazard_path": hazard_path.relative_to(root).as_posix(),
        "hazard_valid_path": hazard_path.relative_to(root).as_posix(),
        "semantic_source_path": provenance_path.relative_to(root).as_posix(),
        "semantic_weight_path": provenance_path.relative_to(root).as_posix(),
    }


def _write_manifest(root: Path, records: list[dict[str, object]]) -> Path:
    path = root / "manifest.json"
    path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "seed": 42,
                "class_names": list(CLASS_NAMES),
                "hazard_names": list(HAZARD_NAMES),
                "records": records,
            }
        ),
        encoding="utf-8",
    )
    return path


def _make_init_checkpoint(
    root: Path,
    manifest_hash: str,
    stage: str,
    architecture: str,
    epoch: int = 1,
    fill: bool = False,
    optimizer_steps: int = 0,
):
    model = _tiny_model_a() if architecture == "model_a" else _tiny_model_b()
    optimizer = keras.optimizers.Adam(learning_rate=1e-4)
    optimizer.build(model.trainable_variables)
    for _ in range(optimizer_steps):
        optimizer.apply_gradients(
            (tf.ones_like(variable) * 1e-3, variable) for variable in model.trainable_variables
        )
    if fill:
        # Distinctive constant weights prove the next stage really starts from this state.
        model.set_weights(
            [np.full(w.shape, 0.01 * (index + 1), dtype=np.float32)
             for index, w in enumerate(model.get_weights())]
        )
    checkpoint_dir = root / f"{stage}-checkpoint"
    manager = CheckpointManager(checkpoint_dir)
    metadata = CheckpointMetadata(
        stage=stage,
        architecture=architecture,
        epoch=epoch,
        data_manifest_hash=manifest_hash,
    )
    manager.save_checkpoint(
        model,
        optimizer,
        metadata,
        [tf.zeros_like(variable) for variable in model.trainable_variables],
    )
    return checkpoint_dir / "latest"


def _configure_test_runtime() -> None:
    """Best-effort process configuration for standalone test runs.

    TensorFlow allows setting visible devices and thread pool parallelism only
    before the eager runtime context is initialized. When running as part of a
    combined discovery suite where earlier modules have already initialized
    TensorFlow, modifying thread pools raises a RuntimeError. Mid-suite, global
    thread pools are already established for the process and test cases execute
    serially within the test runner, so per-suite thread modification is neither
    needed nor supported. We ignore only post-initialization RuntimeErrors,
    ensuring unrelated configuration errors are not masked.
    """
    try:
        tf.config.set_visible_devices([], "GPU")
    except RuntimeError as err:
        if "cannot be modified after being initialized" not in str(err):
            raise

    for setter in (
        tf.config.threading.set_intra_op_parallelism_threads,
        tf.config.threading.set_inter_op_parallelism_threads,
    ):
        try:
            setter(2)
        except RuntimeError as err:
            if "cannot be modified after initialization" not in str(err):
                raise


class TrueStageRunnerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        _configure_test_runtime()

    def test_true_a_stage_streams_data_and_resumes_only_matching_epoch_checkpoint(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _write_manifest(
                root,
                [
                    _write_record(root, "city-train-1", "cityscapes", "train"),
                    _write_record(root, "city-val-1", "cityscapes", "val"),
                ],
            )
            output = root / "runner"
            original_evaluate = train_module.evaluate_validation_split
            calls = 0

            def interrupt_after_one_saved_epoch(*args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise RuntimeError("controlled interruption after epoch-one checkpoint")
                return original_evaluate(*args, **kwargs)

            with (
                mock.patch.object(train_module, "build_model_a", side_effect=_tiny_model_a),
                mock.patch.object(
                    train_module,
                    "evaluate_validation_split",
                    side_effect=interrupt_after_one_saved_epoch,
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "controlled interruption"):
                    train_stage(
                        "city_warmup",
                        manifest,
                        output,
                        weights=None,
                        batch_size=1,
                        accumulate_steps=2,
                        epochs=2,
                    )

            latest = output / "city_warmup" / "checkpoints" / "latest"
            saved = train_module._checkpoint_metadata(latest)
            self.assertEqual(saved.stage, "city_warmup")
            self.assertEqual(saved.epoch, 1)
            self.assertEqual(saved.accumulation_counter, 0)
            self.assertEqual(saved.class_weights and len(saved.class_weights), len(CLASS_NAMES))

            with mock.patch.object(train_module, "build_model_a", side_effect=_tiny_model_a):
                with self.assertRaisesRegex(ValueError, "seed mismatch"):
                    train_stage(
                        "city_warmup",
                        manifest,
                        output,
                        weights=None,
                        batch_size=1,
                        accumulate_steps=2,
                        epochs=2,
                        seed=43,
                        resume_path=latest,
                    )
                summary = train_stage(
                    "city_warmup",
                    manifest,
                    output,
                    weights=None,
                    batch_size=1,
                    accumulate_steps=2,
                    epochs=2,
                    resume_path=latest,
                )
            self.assertEqual(summary["epochs_completed"], 2)
            self.assertTrue(summary["training_eligible"])
            self.assertEqual(summary["real_training_class_pixel_counts"], [41 * WIDTH] * 6 + [42 * WIDTH])
            self.assertEqual(summary["source_record_counts"], {"cityscapes": 1, "surface": 0})
            self.assertIn("semantic_by_source", summary["history"][0]["validation_metrics"])

    def test_stage_b_head_initializes_from_a_and_its_resume_uses_same_b_architecture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _write_manifest(
                root,
                [
                    _write_record(root, "city-train-a", "cityscapes", "train"),
                    _write_record(root, "city-train-b", "cityscapes", "train"),
                    _write_record(root, "surface-train", "surface", "train"),
                    _write_record(root, "city-val", "cityscapes", "val"),
                    _write_record(root, "surface-val", "surface", "val"),
                ],
            )
            init_checkpoint = _make_init_checkpoint(
                root, train_module.compute_sha256(manifest), "mixed_block13", "model_a"
            )
            output = root / "runner"
            original_evaluate = train_module.evaluate_validation_split
            calls = 0

            def interrupt_after_one_saved_epoch(*args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise RuntimeError("controlled B interruption after epoch-one checkpoint")
                return original_evaluate(*args, **kwargs)

            with (
                mock.patch.object(train_module, "build_model_a", side_effect=_tiny_model_a),
                mock.patch.object(
                    train_module, "build_model_b", side_effect=_tiny_model_b
                ),
                mock.patch.object(
                    train_module,
                    "evaluate_validation_split",
                    side_effect=interrupt_after_one_saved_epoch,
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "controlled B interruption"):
                    train_stage(
                        "stage_b_head",
                        manifest,
                        output,
                        init_checkpoint_path=init_checkpoint,
                        weights=None,
                        batch_size=3,
                        accumulate_steps=4,
                        epochs=2,
                    )

            latest = output / "stage_b_head" / "checkpoints" / "latest"
            saved = train_module._checkpoint_metadata(latest)
            self.assertEqual(saved.stage, "stage_b_head")
            self.assertEqual(saved.epoch, 1)
            self.assertEqual(saved.patience_counter, 0)
            with mock.patch.object(train_module, "build_model_b", side_effect=_tiny_model_b):
                summary = train_stage(
                    "stage_b_head",
                    manifest,
                    output,
                    batch_size=3,
                    accumulate_steps=4,
                    epochs=2,
                    resume_path=latest,
                )
            self.assertEqual(summary["epochs_completed"], 2)
            self.assertEqual(summary["source_record_counts"], {"cityscapes": 2, "surface": 1})
            self.assertTrue(
                all(
                    path.split("/")[0].split(":")[-1] in {"risk_conv3x3", "hazard_output"}
                    for path in summary["trainable_variable_paths"]
                )
            )
            self.assertTrue(
                summary["history"][0]["val_semantic_nonregressed"]
                or summary["best_val_semantic_loss"] <= summary["semantic_baseline"] + 1e-6
            )

    def test_class_frequencies_ignore_pseudo_pixels_and_b_head_freezes_everything_else(self):
        batch = {
            "source": ["surface"],
            "semantic": np.array([[[0, 0, 1, 1, 2, 3, 4, 5, 6]]], dtype=np.int32),
            "semantic_source": np.array([[[1, 2, 1, 1, 1, 1, 1, 1, 1]]], dtype=np.uint8),
        }

        class FakeDataset:
            def iter_batches(self, *args, **kwargs):
                yield batch

        counts, sources = _scan_training_statistics(FakeDataset(), batch_size=1)
        self.assertEqual(counts, [1, 2, 1, 1, 1, 1, 1])
        self.assertEqual(sum(counts), 8)
        self.assertEqual(sources, {"cityscapes": 0, "surface": 1})

        model = _tiny_model_b()
        _set_stage_trainability(model, "stage_b_head", STAGE_SPECS["stage_b_head"])
        variable_paths = {
            getattr(variable, "path", variable.name).split("/")[0]
            for variable in model.trainable_variables
        }
        self.assertEqual(variable_paths, {"risk_conv3x3", "hazard_output"})
        self.assertTrue(all(not layer.trainable for layer in model.layers if "batch_normalization" in layer.name))

    def test_empty_validation_and_nonpositive_epochs_are_fatal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _write_manifest(
                root, [_write_record(root, "city-train", "cityscapes", "train")]
            )
            with self.assertRaisesRegex(ValueError, "epochs must be a positive integer"):
                train_stage("city_warmup", manifest, root / "out", epochs=0)
            with self.assertRaisesRegex(ValueError, "smoke_steps must be a positive integer"):
                train_module.run_smoke_test(root / "smoke", steps=0)
            with self.assertRaisesRegex(ValueError, "No records match split='val'"):
                train_stage("city_warmup", manifest, root / "out", weights=None, epochs=1)

    def test_c_requires_checkpoint_bound_evaluation_not_accepted_flag_alone(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_hash = "a" * 64
            checkpoint = _make_init_checkpoint(
                root, manifest_hash, "stage_b_joint", "model_b"
            )
            evidence_path = root / "accepted.json"
            evidence_path.write_text(
                json.dumps({
                    "schema_version": 1,
                    "accepted": True,
                    "stage": "stage_b_joint",
                }),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "checkpoint_sha256"):
                _validate_stage_c_evidence(evidence_path, checkpoint, manifest_hash)

    def test_cold_keras3_optimizer_restore_builds_slots_and_restores_iteration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = keras.Input(shape=(2,))
            outputs = keras.layers.Dense(1)(inputs)
            source_model = keras.Model(inputs, outputs)
            optimizer = keras.optimizers.Adam(learning_rate=0.01)
            optimizer.build(source_model.trainable_variables)
            optimizer.apply_gradients(
                (tf.ones_like(variable), variable)
                for variable in source_model.trainable_variables
            )
            checkpoint_dir = root / "checkpoint"
            manager = CheckpointManager(checkpoint_dir)
            manager.save_checkpoint(
                source_model,
                optimizer,
                CheckpointMetadata(stage="city_warmup", architecture="model_a"),
                [tf.zeros_like(variable) for variable in source_model.trainable_variables],
            )

            restored_model = keras.models.clone_model(source_model)
            cold_optimizer = keras.optimizers.Adam(learning_rate=0.01)
            self.assertLessEqual(len(cold_optimizer.variables), 2)
            metadata, accumulator, counter = CheckpointManager(
                checkpoint_dir / "latest"
            ).restore_checkpoint(
                restored_model,
                optimizer=cold_optimizer,
                expected_metadata={"stage": "city_warmup"},
            )
            self.assertEqual(metadata.stage, "city_warmup")
            self.assertEqual(int(cold_optimizer.iterations.numpy()), 1)
            self.assertEqual(counter, 0)
            self.assertEqual(len(accumulator), len(restored_model.trainable_variables))
            for original, restored in zip(source_model.weights, restored_model.weights):
                np.testing.assert_array_equal(original.numpy(), restored.numpy())


def _tiny_model_c(weights=None, freeze_bn=True, input_range="0_255", include_hazard_head=True):
    return _tiny_model_b(weights=weights, freeze_bn=freeze_bn, input_range=input_range)


def _tiny_builders():
    return mock.patch.multiple(
        train_module,
        build_model_a=_tiny_model_a,
        build_model_b=_tiny_model_b,
        build_model_c=_tiny_model_c,
    )


def _mixed_manifest(root: Path) -> Path:
    return _write_manifest(
        root,
        [
            _write_record(root, "city-train-a", "cityscapes", "train"),
            _write_record(root, "city-train-b", "cityscapes", "train"),
            _write_record(root, "surface-train", "surface", "train"),
            _write_record(root, "city-val", "cityscapes", "val"),
            _write_record(root, "surface-val", "surface", "val"),
        ],
    )


def _city_manifest(root: Path) -> Path:
    return _write_manifest(
        root,
        [
            _write_record(root, "city-train-1", "cityscapes", "train"),
            _write_record(root, "city-val-1", "cityscapes", "val"),
        ],
    )


def _interrupt_on_evaluation(call_number: int):
    """Fails the Nth validation call: 1=baseline, 2=epoch 1, 3=epoch 2 (before its checkpoint)."""
    original = train_module.evaluate_validation_split
    calls = 0

    def wrapper(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == call_number:
            raise RuntimeError("controlled interruption")
        return original(*args, **kwargs)

    return mock.patch.object(train_module, "evaluate_validation_split", side_effect=wrapper)


def _load_tiny_a_weights(checkpoint: Path):
    model = _tiny_model_a()
    model.load_weights(str(checkpoint / "model_weights.weights.h5"))
    return model.get_weights()


def _iterations_index(model) -> int:
    optimizer = keras.optimizers.Adam()
    optimizer.build(model.trainable_variables)
    return next(i for i, v in enumerate(optimizer.variables) if v is optimizer.iterations)


class TrainerCorrectionTests(unittest.TestCase):
    """Regression tests for the staged-trainer gaps, driven through the real train_stage path."""

    @classmethod
    def setUpClass(cls) -> None:
        _configure_test_runtime()

    def test_resume_is_strict_uses_supplied_path_and_matches_uninterrupted_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            common = dict(weights=None, batch_size=1, accumulate_steps=2, epochs=2)
            with _tiny_builders():
                reference = train_stage("city_warmup", manifest, root / "reference", **common)
                with _interrupt_on_evaluation(3):
                    with self.assertRaisesRegex(RuntimeError, "controlled interruption"):
                        train_stage("city_warmup", manifest, root / "interrupted", **common)
                saved = root / "interrupted" / "city_warmup" / "checkpoints" / "latest"
                self.assertEqual(train_module._checkpoint_metadata(saved).epoch, 1)
                supplied = root / "supplied-copy"
                shutil.copytree(saved, supplied)
                # The original state is removed, so only the supplied path can be restored.
                shutil.rmtree(root / "interrupted")

                bad_variants = [
                    (dict(batch_size=2), ValueError, "batch_size mismatch"),
                    (dict(accumulate_steps=3), ValueError, "accumulate_steps mismatch"),
                    (dict(epochs=3), ValueError, "stage_config_hash mismatch"),
                    (dict(learning_rate=2e-3), ValueError, "learning_rate mismatch"),
                    (dict(seed=7), ValueError, "seed mismatch"),
                    (dict(init_checkpoint_path=supplied), ValueError, "mutually exclusive"),
                ]
                for index, (override, error, pattern) in enumerate(bad_variants):
                    with self.subTest(pattern=pattern):
                        with self.assertRaisesRegex(error, pattern):
                            train_stage(
                                "city_warmup", manifest, root / f"bad-{index}",
                                resume_path=supplied, **{**common, **override},
                            )
                with self.assertRaisesRegex(ValueError, "stage mismatch"):
                    train_stage(
                        "city_full", manifest, root / "bad-stage", resume_path=supplied,
                        weights=None, batch_size=1, accumulate_steps=2, epochs=2,
                    )
                with self.assertRaises(FileNotFoundError):
                    train_stage(
                        "city_warmup", manifest, root / "bad-missing",
                        resume_path=root / "does-not-exist", **common,
                    )
                for name, pattern in (
                    ("optimizer_state.npz", "Optimizer state missing"),
                    ("accumulator_state.npz", "accumulator state missing"),
                    ("rng_state.json", "RNG state missing"),
                    ("model_weights.weights.h5", "No valid weights"),
                ):
                    with self.subTest(missing=name):
                        broken = root / f"broken-{name}"
                        shutil.copytree(supplied, broken)
                        (broken / name).unlink()
                        with self.assertRaisesRegex(FileNotFoundError, pattern):
                            train_stage(
                                "city_warmup", manifest, root / f"bad-{name}",
                                resume_path=broken, **common,
                            )

                resumed = train_stage(
                    "city_warmup", manifest, root / "resumed", resume_path=supplied, **common
                )
            self.assertEqual(resumed["epochs_completed"], 2)
            self.assertEqual(len(resumed["history"]), 1)
            self.assertEqual(resumed["history"][0]["epoch"], 2)
            for resumed_w, reference_w in zip(
                _load_tiny_a_weights(root / "resumed" / "city_warmup" / "checkpoints" / "latest"),
                _load_tiny_a_weights(root / "reference" / "city_warmup" / "checkpoints" / "latest"),
            ):
                np.testing.assert_allclose(
                    resumed_w, reference_w, rtol=0, atol=1e-6,
                    err_msg="Resumed training diverged from the uninterrupted deterministic run.",
                )
            self.assertAlmostEqual(
                resumed["history"][0]["val_total_loss"],
                reference["history"][1]["val_total_loss"],
                places=5,
            )

            # A changed manifest is a different dataset: refuse to resume across it.
            manifest.write_text(manifest.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            with _tiny_builders():
                with self.assertRaisesRegex(ValueError, "manifest hash mismatch"):
                    train_stage(
                        "city_warmup", manifest, root / "bad-manifest",
                        resume_path=supplied, **common,
                    )

    def test_early_stopped_checkpoint_cannot_be_resumed_past_patience(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _mixed_manifest(root)
            manifest_hash = train_module.compute_sha256(manifest)
            init = _make_init_checkpoint(root, manifest_hash, "mixed_decoder", "model_a")
            scores = iter([5.0] + [6.0] * 10)

            def fake_validation(trainer, *args, **kwargs):
                total = next(scores)
                return {"total_loss": total, "semantic_loss": total, "hazard_loss": None,
                        "mean_iou": 0.1, "metrics": {}}

            with _tiny_builders(), mock.patch.object(
                train_module, "evaluate_validation_split", side_effect=fake_validation
            ):
                summary = train_stage(
                    "mixed_block13", manifest, root / "out", init_checkpoint_path=init,
                    batch_size=3, accumulate_steps=2, epochs=20,
                )
                self.assertEqual(summary["epochs_completed"], 5)  # patience 5, never improved
                latest = root / "out" / "mixed_block13" / "checkpoints" / "latest"
                with self.assertRaisesRegex(ValueError, "already reached early stopping"):
                    train_stage(
                        "mixed_block13", manifest, root / "out", resume_path=latest,
                        batch_size=3, accumulate_steps=2, epochs=20,
                    )

    def test_previous_stage_checkpoint_rules_for_initialization(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            manifest_hash = train_module.compute_sha256(manifest)
            good = _make_init_checkpoint(root / "good", manifest_hash, "city_warmup", "model_a")
            wrong_stage = _make_init_checkpoint(root / "wrong", manifest_hash, "mixed_block13", "model_a")
            wrong_hash = _make_init_checkpoint(root / "hash", "b" * 64, "city_warmup", "model_a")
            untrained = _make_init_checkpoint(
                root / "untrained", manifest_hash, "city_warmup", "model_a", epoch=0
            )
            kwargs = dict(batch_size=1, accumulate_steps=2, epochs=1)
            with _tiny_builders():
                with self.assertRaisesRegex(ValueError, "requires --init-checkpoint from the city_warmup"):
                    train_stage("city_full", manifest, root / "o1", **kwargs)
                with self.assertRaisesRegex(ValueError, r"must be city_warmup/model_a; found mixed_block13"):
                    train_stage("city_full", manifest, root / "o2", init_checkpoint_path=wrong_stage, **kwargs)
                with self.assertRaisesRegex(ValueError, "manifest hash does not match"):
                    train_stage("city_full", manifest, root / "o3", init_checkpoint_path=wrong_hash, **kwargs)
                with self.assertRaisesRegex(ValueError, "no completed training epoch"):
                    train_stage("city_full", manifest, root / "o4", init_checkpoint_path=untrained, **kwargs)
                with self.assertRaisesRegex(ValueError, "must start fresh"):
                    train_stage("city_warmup", manifest, root / "o5", init_checkpoint_path=good,
                                weights=None, **kwargs)
                with self.assertRaises(FileNotFoundError):
                    train_stage("city_full", manifest, root / "o6",
                                init_checkpoint_path=root / "missing", **kwargs)
                with self.assertRaisesRegex(ValueError, "weights must be"):
                    train_stage("city_warmup", manifest, root / "o7", weights="random", **kwargs)

    def test_city_full_warm_starts_from_prior_stage_and_resets_optimizer_and_epoch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            manifest_hash = train_module.compute_sha256(manifest)
            init = _make_init_checkpoint(
                root, manifest_hash, "city_warmup", "model_a", fill=True, optimizer_steps=5
            )
            first_call = {}
            original = train_module.evaluate_validation_split

            def spy(trainer, *args, **kwargs):
                if not first_call:
                    first_call["weights"] = [w.copy() for w in trainer.model.get_weights()]
                    first_call["iterations"] = int(trainer.optimizer.iterations.numpy())
                return original(trainer, *args, **kwargs)

            with _tiny_builders(), mock.patch.object(
                train_module, "evaluate_validation_split", side_effect=spy
            ):
                summary = train_stage(
                    "city_full", manifest, root / "out", init_checkpoint_path=init,
                    batch_size=1, accumulate_steps=2, epochs=1,
                )
            for index, weights in enumerate(first_call["weights"]):
                np.testing.assert_array_equal(weights, np.full_like(weights, 0.01 * (index + 1)))
            self.assertEqual(first_call["iterations"], 0)  # prior optimizer is never inherited
            latest = root / "out" / "city_full" / "checkpoints" / "latest"
            metadata = train_module._checkpoint_metadata(latest)
            self.assertEqual((metadata.stage, metadata.epoch, metadata.global_step), ("city_full", 1, 1))
            with np.load(latest / "optimizer_state.npz") as optimizer_state:
                iterations = optimizer_state[f"opt_{_iterations_index(_tiny_model_a())}"]
            self.assertEqual(int(iterations), 1)
            self.assertEqual(summary["initialization_mode"], "checkpoint")
            self.assertEqual(
                summary["initialization_checkpoint_sha256"],
                train_module.compute_sha256(init / "model_weights.weights.h5"),
            )

    def test_b_joint_initializes_from_b_head_not_from_a_or_scratch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _mixed_manifest(root)
            manifest_hash = train_module.compute_sha256(manifest)
            head = _make_init_checkpoint(root / "head", manifest_hash, "stage_b_head", "model_b", fill=True)
            a_checkpoint = _make_init_checkpoint(root / "a", manifest_hash, "mixed_block13", "model_a")
            seen = {}
            original = train_module.evaluate_validation_split

            def spy(trainer, *args, **kwargs):
                seen.setdefault("weights", [w.copy() for w in trainer.model.get_weights()])
                return original(trainer, *args, **kwargs)

            with _tiny_builders():
                with self.assertRaisesRegex(ValueError, "must be stage_b_head/model_b"):
                    train_stage("stage_b_joint", manifest, root / "wrong", init_checkpoint_path=a_checkpoint,
                                batch_size=3, accumulate_steps=2, epochs=1)
                with mock.patch.object(train_module, "evaluate_validation_split", side_effect=spy):
                    summary = train_stage(
                        "stage_b_joint", manifest, root / "out", init_checkpoint_path=head,
                        batch_size=3, accumulate_steps=2, epochs=1,
                    )
            for index, weights in enumerate(seen["weights"]):
                np.testing.assert_array_equal(weights, np.full_like(weights, 0.01 * (index + 1)))
            self.assertEqual(summary["architecture"], "model_b")
            self.assertTrue(summary["trainable_variable_paths"])

    def test_stage_b_selection_cannot_choose_semantically_degraded_hazard_improvement(self):
        for semantic_after, expect_best_epoch in ((2.0, 0), (1.0, 1)):
            with self.subTest(semantic_after=semantic_after), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                manifest = _mixed_manifest(root)
                init = _make_init_checkpoint(
                    root, train_module.compute_sha256(manifest), "mixed_block13", "model_a"
                )
                results = iter([
                    {"total_loss": 5.0, "semantic_loss": 1.0, "hazard_loss": 4.0,
                     "mean_iou": 0.1, "metrics": {}},
                    {"total_loss": 1.0, "semantic_loss": semantic_after, "hazard_loss": 0.0,
                     "mean_iou": 0.2, "metrics": {}},
                ])
                with _tiny_builders(), mock.patch.object(
                    train_module, "evaluate_validation_split",
                    side_effect=lambda *a, **k: next(results),
                ):
                    summary = train_stage(
                        "stage_b_head", manifest, root / "out", init_checkpoint_path=init,
                        batch_size=3, accumulate_steps=2, epochs=1,
                    )
                checkpoints = root / "out" / "stage_b_head" / "checkpoints"
                best = train_module._checkpoint_metadata(checkpoints / "best")
                latest = train_module._checkpoint_metadata(checkpoints / "latest")
                self.assertEqual(best.epoch, expect_best_epoch)
                self.assertEqual(latest.epoch, 1)
                self.assertEqual(summary["semantic_baseline"], 1.0)
                self.assertEqual(summary["history"][0]["val_semantic_nonregressed"], expect_best_epoch == 1)
                self.assertEqual(latest.patience_counter, 0 if expect_best_epoch else 1)
                self.assertEqual(summary["best_val_total_loss"], 1.0 if expect_best_epoch else 5.0)
                self.assertEqual(summary["history"][0]["val_semantic_loss"], semantic_after)
                self.assertEqual(summary["history"][0]["val_hazard_loss"], 0.0)

    def test_partial_manifest_is_rejected_by_default_and_diagnostic_mode_is_bounded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unconverted = _write_record(root, "city-train-unconverted", "cityscapes", "train")
            for key in (
                "image_tensor_path", "semantic_path", "hazard_path", "hazard_valid_path",
                "semantic_source_path", "semantic_weight_path",
            ):
                unconverted.pop(key)
            manifest = _write_manifest(
                root,
                [
                    _write_record(root, "city-train-ok", "cityscapes", "train"),
                    unconverted,
                    _write_record(root, "city-val-ok", "cityscapes", "val"),
                ],
            )
            kwargs = dict(weights=None, batch_size=1, accumulate_steps=2)
            with _tiny_builders():
                with self.assertRaisesRegex(ValueError, "not converted"):
                    train_stage("city_warmup", manifest, root / "o1", epochs=1, **kwargs)
                with self.assertRaisesRegex(ValueError, "requires --verification-samples"):
                    train_stage("city_warmup", manifest, root / "o2", epochs=1,
                                allow_partial=True, **kwargs)
                with self.assertRaisesRegex(ValueError, "exactly one epoch"):
                    train_stage("city_warmup", manifest, root / "o3", epochs=2,
                                allow_partial=True, verification_samples=1, **kwargs)
                with self.assertRaisesRegex(ValueError, "valid only with --allow-partial-manifest"):
                    train_stage("city_warmup", manifest, root / "o4", epochs=1,
                                verification_samples=1, **kwargs)
                summary = train_stage("city_warmup", manifest, root / "o5", epochs=1,
                                      allow_partial=True, verification_samples=1, **kwargs)
            self.assertFalse(summary["training_eligible"])
            self.assertEqual(summary["verification_sample_limit"], 1)
            self.assertEqual(summary["source_record_counts"], {"cityscapes": 1, "surface": 0})
            self.assertEqual(
                list(summary["diagnostics"]["train"]["incomplete_record_ids"]),
                ["city-train-unconverted"],
            )
            self.assertIn("NONE", summary["acceptance_claim"])

    def test_class_weights_come_from_real_pixel_counts_and_are_passed_to_the_loss(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            real_loss = train_module.CombinedSemanticLoss
            constructed = []

            def spy(*args, **kwargs):
                constructed.append(kwargs)
                return real_loss(*args, **kwargs)

            with _tiny_builders(), mock.patch.object(train_module, "CombinedSemanticLoss", spy):
                summary = train_stage(
                    "city_warmup", manifest, root / "out", weights=None,
                    batch_size=1, accumulate_steps=2, epochs=1,
                )
            expected = train_module.compute_inverse_sqrt_class_weights(
                summary["real_training_class_pixel_counts"]
            )
            self.assertEqual(constructed[0]["class_weights"], expected)
            self.assertEqual(summary["class_weights"], expected)
            self.assertTrue(all(0.5 <= value <= 3.0 for value in expected))
            saved = train_module._checkpoint_metadata(
                root / "out" / "city_warmup" / "checkpoints" / "latest"
            )
            self.assertEqual(saved.class_weights, expected)

    def test_empty_training_epoch_is_an_error_not_a_zero_loss_epoch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            original = train_module._iter_limited_batches

            def empty_when_training(dataset, batch_size, **kwargs):
                if kwargs.get("augment"):
                    return iter(())
                return original(dataset, batch_size, **kwargs)

            with _tiny_builders(), mock.patch.object(
                train_module, "_iter_limited_batches", side_effect=empty_when_training
            ):
                with self.assertRaisesRegex(ValueError, "yielded no samples"):
                    train_stage(
                        "city_warmup", manifest, root / "out", weights=None,
                        batch_size=1, accumulate_steps=2, epochs=1,
                    )

    def test_oom_fails_with_last_valid_checkpoint_and_documented_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            kwargs = dict(weights=None, batch_size=1, accumulate_steps=2, epochs=1)
            with _tiny_builders():
                with mock.patch.object(
                    train_module.StagedTrainer, "train_micro_step",
                    side_effect=tf.errors.ResourceExhaustedError(None, None, "OOM"),
                ):
                    with self.assertRaises(RuntimeError) as raised:
                        train_stage("city_warmup", manifest, root / "out", **kwargs)
                message = str(raised.exception)
                latest = root / "out" / "city_warmup" / "checkpoints" / "latest"
                self.assertIn("automatic retry was not attempted", message)
                self.assertIn(str(latest), message)
                self.assertIn("--resume", message)
                self.assertIn("--batch-size 1 --accumulate-steps 8", message)
                self.assertIn("--output-dir <new empty directory>", message)
                self.assertEqual(train_module._checkpoint_metadata(latest).epoch, 0)
                # A fresh (non-resume) run never silently overwrites existing checkpoints.
                with self.assertRaises(FileExistsError):
                    train_stage("city_warmup", manifest, root / "out", **kwargs)
                # The advertised last-valid checkpoint really is resumable.
                summary = train_stage("city_warmup", manifest, root / "out", resume_path=latest, **kwargs)
                self.assertEqual(summary["epochs_completed"], 1)

    def test_device_selection_does_not_touch_environment_and_gpu_mode_requires_gpu(self):
        before = dict(os.environ)
        train_module.configure_device("cpu")
        self.assertEqual(dict(os.environ), before)
        with mock.patch.object(tf.config, "list_physical_devices", return_value=[]):
            with self.assertRaisesRegex(RuntimeError, "no physical GPU"):
                train_module.configure_device("gpu")
        with self.assertRaises(ValueError):
            train_module.configure_device("tpu")
        self.assertEqual(dict(os.environ), before)

    def test_cli_entry_runs_a_real_stage_and_validates_numeric_arguments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _city_manifest(root)
            base = ["--manifest", str(manifest), "--stage", "city_warmup", "--weights", "none",
                    "--batch-size", "1", "--accumulate-steps", "2"]
            parsed = train_module.build_parser().parse_args(base + ["--verification-samples", "3"])
            self.assertEqual(parsed.verification_samples, 3)
            with _tiny_builders():
                for bad, pattern in (
                    (["--epochs", "0"], "epochs must be a positive integer"),
                    (["--epochs", "-3"], "epochs must be a positive integer"),
                    (["--batch-size", "0"], "batch_size must be a positive integer"),
                    (["--accumulate-steps", "-1"], "accumulate_steps must be a positive integer"),
                    (["--learning-rate", "0"], "learning_rate must be finite and positive"),
                    (["--learning-rate", "nan"], "learning_rate must be finite and positive"),
                ):
                    with self.subTest(argument=bad):
                        with self.assertRaisesRegex(ValueError, pattern):
                            train_module.main(base + bad + ["--output-dir", str(root / "bad")])
                with self.assertRaisesRegex(ValueError, "Stage C is disabled until accepted"):
                    train_module.main(
                        ["--manifest", str(manifest), "--stage", "stage_c",
                         "--output-dir", str(root / "c")]
                    )
                self.assertEqual(
                    train_module.main(base + ["--epochs", "1", "--output-dir", str(root / "out")]), 0
                )
            summary = json.loads(
                (root / "out" / "city_warmup" / "training_summary.json").read_text(encoding="utf-8")
            )
            self.assertEqual(summary["epochs_completed"], 1)
            self.assertEqual(summary["stage"], "city_warmup")
            self.assertEqual(len(summary["manifest_sha256"]), 64)
            self.assertEqual(summary["seed"], 42)
            self.assertIn("tensorflow", summary["software_versions"])
            self.assertEqual(summary["source_record_counts"], {"cityscapes": 1, "surface": 0})
            self.assertIn("NONE", summary["acceptance_claim"])

    def test_stage_c_resume_revalidates_teacher_evidence_and_unproven_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = _mixed_manifest(root)
            manifest_hash = train_module.compute_sha256(manifest)
            teacher = _make_init_checkpoint(root, manifest_hash, "stage_b_joint", "model_b")
            teacher_metadata = train_module._checkpoint_metadata(teacher)
            evidence = root / "accepted.json"
            evidence.write_text(
                json.dumps({"schema_version": 1, "accepted": True, "stage": "stage_b_joint"}),
                encoding="utf-8",
            )
            kwargs = dict(weights=None, batch_size=3, accumulate_steps=2, epochs=2)
            teacher_args = dict(teacher_checkpoint_path=teacher, teacher_evidence_path=evidence)
            with _tiny_builders():
                # accepted=true alone (no checkpoint-bound evaluator chain) never starts C.
                with self.assertRaisesRegex(ValueError, "checkpoint_sha256"):
                    train_stage("stage_c", manifest, root / "closed", **teacher_args, **kwargs)
                with self.assertRaisesRegex(ValueError, "Stage C is disabled until accepted"):
                    train_stage("stage_c", manifest, root / "closed2", **kwargs)

                validator = mock.patch.object(
                    train_module, "_validate_stage_c_evidence",
                    return_value=(teacher_metadata, "f" * 64),
                )
                with validator, _interrupt_on_evaluation(3):
                    with self.assertRaisesRegex(RuntimeError, "controlled interruption"):
                        train_stage("stage_c", manifest, root / "out", **teacher_args, **kwargs)
                latest = root / "out" / "stage_c" / "checkpoints" / "latest"
                saved = train_module._checkpoint_metadata(latest)
                self.assertEqual((saved.stage, saved.epoch, saved.teacher_checkpoint_hash),
                                 ("stage_c", 1, "f" * 64))
                with validator as validation:
                    with self.assertRaisesRegex(ValueError, "Stage C is disabled until accepted"):
                        train_stage("stage_c", manifest, root / "out", resume_path=latest, **kwargs)
                    summary = train_stage(
                        "stage_c", manifest, root / "out", resume_path=latest,
                        **teacher_args, **kwargs,
                    )
                    self.assertEqual(validation.call_count, 1)  # re-validated on resume
                self.assertEqual(summary["epochs_completed"], 2)
                self.assertEqual(summary["teacher_checkpoint_sha256"], "f" * 64)

    def test_microbatch_gradients_are_sample_weighted_and_clipped_once_per_window(self):
        with tempfile.TemporaryDirectory() as temporary:
            model = _tiny_model_a()
            clipnorm = 1e-3
            trainer = train_module.StagedTrainer(
                model, architecture="model_a", stage="city_warmup", learning_rate=1e-3,
                clipnorm=clipnorm, batch_size=3, accumulate_steps=2,
                class_weights=[1.0] * len(CLASS_NAMES), checkpoint_dir=Path(temporary),
            )
            rng = np.random.RandomState(0)

            def make_batch(size):
                return (
                    tf.constant(rng.uniform(0, 255, (size, HEIGHT, WIDTH, 3)).astype(np.float32)),
                    tf.constant(rng.randint(0, len(CLASS_NAMES), (size, HEIGHT, WIDTH)).astype(np.int32)),
                    tf.ones((size, HEIGHT, WIDTH), dtype=tf.float32),
                )

            def gradients(batch):
                with tf.GradientTape() as tape:
                    loss, _ = trainer.compute_loss(batch[0], batch[1], batch[2])
                return tape.gradient(loss, model.trainable_variables)

            small, large, remainder = make_batch(1), make_batch(3), make_batch(2)
            g_small, g_large = gradients(small), gradients(large)
            captured = []
            original_apply = trainer.optimizer.apply_gradients

            def capture(pairs):
                pairs = list(pairs)
                captured.append([gradient.numpy() for gradient, _ in pairs])
                return original_apply(pairs)

            with mock.patch.object(trainer.optimizer, "apply_gradients", side_effect=capture):
                stepped, _ = trainer.train_micro_step(*small, sample_count=1)
                self.assertFalse(stepped)
                self.assertEqual(trainer.accumulated_samples, 1)
                stepped, _ = trainer.train_micro_step(*large, sample_count=3)
                self.assertTrue(stepped)
                self.assertEqual(len(captured), 1)
                expected, _ = tf.clip_by_global_norm(
                    [(a * 1.0 + b * 3.0) / 4.0 for a, b in zip(g_small, g_large)], clipnorm
                )
                for applied, wanted in zip(captured[0], expected):
                    np.testing.assert_allclose(applied, wanted.numpy(), rtol=1e-4, atol=1e-9)
                applied_norm = float(np.sqrt(sum(float(np.sum(g * g)) for g in captured[0])))
                self.assertAlmostEqual(applied_norm, clipnorm, places=7)
                self.assertEqual((trainer.accumulation_counter, trainer.accumulated_samples), (0, 0))

                # A final partial window is flushed with its own sample count at epoch end.
                g_remainder = gradients(remainder)  # weights moved after the first optimizer step
                trainer.train_micro_step(*remainder, sample_count=2)
                self.assertTrue(trainer.flush_accumulated_gradients())
                self.assertEqual(len(captured), 2)
                expected_tail, _ = tf.clip_by_global_norm(g_remainder, clipnorm)
                for applied, wanted in zip(captured[1], expected_tail):
                    np.testing.assert_allclose(applied, wanted.numpy(), rtol=1e-4, atol=1e-9)
                self.assertFalse(trainer.flush_accumulated_gradients())


if __name__ == "__main__":
    unittest.main()
