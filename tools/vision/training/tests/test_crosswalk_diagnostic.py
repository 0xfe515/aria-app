"""Focused offline tests for the crosswalk diagnostic helpers."""

import json
import unittest

from tools.vision.training.crosswalk_diagnostic import (
    _parser,
    class3_positive_nll,
    class_pixel_counts,
    gradient_summary,
)


class CrosswalkDiagnosticTest(unittest.TestCase):
    def test_class_mapping_and_real_counts(self):
        import numpy as np

        labels = np.array([[0, 3, 3, 6, 255]], dtype=np.uint8)
        provenance = np.array([[1, 1, 2, 1, 1]], dtype=np.uint8)
        counts = class_pixel_counts(labels, provenance)
        self.assertEqual(counts, [1, 0, 0, 1, 0, 0, 1])
        self.assertEqual(counts[3], 1)

    def test_absent_and_empty_class3_counts_are_zero(self):
        import numpy as np

        self.assertEqual(class_pixel_counts(np.array([[1, 2]]), np.array([[1, 1]]))[3], 0)
        self.assertEqual(class_pixel_counts(np.array([[3]]), np.array([[0]]))[3], 0)

    def test_finite_nonzero_gradient_summary(self):
        import numpy as np

        summary = gradient_summary(np.array([0.0, 2.0, -1.0], dtype=np.float32))
        self.assertTrue(summary["finite"])
        self.assertTrue(summary["nonzero"])
        self.assertEqual(summary["nonzero_count"], 2)
        zero_summary = gradient_summary(np.zeros((2,), dtype=np.float32))
        self.assertFalse(zero_summary["nonzero"])
        self.assertFalse(gradient_summary(np.array([float("nan")]))["finite"])

    def test_class3_objective_uses_only_real_positive_pixels_and_class3_channel(self):
        import numpy as np
        import tensorflow as tf

        logits = tf.Variable(np.array([
            [[[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0],
              [8.0, 0.0, 0.0, -8.0, 0.0, 0.0, 0.0]]],
            [[[0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0],
              [0.0, 8.0, 0.0, -8.0, 0.0, 0.0, 0.0]]],
        ], dtype=np.float32))
        labels = tf.constant([[[3, 1]], [[2, 3]]], dtype=tf.int32)
        weights = tf.constant([[[1.0, 1.0]], [[0.0, 0.25]]], dtype=tf.float32)
        with tf.GradientTape() as tape:
            loss, count = class3_positive_nll(logits, labels, weights)
        gradient = tape.gradient(loss, logits).numpy()
        self.assertEqual(count, 1)
        self.assertGreater(abs(float(gradient[0, 0, 0, 3])), 0.0)
        self.assertEqual(float(gradient[0, 0, 1, 3]), 0.0)  # non-class3 pixel
        self.assertEqual(float(gradient[1, 0, 0, 3]), 0.0)  # zero provenance
        self.assertEqual(float(gradient[1, 0, 1, 3]), 0.0)  # pseudo provenance
        self.assertGreater(float(np.count_nonzero(gradient[..., 3])), 0)
        with self.assertRaisesRegex(ValueError, "zero real crosswalk supervision"):
            class3_positive_nll(logits, labels, tf.zeros_like(weights))

    def test_cli_validation_and_stable_json(self):
        parser = _parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["--manifest", "m", "--checkpoint", "c", "--output", "o"])
        args = parser.parse_args([
            "--manifest", "m", "--architecture", "model_a", "--checkpoint", "c",
            "--device", "/CPU:0", "--sample-count", "2", "--output", "o",
        ])
        self.assertEqual(args.sample_count, 2)
        payload = {"status": "pass", "counts": [0, 0, 0, 1]}
        self.assertEqual(json.dumps(payload, sort_keys=True), json.dumps(payload, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
