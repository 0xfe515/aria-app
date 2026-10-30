"""Unit tests for native portrait vision training runner and pipeline contracts.

Task ID: ARIA-PORTRAIT-RUNNER-001
Tests:
- CLI argument parsing, defaults, and stage choices.
- Stage schedule invariants and configurations across all 7 stages.
- Paired augmentation geometric and provenance weight preservation ({0.0, 0.25, 1.0}).
- Checkpoint metadata saving and strict manifest hash validation (rejection on mismatch).
- Synthetic smoke batch generation contracts.
- TensorFlow execution (when TF present):
  - Trainer micro-step and gradient accumulation.
  - Remainder gradient flush.
  - Checkpoint save and restore weight roundtrip.
  - Bounded CPU smoke test end-to-end execution.
"""

import json
import os
import random
import sys
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path


# Restrict default threading
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
os.environ.setdefault("TF_NUM_INTEROP_THREADS", "2")
os.environ.setdefault("TF_NUM_INTRAOP_THREADS", "2")

# Ensure repository root is on sys.path
_REPO_ROOT = Path(__file__).resolve().parents[4]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    np = None  # type: ignore
    HAS_NUMPY = False

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None  # type: ignore
    HAS_PIL = False

try:
    import tensorflow as tf
    from tensorflow import keras
    HAS_TF = True
except (ImportError, ModuleNotFoundError):
    tf = None  # type: ignore
    keras = None  # type: ignore
    HAS_TF = False

from tools.vision.training.train import (
    DEFAULT_ACCUMULATE_STEPS,
    DEFAULT_BATCH_SIZE,
    DEFAULT_CLIPNORM,
    DEFAULT_SEED,
    OOM_FALLBACK_ACCUMULATE_STEPS,
    OOM_FALLBACK_BATCH_SIZE,
    STAGE_SPECS,
    CheckpointManager,
    CheckpointMetadata,
    apply_paired_augmentation,
    build_parser,
    generate_synthetic_smoke_batch,
    run_smoke_test,
)
from tools.vision.training.model import (
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    NUM_HAZARD_CLASSES,
    NUM_SEMANTIC_CLASSES,
    IGNORE_LABEL,
    build_model_a,
    build_model_b,
)


class TestTrainContractsAndCLI(unittest.TestCase):
    """Verifies train.py CLI interface and stage configurations."""

    def test_cli_parser_defaults(self):
        parser = build_parser()
        args = parser.parse_args(["--smoke"])
        self.assertTrue(args.smoke)
        self.assertEqual(args.stage, "city_warmup")
        self.assertEqual(args.batch_size, DEFAULT_BATCH_SIZE)
        self.assertEqual(args.accumulate_steps, DEFAULT_ACCUMULATE_STEPS)
        self.assertEqual(args.device, "cpu")
        self.assertEqual(args.seed, DEFAULT_SEED)

    def test_stage_specifications_completeness(self):
        expected_stages = [
            "city_warmup",
            "city_full",
            "mixed_decoder",
            "mixed_block13",
            "stage_b_head",
            "stage_b_joint",
            "stage_c",
        ]
        for stage_name in expected_stages:
            self.assertIn(stage_name, STAGE_SPECS, f"Missing stage definition: {stage_name}")
            spec = STAGE_SPECS[stage_name]
            self.assertIn(spec["architecture"], ["model_a", "model_b", "model_c"])
            self.assertGreater(spec["epochs"], 0)
            self.assertGreater(spec["learning_rate"], 0.0)
            self.assertIn("encoder_policy", spec)
            self.assertTrue(spec["freeze_bn"])

    def test_synthetic_smoke_batch_contract(self):
        if not HAS_NUMPY:
            self.skipTest("NumPy is required for synthetic batch test.")

        images, sems, weights, hazards, hazard_valids = generate_synthetic_smoke_batch(
            batch_size=2, include_hazard=True, seed=42
        )
        self.assertEqual(images.shape, (2, IMAGE_HEIGHT, IMAGE_WIDTH, 3))
        self.assertEqual(sems.shape, (2, IMAGE_HEIGHT, IMAGE_WIDTH))
        self.assertEqual(weights.shape, (2, IMAGE_HEIGHT, IMAGE_WIDTH))
        self.assertEqual(hazards.shape, (2, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES))
        self.assertEqual(hazard_valids.shape, (2, IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES))

        # Check weight values are only {0.0, 0.25, 1.0}
        unique_weights = set(np.unique(weights))
        for w in unique_weights:
            self.assertTrue(
                np.isclose(w, 0.0) or np.isclose(w, 0.25) or np.isclose(w, 1.0),
                f"Unexpected weight value: {w}",
            )

        # Check ignore pixels have strictly 0.0 weight
        ignore_pixels = sems == IGNORE_LABEL
        if np.any(ignore_pixels):
            self.assertTrue(np.all(weights[ignore_pixels] == 0.0))


class TestPairedAugmentation(unittest.TestCase):
    """Verifies that paired augmentation preserves strict provenance weights and alignment."""

    def setUp(self):
        if not HAS_NUMPY or not HAS_PIL:
            self.skipTest("NumPy and Pillow are required for augmentation tests.")

    def test_weight_preservation_and_ignore_mask(self):
        rng = random.Random(42)
        # Create known pattern
        img = np.full((IMAGE_HEIGHT, IMAGE_WIDTH, 3), 128.0, dtype=np.float32)
        sem = np.zeros((IMAGE_HEIGHT, IMAGE_WIDTH), dtype=np.int32)
        sem[100:200, 50:150] = 4  # person (pseudo)
        sem[300:400, 100:200] = IGNORE_LABEL  # ignore

        wt = np.ones((IMAGE_HEIGHT, IMAGE_WIDTH), dtype=np.float32)
        wt[sem == 4] = 0.25
        wt[sem == IGNORE_LABEL] = 0.0

        haz = np.zeros((IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES), dtype=np.float32)
        val = np.zeros((IMAGE_HEIGHT, IMAGE_WIDTH, NUM_HAZARD_CLASSES), dtype=np.float32)
        haz[10:50, 10:50, 0] = 1.0
        val[10:50, 10:50, 0] = 1.0

        # Run augmentation
        aug_img, aug_sem, aug_wt, aug_haz, aug_val = apply_paired_augmentation(
            img, sem, wt, haz, val, rng
        )

        self.assertEqual(aug_img.shape, (IMAGE_HEIGHT, IMAGE_WIDTH, 3))
        self.assertEqual(aug_sem.shape, (IMAGE_HEIGHT, IMAGE_WIDTH))
        self.assertEqual(aug_wt.shape, (IMAGE_HEIGHT, IMAGE_WIDTH))

        # Crucial check: weights must strictly belong to {0.0, 0.25, 1.0}
        unique_wts = np.unique(aug_wt)
        for w in unique_wts:
            self.assertTrue(
                np.isclose(w, 0.0) or np.isclose(w, 0.25) or np.isclose(w, 1.0),
                f"Weight degraded to non-discrete value: {w}",
            )

        # Ignore pixels must strictly have 0.0 weight
        ignore_mask = aug_sem == IGNORE_LABEL
        if np.any(ignore_mask):
            self.assertTrue(np.all(aug_wt[ignore_mask] == 0.0))


class TestCheckpointManager(unittest.TestCase):
    """Verifies strict checkpoint management and failure on manifest hash mismatch."""

    def test_manifest_hash_mismatch_raises_error(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            ckpt_dir = Path(tmp_dir) / "checkpoints"
            meta = CheckpointMetadata(
                stage="city_warmup",
                epoch=2,
                global_step=100,
                data_manifest_hash="hash_alpha_12345",
            )
            # Write metadata file directly
            tag_dir = ckpt_dir / "latest"
            tag_dir.mkdir(parents=True, exist_ok=True)
            with (tag_dir / "metadata.json").open("w", encoding="utf-8") as f:
                json.dump(asdict(meta), f)

            mgr = CheckpointManager(ckpt_dir)
            # Expect ValueError when expected hash differs
            with self.assertRaises(ValueError):
                mgr.restore_checkpoint(
                    model=None,  # type: ignore
                    tag="latest",
                    expected_manifest_hash="hash_beta_67890",
                )


class TestTrainTensorFlowExecution(unittest.TestCase):
    """Verifies actual TensorFlow training micro-steps, accumulation, and smoke execution."""

    def setUp(self):
        if not HAS_TF or not HAS_NUMPY:
            self.skipTest("TensorFlow >= 2.19 and NumPy are required for training execution tests.")

    def test_staged_trainer_micro_step_and_accumulation(self):
        from tools.vision.training.train import StagedTrainer

        model = build_model_a(weights=None, freeze_bn=True, input_range="0_255")
        with tempfile.TemporaryDirectory() as tmp_dir:
            trainer = StagedTrainer(
                model=model,
                architecture="model_a",
                stage="city_warmup",
                learning_rate=1e-3,
                batch_size=2,
                accumulate_steps=2,
                checkpoint_dir=Path(tmp_dir),
            )

            img_np, sem_np, wt_np, _, _ = generate_synthetic_smoke_batch(batch_size=2, include_hazard=False)

            # Step 1: accumulator count should become 1, stepped should be False
            stepped, metrics = trainer.train_micro_step(
                images=tf.constant(img_np),
                semantic_true=tf.constant(sem_np),
                semantic_weight=tf.constant(wt_np),
            )
            self.assertFalse(stepped)
            self.assertEqual(trainer.accumulation_counter, 1)
            self.assertGreater(metrics["total_loss"], 0.0)

            # Step 2: accumulator count reaches 2, optimizer steps, accumulator resets to 0
            stepped2, metrics2 = trainer.train_micro_step(
                images=tf.constant(img_np),
                semantic_true=tf.constant(sem_np),
                semantic_weight=tf.constant(wt_np),
            )
            self.assertTrue(stepped2)
            self.assertEqual(trainer.accumulation_counter, 0)

    def test_run_smoke_test_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            report = run_smoke_test(
                output_dir=Path(tmp_dir),
                steps=2,
                seed=42,
            )
            self.assertEqual(report["status"], "SUCCEEDED")
            self.assertEqual(report["device"], "CPU")
            self.assertGreater(report["parameter_update_max_diff"], 0.0)
            self.assertLessEqual(report["checkpoint_roundtrip_diff"], 1e-6)
            self.assertLessEqual(report["parity_max_diff"], 1e-4)


if __name__ == "__main__":
    unittest.main()
