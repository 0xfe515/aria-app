import unittest

from tools.vision.training.metrics import (
    HAZARD_NAMES,
    OBJECT_CLASS_NAMES,
    RISK_THRESHOLDS,
    StreamingConfusionMatrix,
    StreamingRiskMetrics,
    acceptance_gates,
    select_risk_thresholds,
)


def risk_row(values):
    return list(values)


class StreamingConfusionMatrixTests(unittest.TestCase):
    def test_update_filters_ignore_and_non_ground_truth_provenance(self):
        metrics = StreamingConfusionMatrix()
        metrics.update(
            truth=[2, 3, 1, 4, 255, 5],
            prediction=[2, 2, 2, 4, 0, 5],
            provenance=[1.0, 1.0, 1.0, 0.25, 1.0, 0.0],
        )

        report = metrics.summary()
        self.assertEqual(report["valid_pixels"], 3)
        self.assertEqual(report["confusion_matrix"][2][2], 1)
        self.assertEqual(report["confusion_matrix"][3][2], 1)
        self.assertEqual(report["confusion_matrix"][1][2], 1)
        self.assertEqual(report["object_ground_truth_pixels"]["person"], 0)
        self.assertEqual(report["object_recall"]["person"], None)

    def test_summary_uses_nonwalkable_pixels_as_false_safe_denominator(self):
        metrics = StreamingConfusionMatrix()
        metrics.update(
            truth=[0, 1, 2, 3, 4, 5],
            prediction=[2, 1, 2, 3, 2, 5],
        )

        report = metrics.summary()
        self.assertEqual(
            report["nonwalkable_to_walkable_false_positive_rate"],
            2 / 4,
        )
        self.assertEqual(report["nonwalkable_pixels"], 4)
        self.assertEqual(report["nonwalkable_to_walkable_pixels"], 2)
        self.assertEqual(report["walkable_precision"], 2 / 4)
        defined_ious = [value for value in report["class_iou"].values() if value is not None]
        self.assertAlmostEqual(report["mean_iou"], sum(defined_ious) / len(defined_ious))
        self.assertEqual(set(report["object_iou"]), set(OBJECT_CLASS_NAMES))

    def test_mismatched_array_lengths_fail(self):
        with self.assertRaisesRegex(ValueError, "different lengths"):
            StreamingConfusionMatrix().update([1, 2], [1])

    def test_unknown_class_fails_instead_of_becoming_background(self):
        with self.assertRaisesRegex(ValueError, "outside the class range"):
            StreamingConfusionMatrix().update([7], [0])


class StreamingRiskMetricsTests(unittest.TestCase):
    def test_channel_last_risk_counts_threshold_equal_scores_and_unique_groups(self):
        metrics = StreamingRiskMetrics()
        truth = risk_row([1, 0, 0, 0, 0, 0, 0, 0]) + risk_row(
            [1, 0, 0, 0, 0, 0, 0, 0]
        )
        scores = risk_row([0.5, 0.8, 0, 0, 0, 0, 0, 0]) + risk_row(
            [0.95, 0, 0, 0, 0, 0, 0, 0]
        )
        valid = risk_row([1] * 8) + risk_row([1] * 8)
        metrics.update(truth, scores, valid, image_id="image-1", group_id="group-1")
        metrics.update(
            risk_row([0] * 8),
            risk_row([0.9] + [0] * 7),
            risk_row([1] * 8),
            image_id="image-2",
            group_id="group-1",
        )

        bike_lane = metrics.summary()["bike_lane"]
        at_half = bike_lane["thresholds"][0]
        at_point_nine = next(row for row in bike_lane["thresholds"] if row["threshold"] == 0.9)
        self.assertIn("pixel_precision", at_half)
        self.assertIn("pixel_recall", at_half)
        self.assertNotIn("precision", at_half)
        self.assertNotIn("recall", at_half)
        self.assertEqual(at_half["true_positive_pixels"], 2)
        self.assertEqual(at_half["false_positive_pixels"], 1)
        self.assertEqual(at_point_nine["true_positive_pixels"], 1)
        self.assertEqual(at_point_nine["false_positive_pixels"], 1)
        self.assertEqual(bike_lane["positive_pixels"], 2)
        self.assertEqual(bike_lane["positive_images"], 1)
        self.assertEqual(bike_lane["positive_groups"], 1)

    def test_invalid_regions_do_not_count_and_channel_count_is_enforced(self):
        metrics = StreamingRiskMetrics()
        metrics.update(
            [1] + [0] * 7,
            [0.99] + [0] * 7,
            [0] * 8,
            image_id="ignored",
            group_id="ignored",
        )
        self.assertEqual(metrics.summary()["bike_lane"]["positive_pixels"], 0)
        with self.assertRaisesRegex(ValueError, "channel-last"):
            metrics.update([0], [0.0], [1], image_id="bad", group_id="bad")

    def test_grid_and_pixel_candidates_fail_closed_on_low_counts(self):
        metrics = StreamingRiskMetrics()
        self.assertEqual(RISK_THRESHOLDS, tuple(round(0.5 + 0.05 * i, 2) for i in range(10)))
        summary = metrics.summary()
        selection = select_risk_thresholds(summary)
        self.assertEqual(set(selection), set(HAZARD_NAMES))
        self.assertFalse(selection["bike_lane"]["candidate_selected"])
        self.assertEqual(selection["bike_lane"]["reason"], "insufficient_positive_images")

    def test_threshold_selection_requires_precision_recall_and_group_counts(self):
        rows = [
            {"threshold": 0.5, "pixel_precision": 0.80, "pixel_recall": 0.95},
            {"threshold": 0.55, "pixel_precision": 0.95, "pixel_recall": 0.75},
        ]
        data = {
            name: {"positive_images": 50, "positive_groups": 5, "thresholds": rows}
            for name in HAZARD_NAMES
        }
        selected = select_risk_thresholds(data)
        self.assertTrue(selected["stairs"]["candidate_selected"])
        self.assertTrue(selected["stairs"]["diagnostic_only"])
        self.assertEqual(selected["stairs"]["threshold"], 0.55)
        data["stairs"]["positive_groups"] = 4
        self.assertFalse(select_risk_thresholds(data)["stairs"]["candidate_selected"])

    def test_thresholds_must_be_sorted_unique_probabilities(self):
        with self.assertRaisesRegex(ValueError, "unique and ascending"):
            StreamingRiskMetrics({name: [0.8, 0.7] for name in HAZARD_NAMES})


class AcceptanceGateTests(unittest.TestCase):
    @staticmethod
    def report(
        surface_iou=0.6,
        false_safe=0.1,
        object_iou=0.5,
        *,
        model_kind="candidate",
        fingerprint="a" * 64,
        split="test",
    ):
        return {
            "schema_version": 1,
            "report_kind": "saved_prediction_evaluation",
            "split": split,
            "record_count": 10,
            "prediction_evidence": "saved_model_predictions",
            "evaluation_fingerprint": fingerprint,
            "split_membership": {
                "record_keys": [f"surface:record-{index}" for index in range(5)]
                + [f"cityscapes:record-{index}" for index in range(5)],
                "group_keys": [f"surface:group-{index}" for index in range(5)]
                + [f"cityscapes:group-{index}" for index in range(5)],
            },
            "trained_model_quality_claimed": False,
            "model_kind": model_kind,
            "overall_mean_iou": surface_iou,
            "historical_baseline_exposure_caveat": (
                "Historical baseline may have seen test data."
                if model_kind == "historical_baseline"
                else None
            ),
            "domains": {
                "surface": {
                    "images": 5,
                    "groups": 5,
                    "valid_pixels": 100,
                    "mean_iou": surface_iou,
                    "nonwalkable_to_walkable_false_positive_rate": false_safe,
                },
                "cityscapes": {
                    "images": 5,
                    "groups": 5,
                    "valid_pixels": 100,
                    "object_iou": {name: object_iou for name in OBJECT_CLASS_NAMES},
                },
            },
            "performance": {
                "timing_source": "device",
                "device_sample_count": 10,
                "device_model": "Pixel",
                "runtime_backend": "GPU",
                "invoke_p50_ms": 100.0,
                "pipeline_p95_ms": 200.0,
            },
        }

    def test_missing_metrics_cannot_pass(self):
        gates = acceptance_gates({"split": "test"}, {"split": "test"}, candidate_name="A")
        self.assertFalse(gates["passed"])
        self.assertTrue(all(not gate["passed"] for gate in gates["gates"]))

    def test_validation_reports_cannot_be_used_as_final_acceptance(self):
        gates = acceptance_gates(
            self.report(model_kind="historical_baseline", split="validation"),
            self.report(split="validation"),
            candidate_name="A",
        )
        self.assertFalse(gates["passed"])
        self.assertEqual(gates["gates"][0]["name"], "saved_model_test_reports_required")

    def test_candidate_gates_apply_strict_thresholds(self):
        baseline = self.report(model_kind="historical_baseline")
        candidate = self.report(surface_iou=0.63, false_safe=0.08, object_iou=0.49)
        result = acceptance_gates(baseline, candidate, candidate_name="A")
        self.assertTrue(result["passed"])
        candidate = self.report(surface_iou=0.629, false_safe=0.081, object_iou=0.489)
        self.assertFalse(acceptance_gates(baseline, candidate, candidate_name="A")["passed"])

    def test_candidate_c_requires_accuracy_speed_and_pipeline_evidence(self):
        baseline = self.report(model_kind="historical_baseline")
        b = self.report(surface_iou=0.65, false_safe=0.05)
        c = self.report(surface_iou=0.646, false_safe=0.05)
        b["performance"]["invoke_p50_ms"] = 100
        c["performance"]["invoke_p50_ms"] = 80
        result = acceptance_gates(baseline, c, candidate_name="C", b_candidate=b)
        self.assertTrue(result["passed"])
        c["performance"]["timing_source"] = "synthetic"
        result = acceptance_gates(baseline, c, candidate_name="C", b_candidate=b)
        self.assertFalse(result["passed"])
        self.assertIn(
            "missing_or_incomparable_real_device_timing",
            [gate["reason"] for gate in result["gates"]],
        )

    def test_compare_rejects_mismatched_ground_truth_and_invalid_values(self):
        baseline = self.report(model_kind="historical_baseline")
        candidate = self.report(surface_iou=0.63, false_safe=0.08, object_iou=0.49)
        candidate["evaluation_fingerprint"] = "b" * 64
        result = acceptance_gates(baseline, candidate, candidate_name="A")
        self.assertFalse(result["passed"])
        self.assertFalse(next(gate for gate in result["gates"] if gate["name"] == "same_test_evidence")["passed"])

        candidate = self.report(surface_iou=1.5, false_safe=-0.1, object_iou=1.2)
        result = acceptance_gates(baseline, candidate, candidate_name="A")
        self.assertFalse(result["passed"])

    def test_full_pipeline_gate_applies_to_a_and_invalid_candidate_names_fail(self):
        baseline = self.report(model_kind="historical_baseline")
        candidate = self.report(surface_iou=0.63, false_safe=0.08, object_iou=0.49)
        candidate["performance"]["pipeline_p95_ms"] = 221.0
        result = acceptance_gates(baseline, candidate, candidate_name="A")
        self.assertFalse(result["passed"])
        self.assertIn("full_pipeline_p95", [gate["name"] for gate in result["gates"]])
        invalid = acceptance_gates(baseline, candidate, candidate_name="Z")
        self.assertEqual(invalid["gates"][0]["name"], "candidate_name_supported")
        self.assertFalse(invalid["passed"])

    def test_synthetic_prediction_report_cannot_pass(self):
        baseline = self.report(model_kind="historical_baseline")
        candidate = self.report(surface_iou=0.63, false_safe=0.08, object_iou=0.49)
        candidate["prediction_evidence"] = "unit_test_fixture"
        result = acceptance_gates(baseline, candidate, candidate_name="A")
        self.assertFalse(result["passed"])


if __name__ == "__main__":
    unittest.main()
