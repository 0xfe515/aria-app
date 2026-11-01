from __future__ import annotations

import gzip
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

from tools.vision.training import predict
from tools.vision.training.data import HAZARD_NAMES, OUT_H, OUT_W


class FakeInterpreter:
    def __init__(self, *, input_dtype=np.float32, input_quant=(0.0, 0), output_shape=None):
        self.input_dtype = input_dtype
        self.input_quant = input_quant
        self.output_shape = output_shape or (1, OUT_H, OUT_W, 7)
        self.inputs = []

    def get_input_details(self):
        return [{"index": 0, "shape": [1, OUT_H, OUT_W, 3], "dtype": self.input_dtype,
                 "quantization": self.input_quant}]

    def get_output_details(self):
        return [{"index": 1, "shape": self.output_shape, "dtype": np.float32,
                 "quantization": (0.0, 0)}]

    def set_tensor(self, index, value):
        self.inputs.append(value.copy())

    def invoke(self):
        pass

    def get_tensor(self, index):
        result = np.zeros(self.output_shape, dtype=np.float32)
        result[..., 3] = 2.0
        return result


def sample():
    return {"id": "record-1", "source": "surface", "group_id": "group-1", "split": "val",
            "image": np.full((OUT_H, OUT_W, 3), 255, dtype=np.float32),
            "semantic": np.full((OUT_H, OUT_W), 3, dtype=np.int32),
            "semantic_source": np.ones((OUT_H, OUT_W), dtype=np.uint8),
            "semantic_weight": np.ones((OUT_H, OUT_W), dtype=np.float32),
            "hazard": np.zeros((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.float32),
            "hazard_valid": np.ones((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.float32)}


class FakeDataset:
    def __init__(self, manifest, split, *, seed, require_complete):
        self.split = split

    def iter_samples(self, *, augment, balanced):
        assert not augment and not balanced
        yield sample()


class PredictTests(unittest.TestCase):
    def _paths(self, root):
        manifest, model, output = root / "manifest.json", root / "model.tflite", root / "pred.jsonl.gz"
        manifest.write_text("{}", encoding="utf-8")
        model.write_bytes(b"fake model")
        return manifest, model, output

    def test_raw_and_quantized_inputs_follow_float_range_and_tensor_metadata(self):
        raw = FakeInterpreter()
        predict._validate_interpreter(raw)
        self.assertEqual(float(predict._model_input(sample()["image"], raw.get_input_details()[0]).max()), 1.0)
        quant = FakeInterpreter(input_dtype=np.uint8, input_quant=(1 / 255, 0))
        tensor = predict._model_input(sample()["image"], quant.get_input_details()[0])
        self.assertEqual(tensor.dtype, np.uint8)
        self.assertEqual(int(tensor.max()), 255)

    def test_rejects_bad_output_shape_and_maps_class_ids_unchanged(self):
        with self.assertRaisesRegex(predict.PredictionError, "Semantic output"):
            predict._validate_interpreter(FakeInterpreter(output_shape=(1, OUT_H, OUT_W, 8)))
        model = FakeInterpreter()
        input_detail, output_detail = predict._validate_interpreter(model)
        record = predict._record(sample(), model, input_detail, output_detail, "validation", "a" * 64)
        self.assertEqual(set(record["semantic_prediction"]), {3})
        self.assertEqual(record["semantic_truth"][0], 3)

    def test_streams_gzip_contract_and_marks_bounded_mode_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, model, output = self._paths(root)
            fake = FakeInterpreter()
            with patch.object(predict, "PortraitDataset", FakeDataset):
                result = predict.generate_predictions(manifest, model, "validation", output,
                    limit=1, interpreter_factory=lambda _path, _threads: fake)
            with gzip.open(output, "rt", encoding="utf-8") as stream:
                record = json.loads(next(stream))
            self.assertTrue(result["diagnostic_only"])
            self.assertFalse(result["acceptance_eligible"])
            self.assertEqual(record["domain"], "surface")
            self.assertEqual(len(record["risk_truth"]), OUT_H * OUT_W * len(HAZARD_NAMES))
            self.assertEqual(set(record["risk_prediction"]), {0.0})
            self.assertFalse(record["risk_prediction_supported"])

    def test_missing_test_thresholds_and_bad_validation_metadata_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, model, output = self._paths(root)
            with self.assertRaisesRegex(predict.PredictionError, "require a frozen"):
                predict.generate_predictions(manifest, model, "test", output)
            report = root / "report.json"
            report.write_text(json.dumps({"split": "test", "model_kind": "candidate"}), encoding="utf-8")
            with self.assertRaisesRegex(predict.PredictionError, "candidate validation"):
                predict.generate_predictions(manifest, model, "test", output, thresholds_from=report)

    def test_missing_semantic_labels_fail(self):
        bad = sample()
        bad["semantic"] = np.zeros((1, 1), dtype=np.int32)
        interpreter = FakeInterpreter()
        inputs, outputs = predict._validate_interpreter(interpreter)
        with self.assertRaisesRegex(predict.PredictionError, "missing or malformed semantic"):
            predict._record(bad, interpreter, inputs, outputs, "validation", "a" * 64)

    def test_threshold_file_must_be_candidate_validation_with_frozen_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, model, output = self._paths(root)
            report = root / "report.json"
            report.write_text(json.dumps({"schema_version": 1,
                "report_kind": "saved_prediction_evaluation", "split": "validation",
                "model_kind": "candidate", "risk_threshold_selection": {},
                "model_sha256": "b" * 64}), encoding="utf-8")
            with self.assertRaisesRegex(predict.PredictionError, "match this model SHA-256"):
                predict.generate_predictions(manifest, model, "test", output, thresholds_from=report)

    def test_test_predictions_accept_only_matching_frozen_validation_thresholds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, model, output = self._paths(root)
            report = root / "report.json"
            report.write_text(json.dumps({
                "schema_version": 1, "report_kind": "saved_prediction_evaluation",
                "split": "validation", "model_kind": "candidate",
                "model_sha256": predict._sha256(model),
                "evaluation_fingerprint": "a" * 64,
                "split_membership": {"record_keys": ["surface:validation-id"]},
                "risk_threshold_selection": {name: {"candidate_selected": False}
                                              for name in HAZARD_NAMES},
            }), encoding="utf-8")
            with patch.object(predict, "PortraitDataset", FakeDataset):
                result = predict.generate_predictions(manifest, model, "test", output,
                    thresholds_from=report, interpreter_factory=lambda _path, _threads: FakeInterpreter())
            self.assertTrue(result["acceptance_eligible"])


if __name__ == "__main__":
    unittest.main()
