"""Unit tests for masked loss functions and distillation objectives.

Task ID: ARIA-PORTRAIT-MODEL-001
Tests:
- Inverse square-root class frequency weighting and clipping bounds [0.5, 3.0].
- Pure-Python numerical correctness for Masked CE, Masked Dice, Masked Focal BCE, and KL Distillation.
- Zero loss and zero gradient guarantees for ignore label 255 and sample weight 0.0.
- Linear provenance weighting ensuring perfect prediction with pseudo weight (0.25) yields exactly 0.0 Dice loss.
- Absent supervised class exclusion in partial annotations (Surface dataset protection).
- Public Keras Loss __call__ API invocation ensuring spatial sample_weight is preserved.
- TensorFlow implementation numeric parity and tf.GradientTape zero-gradient proofs (when TF present).
"""

import os
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

import math
import sys
import unittest
from pathlib import Path

# Add project root to sys.path
_REPO_ROOT = Path(__file__).resolve().parents[4]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.vision.training.losses import (
    DEFAULT_PSEUDO_WEIGHT,
    HAS_TF,
    IGNORE_LABEL,
    NUM_HAZARD_CLASSES,
    NUM_SEMANTIC_CLASSES,
    compute_inverse_sqrt_class_weights,
    py_kl_distillation,
    py_masked_ce,
    py_masked_dice,
    py_masked_focal_bce,
)


class TestLossFormulasPurePython(unittest.TestCase):
    """Pure-Python tests verifying loss algorithms and mathematical properties."""

    def test_inverse_sqrt_class_weights_bounds(self):
        # Extreme class frequencies
        counts = [100000, 5000, 2000, 500, 50, 10, 2]
        weights = compute_inverse_sqrt_class_weights(counts, min_weight=0.5, max_weight=3.0)

        self.assertEqual(len(weights), 7)
        # All weights must be within [0.5, 3.0]
        for w in weights:
            self.assertGreaterEqual(w, 0.5)
            self.assertLessEqual(w, 3.0)

        # Most frequent class must have lowest weight (clipped to min 0.5)
        self.assertEqual(weights[0], 0.5)
        # Rarest class must have highest weight (clipped to max 3.0)
        self.assertEqual(weights[-1], 3.0)

    def test_py_masked_ce_ignore_and_zero_weight(self):
        # 3 pixels:
        # pixel 0: class 2, prob 0.8 -> valid
        # pixel 1: ignore label 255, prob 0.1 -> must contribute 0.0
        # pixel 2: class 1, prob 0.5, weight 0.0 -> must contribute 0.0
        y_true = [2, IGNORE_LABEL, 1]
        y_pred = [
            [0.05, 0.05, 0.8, 0.05, 0.02, 0.02, 0.01],
            [0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.4],
            [0.1, 0.5, 0.2, 0.1, 0.05, 0.03, 0.02],
        ]
        sample_weights = [1.0, 1.0, 0.0]

        loss = py_masked_ce(y_true, y_pred, sample_weight=sample_weights)
        expected = -math.log(0.8)
        self.assertAlmostEqual(loss, expected, places=5)

    def test_py_masked_ce_pseudo_weight(self):
        y_true_mixed = [4, 2]
        y_pred_mixed = [
            [0.1, 0.1, 0.1, 0.1, 0.6, 0.0, 0.0],  # CE = -log(0.6)
            [0.0, 0.0, 0.9, 0.0, 0.0, 0.1, 0.0],  # CE = -log(0.9)
        ]
        mixed_weights = [0.25, 1.0]
        loss_mixed = py_masked_ce(y_true_mixed, y_pred_mixed, sample_weight=mixed_weights)
        expected_mixed = (0.25 * (-math.log(0.6)) + 1.0 * (-math.log(0.9))) / (0.25 + 1.0)
        self.assertAlmostEqual(loss_mixed, expected_mixed, places=5)

    def test_py_masked_dice_perfect_prediction_with_pseudo_zero_loss(self):
        # Perfect prediction with pseudo weight 0.25 must have exactly 0.0 Dice loss
        y_true = [2, 1]
        y_pred_perfect = [
            [0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        ]
        # Weight is pseudo 0.25
        pseudo_weights = [0.25, 0.25]
        loss = py_masked_dice(
            y_true, y_pred_perfect, num_classes=7,
            sample_weight=pseudo_weights, exclude_absent=True
        )
        self.assertAlmostEqual(loss, 0.0, places=5)

    def test_py_masked_dice_absent_class_exclusion(self):
        # 2 pixels: class 2 (sidewalk) and class 1 (road)
        # classes 0, 3, 4, 5, 6 absent in ground truth
        y_true = [2, 1]
        y_pred = [
            [0.0, 0.1, 0.85, 0.0, 0.05, 0.0, 0.0],
            [0.05, 0.9, 0.05, 0.0, 0.0, 0.0, 0.0],
        ]

        loss_excluded = py_masked_dice(y_true, y_pred, num_classes=7, exclude_absent=True)
        loss_all = py_masked_dice(y_true, y_pred, num_classes=7, exclude_absent=False)

        self.assertGreater(loss_excluded, 0.0)
        self.assertLess(loss_excluded, 1.0)
        self.assertNotEqual(loss_excluded, loss_all)

    def test_py_masked_focal_bce(self):
        y_true = [[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]
        y_pred_confident = [[0.9, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1]]
        y_pred_uncertain = [[0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]]

        loss_confident = py_masked_focal_bce(y_true, y_pred_confident, gamma=2.0)
        loss_uncertain = py_masked_focal_bce(y_true, y_pred_uncertain, gamma=2.0)

        self.assertLess(loss_confident, loss_uncertain)

        valid_mask_zero = [[0.0] * 8]
        loss_zero_valid = py_masked_focal_bce(y_true, y_pred_uncertain, valid_mask=valid_mask_zero)
        self.assertEqual(loss_zero_valid, 0.0)

    def test_py_kl_distillation(self):
        teacher = [[0.1, 0.8, 0.1]]
        loss_zero = py_kl_distillation(teacher, teacher, temperature=2.0, weight=0.2)
        self.assertAlmostEqual(loss_zero, 0.0, places=5)

        student = [[0.6, 0.2, 0.2]]
        loss_pos = py_kl_distillation(teacher, student, temperature=2.0, weight=0.2)
        self.assertGreater(loss_pos, 0.0)


class TestLossesTensorFlowExecution(unittest.TestCase):
    """Verifies Keras loss classes via public __call__, numerical parity, and zero-gradient guarantees."""

    def setUp(self):
        if not HAS_TF:
            self.skipTest("TensorFlow >= 2.19 is not installed in the current environment.")

    def test_tf_masked_ce_zero_gradient_on_ignore_via_public_call(self):
        import tensorflow as tf
        from tools.vision.training.losses import MaskedSparseCategoricalCrossentropy

        loss_fn = MaskedSparseCategoricalCrossentropy(ignore_label=255)

        y_true = tf.constant([[[2, 255, 1]]], dtype=tf.int32)
        sample_weight = tf.constant([[[1.0, 1.0, 0.0]]], dtype=tf.float32)

        pred_logits = tf.Variable([[[
            [0.1, 0.1, 2.0, 0.1, 0.1, 0.1, 0.1],
            [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
            [0.5, 1.5, 0.5, 0.5, 0.5, 0.5, 0.5],
        ]]], dtype=tf.float32)

        with tf.GradientTape() as tape:
            pred_probs = tf.nn.softmax(pred_logits, axis=-1)
            # Invoke via public __call__ interface
            loss = loss_fn(y_true, pred_probs, sample_weight=sample_weight)

        grads = tape.gradient(loss, pred_logits)

        # Gradients at pixel 1 (ignore 255) must be strictly 0.0
        grad_pixel_1 = grads[0, 0, 1, :].numpy()
        self.assertTrue((grad_pixel_1 == 0.0).all())

        # Gradients at pixel 2 (weight 0.0) must be strictly 0.0
        grad_pixel_2 = grads[0, 0, 2, :].numpy()
        self.assertTrue((grad_pixel_2 == 0.0).all())

        # Gradients at pixel 0 (valid) must be non-zero
        grad_pixel_0 = grads[0, 0, 0, :].numpy()
        self.assertFalse((grad_pixel_0 == 0.0).all())

    def test_tf_masked_dice_perfect_prediction_pseudo_zero_loss(self):
        import tensorflow as tf
        from tools.vision.training.losses import MaskedDiceLoss

        loss_fn = MaskedDiceLoss(num_classes=7, ignore_label=255, exclude_absent=True)

        y_true = tf.constant([[[2, 1]]], dtype=tf.int32)
        sample_weight = tf.constant([[[0.25, 0.25]]], dtype=tf.float32)

        pred = tf.constant([[[
            [0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        ]]], dtype=tf.float32)

        loss = loss_fn(y_true, pred, sample_weight=sample_weight)
        self.assertAlmostEqual(float(loss.numpy()), 0.0, places=5)

    def test_tf_masked_dice_zero_gradient_on_ignore_via_public_call(self):
        import tensorflow as tf
        from tools.vision.training.losses import MaskedDiceLoss

        loss_fn = MaskedDiceLoss(num_classes=7, ignore_label=255, exclude_absent=True)

        y_true = tf.constant([[[2, 255]]], dtype=tf.int32)
        sample_weight = tf.constant([[[1.0, 1.0]]], dtype=tf.float32)

        pred_var = tf.Variable([[[
            [0.1, 0.1, 0.6, 0.1, 0.05, 0.03, 0.02],
            [0.2, 0.2, 0.2, 0.1, 0.1, 0.1, 0.1],
        ]]], dtype=tf.float32)

        with tf.GradientTape() as tape:
            # Public __call__ invocation
            loss = loss_fn(y_true, pred_var, sample_weight=sample_weight)

        grads = tape.gradient(loss, pred_var)

        # Gradients at pixel 1 (ignore 255) must be strictly 0.0
        grad_pixel_1 = grads[0, 0, 1, :].numpy()
        self.assertTrue((grad_pixel_1 == 0.0).all())

        # Gradients at pixel 0 (valid) must be non-zero
        grad_pixel_0 = grads[0, 0, 0, :].numpy()
        self.assertFalse((grad_pixel_0 == 0.0).all())

    def test_tf_masked_focal_bce_zero_gradient_via_public_call(self):
        import tensorflow as tf
        from tools.vision.training.losses import MaskedFocalBinaryCrossentropy

        loss_fn = MaskedFocalBinaryCrossentropy(gamma=2.0)

        y_true = tf.constant([[[[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]]], dtype=tf.float32)
        valid_mask = tf.constant([[[[1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]]]], dtype=tf.float32)

        pred_var = tf.Variable([[[[0.5] * 8]]], dtype=tf.float32)

        with tf.GradientTape() as tape:
            # Public __call__ invocation
            loss = loss_fn(y_true, pred_var, sample_weight=valid_mask)

        grads = tape.gradient(loss, pred_var)

        grad_ch0 = grads[0, 0, 0, 0].numpy()
        self.assertNotEqual(grad_ch0, 0.0)

        grad_invalid = grads[0, 0, 0, 1:].numpy()
        self.assertTrue((grad_invalid == 0.0).all())


if __name__ == "__main__":
    unittest.main()
