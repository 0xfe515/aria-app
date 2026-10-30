from __future__ import annotations

import json
import ntpath
import os
import random
import subprocess
import zipfile
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Generic, Iterable, Iterator, TypeVar

from .data import CLASS_NAMES, HAZARD_NAMES, IGNORE, OUT_H, OUT_W, letterbox_geometry


_T = TypeVar("_T")
_SOURCES = frozenset(("cityscapes", "surface"))
_SPLITS = frozenset(("train", "val", "test"))
_IMAGE_KEYS = ("image", "semantic", "semantic_weight", "semantic_source", "hazard", "hazard_valid")
_METADATA_KEYS = ("id", "source", "group_id", "split")


class PortraitDatasetError(ValueError):
    """Raised when a training manifest or one of its converted records is invalid."""


class CountedIterator(Iterator[_T], Generic[_T]):
    def __init__(self, iterable: Iterable[_T], sample_size=lambda _item: 1) -> None:
        self._iterator = iter(iterable)
        self._sample_size = sample_size
        self.yielded_count = 0
        self.yielded_samples = 0

    def __iter__(self) -> CountedIterator[_T]:
        return self

    def __next__(self) -> _T:
        item = next(self._iterator)
        self.yielded_count += 1
        self.yielded_samples += self._sample_size(item)
        return item


@dataclass(frozen=True)
class _Record:
    record_id: str
    source: str
    split: str
    group_id: str
    image_sha256: str
    original_image: Path
    paths: dict[str, Path]
    raw: dict[str, Any]


class PortraitDataset:
    """Strict, memory-bounded reader for converted portrait training data."""

    def __init__(
        self,
        manifest_path: str | Path,
        split: str,
        sources: str | Iterable[str] | None = None,
        pseudo_manifest_path: str | Path | None = None,
        seed: int = 42,
        require_complete: bool = True,
        *,
        path_resolver: Any = None,
        _is_windows: bool | None = None,
    ) -> None:
        if not isinstance(split, str) or split not in _SPLITS:
            raise PortraitDatasetError(f"Unknown split {split!r}; expected train, val, or test.")
        if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
            raise PortraitDatasetError("seed must be a non-negative integer.")
        if not isinstance(require_complete, bool):
            raise PortraitDatasetError("require_complete must be a boolean.")

        self.manifest_path = Path(manifest_path).expanduser().resolve()
        self.split = split
        self.seed = seed
        self.require_complete = require_complete
        self._path_resolver = path_resolver
        self._is_windows = _is_windows
        self._np, self._Image, self._ImageEnhance, self._ImageFilter = _image_dependencies()
        self._manifest = _read_json(self.manifest_path, "dataset manifest")
        self._validate_manifest_header(self._manifest)
        records = self._validate_records(self._manifest["records"])
        self._records_by_id = {record.record_id: record for record in records}

        source_filter = _normalize_sources(sources)
        selected = [
            record
            for record in records
            if record.split == split and (source_filter is None or record.source in source_filter)
        ]
        if not selected:
            raise PortraitDatasetError(
                f"No records match split={split!r} and sources={sorted(source_filter) if source_filter else None}."
            )

        complete_records: list[_Record] = []
        incomplete_ids: list[str] = []
        for record in selected:
            absent = [
                key
                for key in ("image_tensor_path", "semantic_path", "hazard_path",
                            "hazard_valid_path", "semantic_source_path", "semantic_weight_path")
                if not record.raw.get(key)
            ]
            if absent:
                if require_complete:
                    raise PortraitDatasetError(
                        f"Record {record.record_id} is not converted; missing manifest paths: {absent}."
                    )
                incomplete_ids.append(record.record_id)
                continue
            self._validate_files_exist(record)
            complete_records.append(record)

        if require_complete and len(complete_records) != len(selected):
            raise PortraitDatasetError("The selected split is incomplete.")
        self._records = complete_records
        self.diagnostics = {
            "mode": "training" if require_complete else "diagnostic-only",
            "selected_records": len(selected),
            "complete_records": len(complete_records),
            "incomplete_record_ids": tuple(incomplete_ids),
        }
        self.training_eligible = require_complete and split == "train" and not incomplete_ids
        self._pseudo = self._load_pseudo_records(pseudo_manifest_path, records)

    @staticmethod
    def _validate_manifest_header(manifest: Any) -> None:
        if not isinstance(manifest, dict):
            raise PortraitDatasetError("Dataset manifest root must be a JSON object.")
        if (
            isinstance(manifest.get("schema_version"), bool)
            or not isinstance(manifest.get("schema_version"), int)
            or manifest.get("schema_version") != 1
        ):
            raise PortraitDatasetError(
                f"Unsupported schema_version {manifest.get('schema_version')!r}; expected 1."
            )
        if manifest.get("class_names") != list(CLASS_NAMES):
            raise PortraitDatasetError(f"class_names must exactly match {list(CLASS_NAMES)}.")
        if manifest.get("hazard_names") != list(HAZARD_NAMES):
            raise PortraitDatasetError(f"hazard_names must exactly match {list(HAZARD_NAMES)}.")
        if isinstance(manifest.get("seed"), bool) or not isinstance(manifest.get("seed"), int):
            raise PortraitDatasetError("Dataset manifest seed must be an integer.")
        if not isinstance(manifest.get("records"), list):
            raise PortraitDatasetError("Dataset manifest records must be a list.")

    def _validate_records(self, raw_records: list[Any]) -> list[_Record]:
        records: list[_Record] = []
        ids: set[str] = set()
        group_splits: dict[str, str] = {}
        hash_splits: dict[str, str] = {}
        parent_cache: dict[Path, Path] = {}
        for index, raw in enumerate(raw_records):
            if not isinstance(raw, dict):
                raise PortraitDatasetError(f"Manifest record {index} must be a JSON object.")
            record_id = raw.get("id")
            source = raw.get("source")
            split = raw.get("split")
            group_id = raw.get("group_id")
            image_hash = raw.get("image_sha256")
            source_split = raw.get("source_split")
            if not isinstance(record_id, str) or not record_id.strip():
                raise PortraitDatasetError(f"Manifest record {index} has an invalid id.")
            if record_id in ids:
                raise PortraitDatasetError(f"Duplicate manifest id {record_id!r}.")
            ids.add(record_id)
            if not isinstance(source, str) or source not in _SOURCES:
                raise PortraitDatasetError(f"Record {record_id} has unknown source {source!r}.")
            if not isinstance(split, str) or split not in _SPLITS:
                raise PortraitDatasetError(f"Record {record_id} has unknown split {split!r}.")
            if not isinstance(source_split, str) or not source_split.strip():
                raise PortraitDatasetError(f"Record {record_id} has no valid source_split.")
            if (
                source == "surface" and source_split != "surface"
                or source == "cityscapes" and source_split not in {"city-train", "city-official-val"}
            ):
                raise PortraitDatasetError(
                    f"Record {record_id} source_split {source_split!r} is inconsistent with {source!r}."
                )
            if not isinstance(group_id, str) or not group_id.strip():
                raise PortraitDatasetError(f"Record {record_id} has no valid group_id.")
            if not _is_sha256(image_hash):
                raise PortraitDatasetError(f"Record {record_id} has an invalid image_sha256.")
            previous_split = group_splits.setdefault(group_id, split)
            if previous_split != split:
                raise PortraitDatasetError(
                    f"Group {group_id!r} crosses splits {previous_split!r} and {split!r}."
                )
            previous_split = hash_splits.setdefault(image_hash, split)
            if previous_split != split:
                raise PortraitDatasetError(
                    f"Image hash {image_hash} crosses splits {previous_split!r} and {split!r}."
                )

            original_raw = raw.get("image_path")
            if not isinstance(original_raw, str) or not original_raw.strip():
                raise PortraitDatasetError(f"Record {record_id} image_path must be absolute.")
            original_image = _resolve_original_image_path(
                original_raw,
                record_id,
                is_windows=self._is_windows,
                resolver=self._path_resolver,
                parent_cache=parent_cache,
            )
            paths: dict[str, Path] = {}
            for key in (
                "image_tensor_path", "semantic_path", "hazard_path", "hazard_valid_path",
                "semantic_source_path", "semantic_weight_path",
            ):
                value = raw.get(key)
                if value:
                    paths[key] = self._manifest_relative_path(value, key, record_id)
            records.append(
                _Record(
                    record_id=record_id,
                    source=source,
                    split=split,
                    group_id=group_id,
                    image_sha256=image_hash,
                    original_image=original_image,
                    paths=paths,
                    raw=raw,
                )
            )
        return records

    def _manifest_relative_path(self, value: Any, key: str, record_id: str) -> Path:
        if not isinstance(value, str) or not value.strip():
            raise PortraitDatasetError(f"Record {record_id} has an invalid {key}.")
        relative = Path(value)
        if relative.is_absolute():
            raise PortraitDatasetError(f"Record {record_id} {key} must be relative to the manifest.")
        resolved = (self.manifest_path.parent / relative).resolve()
        try:
            resolved.relative_to(self.manifest_path.parent)
        except ValueError as exc:
            raise PortraitDatasetError(
                f"Record {record_id} {key} resolves outside the manifest directory."
            ) from exc
        return resolved

    def _validate_files_exist(self, record: _Record) -> None:
        required = (
            "image_tensor_path", "semantic_path", "hazard_path", "hazard_valid_path",
            "semantic_source_path", "semantic_weight_path",
        )
        for key in required:
            path = record.paths.get(key)
            if path is None or not path.is_file():
                raise PortraitDatasetError(
                    f"Record {record.record_id} has a missing converted file for {key}: {path}."
                )
        if not record.original_image.is_file():
            raise PortraitDatasetError(
                f"Record {record.record_id} original image is missing: {record.original_image}."
            )

    def _load_pseudo_records(
        self,
        pseudo_manifest_path: str | Path | None,
        records: list[_Record],
    ) -> dict[str, tuple[Path, Path]]:
        if pseudo_manifest_path is None:
            return {}
        if self.split != "train":
            raise PortraitDatasetError("Pseudo-label overlays are permitted only for split='train'.")
        path = Path(pseudo_manifest_path).expanduser().resolve()
        pseudo_manifest = _read_json(path, "pseudo manifest")
        if (
            not isinstance(pseudo_manifest, dict)
            or isinstance(pseudo_manifest.get("schema_version"), bool)
            or not isinstance(pseudo_manifest.get("schema_version"), int)
            or pseudo_manifest.get("schema_version") != 1
        ):
            raise PortraitDatasetError("Pseudo manifest must be a schema_version 1 JSON object.")
        pseudo_rows = pseudo_manifest.get("records")
        if not isinstance(pseudo_rows, list):
            raise PortraitDatasetError("Pseudo manifest records must be a list.")
        base_records = {record.record_id: record for record in records}
        pseudo_paths: dict[str, tuple[Path, Path]] = {}
        for index, row in enumerate(pseudo_rows):
            if not isinstance(row, dict):
                raise PortraitDatasetError(f"Pseudo record {index} must be a JSON object.")
            record_id = row.get("id")
            if not isinstance(record_id, str) or record_id in pseudo_paths:
                raise PortraitDatasetError(f"Pseudo record {index} has a missing or duplicate id.")
            base = base_records.get(record_id)
            if base is None:
                raise PortraitDatasetError(f"Pseudo record {record_id!r} is not in the base manifest.")
            if (
                row.get("source") != "surface"
                or row.get("split") != "train"
                or base.source != "surface"
                or base.split != "train"
                or row.get("source") != base.source
                or row.get("split") != base.split
                or row.get("image_sha256") != base.image_sha256
                or row.get("group_id") != base.group_id
            ):
                raise PortraitDatasetError(
                    f"Pseudo record {record_id} does not match its Surface train source, split, "
                    "image hash, and group."
                )
            semantic_path = _pseudo_relative_path(path.parent, row.get("semantic_path"), record_id)
            weight_path = _pseudo_relative_path(
                path.parent, row.get("semantic_weight_path"), record_id
            )
            for label, file_path in (("semantic_path", semantic_path), ("semantic_weight_path", weight_path)):
                if not file_path.is_file():
                    raise PortraitDatasetError(
                        f"Pseudo record {record_id} has a missing {label}: {file_path}."
                    )
            pseudo_paths[record_id] = (semantic_path, weight_path)
        return pseudo_paths

    def sample_count(self, epoch: int = 0, balanced: bool = False) -> int:
        return len(self._ordered_records(epoch, balanced))

    def batch_count(self, batch_size: int, epoch: int = 0, balanced: bool = False) -> int:
        if isinstance(batch_size, bool) or not isinstance(batch_size, int) or batch_size < 1:
            raise PortraitDatasetError("batch_size must be a positive integer.")
        count = self.sample_count(epoch, balanced)
        return (count + batch_size - 1) // batch_size

    def _ordered_records(self, epoch: int, balanced: bool) -> list[_Record]:
        if isinstance(epoch, bool) or not isinstance(epoch, int) or epoch < 0:
            raise PortraitDatasetError("epoch must be a non-negative integer.")
        if not isinstance(balanced, bool):
            raise PortraitDatasetError("balanced must be a boolean.")
        rng = random.Random(self.seed + epoch)
        if not balanced:
            ordered = sorted(self._records, key=lambda record: record.record_id)
            rng.shuffle(ordered)
            return ordered
        if self.split != "train":
            raise PortraitDatasetError("Balanced sampling is supported only for split='train'.")
        by_source = {
            source: sorted(
                (record for record in self._records if record.source == source),
                key=lambda record: record.record_id,
            )
            for source in ("cityscapes", "surface")
        }
        if not all(by_source.values()):
            raise PortraitDatasetError(
                "Balanced sampling requires at least one complete record from both sources."
            )
        for source_records in by_source.values():
            rng.shuffle(source_records)
        pair_count = max(len(by_source["cityscapes"]), len(by_source["surface"]))
        pairs = [
            (
                by_source["cityscapes"][index % len(by_source["cityscapes"])],
                by_source["surface"][index % len(by_source["surface"])],
            )
            for index in range(pair_count)
        ]
        rng.shuffle(pairs)
        return [record for pair in pairs for record in pair]

    def iter_samples(
        self,
        epoch: int = 0,
        augment: bool = False,
        balanced: bool = False,
    ) -> CountedIterator[dict[str, Any]]:
        if not isinstance(augment, bool) or not isinstance(balanced, bool):
            raise PortraitDatasetError("augment and balanced must be booleans.")
        records = self._ordered_records(epoch, balanced)
        rng = random.Random(self.seed + epoch)

        def generate() -> Iterator[dict[str, Any]]:
            for record in records:
                sample, content_valid = self._load_sample(record)
                if augment:
                    sample = _augment_sample(sample, content_valid, rng, self._np, self._Image,
                                             self._ImageEnhance, self._ImageFilter)
                yield sample

        return CountedIterator(generate())

    def iter_batches(
        self,
        batch_size: int,
        epoch: int = 0,
        augment: bool = False,
        balanced: bool = False,
    ) -> CountedIterator[dict[str, Any]]:
        if isinstance(batch_size, bool) or not isinstance(batch_size, int) or batch_size < 1:
            raise PortraitDatasetError("batch_size must be a positive integer.")
        samples = self.iter_samples(epoch=epoch, augment=augment, balanced=balanced)

        def generate() -> Iterator[dict[str, Any]]:
            pending: list[dict[str, Any]] = []
            for sample in samples:
                pending.append(sample)
                if len(pending) == batch_size:
                    yield _stack_batch(pending, self._np)
                    pending = []
            if pending:
                yield _stack_batch(pending, self._np)

        return CountedIterator(generate(), sample_size=lambda batch: len(batch["id"]))

    def _load_sample(self, record: _Record) -> tuple[dict[str, Any], Any]:
        np = self._np
        try:
            with self._Image.open(record.original_image) as original:
                geometry = letterbox_geometry(original.width, original.height)
            with self._Image.open(record.paths["image_tensor_path"]) as image_file:
                if image_file.mode != "RGB":
                    raise PortraitDatasetError(
                        f"Record {record.record_id} converted image must be RGB, got {image_file.mode}."
                    )
                image = np.asarray(image_file)
            with self._Image.open(record.paths["semantic_path"]) as semantic_file:
                semantic = np.asarray(semantic_file)
        except (OSError, ValueError) as exc:
            raise PortraitDatasetError(f"Unable to decode sample {record.record_id}: {exc}") from exc
        if image.dtype != np.uint8 or image.shape != (OUT_H, OUT_W, 3):
            raise PortraitDatasetError(
                f"Record {record.record_id} image shape must be {(OUT_H, OUT_W, 3)}, got {image.shape}."
            )
        if semantic.dtype != np.uint8 or semantic.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(
                f"Record {record.record_id} semantic shape must be {(OUT_H, OUT_W)}, got {semantic.shape}."
            )
        if np.any(~np.isin(semantic, (*range(len(CLASS_NAMES)), IGNORE))):
            labels = np.unique(semantic[~np.isin(semantic, (*range(len(CLASS_NAMES)), IGNORE))]).tolist()
            raise PortraitDatasetError(
                f"Record {record.record_id} contains unknown semantic labels {labels}."
            )

        source, weight = self._load_provenance(record)
        hazard, hazard_valid = self._load_hazards(record)
        left, top, content_width, content_height = geometry
        content_valid = np.zeros((OUT_H, OUT_W), dtype=np.bool_)
        content_valid[top:top + content_height, left:left + content_width] = True
        padding = ~content_valid
        if np.any(semantic[padding] != IGNORE):
            raise PortraitDatasetError(f"Record {record.record_id} has non-ignore labels in letterbox padding.")
        if np.any(source[padding] != 0) or np.any(weight[padding] != 0):
            raise PortraitDatasetError(f"Record {record.record_id} has semantic supervision in letterbox padding.")
        if np.any(hazard[padding]) or np.any(hazard_valid[padding]):
            raise PortraitDatasetError(f"Record {record.record_id} has hazard data in letterbox padding.")
        if np.any((source == 0) != (semantic == IGNORE)):
            raise PortraitDatasetError(
                f"Record {record.record_id} semantic labels and provenance ignore mask disagree."
            )
        if np.any((source == 1) & (weight != 1.0)):
            raise PortraitDatasetError(f"Record {record.record_id} real semantic weights must be exactly 1.0.")
        if np.any((source == 2) & (weight != 0.25)):
            raise PortraitDatasetError(f"Record {record.record_id} pseudo semantic weights must be exactly 0.25.")
        if np.any((hazard > 0) & (hazard_valid == 0)):
            raise PortraitDatasetError(f"Record {record.record_id} has positive hazards outside valid regions.")

        pseudo = self._pseudo.get(record.record_id)
        if pseudo is not None:
            pseudo_labels, pseudo_source, pseudo_weight = self._load_pseudo(record, pseudo)
            accepted = pseudo_source == 2
            if np.any(accepted & (semantic != IGNORE)):
                raise PortraitDatasetError(
                    f"Pseudo labels for {record.record_id} overlap original semantic annotations."
                )
            semantic = semantic.copy()
            source = source.copy()
            weight = weight.copy()
            semantic[accepted] = pseudo_labels[accepted]
            source[accepted] = pseudo_source[accepted]
            weight[accepted] = pseudo_weight[accepted]

        sample = {
            "image": image.astype(np.float32),
            "semantic": semantic.astype(np.int32),
            "semantic_weight": weight.astype(np.float32, copy=False),
            "semantic_source": source.astype(np.uint8, copy=False),
            "hazard": hazard.astype(np.float32),
            "hazard_valid": hazard_valid.astype(np.float32),
            "id": record.record_id,
            "source": record.source,
            "group_id": record.group_id,
            "split": record.split,
        }
        return sample, content_valid

    def _load_provenance(self, record: _Record) -> tuple[Any, Any]:
        np = self._np
        source_path = record.paths["semantic_source_path"]
        weight_path = record.paths["semantic_weight_path"]
        if source_path == weight_path:
            arrays = _load_npz(source_path, ("semantic_source", "semantic_weight"), record.record_id)
            source = arrays["semantic_source"]
            weight = arrays["semantic_weight"]
        else:
            source = _load_npz(source_path, ("semantic_source",), record.record_id)["semantic_source"]
            weight = _load_npz(weight_path, ("semantic_weight",), record.record_id)["semantic_weight"]
        if source.dtype != np.uint8 or source.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(
                f"Record {record.record_id} semantic_source must be uint8 {(OUT_H, OUT_W)}."
            )
        if weight.dtype != np.float32 or weight.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(
                f"Record {record.record_id} semantic_weight must be float32 {(OUT_H, OUT_W)}."
            )
        if np.any(~np.isin(source, (0, 1))):
            raise PortraitDatasetError(
                f"Base record {record.record_id} semantic_source may contain only 0=ignore and 1=real."
            )
        if np.any((source == 0) & (weight != 0.0)) or np.any((source == 1) & (weight != 1.0)):
            raise PortraitDatasetError(
                f"Record {record.record_id} provenance IDs and weights must be exactly (0,0.0) or (1,1.0)."
            )
        return source, weight

    def _load_hazards(self, record: _Record) -> tuple[Any, Any]:
        np = self._np
        hazard_path = record.paths["hazard_path"]
        valid_path = record.paths["hazard_valid_path"]
        if hazard_path == valid_path:
            arrays = _load_npz(hazard_path, ("hazard", "hazard_valid"), record.record_id)
            hazard = arrays["hazard"]
            valid = arrays["hazard_valid"]
        else:
            hazard = _load_npz(hazard_path, ("hazard",), record.record_id)["hazard"]
            valid = _load_npz(valid_path, ("hazard_valid",), record.record_id)["hazard_valid"]
        expected = (OUT_H, OUT_W, len(HAZARD_NAMES))
        if hazard.dtype != np.uint8 or hazard.shape != expected:
            raise PortraitDatasetError(f"Record {record.record_id} hazard must be uint8 {expected}.")
        if valid.dtype != np.uint8 or valid.shape != expected:
            raise PortraitDatasetError(f"Record {record.record_id} hazard_valid must be uint8 {expected}.")
        if np.any(~np.isin(hazard, (0, 1))) or np.any(~np.isin(valid, (0, 1))):
            raise PortraitDatasetError(f"Record {record.record_id} hazard arrays must contain only 0 or 1.")
        return hazard, valid

    def _load_pseudo(self, record: _Record, paths: tuple[Path, Path]) -> tuple[Any, Any, Any]:
        np = self._np
        try:
            with self._Image.open(paths[0]) as semantic_file:
                labels = np.asarray(semantic_file)
        except (OSError, ValueError) as exc:
            raise PortraitDatasetError(
                f"Unable to decode pseudo semantic labels for {record.record_id}: {exc}"
            ) from exc
        if labels.dtype != np.uint8 or labels.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(f"Pseudo semantic labels for {record.record_id} have the wrong shape.")
        arrays = _load_npz(
            paths[1], ("semantic_source", "semantic_weight"), f"pseudo record {record.record_id}"
        )
        source = arrays["semantic_source"]
        weight = arrays["semantic_weight"]
        if source.dtype != np.uint8 or source.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(
                f"Pseudo semantic_source for {record.record_id} must be uint8 {(OUT_H, OUT_W)}."
            )
        if weight.dtype != np.float32 or weight.shape != (OUT_H, OUT_W):
            raise PortraitDatasetError(
                f"Pseudo semantic_weight for {record.record_id} must be float32 {(OUT_H, OUT_W)}."
            )
        accepted = source == 2
        if (
            np.any(~np.isin(source, (0, 2)))
            or np.any((source == 0) & (weight != 0.0))
            or np.any(accepted & (weight != 0.25))
            or np.any(accepted & ~np.isin(labels, (4, 5, 6)))
            or np.any((source == 0) & (labels != IGNORE))
        ):
            raise PortraitDatasetError(
                f"Pseudo labels for {record.record_id} must pair source 2/object IDs 4-6/weight .25 "
                "and source 0/ignore/weight 0."
            )
        left, top, content_width, content_height = letterbox_geometry(
            *self._original_size(record)
        )
        if np.any(accepted[:top]) or np.any(accepted[top + content_height:]):
            raise PortraitDatasetError(f"Pseudo labels for {record.record_id} cover letterbox padding.")
        if np.any(accepted[:, :left]) or np.any(accepted[:, left + content_width:]):
            raise PortraitDatasetError(f"Pseudo labels for {record.record_id} cover letterbox padding.")
        return labels, source, weight

    def _original_size(self, record: _Record) -> tuple[int, int]:
        try:
            with self._Image.open(record.original_image) as image:
                return image.width, image.height
        except OSError as exc:
            raise PortraitDatasetError(
                f"Unable to read original image dimensions for {record.record_id}: {exc}"
            ) from exc


def _image_dependencies() -> tuple[Any, Any, Any, Any]:
    try:
        import numpy as np
        from PIL import Image, ImageEnhance, ImageFilter
    except ImportError as exc:
        raise RuntimeError("The training loader requires NumPy and Pillow in the isolated environment.") from exc
    return np, Image, ImageEnhance, ImageFilter


def _read_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PortraitDatasetError(f"Unable to read {label} {path}: {exc}") from exc


def _normalize_sources(sources: str | Iterable[str] | None) -> frozenset[str] | None:
    if sources is None or sources == "mixed":
        return None
    if isinstance(sources, str):
        normalized = frozenset((sources,))
    else:
        try:
            normalized = frozenset(sources)
        except TypeError as exc:
            raise PortraitDatasetError("sources must be None, a source name, or an iterable of names.") from exc
    if not normalized or not normalized <= _SOURCES:
        raise PortraitDatasetError(f"sources must contain only {sorted(_SOURCES)}.")
    return normalized


def _is_sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(char in "0123456789abcdef" for char in value)


def _resolve_original_image_path(
    value: str,
    record_id: str,
    *,
    is_windows: bool | None = None,
    resolver: Any = None,
    parent_cache: dict[Path, Path] | None = None,
) -> Path:
    drive, tail = ntpath.splitdrive(value)
    if drive.startswith(("\\\\", "//")) or value.startswith(("//", "\\\\")):
        raise PortraitDatasetError(
            f"Record {record_id} uses an unsupported UNC image_path: {value!r}."
        )

    running_on_windows = os.name == "nt" if is_windows is None else is_windows
    if drive:
        if len(drive) != 2 or not drive[0].isalpha() or drive[1] != ":":
            raise PortraitDatasetError(
                f"Record {record_id} image_path has an invalid drive specification: {value!r}."
            )
        if not tail.startswith(("\\", "/")):
            raise PortraitDatasetError(
                f"Record {record_id} image_path is drive-relative, not absolute: {value!r}."
            )
        if running_on_windows:
            resolved = Path(value).expanduser().resolve()
            if resolved.drive and resolved.drive.upper() != drive.upper():
                raise PortraitDatasetError(
                    f"Record {record_id} image_path escapes drive {drive!r}: {value!r}."
                )
            return resolved

        drive_root = f"{drive[0].upper()}:\\"
        translated_root = _wsl_drive_root(drive_root, resolver=resolver)
        relative = tail.lstrip("\\/").replace("\\", "/")
        if not relative:
            return translated_root

        unresolved = translated_root / relative
        leaf = unresolved.name
        if leaf in (".", ".."):
            resolved = unresolved.resolve()
            try:
                resolved.relative_to(translated_root)
            except ValueError as exc:
                raise PortraitDatasetError(
                    f"Record {record_id} image_path escapes drive root {drive_root!r}: {value!r}."
                ) from exc
            return resolved

        parent_path = unresolved.parent
        if parent_cache is not None and parent_path in parent_cache:
            resolved_parent = parent_cache[parent_path]
        else:
            resolved_parent = parent_path.resolve()
            if parent_cache is not None:
                parent_cache[parent_path] = resolved_parent

        candidate = resolved_parent / leaf
        if candidate.is_symlink():
            resolved = candidate.resolve()
        else:
            resolved = candidate

        try:
            resolved.relative_to(translated_root)
        except ValueError as exc:
            raise PortraitDatasetError(
                f"Record {record_id} image_path escapes drive root {drive_root!r}: {value!r}."
            ) from exc
        return resolved

    path = Path(value)
    if running_on_windows:
        if path.is_absolute():
            return path.expanduser().resolve()
    else:
        if path.is_absolute() or value.startswith("/"):
            return path.expanduser().resolve()
    raise PortraitDatasetError(f"Record {record_id} image_path must be absolute: {value!r}.")


@lru_cache(maxsize=None)
def _wsl_drive_root(drive_root: str, resolver: Any = None) -> Path:
    if resolver is not None:
        try:
            raw_result = resolver(drive_root)
        except Exception as exc:
            if isinstance(exc, PortraitDatasetError):
                raise
            raise PortraitDatasetError(
                f"Resolver could not resolve Windows drive {drive_root!r}: {exc}"
            ) from exc
        translated = str(raw_result).strip()
    else:
        try:
            result = subprocess.run(
                ["wslpath", "-u", drive_root],
                capture_output=True,
                check=True,
                text=True,
                timeout=10,
                shell=False,
            )
            translated = result.stdout.strip()
        except FileNotFoundError as exc:
            raise PortraitDatasetError(
                "Cannot resolve a Windows image_path on POSIX: install or enable WSL's "
                "'wslpath' utility and ensure the Windows drive is mounted."
            ) from exc
        except subprocess.CalledProcessError as exc:
            detail = (exc.stderr or "").strip()
            raise PortraitDatasetError(
                f"wslpath could not resolve Windows drive {drive_root!r}"
                f"{': ' + detail if detail else '.'}"
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise PortraitDatasetError(
                f"wslpath timed out while resolving Windows drive {drive_root!r}."
            ) from exc
        except OSError as exc:
            raise PortraitDatasetError(
                f"Unable to run wslpath for Windows drive {drive_root!r}: {exc}"
            ) from exc

    if not translated:
        raise PortraitDatasetError(
            f"wslpath returned an empty path for Windows drive {drive_root!r}."
        )
    root = Path(translated)
    is_absolute = root.is_absolute() or translated.startswith("/")
    if not is_absolute:
        raise PortraitDatasetError(
            f"wslpath returned a malformed non-absolute path for Windows drive "
            f"{drive_root!r}: {translated!r}."
        )
    root = root.resolve()
    if not root.is_dir():
        raise PortraitDatasetError(
            f"wslpath resolved Windows drive {drive_root!r} to unavailable mount {root}."
        )
    return root


def _pseudo_relative_path(base_dir: Path, value: Any, record_id: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise PortraitDatasetError(f"Pseudo record {record_id} has an invalid file path.")
    relative = Path(value)
    if relative.is_absolute():
        raise PortraitDatasetError(f"Pseudo record {record_id} file paths must be relative.")
    resolved = (base_dir / relative).resolve()
    try:
        resolved.relative_to(base_dir.resolve())
    except ValueError as exc:
        raise PortraitDatasetError(f"Pseudo record {record_id} path escapes its manifest directory.") from exc
    return resolved


def _load_npz(path: Path, required: tuple[str, ...], record_id: str) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path, "r") as archive:
            if archive.testzip() is not None:
                raise PortraitDatasetError(f"Record {record_id} has a corrupt NPZ file: {path}.")
        import numpy as np

        with np.load(path, allow_pickle=False) as archive:
            missing = set(required) - set(archive.files)
            if missing:
                raise PortraitDatasetError(
                    f"Record {record_id} NPZ {path} is missing arrays {sorted(missing)}."
                )
            return {key: archive[key].copy() for key in required}
    except PortraitDatasetError:
        raise
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        raise PortraitDatasetError(f"Unable to load NPZ for record {record_id} at {path}: {exc}") from exc


def _augment_sample(
    sample: dict[str, Any],
    content_valid: Any,
    rng: random.Random,
    np: Any,
    Image: Any,
    ImageEnhance: Any,
    ImageFilter: Any,
) -> dict[str, Any]:
    height, width = OUT_H, OUT_W
    if rng.random() >= 0.5:
        scale = rng.uniform(0.75, 1.0)
        crop_height = max(1, round(height * scale))
        crop_width = max(1, round(width * scale))
        top = rng.randint(0, height - crop_height)
        left = rng.randint(0, width - crop_width)
        bottom, right = top + crop_height, left + crop_width

        image_crop = sample["image"][top:bottom, left:right].astype(np.uint8)
        image = Image.fromarray(image_crop, mode="RGB").resize(
            (width, height), Image.Resampling.BILINEAR
        )
        sample["image"] = np.asarray(image, dtype=np.float32).copy()
        for key in ("semantic", "semantic_source", "semantic_weight", "hazard", "hazard_valid"):
            values = sample[key][top:bottom, left:right]
            if values.ndim == 2:
                sample[key] = np.asarray(
                    Image.fromarray(values).resize((width, height), Image.Resampling.NEAREST)
                ).copy()
            else:
                resized_channels = []
                for channel in range(values.shape[-1]):
                    plane = Image.fromarray(values[..., channel])
                    resized_channels.append(
                        np.asarray(plane.resize((width, height), Image.Resampling.NEAREST))
                    )
                sample[key] = np.stack(resized_channels, axis=-1)
        valid_image = Image.fromarray(content_valid[top:bottom, left:right].astype(np.uint8) * 255)
        content_valid = np.asarray(
            valid_image.resize((width, height), Image.Resampling.NEAREST)
        ) > 0

    if rng.random() < 0.5:
        for key in _IMAGE_KEYS:
            sample[key] = np.ascontiguousarray(np.flip(sample[key], axis=1))
        content_valid = np.ascontiguousarray(np.fliplr(content_valid))

    image = Image.fromarray(np.clip(sample["image"], 0, 255).astype(np.uint8), mode="RGB")
    image = ImageEnhance.Brightness(image).enhance(rng.uniform(0.85, 1.15))
    image = ImageEnhance.Contrast(image).enhance(rng.uniform(0.85, 1.15))
    if rng.random() < 0.3:
        image = image.filter(ImageFilter.GaussianBlur(radius=0.5))
    sample["image"] = np.asarray(image, dtype=np.float32)
    sample["image"][~content_valid] = 0.0
    sample["semantic"] = sample["semantic"].astype(np.int32, copy=False)
    sample["semantic_source"] = sample["semantic_source"].astype(np.uint8, copy=False)
    sample["semantic_weight"] = sample["semantic_weight"].astype(np.float32, copy=False)
    sample["hazard"] = sample["hazard"].astype(np.float32, copy=False)
    sample["hazard_valid"] = sample["hazard_valid"].astype(np.float32, copy=False)
    sample["semantic_weight"][sample["semantic"] == IGNORE] = 0.0
    return sample


def _stack_batch(samples: list[dict[str, Any]], np: Any) -> dict[str, Any]:
    batch = {
        key: np.stack([sample[key] for sample in samples], axis=0)
        for key in _IMAGE_KEYS
    }
    batch.update({key: [sample[key] for sample in samples] for key in _METADATA_KEYS})
    return batch
