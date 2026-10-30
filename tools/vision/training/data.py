from __future__ import annotations

import hashlib
import json
import math
import struct
import xml.etree.ElementTree as ET
import zlib
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from threading import local
from typing import Any, Sequence


OUT_W = 288
OUT_H = 512
SEED = 42
CLASS_NAMES = ("other", "road", "sidewalk", "crosswalk", "person", "vehicle", "pole_sign")
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
IGNORE = 255

SURFACE_COLOR_MAP = {
    (0, 0, 255): 2,
    (128, 128, 128): 2,
    (217, 217, 217): 2,
    (55, 86, 35): 2,
    (110, 168, 70): 2,
    (255, 230, 153): 2,
    (198, 89, 17): 2,
    (255, 255, 0): 2,
    (128, 96, 0): 2,
    (255, 155, 155): 2,
    (255, 0, 255): 3,
    (208, 88, 255): 3,
    (255, 128, 255): 1,
    (230, 170, 255): 1,
    (138, 60, 200): 1,
    (88, 38, 128): 1,
    (0, 255, 0): 0,
    (255, 0, 0): 0,
    (255, 128, 0): 0,
    (255, 192, 0): 0,
    (105, 105, 255): 0,
}

CITYSCAPES_CLASS_MAP = {7: 1, 8: 2, 9: 1, 10: 1}
CITYSCAPES_CLASS_MAP.update({17: 6, 18: 6, 19: 6, 20: 6, 24: 4, 25: 4})
CITYSCAPES_CLASS_MAP.update({label_id: 5 for label_id in range(26, 34)})
CITYSCAPES_OTHER_IDS = frozenset((*range(11, 17), *range(21, 24)))
CITYSCAPES_VOID_IDS = frozenset((*range(0, 7), 255))

_SURFACE_KNOWN_LABELS = {
    "alley",
    "roadway",
    "sidewalk",
    "braille_guide_blocks",
    "bike_lane",
    "caution_zone",
}
_SURFACE_SEMANTIC_BY_LABEL = {
    "alley": 1,
    "roadway": 1,
    "sidewalk": 2,
    "braille_guide_blocks": 2,
    "bike_lane": 2,
    "caution_zone": 0,
}
_HAZARD_INDEX = {name: index for index, name in enumerate(HAZARD_NAMES)}


@dataclass
class DatasetRecord:
    record_id: str
    source: str
    group_seed: str
    source_split: str
    image_path: Path
    image_sha256: str
    semantic_annotation_path: Path | None
    xml_path: Path | None = None
    xml_image_name: str | None = None
    city: str | None = None
    hazard_instances: Counter[str] = field(default_factory=Counter)
    class_pixel_counts: Counter[str] = field(default_factory=Counter)
    hazard_pixel_counts: Counter[str] = field(default_factory=Counter)
    hazard_valid_pixel_counts: Counter[str] = field(default_factory=Counter)
    mask_sha256: str | None = None
    xml_sha256: str | None = None
    split: str | None = None
    group_id: str | None = None
    generated_image_path: str | None = None
    semantic_path: str | None = None
    hazard_path: str | None = None
    hazard_valid_path: str | None = None
    semantic_source_path: str | None = None
    semantic_weight_path: str | None = None


class DatasetError(ValueError):
    pass


def require_image_dependencies() -> tuple[Any, Any]:
    try:
        import numpy as np
        from PIL import Image, ImageDraw
    except ImportError as exc:
        raise RuntimeError(
            "Image conversion requires NumPy and Pillow in the isolated training environment."
        ) from exc
    return np, (Image, ImageDraw)


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _stable_id(value: str, prefix: str = "") -> str:
    return prefix + hashlib.sha256(value.encode("utf-8")).hexdigest()[:20]


def _xml_hazard(label: str, attribute: str | None) -> str | None:
    if label == "bike_lane":
        return "bike_lane"
    if label == "caution_zone" and attribute in {
        "stairs",
        "manhole",
        "tree_zone",
        "grating",
        "repair_zone",
    }:
        return attribute
    if label in {"sidewalk", "braille_guide_blocks", "alley"} and attribute == "damaged":
        return "damaged_surface"
    if label == "alley" and attribute == "speed_bump":
        return "speed_bump"
    return None


def _xml_polygon_descriptor(polygon: ET.Element) -> tuple[str, str | None] | None:
    label = polygon.get("label", "")
    if label not in _SURFACE_KNOWN_LABELS:
        return None
    attribute = next(
        (
            (element.text or "").strip()
            for element in polygon.findall("attribute")
            if element.get("name") == "attribute"
        ),
        None,
    )
    if label == "caution_zone" and attribute not in {
        "stairs",
        "manhole",
        "tree_zone",
        "grating",
        "repair_zone",
    }:
        return None
    if label == "bike_lane":
        attribute = None
    elif label in {"sidewalk", "braille_guide_blocks"} and attribute not in {
        "blocks",
        "cement",
        "urethane",
        "asphalt",
        "soil_stone",
        "damaged",
        "other",
        "normal",
    }:
        return None
    elif label == "alley" and attribute not in {"normal", "crosswalk", "speed_bump", "damaged"}:
        return None
    elif label == "roadway" and attribute not in {"normal", "crosswalk"}:
        return None
    hazard = _xml_hazard(label, attribute)
    return label, hazard


def parse_surface_xml(path: Path) -> tuple[str, dict[str, list[ET.Element]], Counter[str]]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise DatasetError(f"Unable to parse Surface XML {path}: {exc}") from exc
    task = root.find("./meta/task")
    task_name = (task.findtext("name") if task is not None else None) or path.stem
    frames: dict[str, list[ET.Element]] = {}
    counts: Counter[str] = Counter()
    for image in root.findall(".//image"):
        name = image.get("name")
        if not name:
            continue
        polygons = image.findall("polygon")
        frames[name] = polygons
        for polygon in polygons:
            descriptor = _xml_polygon_descriptor(polygon)
            if descriptor and descriptor[1]:
                counts[descriptor[1]] += 1
    return task_name, frames, counts


def _surface_recording(directory: Path, task_name: str) -> str:
    return f"surface:{directory.name}:{task_name}"


def _record_from_paths(
    source: str,
    group_seed: str,
    source_split: str,
    image_path: Path,
    semantic_path: Path | None,
    **kwargs: Any,
) -> DatasetRecord:
    relative = image_path.as_posix()
    return DatasetRecord(
        record_id=_stable_id(f"{source}:{relative}", f"{source}-"),
        source=source,
        group_seed=group_seed,
        source_split=source_split,
        image_path=image_path,
        image_sha256=sha256_file(image_path),
        semantic_annotation_path=semantic_path,
        mask_sha256=sha256_file(semantic_path) if semantic_path and semantic_path.is_file() else None,
        **kwargs,
    )


def discover_records(dataset_root: Path) -> tuple[list[DatasetRecord], dict[str, Any]]:
    root = dataset_root.expanduser().resolve()
    surface_root = root / "Surface_1"
    city_root = root / "cityscapes"
    if not surface_root.is_dir() or not city_root.is_dir():
        raise DatasetError(
            f"Expected both {surface_root} and {city_root} to be directories."
        )

    records: list[DatasetRecord] = []
    summary: dict[str, Any] = {
        "surface": {
            "images": 0,
            "paired": 0,
            "unpaired": 0,
            "xml_files": 0,
            "xml_tagged_images": 0,
            "xml_semantic_polygon_counts": Counter(),
        },
        "cityscapes": {"images": 0, "paired": 0, "unpaired": 0, "split_images": {}},
    }

    for directory in sorted(path for path in surface_root.iterdir() if path.is_dir()):
        xml_files = sorted(directory.glob("*.xml"))
        xml_path = xml_files[0] if xml_files else None
        xml_digest = sha256_file(xml_path) if xml_path else None
        frames: dict[str, list[ET.Element]] = {}
        task_name = xml_path.stem if xml_path else directory.name
        xml_hazards: Counter[str] = Counter()
        if xml_path:
            summary["surface"]["xml_files"] += 1
            task_name, frames, xml_hazards = parse_surface_xml(xml_path)
            for polygons in frames.values():
                for polygon in polygons:
                    descriptor = _xml_polygon_descriptor(polygon)
                    if descriptor:
                        semantic_class = _SURFACE_SEMANTIC_BY_LABEL[descriptor[0]]
                        summary["surface"]["xml_semantic_polygon_counts"][
                            CLASS_NAMES[semantic_class]
                        ] += 1
        images = sorted(
            image
            for image in directory.glob("*.jpg")
            if image.is_file()
        )
        for image_path in images:
            mask_path = directory / "MASK" / f"{image_path.stem}.png"
            is_paired = mask_path.is_file()
            image_polygons = frames.get(image_path.name, [])
            image_hazards: Counter[str] = Counter()
            for polygon in image_polygons:
                descriptor = _xml_polygon_descriptor(polygon)
                if descriptor and descriptor[1]:
                    image_hazards[descriptor[1]] += 1
            record = _record_from_paths(
                "surface",
                _surface_recording(directory, task_name),
                "surface",
                image_path,
                mask_path if is_paired else None,
                xml_path=xml_path,
                xml_image_name=image_path.name if image_path.name in frames else None,
                xml_sha256=xml_digest,
                hazard_instances=image_hazards,
            )
            records.append(record)
            summary["surface"]["images"] += 1
            summary["surface"]["paired"] += int(is_paired)
            summary["surface"]["unpaired"] += int(not is_paired)
            summary["surface"]["xml_tagged_images"] += int(image_path.name in frames)
        if xml_path:
            summary["surface"].setdefault("xml_hazard_polygon_counts", Counter()).update(xml_hazards)

    city_images = city_root / "leftImg8bit"
    city_labels = city_root / "gtFine"
    for source_split, output_split in (("train", "city-train"), ("val", "city-official-val")):
        split_root = city_images / source_split
        split_counts: Counter[str] = Counter()
        if not split_root.is_dir():
            continue
        for image_path in sorted(split_root.glob("*/*.png")):
            city = image_path.parent.name
            stem = image_path.stem.removesuffix("_leftImg8bit")
            label_path = city_labels / source_split / city / f"{stem}_gtFine_labelIds.png"
            label_exists = label_path.is_file()
            record = _record_from_paths(
                "cityscapes",
                f"cityscapes:{output_split}:{city}",
                output_split,
                image_path,
                label_path if label_exists else None,
                city=city,
            )
            records.append(record)
            split_counts["images"] += 1
            split_counts["paired"] += int(label_exists)
            split_counts["unpaired"] += int(not label_exists)
        summary["cityscapes"]["split_images"][output_split] = dict(split_counts)
        summary["cityscapes"]["images"] += split_counts["images"]
        summary["cityscapes"]["paired"] += split_counts["paired"]
        summary["cityscapes"]["unpaired"] += split_counts["unpaired"]

    if not records:
        raise DatasetError(f"No supported images found beneath {root}.")
    summary["surface"]["xml_hazard_polygon_counts"] = dict(
        summary["surface"].get("xml_hazard_polygon_counts", {})
    )
    summary["surface"]["xml_semantic_polygon_counts"] = dict(
        summary["surface"]["xml_semantic_polygon_counts"]
    )
    return records, summary


class _UnionFind:
    def __init__(self) -> None:
        self.parent: dict[str, str] = {}

    def find(self, item: str) -> str:
        self.parent.setdefault(item, item)
        if self.parent[item] != item:
            self.parent[item] = self.find(self.parent[item])
        return self.parent[item]

    def union(self, first: str, second: str) -> None:
        left, right = self.find(first), self.find(second)
        if left == right:
            return
        if left < right:
            self.parent[right] = left
        else:
            self.parent[left] = right


def _split_group_ids(
    group_sizes: dict[str, int],
    ratios: Sequence[tuple[str, float]],
    seed: int,
    fixed: dict[str, str] | None = None,
) -> dict[str, str]:
    fixed = fixed or {}
    result = dict(fixed)
    free = {group: size for group, size in group_sizes.items() if group not in fixed}
    total = sum(group_sizes.values())
    fixed_sizes = Counter(
        {name: sum(group_sizes[group] for group, split in fixed.items() if split == name) for name, _ in ratios}
    )
    targets = {name: total * ratio for name, ratio in ratios}
    assigned = Counter(fixed_sizes)
    tie_key = lambda group: hashlib.sha256(f"{seed}:{group}".encode("utf-8")).hexdigest()
    for group in sorted(free, key=lambda value: tie_key(value)):
        size = free[group]
        choices = []
        for name, _ in ratios:
            projected = assigned.copy()
            projected[name] += size
            error = sum(
                ((projected[split] - targets[split]) / max(targets[split], 1)) ** 2
                for split, _ in ratios
            )
            choices.append((error, tie_key(f"{group}:{name}"), name))
        chosen = min(choices)[2]
        result[group] = chosen
        assigned[chosen] += size
    return result


def assign_group_splits(records: list[DatasetRecord], seed: int = SEED) -> dict[str, Any]:
    union = _UnionFind()
    hash_groups: dict[str, list[str]] = defaultdict(list)
    for record in records:
        union.find(record.group_seed)
        hash_groups[record.image_sha256].append(record.group_seed)
    for groups in hash_groups.values():
        for group in groups[1:]:
            union.union(groups[0], group)

    components: dict[str, list[DatasetRecord]] = defaultdict(list)
    for record in records:
        components[union.find(record.group_seed)].append(record)
    surface_components: dict[str, int] = {}
    city_components: dict[str, int] = {}
    fixed: dict[str, str] = {}
    for root, grouped_records in components.items():
        base_ids = sorted({record.group_seed for record in grouped_records})
        group_id = _stable_id("\n".join(base_ids), "group-")
        for record in grouped_records:
            record.group_id = group_id
        if any(record.source_split == "city-official-val" for record in grouped_records):
            fixed[root] = "test"
        elif any(record.source == "surface" for record in grouped_records):
            surface_components[root] = len(grouped_records)
        elif any(record.source_split == "city-train" for record in grouped_records):
            city_components[root] = len(grouped_records)

    surface_assignments = _split_group_ids(
        surface_components,
        (("train", 0.70), ("val", 0.15), ("test", 0.15)),
        seed,
        {root: split for root, split in fixed.items() if root in surface_components},
    )
    city_assignments = _split_group_ids(
        city_components,
        (("train", 0.85), ("val", 0.15)),
        seed,
        {root: split for root, split in fixed.items() if root in city_components},
    )

    group_splits = dict(fixed)
    group_splits.update(surface_assignments)
    for root, split in city_assignments.items():
        group_splits.setdefault(root, split)
    for root, grouped_records in components.items():
        split = group_splits[root]
        for record in grouped_records:
            record.split = split

    split_groups: dict[str, set[str]] = defaultdict(set)
    split_hashes: dict[str, set[str]] = defaultdict(set)
    duplicate_hashes: dict[str, list[str]] = {}
    for record in records:
        split_groups[record.split or ""].add(record.group_id or "")
        split_hashes[record.split or ""].add(record.image_sha256)
        duplicate_hashes.setdefault(record.image_sha256, []).append(record.record_id)
    group_overlap = {
        left: sorted(split_groups[left] & split_groups[right])
        for index, (left, _) in enumerate((("train", 0), ("val", 1), ("test", 2)))
        for right, _ in (("train", 0), ("val", 1), ("test", 2))[index + 1 :]
        if split_groups[left] & split_groups[right]
    }
    hash_overlap = {
        f"{left}:{right}": sorted(split_hashes[left] & split_hashes[right])
        for left, right in (("train", "val"), ("train", "test"), ("val", "test"))
        if split_hashes[left] & split_hashes[right]
    }
    duplicates = {
        digest: ids for digest, ids in duplicate_hashes.items() if len(ids) > 1
    }
    merged_group_count = sum(
        len({record.group_seed for record in grouped_records}) > 1
        for grouped_records in components.values()
    )
    return {
        "group_split_counts": {
            split: len(values) for split, values in sorted(split_groups.items())
        },
        "record_split_counts": dict(Counter(record.split for record in records)),
        "group_overlap": group_overlap,
        "duplicate_hash_overlap": hash_overlap,
        "duplicate_image_hashes": duplicates,
        "merged_group_count": merged_group_count,
        "duplicate_image_hash_group_count": len(duplicates),
        "surface_split_record_counts": dict(
            Counter(record.split for record in records if record.source == "surface")
        ),
        "cityscapes_split_record_counts": dict(
            Counter(record.split for record in records if record.source == "cityscapes")
        ),
        "surface_historical_test_note": (
            "The historical baseline may have seen examples from the new Surface test split; "
            "this split is leak-resistant for this run but is not guaranteed historically unseen."
        ),
    }


def letterbox_geometry(
    width: int,
    height: int,
    target_width: int = OUT_W,
    target_height: int = OUT_H,
) -> tuple[int, int, int, int]:
    if min(width, height, target_width, target_height) <= 0:
        raise ValueError("Source and target dimensions must be positive.")
    scale = min(target_width / width, target_height / height)
    content_width = min(target_width, max(1, math.floor(width * scale + 0.5)))
    content_height = min(target_height, max(1, math.floor(height * scale + 0.5)))
    left = (target_width - content_width) // 2
    top = (target_height - content_height) // 2
    return left, top, content_width, content_height


def letterbox_image(
    image: Any,
    *,
    mask: bool = False,
    pad_value: int | tuple[int, int, int] = 0,
    target_width: int = OUT_W,
    target_height: int = OUT_H,
) -> tuple[Any, tuple[int, int, int, int]]:
    np, image_types = require_image_dependencies()
    Image, _ = image_types
    source = image if isinstance(image, Image.Image) else Image.fromarray(np.asarray(image))
    left, top, width, height = letterbox_geometry(
        source.width, source.height, target_width, target_height
    )
    resampling = Image.Resampling.NEAREST if mask else Image.Resampling.BILINEAR
    resized = source.resize((width, height), resampling)
    canvas = Image.new(source.mode, (target_width, target_height), color=pad_value)
    canvas.paste(resized, (left, top))
    return canvas, (left, top, width, height)


@lru_cache(maxsize=1)
def _surface_color_lookup() -> Any:
    np, _ = require_image_dependencies()
    lookup = np.full(1 << 24, IGNORE, dtype=np.uint8)
    for color, class_id in SURFACE_COLOR_MAP.items():
        packed_color = (color[0] << 16) | (color[1] << 8) | color[2]
        lookup[packed_color] = class_id
    return lookup


def _surface_mask_to_classes(mask: Any) -> Any:
    np, _ = require_image_dependencies()
    rgb = np.asarray(mask.convert("RGB"), dtype=np.uint8)
    packed = (
        (rgb[:, :, 0].astype(np.uint32) << 16)
        | (rgb[:, :, 1].astype(np.uint32) << 8)
        | rgb[:, :, 2].astype(np.uint32)
    )
    mapped = _surface_color_lookup()[packed]
    unknown_colors = np.unique(packed[(mapped == IGNORE) & (packed != 0)])
    if len(unknown_colors):
        examples = [
            (
                int(color >> 16),
                int((color >> 8) & 255),
                int(color & 255),
            )
            for color in unknown_colors[:12]
        ]
        raise DatasetError(
            f"Unknown Surface mask RGB colors ({len(unknown_colors)} distinct), including {examples}; "
            "refusing to convert them to other."
        )
    return mapped


def _cityscapes_mask_to_classes(mask: Any) -> Any:
    np, _ = require_image_dependencies()
    labels = np.asarray(mask.convert("L"), dtype=np.uint8)
    mapped_lookup, known_lookup = _cityscapes_label_lookup()
    mapped = mapped_lookup[labels]
    unknown = ~known_lookup[labels]
    if np.any(unknown):
        ids = np.unique(labels[unknown]).tolist()
        raise DatasetError(
            f"Unknown Cityscapes labelIds {ids}; refusing to convert them to other."
        )
    return mapped


@lru_cache(maxsize=1)
def _cityscapes_label_lookup() -> tuple[Any, Any]:
    np, _ = require_image_dependencies()
    mapped = np.full(256, IGNORE, dtype=np.uint8)
    known = np.zeros(256, dtype=bool)
    known[list(CITYSCAPES_VOID_IDS)] = True
    known[list(CITYSCAPES_OTHER_IDS)] = True
    mapped[list(CITYSCAPES_OTHER_IDS)] = 0
    for label_id, class_id in CITYSCAPES_CLASS_MAP.items():
        mapped[label_id] = class_id
        known[label_id] = True
    return mapped, known


def _surface_hazard_layer(
    xml_path: Path | None,
    image_name: str | None,
    width: int,
    height: int,
    polygons: Sequence[ET.Element] | None = None,
) -> tuple[Any, dict[int, tuple[int, ...]]]:
    np, image_types = require_image_dependencies()
    Image, ImageDraw = image_types
    layer = Image.new("I", (width, height), color=0)
    code_map: dict[int, tuple[int, ...]] = {}
    if not xml_path or not image_name:
        return layer, code_map
    if polygons is None:
        _, frames, _ = parse_surface_xml(xml_path)
        polygons = frames.get(image_name, [])
    entries: list[tuple[int, int, ET.Element, tuple[int, ...]]] = []
    next_code = 1
    for index, polygon in enumerate(polygons):
        descriptor = _xml_polygon_descriptor(polygon)
        if descriptor is None:
            code = 0
        else:
            label, hazard = descriptor
            semantic_class = _SURFACE_SEMANTIC_BY_LABEL[label]
            hazard_indexes = (_HAZARD_INDEX[hazard],) if hazard else ()
            code_map[next_code] = (semantic_class, *hazard_indexes)
            code = next_code
            next_code += 1
        try:
            z_order = int(polygon.get("z_order", "0"))
        except ValueError as exc:
            raise DatasetError(
                f"Invalid z_order in {xml_path} for image {image_name}: {polygon.get('z_order')!r}"
            ) from exc
        entries.append((z_order, index, polygon, (code,)))

    draw = ImageDraw.Draw(layer)
    for _, _, polygon, (code,) in sorted(entries, key=lambda item: (item[0], item[1])):
        points = []
        for point in (polygon.get("points") or "").split(";"):
            if not point:
                continue
            try:
                x_text, y_text = point.split(",", 1)
                points.append((float(x_text), float(y_text)))
            except ValueError as exc:
                raise DatasetError(
                    f"Malformed polygon points in {xml_path} for image {image_name}."
                ) from exc
        if len(points) >= 3:
            draw.polygon(points, fill=code)
    return layer, code_map


def _hazard_arrays(
    layer: Any,
    code_map: dict[int, tuple[int, ...]],
    geometry: tuple[int, int, int, int],
) -> tuple[Any, Any]:
    np, image_types = require_image_dependencies()
    Image, _ = image_types
    left, top, width, height = geometry
    resized = layer.resize((width, height), Image.Resampling.NEAREST)
    ids = np.asarray(resized, dtype=np.int32)
    hazard = np.zeros((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.uint8)
    valid = np.zeros_like(hazard)
    height_slice = slice(top, top + height)
    width_slice = slice(left, left + width)
    for code, values in code_map.items():
        region = ids == code
        if not np.any(region):
            continue
        for channel in range(len(HAZARD_NAMES)):
            valid[height_slice, width_slice, channel][region] = 1
        for channel in values[1:]:
            hazard[height_slice, width_slice, channel][region] = 1
    return hazard, valid


def _save_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _to_manifest_record(record: DatasetRecord) -> dict[str, Any]:
    annotation_paths = {
        "semantic": str(record.semantic_annotation_path.resolve()) if record.semantic_annotation_path else None,
        "xml": str(record.xml_path.resolve()) if record.xml_path else None,
        "xml_image_name": record.xml_image_name,
    }
    if record.source == "cityscapes":
        annotation_paths["city_label_ids"] = annotation_paths["semantic"]
    return {
        "id": record.record_id,
        "source": record.source,
        "group_id": record.group_id,
        "split": record.split,
        "city": record.city,
        "source_split": record.source_split,
        "image_path": str(record.image_path.resolve()),
        "image_sha256": record.image_sha256,
        "annotation_paths": annotation_paths,
        "annotation_sha256": {
            "semantic": record.mask_sha256,
            "xml": record.xml_sha256,
        },
        "image_tensor_path": record.generated_image_path,
        "semantic_path": record.semantic_path,
        "hazard_path": record.hazard_path,
        "hazard_valid_path": record.hazard_valid_path,
        "semantic_source_path": record.semantic_source_path,
        "semantic_weight_path": record.semantic_weight_path,
        "xml_hazard_polygon_counts": dict(record.hazard_instances),
        "class_pixel_counts": dict(record.class_pixel_counts),
        "hazard_pixel_counts": dict(record.hazard_pixel_counts),
        "hazard_valid_pixel_counts": dict(record.hazard_valid_pixel_counts),
    }


def inventory_dataset(dataset_root: Path, output_dir: Path, seed: int = SEED) -> dict[str, Any]:
    records, source_summary = discover_records(dataset_root)
    audit = assign_group_splits(records, seed)
    manifest = {
        "schema_version": 1,
        "seed": seed,
        "class_names": list(CLASS_NAMES),
        "hazard_names": list(HAZARD_NAMES),
        "records": [_to_manifest_record(record) for record in records],
    }
    report = {
        "schema_version": 1,
        "dataset_root": str(dataset_root.expanduser().resolve()),
        "source_inventory": source_summary,
        "split_audit": audit,
        "frequencies": {
            "xml_semantic_polygons_by_source": dict(source_summary["surface"]["xml_semantic_polygon_counts"]),
            "xml_hazard_polygons_by_source": dict(source_summary["surface"]["xml_hazard_polygon_counts"]),
            "semantic_pixels": None,
            "hazard_pixels": None,
            "note": (
                "Inventory-only reports XML semantic/hazard polygon frequencies, not pixel areas. "
                "Per-pixel frequencies are populated by --convert."
            ),
        },
        "conversion": {"status": "not_run"},
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    _save_json(output_dir / "manifest.json", manifest)
    _save_json(output_dir / "inventory_report.json", report)
    return {"manifest": manifest, "report": report, "records": records}


def select_conversion_records(
    records: Sequence[DatasetRecord],
    per_source: int,
    seed: int = SEED,
) -> list[DatasetRecord]:
    if per_source < 1:
        raise ValueError("Sample size per source must be positive.")
    selected: list[DatasetRecord] = []
    for source in ("surface", "cityscapes"):
        candidates = [
            record
            for record in records
            if record.source == source and record.semantic_annotation_path is not None
        ]
        tie_key = lambda record: hashlib.sha256(
            f"{seed}:{record.record_id}".encode("utf-8")
        ).hexdigest()
        ordered = sorted(candidates, key=tie_key)
        chosen: list[DatasetRecord] = []
        if source == "surface":
            annotated = [
                record for record in ordered
                if record.xml_image_name and record.xml_path and record.semantic_annotation_path
            ]
            for hazard in HAZARD_NAMES:
                options = [
                    record for record in annotated
                    if record.hazard_instances.get(hazard, 0) > 0 and record not in chosen
                ]
                if options and len(chosen) < per_source:
                    chosen.append(options[0])
            if len(chosen) < per_source and annotated:
                chosen.append(next((record for record in annotated if record not in chosen), annotated[0]))
        for record in ordered:
            if len(chosen) >= per_source:
                break
            if record not in chosen:
                chosen.append(record)
        selected.extend(chosen)
    if not selected:
        raise DatasetError("No paired Surface or Cityscapes records are available for conversion.")
    return selected


def convert_inventory(
    inventory: dict[str, Any],
    output_dir: Path,
    seed: int = SEED,
    sample_per_source: int | None = None,
) -> dict[str, Any]:
    np, image_types = require_image_dependencies()
    Image, _ = image_types
    all_records: list[DatasetRecord] = inventory["records"]
    records = (
        select_conversion_records(all_records, sample_per_source, seed)
        if sample_per_source is not None
        else all_records
    )
    if sample_per_source is not None:
        selected_ids = {record.record_id for record in records}
        for record in all_records:
            if record.record_id in selected_ids:
                continue
            record.generated_image_path = None
            record.semantic_path = None
            record.hazard_path = None
            record.hazard_valid_path = None
            record.semantic_source_path = None
            record.semantic_weight_path = None
            record.class_pixel_counts.clear()
            record.hazard_pixel_counts.clear()
            record.hazard_valid_pixel_counts.clear()
    manifest_path = output_dir / "manifest.json"
    semantic_counts: dict[str, Counter[str]] = defaultdict(Counter)
    hazard_counts: dict[str, Counter[str]] = defaultdict(Counter)
    hazard_valid_counts: dict[str, Counter[str]] = defaultdict(Counter)
    xml_cache = local()

    def convert_record(record: DatasetRecord) -> None:
        with Image.open(record.image_path) as source_image:
            image = source_image.convert("RGB")
        boxed_image, geometry = letterbox_image(image)
        folder = Path("converted") / record.split / record.source
        image_rel = folder / f"{record.record_id}.png"
        image_out = output_dir / image_rel
        image_out.parent.mkdir(parents=True, exist_ok=True)
        boxed_image.save(image_out, format="PNG")
        record.generated_image_path = image_rel.as_posix()

        semantic_canvas = Image.new("L", (OUT_W, OUT_H), color=IGNORE)
        semantic_annotation = record.semantic_annotation_path
        if semantic_annotation:
            with Image.open(semantic_annotation) as mask:
                if record.source == "surface":
                    categorical = Image.fromarray(_surface_mask_to_classes(mask), mode="L")
                else:
                    categorical = Image.fromarray(_cityscapes_mask_to_classes(mask), mode="L")
                left, top, width, height = geometry
                categorical = categorical.resize((width, height), Image.Resampling.NEAREST)
                semantic_canvas.paste(categorical, (left, top))
        semantic_rel = folder / f"{record.record_id}.semantic.png"
        semantic_out = output_dir / semantic_rel
        semantic_out.parent.mkdir(parents=True, exist_ok=True)
        semantic_canvas.save(semantic_out, format="PNG")
        record.semantic_path = semantic_rel.as_posix()

        labels = np.asarray(semantic_canvas, dtype=np.uint8)
        semantic_source = (labels != IGNORE).astype(np.uint8)
        semantic_weight = semantic_source.astype(np.float32)
        source_rel = folder / f"{record.record_id}.provenance.npz"
        np.savez_compressed(
            output_dir / source_rel,
            semantic_source=semantic_source,
            semantic_weight=semantic_weight,
        )
        record.semantic_source_path = source_rel.as_posix()
        record.semantic_weight_path = source_rel.as_posix()

        if record.source == "surface":
            active_xml_path = getattr(xml_cache, "path", None)
            active_xml_frames = getattr(xml_cache, "frames", {})
            if record.xml_path and record.xml_path != active_xml_path:
                _, active_xml_frames, _ = parse_surface_xml(record.xml_path)
                xml_cache.path = record.xml_path
                xml_cache.frames = active_xml_frames
            native_layer, code_map = _surface_hazard_layer(
                record.xml_path,
                record.xml_image_name,
                image.width,
                image.height,
                active_xml_frames.get(record.xml_image_name or "", []),
            )
            hazards, hazard_valid = _hazard_arrays(native_layer, code_map, geometry)
        else:
            hazards = np.zeros((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.uint8)
            hazard_valid = np.zeros_like(hazards)
        hazard_rel = folder / f"{record.record_id}.hazards.npz"
        np.savez_compressed(output_dir / hazard_rel, hazard=hazards, hazard_valid=hazard_valid)
        record.hazard_path = hazard_rel.as_posix()
        record.hazard_valid_path = hazard_rel.as_posix()

        class_pixel_counts = np.bincount(labels[labels != IGNORE], minlength=len(CLASS_NAMES))
        record.class_pixel_counts = Counter(
            {name: int(class_pixel_counts[index]) for index, name in enumerate(CLASS_NAMES)}
        )
        hazard_pixel_counts = hazards.sum(axis=(0, 1))
        record.hazard_pixel_counts = Counter(
            {name: int(hazard_pixel_counts[index]) for index, name in enumerate(HAZARD_NAMES)}
        )
        hazard_valid_pixel_counts = hazard_valid.sum(axis=(0, 1))
        record.hazard_valid_pixel_counts = Counter(
            {name: int(hazard_valid_pixel_counts[index]) for index, name in enumerate(HAZARD_NAMES)}
        )

    if records:
        with ThreadPoolExecutor(max_workers=min(4, len(records))) as executor:
            list(executor.map(convert_record, records))

    for record in records:
        semantic_counts[record.source].update(record.class_pixel_counts)
        hazard_counts[record.source].update(record.hazard_pixel_counts)
        hazard_valid_counts[record.source].update(record.hazard_valid_pixel_counts)
    converted = len(records)

    manifest = {
        "schema_version": 1,
        "seed": seed,
        "class_names": list(CLASS_NAMES),
        "hazard_names": list(HAZARD_NAMES),
        "records": [_to_manifest_record(record) for record in all_records],
    }
    _save_json(manifest_path, manifest)
    report_path = output_dir / "inventory_report.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    report["frequencies"] = {
        "semantic_pixels": {
            source: dict(counts) for source, counts in sorted(semantic_counts.items())
        },
        "hazard_pixels": {
            source: dict(counts) for source, counts in sorted(hazard_counts.items())
        },
        "hazard_valid_pixels": {
            source: dict(counts) for source, counts in sorted(hazard_valid_counts.items())
        },
        "xml_hazard_polygons_by_source": dict(
            report["source_inventory"]["surface"]["xml_hazard_polygon_counts"]
        ),
        "xml_semantic_polygons_by_source": dict(
            report["source_inventory"]["surface"]["xml_semantic_polygon_counts"]
        ),
        "note": "Pixel counts exclude ignore/padding; hazard valid coverage is recorded separately.",
    }
    report["conversion"] = {
        "status": "partial" if converted < len(all_records) else "complete",
        "converted_records": converted,
        "inventory_records": len(all_records),
        "sample_per_source": sample_per_source,
        "output_size": {"width": OUT_W, "height": OUT_H},
    }
    _save_json(report_path, report)
    _save_json(
        output_dir / "completion_report.json",
        {
            "status": "partial" if converted < len(all_records) else "complete",
            "converted_records": converted,
            "inventory_records": len(all_records),
            "sample_per_source": sample_per_source,
            "manifest": "manifest.json",
            "inventory_report": "inventory_report.json",
            "semantic_pixel_counts": report["frequencies"]["semantic_pixels"],
            "hazard_pixel_counts": report["frequencies"]["hazard_pixels"],
            "hazard_valid_pixel_counts": report["frequencies"]["hazard_valid_pixels"],
        },
    )
    return manifest


def load_manifest_records(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DatasetError(f"Unable to read manifest {path}: {exc}") from exc
    if manifest.get("schema_version") != 1 or not isinstance(manifest.get("records"), list):
        raise DatasetError(f"Unsupported or malformed manifest: {path}")
    return manifest, manifest["records"]


def load_inventory_manifest(manifest_path: Path) -> dict[str, Any]:
    manifest_path = manifest_path.expanduser().resolve()
    manifest, raw_records = load_manifest_records(manifest_path)
    report_path = manifest_path.parent / "inventory_report.json"
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DatasetError(f"Unable to read inventory report {report_path}: {exc}") from exc

    records: list[DatasetRecord] = []
    for raw in raw_records:
        annotations = raw.get("annotation_paths", {})
        hashes = raw.get("annotation_sha256", {})
        records.append(
            DatasetRecord(
                record_id=raw["id"],
                source=raw["source"],
                group_seed=raw.get("group_id") or raw["id"],
                source_split=raw["source_split"],
                image_path=Path(raw["image_path"]),
                image_sha256=raw["image_sha256"],
                semantic_annotation_path=Path(annotations["semantic"])
                if annotations.get("semantic")
                else None,
                xml_path=Path(annotations["xml"]) if annotations.get("xml") else None,
                xml_image_name=annotations.get("xml_image_name"),
                city=raw.get("city"),
                hazard_instances=Counter(raw.get("xml_hazard_polygon_counts", {})),
                class_pixel_counts=Counter(raw.get("class_pixel_counts", {})),
                hazard_pixel_counts=Counter(raw.get("hazard_pixel_counts", {})),
                hazard_valid_pixel_counts=Counter(raw.get("hazard_valid_pixel_counts", {})),
                mask_sha256=hashes.get("semantic"),
                xml_sha256=hashes.get("xml"),
                split=raw["split"],
                group_id=raw.get("group_id"),
                generated_image_path=raw.get("image_tensor_path"),
                semantic_path=raw.get("semantic_path"),
                hazard_path=raw.get("hazard_path"),
                hazard_valid_path=raw.get("hazard_valid_path"),
                semantic_source_path=raw.get("semantic_source_path"),
                semantic_weight_path=raw.get("semantic_weight_path"),
            )
        )
    return {"manifest": manifest, "report": report, "records": records}


def count_png_rgb_colors(path: Path) -> Counter[tuple[int, int, int]]:
    """Count 8-bit RGB/RGBA PNG colors with stdlib only for inventory audits."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise DatasetError(f"Not a PNG file: {path}")
    offset = 8
    compressed = bytearray()
    width = height = color_type = bit_depth = interlace = 0
    while offset < len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        payload = data[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if compression or filtering or interlace or bit_depth != 8 or color_type not in (2, 6):
                raise DatasetError(
                    f"Inventory PNG parser supports only non-interlaced 8-bit RGB/RGBA masks: {path}"
                )
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
    channels = 3 if color_type == 2 else 4
    bytes_per_pixel = channels
    raw = zlib.decompress(compressed)
    row_bytes = width * channels
    previous = bytearray(row_bytes)
    counts: Counter[tuple[int, int, int]] = Counter()
    cursor = 0
    for _ in range(height):
        filter_type = raw[cursor]
        cursor += 1
        row = bytearray(raw[cursor : cursor + row_bytes])
        cursor += row_bytes
        for index in range(row_bytes):
            left = row[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            above = previous[index]
            upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            if filter_type == 1:
                row[index] = (row[index] + left) & 255
            elif filter_type == 2:
                row[index] = (row[index] + above) & 255
            elif filter_type == 3:
                row[index] = (row[index] + ((left + above) >> 1)) & 255
            elif filter_type == 4:
                base = left + above - upper_left
                distance_left = abs(base - left)
                distance_above = abs(base - above)
                distance_upper_left = abs(base - upper_left)
                predictor = left if distance_left <= distance_above and distance_left <= distance_upper_left else (
                    above if distance_above <= distance_upper_left else upper_left
                )
                row[index] = (row[index] + predictor) & 255
            elif filter_type != 0:
                raise DatasetError(f"Unknown PNG filter {filter_type} in {path}")
        if channels == 3:
            counts.update(
                (row[index], row[index + 1], row[index + 2])
                for index in range(0, row_bytes, channels)
            )
        else:
            counts.update(
                (row[index], row[index + 1], row[index + 2])
                for index in range(0, row_bytes, channels)
            )
        previous = row
    return counts
