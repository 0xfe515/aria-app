from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from tools.vision.training.pseudo_labels import (
    CONFIDENCE_THRESHOLD,
    MARGIN_THRESHOLD,
    MAX_WORKERS,
    OBJECT_CLASSES,
    PSEUDO_WEIGHT,
    PseudoLabelError,
    _validate_interpreter,
    generate_pseudo_labels,
    pseudo_supervision_arrays,
    select_converted_surface_training_records,
    select_agreed_object_pixels,
)


HAS_NUMPY_PIL = (
    importlib.util.find_spec("numpy") is not None
    and importlib.util.find_spec("PIL") is not None
)


@unittest.skipUnless(HAS_NUMPY_PIL, "requires NumPy and Pillow for pixelwise teacher agreement")
class PseudoAgreementTests(unittest.TestCase):
    def test_only_flip_agreed_confident_objects_on_unlabeled_pixels_survive(self) -> None:
        import numpy as np

        original = np.full((2, 3, 7), 0.01, dtype=np.float32)
        original[..., 4] = 0.94
        flipped_prediction = np.flip(original, axis=1).copy()
        ground_truth = np.full((2, 3), 255, dtype=np.uint8)
        ground_truth[0, 0] = 2
        content_valid = np.ones((2, 3), dtype=np.bool_)
        content_valid[1, 0] = False
        labels, confidence = select_agreed_object_pixels(
            original, flipped_prediction, ground_truth, content_valid
        )

        accepted = (ground_truth == 255) & content_valid
        self.assertTrue(np.all(labels[accepted] == 4))
        self.assertEqual(labels[0, 0], 255)
        self.assertEqual(labels[1, 0], 255)
        self.assertTrue(np.all(confidence[accepted] >= CONFIDENCE_THRESHOLD))
        self.assertEqual(float(confidence[0, 0]), 0.0)
        self.assertEqual(float(confidence[1, 0]), 0.0)

    def test_disagreement_and_non_object_classes_are_ignored(self) -> None:
        import numpy as np

        original = np.full((1, 3, 7), 0.01, dtype=np.float32)
        original[..., 4] = 0.94
        reflected = np.flip(original, axis=1).copy()
        reflected[0, 1] = 0.01
        reflected[0, 1, 5] = 0.94
        original[0, 2] = 0.01
        original[0, 2, 0] = 0.94
        reflected[0, 0] = 0.01
        reflected[0, 0, 0] = 0.94
        labels, confidence = select_agreed_object_pixels(
            original,
            reflected,
            np.full((1, 3), 255, dtype=np.uint8),
            np.ones((1, 3), dtype=np.bool_),
        )

        self.assertEqual(labels[0, 0], 4)
        self.assertEqual(labels[0, 1], 255)
        self.assertEqual(labels[0, 2], 255)
        self.assertEqual(confidence[0, 1], 0.0)

    def test_malformed_probability_normalization_is_rejected(self) -> None:
        import numpy as np

        invalid = np.full((1, 1, 7), 0.05, dtype=np.float32)
        invalid[..., 4] = 0.5
        with self.assertRaises(PseudoLabelError):
            select_agreed_object_pixels(
                invalid,
                invalid,
                np.full((1, 1), 255, dtype=np.uint8),
                np.ones((1, 1), dtype=np.bool_),
            )


class PseudoContractTests(unittest.TestCase):
    def test_thresholds_and_class_scope_are_fixed(self) -> None:
        self.assertEqual(CONFIDENCE_THRESHOLD, 0.90)
        self.assertEqual(MARGIN_THRESHOLD, 0.20)
        self.assertEqual(PSEUDO_WEIGHT, 0.25)
        self.assertEqual(OBJECT_CLASSES, frozenset((4, 5, 6)))

    @unittest.skipUnless(HAS_NUMPY_PIL, "requires NumPy and Pillow")
    def test_pseudo_source_and_weight_arrays_mark_only_object_targets(self) -> None:
        import numpy as np

        source, weight = pseudo_supervision_arrays(
            np.asarray([[255, 4, 5, 6]], dtype=np.uint8)
        )
        self.assertEqual(source.tolist(), [[0, 2, 2, 2]])
        self.assertEqual(weight.tolist(), [[0.0, 0.25, 0.25, 0.25]])
        with self.assertRaises(PseudoLabelError):
            pseudo_supervision_arrays(np.asarray([[3]], dtype=np.uint8))

    def test_partial_manifest_selects_only_converted_surface_train_records(self) -> None:
        converted = {
            "id": "train-surface",
            "source": "surface",
            "split": "train",
            "image_tensor_path": "image.png",
            "semantic_path": "semantic.png",
        }
        unconverted = {
            "id": "unconverted-surface",
            "source": "surface",
            "split": "train",
            "image_tensor_path": None,
            "semantic_path": None,
        }
        test = {
            "id": "test-surface",
            "source": "surface",
            "split": "test",
            "image_tensor_path": "test.png",
            "semantic_path": "test.semantic.png",
        }

        selected, skipped = select_converted_surface_training_records(
            [converted, unconverted, test]
        )

        self.assertEqual(selected, [converted])
        self.assertEqual(skipped, 1)
        with self.assertRaises(PseudoLabelError):
            select_converted_surface_training_records(
                [{**converted, "semantic_path": None}]
            )

    def test_existing_output_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "existing"
            output.mkdir()
            marker = output / "keep.txt"
            marker.write_text("existing bounded output", encoding="utf-8")
            with self.assertRaises(PseudoLabelError):
                generate_pseudo_labels(
                    Path("unused-manifest.json"),
                    Path("unused-model.tflite"),
                    output,
                )
            self.assertEqual(marker.read_text(encoding="utf-8"), "existing bounded output")

    def test_worker_count_is_bounded(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaises(PseudoLabelError):
                generate_pseudo_labels(
                    Path("unused-manifest.json"),
                    Path("unused-model.tflite"),
                    Path(temporary_directory) / "output",
                    workers=MAX_WORKERS + 1,
                )

    @unittest.skipUnless(HAS_NUMPY_PIL, "requires NumPy and Pillow")
    def test_bounded_parallel_inference_matches_serial_and_ignores_padding(self) -> None:
        import numpy as np
        from PIL import Image

        from tools.vision.training.data import OUT_H, OUT_W

        class FakeInterpreter:
            def __init__(self) -> None:
                self.owner = threading.get_ident()
                self.image = None
                self.output = None
                self.input_details = [
                    {
                        "index": 0,
                        "shape": (1, OUT_H, OUT_W, 3),
                        "dtype": np.float32,
                    }
                ]
                self.output_details = [
                    {
                        "index": 1,
                        "shape": (1, OUT_H, OUT_W, 7),
                        "dtype": np.float32,
                    }
                ]

            def get_input_details(self):
                return self.input_details

            def get_output_details(self):
                return self.output_details

            def set_tensor(self, _index, value) -> None:
                self.assert_owner()
                self.image = value

            def invoke(self) -> None:
                self.assert_owner()
                time.sleep(0.02)
                class_id = 4 if self.image.mean() > 0.5 else 0
                self.output = np.full((1, OUT_H, OUT_W, 7), 0.01, dtype=np.float32)
                self.output[..., class_id] = 0.94

            def get_tensor(self, _index):
                self.assert_owner()
                return self.output

            def assert_owner(self) -> None:
                if threading.get_ident() != self.owner:
                    raise AssertionError("An interpreter was shared across worker threads.")

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            model_path = root / "teacher.tflite"
            model_path.write_bytes(b"frozen-teacher")
            records = []
            for record_id, color in (("sample-b", 0), ("sample-a", 255)):
                source_path = root / f"{record_id}.source.png"
                tensor_path = root / f"{record_id}.tensor.png"
                semantic_path = root / f"{record_id}.semantic.png"
                Image.new("RGB", (512, 256), (color, color, color)).save(source_path)
                Image.new("RGB", (OUT_W, OUT_H), (color, color, color)).save(tensor_path)
                Image.new("L", (OUT_W, OUT_H), 255).save(semantic_path)
                records.append(
                    {
                        "id": record_id,
                        "source": "surface",
                        "split": "train",
                        "group_id": f"group-{record_id}",
                        "image_path": str(source_path),
                        "image_sha256": record_id,
                        "image_tensor_path": tensor_path.name,
                        "semantic_path": semantic_path.name,
                    }
                )
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps({"schema_version": 1, "records": records}),
                encoding="utf-8",
            )
            serial_threads = set()

            def serial_factory(_path: Path):
                serial_threads.add(threading.get_ident())
                return FakeInterpreter()

            serial_output = root / "serial"
            parallel_output = root / "parallel"
            serial_report = generate_pseudo_labels(
                manifest_path,
                model_path,
                serial_output,
                workers=1,
                limit=2,
                progress_interval=100,
                interpreter_factory=serial_factory,
            )
            parallel_lock = threading.Lock()
            parallel_threads = set()

            def parallel_factory(_path: Path):
                with parallel_lock:
                    parallel_threads.add(threading.get_ident())
                return FakeInterpreter()

            parallel_report = generate_pseudo_labels(
                manifest_path,
                model_path,
                parallel_output,
                workers=2,
                limit=2,
                progress_interval=100,
                interpreter_factory=parallel_factory,
            )

            self.assertEqual(len(serial_threads), 1)
            self.assertEqual(len(parallel_threads), 2)
            self.assertEqual(
                {key: value for key, value in serial_report.items() if key != "workers"},
                {key: value for key, value in parallel_report.items() if key != "workers"},
            )
            self.assertEqual(serial_report["status"], "partial")
            self.assertFalse(serial_report["complete"])
            self.assertEqual(serial_report["records_processed"], 2)
            self.assertEqual(serial_report["records_without_pseudo_labels"], 1)
            self.assertEqual(serial_report["records_with_pseudo_labels"], 1)
            self.assertEqual(
                serial_report["accepted_pixels_by_class"]["person"],
                288 * 144,
            )
            for record_id in ("sample-a",):
                semantic_name = f"{record_id}.pseudo.png"
                with Image.open(serial_output / "labels" / semantic_name) as serial_image:
                    serial_labels = np.asarray(serial_image)
                with Image.open(parallel_output / "labels" / semantic_name) as parallel_image:
                    parallel_labels = np.asarray(parallel_image)
                np.testing.assert_array_equal(serial_labels, parallel_labels)
                self.assertTrue(np.all(serial_labels[:184] == 255))
                self.assertTrue(np.all(serial_labels[328:] == 255))
                weights_name = f"{record_id}.pseudo.npz"
                with np.load(serial_output / "weights" / weights_name) as serial_weights:
                    serial_arrays = {key: serial_weights[key] for key in serial_weights.files}
                with np.load(parallel_output / "weights" / weights_name) as parallel_weights:
                    parallel_arrays = {key: parallel_weights[key] for key in parallel_weights.files}
                for key in serial_arrays:
                    np.testing.assert_array_equal(serial_arrays[key], parallel_arrays[key])
            serial_manifest = json.loads(
                (serial_output / "pseudo_manifest.json").read_text(encoding="utf-8")
            )
            parallel_manifest = json.loads(
                (parallel_output / "pseudo_manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(serial_manifest, parallel_manifest)
            self.assertEqual(serial_manifest["records"][0]["id"], "sample-a")

    @unittest.skipUnless(HAS_NUMPY_PIL, "requires NumPy and Pillow")
    def test_interpreter_tensor_contract_is_validated(self) -> None:
        import numpy as np

        class WrongShapeInterpreter:
            def get_input_details(self):
                return [
                    {"shape": (1, 2, 3, 3), "dtype": np.float32}
                ]

            def get_output_details(self):
                return [
                    {"shape": (1, 2, 3, 7), "dtype": np.float32}
                ]

        with self.assertRaises(PseudoLabelError):
            _validate_interpreter(WrongShapeInterpreter())


if __name__ == "__main__":
    unittest.main()
