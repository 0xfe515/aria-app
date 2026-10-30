#!/usr/bin/env python3
"""Prepare CamVid fixtures and score Pixel-produced Aria orientation results."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ROOT = ROOT / "build" / "vision-orientation-evaluation"
DATASET_COMMIT = "79ae0071a75d90331a62b5a2aaf6d53cc69fd9f6"
DATASET_BASE_URL = f"https://raw.githubusercontent.com/lih627/CamVid/{DATASET_COMMIT}"
TEST_SPLIT_URL = f"{DATASET_BASE_URL}/camvid_test.txt"
ARIA_CLASSES = ("other", "road", "sidewalk", "crosswalk", "person", "vehicle", "pole_sign")
ARIA_COLORS_RGB = np.array(
    [
        (0, 0, 0),
        (255, 57, 57),
        (57, 255, 57),
        (57, 57, 255),
        (255, 255, 57),
        (255, 57, 255),
        (57, 255, 255),
    ],
    dtype=np.uint8,
)
CAMVID_TO_ARIA = np.full(256, 255, dtype=np.uint8)
CAMVID_TO_ARIA[[0, 1, 5, 7]] = 0
CAMVID_TO_ARIA[3] = 1
CAMVID_TO_ARIA[4] = 2
CAMVID_TO_ARIA[9] = 4
CAMVID_TO_ARIA[8] = 5
CAMVID_TO_ARIA[[2, 6]] = 6
CAMVID_TO_ARIA[255] = 255
STRATEGIES = (
    "legacy_bottom_crop",
    "portrait_cw90_full_frame_letterbox",
    "upright_full_frame_letterbox",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "aria-vision-orientation-eval/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sequence_name(filename: str) -> str:
    return filename.split("_f", 1)[0] if "_f" in filename else filename.rsplit("_", 1)[0]


def choose_evenly_spaced(items: list[dict], count: int) -> list[dict]:
    if count <= 0 or count > len(items):
        raise ValueError(f"Cannot select {count} frames from {len(items)}")
    if count == 1:
        return [items[len(items) // 2]]
    indexes = [math.floor((i + 0.5) * len(items) / count) for i in range(count)]
    return [items[index] for index in indexes]


def portrait_center_crop(image: Image.Image) -> tuple[Image.Image, tuple[int, int, int, int]]:
    width = max(1, (image.height * 9) // 16)
    left = (image.width - width) // 2
    rect = (left, 0, left + width, image.height)
    return image.crop(rect), (left, 0, width, image.height)


def select_frames(test_split: bytes, count: int) -> list[dict]:
    entries = []
    for line in test_split.decode("utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        if len(parts) != 2:
            raise ValueError(f"Malformed CamVid test split line: {line!r}")
        image_path, label_path = parts
        image_name = Path(image_path).name
        label_name = Path(label_path).name
        if Path(image_path).parent.as_posix() != "CamVid_RGB" or Path(label_path).parent.as_posix() != "CamVidGray":
            raise ValueError(f"Unexpected CamVid paths: {line!r}")
        entries.append(
            {
                "id": image_name.removesuffix(".png"),
                "imageName": image_name,
                "labelName": label_name,
                "sequence": sequence_name(image_name),
                "split": "test",
            }
        )
    if count > len(entries):
        raise ValueError(f"Requested {count} frames, test split has only {len(entries)}")

    grouped: dict[str, list[dict]] = defaultdict(list)
    for entry in entries:
        grouped[entry["sequence"]].append(entry)
    groups = [grouped[name] for name in sorted(grouped)]
    selected: list[dict] = []
    counts = [count // len(groups)] * len(groups)
    for index in range(count % len(groups)):
        counts[index] += 1
    for group, group_count in zip(groups, counts, strict=True):
        if group_count:
            selected.extend(choose_evenly_spaced(group, group_count))
    return selected


def download_fixtures(root: Path, count: int) -> None:
    fixture_dir = root / "fixtures"
    fixture_dir.mkdir(parents=True, exist_ok=True)
    test_split = fetch_bytes(TEST_SPLIT_URL)
    frames = select_frames(test_split, count)
    for frame in frames:
        image_url = f"{DATASET_BASE_URL}/CamVid_RGB/{frame['imageName']}"
        label_url = f"{DATASET_BASE_URL}/CamVidGray/{frame['labelName']}"
        image_bytes = fetch_bytes(image_url)
        label_bytes = fetch_bytes(label_url)
        image_path = fixture_dir / frame["imageName"]
        label_path = fixture_dir / frame["labelName"]
        image_path.write_bytes(image_bytes)
        label_path.write_bytes(label_bytes)
        with Image.open(image_path) as source, Image.open(label_path) as labels:
            source.load()
            labels.load()
            if source.size != labels.size:
                raise ValueError(f"Dimension mismatch for {frame['id']}: {source.size} vs {labels.size}")
            if source.size != (960, 720):
                raise ValueError(f"Unexpected CamVid resolution for {frame['id']}: {source.size}")
            if labels.mode not in ("L", "P"):
                raise ValueError(f"Expected grayscale CamVid labels for {frame['id']}, got {labels.mode}")
            label_ids = np.asarray(labels.convert("L"), dtype=np.uint8)
            unknown = sorted(set(np.unique(label_ids).tolist()) - set(range(11)) - {255})
            if unknown:
                raise ValueError(f"Unexpected CamVid label IDs for {frame['id']}: {unknown}")
            portrait, portrait_rect = portrait_center_crop(source)
            portrait_left, _, portrait_width, portrait_height = portrait_rect
            portrait_labels, label_crop_rect = portrait_center_crop(labels.convert("L"))
            portrait_image_path = fixture_dir / f"{frame['id']}--portrait.png"
            portrait_label_path = fixture_dir / f"{frame['id']}--portrait-label.png"
            portrait.save(portrait_image_path)
            if label_crop_rect != portrait_rect:
                raise ValueError(f"Image and label portrait crops differ for {frame['id']}")
            portrait_labels.save(portrait_label_path)
            portrait_image_bytes = portrait_image_path.read_bytes()
            portrait_label_bytes = portrait_label_path.read_bytes()
        frame.update(
            {
                "sourceImage": image_path.name,
                "sourceLabel": label_path.name,
                "width": 960,
                "height": 720,
                "portraitImage": portrait_image_path.name,
                "portraitLabel": portrait_label_path.name,
                "portraitCropRect": [
                    portrait_left,
                    0,
                    portrait_width,
                    portrait_height,
                ],
                "portraitDerivation": "center_crop_to_9x16_without_rotation",
                "imageSha256": sha256(image_bytes),
                "labelSha256": sha256(label_bytes),
                "portraitImageSha256": sha256(portrait_image_bytes),
                "portraitLabelSha256": sha256(portrait_label_bytes),
                "imageUrl": image_url,
                "labelUrl": label_url,
            }
        )
    manifest = {
        "schemaVersion": 2,
        "dataset": "CamVid Cambridge-driving Labeled Video Database",
        "datasetMirror": "lih627/CamVid",
        "datasetCommit": DATASET_COMMIT,
        "split": "test",
        "splitUrl": TEST_SPLIT_URL,
        "selection": "balanced across sequences, evenly spaced within each sequence",
        "count": len(frames),
        "frames": frames,
    }
    (fixture_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Downloaded {len(frames)} labeled frames to {fixture_dir}")
    print("Selected:", ", ".join(frame["id"] for frame in frames))


def letterbox(image: Image.Image, target_size: tuple[int, int] = (512, 288)) -> tuple[Image.Image, tuple[int, int, int, int]]:
    image = image.convert("RGB")
    target_width, target_height = target_size
    scale = min(target_width / image.width, target_height / image.height)
    width = max(1, math.floor(image.width * scale + 0.5))
    height = max(1, math.floor(image.height * scale + 0.5))
    resized = image.resize((width, height), Image.Resampling.BILINEAR)
    left = (target_width - width) // 2
    top = (target_height - height) // 2
    canvas = Image.new("RGB", target_size, (0, 0, 0))
    canvas.paste(resized, (left, top))
    return canvas, (left, top, width, height)


def inverse_prediction(
    model_prediction: Image.Image,
    model_content_rect: tuple[int, int, int, int],
    source_rect: tuple[int, int, int, int],
    source_size: tuple[int, int],
    inverse_rotation_degrees: int = 0,
) -> tuple[np.ndarray, np.ndarray]:
    source_width, source_height = source_size
    model_left, model_top, model_width, model_height = model_content_rect
    source_left, source_top, source_width_in_rect, source_height_in_rect = source_rect
    if inverse_rotation_degrees not in (0, 90, 180, 270):
        raise ValueError(f"Unsupported inverse rotation: {inverse_rotation_degrees}")
    resize_width, resize_height = (
        (source_height_in_rect, source_width_in_rect)
        if inverse_rotation_degrees in (90, 270)
        else (source_width_in_rect, source_height_in_rect)
    )
    cropped = model_prediction.crop(
        (model_left, model_top, model_left + model_width, model_top + model_height)
    )
    scaled = cropped.resize(
        (resize_width, resize_height),
        Image.Resampling.NEAREST,
    )
    if inverse_rotation_degrees == 90:
        scaled = scaled.transpose(Image.Transpose.ROTATE_270)
    elif inverse_rotation_degrees == 180:
        scaled = scaled.transpose(Image.Transpose.ROTATE_180)
    elif inverse_rotation_degrees == 270:
        scaled = scaled.transpose(Image.Transpose.ROTATE_90)
    if scaled.size != (source_width_in_rect, source_height_in_rect):
        raise ValueError(f"Inverse transform produced {scaled.size}, expected source rectangle {(source_width_in_rect, source_height_in_rect)}")
    prediction = np.zeros((source_height, source_width), dtype=np.uint8)
    visible = np.zeros((source_height, source_width), dtype=bool)
    prediction[
        source_top : source_top + source_height_in_rect,
        source_left : source_left + source_width_in_rect,
    ] = np.asarray(scaled.convert("L"), dtype=np.uint8)
    visible[
        source_top : source_top + source_height_in_rect,
        source_left : source_left + source_width_in_rect,
    ] = True
    return prediction, visible


def confusion_metrics(
    ground_truth: np.ndarray,
    prediction: np.ndarray,
    valid: np.ndarray,
) -> dict:
    gt = ground_truth[valid].astype(np.int64)
    pred = prediction[valid].astype(np.int64)
    if np.any((gt < 0) | (gt >= len(ARIA_CLASSES))):
        raise ValueError("Ground truth contains a non-mapped ID in the valid mask")
    if np.any((pred < 0) | (pred >= len(ARIA_CLASSES))):
        raise ValueError("Predictions contain IDs outside the seven-class model contract")
    matrix = np.bincount(
        gt * len(ARIA_CLASSES) + pred,
        minlength=len(ARIA_CLASSES) ** 2,
    ).reshape((len(ARIA_CLASSES), len(ARIA_CLASSES)))
    per_class = []
    included_ious = []
    for class_id, name in enumerate(ARIA_CLASSES):
        true_positive = int(matrix[class_id, class_id])
        false_positive = int(matrix[:, class_id].sum() - true_positive)
        false_negative = int(matrix[class_id, :].sum() - true_positive)
        support = int(matrix[class_id, :].sum())
        predicted_support = int(matrix[:, class_id].sum())
        if support == 0:
            per_class.append(
                {
                    "id": class_id,
                    "name": name,
                    "status": "excluded_no_compatible_camvid_ground_truth",
                    "groundTruthPixels": 0,
                    "predictedPixels": predicted_support,
                    "iou": None,
                    "precision": 0.0 if predicted_support else None,
                    "recall": None,
                }
            )
            continue
        iou_denominator = true_positive + false_positive + false_negative
        precision_denominator = true_positive + false_positive
        recall_denominator = true_positive + false_negative
        iou = true_positive / iou_denominator if iou_denominator else None
        precision = true_positive / precision_denominator if precision_denominator else None
        recall = true_positive / recall_denominator if recall_denominator else None
        if iou is not None:
            included_ious.append(iou)
        per_class.append(
            {
                "id": class_id,
                "name": name,
                "status": "evaluated",
                "groundTruthPixels": support,
                "predictedPixels": predicted_support,
                "truePositive": true_positive,
                "falsePositive": false_positive,
                "falseNegative": false_negative,
                "iou": iou,
                "precision": precision,
                "recall": recall,
            }
        )
    return {
        "validPixels": int(valid.sum()),
        "confusionMatrix": matrix.tolist(),
        "macroIoUValidMappedClasses": (
            float(np.mean(included_ious)) if included_ious else None
        ),
        "classes": per_class,
    }


def resize_for_cell(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    image = image.convert("RGB")
    image.thumbnail(size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", size, (242, 242, 242))
    canvas.paste(image, ((size[0] - image.width) // 2, (size[1] - image.height) // 2))
    return canvas


def prediction_overlay(source: Image.Image, prediction: np.ndarray, visible: np.ndarray) -> Image.Image:
    source_rgb = np.asarray(source.convert("RGB"), dtype=np.uint8)
    colors = ARIA_COLORS_RGB[np.clip(prediction, 0, len(ARIA_CLASSES) - 1)]
    blended = (
        source_rgb.astype(np.float32) * 0.55 + colors.astype(np.float32) * 0.45
    ).astype(np.uint8)
    output = source_rgb.copy()
    output[visible] = blended[visible]
    return Image.fromarray(output)


def ground_truth_overlay(source: Image.Image, ground_truth: np.ndarray) -> Image.Image:
    visible = ground_truth != 255
    return prediction_overlay(source, ground_truth, visible)


def make_contact_sheet(
    fixture_dir: Path,
    result_dir: Path,
    frame: dict,
    orientation: str,
    loaded: dict[str, dict],
    gt: np.ndarray,
    visible_predictions: dict[str, tuple[np.ndarray, np.ndarray]],
) -> Path:
    cell_width, cell_height = 320, 240
    title_height = 26
    columns = 3
    source = Image.open(fixture_dir / frame["sourceImage"]).convert("RGB")
    oriented_name = (
        frame["sourceImage"] if orientation == "landscape" else frame["portraitImage"]
    )
    cells = [
        ("Source landscape", source),
        (
            "Orientation sample",
            Image.open(fixture_dir / oriented_name).convert("RGB"),
        ),
        ("Mapped CamVid ground truth", ground_truth_overlay(source, gt)),
        ("Legacy model input", Image.open(result_dir / loaded[STRATEGIES[0]]["inputImage"])),
        (
            "Legacy inverse overlay",
            prediction_overlay(source, *visible_predictions[STRATEGIES[0]]),
        ),
        ("CW90-if-portrait input", Image.open(result_dir / loaded[STRATEGIES[1]]["inputImage"])),
        (
            "CW90 inverse overlay",
            prediction_overlay(source, *visible_predictions[STRATEGIES[1]]),
        ),
        ("Upright model input", Image.open(result_dir / loaded[STRATEGIES[2]]["inputImage"])),
        (
            "Upright inverse overlay",
            prediction_overlay(source, *visible_predictions[STRATEGIES[2]]),
        ),
    ]
    sheet = Image.new(
        "RGB",
        (columns * cell_width, 3 * (cell_height + title_height)),
        (255, 255, 255),
    )
    draw = ImageDraw.Draw(sheet)
    for index, (title, image) in enumerate(cells):
        row, column = divmod(index, columns)
        x = column * cell_width
        y = row * (cell_height + title_height)
        draw.text((x + 4, y + 5), title, fill=(0, 0, 0), font=ImageFont.load_default())
        sheet.paste(resize_for_cell(image, (cell_width, cell_height)), (x, y + title_height))
    contact_dir = result_dir / "contact_sheets"
    contact_dir.mkdir(parents=True, exist_ok=True)
    path = contact_dir / f"{frame['id']}--{orientation}.jpg"
    sheet.save(path, quality=90)
    return path


def summarize(root: Path) -> None:
    host_start = time.perf_counter()
    fixture_dir = root / "fixtures"
    result_dir = root / "results"
    manifest = json.loads((fixture_dir / "manifest.json").read_text(encoding="utf-8"))
    run = json.loads((result_dir / "run.json").read_text(encoding="utf-8"))
    orientations = ("landscape", "portrait_center_crop")
    frames_by_id = {frame["id"]: frame for frame in manifest["frames"]}
    expected_ids = {
        f"{frame_id}--{orientation}"
        for frame_id in frames_by_id
        for orientation in orientations
    }
    result_frames = {frame["id"]: frame for frame in run["frames"]}
    if len(result_frames) != len(run["frames"]) or set(expected_ids) != set(result_frames):
        raise ValueError("Device run must contain both orientations for every prepared scene")

    all_strategy_times = {
        (orientation, strategy): []
        for orientation in orientations
        for strategy in STRATEGIES
    }
    all_transform_times = {
        (orientation, strategy): []
        for orientation in orientations
        for strategy in STRATEGIES
    }
    all_decode_times = {
        (orientation, strategy): []
        for orientation in orientations
        for strategy in STRATEGIES
    }
    frame_reports = []
    confusion_full = {
        (orientation, strategy): np.zeros((7, 7), dtype=np.int64)
        for orientation in orientations
        for strategy in STRATEGIES
    }
    confusion_common = {
        (orientation, strategy): np.zeros((7, 7), dtype=np.int64)
        for orientation in orientations
        for strategy in STRATEGIES
    }
    paired_control_equivalence = []

    for frame_id, frame in frames_by_id.items():
        source = Image.open(fixture_dir / frame["sourceImage"]).convert("RGB")
        raw_labels = np.asarray(
            Image.open(fixture_dir / frame["sourceLabel"]).convert("L"),
            dtype=np.uint8,
        )
        gt = CAMVID_TO_ARIA[raw_labels]
        if source.size != (frame["width"], frame["height"]) or gt.shape != (source.height, source.width):
            raise ValueError(f"Source dimensions changed for {frame_id}")
        portrait, portrait_rect = portrait_center_crop(source)
        portrait_labels, label_rect = portrait_center_crop(
            Image.open(fixture_dir / frame["sourceLabel"]).convert("L")
        )
        if portrait_rect != label_rect or list(portrait_rect) != frame["portraitCropRect"]:
            raise ValueError(f"Portrait center-crop geometry changed for {frame_id}")
        if not np.array_equal(
            np.asarray(portrait),
            np.asarray(Image.open(fixture_dir / frame["portraitImage"]).convert("RGB")),
        ):
            raise ValueError(f"Portrait RGB fixture is not the upright center crop for {frame_id}")
        if not np.array_equal(
            np.asarray(portrait_labels),
            np.asarray(Image.open(fixture_dir / frame["portraitLabel"]).convert("L")),
        ):
            raise ValueError(f"Portrait label fixture is not the matching center crop for {frame_id}")
        base_left, base_top, base_width, base_height = portrait_rect
        if not frame["portraitImageSha256"] == sha256(
            (fixture_dir / frame["portraitImage"]).read_bytes()
        ) or not frame["portraitLabelSha256"] == sha256(
            (fixture_dir / frame["portraitLabel"]).read_bytes()
        ):
            raise ValueError(f"Portrait fixture hash mismatch for {frame_id}")

        for orientation in orientations:
            fixture_id = f"{frame_id}--{orientation}"
            device_frame = result_frames[fixture_id]
            if device_frame["baseFrameId"] != frame_id or device_frame["orientation"] != orientation:
                raise ValueError(f"Device output identity mismatch for {fixture_id}")
            strategy_data = {
                entry["strategy"]: entry for entry in device_frame["strategies"]
            }
            if len(strategy_data) != len(device_frame["strategies"]) or set(strategy_data) != set(STRATEGIES):
                raise ValueError(f"{fixture_id} lacks one or more unique required strategies")
            sample = source if orientation == "landscape" else portrait
            sample_left, sample_top = (
                (0, 0) if orientation == "landscape" else (base_left, base_top)
            )
            sample_rect = (sample_left, sample_top, sample.width, sample.height)
            inverse: dict[str, tuple[np.ndarray, np.ndarray]] = {}
            loaded: dict[str, dict] = {}
            input_images: dict[str, np.ndarray] = {}
            prediction_images: dict[str, np.ndarray] = {}
            for strategy in STRATEGIES:
                item = strategy_data[strategy]
                input_path = result_dir / item["inputImage"]
                prediction_path = result_dir / item["predictionImage"]
                if not input_path.is_file() or not prediction_path.is_file():
                    raise FileNotFoundError(f"Missing device output for {fixture_id}/{strategy}")
                input_image = Image.open(input_path).convert("RGB")
                model_prediction = Image.open(prediction_path).convert("L")
                if input_image.size != (512, 288) or model_prediction.size != (512, 288):
                    raise ValueError(f"Unexpected model tensor shape for {fixture_id}/{strategy}")
                input_images[strategy] = np.asarray(input_image)
                prediction_images[strategy] = np.asarray(model_prediction)
                model_content = tuple(item["modelContentRect"])
                source_rect = tuple(item["sourceRect"])
                inverse[strategy] = inverse_prediction(
                    model_prediction,
                    model_content,
                    source_rect,
                    source.size,
                    item.get("inverseRotationDegrees", 0),
                )
                loaded[strategy] = item
                all_strategy_times[(orientation, strategy)].extend(
                    value / 1_000_000.0 for value in item["invokeTimesNanos"]
                )
                all_transform_times[(orientation, strategy)].append(
                    item["transformNanos"] / 1_000_000.0
                )
                decode_times = item["productionDecodeArgmaxAndMaskTimesNanos"]
                if len(decode_times) != len(item["invokeTimesNanos"]):
                    raise ValueError(f"Missing production decode timings for {fixture_id}/{strategy}")
                all_decode_times[(orientation, strategy)].extend(
                    value / 1_000_000.0 for value in decode_times
                )

                production_transform = item["productionTransform"]
                production_strategy = {
                    STRATEGIES[0]: "LEGACY_CROP",
                    STRATEGIES[1]: "FULL_FRAME_ROTATED_LANDSCAPE",
                    STRATEGIES[2]: "FULL_FRAME_UPRIGHT_LETTERBOX",
                }[strategy]
                if strategy == STRATEGIES[0]:
                    crop_height = (sample.width * 288) // 512
                    if crop_height <= sample.height:
                        expected_source_rect = (
                            sample_left,
                            sample_top + sample.height - crop_height,
                            sample.width,
                            crop_height,
                        )
                    else:
                        crop_width = (sample.height * 512) // 288
                        expected_source_rect = (
                            sample_left + (sample.width - crop_width) // 2,
                            sample_top,
                            crop_width,
                            sample.height,
                        )
                    expected_content = (0, 0, 512, 288)
                    expected_inverse_rotation = 0
                else:
                    expected_source_rect = sample_rect
                    transform_image = sample
                    expected_inverse_rotation = 0
                    if strategy == STRATEGIES[1] and sample.height > sample.width:
                        transform_image = sample.transpose(Image.Transpose.ROTATE_270)
                        expected_inverse_rotation = 270
                    _, expected_content = letterbox(transform_image)
                    if transform_image is not sample:
                        transform_image.close()
                if source_rect != expected_source_rect:
                    raise ValueError(
                        f"Unexpected sourceRect for {fixture_id}/{strategy}: "
                        f"{source_rect}, expected {expected_source_rect}"
                    )
                if model_content != expected_content:
                    raise ValueError(
                        f"Unexpected modelContentRect for {fixture_id}/{strategy}: "
                        f"{model_content}, expected {expected_content}"
                    )
                if item.get("inverseRotationDegrees", 0) != expected_inverse_rotation:
                    raise ValueError(f"Unexpected inverse rotation for {fixture_id}/{strategy}")
                expected_production_crop = (
                    source_rect[0] - sample_left,
                    source_rect[1] - sample_top,
                    source_rect[2],
                    source_rect[3],
                )
                if (
                    production_transform["strategy"] != production_strategy
                    or production_transform["bufferWidth"] != sample.width
                    or production_transform["bufferHeight"] != sample.height
                    or production_transform["rotationDegrees"] != 0
                    or production_transform["uprightWidth"] != sample.width
                    or production_transform["uprightHeight"] != sample.height
                    or tuple(production_transform["cropRect"]) != expected_production_crop
                    or tuple(production_transform["modelContentRect"]) != model_content
                    or production_transform["isRotatedClockwise"]
                    != (strategy == STRATEGIES[1] and orientation == "portrait_center_crop")
                ):
                    raise ValueError(f"Production transform metadata mismatch for {fixture_id}/{strategy}")

            label_valid = gt != 255
            common_visible = np.logical_and.reduce([inverse[strategy][1] for strategy in STRATEGIES])
            common_valid = common_visible & label_valid
            strategy_reports = {}
            for strategy in STRATEGIES:
                prediction, visible = inverse[strategy]
                full_valid = visible & label_valid
                full_metrics = confusion_metrics(gt, prediction, full_valid)
                common_metrics = confusion_metrics(gt, prediction, common_valid)
                confusion_full[(orientation, strategy)] += np.asarray(
                    full_metrics["confusionMatrix"], dtype=np.int64
                )
                confusion_common[(orientation, strategy)] += np.asarray(
                    common_metrics["confusionMatrix"], dtype=np.int64
                )
                strategy_reports[strategy] = {
                    "visibleValidPixels": full_metrics["validPixels"],
                    "coverageOfAllMappedSourcePixels": (
                        full_metrics["validPixels"] / int(label_valid.sum())
                        if int(label_valid.sum())
                        else None
                    ),
                    "fullField": full_metrics,
                    "commonVisibleRegion": common_metrics,
                    "transformMs": loaded[strategy]["transformNanos"] / 1_000_000.0,
                    "invokeTimesMs": [
                        value / 1_000_000.0
                        for value in loaded[strategy]["invokeTimesNanos"]
                    ],
                    "productionDecodeArgmaxAndMaskTimesMs": [
                        value / 1_000_000.0
                        for value in loaded[strategy]["productionDecodeArgmaxAndMaskTimesNanos"]
                    ],
                }

            contact_sheet = make_contact_sheet(
                fixture_dir, result_dir, frame, orientation, loaded, gt, inverse
            )
            frame_reports.append(
                {
                    "id": fixture_id,
                    "baseFrameId": frame_id,
                    "orientation": orientation,
                    "orientationDescription": (
                        "original landscape source"
                        if orientation == "landscape"
                        else "upright 9:16 center crop; derived without rotating the source"
                    ),
                    "sequence": frame["sequence"],
                    "split": frame["split"],
                    "orientationSampleSourceRect": list(sample_rect),
                    "width": sample.width,
                    "height": sample.height,
                    "mappedGroundTruthPixels": int(label_valid.sum()),
                    "commonVisibleMappedPixels": int(common_valid.sum()),
                    "commonRegionCoverage": (
                        int(common_valid.sum()) / int(label_valid.sum())
                        if int(label_valid.sum())
                        else None
                    ),
                    "strategies": strategy_reports,
                    "contactSheet": str(contact_sheet.relative_to(root)).replace("\\", "/"),
                }
            )

            if orientation == "landscape":
                controls_equal = np.array_equal(
                    input_images[STRATEGIES[1]], input_images[STRATEGIES[2]]
                )
                if not controls_equal:
                    raise ValueError(f"Landscape full-frame controls differ for {frame_id}")
                paired_control_equivalence.append(
                    {
                        "baseFrameId": frame_id,
                        "modelInputsEqual": True,
                        "predictionMasksEqual": bool(
                            np.array_equal(
                                prediction_images[STRATEGIES[1]],
                                prediction_images[STRATEGIES[2]],
                            )
                        ),
                    }
                )

    orientation_totals = {}
    for orientation in orientations:
        orientation_totals[orientation] = {}
        for strategy in STRATEGIES:
            full = confusion_metrics_from_matrix(confusion_full[(orientation, strategy)])
            common = confusion_metrics_from_matrix(confusion_common[(orientation, strategy)])
            orientation_totals[orientation][strategy] = {
                "fullField": full,
                "commonVisibleRegion": common,
                "deviceModelInvokeTimingMs": timing_summary(
                    all_strategy_times[(orientation, strategy)]
                ),
                "deviceTransformTimingMs": timing_summary(
                    all_transform_times[(orientation, strategy)]
                ),
                "deviceProductionDecodeArgmaxAndMaskTimingMs": timing_summary(
                    all_decode_times[(orientation, strategy)]
                ),
                "timingScope": (
                    "Pixel device; model invoke is raw interpreter execution, transform is bitmap fixture "
                    "preparation plus RGB float conversion (not CameraX YUV preprocessing), and production "
                    "decode is decodeArgmaxAndMask on actual model probabilities without overlay allocation"
                ),
            }

    results = {
        "schemaVersion": 2,
        "dataset": manifest,
        "comparisonDesign": {
            "sourceScenes": len(frames_by_id),
            "orientationsPerScene": 2,
            "strategiesPerOrientation": len(STRATEGIES),
            "conditions": len(frame_reports) * len(STRATEGIES),
            "portraitDerivation": "upright 9:16 center crop without rotating objects",
        },
        "device": {
            "model": run["deviceModel"],
            "sdkInt": run["sdkInt"],
            "requestedBackend": run["requestedBackend"],
            "actualBackend": run["actualBackend"],
            "backendDescription": run["backendDescription"],
            "modelInvokeTimingScope": run["modelInvokeTimingScope"],
            "transformTimingScope": run["transformTimingScope"],
            "productionDecodeTimingScope": run["productionDecodeTimingScope"],
            "modelName": run["modelName"],
            "modelSha256": run["modelSha256"],
            "warmupRunsPerInput": run["warmupRunsPerInput"],
            "measuredRunsPerInput": run["measuredRunsPerInput"],
        },
        "hostPostprocessingMs": (time.perf_counter() - host_start) * 1000.0,
        "hostPostprocessingScope": "Host metric aggregation and contact-sheet rendering only; no model inference",
        "labelMapping": {
            "sourceClassOrder": [
                "Sky", "Building", "Pole", "Road", "Sidewalk", "Tree",
                "SignSymbol", "Fence", "Car", "Pedestrian", "Bicyclist",
            ],
            "camvidToAria": {
                "Sky, Building, Tree, Fence": "other (0)",
                "Road": "road (1)",
                "Sidewalk/Pavement": "sidewalk (2)",
                "Pedestrian": "person (4)",
                "Car": "vehicle (5)",
                "Pole, SignSymbol": "pole_sign (6)",
                "Void (255)": "ignored",
                "Bicyclist (10)": "excluded as incompatible with the vehicle label",
                "crosswalk (3)": "unavailable as a separate CamVid ground-truth class; excluded",
            },
            "limitations": [
                "CamVid's Sidewalk/Pavement label does not separate crosswalk.",
                "Bicyclist is excluded rather than mapped to the broader Aria vehicle class.",
                "The grouped other class combines Sky, Building, Tree, and Fence.",
                "Each landscape scene also contributes an upright portrait center crop; it is not a native portrait capture.",
                "Pixel labels and transformations are evaluated in original source coordinates; letterbox padding is never scored.",
            ],
        },
        "orientationTotals": orientation_totals,
        "landscapeControlEquivalence": paired_control_equivalence,
        "frames": frame_reports,
        "recognitionClaim": (
            "These are actual bundled-model predictions on both orientations for each source scene, scored against CamVid labels; "
            "the small sample and cross-dataset label mapping do not establish trained accuracy."
        ),
    }
    (result_dir / "summary.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(result_dir / "summary.json")
    for orientation, strategy_reports in orientation_totals.items():
        for strategy, report in strategy_reports.items():
            all_metrics = report["fullField"]["classes"]
            ious = ", ".join(
                f"{item['name']}={item['iou']:.3f}"
                for item in all_metrics
                if item["iou"] is not None
            )
            timing = report["deviceModelInvokeTimingMs"]
            decode_timing = report["deviceProductionDecodeArgmaxAndMaskTimingMs"]
            print(
                f"{orientation}/{strategy}: full mIoU="
                f"{report['fullField']['macroIoUValidMappedClasses']:.3f}; "
                f"invoke p50/p95={timing['p50']:.1f}/{timing['p95']:.1f} ms; "
                f"production decode p50/p95={decode_timing['p50']:.1f}/"
                f"{decode_timing['p95']:.1f} ms; {ious}"
            )
    print(f"Contact sheets: {result_dir / 'contact_sheets'}")


def confusion_metrics_from_matrix(matrix: np.ndarray) -> dict:
    per_class = []
    ious = []
    valid_pixels = int(matrix.sum())
    for class_id, name in enumerate(ARIA_CLASSES):
        tp = int(matrix[class_id, class_id])
        fp = int(matrix[:, class_id].sum() - tp)
        fn = int(matrix[class_id, :].sum() - tp)
        support = int(matrix[class_id, :].sum())
        predicted_support = int(matrix[:, class_id].sum())
        if support == 0:
            per_class.append(
                {
                    "id": class_id,
                    "name": name,
                    "status": "excluded_no_compatible_camvid_ground_truth",
                    "groundTruthPixels": 0,
                    "predictedPixels": predicted_support,
                    "iou": None,
                    "precision": 0.0 if predicted_support else None,
                    "recall": None,
                }
            )
            continue
        iou = tp / (tp + fp + fn) if tp + fp + fn else None
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / (tp + fn) if tp + fn else None
        if iou is not None:
            ious.append(iou)
        per_class.append(
            {
                "id": class_id,
                "name": name,
                "status": "evaluated",
                "groundTruthPixels": support,
                "predictedPixels": predicted_support,
                "truePositive": tp,
                "falsePositive": fp,
                "falseNegative": fn,
                "iou": iou,
                "precision": precision,
                "recall": recall,
            }
        )
    return {
        "validPixels": valid_pixels,
        "confusionMatrix": matrix.tolist(),
        "macroIoUValidMappedClasses": float(np.mean(ious)) if ious else None,
        "classes": per_class,
    }


def timing_summary(values: list[float]) -> dict:
    if not values:
        return {"samples": 0, "p50": None, "p95": None}
    array = np.asarray(values, dtype=np.float64)
    return {
        "samples": len(values),
        "p50": float(np.percentile(array, 50)),
        "p95": float(np.percentile(array, 95)),
        "mean": float(array.mean()),
    }


def run_self_tests() -> None:
    source = Image.new("RGB", (16, 8), (0, 0, 0))
    pixels = source.load()
    pixels[0, 0] = (255, 0, 0)
    pixels[15, 0] = (0, 255, 0)
    pixels[0, 7] = (0, 0, 255)
    pixels[15, 7] = (255, 255, 0)
    for y in range(1, 7):
        pixels[6, y] = (255, 0, 255)
    for x in range(1, 15):
        pixels[x, 5] = (0, 255, 255)
    letterboxed, content = letterbox(source)
    if content != (0, 16, 512, 256):
        raise AssertionError(f"Unexpected full-frame content rectangle: {content}")
    if letterboxed.getpixel((256, 2)) != (0, 0, 0) or letterboxed.getpixel((256, 285)) != (0, 0, 0):
        raise AssertionError("Letterbox padding color or coordinates changed")
    for point, expected in (
        ((10, 20), (255, 0, 0)),
        ((500, 20), (0, 255, 0)),
        ((10, 267), (0, 0, 255)),
        ((500, 267), (255, 255, 0)),
        ((208, 80), (255, 0, 255)),
        ((100, 190), (0, 255, 255)),
    ):
        actual = letterboxed.getpixel(point)
        if max(abs(actual[channel] - expected[channel]) for channel in range(3)) > 16:
            raise AssertionError(f"Asymmetric marker mismatch at {point}: {actual}")

    wide_source = Image.new("RGB", (32, 18))
    for y in range(wide_source.height):
        for x in range(wide_source.width):
            wide_source.putpixel((x, y), (x * 7 % 256, y * 11 % 256, (x + y) * 5 % 256))
    portrait, portrait_rect = portrait_center_crop(wide_source)
    if portrait_rect != (11, 0, 10, 18) or portrait.size != (10, 18):
        raise AssertionError(f"Unexpected upright portrait center crop: {portrait_rect}, {portrait.size}")
    if not np.array_equal(
        np.asarray(portrait),
        np.asarray(wide_source.crop((11, 0, 21, 18))),
    ):
        raise AssertionError("Portrait center crop changed or rotated source pixels")
    rotated = portrait.transpose(Image.Transpose.ROTATE_270)
    restored = rotated.transpose(Image.Transpose.ROTATE_90)
    if not np.array_equal(np.asarray(restored), np.asarray(portrait)):
        raise AssertionError("CW90 portrait preprocessing and CCW90 inverse did not round-trip")

    rotated_input, rotated_content = letterbox(rotated)
    if rotated_content != (0, 2, 512, 284):
        raise AssertionError(f"Unexpected rotated portrait content bounds: {rotated_content}")
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
        portrait_rect,
        (wide_source.width, wide_source.height),
        inverse_rotation_degrees=270,
    )
    expected_portrait_visible = np.zeros((wide_source.height, wide_source.width), dtype=bool)
    expected_portrait_visible[:, 11:21] = True
    if not np.array_equal(rotated_visible, expected_portrait_visible):
        raise AssertionError("Inverse CW90 prediction did not land in the portrait center crop")
    if not np.all(rotated_mapped[rotated_visible] == 1):
        raise AssertionError("Inverse-rotated portrait content did not preserve model classes")

    upright_input, upright_content = letterbox(portrait)
    if upright_content != (176, 0, 160, 288):
        raise AssertionError(f"Unexpected upright portrait content bounds: {upright_content}")
    upright_prediction = Image.new("L", (512, 288), 6)
    upright_prediction.paste(1, (176, 0, 336, 288))
    upright_mapped, upright_visible = inverse_prediction(
        upright_prediction,
        upright_content,
        portrait_rect,
        (wide_source.width, wide_source.height),
    )
    if not np.array_equal(upright_visible, expected_portrait_visible):
        raise AssertionError("Upright predictions did not land in the portrait center crop")
    if not np.all(upright_mapped[upright_visible] == 1):
        raise AssertionError("Upright portrait content did not preserve model classes")

    legacy_crop_height = (portrait.width * 288) // 512
    legacy_source_rect = (11, portrait.height - legacy_crop_height, portrait.width, legacy_crop_height)
    legacy_prediction = Image.new("L", (512, 288), 1)
    legacy_mapped, legacy_visible = inverse_prediction(
        legacy_prediction,
        (0, 0, 512, 288),
        legacy_source_rect,
        (wide_source.width, wide_source.height),
    )
    common_visible = rotated_visible & upright_visible & legacy_visible
    expected_common = np.zeros((wide_source.height, wide_source.width), dtype=bool)
    expected_common[13:18, 11:21] = True
    if not np.array_equal(common_visible, expected_common):
        raise AssertionError("Three-strategy common region did not match the portrait legacy crop")

    labels = Image.new("L", (16, 8), 1)
    model_prediction = Image.new("L", (512, 288), 6)
    model_prediction.paste(1, (0, 16, 512, 272))
    mapped, visible = inverse_prediction(
        model_prediction,
        model_content_rect=(0, 16, 512, 256),
        source_rect=(0, 0, 16, 8),
        source_size=(16, 8),
    )
    ground_truth = np.asarray(labels, dtype=np.uint8)
    metrics = confusion_metrics(ground_truth, mapped, visible)
    if metrics["validPixels"] != 16 * 8 or metrics["classes"][1]["iou"] != 1.0:
        raise AssertionError("Padding predictions leaked into mapped source-coordinate metrics")
    if metrics["classes"][6]["groundTruthPixels"] != 0:
        raise AssertionError("Padding-only predictions were counted as valid ground truth")
    if not np.array_equal(ARIA_COLORS_RGB[1], np.array((255, 57, 57), dtype=np.uint8)):
        raise AssertionError("Aria road overlay palette no longer matches VisionContract")
    print(
        "Synthetic center-crop, CW90/CCW90 inverse, three-strategy common region, "
        "letterbox, palette, and padding-exclusion tests passed."
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    download_parser = commands.add_parser("download", help="download a modest CamVid test subset")
    download_parser.add_argument("--count", type=int, default=8)
    commands.add_parser("summarize", help="score Pixel output and render review contact sheets")
    commands.add_parser("self-test", help="run synthetic geometry and padding-exclusion checks")
    args = parser.parse_args(argv)
    try:
        if args.command == "download":
            download_fixtures(args.root, args.count)
        elif args.command == "summarize":
            summarize(args.root)
        else:
            run_self_tests()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
