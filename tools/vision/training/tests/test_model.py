"""Unit tests for native portrait vision model architectures and contracts.

Task ID: ARIA-PORTRAIT-MODEL-001
Tests:
- Architectural constants and contract invariants (dimensions, class counts, order).
- Import safety and honest dependency skipping when TensorFlow is absent.
- Model A, B, C instantiation, layer names, output shapes, and tensor contracts (when TF present).
- BatchNormalization freezing and block13+ fine-tune policy enforcement (when TF present).
- Risk head attachment to pre-trained Model A (when TF present).
- Model C bilinear upsampling decoder verification (when TF present).
- Export model wrapper shape, single normalization, and numerical equivalence with 0..255 input (when TF present).
"""

import os
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

import sys
import unittest
from pathlib import Path

# Add project root to sys.path to enable direct test discovery
_REPO_ROOT = Path(__file__).resolve().parents[4]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.vision.training.model import (
    ENCODER_SKIP_NAMES,
    HAZARD_CLASSES,
    IMAGE_CHANNELS,
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    INPUT_SHAPE,
    NUM_HAZARD_CLASSES,
    NUM_SEMANTIC_CLASSES,
    SEMANTIC_CLASSES,
    WALKABLE_CLASSES,
    HAS_TF,
)


class TestModelContract(unittest.TestCase):
    """Verifies that model contracts and constants adhere to immutable specifications."""

    def test_dimensions_and_shapes(self):
        self.assertEqual(IMAGE_HEIGHT, 512)
        self.assertEqual(IMAGE_WIDTH, 288)
        self.assertEqual(IMAGE_CHANNELS, 3)
        self.assertEqual(INPUT_SHAPE, (512, 288, 3))

    def test_semantic_classes(self):
        self.assertEqual(NUM_SEMANTIC_CLASSES, 7)
        self.assertEqual(len(SEMANTIC_CLASSES), 7)
        expected_classes = [
            "other", "road", "sidewalk", "crosswalk",
            "person", "vehicle", "pole_sign"
        ]
        self.assertEqual(SEMANTIC_CLASSES, expected_classes)
        self.assertEqual(WALKABLE_CLASSES, [2, 3])

    def test_hazard_classes(self):
        self.assertEqual(NUM_HAZARD_CLASSES, 8)
        self.assertEqual(len(HAZARD_CLASSES), 8)
        expected_hazards = [
            "bike_lane", "damaged_surface", "stairs", "manhole",
            "tree_zone", "grating", "repair_zone", "speed_bump"
        ]
        self.assertEqual(HAZARD_CLASSES, expected_hazards)

    def test_encoder_skip_names(self):
        self.assertEqual(len(ENCODER_SKIP_NAMES), 5)
        self.assertEqual(
            ENCODER_SKIP_NAMES,
            [
                "block_1_expand_relu",
                "block_3_expand_relu",
                "block_6_expand_relu",
                "block_13_expand_relu",
                "block_16_project",
            ],
        )

    def test_import_safety(self):
        """Module must import cleanly in any environment without raising UnboundLocal or syntax errors."""
        import tools.vision.training.model as m
        self.assertIsInstance(m.HAS_TF, bool)


class TestModelTensorFlowExecution(unittest.TestCase):
    """Verifies Keras graph construction, shapes, and layer policies when TensorFlow is available."""

    def setUp(self):
        if not HAS_TF:
            self.skipTest("TensorFlow >= 2.19 is not installed in the current environment.")

    def tearDown(self):
        if HAS_TF:
            from tensorflow import keras
            keras.backend.clear_session()

    def test_build_model_a_graph_and_shapes(self):
        from tools.vision.training.model import build_model_a
        model_a = build_model_a(weights=None, freeze_bn=True, input_range="0_255")
        self.assertEqual(tuple(model_a.input_shape), (None, 512, 288, 3))
        self.assertEqual(tuple(model_a.output_shape), (None, 512, 288, 7))

        output_layer = model_a.layers[-1]
        self.assertEqual(output_layer.name, "semantic_output")

        feat_layer = model_a.get_layer("decoder_final_features")
        self.assertEqual(tuple(feat_layer.output.shape), (None, 512, 288, 16))

    def test_batch_normalization_freezing(self):
        from tools.vision.training.model import build_model_a, freeze_batch_normalization
        from tensorflow.keras import layers

        model = build_model_a(weights=None, freeze_bn=False)
        frozen_count = freeze_batch_normalization(model)
        self.assertGreater(frozen_count, 0)

        for layer in model.layers:
            if isinstance(layer, layers.BatchNormalization):
                self.assertFalse(layer.trainable)

    def test_fine_tune_policy_block13(self):
        from tools.vision.training.model import build_model_a, set_encoder_trainability

        model = build_model_a(weights=None, freeze_bn=True)

        trainable, non_trainable = set_encoder_trainability(model, policy="freeze_all")
        self.assertEqual(trainable, 0)
        self.assertGreater(non_trainable, 0)

        trainable, non_trainable = set_encoder_trainability(model, policy="block13_plus")
        self.assertGreater(trainable, 0)
        self.assertGreater(non_trainable, 0)

    def test_build_model_b_dual_heads(self):
        from tools.vision.training.model import build_model_b
        model_b = build_model_b(weights=None, freeze_bn=True)
        self.assertEqual(len(model_b.outputs), 2)
        self.assertEqual(tuple(model_b.outputs[0].shape), (None, 512, 288, 7))
        self.assertEqual(tuple(model_b.outputs[1].shape), (None, 512, 288, 8))

    def test_attach_risk_head_to_model_a(self):
        from tools.vision.training.model import attach_risk_head, build_model_a
        model_a = build_model_a(weights=None, freeze_bn=True)
        model_b = attach_risk_head(model_a)

        self.assertEqual(len(model_b.outputs), 2)
        self.assertEqual(tuple(model_b.outputs[0].shape), (None, 512, 288, 7))
        self.assertEqual(tuple(model_b.outputs[1].shape), (None, 512, 288, 8))

    def test_build_model_c_bilinear_lightweight(self):
        from tools.vision.training.model import build_model_c
        from tensorflow.keras import layers

        model_c = build_model_c(weights=None, freeze_bn=True, include_hazard_head=True)
        self.assertEqual(len(model_c.outputs), 2)
        self.assertEqual(tuple(model_c.outputs[0].shape), (None, 512, 288, 7))
        self.assertEqual(tuple(model_c.outputs[1].shape), (None, 512, 288, 8))

        upsample_layers = [l for l in model_c.layers if isinstance(l, layers.UpSampling2D)]
        self.assertGreaterEqual(len(upsample_layers), 4)
        for ul in upsample_layers:
            self.assertEqual(ul.interpolation, "bilinear")

    def test_build_export_model_single_norm_and_numerical_equivalence(self):
        import numpy as np
        import tensorflow as tf
        from tools.vision.training.model import build_export_model, build_model_a

        model_train = build_model_a(weights=None, input_range="0_255")
        model_export = build_export_model(model_train, batch_size=1)

        self.assertEqual(tuple(model_export.input_shape), (1, 512, 288, 3))
        self.assertEqual(tuple(model_export.output_shape), (1, 512, 288, 7))

        # Test numerical equivalence on synthetic [0, 1] input
        x_01 = np.random.uniform(0.0, 1.0, size=(1, 512, 288, 3)).astype("float32")
        x_255 = x_01 * 255.0

        out_train = model_train.predict(x_255, verbose=0)
        out_export = model_export.predict(x_01, verbose=0)

        max_diff = np.max(np.abs(out_train - out_export))
        self.assertLess(max_diff, 1e-4)

    def test_build_export_model_invalid_batch_size(self):
        from tools.vision.training.model import build_export_model, build_model_a
        model_train = build_model_a(weights=None, input_range="0_255")
        with self.assertRaises(ValueError):
            build_export_model(model_train, batch_size=0)
        with self.assertRaises(ValueError):
            build_export_model(model_train, batch_size=-1)


if __name__ == "__main__":
    unittest.main()
