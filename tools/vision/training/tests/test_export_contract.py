"""Fast fail-closed regression tests for exporter contracts."""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

try:
    import numpy as np
except ImportError:
    np = None

from tools.vision.training import export as exporter
from tools.vision.training.model import (
    IMAGE_CHANNELS,
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    NUM_HAZARD_CLASSES,
    NUM_SEMANTIC_CLASSES,
)


class FakeInterpreter:
    def __init__(self, output_details=None, output_values=None, input_details=None):
        self.input_details = input_details or [
            {
                "index": 0,
                "name": "serving_default_input",
                "shape": np.array([1, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_CHANNELS]),
                "dtype": np.float32,
            }
        ]
        self.output_details = output_details or [
            {
                "index": 1,
                "name": "semantic",
                "shape": np.array([1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_SEMANTIC_CLASSES]),
                "dtype": np.float32,
            }
        ]
        self.output_values = output_values or {}

    def allocate_tensors(self):
        pass

    def get_input_details(self):
        return self.input_details

    def get_output_details(self):
        return self.output_details

    def set_tensor(self, index, value):
        self.input = value

    def invoke(self):
        pass

    def get_tensor(self, index):
        return self.output_values[index]


@unittest.skipIf(np is None, "NumPy is required for exporter tensor contracts.")
class TestExportManifestFailClosed(unittest.TestCase):
    def _manifest(self, report=None, parity_report=None):
        model_bytes = b"tiny-test-model"
        interpreter = FakeInterpreter()
        fake_tf = SimpleNamespace(
            lite=SimpleNamespace(Interpreter=lambda **_: interpreter)
        )
        with tempfile.TemporaryDirectory() as tmp:
            report_path = None
            if report is not None:
                report_path = Path(tmp) / "evaluation.json"
                if isinstance(report, bytes):
                    report_path.write_bytes(report)
                else:
                    report_path.write_text(json.dumps(report), encoding="utf-8")
            with mock.patch.object(exporter, "tf", fake_tf):
                manifest = exporter.generate_v2_manifest(
                    model_path=Path("model.tflite"),
                    architecture="model_a",
                    precision="fp32",
                    tflite_bytes=model_bytes,
                    hazard_thresholds_path=report_path,
                    parity_report=parity_report,
                )
        return manifest, model_bytes

    def test_external_flags_never_enable_automatic_warnings(self):
        digest = hashlib.sha256(b"tiny-test-model").hexdigest()
        manifest, _ = self._manifest(
            {
                "automatic_warning_enablement": True,
                "warning_level_evidence_contract": {"supported": True},
                "model_sha256": digest,
                "candidate_selected": {"person": 0.75},
                "thresholds": {"person": 0.75},
            }
        )
        hazard = manifest["hazard_thresholds"]
        self.assertFalse(hazard["enabled"])
        self.assertFalse(hazard["automatic_warning_enablement"])
        self.assertTrue(hazard["diagnostic_only"])
        self.assertEqual(hazard["thresholds"], {"person": 0.75})

    def test_legacy_enabled_pixel_thresholds_remain_diagnostic(self):
        manifest, _ = self._manifest(
            {
                "enabled": True,
                "thresholds": {"person": 0.8},
                "candidate_selected": {"person": 0.8},
            }
        )
        hazard = manifest["hazard_thresholds"]
        self.assertFalse(hazard["enabled"])
        self.assertEqual(hazard["thresholds"], {"person": 0.8})
        self.assertIn("warning-event evidence is unsupported", hazard["caveat"])

    def test_missing_model_hash_is_diagnostic_only(self):
        manifest, _ = self._manifest(
            {
                "automatic_warning_enablement": True,
                "warning_level_evidence_contract": {"supported": True},
                "thresholds": {"person": 0.75},
            }
        )
        hazard = manifest["hazard_thresholds"]
        self.assertFalse(hazard["enabled"])
        self.assertEqual(hazard["warning_enablement_reason"], "warning_level_evidence_not_supported")
        self.assertEqual(hazard["thresholds"], {"person": 0.75})

    def test_mismatched_model_hash_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not match exported model"):
            self._manifest({"model_sha256": "0" * 64, "thresholds": {"person": 0.7}})

    def test_malformed_evaluation_reports_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Failed to process"):
            self._manifest(b'{"automatic_warning_enablement":')
        with self.assertRaisesRegex(ValueError, "JSON object"):
            self._manifest(["not", "an", "object"])

    def test_missing_threshold_report_is_an_error(self):
        interpreter = FakeInterpreter()
        fake_tf = SimpleNamespace(lite=SimpleNamespace(Interpreter=lambda **_: interpreter))
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(exporter, "tf", fake_tf):
            with self.assertRaises(FileNotFoundError):
                exporter.generate_v2_manifest(
                    Path("model.tflite"), "model_a", "fp32", b"tiny",
                    hazard_thresholds_path=Path(tmp) / "missing.json",
                )

    def test_direct_manifest_generation_records_skipped_parity(self):
        manifest, _ = self._manifest()
        self.assertEqual(manifest["parity"]["status"], "skipped")
        self.assertIsNone(manifest["parity"]["report"])
        self.assertIn("was not verified", manifest["device_caveat"])


@unittest.skipIf(np is None, "NumPy is required for exporter tensor contracts.")
class TestExportParityContract(unittest.TestCase):
    def test_multi_output_parity_matches_channel_signatures_not_order(self):
        semantic = np.zeros((1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_SEMANTIC_CLASSES), dtype=np.float32)
        hazard = np.zeros((1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES), dtype=np.float32)
        interpreter = FakeInterpreter(
            output_details=[
                {
                    "index": 2,
                    "name": "hazard",
                    "shape": np.array(hazard.shape),
                    "dtype": np.float32,
                },
                {
                    "index": 1,
                    "name": "semantic",
                    "shape": np.array(semantic.shape),
                    "dtype": np.float32,
                },
            ],
            output_values={2: hazard, 1: semantic},
        )
        fake_tf = SimpleNamespace(lite=SimpleNamespace(Interpreter=lambda **_: interpreter))

        class FakeKerasModel:
            def __call__(self, inputs, training=False):
                return [semantic, hazard]

        with mock.patch.object(exporter, "tf", fake_tf):
            report = exporter.verify_keras_tflite_parity(
                FakeKerasModel(), b"tiny", precision="fp32", sample_count=1
            )
        self.assertTrue(report["passed"])
        self.assertEqual([item["shape"][-1] for item in report["outputs"]], [
            NUM_SEMANTIC_CLASSES,
            NUM_HAZARD_CLASSES,
        ])

    def test_parity_requires_positive_samples_and_supported_precision(self):
        with mock.patch.object(exporter, "_check_runtime_tf"):
            with self.assertRaisesRegex(ValueError, "positive integer"):
                exporter.verify_keras_tflite_parity(None, b"tiny", sample_count=0)
            with self.assertRaisesRegex(ValueError, "Unsupported precision"):
                exporter.verify_keras_tflite_parity(None, b"tiny", precision="int8")

    def test_parity_rejects_uncontracted_output_shape(self):
        interpreter = FakeInterpreter(
            output_details=[
                {
                    "index": 1,
                    "name": "unexpected",
                    "shape": np.array([1, IMAGE_HEIGHT, IMAGE_WIDTH, 6]),
                    "dtype": np.float32,
                }
            ]
        )
        fake_tf = SimpleNamespace(lite=SimpleNamespace(Interpreter=lambda **_: interpreter))
        with mock.patch.object(exporter, "tf", fake_tf):
            with self.assertRaisesRegex(ValueError, "Unsupported TFLite output tensor shape"):
                exporter.verify_keras_tflite_parity(None, b"tiny")


class TestCheckpointArchitectureContract(unittest.TestCase):
    class FakeModel:
        def load_weights(self, path):
            self.weights_path = path

    def _checkpoint(self, directory, metadata=None):
        checkpoint = Path(directory)
        (checkpoint / "model_weights.weights.h5").write_bytes(b"fixture")
        if metadata is not None:
            (checkpoint / "metadata.json").write_text(metadata, encoding="utf-8")
        return checkpoint

    def test_malformed_metadata_fails_without_architecture_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkpoint = self._checkpoint(tmp, "{malformed")
            with mock.patch.object(exporter, "_check_runtime_tf"):
                with self.assertRaisesRegex(ValueError, "Could not parse checkpoint metadata"):
                    exporter.load_trained_model_from_checkpoint(checkpoint)

    def test_missing_metadata_requires_explicit_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkpoint = self._checkpoint(tmp)
            with mock.patch.object(exporter, "_check_runtime_tf"), mock.patch.object(
                exporter, "build_model_a", return_value=self.FakeModel()
            ):
                with self.assertRaisesRegex(ValueError, "pass --architecture explicitly"):
                    exporter.load_trained_model_from_checkpoint(checkpoint)
                model, metadata = exporter.load_trained_model_from_checkpoint(checkpoint, "model_a")
        self.assertEqual(metadata, {})
        self.assertTrue(model.weights_path.endswith("model_weights.weights.h5"))

    def test_architecture_override_cannot_conflict_with_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkpoint = self._checkpoint(tmp, '{"architecture":"model_b"}')
            with mock.patch.object(exporter, "_check_runtime_tf"):
                with self.assertRaisesRegex(ValueError, "conflicts with checkpoint metadata"):
                    exporter.load_trained_model_from_checkpoint(checkpoint, "model_a")


@unittest.skipIf(np is None, "NumPy is required for exporter tensor contracts.")
class TestExportPublicationSafety(unittest.TestCase):
    def test_parity_failure_preserves_existing_model_and_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "model.tflite"
            manifest_path = Path(tmp) / "model_manifest.json"
            output_path.write_bytes(b"existing-model")
            manifest_path.write_bytes(b"existing-manifest")
            converter = SimpleNamespace(convert=lambda: b"new-model")
            fake_tf = SimpleNamespace(
                lite=SimpleNamespace(
                    TFLiteConverter=SimpleNamespace(
                        from_keras_model=lambda model: converter
                    )
                )
            )
            with (
                mock.patch.object(exporter, "tf", fake_tf),
                mock.patch.object(exporter, "_check_runtime_tf"),
                mock.patch.object(exporter, "load_trained_model_from_checkpoint", return_value=(object(), {"architecture": "model_a"})),
                mock.patch.object(exporter, "build_export_model", return_value=object()),
                mock.patch.object(exporter, "verify_keras_tflite_parity", side_effect=ValueError("forced parity failure")),
            ):
                with self.assertRaisesRegex(ValueError, "forced parity failure"):
                    exporter.export_model(Path("checkpoint"), output_path)

            self.assertEqual(output_path.read_bytes(), b"existing-model")
            self.assertEqual(manifest_path.read_bytes(), b"existing-manifest")
            self.assertEqual(list(Path(tmp).glob(".*.tmp")), [])
            self.assertEqual(list(Path(tmp).glob(".*.bak")), [])

    def test_skipped_parity_is_recorded_when_verification_is_disabled(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "model.tflite"
            interpreter = FakeInterpreter()
            converter = SimpleNamespace(convert=lambda: b"new-model")
            fake_tf = SimpleNamespace(
                lite=SimpleNamespace(
                    TFLiteConverter=SimpleNamespace(from_keras_model=lambda model: converter),
                    Interpreter=lambda **_: interpreter,
                )
            )
            with (
                mock.patch.object(exporter, "tf", fake_tf),
                mock.patch.object(exporter, "_check_runtime_tf"),
                mock.patch.object(exporter, "load_trained_model_from_checkpoint", return_value=(object(), {"architecture": "model_a"})),
                mock.patch.object(exporter, "build_export_model", return_value=object()),
            ):
                exporter.export_model(
                    Path("checkpoint"),
                    output_path,
                    verify_parity=False,
                )

            manifest = json.loads((Path(tmp) / "model_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["parity"]["status"], "skipped")
            self.assertIsNone(manifest["parity"]["report"])
            self.assertIn("was not verified", manifest["device_caveat"])


if __name__ == "__main__":
    unittest.main()
