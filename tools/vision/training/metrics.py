"""Streaming, dependency-free evaluation metrics for native portrait models."""

from __future__ import annotations

from bisect import bisect_right
from itertools import zip_longest
from math import isfinite
from typing import Iterable, Mapping, Sequence


CLASS_NAMES = ("other", "road", "sidewalk", "crosswalk", "person", "vehicle", "pole_sign")
WALKABLE_CLASS_IDS = frozenset((2, 3))
OBJECT_CLASS_IDS = (4, 5, 6)
OBJECT_CLASS_NAMES = tuple(CLASS_NAMES[class_id] for class_id in OBJECT_CLASS_IDS)
IGNORE_INDEX = 255
REAL_PROVENANCE = 1.0
HAZARD_NAMES = (
    "bike_lane",
    "damaged_surface",
    "stairs",
    "manhole",
    "tree_zone",
    "grating",
    "repair_zone",
    "speed_bump",
)
RISK_THRESHOLDS = tuple(round(0.50 + 0.05 * index, 2) for index in range(10))

_SENTINEL = object()


def _paired(
    left: Iterable[object], right: Iterable[object], label: str
) -> Iterable[tuple[object, object]]:
    for first, second in zip_longest(left, right, fillvalue=_SENTINEL):
        if first is _SENTINEL or second is _SENTINEL:
            raise ValueError(f"{label} arrays have different lengths")
        yield first, second


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


class StreamingConfusionMatrix:
    """Accumulate a truth-row/prediction-column confusion matrix."""

    def __init__(self, class_count: int = len(CLASS_NAMES)) -> None:
        if class_count <= 0:
            raise ValueError("class_count must be positive")
        self.class_count = class_count
        self.matrix = [[0 for _ in range(class_count)] for _ in range(class_count)]
        self.valid_pixels = 0

    def update(
        self,
        truth: Iterable[int],
        prediction: Iterable[int],
        provenance: Iterable[float] | None = None,
        *,
        ignore_index: int = IGNORE_INDEX,
        real_provenance: float = REAL_PROVENANCE,
    ) -> None:
        if provenance is None:
            samples: Iterable[tuple[object, ...]] = (
                (actual, predicted)
                for actual, predicted in _paired(truth, prediction, "semantic")
            )
        else:
            def with_provenance() -> Iterable[tuple[object, ...]]:
                for pair, source in zip_longest(
                    _paired(truth, prediction, "semantic"),
                    iter(provenance),
                    fillvalue=_SENTINEL,
                ):
                    if pair is _SENTINEL or source is _SENTINEL:
                        raise ValueError("semantic provenance array has different length")
                    yield pair[0], pair[1], source

            samples = with_provenance()

        for sample in samples:
            actual, predicted = sample[:2]
            source = sample[2] if len(sample) == 3 else real_provenance
            actual_id = int(actual)
            predicted_id = int(predicted)
            if actual_id == ignore_index or source != real_provenance:
                continue
            if not 0 <= actual_id < self.class_count:
                raise ValueError(f"semantic truth class {actual_id} is outside the class range")
            if not 0 <= predicted_id < self.class_count:
                raise ValueError(f"semantic prediction class {predicted_id} is outside the class range")
            self.matrix[actual_id][predicted_id] += 1
            self.valid_pixels += 1

    def summary(
        self,
        class_names: Sequence[str] = CLASS_NAMES,
        walkable_class_ids: frozenset[int] = WALKABLE_CLASS_IDS,
        object_class_ids: Sequence[int] = OBJECT_CLASS_IDS,
    ) -> dict[str, object]:
        if len(class_names) != self.class_count:
            raise ValueError("class_names length must match class_count")
        if any(not 0 <= class_id < self.class_count for class_id in walkable_class_ids):
            raise ValueError("walkable class ID is outside the class range")

        class_iou: dict[str, float | None] = {}
        class_recall: dict[str, float | None] = {}
        for class_id, class_name in enumerate(class_names):
            true_positive = self.matrix[class_id][class_id]
            actual_count = sum(self.matrix[class_id])
            predicted_count = sum(row[class_id] for row in self.matrix)
            union = actual_count + predicted_count - true_positive
            class_iou[class_name] = _ratio(true_positive, union)
            class_recall[class_name] = _ratio(true_positive, actual_count)

        defined_ious = [value for value in class_iou.values() if value is not None]
        mean_iou = sum(defined_ious) / len(defined_ious) if defined_ious else None

        nonwalkable_ids = set(range(self.class_count)) - set(walkable_class_ids)
        true_nonwalkable = sum(sum(self.matrix[class_id]) for class_id in nonwalkable_ids)
        false_safe = sum(
            self.matrix[actual_id][predicted_id]
            for actual_id in nonwalkable_ids
            for predicted_id in walkable_class_ids
        )
        predicted_walkable = sum(
            self.matrix[actual_id][predicted_id]
            for actual_id in range(self.class_count)
            for predicted_id in walkable_class_ids
        )
        true_positive_walkable = sum(
            self.matrix[actual_id][predicted_id]
            for actual_id in walkable_class_ids
            for predicted_id in walkable_class_ids
        )

        object_recall = {
            class_names[class_id]: class_recall[class_names[class_id]]
            for class_id in object_class_ids
        }
        object_iou = {
            class_names[class_id]: class_iou[class_names[class_id]]
            for class_id in object_class_ids
        }
        return {
            "class_iou": class_iou,
            "mean_iou": mean_iou,
            "valid_pixels": self.valid_pixels,
            "confusion_matrix": [row[:] for row in self.matrix],
            "nonwalkable_to_walkable_false_positive_rate": _ratio(false_safe, true_nonwalkable),
            "nonwalkable_pixels": true_nonwalkable,
            "nonwalkable_to_walkable_pixels": false_safe,
            "walkable_precision": _ratio(true_positive_walkable, predicted_walkable),
            "predicted_walkable_pixels": predicted_walkable,
            "object_recall": object_recall,
            "object_iou": object_iou,
            "object_ground_truth_pixels": {
                class_names[class_id]: sum(self.matrix[class_id])
                for class_id in object_class_ids
            },
            "undefined_iou_classes": [
                class_name for class_name, value in class_iou.items() if value is None
            ],
        }


class StreamingRiskMetrics:
    """Accumulate binary risk metrics at fixed thresholds without retaining rasters."""

    def __init__(
        self,
        thresholds_by_class: Mapping[str, Sequence[float]] | None = None,
    ) -> None:
        thresholds_by_class = thresholds_by_class or {
            name: RISK_THRESHOLDS for name in HAZARD_NAMES
        }
        if set(thresholds_by_class) != set(HAZARD_NAMES):
            raise ValueError("thresholds must be provided for every hazard class")
        self.thresholds = {
            name: tuple(float(value) for value in thresholds_by_class[name])
            for name in HAZARD_NAMES
        }
        for name, thresholds in self.thresholds.items():
            if not thresholds or any(
                not isfinite(value) or not 0.0 <= value <= 1.0 for value in thresholds
            ):
                raise ValueError(f"invalid thresholds for {name}")
            if tuple(sorted(set(thresholds))) != thresholds:
                raise ValueError(f"thresholds for {name} must be unique and ascending")
        self._stats = {
            name: {
                "tp_delta": [0] * (len(values) + 1),
                "fp_delta": [0] * (len(values) + 1),
                "positive_pixels": 0,
                "positive_image_ids": set(),
                "positive_group_ids": set(),
            }
            for name, values in self.thresholds.items()
        }

    def update(
        self,
        truth: Iterable[int],
        score: Iterable[float],
        valid: Iterable[int | bool],
        *,
        image_id: str,
        group_id: str,
    ) -> None:
        per_image_positive = [False] * len(HAZARD_NAMES)
        rows = zip_longest(truth, score, valid, fillvalue=_SENTINEL)
        element_count = 0
        for index, (actual, confidence, is_valid) in enumerate(rows):
            if actual is _SENTINEL or confidence is _SENTINEL or is_valid is _SENTINEL:
                raise ValueError("risk truth, prediction, and validity arrays have different lengths")
            element_count = index + 1
            hazard_id = index % len(HAZARD_NAMES)
            if is_valid not in (0, 1, False, True):
                raise ValueError("risk validity values must be boolean or 0/1")
            if actual not in (0, 1, False, True):
                raise ValueError("risk truth values must be binary")
            confidence_value = float(confidence)
            if not isfinite(confidence_value) or not 0.0 <= confidence_value <= 1.0:
                raise ValueError("risk predictions must be finite probabilities in [0, 1]")
            if not is_valid:
                continue
            actual_positive = bool(actual)
            name = HAZARD_NAMES[hazard_id]
            stats = self._stats[name]
            thresholds = self.thresholds[name]
            last_positive_index = bisect_right(thresholds, confidence_value) - 1
            if actual_positive:
                stats["positive_pixels"] += 1
                per_image_positive[hazard_id] = True
                if last_positive_index >= 0:
                    stats["tp_delta"][0] += 1
                    stats["tp_delta"][last_positive_index + 1] -= 1
            elif last_positive_index >= 0:
                stats["fp_delta"][0] += 1
                stats["fp_delta"][last_positive_index + 1] -= 1

        for index, is_positive in enumerate(per_image_positive):
            if is_positive:
                stats = self._stats[HAZARD_NAMES[index]]
                stats["positive_image_ids"].add(image_id)
                stats["positive_group_ids"].add(group_id)
        if element_count % len(HAZARD_NAMES):
            raise ValueError("risk arrays must contain channel-last rows for all hazard classes")

    def summary(self) -> dict[str, object]:
        result: dict[str, object] = {}
        for name in HAZARD_NAMES:
            stats = self._stats[name]
            true_positives = 0
            false_positives = 0
            rows = []
            for index, threshold in enumerate(self.thresholds[name]):
                true_positives += stats["tp_delta"][index]
                false_positives += stats["fp_delta"][index]
                false_negatives = stats["positive_pixels"] - true_positives
                precision = _ratio(true_positives, true_positives + false_positives)
                recall = _ratio(true_positives, stats["positive_pixels"])
                rows.append(
                    {
                        "threshold": threshold,
                        "true_positive_pixels": true_positives,
                        "false_positive_pixels": false_positives,
                        "false_negative_pixels": false_negatives,
                        "pixel_precision": precision,
                        "pixel_recall": recall,
                    }
                )
            result[name] = {
                "thresholds": rows,
                "positive_pixels": stats["positive_pixels"],
                "positive_images": len(stats["positive_image_ids"]),
                "positive_groups": len(stats["positive_group_ids"]),
            }
        return result


def select_risk_thresholds(
    risk_metrics: Mapping[str, Mapping[str, object]],
    *,
    minimum_precision: float = 0.90,
    minimum_recall: float = 0.70,
    minimum_positive_images: int = 50,
    minimum_positive_groups: int = 5,
) -> dict[str, object]:
    """Select diagnostic pixel-threshold candidates, or fail closed per class."""
    selection: dict[str, object] = {}
    for name in HAZARD_NAMES:
        class_metrics = risk_metrics.get(name)
        if class_metrics is None:
            selection[name] = {
                "candidate_selected": False,
                "diagnostic_only": True,
                "threshold": None,
                "reason": "missing_metrics",
            }
            continue
        positive_images = int(class_metrics.get("positive_images", 0))
        positive_groups = int(class_metrics.get("positive_groups", 0))
        rows = class_metrics.get("thresholds", [])
        if positive_images < minimum_positive_images:
            selection[name] = {
                "candidate_selected": False,
                "diagnostic_only": True,
                "threshold": None,
                "reason": "insufficient_positive_images",
            }
            continue
        if positive_groups < minimum_positive_groups:
            selection[name] = {
                "candidate_selected": False,
                "diagnostic_only": True,
                "threshold": None,
                "reason": "insufficient_positive_groups",
            }
            continue

        eligible = []
        for row in rows:
            precision = row.get("pixel_precision")
            recall = row.get("pixel_recall")
            if (
                precision is not None
                and recall is not None
                and precision >= minimum_precision
                and recall >= minimum_recall
            ):
                f1 = 2 * precision * recall / (precision + recall)
                eligible.append((f1, precision, row["threshold"], recall))
        if not eligible:
            selection[name] = {
                "candidate_selected": False,
                "diagnostic_only": True,
                "threshold": None,
                "reason": "precision_recall_requirements_not_met",
            }
            continue
        f1, precision, threshold, recall = max(eligible)
        selection[name] = {
            "candidate_selected": True,
            "diagnostic_only": True,
            "threshold": threshold,
            "pixel_precision": precision,
            "pixel_recall": recall,
            "f1": f1,
            "reason": None,
        }
    return selection


def acceptance_gates(
    baseline: Mapping[str, object],
    candidate: Mapping[str, object],
    *,
    candidate_name: str,
    b_candidate: Mapping[str, object] | None = None,
) -> dict[str, object]:
    """Compare final test reports; every absent or invalid evidence item fails closed."""
    gates: list[dict[str, object]] = []

    def add_gate(
        name: str,
        observed: object,
        requirement: str,
        passed: bool,
        reason: str | None = None,
    ) -> None:
        gates.append(
            {
                "name": name,
                "passed": passed,
                "observed": observed,
                "requirement": requirement,
                "reason": reason,
            }
        )

    def report_has_evidence(report: Mapping[str, object], model_kind: str) -> bool:
        record_count = report.get("record_count")
        domains = report.get("domains")
        membership = report.get("split_membership")
        record_keys = membership.get("record_keys") if isinstance(membership, Mapping) else None
        group_keys = membership.get("group_keys") if isinstance(membership, Mapping) else None
        return (
            report.get("schema_version") == 1
            and report.get("report_kind") == "saved_prediction_evaluation"
            and report.get("split") == "test"
            and isinstance(record_count, int)
            and not isinstance(record_count, bool)
            and record_count > 0
            and report.get("prediction_evidence") == "saved_model_predictions"
            and report.get("trained_model_quality_claimed") is False
            and report.get("model_kind") == model_kind
            and isinstance(report.get("evaluation_fingerprint"), str)
            and len(report["evaluation_fingerprint"]) == 64
            and all(
                character in "0123456789abcdef"
                for character in report["evaluation_fingerprint"]
            )
            and isinstance(record_keys, list)
            and len(record_keys) == record_count
            and all(isinstance(value, str) for value in record_keys)
            and isinstance(group_keys, list)
            and all(isinstance(value, str) for value in group_keys)
            and isinstance(domains, Mapping)
            and all(
                isinstance(domains.get(domain), Mapping)
                and isinstance(domains[domain].get("images"), int)
                and not isinstance(domains[domain]["images"], bool)
                and domains[domain]["images"] > 0
                and isinstance(domains[domain].get("groups"), int)
                and not isinstance(domains[domain]["groups"], bool)
                and domains[domain]["groups"] > 0
                and isinstance(domains[domain].get("valid_pixels"), int)
                and not isinstance(domains[domain]["valid_pixels"], bool)
                and domains[domain]["valid_pixels"] > 0
                for domain in ("surface", "cityscapes")
            )
            and len(group_keys)
            == sum(domains[domain]["groups"] for domain in ("surface", "cityscapes"))
            and (
                model_kind != "historical_baseline"
                or (
                    isinstance(report.get("historical_baseline_exposure_caveat"), str)
                    and bool(report["historical_baseline_exposure_caveat"])
                )
            )
        )

    baseline_evidence = report_has_evidence(baseline, "historical_baseline")
    candidate_evidence = report_has_evidence(candidate, "candidate")
    if candidate_name.upper() not in ("A", "B", "C"):
        add_gate(
            "candidate_name_supported",
            candidate_name,
            "candidate name must be A, B, or C",
            False,
            "invalid_candidate_name",
        )
        return {"passed": False, "gates": gates}
    if not baseline_evidence or not candidate_evidence:
        add_gate(
            "saved_model_test_reports_required",
            {
                "baseline_valid": baseline_evidence,
                "candidate_valid": candidate_evidence,
            },
            "use complete test reports from saved model predictions; baseline must include its historical exposure caveat",
            False,
            "missing_or_untrusted_report_evidence",
        )
        return {"passed": False, "gates": gates}

    fingerprints_match = (
        baseline.get("evaluation_fingerprint") == candidate.get("evaluation_fingerprint")
    )
    add_gate(
        "same_test_evidence",
        {
            "baseline": baseline.get("evaluation_fingerprint"),
            "candidate": candidate.get("evaluation_fingerprint"),
        },
        "baseline and candidate must use the same test records, groups, labels, and provenance",
        fingerprints_match,
        None if fingerprints_match else "test_set_or_ground_truth_mismatch",
    )

    baseline_domains = baseline.get("domains")
    candidate_domains = candidate.get("domains")
    if not isinstance(baseline_domains, Mapping) or not isinstance(candidate_domains, Mapping):
        baseline_domains = {}
        candidate_domains = {}

    def number(report: Mapping[str, object], *path: str) -> float | None:
        current: object = report
        for key in path:
            if not isinstance(current, Mapping) or key not in current:
                return None
            current = current[key]
        if isinstance(current, bool) or not isinstance(current, (int, float)):
            return None
        value = float(current)
        return value if isfinite(value) else None

    def rate(report: Mapping[str, object], *path: str) -> float | None:
        value = number(report, *path)
        return value if value is not None and 0.0 <= value <= 1.0 else None

    def device_metric(report: Mapping[str, object], key: str) -> float | None:
        performance = report.get("performance")
        if not isinstance(performance, Mapping) or performance.get("timing_source") != "device":
            return None
        if not (
            isinstance(performance.get("device_model"), str)
            and performance["device_model"]
            and isinstance(performance.get("runtime_backend"), str)
            and performance["runtime_backend"]
        ):
            return None
        count = performance.get("device_sample_count")
        if isinstance(count, bool) or not isinstance(count, int) or count <= 0:
            return None
        value = performance.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        return float(value) if isfinite(float(value)) and float(value) > 0 else None

    def same_device(first: Mapping[str, object], second: Mapping[str, object]) -> bool:
        first_performance = first.get("performance")
        second_performance = second.get("performance")
        if not isinstance(first_performance, Mapping) or not isinstance(second_performance, Mapping):
            return False
        return (
            first_performance.get("device_model") == second_performance.get("device_model")
            and first_performance.get("runtime_backend") == second_performance.get("runtime_backend")
            and first_performance.get("timing_source") == "device"
            and second_performance.get("timing_source") == "device"
        )

    baseline_surface_iou = rate(baseline_domains, "surface", "mean_iou")
    candidate_surface_iou = rate(candidate_domains, "surface", "mean_iou")
    surface_pass = (
        baseline_surface_iou is not None
        and candidate_surface_iou is not None
        and candidate_surface_iou >= baseline_surface_iou + 0.03
    )
    add_gate(
        "surface_mean_iou",
        {"baseline": baseline_surface_iou, "candidate": candidate_surface_iou},
        "candidate must improve Surface mean IoU by at least 0.03",
        surface_pass,
        None if baseline_surface_iou is not None and candidate_surface_iou is not None else "missing_or_invalid_metric",
    )

    baseline_false_safe = rate(
        baseline_domains, "surface", "nonwalkable_to_walkable_false_positive_rate"
    )
    candidate_false_safe = rate(
        candidate_domains, "surface", "nonwalkable_to_walkable_false_positive_rate"
    )
    false_safe_pass = (
        baseline_false_safe is not None
        and candidate_false_safe is not None
        and candidate_false_safe <= 0.8 * baseline_false_safe
    )
    add_gate(
        "surface_false_safe_rate",
        {"baseline": baseline_false_safe, "candidate": candidate_false_safe},
        "candidate rate must be at most 0.8 times baseline",
        false_safe_pass,
        None if baseline_false_safe is not None and candidate_false_safe is not None else "missing_or_invalid_metric",
    )

    baseline_city_iou = baseline_domains.get("cityscapes", {})
    candidate_city_iou = candidate_domains.get("cityscapes", {})
    for class_name in OBJECT_CLASS_NAMES:
        base_iou = (
            baseline_city_iou.get("object_iou", {}).get(class_name)
            if isinstance(baseline_city_iou, Mapping)
            and isinstance(baseline_city_iou.get("object_iou"), Mapping)
            else None
        )
        new_iou = (
            candidate_city_iou.get("object_iou", {}).get(class_name)
            if isinstance(candidate_city_iou, Mapping)
            and isinstance(candidate_city_iou.get("object_iou"), Mapping)
            else None
        )
        if (
            isinstance(base_iou, bool)
            or not isinstance(base_iou, (int, float))
            or not isfinite(float(base_iou))
            or not 0.0 <= float(base_iou) <= 1.0
        ):
            base_iou = None
        if (
            isinstance(new_iou, bool)
            or not isinstance(new_iou, (int, float))
            or not isfinite(float(new_iou))
            or not 0.0 <= float(new_iou) <= 1.0
        ):
            new_iou = None
        passed = base_iou is not None and new_iou is not None and float(new_iou) >= float(base_iou) - 0.01
        add_gate(
            f"cityscapes_{class_name}_iou",
            {"baseline": base_iou, "candidate": new_iou},
            "candidate object IoU loss must be no more than 0.01",
            passed,
            None if base_iou is not None and new_iou is not None else "missing_or_invalid_metric",
        )

    if candidate_name.upper() == "C":
        if b_candidate is None or not report_has_evidence(b_candidate, "candidate"):
            add_gate(
                "candidate_c_reference_b",
                None,
                "candidate C requires a candidate B report on the test split",
                False,
                "missing_or_invalid_candidate_b_report",
            )
        else:
            b_domains = b_candidate.get("domains", {})
            b_overall_iou = rate(b_candidate, "overall_mean_iou")
            c_overall_iou = rate(candidate, "overall_mean_iou")
            b_false_safe = (
                rate(b_domains, "surface", "nonwalkable_to_walkable_false_positive_rate")
                if isinstance(b_domains, Mapping)
                else None
            )
            c_surface_iou = rate(candidate_domains, "surface", "mean_iou")
            c_false_safe = rate(
                candidate_domains, "surface", "nonwalkable_to_walkable_false_positive_rate"
            )
            b_fingerprint_matches = (
                b_candidate.get("evaluation_fingerprint")
                == candidate.get("evaluation_fingerprint")
            )
            add_gate(
                "candidate_c_vs_b_mean_iou",
                {"candidate_b": b_overall_iou, "candidate_c": c_overall_iou},
                "candidate C overall mean IoU loss versus B must be no more than 0.005",
                b_fingerprint_matches
                and b_overall_iou is not None
                and c_overall_iou is not None
                and c_overall_iou >= b_overall_iou - 0.005,
                (
                    "test_set_or_ground_truth_mismatch"
                    if not b_fingerprint_matches
                    else None
                    if b_overall_iou is not None and c_overall_iou is not None
                    else "missing_or_invalid_metric"
                ),
            )
            add_gate(
                "candidate_c_vs_b_false_safe_rate",
                {"candidate_b": b_false_safe, "candidate_c": c_false_safe},
                "candidate C must not increase false-safe rate versus B",
                b_fingerprint_matches
                and b_false_safe is not None
                and c_false_safe is not None
                and c_false_safe <= b_false_safe,
                (
                    "test_set_or_ground_truth_mismatch"
                    if not b_fingerprint_matches
                    else None
                    if b_false_safe is not None and c_false_safe is not None
                    else "missing_or_invalid_metric"
                ),
            )

            b_p50 = device_metric(b_candidate, "invoke_p50_ms")
            c_p50 = device_metric(candidate, "invoke_p50_ms")
            valid_timing = (
                isinstance(b_p50, (int, float))
                and not isinstance(b_p50, bool)
                and isfinite(float(b_p50))
                and isinstance(c_p50, (int, float))
                and not isinstance(c_p50, bool)
                and isfinite(float(c_p50))
            )
            add_gate(
                "candidate_c_invoke_p50",
                {"candidate_b_ms": b_p50, "candidate_c_ms": c_p50},
                "same-device invoke p50 for C must be at most 0.8 times B",
                b_fingerprint_matches
                and valid_timing
                and same_device(b_candidate, candidate)
                and float(c_p50) <= 0.8 * float(b_p50),
                (
                    "test_set_or_ground_truth_mismatch"
                    if not b_fingerprint_matches
                    else None
                    if valid_timing and same_device(b_candidate, candidate)
                    else "missing_or_incomparable_real_device_timing"
                ),
            )
    base_p95 = device_metric(baseline, "pipeline_p95_ms")
    candidate_p95 = device_metric(candidate, "pipeline_p95_ms")
    valid_pipeline_timing = (
        isinstance(base_p95, (int, float))
        and not isinstance(base_p95, bool)
        and isfinite(float(base_p95))
        and isinstance(candidate_p95, (int, float))
        and not isinstance(candidate_p95, bool)
        and isfinite(float(candidate_p95))
    )
    add_gate(
        "full_pipeline_p95",
        {"baseline_ms": base_p95, "candidate_ms": candidate_p95},
        "same-device full-pipeline p95 must be at most 1.10 times baseline",
        fingerprints_match
        and valid_pipeline_timing
        and same_device(baseline, candidate)
        and float(candidate_p95) <= 1.10 * float(base_p95),
        (
            "test_set_or_ground_truth_mismatch"
            if not fingerprints_match
            else None
            if valid_pipeline_timing and same_device(baseline, candidate)
            else "missing_or_incomparable_real_device_timing"
        ),
    )

    return {"passed": all(gate["passed"] for gate in gates), "gates": gates}
