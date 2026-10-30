#!/usr/bin/env python3
"""Score the four-arm CamVid portrait-model evaluation produced on Android."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from tools.vision.evaluation.evaluate_orientation import (  # noqa: E402
    ARIA_CLASSES,
    ARIA_COLORS_RGB,
    CAMVID_TO_ARIA,
    confusion_metrics,
    confusion_metrics_from_matrix,
    inverse_prediction,
    letterbox,
    portrait_center_crop,
    sha256,
    timing_summary,
)

DEFAULT_ROOT = ROOT / "build" / "vision-portrait-evaluation"
FIXTURE_ROOT = ROOT / "build" / "vision-orientation-evaluation" / "fixtures"
BASELINE_MODEL_SHA256 = "84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847"
ARMS = (
    "legacy_bottom_crop",
    "portrait_cw90_full_frame_letterbox",
    "upright_full_frame_letterbox",
    "portrait_model_upright_letterbox",
)
ORIENTATIONS = ("landscape", "portrait_center_crop")
MODEL_KEYS = {
    ARMS[0]: "legacy",
    ARMS[1]: "legacy",
    ARMS[2]: "legacy",
    ARMS[3]: "portrait",
}


def expected_geometry(
    sample: Image.Image,
    arm: str,
    model_width: int,
    model_height: int,
    offset_x: int,
    offset_y: int,
) -> tuple[tuple[int, int, int, int], tuple[int, int, int, int], int, tuple[int, int]]:
    source_rect = (offset_x, offset_y, sample.width, sample.height)
    if arm == ARMS[0]:
        crop_height = (sample.width * model_height) // model_width
        if crop_height <= sample.height:
            crop = (0, sample.height - crop_height, sample.width, crop_height)
        else:
            crop_width = (sample.height * model_width) // model_height
            crop = ((sample.width - crop_width) // 2, 0, crop_width, sample.height)
        source_rect = (offset_x + crop[0], offset_y + crop[1], crop[2], crop[3])
        content = (0, 0, model_width, model_height)
        inverse_rotation = 0
        production_crop = crop
    else:
        model_oriented = sample
        inverse_rotation = 0
        if arm == ARMS[1] and sample.height > sample.width:
            model_oriented = sample.transpose(Image.Transpose.ROTATE_270)
            inverse_rotation = 270
        if arm not in ARMS[1:]:
            raise ValueError(f"Unknown strategy {arm}")
        _, content = letterbox(model_oriented, (model_width, model_height))
        if model_oriented is not sample:
            model_oriented.close()
        production_crop = (0, 0, sample.width, sample.height)
    return content, source_rect, inverse_rotation, production_crop


def _validate_audit(model: dict, expected_hash: str) -> None:
    audit = model.get("audit")
    if not isinstance(audit, dict):
        raise ValueError("Portrait model audit JSON is missing from run.json")
    actual_hash = model.get("sha256")
    if not isinstance(actual_hash, str) or len(actual_hash) != 64:
        raise ValueError("Portrait model SHA-256 is missing from run.json")
    if actual_hash.lower() != expected_hash.lower():
        raise ValueError("Portrait model hash differs from its Android run metadata")
    if not isinstance(model.get("auditSha256"), str) or len(model["auditSha256"]) != 64:
        raise ValueError("Portrait model audit SHA-256 is missing from run.json")
    audit_text = json.dumps(audit, sort_keys=True).lower()
    if actual_hash.lower() not in audit_text:
        raise ValueError("Portrait audit does not identify the evaluated model hash")


def _read_inputs(root: Path) -> tuple[dict, dict, dict[str, dict], dict[str, dict]]:
    manifest_path = FIXTURE_ROOT / "manifest.json"
    run_path = root / "results" / "run.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    run = json.loads(run_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 2 or len(manifest.get("frames", [])) != 8:
        raise ValueError("Expected the existing schema-2 manifest with eight CamVid scenes")
    if run.get("schemaVersion") != 1 or run.get("sourceSceneCount") != 8:
        raise ValueError("Expected a complete portrait evaluation run with eight source scenes")
    models = run.get("models", {})
    legacy = models.get("legacy", {})
    portrait = models.get("portrait", {})
    if legacy.get("sha256") != BASELINE_MODEL_SHA256:
        raise ValueError("Legacy arms were not measured with the pinned optimized baseline model")
    if legacy.get("inputShape") != [1, 288, 512, 3] or legacy.get("outputShape") != [1, 288, 512, 7]:
        raise ValueError("Legacy model does not have the expected landscape NHWC tensor shapes")
    if portrait.get("inputShape") != [1, 512, 288, 3] or portrait.get("outputShape") != [1, 512, 288, 7]:
        raise ValueError("Portrait model was not invoked with [1,512,288,3] and [1,512,288,7]")
    _validate_audit(portrait, portrait["sha256"])
    frames = {frame["id"]: frame for frame in run.get("frames", [])}
    expected = {
        f"{frame['id']}--{orientation}"
        for frame in manifest["frames"]
        for orientation in ORIENTATIONS
    }
    if len(frames) != len(run.get("frames", [])) or set(frames) != expected:
        raise ValueError("Device run must include exactly both orientations for all eight scenes")
    return manifest, run, frames, models


def _mapped_ground_truth(fixture: dict) -> tuple[Image.Image, np.ndarray, Image.Image]:
    source = Image.open(FIXTURE_ROOT / fixture["sourceImage"]).convert("RGB")
    label_file = FIXTURE_ROOT / fixture["sourceLabel"]
    labels = CAMVID_TO_ARIA[np.asarray(Image.open(label_file).convert("L"), dtype=np.uint8)]
    if source.size != (fixture["width"], fixture["height"]):
        raise ValueError(f"Source dimensions changed for {fixture['id']}")
    for name_key, hash_key in (
        ("sourceImage", "imageSha256"),
        ("sourceLabel", "labelSha256"),
        ("portraitImage", "portraitImageSha256"),
        ("portraitLabel", "portraitLabelSha256"),
    ):
        if sha256((FIXTURE_ROOT / fixture[name_key]).read_bytes()) != fixture[hash_key]:
            raise ValueError(f"Fixture checksum mismatch for {fixture['id']}/{fixture[name_key]}")
    portrait, crop = portrait_center_crop(source)
    expected = fixture["portraitCropRect"]
    if list(crop) != expected:
        raise ValueError(f"Fixture portrait geometry changed for {fixture['id']}")
    portrait_fixture = Image.open(FIXTURE_ROOT / fixture["portraitImage"]).convert("RGB")
    portrait_labels = Image.open(FIXTURE_ROOT / fixture["portraitLabel"]).convert("L")
    source_labels = Image.open(label_file).convert("L")
    expected_labels, label_crop = portrait_center_crop(source_labels)
    if crop != label_crop:
        raise ValueError(f"Image and label portrait crops differ for {fixture['id']}")
    if not np.array_equal(np.asarray(portrait), np.asarray(portrait_fixture)):
        raise ValueError(f"Portrait fixture is not the upright source crop for {fixture['id']}")
    if not np.array_equal(np.asarray(expected_labels), np.asarray(portrait_labels)):
        raise ValueError(f"Portrait labels are not the matching source crop for {fixture['id']}")
    portrait_fixture.close()
    portrait_labels.close()
    source_labels.close()
    expected_labels.close()
    return source, labels, portrait


def _overlay(source: Image.Image, prediction: np.ndarray, visible: np.ndarray) -> Image.Image:
    rgb = np.asarray(source.convert("RGB"), dtype=np.uint8)
    class_colors = ARIA_COLORS_RGB[np.clip(prediction, 0, len(ARIA_CLASSES) - 1)]
    blended = (rgb.astype(np.float32) * 0.55 + class_colors.astype(np.float32) * 0.45).astype(np.uint8)
    result = rgb.copy()
    result[visible] = blended[visible]
    return Image.fromarray(result)


def _resize_cell(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    image = image.convert("RGB")
    image.thumbnail(size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", size, (242, 242, 242))
    canvas.paste(image, ((size[0] - image.width) // 2, (size[1] - image.height) // 2))
    return canvas


def _contact_sheet(
    root: Path,
    fixture: dict,
    orientation: str,
    source: Image.Image,
    sample: Image.Image,
    ground_truth: np.ndarray,
    inverses: dict[str, tuple[np.ndarray, np.ndarray]],
    loaded: dict[str, dict],
) -> str:
    cells: list[tuple[str, Image.Image]] = [
        ("Source scene", source),
        ("Orientation sample", sample),
        ("CamVid labels", _overlay(source, ground_truth, ground_truth != 255)),
    ]
    result_dir = root / "results"
    titles = (
        "Legacy model input",
        "Legacy mapped predictions",
        "Rotated letterbox input",
        "Rotated mapped predictions",
        "Upright letterbox input",
        "Upright mapped predictions",
        "Portrait model input",
        "Portrait mapped predictions",
    )
    for index, arm in enumerate(ARMS):
        item = loaded[arm]
        cells.append((titles[index * 2], Image.open(result_dir / item["inputImage"]).convert("RGB")))
        cells.append((titles[index * 2 + 1], _overlay(source, *inverses[arm])))

    cell_width, cell_height, title_height, columns = 320, 240, 26, 4
    rows = math.ceil(len(cells) / columns)
    sheet = Image.new("RGB", (columns * cell_width, rows * (cell_height + title_height)), "white")
    draw = ImageDraw.Draw(sheet)
    for index, (title, image) in enumerate(cells):
        row, column = divmod(index, columns)
        x, y = column * cell_width, row * (cell_height + title_height)
        draw.text((x + 4, y + 5), title, fill=(0, 0, 0), font=ImageFont.load_default())
        sheet.paste(_resize_cell(image, (cell_width, cell_height)), (x, y + title_height))
    path = result_dir / "contact_sheets" / f"{fixture['id']}--{orientation}.jpg"
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path, quality=90)
    return str(path.relative_to(root)).replace("\\", "/")


def summarize(root: Path) -> None:
    started = time.perf_counter()
    manifest, run, run_frames, models = _read_inputs(root)
    confusion_full = {
        (orientation, arm): np.zeros((len(ARIA_CLASSES), len(ARIA_CLASSES)), dtype=np.int64)
        for orientation in ORIENTATIONS
        for arm in ARMS
    }
    confusion_common = {
        (orientation, arm): np.zeros((len(ARIA_CLASSES), len(ARIA_CLASSES)), dtype=np.int64)
        for orientation in ORIENTATIONS
        for arm in ARMS
    }
    invoke_values = {(orientation, arm): [] for orientation in ORIENTATIONS for arm in ARMS}
    argmax_values = {(orientation, arm): [] for orientation in ORIENTATIONS for arm in ARMS}
    transform_values = {(orientation, arm): [] for orientation in ORIENTATIONS for arm in ARMS}
    frame_reports = []
    paired_deltas = []
    landscape_controls = []

    for fixture in manifest["frames"]:
        source, ground_truth, portrait = _mapped_ground_truth(fixture)
        left, top, width, height = fixture["portraitCropRect"]
        for orientation in ORIENTATIONS:
            device_frame = run_frames[f"{fixture['id']}--{orientation}"]
            if device_frame["baseFrameId"] != fixture["id"] or device_frame["orientation"] != orientation:
                raise ValueError(f"Device fixture identity mismatch: {fixture['id']}--{orientation}")
            sample = source if orientation == "landscape" else portrait
            offset = (0, 0) if orientation == "landscape" else (left, top)
            loaded = {item["arm"]: item for item in device_frame.get("strategies", [])}
            if len(loaded) != len(device_frame.get("strategies", [])) or set(loaded) != set(ARMS):
                raise ValueError(f"{device_frame['id']} does not contain exactly four unique arms")
            inverses: dict[str, tuple[np.ndarray, np.ndarray]] = {}
            input_pixels: dict[str, np.ndarray] = {}
            prediction_pixels: dict[str, np.ndarray] = {}
            for arm in ARMS:
                item = loaded[arm]
                model = models[MODEL_KEYS[arm]]
                model_width, model_height = model["width"], model["height"]
                if item["modelWidth"] != model_width or item["modelHeight"] != model_height:
                    raise ValueError(f"Model dimensions differ from tensor shape for {device_frame['id']}/{arm}")
                input_path = root / "results" / item["inputImage"]
                prediction_path = root / "results" / item["predictionImage"]
                if not input_path.is_file() or not prediction_path.is_file():
                    raise FileNotFoundError(f"Missing device output for {device_frame['id']}/{arm}")
                input_image = Image.open(input_path).convert("RGB")
                prediction = Image.open(prediction_path).convert("L")
                if input_image.size != (model_width, model_height) or prediction.size != (model_width, model_height):
                    raise ValueError(
                        f"Unexpected {arm} output size {input_image.size}/{prediction.size}; "
                        f"expected {(model_width, model_height)}"
                    )
                input_pixels[arm] = np.asarray(input_image)
                prediction_pixels[arm] = np.asarray(prediction)
                model_content = tuple(item["modelContentRect"])
                source_rect = tuple(item["sourceRect"])
                inverses[arm] = inverse_prediction(
                    prediction,
                    model_content,
                    source_rect,
                    source.size,
                    item["inverseRotationDegrees"],
                )
                expected_content, expected_source, expected_rotation, production_crop = expected_geometry(
                    sample,
                    arm,
                    model_width,
                    model_height,
                    offset[0],
                    offset[1],
                )
                if model_content != expected_content or source_rect != expected_source:
                    raise ValueError(f"Unexpected source/content geometry for {device_frame['id']}/{arm}")
                if item["inverseRotationDegrees"] != expected_rotation:
                    raise ValueError(f"Unexpected inverse rotation for {device_frame['id']}/{arm}")
                transform = item["productionTransform"]
                expected_strategy = {
                    ARMS[0]: "LEGACY_CROP",
                    ARMS[1]: "FULL_FRAME_ROTATED_LANDSCAPE",
                    ARMS[2]: "FULL_FRAME_UPRIGHT_LETTERBOX",
                    ARMS[3]: "FULL_FRAME_UPRIGHT_LETTERBOX",
                }[arm]
                expected_rotated = arm == ARMS[1] and sample.height > sample.width
                if (
                    transform["strategy"] != expected_strategy
                    or transform["bufferWidth"] != sample.width
                    or transform["bufferHeight"] != sample.height
                    or transform["rotationDegrees"] != 0
                    or transform["uprightWidth"] != sample.width
                    or transform["uprightHeight"] != sample.height
                    or tuple(transform["cropRect"]) != production_crop
                    or tuple(transform["modelContentRect"]) != model_content
                    or transform["isRotatedClockwise"] != expected_rotated
                ):
                    raise ValueError(f"Production geometry metadata mismatch for {device_frame['id']}/{arm}")
                invoke_times = item["invokeTimesNanos"]
                argmax_times = item["rawArgmaxPostprocessTimesNanos"]
                if len(invoke_times) != run["measuredRunsPerInput"] or len(argmax_times) != len(invoke_times):
                    raise ValueError(f"Missing five invoke/argmax measurements for {device_frame['id']}/{arm}")
                invoke_values[(orientation, arm)].extend(value / 1e6 for value in invoke_times)
                argmax_values[(orientation, arm)].extend(value / 1e6 for value in argmax_times)
                transform_values[(orientation, arm)].append(item["transformNanos"] / 1e6)

            label_valid = ground_truth != 255
            common_visible = np.logical_and.reduce([inverses[arm][1] for arm in ARMS])
            common_valid = common_visible & label_valid
            mapped_label_count = int(label_valid.sum())
            strategy_reports = {}
            arm_scores = {}
            for arm in ARMS:
                prediction, visible = inverses[arm]
                full_metrics = confusion_metrics(ground_truth, prediction, visible & label_valid)
                common_metrics = confusion_metrics(ground_truth, prediction, common_valid)
                confusion_full[(orientation, arm)] += np.asarray(full_metrics["confusionMatrix"], dtype=np.int64)
                confusion_common[(orientation, arm)] += np.asarray(common_metrics["confusionMatrix"], dtype=np.int64)
                arm_scores[arm] = common_metrics["macroIoUValidMappedClasses"]
                strategy_reports[arm] = {
                    "modelKey": MODEL_KEYS[arm],
                    "fullField": full_metrics,
                    "commonVisibleRegion": common_metrics,
                    "coverageOfAllMappedSourcePixels": (
                        full_metrics["validPixels"] / mapped_label_count if mapped_label_count else None
                    ),
                    "invokeTimesMs": [value / 1e6 for value in loaded[arm]["invokeTimesNanos"]],
                    "rawArgmaxPostprocessTimesMs": [
                        value / 1e6 for value in loaded[arm]["rawArgmaxPostprocessTimesNanos"]
                    ],
                    "transformMs": loaded[arm]["transformNanos"] / 1e6,
                }

            sheet = _contact_sheet(
                root,
                fixture,
                orientation,
                source,
                sample,
                ground_truth,
                inverses,
                loaded,
            )
            frame_reports.append(
                {
                    "id": device_frame["id"],
                    "baseFrameId": fixture["id"],
                    "orientation": orientation,
                    "width": sample.width,
                    "height": sample.height,
                    "mappedGroundTruthPixels": mapped_label_count,
                    "commonVisibleMappedPixels": int(common_valid.sum()),
                    "commonRegionCoverage": int(common_valid.sum()) / mapped_label_count if mapped_label_count else None,
                    "strategies": strategy_reports,
                    "contactSheet": sheet,
                }
            )
            baseline_score = arm_scores[ARMS[0]]
            for arm in ARMS[1:]:
                score = arm_scores[arm]
                paired_deltas.append(
                    {
                        "fixtureId": device_frame["id"],
                        "orientation": orientation,
                        "arm": arm,
                        "commonRegionMiouDeltaVsLegacy": (
                            score - baseline_score if score is not None and baseline_score is not None else None
                        ),
                    }
                )
            if orientation == "landscape":
                controls_equal = np.array_equal(input_pixels[ARMS[1]], input_pixels[ARMS[2]])
                masks_equal = np.array_equal(prediction_pixels[ARMS[1]], prediction_pixels[ARMS[2]])
                if not controls_equal or not masks_equal:
                    raise ValueError(f"Landscape old-model controls differ for {fixture['id']}")
                landscape_controls.append(
                    {
                        "baseFrameId": fixture["id"],
                        "modelInputsEqual": controls_equal,
                        "predictionMasksEqual": masks_equal,
                    }
                )
        portrait.close()
        source.close()

    totals = {}
    for orientation in ORIENTATIONS:
        totals[orientation] = {}
        for arm in ARMS:
            totals[orientation][arm] = {
                "modelKey": MODEL_KEYS[arm],
                "fullField": confusion_metrics_from_matrix(confusion_full[(orientation, arm)]),
                "commonVisibleRegion": confusion_metrics_from_matrix(confusion_common[(orientation, arm)]),
                "deviceModelInvokeTimingMs": timing_summary(invoke_values[(orientation, arm)]),
                "deviceRawArgmaxPostprocessTimingMs": timing_summary(argmax_values[(orientation, arm)]),
                "deviceTransformTimingMs": timing_summary(transform_values[(orientation, arm)]),
                "timingScope": (
                    "Pixel 3 LiteRT raw interpreter invoke, raw per-pixel argmax, or bitmap transform plus "
                    "normalized RGB conversion; no output-buffer copy in invoke timing and no CameraX YUV preprocessing"
                ),
            }

    summary = {
        "schemaVersion": 1,
        "dataset": {
            "name": manifest["dataset"],
            "mirror": manifest["datasetMirror"],
            "commit": manifest["datasetCommit"],
            "split": manifest["split"],
            "sourceScenes": len(manifest["frames"]),
            "orientationsPerScene": 2,
            "armsPerOrientation": len(ARMS),
            "conditions": len(frame_reports) * len(ARMS),
            "portraitDerivation": "Existing upright 9:16 center crop; no source or label rotation",
        },
        "device": {
            "model": run["deviceModel"],
            "sdkInt": run["sdkInt"],
            "models": models,
            "portraitGpuParityStatus": run["portraitGpuParityStatus"],
            "portraitGpuParityRejectReason": run.get("portraitGpuParityRejectReason"),
            "portraitGpuParityThresholds": run["portraitGpuParityThresholds"],
            "portraitGpuParity": run["portraitGpuParity"],
            "cpuThreads": run["cpuThreads"],
            "warmupRunsPerInput": run["warmupRunsPerInput"],
            "measuredRunsPerInput": run["measuredRunsPerInput"],
        },
        "labelMapping": {
            "camvidToAria": {
                "Sky, Building, Tree, Fence": "other (0)",
                "Road": "road (1)",
                "Sidewalk/Pavement": "sidewalk (2)",
                "Pedestrian": "person (4)",
                "Car": "vehicle (5)",
                "Pole, SignSymbol": "pole_sign (6)",
                "Void (255)": "ignored",
                "Bicyclist (10)": "excluded as incompatible with vehicle",
                "crosswalk (3)": "unavailable as a separate CamVid class; excluded",
            },
            "macroIoU": "Mean only over classes with compatible ground-truth support; crosswalk is excluded.",
        },
        "coverageDefinition": (
            "For each arm, full-field coverage is valid mapped source-label pixels visible after inverse mapping "
            "divided by all mapped source-label pixels. Common-region metrics use the intersection of valid "
            "source-coordinate pixels visible to all four arms, excluding CamVid void labels."
        ),
        "orientationTotals": totals,
        "pairedPerSceneDeltasVsLegacy": paired_deltas,
        "landscapeControlEquivalence": landscape_controls,
        "frames": frame_reports,
        "timingLimitations": [
            "Arms ran in a fixed order on a small sample; timing is descriptive, not a statistically supported speedup claim.",
            "Only five invoke and argmax measurements per condition were collected after three warmups.",
            "Transform timing includes bitmap geometry and RGB float conversion but excludes CameraX YUV conversion.",
        ],
        "recognitionLimitations": [
            "The 8 portrait samples are derived upright center crops, not native portrait recordings.",
            "CamVid categories do not fully align with Aria's learned classes; see the label mapping and exclusions.",
            "Results measure these model outputs against this small labeled subset and do not establish trained accuracy.",
        ],
        "hostPostprocessingMs": (time.perf_counter() - started) * 1000.0,
    }
    result_path = root / "results" / "summary.json"
    result_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(result_path)
    for orientation, arms in totals.items():
        for arm, report in arms.items():
            timing = report["deviceModelInvokeTimingMs"]
            argmax = report["deviceRawArgmaxPostprocessTimingMs"]
            miou = report["fullField"]["macroIoUValidMappedClasses"]
            print(
                f"{orientation}/{arm}: full mIoU={miou:.3f}; "
                f"invoke p50/p95={timing['p50']:.1f}/{timing['p95']:.1f} ms; "
                f"argmax p50/p95={argmax['p50']:.1f}/{argmax['p95']:.1f} ms"
            )
    print(f"Contact sheets: {root / 'results' / 'contact_sheets'}")


def run_self_tests() -> None:
    source = Image.new("RGB", (16, 8), (0, 0, 0))
    source.putpixel((0, 0), (255, 0, 0))
    source.putpixel((15, 0), (0, 255, 0))
    source.putpixel((0, 7), (0, 0, 255))
    source.putpixel((15, 7), (255, 255, 0))
    portrait_input, content = letterbox(source, (288, 512))
    if content != (0, 184, 288, 144):
        raise AssertionError(f"Portrait shape content rectangle changed: {content}")
    for point, expected in (
        ((2, 186), (255, 0, 0)),
        ((285, 186), (0, 255, 0)),
        ((2, 325), (0, 0, 255)),
        ((285, 325), (255, 255, 0)),
    ):
        actual = portrait_input.getpixel(point)
        if max(abs(actual[channel] - expected[channel]) for channel in range(3)) > 16:
            raise AssertionError(f"Portrait orientation marker mismatch at {point}: {actual}")
    if portrait_input.getpixel((140, 20)) != (0, 0, 0):
        raise AssertionError("Portrait model letterbox padding changed")

    wide = Image.new("RGB", (32, 18))
    for y in range(wide.height):
        for x in range(wide.width):
            wide.putpixel((x, y), (x * 7 % 256, y * 11 % 256, (x + y) * 5 % 256))
    upright, crop = portrait_center_crop(wide)
    if crop != (11, 0, 10, 18) or not np.array_equal(
        np.asarray(upright),
        np.asarray(wide.crop((11, 0, 21, 18))),
    ):
        raise AssertionError("Portrait source center crop was rotated or changed")

    portrait_model_input, portrait_content = letterbox(upright, (288, 512))
    if portrait_content != (2, 0, 284, 512):
        raise AssertionError(f"Unexpected portrait model content bounds {portrait_content}")
    portrait_prediction = Image.new("L", (288, 512), 6)
    portrait_prediction.paste(
        1,
        (
            portrait_content[0],
            portrait_content[1],
            portrait_content[0] + portrait_content[2],
            portrait_content[1] + portrait_content[3],
        ),
    )
    mapped_portrait, visible_portrait = inverse_prediction(
        portrait_prediction,
        portrait_content,
        crop,
        wide.size,
    )
    expected_visible = np.zeros((wide.height, wide.width), dtype=bool)
    expected_visible[:, 11:21] = True
    if not np.array_equal(visible_portrait, expected_visible):
        raise AssertionError("Portrait prediction did not map into the source-coordinate crop")
    if not np.all(mapped_portrait[visible_portrait] == 1):
        raise AssertionError("Portrait source inverse map changed class IDs")

    model_prediction = Image.new("L", (512, 288), 6)
    model_prediction.paste(1, (0, 16, 512, 272))
    letterbox_mapped, letterbox_visible = inverse_prediction(
        model_prediction,
        (0, 16, 512, 256),
        (0, 0, wide.width, wide.height),
        wide.size,
    )
    crop_height = upright.width * 288 // 512
    legacy_rect = (crop[0], crop[1] + upright.height - crop_height, upright.width, crop_height)
    legacy_prediction = Image.new("L", (512, 288), 1)
    legacy_mapped, legacy_visible = inverse_prediction(
        legacy_prediction,
        (0, 0, 512, 288),
        legacy_rect,
        wide.size,
    )
    rotated = upright.transpose(Image.Transpose.ROTATE_270)
    rotated_input, rotated_content = letterbox(rotated, (512, 288))
    rotated_prediction = Image.new("L", (512, 288), 6)
    rotated_prediction.paste(1, (
        rotated_content[0],
        rotated_content[1],
        rotated_content[0] + rotated_content[2],
        rotated_content[1] + rotated_content[3],
    ))
    rotated_mapped, rotated_visible = inverse_prediction(
        rotated_prediction,
        rotated_content,
        crop,
        wide.size,
        270,
    )
    common = np.logical_and.reduce(
        [visible_portrait, letterbox_visible, legacy_visible, rotated_visible]
    )
    expected_common = np.zeros((wide.height, wide.width), dtype=bool)
    expected_common[wide.height - crop_height :, 11:21] = True
    if not np.array_equal(common, expected_common):
        raise AssertionError("Four-arm common-visible geometry is not the crop intersection")
    if not np.all(letterbox_mapped[letterbox_visible] == 1):
        raise AssertionError("Letterbox source inverse map changed class IDs")
    labels = np.ones((wide.height, wide.width), dtype=np.uint8)
    metrics = confusion_metrics(labels, mapped_portrait, visible_portrait)
    if metrics["validPixels"] != int(visible_portrait.sum()) or metrics["classes"][1]["iou"] != 1.0:
        raise AssertionError("Mapped scores included model padding or lost the known class")
    if np.any(legacy_mapped[~legacy_visible] != 0) or np.any(rotated_mapped[~rotated_visible] != 0):
        raise AssertionError("Invisible source-coordinate predictions were not excluded")

    for image in (source, portrait_input, wide, upright, portrait_model_input, portrait_prediction,
                  model_prediction, legacy_prediction, rotated, rotated_input, rotated_prediction):
        image.close()
    print("Portrait tensor shape, upright geometry, four-arm inverse maps, and padding-exclusion tests passed.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("summarize", help="score Pixel output and render four-arm contact sheets")
    commands.add_parser("self-test", help="check portrait geometry and padding exclusion synthetically")
    args = parser.parse_args(argv)
    try:
        if args.command == "summarize":
            summarize(args.root)
        else:
            run_self_tests()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
