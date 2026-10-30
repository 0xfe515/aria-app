from __future__ import annotations

import re
import subprocess
from unittest.mock import Mock, patch

import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image

from tools.vision.training.data import CLASS_NAMES, HAZARD_NAMES, convert_inventory, inventory_dataset, load_inventory_manifest
import subprocess
from unittest.mock import Mock, patch
import subprocess
from unittest.mock import Mock, patch
from tools.vision.training.train_data import PortraitDataset, PortraitDatasetError, _resolve_original_image_path, _wsl_drive_root, _resolve_original_image_path, _wsl_drive_root, _resolve_original_image_path, _wsl_drive_root


HEIGHT = 512
WIDTH = 288
CONTENT_TOP = 112
CONTENT_BOTTOM = 400


def _record(
    root: Path,
    record_id: str,
    source: str = "surface",
    split: str = "train",
    group_id: str | None = None,
    image_hash: str | None = None,
) -> dict[str, object]:
    original = root / "original" / f"{record_id}.jpg"
    original.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (4, 4), (0, 0, 0)).save(original)

    converted = root / "converted"
    converted.mkdir(parents=True, exist_ok=True)
    image_path = converted / f"{record_id}.png"
    image = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    image[CONTENT_TOP:CONTENT_BOTTOM, :, 0] = 100
    Image.fromarray(image, mode="RGB").save(image_path)

    labels = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
    labels[CONTENT_TOP:CONTENT_BOTTOM, :] = 2
    labels[CONTENT_TOP + 8:CONTENT_TOP + 16, 8:16] = 255
    semantic_path = converted / f"{record_id}.semantic.png"
    Image.fromarray(labels, mode="L").save(semantic_path)

    semantic_source = (labels != 255).astype(np.uint8)
    semantic_weight = semantic_source.astype(np.float32)
    semantic_source_path = converted / f"{record_id}.provenance.npz"
    np.savez_compressed(
        semantic_source_path,
        semantic_source=semantic_source,
        semantic_weight=semantic_weight,
    )
    hazard = np.zeros((HEIGHT, WIDTH, len(HAZARD_NAMES)), dtype=np.uint8)
    hazard_valid = np.zeros_like(hazard)
    hazard_path = converted / f"{record_id}.hazards.npz"
    np.savez_compressed(hazard_path, hazard=hazard, hazard_valid=hazard_valid)

    return {
        "id": record_id,
        "source": source,
        "group_id": group_id or f"group-{record_id}",
        "split": split,
        "source_split": "surface" if source == "surface" else "city-train",
        "image_path": str(original.resolve()),
        "image_sha256": image_hash or f"{len(record_id):064x}",
        "image_tensor_path": image_path.relative_to(root).as_posix(),
        "semantic_path": semantic_path.relative_to(root).as_posix(),
        "hazard_path": hazard_path.relative_to(root).as_posix(),
        "hazard_valid_path": hazard_path.relative_to(root).as_posix(),
        "semantic_source_path": semantic_source_path.relative_to(root).as_posix(),
        "semantic_weight_path": semantic_source_path.relative_to(root).as_posix(),
    }


def _manifest(root: Path, records: list[dict[str, object]]) -> Path:
    path = root / "manifest.json"
    path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "seed": 42,
                "class_names": list(CLASS_NAMES),
                "hazard_names": list(HAZARD_NAMES),
                "records": records,
            }
        ),
        encoding="utf-8",
    )
    return path


def _set_original_ignore(
    root: Path,
    row: dict[str, object],
    top: int,
    bottom: int,
    left: int,
    right: int,
) -> None:
    semantic_path = root / str(row["semantic_path"])
    with Image.open(semantic_path) as source:
        labels = np.asarray(source, dtype=np.uint8).copy()
    labels[top:bottom, left:right] = 255
    Image.fromarray(labels, mode="L").save(semantic_path)
    provenance_path = root / str(row["semantic_source_path"])
    source_array = (labels != 255).astype(np.uint8)
    np.savez_compressed(
        provenance_path,
        semantic_source=source_array,
        semantic_weight=source_array.astype(np.float32),
    )


def _dataset(root: Path, records: list[dict[str, object]], **kwargs: object) -> PortraitDataset:
    return PortraitDataset(_manifest(root, records), split="train", **kwargs)


class PortraitDatasetTests(unittest.TestCase):
    def setUp(self) -> None:
        _wsl_drive_root.cache_clear()

    def tearDown(self) -> None:
        _wsl_drive_root.cache_clear()

    @patch("tools.vision.training.train_data.subprocess.run")
    def test_wsl_path_missing_tool_raises_actionable_error(self, mock_run: Mock) -> None:
        mock_run.side_effect = FileNotFoundError(2, "No such file or directory", "wslpath")
        with self.assertRaisesRegex(
            PortraitDatasetError,
            "Cannot resolve a Windows image_path on POSIX: install or enable WSL's 'wslpath' utility",
        ):
            _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-missing-tool", is_windows=False)

    @patch("tools.vision.training.train_data.subprocess.run")
    def test_wsl_path_called_process_error_reports_stderr(self, mock_run: Mock) -> None:
        mock_run.side_effect = subprocess.CalledProcessError(
            1, ["wslpath", "-u", "C:\\"], stderr="wslpath: C:\\: No such file"
        )
        with self.assertRaisesRegex(
            PortraitDatasetError,
            "wslpath could not resolve Windows drive",
        ):
            _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-error", is_windows=False)

    @patch("tools.vision.training.train_data.subprocess.run")
    def test_wsl_path_timeout_reports_timeout(self, mock_run: Mock) -> None:
        mock_run.side_effect = subprocess.TimeoutExpired(["wslpath", "-u", "C:\\"], 10)
        with self.assertRaisesRegex(
            PortraitDatasetError,
            "wslpath timed out while resolving Windows drive",
        ):
            _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-timeout", is_windows=False)

    @patch("tools.vision.training.train_data.subprocess.run")
    def test_wsl_path_empty_output_raises_error(self, mock_run: Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout="   \n")
        with self.assertRaisesRegex(
            PortraitDatasetError,
            "wslpath returned an empty path for Windows drive",
        ):
            _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-empty", is_windows=False)

    @patch("tools.vision.training.train_data.subprocess.run")
    def test_wsl_path_malformed_relative_output_raises_error(self, mock_run: Mock) -> None:
        mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout="mnt/c\n")
        with self.assertRaisesRegex(
            PortraitDatasetError,
            "wslpath returned a malformed non-absolute path for Windows drive",
        ):
            _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-rel", is_windows=False)

    def test_wsl_path_unavailable_mount_raises_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            nonexistent = Path(temporary) / "missing_mnt"
            with patch("tools.vision.training.train_data.subprocess.run") as mock_run:
                mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout=str(nonexistent))
                with self.assertRaisesRegex(
                    PortraitDatasetError,
                    "to unavailable mount",
                ):
                    _resolve_original_image_path(r"C:\Users\felice\image.jpg", "rec-unavail", is_windows=False)

    def test_wsl_path_caching_and_shell_false_argv(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            mock_mount = Path(temporary) / "mnt_c"
            mock_mount.mkdir()
            with patch("tools.vision.training.train_data.subprocess.run") as mock_run:
                mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout=str(mock_mount))
                root1 = _wsl_drive_root("C:\\")
                root2 = _wsl_drive_root("C:\\")
                self.assertEqual(root1, mock_mount.resolve())
                self.assertEqual(root2, mock_mount.resolve())
                self.assertEqual(mock_run.call_count, 1)
                mock_run.assert_called_once_with(
                    ["wslpath", "-u", "C:\\"],
                    capture_output=True,
                    check=True,
                    text=True,
                    timeout=10,
                    shell=False,
                )

    def test_wsl_path_normal_and_space_containing_windows_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            mock_mount = Path(temporary) / "mnt_c"
            img_dir = mock_mount / "Users" / "felice" / "My Photos" / "sub dir"
            img_dir.mkdir(parents=True)
            img_file = img_dir / "image 1.jpg"
            img_file.write_bytes(b"dummy")

            with patch("tools.vision.training.train_data.subprocess.run") as mock_run:
                mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout=str(mock_mount))
                resolved = _resolve_original_image_path(
                    r"C:\Users\felice\My Photos\sub dir\image 1.jpg",
                    "rec-space",
                    is_windows=False,
                )
                self.assertEqual(resolved, img_file.resolve())
                self.assertTrue(resolved.is_file())

    def test_drive_relative_and_unc_paths_rejected(self) -> None:
        with self.assertRaisesRegex(PortraitDatasetError, "drive-relative, not absolute"):
            _resolve_original_image_path("C:relative.jpg", "rec-rel")
        with self.assertRaisesRegex(PortraitDatasetError, "drive-relative, not absolute"):
            _resolve_original_image_path(r"c:dir\file.jpg", "rec-rel2")
        with self.assertRaisesRegex(PortraitDatasetError, "drive-relative, not absolute"):
            _resolve_original_image_path("C:", "rec-rel3")
        with self.assertRaisesRegex(PortraitDatasetError, "unsupported UNC image_path"):
            _resolve_original_image_path(r"\\server\share\file.jpg", "rec-unc1")
        with self.assertRaisesRegex(PortraitDatasetError, "unsupported UNC image_path"):
            _resolve_original_image_path("//server/share/file.jpg", "rec-unc2")
        with self.assertRaisesRegex(PortraitDatasetError, "unsupported UNC image_path"):
            _resolve_original_image_path(r"\\?\C:\file.jpg", "rec-unc3")
        with self.assertRaisesRegex(PortraitDatasetError, "unsupported UNC image_path"):
            _resolve_original_image_path(r"\\", "rec-unc4")
        with self.assertRaisesRegex(PortraitDatasetError, "invalid drive specification"):
            _resolve_original_image_path(r"1:\file.jpg", "rec-bad-drive")

    def test_wsl_path_traversal_outside_drive_root_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            mock_mount = Path(temporary) / "mnt_c"
            mock_mount.mkdir()
            with patch("tools.vision.training.train_data.subprocess.run") as mock_run:
                mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout=str(mock_mount))
                with self.assertRaisesRegex(PortraitDatasetError, "escapes drive root"):
                    _resolve_original_image_path(r"C:\..\..\outside.jpg", "rec-escape1", is_windows=False)
                with self.assertRaisesRegex(PortraitDatasetError, "escapes drive root"):
                    _resolve_original_image_path(r"C:\folder\..\..\..\outside.jpg", "rec-escape2", is_windows=False)

    def test_native_windows_and_posix_path_resolution(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temp_path = Path(temporary) / "img.jpg"
            temp_path.write_bytes(b"dummy")

            resolved_win = _resolve_original_image_path(str(temp_path.resolve()), "rec-win", is_windows=True)
            self.assertEqual(resolved_win, temp_path.resolve())

            resolved_posix = _resolve_original_image_path("/tmp/test_img.jpg", "rec-posix", is_windows=False)
            self.assertEqual(resolved_posix, Path("/tmp/test_img.jpg").resolve())

            with self.assertRaisesRegex(PortraitDatasetError, "must be absolute"):
                _resolve_original_image_path("relative/img.jpg", "rec-bad", is_windows=False)
            with self.assertRaisesRegex(PortraitDatasetError, "must be absolute"):
                _resolve_original_image_path("relative/img.jpg", "rec-bad", is_windows=True)

    def test_full_windows_manifest_consumed_under_mocked_wsl_preserves_geometry_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mock_mount = root / "mnt_c"
            orig_dir = mock_mount / "Users" / "felice" / "Desktop" / "dataset" / "original"
            orig_dir.mkdir(parents=True)
            original_file = orig_dir / "sample_0.jpg"
            Image.new("RGB", (100, 100), (10, 20, 30)).save(original_file)

            conv_dir = root / "converted_data"
            conv_dir.mkdir()
            windows_path = r"C:\Users\felice\Desktop\dataset\original\sample_0.jpg"
            row = _record(conv_dir, "sample_0")
            row["image_path"] = windows_path
            expected_hash = row["image_sha256"]
            manifest_file = _manifest(conv_dir, [row])

            with patch("tools.vision.training.train_data.subprocess.run") as mock_run:
                mock_run.return_value = subprocess.CompletedProcess(["wslpath", "-u", "C:\\"], 0, stdout=str(mock_mount))
                dataset = PortraitDataset(
                    manifest_file,
                    split="train",
                    _is_windows=False,
                )

                record = dataset._records[0]
                self.assertEqual(record.image_sha256, expected_hash)
                self.assertEqual(record.raw["image_path"], windows_path)
                self.assertEqual(record.original_image, original_file.resolve())
                self.assertTrue(record.original_image.is_file())

                sample = next(dataset.iter_samples(epoch=0, augment=False))
                self.assertEqual(sample["id"], "sample_0")
                self.assertEqual(sample["source"], "surface")
                self.assertEqual(sample["split"], "train")
                self.assertEqual(dataset._original_size(record), (100, 100))

                batches = list(dataset.iter_batches(1, epoch=0, augment=False))
                self.assertEqual(len(batches), 1)
                self.assertEqual(batches[0]["id"], ["sample_0"])

    def test_injected_path_resolver_supported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mock_mount = root / "custom_mnt"
            orig_dir = mock_mount / "data"
            orig_dir.mkdir(parents=True)
            img_file = orig_dir / "test.jpg"
            Image.new("RGB", (4, 4), (0, 0, 0)).save(img_file)

            row = _record(root, "rec_custom")
            row["image_path"] = r"D:\data\test.jpg"
            manifest_file = _manifest(root, [row])

            def custom_resolver(drive_root: str) -> Path:
                if drive_root.upper().startswith("D:"):
                    return mock_mount
                raise ValueError(f"Unknown drive {drive_root}")

            dataset = PortraitDataset(
                manifest_file,
                split="train",
                path_resolver=custom_resolver,
                _is_windows=False,
            )
            self.assertEqual(dataset._records[0].original_image, img_file.resolve())

    def test_sample_and_final_partial_batch_have_exact_contract_and_actual_counts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rows = [
                _record(root, f"city-{index}", source="cityscapes")
                for index in range(3)
            ] + [_record(root, "surface-0")]
            dataset = _dataset(root, rows)

            first = dataset.iter_samples(epoch=4, balanced=True)
            first_ids = [sample["id"] for sample in first]
            repeated = dataset.iter_samples(epoch=4, balanced=True)
            self.assertEqual(first_ids, [sample["id"] for sample in repeated])
            self.assertEqual(first.yielded_count, 6)
            self.assertEqual(first.yielded_samples, 6)

            counts = Counter(
                sample["source"]
                for sample in dataset.iter_samples(epoch=4, balanced=True)
            )
            self.assertEqual(counts, {"cityscapes": 3, "surface": 3})
            self.assertEqual(dataset.sample_count(epoch=4, balanced=True), 6)
            self.assertEqual(dataset.batch_count(4, epoch=4, balanced=True), 2)

            batches = dataset.iter_batches(4, epoch=4, balanced=True)
            values = list(batches)
            self.assertEqual([len(batch["id"]) for batch in values], [4, 2])
            self.assertEqual(batches.yielded_count, 2)
            self.assertEqual(batches.yielded_samples, 6)
            sample = values[0]
            self.assertEqual(
                set(sample),
                {"image", "semantic", "semantic_weight", "semantic_source", "hazard",
                 "hazard_valid", "id", "source", "group_id", "split"},
            )
            self.assertEqual(sample["image"].shape, (4, HEIGHT, WIDTH, 3))
            self.assertEqual(sample["semantic"].shape, (4, HEIGHT, WIDTH))
            self.assertEqual(sample["hazard"].shape, (4, HEIGHT, WIDTH, 8))
            self.assertEqual(sample["hazard_valid"].dtype, np.float32)
            self.assertEqual(sample["semantic"].dtype, np.int32)

    def test_pseudo_overlay_uses_published_keys_and_exact_weight_without_overwriting_real(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            row = _record(root, "surface")
            _set_original_ignore(root, row, CONTENT_TOP + 8, CONTENT_TOP + 16, 8, 16)
            pseudo_dir = root / "pseudo"
            (pseudo_dir / "labels").mkdir(parents=True)
            (pseudo_dir / "weights").mkdir()
            labels = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
            labels[CONTENT_TOP + 8:CONTENT_TOP + 16, 8:16] = 4
            labels[CONTENT_TOP + 9, 9] = 5
            label_path = pseudo_dir / "labels" / "surface.png"
            Image.fromarray(labels, mode="L").save(label_path)
            source = (labels != 255).astype(np.uint8) * 2
            weight = np.where(source == 2, np.float32(0.25), np.float32(0.0))
            weights_path = pseudo_dir / "weights" / "surface.npz"
            np.savez_compressed(
                weights_path, semantic_source=source, semantic_weight=weight,
                confidence=np.where(source == 2, 0.95, 0.0).astype(np.float32),
            )
            pseudo_manifest = pseudo_dir / "pseudo_manifest.json"
            pseudo_manifest.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "records": [
                            {
                                "id": row["id"],
                                "source": row["source"],
                                "group_id": row["group_id"],
                                "split": row["split"],
                                "image_sha256": row["image_sha256"],
                                "semantic_path": "labels/surface.png",
                                "semantic_weight_path": "weights/surface.npz",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            dataset = _dataset(root, [row], pseudo_manifest_path=pseudo_manifest)
            sample = next(dataset.iter_samples())

            self.assertEqual(int(sample["semantic"][CONTENT_TOP + 10, 10]), 4)
            self.assertEqual(int(sample["semantic_source"][CONTENT_TOP + 10, 10]), 2)
            self.assertEqual(float(sample["semantic_weight"][CONTENT_TOP + 10, 10]), 0.25)
            self.assertEqual(int(sample["semantic"][CONTENT_TOP + 9, 9]), 5)
            self.assertEqual(int(sample["semantic_source"][CONTENT_TOP + 9, 9]), 2)
            self.assertEqual(int(sample["semantic"][CONTENT_TOP + 20, 20]), 2)
            self.assertEqual(int(sample["semantic_source"][CONTENT_TOP + 20, 20]), 1)

    def test_pseudo_metadata_mismatch_and_val_overlay_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            row = _record(root, "surface")
            pseudo_manifest = root / "pseudo.json"
            pseudo_manifest.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "records": [{
                            "id": row["id"], "source": "surface", "group_id": row["group_id"],
                            "split": "train", "image_sha256": "f" * 64,
                            "semantic_path": "missing.png", "semantic_weight_path": "missing.npz",
                        }],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(PortraitDatasetError, "image hash"):
                _dataset(root, [row], pseudo_manifest_path=pseudo_manifest)

            val_row = _record(root, "val-surface", split="val")
            with self.assertRaisesRegex(PortraitDatasetError, "only for split='train'"):
                PortraitDataset(_manifest(root, [val_row]), split="val",
                                pseudo_manifest_path=pseudo_manifest)

    def test_global_group_and_hash_leakage_are_rejected_before_split_filtering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            train = _record(root, "train", group_id="leaked-group")
            val = _record(root, "val", split="val", group_id="leaked-group")
            with self.assertRaisesRegex(PortraitDatasetError, "crosses splits"):
                _dataset(root, [train, val])

            val["group_id"] = "separate-group"
            val["image_sha256"] = train["image_sha256"]
            with self.assertRaisesRegex(PortraitDatasetError, "Image hash .* crosses splits"):
                _dataset(root, [train, val])

    def test_unknown_semantic_label_is_rejected_not_silently_mapped(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            row = _record(root, "bad-label")
            semantic_path = root / str(row["semantic_path"])
            with Image.open(semantic_path) as source:
                labels = np.asarray(source, dtype=np.uint8).copy()
            labels[CONTENT_TOP + 1, 1] = 7
            Image.fromarray(labels, mode="L").save(semantic_path)
            dataset = _dataset(root, [row])
            with self.assertRaisesRegex(PortraitDatasetError, "unknown semantic labels"):
                next(dataset.iter_samples())

    def test_diagnostic_incomplete_mode_is_explicit_and_never_training_eligible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            complete = _record(root, "complete")
            pending = _record(root, "pending")
            for key in ("image_tensor_path", "semantic_path", "hazard_path",
                        "hazard_valid_path", "semantic_source_path", "semantic_weight_path"):
                pending[key] = None
            dataset = _dataset(root, [complete, pending], require_complete=False)

            self.assertFalse(dataset.training_eligible)
            self.assertEqual(dataset.diagnostics["mode"], "diagnostic-only")
            self.assertEqual(dataset.diagnostics["incomplete_record_ids"], ("pending",))
            iterator = dataset.iter_samples()
            self.assertEqual(len(list(iterator)), 1)
            self.assertEqual(iterator.yielded_count, 1)

    def test_augmentation_keeps_paired_provenance_weights_and_hazard_masks_categorical(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            row = _record(root, "surface")
            _set_original_ignore(root, row, CONTENT_TOP + 32, CONTENT_TOP + 96, 32, 96)
            pseudo_dir = root / "pseudo"
            (pseudo_dir / "labels").mkdir(parents=True)
            (pseudo_dir / "weights").mkdir()
            labels = np.full((HEIGHT, WIDTH), 255, dtype=np.uint8)
            labels[CONTENT_TOP + 32:CONTENT_TOP + 96, 32:96] = 4
            label_path = pseudo_dir / "labels" / "surface.png"
            Image.fromarray(labels, mode="L").save(label_path)
            source = (labels != 255).astype(np.uint8) * 2
            weights = np.where(source == 2, np.float32(0.25), np.float32(0))
            weight_path = pseudo_dir / "weights" / "surface.npz"
            np.savez_compressed(weight_path, semantic_source=source, semantic_weight=weights)
            pseudo_manifest = pseudo_dir / "pseudo_manifest.json"
            pseudo_manifest.write_text(json.dumps({
                "schema_version": 1,
                "records": [{
                    "id": row["id"], "source": row["source"], "group_id": row["group_id"],
                    "split": row["split"], "image_sha256": row["image_sha256"],
                    "semantic_path": "labels/surface.png",
                    "semantic_weight_path": "weights/surface.npz",
                }],
            }), encoding="utf-8")
            dataset = _dataset(root, [row], pseudo_manifest_path=pseudo_manifest)

            augmented = next(dataset.iter_samples(epoch=0, augment=True))
            self.assertEqual(augmented["image"].dtype, np.float32)
            self.assertEqual(augmented["semantic"].dtype, np.int32)
            self.assertTrue(set(np.unique(augmented["semantic_weight"])) <= {0.0, 0.25, 1.0})
            pseudo = augmented["semantic_source"] == 2
            self.assertTrue(np.all(augmented["semantic"][pseudo] == 4))
            self.assertTrue(np.all(augmented["semantic_weight"][pseudo] == 0.25))
            self.assertTrue(set(np.unique(augmented["hazard"])) <= {0.0, 1.0})
            self.assertTrue(set(np.unique(augmented["hazard_valid"])) <= {0.0, 1.0})

    def test_actual_converter_serialization_loads_without_reinterpretation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "dataset"
            surface_dir = root / "Surface_1" / "Surface_001"
            (root / "cityscapes").mkdir(parents=True)
            mask_dir = surface_dir / "MASK"
            mask_dir.mkdir(parents=True)
            Image.new("RGB", (4, 4), (0, 0, 0)).save(surface_dir / "frame.jpg")
            Image.new("RGB", (4, 4), (0, 0, 255)).save(mask_dir / "frame.png")
            surface_dir.joinpath("sequence.xml").write_text(
                """<annotations><meta><task><name>fixture</name></task></meta>
                <image name="frame.jpg" width="4" height="4">
                  <polygon label="bike_lane" z_order="1" points="0,0;3,0;3,3;0,3"/>
                </image></annotations>""",
                encoding="utf-8",
            )
            output = Path(temporary) / "converted-data"
            inventory_dataset(root, output)
            inventory = load_inventory_manifest(output / "manifest.json")
            manifest = convert_inventory(inventory, output, sample_per_source=1)
            record = manifest["records"][0]
            dataset = PortraitDataset(output / "manifest.json", split=record["split"])
            sample = next(dataset.iter_samples())

            self.assertEqual(sample["image"].shape, (HEIGHT, WIDTH, 3))
            self.assertEqual(sample["semantic"].shape, (HEIGHT, WIDTH))
            self.assertEqual(int(sample["semantic"][CONTENT_TOP + 100, 100]), 2)
            self.assertEqual(int(sample["hazard"][CONTENT_TOP + 100, 100, 0]), 1)
            self.assertTrue(np.all(sample["hazard_valid"][CONTENT_TOP + 100, 100] == 1))
            self.assertTrue(np.all(sample["semantic_weight"][~(sample["semantic"] == 255)] == 1.0))


if __name__ == "__main__":
    unittest.main()
