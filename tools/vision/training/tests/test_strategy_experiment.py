"""Unit tests for the controlled two-arm Model A strategy experiment runner.

Task ID: ARIA-STRATEGY-AB-PILOT-20261031
Covers:
- Budget and gradient accumulation exactness across Phase 1 and Phase 2, including stream exhaustion reshuffling.
- Identical Phase 2 sample ordering and augmentation RNG across Arm A and Arm B (verified via image/semantic/weight/ID digests).
- Deterministic 70% Surface : 30% Cityscapes microbatch schedule and actual sample count reporting.
- Shared initial weights tensor hash, frozen encoder/BN invariance, shared real-train class weights, and output directory protection.
- Strict exclusion of test split and validation split from class-weight statistics and training streams.
- Single-forward validation evaluator equivalence with StreamingConfusionMatrix.update and None (null) metric handling.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional

os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

_REPO_ROOT = Path(__file__).resolve().parents[4]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

import numpy as np
from PIL import Image
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers

from tools.vision.training.data import (
    CLASS_NAMES,
    HAZARD_NAMES,
    IGNORE,
    OUT_H,
    OUT_W,
    sha256_file,
)
from tools.vision.training.metrics import StreamingConfusionMatrix
from tools.vision.training.model import (
    NUM_SEMANTIC_CLASSES,
    freeze_batch_normalization,
    set_encoder_trainability,
)
from tools.vision.training.strategy_experiment import (
    DEFAULT_ACCUMULATE_STEPS,
    DEFAULT_BATCH_SIZE,
    DEFAULT_PHASE1_UPDATES,
    DEFAULT_PHASE2_UPDATES,
    EXPECTED_MANIFEST_SHA256,
    EXPECTED_SPLIT_COUNTS,
    MIXED_SCHEDULE_10,
    audit_manifest_and_compute_shared_weights,
    build_experiment_datasets,
    compare_arms,
    compute_subset_macro_iou,
    evaluate_model_a_validation,
    planned_schedule_sources,
    run_strategy_experiment,
    summarize_domain_confusion,
    update_confusion_matrix_fast,
)


def _build_stub_model_a(_weights: Optional[str] = None) -> keras.Model:
    """Builds a fast unit-test stub with an 'encoder' submodel, BN layer, and decoder head."""
    inputs = layers.Input(shape=(OUT_H, OUT_W, 3), name="image_input")
    norm = layers.Rescaling(1.0 / 127.5, offset=-1.0, name="input_normalization")(inputs)

    enc_in = layers.Input(shape=(OUT_H, OUT_W, 3), name="enc_in")
    enc_conv = layers.Conv2D(4, 3, padding="same", use_bias=False, name="block_1_conv")(enc_in)
    enc_bn = layers.BatchNormalization(name="block_1_bn")(enc_conv)
    encoder = keras.Model(inputs=enc_in, outputs=enc_bn, name="mobilenetv2_encoder")

    feat = encoder(norm)
    dec = layers.Conv2D(4, 3, padding="same", activation="relu", name="decoder_final_features")(feat)
    out = layers.Conv2D(NUM_SEMANTIC_CLASSES, 1, activation="softmax", name="semantic_output")(dec)
    model = keras.Model(inputs=inputs, outputs=out, name="stub_portrait_model_a")

    set_encoder_trainability(model, policy="freeze_all")
    freeze_batch_normalization(model)
    return model


def _write_fixture_dataset(root: Path, *, city_train_count: int = 3, surface_train_count: int = 3) -> Path:
    """Writes a complete, converter-shaped mini dataset with train, val, and test records."""
    raw_dir = root / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    records: List[Dict[str, Any]] = []

    specs = [
        ("cityscapes", "city-train", "train", city_train_count),
        ("surface", "surface", "train", surface_train_count),
        ("cityscapes", "city-train", "val", 2),
        ("surface", "surface", "val", 2),
        ("cityscapes", "city-official-val", "test", 1),
        ("surface", "surface", "test", 1),
    ]

    rec_idx = 0
    for source, source_split, split, count in specs:
        for i in range(count):
            rec_idx += 1
            rec_id = f"{source}-{split}-{i:03d}"
            group_id = f"group-{source}-{split}-{i:03d}"

            # Full-frame portrait raw image (288x512) -> letterbox geometry has 0 padding
            orig_path = raw_dir / f"{rec_id}_orig.png"
            orig_arr = np.full((OUT_H, OUT_W, 3), (rec_idx * 17) % 251, dtype=np.uint8)
            Image.fromarray(orig_arr, mode="RGB").save(orig_path)
            img_sha = sha256_file(orig_path)

            rel_dir = Path("converted") / split / source
            out_dir = root / rel_dir
            out_dir.mkdir(parents=True, exist_ok=True)

            conv_img_rel = (rel_dir / f"{rec_id}.png").as_posix()
            Image.fromarray(orig_arr, mode="RGB").save(root / conv_img_rel)

            sem = np.full((OUT_H, OUT_W), IGNORE, dtype=np.uint8)
            if split == "test":
                # Distinct test pixel distribution to verify test split is excluded from class weights
                sem[:, :] = 6
            elif source == "cityscapes":
                sem[0:100, :] = 0  # other
                sem[100:220, :] = 1  # road
                sem[220:300, :] = 2  # sidewalk
                sem[300:360, :] = 4  # person
                sem[360:430, :] = 5  # vehicle
                sem[430:480, :] = 6  # pole_sign
            else:
                sem[0:80, :] = 0  # other
                sem[80:220, :] = 1  # road
                sem[220:400, :] = 2  # sidewalk
                sem[400:480, :] = 3  # crosswalk

            sem_rel = (rel_dir / f"{rec_id}.semantic.png").as_posix()
            Image.fromarray(sem, mode="L").save(root / sem_rel)

            sem_source = (sem != IGNORE).astype(np.uint8)
            sem_weight = sem_source.astype(np.float32)
            prov_rel = (rel_dir / f"{rec_id}.provenance.npz").as_posix()
            np.savez_compressed(
                root / prov_rel,
                semantic_source=sem_source,
                semantic_weight=sem_weight,
            )

            hazard = np.zeros((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.uint8)
            hazard_valid = np.zeros((OUT_H, OUT_W, len(HAZARD_NAMES)), dtype=np.uint8)
            haz_rel = (rel_dir / f"{rec_id}.hazards.npz").as_posix()
            np.savez_compressed(
                root / haz_rel,
                hazard=hazard,
                hazard_valid=hazard_valid,
            )

            px_counts = np.bincount(sem[sem != IGNORE], minlength=len(CLASS_NAMES))
            records.append(
                {
                    "id": rec_id,
                    "source": source,
                    "source_split": source_split,
                    "split": split,
                    "group_id": group_id,
                    "city": "aachen" if source == "cityscapes" else None,
                    "image_path": str(orig_path.resolve()),
                    "image_sha256": img_sha,
                    "annotation_paths": {"semantic": str(orig_path.resolve()), "xml": None, "xml_image_name": None},
                    "annotation_sha256": {"semantic": img_sha, "xml": None},
                    "image_tensor_path": conv_img_rel,
                    "semantic_path": sem_rel,
                    "semantic_source_path": prov_rel,
                    "semantic_weight_path": prov_rel,
                    "hazard_path": haz_rel,
                    "hazard_valid_path": haz_rel,
                    "xml_hazard_polygon_counts": {},
                    "class_pixel_counts": {
                        c_name: int(px_counts[idx]) for idx, c_name in enumerate(CLASS_NAMES)
                    },
                    "hazard_pixel_counts": {h: 0 for h in HAZARD_NAMES},
                    "hazard_valid_pixel_counts": {h: 0 for h in HAZARD_NAMES},
                }
            )

    manifest = {
        "schema_version": 1,
        "seed": 42,
        "class_names": list(CLASS_NAMES),
        "hazard_names": list(HAZARD_NAMES),
        "records": records,
    }
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest_path


class TestStrategyExperimentScheduleAndCounts(unittest.TestCase):
    """Verifies the deterministic 70:30 schedule and actual sample count reporting."""

    def test_mixed_schedule_ratio_and_actual_counts(self):
        self.assertEqual(len(MIXED_SCHEDULE_10), 10)
        self.assertEqual(MIXED_SCHEDULE_10.count("surface"), 7)
        self.assertEqual(MIXED_SCHEDULE_10.count("cityscapes"), 3)

        phase1_microbatches = DEFAULT_PHASE1_UPDATES * DEFAULT_ACCUMULATE_STEPS  # 256
        phase2_microbatches = DEFAULT_PHASE2_UPDATES * DEFAULT_ACCUMULATE_STEPS  # 768

        # Every complete 10-microbatch block has 7 Surface and 3 Cityscapes
        sched_p1 = planned_schedule_sources("mixed_70_30", phase1_microbatches)
        sched_p2 = planned_schedule_sources("mixed_70_30", phase2_microbatches)
        for window_start in range(0, 250, 10):
            window = sched_p1[window_start : window_start + 10]
            self.assertEqual(window.count("surface"), 7)
            self.assertEqual(window.count("cityscapes"), 3)

        # Phase 1 (256 microbatches = 25*10 + 6): 179 surface, 77 cityscapes
        self.assertEqual(sched_p1.count("surface"), 179)
        self.assertEqual(sched_p1.count("cityscapes"), 77)
        self.assertEqual(len(sched_p1) * DEFAULT_BATCH_SIZE, 512)

        # Phase 2 (768 microbatches = 76*10 + 8): 538 surface, 230 cityscapes
        self.assertEqual(sched_p2.count("surface"), 538)
        self.assertEqual(sched_p2.count("cityscapes"), 230)
        self.assertEqual(len(sched_p2) * DEFAULT_BATCH_SIZE, 1536)

        # City-only Phase 1 (Arm A) has 256 cityscapes, 0 surface
        sched_city = planned_schedule_sources("city_only", phase1_microbatches)
        self.assertEqual(sched_city.count("cityscapes"), 256)
        self.assertEqual(sched_city.count("surface"), 0)


class TestManifestAuditAndExclusion(unittest.TestCase):
    """Verifies manifest hash/count checks, real-train class weights, and val/test exclusion."""

    def test_manifest_audit_and_test_split_exclusion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest_path = _write_fixture_dataset(root, city_train_count=2, surface_train_count=2)
            datasets = build_experiment_datasets(manifest_path, seed=42)

            audit = audit_manifest_and_compute_shared_weights(
                manifest_path,
                require_expected_manifest_hash=False,
                sample_provenance_datasets={
                    "cityscapes": datasets["city_train"],
                    "surface": datasets["surface_train"],
                },
                sample_provenance_records_per_source=2,
            )
            baseline_counts = list(audit["real_training_class_pixel_counts_vector"])
            baseline_weights = list(audit["shared_class_weights"])

            # Mutate val and test class_pixel_counts massively; train counts & weights must be unchanged
            raw = json.loads(manifest_path.read_text(encoding="utf-8"))
            for rec in raw["records"]:
                if rec["split"] in ("val", "test"):
                    rec["class_pixel_counts"] = {c: 99999999 for c in CLASS_NAMES}
            manifest_path.write_text(json.dumps(raw, indent=2), encoding="utf-8")

            audit_after = audit_manifest_and_compute_shared_weights(
                manifest_path,
                require_expected_manifest_hash=False,
            )
            self.assertEqual(audit_after["real_training_class_pixel_counts_vector"], baseline_counts)
            self.assertEqual(audit_after["shared_class_weights"], baseline_weights)

            # Production hash check must reject fixture manifest
            with self.assertRaises(ValueError):
                audit_manifest_and_compute_shared_weights(
                    manifest_path,
                    require_expected_manifest_hash=True,
                )

            # Corrupt a train record's class_pixel_counts with boolean -> must fail
            for rec in raw["records"]:
                if rec["split"] == "train":
                    rec["class_pixel_counts"]["road"] = True
                    break
            manifest_path.write_text(json.dumps(raw, indent=2), encoding="utf-8")
            with self.assertRaises(ValueError):
                audit_manifest_and_compute_shared_weights(
                    manifest_path,
                    require_expected_manifest_hash=False,
                )

    def test_production_manifest_audit_if_present(self):
        prod_manifest = _REPO_ROOT / "build" / "vision-training" / "data" / "manifest.json"
        if not prod_manifest.is_file():
            self.skipTest("Production manifest not present in this checkout.")
        audit = audit_manifest_and_compute_shared_weights(
            prod_manifest,
            require_expected_manifest_hash=True,
        )
        self.assertEqual(audit["manifest_sha256"], EXPECTED_MANIFEST_SHA256)
        self.assertEqual(audit["split_record_counts"], EXPECTED_SPLIT_COUNTS)
        self.assertEqual(len(audit["shared_class_weights"]), NUM_SEMANTIC_CLASSES)
        # Surface train has 0 object pixels; Cityscapes train has 0 crosswalk pixels
        surf_counts = audit["real_training_class_pixel_counts_by_source"]["surface"]
        city_counts = audit["real_training_class_pixel_counts_by_source"]["cityscapes"]
        self.assertEqual(surf_counts["person"], 0)
        self.assertEqual(surf_counts["vehicle"], 0)
        self.assertEqual(surf_counts["pole_sign"], 0)
        self.assertEqual(city_counts["crosswalk"], 0)


class TestMetricsNoneHandlingAndEvaluator(unittest.TestCase):
    """Verifies None (null) handling, explicit denominators, and single-forward equivalence."""

    def test_metrics_none_handling_and_denominators(self):
        # All target classes None -> macro_iou is None, denominator is 0
        all_none = compute_subset_macro_iou(
            {"sidewalk": None, "crosswalk": None},
            ("sidewalk", "crosswalk"),
        )
        self.assertIsNone(all_none["macro_iou"])
        self.assertIsNone(all_none["strict_macro_iou"])
        self.assertEqual(all_none["denominator"], 0)
        self.assertEqual(all_none["undefined_classes"], ["sidewalk", "crosswalk"])

        # Partial None (e.g., Cityscapes has sidewalk defined, crosswalk None)
        partial = compute_subset_macro_iou(
            {"sidewalk": 0.64, "crosswalk": None},
            ("sidewalk", "crosswalk"),
        )
        self.assertAlmostEqual(partial["macro_iou"], 0.64)
        self.assertIsNone(partial["strict_macro_iou"])
        self.assertEqual(partial["denominator"], 1)
        self.assertEqual(partial["defined_classes"], ["sidewalk"])
        self.assertEqual(partial["undefined_classes"], ["crosswalk"])

        # Surface confusion matrix with only classes 0..3 present in truth and predictions
        cm = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
        truth = np.array([0, 1, 2, 3, 255], dtype=np.int32)
        pred = np.array([0, 1, 2, 2, 6], dtype=np.int32)  # pixel 4 is ignored even though pred=6
        prov = np.array([1, 1, 1, 1, 0], dtype=np.uint8)
        update_confusion_matrix_fast(cm, truth, pred, prov)

        summary = summarize_domain_confusion(
            cm,
            domain="surface",
            images_evaluated=2,
            mean_semantic_loss=0.5,
        )
        self.assertIsNone(summary["class_iou"]["person"])
        self.assertIsNone(summary["class_iou"]["vehicle"])
        self.assertIsNone(summary["class_iou"]["pole_sign"])
        self.assertEqual(summary["defined_class_count"], 4)
        self.assertEqual(summary["gt_present_class_count"], 4)
        self.assertEqual(
            summary["undefined_iou_classes"],
            ["person", "vehicle", "pole_sign"],
        )

    def test_fast_confusion_matches_reference_and_single_forward(self):
        rng = np.random.RandomState(42)
        truth = rng.choice([0, 1, 2, 3, 4, 5, 6, 255], size=(3, 32, 24)).astype(np.int32)
        pred = rng.randint(0, 7, size=(3, 32, 24)).astype(np.int32)
        # Include 0=ignore, 1=real, and 2=pseudo (plus float 0.25) to prove non-real pixels are skipped
        prov = np.where(truth == 255, 0.0, rng.choice([1.0, 2.0, 0.25], size=(3, 32, 24))).astype(
            np.float32
        )

        ref_cm = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)
        fast_cm = StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES)

        ref_cm.update(truth.reshape(-1), pred.reshape(-1), provenance=prov.reshape(-1))
        update_confusion_matrix_fast(fast_cm, truth, pred, provenance=prov)

        self.assertEqual(fast_cm.valid_pixels, ref_cm.valid_pixels)
        self.assertEqual(fast_cm.matrix, ref_cm.matrix)
        self.assertEqual(
            fast_cm.summary(class_names=CLASS_NAMES),
            ref_cm.summary(class_names=CLASS_NAMES),
        )

        # Out-of-range class IDs or mismatched lengths must fail just like StreamingConfusionMatrix
        with self.assertRaises(ValueError):
            update_confusion_matrix_fast(
                StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES),
                np.array([0, 7], dtype=np.int32),
                np.array([0, 1], dtype=np.int32),
                np.array([1.0, 1.0], dtype=np.float32),
            )
        with self.assertRaises(ValueError):
            update_confusion_matrix_fast(
                StreamingConfusionMatrix(class_count=NUM_SEMANTIC_CLASSES),
                np.array([0, 1], dtype=np.int32),
                np.array([0], dtype=np.int32),
            )


class TestTwoArmExperimentExecution(unittest.TestCase):
    """End-to-end bounded execution test verifying budget, Phase 2 digest parity, and invariants."""

    def test_two_arm_budget_phase2_digest_and_shared_init_invariants(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Use odd record count (3) with batch_size=2 to exercise epoch exhaustion + carry reshuffling
            manifest_path = _write_fixture_dataset(
                root / "data",
                city_train_count=3,
                surface_train_count=3,
            )
            out_dir = root / "experiment_out"

            results = run_strategy_experiment(
                manifest_path=manifest_path,
                output_dir=out_dir,
                device="cpu",
                weights=None,
                seed=42,
                batch_size=2,
                accumulate_steps=2,
                phase1_updates=2,
                phase2_updates=3,
                phase1_lr=1e-3,
                phase2_lr=3e-4,
                require_expected_manifest_hash=False,
                model_factory=_build_stub_model_a,
            )

            self.assertEqual(results["status"], "COMPLETED")
            self.assertEqual(results["exit_code"], 0)
            self.assertTrue((out_dir / "experiment_results.json").is_file())
            self.assertEqual((out_dir / "exit_code.txt").read_text(encoding="utf-8").strip(), "0")
            self.assertTrue((out_dir / "arm_a" / "arm_summary.json").is_file())
            self.assertTrue((out_dir / "arm_b" / "arm_summary.json").is_file())
            invariants = results["invariants_verified"]
            for key, passed in invariants.items():
                self.assertTrue(passed, f"Invariant {key} failed: {invariants}")

            arm_a = results["arms"]["arm_a"]
            arm_b = results["arms"]["arm_b"]

            # 1. Exact update, optimizer.iterations, and sample counts
            self.assertEqual(arm_a["phase1"]["optimizer_iterations_initial"], 0)
            self.assertEqual(arm_a["phase1"]["optimizer_iterations_final"], 2)
            self.assertEqual(arm_a["phase1"]["optimizer_updates_completed"], 2)
            self.assertEqual(arm_a["phase1"]["microbatches_completed"], 4)
            self.assertEqual(arm_a["phase1"]["sample_presentations_completed"], 8)
            self.assertEqual(
                arm_a["phase1"]["sample_presentations_by_source"],
                {"surface": 0, "cityscapes": 8},
            )
            # Because city_train_count=3 and 8 samples were consumed, exhaustion reshuffle occurred
            self.assertGreaterEqual(
                arm_a["phase1"]["stream_exhaustion_stats"]["cityscapes"]["reshuffles_on_exhaustion"],
                2,
            )

            self.assertEqual(arm_b["phase1"]["optimizer_iterations_initial"], 0)
            self.assertEqual(arm_b["phase1"]["optimizer_iterations_final"], 2)
            self.assertEqual(arm_b["phase1"]["optimizer_updates_completed"], 2)
            self.assertEqual(arm_b["phase1"]["microbatches_completed"], 4)
            self.assertEqual(arm_b["phase1"]["sample_presentations_completed"], 8)
            # First 4 slots of MIXED_SCHEDULE_10 are ("surface", "surface", "cityscapes", "surface")
            self.assertEqual(
                arm_b["phase1"]["sample_presentations_by_source"],
                {"surface": 6, "cityscapes": 2},
            )

            self.assertEqual(arm_a["phase2"]["optimizer_iterations_initial"], 0)
            self.assertEqual(arm_a["phase2"]["optimizer_iterations_final"], 3)
            self.assertEqual(arm_a["phase2"]["optimizer_updates_completed"], 3)
            self.assertEqual(arm_a["phase2"]["microbatches_completed"], 6)
            self.assertEqual(arm_a["phase2"]["sample_presentations_completed"], 12)
            self.assertEqual(arm_b["phase2"]["optimizer_iterations_initial"], 0)
            self.assertEqual(arm_b["phase2"]["optimizer_iterations_final"], 3)
            self.assertEqual(arm_b["phase2"]["optimizer_updates_completed"], 3)
            self.assertEqual(arm_b["phase2"]["microbatches_completed"], 6)
            self.assertEqual(arm_b["phase2"]["sample_presentations_completed"], 12)

            # 2. Phase 2 input digest must match across IDs, image, semantic, and weight
            self.assertEqual(arm_a["phase2"]["input_digest"], arm_b["phase2"]["input_digest"])
            self.assertNotEqual(
                arm_a["phase1"]["input_digest"]["combined_sha256"],
                arm_b["phase1"]["input_digest"]["combined_sha256"],
            )

            # 3. Shared initial weights match; trainable weights updated; non-trainable frozen
            init_sha = results["shared_initialization"]["shared_initial_tensor_hashes"][
                "all_weights_tensor_sha256"
            ]
            self.assertEqual(
                arm_a["initial_weights_tensor_hashes"]["all_weights_tensor_sha256"],
                init_sha,
            )
            self.assertEqual(
                arm_b["initial_weights_tensor_hashes"]["all_weights_tensor_sha256"],
                init_sha,
            )
            self.assertNotEqual(
                arm_a["final_weights_tensor_hashes"]["trainable_weights_tensor_sha256"],
                arm_a["initial_weights_tensor_hashes"]["trainable_weights_tensor_sha256"],
            )
            self.assertNotEqual(
                arm_b["final_weights_tensor_hashes"]["trainable_weights_tensor_sha256"],
                arm_b["initial_weights_tensor_hashes"]["trainable_weights_tensor_sha256"],
            )

            # 4. Single-forward pass count in validation: 2 surface val (1 batch) + 2 city val (1 batch) = 2
            self.assertEqual(arm_a["validation"]["forward_passes_executed"], 2)
            self.assertEqual(arm_b["validation"]["forward_passes_executed"], 2)
            self.assertEqual(arm_a["validation"]["total_validation_images"], 4)

            # 5. Non-empty output directory is rejected
            with self.assertRaises(FileExistsError):
                run_strategy_experiment(
                    manifest_path=manifest_path,
                    output_dir=out_dir,
                    device="cpu",
                    weights=None,
                    require_expected_manifest_hash=False,
                    model_factory=_build_stub_model_a,
                )

    def test_real_model_a_clear_session_initial_tensor_hash_parity(self):
        import gc
        from tools.vision.training.strategy_experiment import (
            _default_model_factory,
            compute_model_weights_tensor_sha256,
            set_deterministic_seeds,
        )

        with tempfile.TemporaryDirectory() as tmp:
            weights_path = Path(tmp) / "shared_init.weights.h5"

            keras.backend.clear_session()
            gc.collect()
            set_deterministic_seeds(42)
            m0 = _default_model_factory(None)
            m0.save_weights(str(weights_path))
            h0 = compute_model_weights_tensor_sha256(m0)
            del m0
            keras.backend.clear_session()
            gc.collect()

            set_deterministic_seeds(42)
            m1 = _default_model_factory(None)
            m1.load_weights(str(weights_path))
            h1 = compute_model_weights_tensor_sha256(m1)
            del m1
            keras.backend.clear_session()
            gc.collect()

            self.assertEqual(h0["all_weights_tensor_sha256"], h1["all_weights_tensor_sha256"])
            self.assertEqual(
                h0["trainable_weights_tensor_sha256"],
                h1["trainable_weights_tensor_sha256"],
            )
            self.assertEqual(
                h0["non_trainable_weights_tensor_sha256"],
                h1["non_trainable_weights_tensor_sha256"],
            )
            self.assertEqual(h0["trainable_variable_paths"], h1["trainable_variable_paths"])
            self.assertGreater(h0["trainable_variable_count"], 0)
            self.assertGreater(h0["non_trainable_variable_count"], 0)


if __name__ == "__main__":
    unittest.main()
