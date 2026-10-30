import gzip
import json
import tempfile
import unittest
from pathlib import Path

from tools.vision.training.evaluate import (
    HISTORICAL_BASELINE_CAVEAT,
    evaluate_records,
    iter_prediction_records,
    main,
)
from tools.vision.training.metrics import HAZARD_NAMES, RISK_THRESHOLDS


def prediction_record(
    record_id="surface-1",
    *,
    domain="surface",
    split="validation",
    group_id="surface-group-1",
    timed=False,
):
    pixels = 5
    record = {
        "record_id": record_id,
        "prediction_evidence": "unit_test_fixture",
        "domain": domain,
        "split": split,
        "group_id": group_id,
        "semantic_truth": [2, 3, 1, 4, 255],
        "semantic_prediction": [2, 3, 2, 4, 0],
        "semantic_provenance": [1.0, 1.0, 1.0, 0.25, 1.0],
    }
    if domain == "surface":
        record.update(
            risk_truth=[0] * (pixels * len(HAZARD_NAMES)),
            risk_prediction=[0.0] * (pixels * len(HAZARD_NAMES)),
            risk_valid=[1] * (pixels * len(HAZARD_NAMES)),
        )
    if timed:
        record.update(
            timing_ms={"invoke": 12.5, "pipeline": 23.0},
            timing_source="device",
            device_model="Pixel 3",
            runtime_backend="GPU",
        )
    return record


class EvaluateRecordsTests(unittest.TestCase):
    def test_domain_metrics_and_historical_baseline_caveat_are_explicit(self):
        report = evaluate_records(
            [prediction_record()],
            split="validation",
            historical_baseline=True,
        )
        self.assertEqual(report["domains"]["surface"]["images"], 1)
        self.assertEqual(report["domains"]["surface"]["groups"], 1)
        self.assertEqual(report["domains"]["surface"]["valid_pixels"], 3)
        self.assertEqual(report["domains"]["surface"]["object_ground_truth_pixels"]["person"], 0)
        self.assertEqual(report["historical_baseline_exposure_caveat"], HISTORICAL_BASELINE_CAVEAT)
        self.assertFalse(report["trained_model_quality_claimed"])
        self.assertEqual(report["risk"]["annotated_records"], 1)

    def test_validation_grid_is_exact_and_test_uses_frozen_validation_thresholds(self):
        validation = evaluate_records(
            [prediction_record()],
            split="validation",
        )
        self.assertEqual(
            [
                row["threshold"]
                for row in validation["risk"]["metrics"]["bike_lane"]["thresholds"]
            ],
            list(RISK_THRESHOLDS),
        )

        test = evaluate_records(
            [
                prediction_record(
                    record_id="surface-test",
                    split="test",
                    group_id="surface-test-group",
                )
            ],
            split="test",
            thresholds_from=validation,
        )
        self.assertEqual(test["split"], "test")
        self.assertEqual(
            test["risk"]["validation_threshold_selection"],
            validation["risk_threshold_selection"],
        )
        self.assertEqual(
            test["risk"]["metrics"]["bike_lane"]["thresholds"][0]["threshold"],
            RISK_THRESHOLDS[0],
        )
        self.assertFalse(
            test["risk_threshold_selection"]["bike_lane"]["candidate_selected"]
        )

    def test_test_evaluation_requires_a_validation_threshold_report(self):
        with self.assertRaisesRegex(ValueError, "requires thresholds"):
            evaluate_records([prediction_record(split="test")], split="test")
        with self.assertRaisesRegex(ValueError, "must come from a validation report"):
            evaluate_records(
                [prediction_record(split="test")],
                split="test",
                thresholds_from={"split": "test"},
            )

    def test_test_evaluation_rejects_validation_record_or_group_overlap(self):
        validation = evaluate_records(
            [prediction_record(group_id="same-group")],
            split="validation",
        )
        with self.assertRaisesRegex(ValueError, "overlap"):
            evaluate_records(
                [
                    prediction_record(
                        record_id="test-id",
                        split="test",
                        group_id="same-group",
                    )
                ],
                split="test",
                thresholds_from=validation,
            )

    def test_test_risk_enablement_fails_closed_when_heldout_coverage_is_low(self):
        validation_thresholds = {
            "schema_version": 1,
            "report_kind": "saved_prediction_evaluation",
            "model_kind": "candidate",
            "split": "validation",
            "record_count": 1,
            "evaluation_fingerprint": "a" * 64,
            "domains": {"surface": {"groups": 1}},
            "split_membership": {
                "record_keys": ["surface:validation-id"],
                "group_keys": ["surface:validation-group"],
            },
            "risk_threshold_selection": {
                name: {"candidate_selected": True, "threshold": 0.75}
                for name in HAZARD_NAMES
            },
        }
        test = evaluate_records(
            [
                prediction_record(
                    record_id="surface-test-id",
                    split="test",
                    group_id="surface-test-group",
                )
            ],
            split="test",
            thresholds_from=validation_thresholds,
        )
        self.assertTrue(
            test["risk"]["validation_threshold_selection"]["bike_lane"][
                "candidate_selected"
            ]
        )
        self.assertFalse(
            test["risk_threshold_selection"]["bike_lane"]["candidate_selected"]
        )
        self.assertEqual(
            test["risk_threshold_selection"]["bike_lane"]["reason"],
            "insufficient_test_positive_images",
        )

    def test_evaluation_fingerprint_ignores_predictions_but_tracks_labels_and_groups(self):
        record = prediction_record()
        original = evaluate_records([record], split="validation")
        changed_prediction = dict(record, semantic_prediction=[0, 0, 0, 0, 0])
        different_prediction = evaluate_records([changed_prediction], split="validation")
        changed_truth = dict(record, semantic_truth=[3, 3, 1, 4, 255])
        different_truth = evaluate_records([changed_truth], split="validation")
        self.assertEqual(
            original["evaluation_fingerprint"],
            different_prediction["evaluation_fingerprint"],
        )
        self.assertNotEqual(
            original["evaluation_fingerprint"],
            different_truth["evaluation_fingerprint"],
        )

    def test_record_split_duplicate_ids_and_missing_surface_hazards_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "expected 'validation'"):
            evaluate_records([prediction_record(split="test")], split="validation")
        with self.assertRaisesRegex(ValueError, "duplicate record_id"):
            evaluate_records(
                [prediction_record(), prediction_record()],
                split="validation",
            )
        record = prediction_record()
        del record["risk_valid"]
        with self.assertRaisesRegex(ValueError, "risk_valid"):
            evaluate_records([record], split="validation")

    def test_device_timing_requires_identifiable_device_and_backend(self):
        record = prediction_record(timed=True)
        del record["device_model"]
        with self.assertRaisesRegex(ValueError, "requires device_model"):
            evaluate_records([record], split="validation")
        report = evaluate_records(
            [prediction_record(timed=True)],
            split="validation",
        )
        self.assertEqual(report["performance"]["timing_source"], "device")
        self.assertEqual(report["performance"]["invoke_p50_ms"], 12.5)
        self.assertEqual(report["performance"]["pipeline_p95_ms"], 23.0)

    def test_zero_device_latency_is_rejected(self):
        record = prediction_record(timed=True)
        record["timing_ms"]["invoke"] = 0.0
        with self.assertRaisesRegex(ValueError, "finite positive"):
            evaluate_records([record], split="validation")

    def test_cityscapes_records_do_not_enter_surface_hazard_counts(self):
        report = evaluate_records(
            [prediction_record(domain="cityscapes")],
            split="validation",
        )
        self.assertEqual(report["risk"]["annotated_records"], 0)
        self.assertEqual(report["risk"]["metrics"]["bike_lane"]["positive_images"], 0)

    def test_perfect_pixel_risk_candidates_never_enable_frame_warnings(self):
        records = []
        for index in range(50):
            record = prediction_record(
                record_id=f"surface-{index}",
                group_id=f"group-{index}",
            )
            record["risk_truth"] = [1] * (5 * len(HAZARD_NAMES))
            record["risk_prediction"] = [0.99] * (5 * len(HAZARD_NAMES))
            records.append(record)

        report = evaluate_records(records, split="validation")
        self.assertTrue(
            report["risk_threshold_selection"]["bike_lane"]["candidate_selected"]
        )
        self.assertEqual(
            report["risk_threshold_selection"]["bike_lane"]["pixel_precision"],
            1.0,
        )
        self.assertEqual(
            report["risk_threshold_selection"]["bike_lane"]["pixel_recall"],
            1.0,
        )
        self.assertIs(report["automatic_warning_enablement"], False)
        self.assertEqual(
            report["automatic_warning_enablement_reason"],
            "warning_level_evidence_not_supported",
        )

    def test_validation_thresholds_and_predictions_must_match_explicit_model_hashes(self):
        model_sha256 = "a" * 64
        validation = evaluate_records(
            [prediction_record()],
            split="validation",
            model_sha256=model_sha256,
        )
        test_record = prediction_record(
            record_id="surface-test",
            split="test",
            group_id="surface-test-group",
        )
        test = evaluate_records(
            [test_record],
            split="test",
            thresholds_from=validation,
            model_sha256=model_sha256,
        )
        self.assertEqual(test["threshold_model_sha256"], model_sha256)
        self.assertIs(test["automatic_warning_enablement"], False)
        with self.assertRaisesRegex(ValueError, "same model_sha256"):
            evaluate_records(
                [test_record],
                split="test",
                thresholds_from=validation,
                model_sha256="b" * 64,
            )
        with self.assertRaisesRegex(ValueError, "lowercase 64-character"):
            evaluate_records(
                [prediction_record()],
                split="validation",
                model_sha256="not-a-hash",
            )


class PredictionStreamTests(unittest.TestCase):
    def test_gzip_jsonl_is_streamed_as_objects(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "predictions.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as file:
                file.write(json.dumps(prediction_record()) + "\n")
                file.write(json.dumps(prediction_record("surface-2")) + "\n")
            records = list(iter_prediction_records(path))
        self.assertEqual([item["record_id"] for item in records], ["surface-1", "surface-2"])

    def test_cli_writes_deterministic_machine_readable_report(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            input_path = root / "predictions.jsonl"
            first_output = root / "first.json"
            second_output = root / "second.json"
            input_path.write_text(json.dumps(prediction_record()) + "\n", encoding="utf-8")
            for output in (first_output, second_output):
                result = main(
                    [
                        "evaluate",
                        "--predictions",
                        str(input_path),
                        "--split",
                        "validation",
                        "--model-kind",
                        "historical_baseline",
                        "--output",
                        str(output),
                    ]
                )
                self.assertEqual(result, 0)
            first_text = first_output.read_text(encoding="utf-8")
            self.assertEqual(first_text, second_output.read_text(encoding="utf-8"))
            self.assertEqual(json.loads(first_text)["model_kind"], "historical_baseline")

    def test_compare_cli_emits_fail_closed_gate_report_for_missing_metrics(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            baseline_path = root / "baseline.json"
            candidate_path = root / "candidate.json"
            output_path = root / "comparison.json"
            for path in (baseline_path, candidate_path):
                path.write_text(
                    json.dumps({"schema_version": 1, "split": "test"}),
                    encoding="utf-8",
                )
            result = main(
                [
                    "compare",
                    "--baseline",
                    str(baseline_path),
                    "--candidate",
                    str(candidate_path),
                    "--candidate-name",
                    "A",
                    "--output",
                    str(output_path),
                ]
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
        self.assertEqual(result, 0)
        self.assertFalse(report["passed"])
        self.assertFalse(report["trained_model_quality_claimed"])
        self.assertFalse(report["semantic_model_acceptance"]["passed"])
        self.assertFalse(report["automatic_warning_enablement"])
        self.assertFalse(report["warning_enablement"]["automatic_warning_enablement"])

    def test_compare_does_not_trust_missing_or_synthetic_warning_evidence(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            baseline_path = root / "baseline.json"
            candidate_path = root / "candidate.json"
            output_path = root / "comparison.json"
            baseline_path.write_text(
                json.dumps({"schema_version": 1, "split": "test"}),
                encoding="utf-8",
            )
            candidate_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "split": "test",
                        "automatic_warning_enablement": True,
                        "model_sha256": "a" * 64,
                        "threshold_model_sha256": "b" * 64,
                        "warning_level_evidence": {
                            "synthetic": True,
                            "tp": -1,
                            "fp": "invalid",
                            "fn": 0,
                        },
                    }
                ),
                encoding="utf-8",
            )
            result = main(
                [
                    "compare",
                    "--baseline",
                    str(baseline_path),
                    "--candidate",
                    str(candidate_path),
                    "--candidate-name",
                    "A",
                    "--output",
                    str(output_path),
                ]
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
        self.assertEqual(result, 0)
        self.assertFalse(report["automatic_warning_enablement"])
        self.assertFalse(report["warning_enablement"]["automatic_warning_enablement"])
        self.assertFalse(report["warning_enablement"]["model_hash_match"])

    def test_legacy_semantic_reports_can_pass_without_enabling_warnings(self):
        def report(model_kind, surface_iou, false_safe, object_iou):
            return {
                "schema_version": 1,
                "report_kind": "saved_prediction_evaluation",
                "split": "test",
                "record_count": 2,
                "prediction_evidence": "saved_model_predictions",
                "evaluation_fingerprint": "a" * 64,
                "split_membership": {
                    "record_keys": ["surface:record-1", "cityscapes:record-1"],
                    "group_keys": ["surface:group-1", "cityscapes:group-1"],
                },
                "trained_model_quality_claimed": False,
                "model_kind": model_kind,
                "historical_baseline_exposure_caveat": (
                    "Historical baseline may have seen test data."
                    if model_kind == "historical_baseline"
                    else None
                ),
                "domains": {
                    "surface": {
                        "images": 1,
                        "groups": 1,
                        "valid_pixels": 10,
                        "mean_iou": surface_iou,
                        "nonwalkable_to_walkable_false_positive_rate": false_safe,
                    },
                    "cityscapes": {
                        "images": 1,
                        "groups": 1,
                        "valid_pixels": 10,
                        "object_iou": {
                            name: object_iou
                            for name in ("person", "vehicle", "pole_sign")
                        },
                    },
                },
                "performance": {
                    "timing_source": "device",
                    "device_sample_count": 2,
                    "device_model": "Pixel",
                    "runtime_backend": "GPU",
                    "invoke_p50_ms": 100.0,
                    "pipeline_p95_ms": 200.0,
                },
            }

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            baseline_path = root / "baseline.json"
            candidate_path = root / "candidate.json"
            output_path = root / "comparison.json"
            baseline_path.write_text(
                json.dumps(report("historical_baseline", 0.6, 0.1, 0.5)),
                encoding="utf-8",
            )
            candidate_path.write_text(
                json.dumps(report("candidate", 0.63, 0.08, 0.49)),
                encoding="utf-8",
            )
            result = main(
                [
                    "compare",
                    "--baseline",
                    str(baseline_path),
                    "--candidate",
                    str(candidate_path),
                    "--candidate-name",
                    "A",
                    "--output",
                    str(output_path),
                ]
            )
            comparison = json.loads(output_path.read_text(encoding="utf-8"))
        self.assertEqual(result, 0)
        self.assertTrue(comparison["semantic_model_acceptance"]["passed"])
        self.assertTrue(comparison["passed"])
        self.assertFalse(comparison["automatic_warning_enablement"])
        self.assertFalse(comparison["warning_enablement"]["automatic_warning_enablement"])

    def test_malformed_jsonl_returns_a_line_specific_error(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "predictions.jsonl"
            path.write_text("{}\nnot-json\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "line 2"):
                list(iter_prediction_records(path))


if __name__ == "__main__":
    unittest.main()
