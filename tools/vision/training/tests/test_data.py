from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from tools.vision.training.data import (
    CITYSCAPES_CLASS_MAP,
    CITYSCAPES_OTHER_IDS,
    CITYSCAPES_VOID_IDS,
    DatasetError,
    DatasetRecord,
    SURFACE_COLOR_MAP,
    _cityscapes_mask_to_classes,
    _hazard_arrays,
    _surface_hazard_layer,
    _surface_mask_to_classes,
    assign_group_splits,
    count_png_rgb_colors,
    convert_inventory,
    inventory_dataset,
    load_inventory_manifest,
    letterbox_geometry,
    letterbox_image,
    parse_surface_xml,
    select_conversion_records,
)


HAS_IMAGE_DEPS = importlib.util.find_spec("numpy") is not None and importlib.util.find_spec("PIL") is not None


class GroupSplitTests(unittest.TestCase):
    def test_duplicates_join_recording_groups_and_never_cross_splits(self) -> None:
        records = [
            DatasetRecord(
                record_id=f"surface-{index}",
                source="surface",
                group_seed=f"surface:dir-{index}:sequence-{index}",
                source_split="surface",
                image_path=Path(f"images/{index}.jpg"),
                image_sha256=f"hash-{index}",
                semantic_annotation_path=None,
            )
            for index in range(12)
        ]
        duplicate = DatasetRecord(
            record_id="duplicate-frame",
            source="surface",
            group_seed="surface:other-dir:other-sequence",
            source_split="surface",
            image_path=Path("images/duplicate.jpg"),
            image_sha256="hash-3",
            semantic_annotation_path=None,
        )
        official_val = DatasetRecord(
            record_id="official-val",
            source="cityscapes",
            group_seed="cityscapes:city-official-val:munster",
            source_split="city-official-val",
            image_path=Path("city/val.png"),
            image_sha256="val-hash",
            semantic_annotation_path=None,
            city="munster",
        )
        records.extend((duplicate, official_val))

        audit = assign_group_splits(records)

        self.assertEqual(records[3].group_id, duplicate.group_id)
        self.assertEqual(records[3].split, duplicate.split)
        self.assertEqual(official_val.split, "test")
        self.assertEqual(audit["group_overlap"], {})
        self.assertEqual(audit["duplicate_hash_overlap"], {})
        self.assertEqual(assign_group_splits(records)["record_split_counts"], audit["record_split_counts"])

    def test_bounded_sample_selects_paired_records_from_both_sources(self) -> None:
        surface = DatasetRecord(
            record_id="surface-hazard-frame",
            source="surface",
            group_seed="surface:directory:sequence",
            source_split="surface",
            image_path=Path("surface/frame.jpg"),
            image_sha256="surface-hash",
            semantic_annotation_path=Path("surface/MASK/frame.png"),
            xml_path=Path("surface/sequence.xml"),
            xml_image_name="frame.jpg",
            hazard_instances=Counter({"stairs": 1}),
        )
        city = DatasetRecord(
            record_id="city-train-frame",
            source="cityscapes",
            group_seed="cityscapes:city-train:munster",
            source_split="city-train",
            image_path=Path("city/frame.png"),
            image_sha256="city-hash",
            semantic_annotation_path=Path("city/label.png"),
            city="munster",
        )

        sample = select_conversion_records((surface, city), per_source=1)
        repeat = select_conversion_records((city, surface), per_source=1)

        self.assertEqual({record.source for record in sample}, {"surface", "cityscapes"})
        self.assertEqual({record.record_id for record in sample}, {record.record_id for record in repeat})
        self.assertIn(surface, sample)


class GeometryTests(unittest.TestCase):
    def test_app_letterbox_geometry_matches_wide_and_tall_frames(self) -> None:
        self.assertEqual(letterbox_geometry(1920, 1080), (0, 175, 288, 162))
        self.assertEqual(letterbox_geometry(480, 640), (0, 64, 288, 384))
        self.assertEqual(letterbox_geometry(640, 480), (0, 148, 288, 216))

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_paired_mask_transform_uses_nearest_and_ignore_padding(self) -> None:
        import numpy as np
        from PIL import Image

        source = Image.new("L", (4, 2), color=2)
        transformed, geometry = letterbox_image(
            source, mask=True, pad_value=255, target_width=8, target_height=8
        )
        pixels = np.asarray(transformed)
        self.assertEqual(geometry, (0, 2, 8, 4))
        self.assertTrue(np.all(pixels[:2] == 255))
        self.assertTrue(np.all(pixels[2:6] == 2))
        self.assertTrue(np.all(pixels[6:] == 255))


class AnnotationTests(unittest.TestCase):
    def test_verified_caution_zone_palette_is_known_other_not_unknown(self) -> None:
        for color in (
            (0, 255, 0),
            (255, 0, 0),
            (255, 128, 0),
            (255, 192, 0),
            (105, 105, 255),
        ):
            self.assertEqual(SURFACE_COLOR_MAP[color], 0)

    def test_xml_hazard_frequencies_and_z_order_rasterization(self) -> None:
        if not HAS_IMAGE_DEPS:
            self.skipTest("requires NumPy and Pillow")
        xml = """<?xml version="1.0"?>
        <annotations>
          <meta><task><name>fixture-sequence</name></task></meta>
          <image name="frame.jpg" width="4" height="4">
            <polygon label="bike_lane" z_order="1" points="0,0;3,0;3,3;0,3"/>
            <polygon label="caution_zone" z_order="2" points="1,1;2,1;2,2;1,2">
              <attribute name="attribute">stairs</attribute>
            </polygon>
          </image>
        </annotations>"""
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "sequence.xml"
            path.write_text(xml, encoding="utf-8")
            task, frames, counts = parse_surface_xml(path)
            self.assertEqual(task, "fixture-sequence")
            self.assertEqual(counts, {"bike_lane": 1, "stairs": 1})
            layer, code_map = _surface_hazard_layer(path, "frame.jpg", 4, 4)
            hazard, valid = _hazard_arrays(layer, code_map, letterbox_geometry(4, 4))

        self.assertEqual(hazard[256, 144, 2], 1)
        self.assertEqual(hazard[256, 144, 0], 0)
        self.assertTrue((valid[256, 144] == 1).all())
        self.assertEqual(hazard[130, 20, 0], 1)
        self.assertEqual(hazard[130, 20, 2], 0)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_unknown_surface_colors_are_rejected_not_mapped_to_other(self) -> None:
        import numpy as np
        from PIL import Image

        mask = Image.fromarray(np.asarray([[[1, 2, 3], [0, 0, 0]]], dtype=np.uint8), mode="RGB")
        with self.assertRaisesRegex(DatasetError, "Unknown Surface mask RGB colors"):
            _surface_mask_to_classes(mask)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_surface_palette_lookup_matches_every_verified_color(self) -> None:
        import numpy as np
        from PIL import Image

        colors = list(SURFACE_COLOR_MAP)
        pixels = np.asarray([colors + [(0, 0, 0)]], dtype=np.uint8)
        expected = np.full(pixels.shape[:2], 255, dtype=np.uint8)
        for color, class_id in SURFACE_COLOR_MAP.items():
            expected[np.all(pixels == color, axis=-1)] = class_id

        mapped = _surface_mask_to_classes(Image.fromarray(pixels, mode="RGB"))

        np.testing.assert_array_equal(mapped, expected)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_stdlib_png_color_counter_counts_real_mask_colors(self) -> None:
        import numpy as np
        from PIL import Image

        pixels = np.asarray([[[1, 2, 3], [1, 2, 3]], [[0, 0, 0], [9, 8, 7]]], dtype=np.uint8)
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "mask.png"
            Image.fromarray(pixels, mode="RGB").save(path)
            counts = count_png_rgb_colors(path)
        self.assertEqual(counts[(1, 2, 3)], 2)
        self.assertEqual(counts[(0, 0, 0)], 1)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_cityscapes_void_stays_ignored_and_vehicle_labels_map_to_vehicle(self) -> None:
        import numpy as np
        from PIL import Image

        labels = Image.fromarray(np.asarray([[0, 11, 26, 255]], dtype=np.uint8), mode="L")
        mapped = _cityscapes_mask_to_classes(labels)
        self.assertEqual(mapped.tolist(), [[255, 0, 5, 255]])
        self.assertEqual([CITYSCAPES_CLASS_MAP[label_id] for label_id in range(26, 34)], [5] * 8)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_cityscapes_lookup_matches_every_known_label_id(self) -> None:
        import numpy as np
        from PIL import Image

        known_ids = sorted(CITYSCAPES_VOID_IDS | CITYSCAPES_OTHER_IDS | frozenset(CITYSCAPES_CLASS_MAP))
        labels = np.asarray([known_ids], dtype=np.uint8)
        expected = np.full(labels.shape, 255, dtype=np.uint8)
        for label_id, class_id in CITYSCAPES_CLASS_MAP.items():
            expected[labels == label_id] = class_id
        for label_id in CITYSCAPES_OTHER_IDS:
            expected[labels == label_id] = 0

        mapped = _cityscapes_mask_to_classes(Image.fromarray(labels, mode="L"))

        np.testing.assert_array_equal(mapped, expected)

    @unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
    def test_unknown_cityscapes_labelid_is_rejected(self) -> None:
        import numpy as np
        from PIL import Image

        for label_id in (34, 35):
            with self.subTest(label_id=label_id):
                labels = Image.fromarray(np.asarray([[label_id]], dtype=np.uint8), mode="L")
                with self.assertRaisesRegex(DatasetError, "Unknown Cityscapes labelIds"):
                    _cityscapes_mask_to_classes(labels)


@unittest.skipUnless(HAS_IMAGE_DEPS, "requires NumPy and Pillow")
class ConversionIntegrationTests(unittest.TestCase):
    def test_inventory_and_bounded_conversion_write_paired_portrait_records(self) -> None:
        import numpy as np
        from PIL import Image

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "dataset"
            surface_dir = root / "Surface_1" / "Surface_001"
            mask_dir = surface_dir / "MASK"
            city_image_dir = root / "cityscapes" / "leftImg8bit" / "train" / "munster"
            city_label_dir = root / "cityscapes" / "gtFine" / "train" / "munster"
            mask_dir.mkdir(parents=True)
            city_image_dir.mkdir(parents=True)
            city_label_dir.mkdir(parents=True)
            Image.new("RGB", (4, 4), (30, 60, 90)).save(surface_dir / "frame.jpg")
            Image.new("RGB", (4, 4), (90, 60, 30)).save(surface_dir / "frame2.jpg")
            Image.fromarray(
                np.full((4, 4, 3), (0, 0, 255), dtype=np.uint8), mode="RGB"
            ).save(mask_dir / "frame.png")
            Image.fromarray(
                np.full((4, 4, 3), (0, 0, 255), dtype=np.uint8), mode="RGB"
            ).save(mask_dir / "frame2.png")
            surface_dir.joinpath("recording.xml").write_text(
                """<annotations><meta><task><name>fixture</name></task></meta>
                <image name="frame.jpg" width="4" height="4">
                  <polygon label="bike_lane" z_order="1" points="0,0;3,0;3,3;0,3"/>
                </image>
                <image name="frame2.jpg" width="4" height="4">
                  <polygon label="caution_zone" z_order="1" points="0,0;3,0;3,3;0,3">
                    <attribute name="attribute">stairs</attribute>
                  </polygon>
                </image></annotations>""",
                encoding="utf-8",
            )
            Image.new("RGB", (4, 4), (40, 50, 60)).save(
                city_image_dir / "munster_000000_000000_leftImg8bit.png"
            )
            Image.fromarray(
                np.asarray([[0, 7, 8, 26]] * 4, dtype=np.uint8), mode="L"
            ).save(city_label_dir / "munster_000000_000000_gtFine_labelIds.png")

            output = Path(temp_dir) / "generated"
            inventory_dataset(root, output)
            inventory = load_inventory_manifest(output / "manifest.json")
            convert_inventory(inventory, output, sample_per_source=2)
            reloaded = load_inventory_manifest(output / "manifest.json")
            manifest = convert_inventory(reloaded, output, sample_per_source=1)

            self.assertEqual(len(manifest["records"]), 3)
            converted = [record for record in manifest["records"] if record["image_tensor_path"]]
            self.assertEqual(len(converted), 2)
            self.assertFalse(any(
                record["annotation_paths"]["xml_image_name"] == "frame2.jpg"
                for record in converted
            ))
            surface_record = next(record for record in converted if record["source"] == "surface")
            with Image.open(output / surface_record["semantic_path"]) as semantic_image:
                semantic = np.asarray(semantic_image)
            self.assertEqual(semantic.shape, (512, 288))
            self.assertEqual(int(semantic[0, 0]), 255)
            self.assertEqual(int(semantic[256, 144]), 2)
            with np.load(output / surface_record["hazard_path"]) as hazards:
                self.assertEqual(hazards["hazard"].shape, (512, 288, 8))
                self.assertEqual(int(hazards["hazard"][256, 144, 0]), 1)
                self.assertEqual(int(hazards["hazard_valid"][256, 144].sum()), 8)
            self.assertTrue((output / surface_record["semantic_source_path"]).is_file())
            report = json.loads((output / "inventory_report.json").read_text(encoding="utf-8"))
            self.assertEqual(report["conversion"]["converted_records"], 2)


if __name__ == "__main__":
    unittest.main()
