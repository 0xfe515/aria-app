"""Unit tests for native portrait vision model export and verification contracts.

Task ID: ARIA-PORTRAIT-RUNNER-001
Tests:
- Export CLI argument parsing, precision options, and defaults.
- Missing checkpoint error handling (strict refusal of silent fallbacks).
- Multi-output v2 manifest schema and field compliance.
- TensorFlow execution (when TF present):
  - Model A and Model B export model construction.
  - TFLite conversion (FP32 and FP16).
  - Numeric Keras vs TFLite interpreter parity verification.
  - End-to-end export smoke execution.
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

# Restrict default threading
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

# Ensure repository root is on sys.path
_REPO_ROOT = Path(__file__).resolve().parents[4]
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

from tools.vision.training.export import (
    build_parser,
    generate_v2_manifest,
    load_trained_model_from_checkpoint,
    run_export_smoke,
    verify_keras_tflite_parity,
)
from tools.vision.training.model import (
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    NUM_HAZARD_CLASSES,
    NUM_SEMANTIC_CLASSES,
    build_export_model,
    build_model_a,
    build_model_b,
)


class TestExportCLIAndContracts(unittest.TestCase):
    """Verifies export CLI parsing and contract enforcement."""

    def test_cli_parser_defaults(self):
        parser = build_parser()
        args = parser.parse_args(["--smoke"])
        self.assertTrue(args.smoke)
        self.assertEqual(args.precision, "fp32")
        self.assertTrue(args.verify_parity)
        self.assertEqual(args.device, "cpu")

    def test_missing_checkpoint_raises_error(self):
        non_existent = Path("non/existent/checkpoint/dir")
        with self.assertRaises(FileNotFoundError):
            load_trained_model_from_checkpoint(non_existent)


class TestV2ManifestSchema(unittest.TestCase):
    """Verifies that generated v2 manifests conform to the multi-output schema specification."""

    def setUp(self):
        if not HAS_TF or not HAS_NUMPY:
            self.skipTest("TensorFlow and NumPy required for manifest tests.")

    def test_manifest_schema_model_a(self):
        model_a = build_model_a(weights=None, freeze_bn=False, input_range="0_255")
        exp_a = build_export_model(model_a, batch_size=1)
        converter = tf.lite.TFLiteConverter.from_keras_model(exp_a)
        tflite_bytes = converter.convert()

        dummy_path = Path("walkable_seg_portrait.tflite")
        manifest = generate_v2_manifest(
            model_path=dummy_path,
            architecture="model_a",
            precision="fp32",
            tflite_bytes=tflite_bytes,
        )

        self.assertEqual(manifest["schema_version"], 2)
        self.assertEqual(manifest["architecture"], "model_a")
        self.assertEqual(manifest["precision"], "fp32")
        self.assertIn("model_sha256", manifest)

        # Input contract
        inp = manifest["input"]
        self.assertEqual(inp["shape"], [1, IMAGE_HEIGHT, IMAGE_WIDTH, 3])
        self.assertEqual(inp["range"], [0.0, 1.0])
        self.assertEqual(inp["normalization"], "internal_rescaling_2x_minus_1")

        # Outputs contract
        self.assertEqual(len(manifest["outputs"]), 1)
        out0 = manifest["outputs"][0]
        self.assertEqual(out0["shape"], [1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_SEMANTIC_CLASSES])
        self.assertEqual(out0["role"], "semantic_segmentation")
        self.assertEqual(len(out0["classes"]), 7)

        # Hazard thresholds contract: disabled by default
        haz = manifest["hazard_thresholds"]
        self.assertFalse(haz["enabled"])
        self.assertIsNone(haz["thresholds"])
        self.assertIn("caveat", haz)

    def test_manifest_schema_model_b(self):
        model_b = build_model_b(weights=None, freeze_bn=False, input_range="0_255")
        exp_b = build_export_model(model_b, batch_size=1)
        converter = tf.lite.TFLiteConverter.from_keras_model(exp_b)
        tflite_bytes = converter.convert()

        dummy_path = Path("walkable_seg_hazard_portrait.tflite")
        manifest = generate_v2_manifest(
            model_path=dummy_path,
            architecture="model_b",
            precision="fp16",
            tflite_bytes=tflite_bytes,
        )

        self.assertEqual(manifest["schema_version"], 2)
        self.assertEqual(manifest["architecture"], "model_b")
        self.assertEqual(len(manifest["outputs"]), 2)

        sem_out = next(o for o in manifest["outputs"] if o["role"] == "semantic_segmentation")
        haz_out = next(o for o in manifest["outputs"] if o["role"] == "hazard_estimation")

        self.assertEqual(sem_out["shape"], [1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_SEMANTIC_CLASSES])
        self.assertEqual(haz_out["shape"], [1, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES])
        self.assertEqual(len(haz_out["classes"]), 8)


class TestExportTensorFlowExecution(unittest.TestCase):
    """Verifies numeric parity verification and export smoke execution."""

    def setUp(self):
        if not HAS_TF or not HAS_NUMPY:
            self.skipTest("TensorFlow and NumPy required for export execution tests.")

    def test_parity_verification_fp32(self):
        model_a = build_model_a(weights=None, freeze_bn=False, input_range="0_255")
        exp_a = build_export_model(model_a, batch_size=1)
        converter = tf.lite.TFLiteConverter.from_keras_model(exp_a)
        tflite_bytes = converter.convert()

        parity = verify_keras_tflite_parity(exp_a, tflite_bytes, precision="fp32", sample_count=1)
        self.assertTrue(parity["passed"])
        self.assertLessEqual(parity["outputs"][0]["max_diff"], 1e-4)

    def test_run_export_smoke_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            report = run_export_smoke(Path(tmp_dir))
            self.assertEqual(report["status"], "SUCCEEDED")
            self.assertTrue(report["model_a_parity"])
            self.assertTrue(report["model_b_parity"])
            self.assertGreater(report["model_a_bytes"], 0)
            self.assertGreater(report["model_b_bytes"], 0)


if __name__ == "__main__":
    unittest.main()
