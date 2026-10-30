"""Evaluate saved portrait predictions without importing TensorFlow."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import sys
from copy import deepcopy
from pathlib import Path
from typing import Iterable, Mapping, Sequence

from tools.vision.training.metrics import (
    CLASS_NAMES,
    HAZARD_NAMES,
    IGNORE_INDEX,
    OBJECT_CLASS_NAMES,
    REAL_PROVENANCE,
    RISK_THRESHOLDS,
    WALKABLE_CLASS_IDS,
    StreamingConfusionMatrix,
    StreamingRiskMetrics,
    acceptance_gates,
    select_risk_thresholds,
)


REPORT_SCHEMA_VERSION = 1
HISTORICAL_BASELINE_CAVEAT = (
    "The historical model may have been exposed to the current Surface test data; "
    "its comparison is not an independent historical holdout."
)
WARNING_EVIDENCE_UNAVAILABLE = "warning_level_evidence_not_supported"
WARNING_POLICY_CONTRACT = {
    "near_roi_rows_inclusive": [230, 287],
    "minimum_corridor_occupancy": 0.01,
    "minimum_connected_component_pixels": 32,
    "required_consecutive_frames": 2,
    "warning_expiry_ms": 1500,
    "cooldown_ms": 2500,
    "session_fencing": True,
    "suppress_when_orientation_unreliable": True,
    "priority": [
        "stairs",
        "repair_zone",
        "damaged_surface",
        "speed_bump",
        "manhole",
        "grating",
        "tree_zone",
        "bike_lane",
    ],
}
WARNING_LEVEL_EVIDENCE_CONTRACT = {
    "schema_version": 1,
    "supported": False,
    "required_split": "test",
    "required_evidence_kind": "heldout_warning_event_labels",
    "required_prediction_evidence": "real_application_warning_replay",
    "minimum_event_precision": 0.90,
    "minimum_event_recall": 0.70,
    "minimum_positive_images": 50,
    "minimum_positive_groups": 5,
    "count_unit": "one warning decision per image and hazard",
    "required_counts": ["true_positives", "false_positives", "false_negatives"],
    "required_coverage_counts": ["positive_images", "positive_groups"],
    "required_distinct_ids": ["positive_image_ids", "positive_group_ids"],
    "bounded_values": {
        "counts": "nonnegative integers no greater than test record count",
        "rates": "finite values in [0, 1]",
    },
    "count_consistency": {
        "rates_derived_from_counts": True,
        "true_positives_plus_false_negatives_equals_positive_images": True,
        "positive_images_equals_unique_positive_image_ids": True,
        "positive_groups_equals_unique_positive_group_ids": True,
        "true_positives_plus_false_positives_at_most_test_record_count": True,
    },
    "required_links": [
        "evaluation_fingerprint",
        "model_sha256",
        "threshold_provenance",
        "warning_policy",
    ],
    "thresholds_frozen_from_validation": True,
    "test_data_used_for_threshold_tuning": False,
    "warning_policy": WARNING_POLICY_CONTRACT,
}


def _is_sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _validated_model_sha256(value: str | None) -> str | None:
    if value is None:
        return None
    if not _is_sha256(value):
        raise ValueError("model_sha256 must be a lowercase 64-character SHA-256 digest")
    return value


def _threshold_model_sha256(report: Mapping[str, object]) -> str | None:
    value = report.get("threshold_model_sha256")
    return value if isinstance(value, str) else None


def _read_json(path: Path) -> object:
    try:
        with path.open("r", encoding="utf-8") as file:
            return json.load(file)
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read JSON {path}: {error}") from error


def _write_json(path: Path, value: Mapping[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as file:
        json.dump(value, file, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        file.write("\n")


def iter_prediction_records(path: str | Path) -> Iterable[dict[str, object]]:
    """Yield one JSONL record at a time; gzip is selected by the `.gz` suffix."""
    source = Path(path)
    opener = gzip.open if source.suffix.lower() == ".gz" else open
    try:
        with opener(source, "rt", encoding="utf-8") as file:
            for line_number, line in enumerate(file, start=1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ValueError(
                        f"invalid JSON in {source} line {line_number}: {error}"
                    ) from error
                if not isinstance(value, dict):
                    raise ValueError(f"{source} line {line_number} must contain a JSON object")
                yield value
    except OSError as error:
        raise ValueError(f"cannot read prediction stream {source}: {error}") from error


def _required_string(record: Mapping[str, object], key: str) -> str:
    value = record.get(key)
    if not isinstance(value, str) or not value:
        raise ValueError(f"record field {key!r} must be a non-empty string")
    return value


def _int_array(record: Mapping[str, object], key: str) -> list[int]:
    value = record.get(key)
    if not isinstance(value, list) or any(
        isinstance(item, bool) or not isinstance(item, int) for item in value
    ):
        raise ValueError(f"record field {key!r} must be a flat integer array")
    return value


def _float_array(record: Mapping[str, object], key: str) -> list[float]:
    value = record.get(key)
    if not isinstance(value, list) or any(
        isinstance(item, bool) or not isinstance(item, (int, float)) for item in value
    ):
        raise ValueError(f"record field {key!r} must be a flat numeric array")
    result = [float(item) for item in value]
    if any(not math.isfinite(item) for item in result):
        raise ValueError(f"record field {key!r} must contain only finite numbers")
    return result


def _validity_array(record: Mapping[str, object], key: str) -> list[int]:
    value = record.get(key)
    if not isinstance(value, list) or any(item not in (0, 1, False, True) for item in value):
        raise ValueError(f"record field {key!r} must be a flat boolean or 0/1 array")
    return [int(item) for item in value]


def _percentile(values: Sequence[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def evaluate_records(
    records: Iterable[Mapping[str, object]],
    *,
    split: str,
    thresholds_from: Mapping[str, object] | None = None,
    historical_baseline: bool = False,
    model_sha256: str | None = None,
) -> dict[str, object]:
    """Consume prediction records and return one deterministic report."""
    model_sha256 = _validated_model_sha256(model_sha256)
    if split not in ("validation", "test"):
        raise ValueError("split must be 'validation' or 'test'")
    if split == "test" and thresholds_from is None:
        raise ValueError("test evaluation requires thresholds selected from validation")
    if thresholds_from is not None and thresholds_from.get("split") != "validation":
        raise ValueError("risk thresholds must come from a validation report")
    if thresholds_from is not None:
        if (
            thresholds_from.get("schema_version") != REPORT_SCHEMA_VERSION
            or thresholds_from.get("report_kind") != "saved_prediction_evaluation"
            or thresholds_from.get("model_kind") != "candidate"
        ):
            raise ValueError("risk thresholds must come from a candidate validation report")
        validation_model_sha256 = thresholds_from.get("model_sha256")
        if (
            model_sha256 is not None
            and isinstance(validation_model_sha256, str)
            and model_sha256 != validation_model_sha256
        ):
            raise ValueError("risk thresholds and predictions must use the same model_sha256")
        fingerprint = thresholds_from.get("evaluation_fingerprint")
        validation_record_count = thresholds_from.get("record_count")
        validation_domains = thresholds_from.get("domains")
        if (
            not isinstance(fingerprint, str)
            or len(fingerprint) != 64
            or any(character not in "0123456789abcdef" for character in fingerprint)
            or isinstance(validation_record_count, bool)
            or not isinstance(validation_record_count, int)
            or validation_record_count <= 0
            or not isinstance(validation_domains, Mapping)
        ):
            raise ValueError("validation report evidence is incomplete")
        membership = thresholds_from.get("split_membership")
        if not isinstance(membership, Mapping):
            raise ValueError("validation report has no split membership evidence")
        validation_record_keys = membership.get("record_keys")
        validation_group_keys = membership.get("group_keys")
        if (
            not isinstance(validation_record_keys, list)
            or not isinstance(validation_group_keys, list)
            or any(not isinstance(value, str) for value in validation_record_keys)
            or any(not isinstance(value, str) for value in validation_group_keys)
            or len(validation_record_keys) != validation_record_count
            or len(set(validation_record_keys)) != len(validation_record_keys)
            or not validation_group_keys
            or len(set(validation_group_keys)) != len(validation_group_keys)
            or not all(
                isinstance(domains, Mapping)
                and isinstance(domains.get("groups"), int)
                and not isinstance(domains.get("groups"), bool)
                and domains["groups"] >= 0
                for domains in validation_domains.values()
            )
            or sum(
                domains.get("groups", 0)
                for domains in validation_domains.values()
                if isinstance(domains, Mapping)
            )
            != len(validation_group_keys)
        ):
            raise ValueError("validation split membership evidence is malformed")
    else:
        validation_record_keys = []
        validation_group_keys = []

    matrices: dict[str, StreamingConfusionMatrix] = {}
    domain_images: dict[str, set[str]] = {}
    domain_groups: dict[str, set[str]] = {}
    risk_metrics: StreamingRiskMetrics | None = None
    risk_records = 0
    risk_threshold_selection: Mapping[str, object] | None = None
    if split == "validation":
        risk_metrics = StreamingRiskMetrics()
    else:
        source_selection = thresholds_from.get("risk_threshold_selection", {})
        if not isinstance(source_selection, Mapping):
            raise ValueError("validation report has no risk threshold selection")
        if set(source_selection) != set(HAZARD_NAMES):
            raise ValueError("validation risk threshold selection is incomplete")
        risk_threshold_selection = source_selection
        threshold_map: dict[str, tuple[float, ...]] = {}
        for name in HAZARD_NAMES:
            selected = source_selection.get(name, {})
            if not isinstance(selected, Mapping):
                raise ValueError(f"validation threshold selection for {name} is malformed")
            value = selected.get("threshold")
            candidate_selected = selected.get(
                "candidate_selected",
                selected.get("enabled"),
            )
            if not isinstance(candidate_selected, bool):
                raise ValueError(f"validation threshold enablement for {name} is malformed")
            if candidate_selected and (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or float(value) not in RISK_THRESHOLDS
            ):
                raise ValueError(f"enabled validation threshold for {name} is invalid")
            # Disabled classes retain a fixed 0.50 diagnostic measurement; they remain disabled.
            threshold_map[name] = (
                (float(value),)
                if candidate_selected
                and isinstance(value, (int, float))
                and not isinstance(value, bool)
                else (RISK_THRESHOLDS[0],)
            )
        risk_metrics = StreamingRiskMetrics(threshold_map)

    seen_ids: set[str] = set()
    invoke_samples: list[float] = []
    pipeline_samples: list[float] = []
    device_models: set[str] = set()
    runtime_backends: set[str] = set()
    non_device_timing_records = 0
    record_count = 0
    prediction_evidence_kinds: set[str] = set()
    fingerprint_entries: list[tuple[str, str, str, str]] = []
    split_record_keys: set[str] = set()
    split_group_keys: set[str] = set()

    for record in records:
        record_count += 1
        record_id = _required_string(record, "record_id")
        if record_id in seen_ids:
            raise ValueError(f"duplicate record_id {record_id!r}")
        seen_ids.add(record_id)
        domain = _required_string(record, "domain")
        if domain not in ("surface", "cityscapes"):
            raise ValueError(f"unsupported domain {domain!r}")
        if record.get("split") != split:
            raise ValueError(
                f"record {record_id!r} has split {record.get('split')!r}, expected {split!r}"
            )
        group_id = _required_string(record, "group_id")
        prediction_evidence = record.get("prediction_evidence")
        if prediction_evidence not in ("saved_model_predictions", "unit_test_fixture"):
            raise ValueError(
                "record field 'prediction_evidence' must be saved_model_predictions or unit_test_fixture"
            )
        prediction_evidence_kinds.add(prediction_evidence)
        truth = _int_array(record, "semantic_truth")
        prediction = _int_array(record, "semantic_prediction")
        provenance = _float_array(record, "semantic_provenance")
        if any(value not in (0.0, 0.25, REAL_PROVENANCE) for value in provenance):
            raise ValueError("semantic_provenance values must be 0.0, 0.25, or 1.0")
        matrix = matrices.setdefault(domain, StreamingConfusionMatrix())
        matrix.update(truth, prediction, provenance, ignore_index=IGNORE_INDEX)
        domain_images.setdefault(domain, set()).add(record_id)
        domain_groups.setdefault(domain, set()).add(group_id)
        record_key = f"{domain}:{record_id}"
        group_key = f"{domain}:{group_id}"
        split_record_keys.add(record_key)
        split_group_keys.add(group_key)

        if domain == "surface":
            risk_truth = _int_array(record, "risk_truth")
            risk_prediction = _float_array(record, "risk_prediction")
            risk_valid = _validity_array(record, "risk_valid")
            expected_risk_values = len(truth) * len(HAZARD_NAMES)
            if not (
                len(risk_truth) == len(risk_prediction) == len(risk_valid) == expected_risk_values
            ):
                raise ValueError(
                    f"record {record_id!r} risk arrays must have {expected_risk_values} "
                    "channel-last values"
                )
            risk_metrics.update(
                risk_truth,
                risk_prediction,
                risk_valid,
                image_id=record_id,
                group_id=group_id,
            )
            risk_records += 1
        else:
            risk_truth = []
            risk_valid = []

        fingerprint_payload = {
            "domain": domain,
            "record_id": record_id,
            "group_id": group_id,
            "semantic_truth": truth,
            "semantic_provenance": provenance,
            "risk_truth": risk_truth,
            "risk_valid": risk_valid,
        }
        fingerprint_json = json.dumps(
            fingerprint_payload,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        fingerprint_entries.append(
            (domain, record_id, group_id, hashlib.sha256(fingerprint_json).hexdigest())
        )

        timings = record.get("timing_ms")
        if timings is not None:
            if not isinstance(timings, Mapping):
                raise ValueError(f"record {record_id!r} timing_ms must be an object")
            invoke_ms = timings.get("invoke")
            pipeline_ms = timings.get("pipeline")
            if (
                isinstance(invoke_ms, bool)
                or not isinstance(invoke_ms, (int, float))
                or isinstance(pipeline_ms, bool)
                or not isinstance(pipeline_ms, (int, float))
                or not math.isfinite(float(invoke_ms))
                or not math.isfinite(float(pipeline_ms))
                or float(invoke_ms) <= 0
                or float(pipeline_ms) <= 0
            ):
                raise ValueError(f"record {record_id!r} timing values must be finite positive ms")
            if record.get("timing_source") == "device":
                device_model = record.get("device_model")
                runtime_backend = record.get("runtime_backend")
                if (
                    not isinstance(device_model, str)
                    or not device_model.strip()
                    or not isinstance(runtime_backend, str)
                    or not runtime_backend.strip()
                ):
                    raise ValueError(
                        f"record {record_id!r} device timing requires device_model and runtime_backend"
                    )
                invoke_samples.append(float(invoke_ms))
                pipeline_samples.append(float(pipeline_ms))
                device_models.add(device_model.strip())
                runtime_backends.add(runtime_backend.strip())
            else:
                non_device_timing_records += 1

    if not record_count:
        raise ValueError("prediction stream contains no records")
    if not matrices:
        raise ValueError("prediction stream contains no semantic metrics")
    overlapping_records = split_record_keys.intersection(validation_record_keys)
    overlapping_groups = split_group_keys.intersection(validation_group_keys)
    if overlapping_records or overlapping_groups:
        raise ValueError(
            "validation and test splits overlap in record IDs or groups "
            f"({len(overlapping_records)} records, {len(overlapping_groups)} groups)"
        )

    domains = {
        domain: {
            "images": len(domain_images[domain]),
            "groups": len(domain_groups[domain]),
            **matrices[domain].summary(),
        }
        for domain in sorted(matrices)
    }
    domain_mious = [
        domain_report["mean_iou"]
        for domain_report in domains.values()
        if domain_report["mean_iou"] is not None
    ]

    risk_report: dict[str, object] = {
        "annotated_records": risk_records,
        "metrics": risk_metrics.summary(),
    }
    if split == "validation":
        selection = select_risk_thresholds(risk_report["metrics"])
        risk_report["threshold_selection"] = selection
        risk_threshold_selection = selection
    else:
        validation_selection = dict(risk_threshold_selection or {})
        heldout_selection: dict[str, object] = {}
        for name in HAZARD_NAMES:
            validation_result = validation_selection[name]
            test_metrics = risk_report["metrics"][name]
            test_row = test_metrics["thresholds"][0]
            validation_candidate_selected = validation_result.get(
                "candidate_selected",
                validation_result.get("enabled"),
            )
            if not validation_candidate_selected:
                heldout_selection[name] = {
                    "candidate_selected": False,
                    "diagnostic_only": True,
                    "threshold": None,
                    "reason": "disabled_on_validation",
                    "validation_threshold": None,
                }
                continue
            precision = test_row["pixel_precision"]
            recall = test_row["pixel_recall"]
            images = test_metrics["positive_images"]
            groups = test_metrics["positive_groups"]
            enough_images = images >= 50
            enough_groups = groups >= 5
            meets_rates = (
                precision is not None
                and recall is not None
                and precision >= 0.90
                and recall >= 0.70
            )
            reason = (
                None
                if enough_images and enough_groups and meets_rates
                else "insufficient_test_positive_images"
                if not enough_images
                else "insufficient_test_positive_groups"
                if not enough_groups
                else "test_precision_recall_requirements_not_met"
            )
            heldout_selection[name] = {
                "candidate_selected": reason is None,
                "diagnostic_only": True,
                "threshold": validation_result["threshold"] if reason is None else None,
                "validation_threshold": validation_result["threshold"],
                "validation_candidate_selected": True,
                "pixel_precision": precision,
                "pixel_recall": recall,
                "test_positive_images": images,
                "test_positive_groups": groups,
                "reason": reason,
            }
        risk_report["validation_threshold_selection"] = validation_selection
        risk_report["threshold_selection"] = heldout_selection
        risk_threshold_selection = heldout_selection

    fingerprint_hasher = hashlib.sha256()
    for domain, record_id, group_id, record_digest in sorted(fingerprint_entries):
        fingerprint_hasher.update(
            f"{domain}\0{record_id}\0{group_id}\0{record_digest}\n".encode("utf-8")
        )
    prediction_evidence = (
        next(iter(prediction_evidence_kinds))
        if len(prediction_evidence_kinds) == 1
        else "mixed"
    )

    device_sample_count = len(invoke_samples)
    if (
        device_sample_count
        and not non_device_timing_records
        and len(device_models) == 1
        and len(runtime_backends) == 1
    ):
        timing_source = "device"
    elif device_sample_count:
        timing_source = "mixed"
    else:
        timing_source = "missing" if not non_device_timing_records else "non_device"

    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "report_kind": "saved_prediction_evaluation",
        "split": split,
        "record_count": record_count,
        "prediction_evidence": prediction_evidence,
        "evaluation_fingerprint": fingerprint_hasher.hexdigest(),
        "split_membership": {
            "record_keys": sorted(split_record_keys),
            "group_keys": sorted(split_group_keys),
        },
        "domains": domains,
        "overall_mean_iou": (
            sum(domain_mious) / len(domain_mious) if domain_mious else None
        ),
        "risk": risk_report,
        "risk_threshold_selection": dict(risk_threshold_selection or {}),
        "automatic_warning_enablement": False,
        "automatic_warning_enablement_reason": WARNING_EVIDENCE_UNAVAILABLE,
        "warning_level_evidence_contract": deepcopy(WARNING_LEVEL_EVIDENCE_CONTRACT),
        "model_sha256": model_sha256,
        "threshold_model_sha256": (
            thresholds_from.get("model_sha256")
            if thresholds_from is not None
            else model_sha256
        ),
        "threshold_provenance": {
            "split": "validation",
            "evaluation_fingerprint": (
                thresholds_from.get("evaluation_fingerprint")
                if thresholds_from is not None
                else fingerprint_hasher.hexdigest()
            ),
            "model_sha256": (
                thresholds_from.get("model_sha256")
                if thresholds_from is not None
                else model_sha256
            ),
        },
        "performance": {
            "timing_source": timing_source,
            "device_sample_count": device_sample_count,
            "non_device_timing_record_count": non_device_timing_records,
            "invoke_p50_ms": _percentile(invoke_samples, 0.50),
            "pipeline_p95_ms": _percentile(pipeline_samples, 0.95),
            "device_model": next(iter(device_models)) if len(device_models) == 1 else None,
            "runtime_backend": next(iter(runtime_backends)) if len(runtime_backends) == 1 else None,
        },
        "evaluation_contract": {
            "class_names": list(CLASS_NAMES),
            "walkable_class_ids": sorted(WALKABLE_CLASS_IDS),
            "walkable_class_names": [CLASS_NAMES[index] for index in sorted(WALKABLE_CLASS_IDS)],
            "object_class_names": list(OBJECT_CLASS_NAMES),
            "ignore_index": IGNORE_INDEX,
            "semantic_ground_truth_provenance": "only provenance weight 1.0 is counted",
            "false_safe_denominator": "all valid real-label pixels whose class is not walkable",
            "risk_threshold_selection_split": "validation only; pixel-diagnostic candidate thresholds",
            "automatic_warning_evidence_supported": False,
        },
        "historical_baseline_exposure_caveat": (
            HISTORICAL_BASELINE_CAVEAT if historical_baseline else None
        ),
        "trained_model_quality_claimed": False,
        "model_kind": "historical_baseline" if historical_baseline else "candidate",
    }


def load_validation_report(path: str | Path) -> dict[str, object]:
    value = _read_json(Path(path))
    if not isinstance(value, dict) or value.get("split") != "validation":
        raise ValueError("threshold report must be a validation report")
    if value.get("schema_version") != REPORT_SCHEMA_VERSION:
        raise ValueError("unsupported validation report schema_version")
    if (
        value.get("report_kind") != "saved_prediction_evaluation"
        or value.get("model_kind") != "candidate"
        or value.get("prediction_evidence") != "saved_model_predictions"
    ):
        raise ValueError("threshold report must be a candidate evaluation report")
    membership = value.get("split_membership")
    record_count = value.get("record_count")
    fingerprint = value.get("evaluation_fingerprint")
    if (
        not isinstance(membership, Mapping)
        or not isinstance(membership.get("record_keys"), list)
        or not isinstance(membership.get("group_keys"), list)
        or isinstance(record_count, bool)
        or not isinstance(record_count, int)
        or record_count <= 0
        or len(membership["record_keys"]) != record_count
        or any(not isinstance(value, str) for value in membership["record_keys"])
        or len(set(membership["record_keys"])) != record_count
        or not membership["group_keys"]
        or any(not isinstance(value, str) for value in membership["group_keys"])
        or len(set(membership["group_keys"])) != len(membership["group_keys"])
        or not isinstance(fingerprint, str)
        or len(fingerprint) != 64
        or any(character not in "0123456789abcdef" for character in fingerprint)
    ):
        raise ValueError("validation report membership/fingerprint evidence is incomplete")
    return value


def _load_report(path: str | Path) -> dict[str, object]:
    value = _read_json(Path(path))
    if not isinstance(value, dict):
        raise ValueError(f"report {path} must contain a JSON object")
    if value.get("schema_version") != REPORT_SCHEMA_VERSION:
        raise ValueError(f"report {path} has an unsupported schema_version")
    return value


def _evaluate_command(args: argparse.Namespace) -> dict[str, object]:
    thresholds_from = load_validation_report(args.thresholds_from) if args.thresholds_from else None
    report = evaluate_records(
        iter_prediction_records(args.predictions),
        split=args.split,
        thresholds_from=thresholds_from,
        historical_baseline=args.model_kind == "historical_baseline",
        model_sha256=args.model_sha256,
    )
    report["model_kind"] = args.model_kind
    if args.output:
        _write_json(Path(args.output), report)
    return report


def _compare_command(args: argparse.Namespace) -> dict[str, object]:
    baseline = _load_report(args.baseline)
    candidate = _load_report(args.candidate)
    b_candidate = _load_report(args.b_candidate) if args.b_candidate else None
    gates = acceptance_gates(
        baseline,
        candidate,
        candidate_name=args.candidate_name,
        b_candidate=b_candidate,
    )
    result = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "comparison": f"{args.candidate_name.upper()}_against_baseline",
        **gates,
        "semantic_model_acceptance": {
            "passed": gates["passed"],
            "gates": gates["gates"],
        },
        "warning_enablement": {
            "automatic_warning_enablement": False,
            "reason": WARNING_EVIDENCE_UNAVAILABLE,
            "model_sha256": candidate.get("model_sha256"),
            "threshold_model_sha256": _threshold_model_sha256(candidate),
            "model_hash_match": (
                _is_sha256(candidate.get("model_sha256"))
                and _is_sha256(_threshold_model_sha256(candidate))
                and candidate.get("model_sha256")
                == _threshold_model_sha256(candidate)
            ),
        },
        "automatic_warning_enablement": False,
        "trained_model_quality_claimed": False,
    }
    if args.output:
        _write_json(Path(args.output), result)
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Stream saved native portrait predictions into evaluation reports."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    evaluate_parser = subparsers.add_parser(
        "evaluate",
        help="evaluate JSONL predictions on validation or held-out test data",
    )
    evaluate_parser.add_argument("--predictions", required=True, help="saved prediction JSONL or .jsonl.gz")
    evaluate_parser.add_argument("--split", choices=("validation", "test"), required=True)
    evaluate_parser.add_argument(
        "--thresholds-from",
        help="validation report supplying frozen risk thresholds (required for test)",
    )
    evaluate_parser.add_argument(
        "--model-kind",
        choices=("candidate", "historical_baseline"),
        default="candidate",
    )
    evaluate_parser.add_argument(
        "--model-sha256",
        help="lowercase SHA-256 of the model that produced these predictions",
    )
    evaluate_parser.add_argument("--output", help="write deterministic JSON report to this path")

    compare_parser = subparsers.add_parser(
        "compare",
        help="apply fail-closed acceptance gates to final test reports",
    )
    compare_parser.add_argument("--baseline", required=True)
    compare_parser.add_argument("--candidate", required=True)
    compare_parser.add_argument("--candidate-name", choices=("A", "B", "C"), required=True)
    compare_parser.add_argument("--b-candidate", help="required candidate B test report when comparing C")
    compare_parser.add_argument("--output", help="write deterministic JSON gate report to this path")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "evaluate":
            report = _evaluate_command(args)
        else:
            report = _compare_command(args)
        if not args.output:
            json.dump(report, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
            sys.stdout.write("\n")
        return 0
    except (OSError, ValueError) as error:
        print(f"evaluation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
