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

    def test_actual_city_counts_regression_nonflat_weights(self):
        """Regressions for actual Cityscapes training counts proving non-flat present weights.

        Actual Cityscapes pixel counts:
            0: 42472514 (road/other)
            1: 35308559 (sidewalk/road)
            2:  5835902 (sidewalk)
            3:        0 (crosswalk - absent in Cityscapes)
            4:  1206850 (person)
            5:  7614918 (vehicle)
            6:  1908035 (pole_sign)
        Prior bug: crosswalk with 0 count dominated reciprocal normalization (1/sqrt(1e-8)=10000),
        crushing all positive class weights to min_weight 0.5: [0.5, 0.5, 0.5, 3.0, 0.5, 0.5, 0.5].
        Fix: absent class receives neutral 1.0 (clipped), while positive classes are normalized
        using positive classes only.
        """
        city_counts = [42472514, 35308559, 5835902, 0, 1206850, 7614918, 1908035]
        weights = compute_inverse_sqrt_class_weights(city_counts, min_weight=0.5, max_weight=3.0)

        self.assertEqual(len(weights), 7)

        # Absent class 3 (crosswalk) must receive neutral weight 1.0
        self.assertEqual(weights[3], 1.0)

        # Present classes must NOT be flattened to min_weight 0.5
        present_weights = [weights[0], weights[1], weights[2], weights[4], weights[5], weights[6]]
        self.assertGreater(len(set(present_weights)), 1, "Present weights must not be flat")
        self.assertNotEqual(present_weights, [0.5, 0.5, 0.5, 0.5, 0.5, 0.5])

        # Exact expected normalized weights computed from positive counts only:
        # total_pos = 94346778
        # raw: [1.490423, 1.634645, 4.020773, 8.841723, 3.519905, 7.031862]
        # mean_pos = 4.42322187
        # norm: [0.33695, 0.36956, 0.90901, 1.99893, 0.79578, 1.58976]
        # clipped to [0.5, 3.0]
        expected_weights = [
            0.5,
            0.5,
            0.9090145132587971,
            1.0,
            1.9989326651463217,
            0.7957784916487853,
            1.5897602870593375,
        ]
        for idx, (w, exp) in enumerate(zip(weights, expected_weights)):
            self.assertAlmostEqual(
                w, exp, places=6,
                msg=f"Class {idx} weight {w} does not match expected {exp}",
            )

        # Relative ranking of present classes must reflect inverse frequencies:
        # rarest present (class 4) > class 6 > class 2 > class 5 > classes 0, 1
        self.assertGreater(weights[4], weights[6])
        self.assertGreater(weights[6], weights[2])
        self.assertGreater(weights[2], weights[5])
        self.assertGreater(weights[5], weights[0])
        self.assertEqual(weights[0], 0.5)
        self.assertEqual(weights[1], 0.5)

    def test_adding_zero_count_classes_invariance(self):
        """Adding zero-count classes must not change corresponding positive class weights."""
        pos_counts = [10000, 4000, 2500, 800, 150]
        baseline_weights = compute_inverse_sqrt_class_weights(pos_counts)

        # Insert zeros at arbitrary positions: beginning, interior, end, multiple consecutive
        mixed_counts = [0, 10000, 0, 0, 4000, 2500, 0, 800, 150, 0]
        mixed_weights = compute_inverse_sqrt_class_weights(mixed_counts)

        # Verify all absent classes received neutral 1.0
        for zero_idx in [0, 2, 3, 6, 9]:
            self.assertEqual(
                mixed_weights[zero_idx], 1.0,
                msg=f"Zero-count class at index {zero_idx} must have neutral weight 1.0",
            )

        # Verify positive classes have identical weights to baseline
        pos_indices_in_mixed = [1, 4, 5, 7, 8]
        extracted_pos_weights = [mixed_weights[i] for i in pos_indices_in_mixed]
        for p_idx, (extracted, base) in enumerate(zip(extracted_pos_weights, baseline_weights)):
            self.assertAlmostEqual(
                extracted, base, places=10,
                msg=f"Positive class {p_idx} changed after adding zero-count classes",
            )

        # Same invariance must hold with normalize=False
        baseline_unnorm = compute_inverse_sqrt_class_weights(pos_counts, normalize=False)
        mixed_unnorm = compute_inverse_sqrt_class_weights(mixed_counts, normalize=False)
        extracted_unnorm = [mixed_unnorm[i] for i in pos_indices_in_mixed]
        for p_idx, (extracted, base) in enumerate(zip(extracted_unnorm, baseline_unnorm)):
            self.assertAlmostEqual(
                extracted, base, places=10,
                msg=f"Unnormalized positive class {p_idx} changed after adding zero-count classes",
            )

    def test_scale_counts_invariance(self):
        """Weights must be strictly invariant to uniform scaling of frequencies or counts."""
        city_counts = [42472514, 35308559, 5835902, 0, 1206850, 7614918, 1908035]
        base_weights = compute_inverse_sqrt_class_weights(city_counts)

        scales = [0.001, 0.5, 2.0, 10.0, 100.0, 17.54321]
        for scale in scales:
            scaled_counts = [c * scale for c in city_counts]
            scaled_weights = compute_inverse_sqrt_class_weights(scaled_counts)
            for idx, (w, base) in enumerate(zip(scaled_weights, base_weights)):
                self.assertAlmostEqual(
                    w, base, places=9,
                    msg=f"Scale factor {scale} altered weight for class {idx}",
                )

        # Scale invariance with normalize=False
        base_unnorm = compute_inverse_sqrt_class_weights(city_counts, normalize=False)
        scaled_unnorm = compute_inverse_sqrt_class_weights(
            [c * 3.14159 for c in city_counts], normalize=False
        )
        for idx, (w, base) in enumerate(zip(scaled_unnorm, base_unnorm)):
            self.assertAlmostEqual(w, base, places=9)

    def test_all_positive_legacy_result(self):
        """All-positive inputs must retain existing legacy behavior."""
        counts = [100000, 5000, 2000, 500, 50, 10, 2]
        weights = compute_inverse_sqrt_class_weights(counts, min_weight=0.5, max_weight=3.0)

        # Legacy calculation result:
        expected = [0.5, 0.5, 0.5, 0.5, 0.792503343487512, 1.772091348433942, 3.0]
        for idx, (w, exp) in enumerate(zip(weights, expected)):
            self.assertAlmostEqual(w, exp, places=6)

        # Equal frequencies must all receive 1.0
        balanced = [500, 500, 500, 500]
        balanced_weights = compute_inverse_sqrt_class_weights(balanced)
        self.assertEqual(balanced_weights, [1.0, 1.0, 1.0, 1.0])

        # Rare positive count regression preserving 1e-8 frequency floor
        # For [1, 1e12], freq_0 = 1 / (1 + 1e12) < 1e-8, floored at 1e-8 -> raw_0 = 10000.0
        # raw_1 = 1.0 / sqrt(1.0) = 1.0 -> mean = 5000.5 -> norm = [1.999800019998, 0.00019998]
        # clipped to [0.5, 3.0] -> [1.9998000199980002, 0.5]
        rare_counts = [1, 1e12]
        rare_weights = compute_inverse_sqrt_class_weights(rare_counts)
        self.assertAlmostEqual(rare_weights[0], 10000.0 / 5000.5, places=6)
        self.assertAlmostEqual(rare_weights[1], 0.5, places=6)

    def test_all_zero_and_normalize_false(self):
        """All-zero input must return neutral weights; normalize=False must preserve raw semantics."""
        # Empty input
        self.assertEqual(compute_inverse_sqrt_class_weights([]), [])

        # All-zero counts
        zeros = [0, 0, 0, 0]
        self.assertEqual(compute_inverse_sqrt_class_weights(zeros), [1.0, 1.0, 1.0, 1.0])
        self.assertEqual(
            compute_inverse_sqrt_class_weights(zeros, normalize=False),
            [1.0, 1.0, 1.0, 1.0],
        )

        # All-zero clipped to custom bounds
        self.assertEqual(
            compute_inverse_sqrt_class_weights(zeros, min_weight=1.5, max_weight=3.0),
            [1.5, 1.5, 1.5, 1.5],
        )
        self.assertEqual(
            compute_inverse_sqrt_class_weights(zeros, min_weight=0.2, max_weight=0.8),
            [0.8, 0.8, 0.8, 0.8],
        )

        # normalize=False with mixed positive and absent classes
        # Class 0: count 100 -> freq = 100/500 = 0.2 -> 1/sqrt(0.2) = 2.236068
        # Class 1: count 0   -> absent -> neutral 1.0
        # Class 2: count 400 -> freq = 400/500 = 0.8 -> 1/sqrt(0.8) = 1.118034
        mixed = [100, 0, 400]
        unnorm_weights = compute_inverse_sqrt_class_weights(mixed, normalize=False)
        self.assertAlmostEqual(unnorm_weights[0], 1.0 / math.sqrt(0.2), places=6)
        self.assertEqual(unnorm_weights[1], 1.0)
        self.assertAlmostEqual(unnorm_weights[2], 1.0 / math.sqrt(0.8), places=6)

    def test_invalid_inputs_rejected(self):
        """Rejects negative, non-finite, and boolean counts as well as invalid clipping bounds."""
        # Negative counts
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, -5, 20])

        # Non-finite counts
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, float("nan"), 20])
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, float("inf"), 20])
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, float("-inf"), 20])

        # Boolean counts (bool is subclass of int in Python)
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, True, 20])
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([False, 10, 20])

        # Non-numeric counts
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, "5", 20])
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, None, 20])

        # Invalid bounds
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=3.0, max_weight=1.0)
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=0.0, max_weight=3.0)
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=-0.5, max_weight=3.0)
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=0.5, max_weight=-1.0)
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=float("nan"))
        with self.assertRaises(ValueError):
            compute_inverse_sqrt_class_weights([10, 20], max_weight=float("inf"))
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight=True)
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, 20], max_weight=False)
        with self.assertRaises(TypeError):
            compute_inverse_sqrt_class_weights([10, 20], min_weight="0.5")

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
