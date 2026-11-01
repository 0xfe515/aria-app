#!/usr/bin/env python3
"""Unit tests for the generalized WSL full training launcher.

Tests command construction, safe stage/path resolution, duplicate-process refusal,
checkpoint predecessor validation, and status observation without requiring Windows,
WSL, TensorFlow, GPU, or real datasets.
"""

from __future__ import annotations

import json
import subprocess
import re
import tempfile
from typing import Any
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from tools.vision.training.environment import launch_full_training as launcher


class TestStageAndPathResolution(unittest.TestCase):
    """Verifies stage validation and path resolution rules."""

    def test_supported_stages_allowlist(self) -> None:
        expected = (
            "city_warmup",
            "city_full",
            "mixed_decoder",
            "mixed_block13",
            "stage_b_head",
            "stage_b_joint",
            "stage_c",
        )
        self.assertEqual(launcher.SUPPORTED_STAGES, expected)

    def test_validate_stage_valid_and_invalid(self) -> None:
        self.assertEqual(launcher.validate_stage("city_warmup"), "city_warmup")
        self.assertEqual(launcher.validate_stage("  city_full  "), "city_full")
        self.assertEqual(launcher.validate_stage("mixed_decoder"), "mixed_decoder")

        with self.assertRaises(ValueError) as ctx:
            launcher.validate_stage("unknown_stage")
        self.assertIn("Unsupported stage 'unknown_stage'", str(ctx.exception))

        with self.assertRaises(ValueError):
            launcher.validate_stage("")

    def test_validate_epochs_bounds(self) -> None:
        # None returns stage-specific default
        self.assertEqual(launcher.validate_epochs(None, "city_warmup"), 5)
        self.assertEqual(launcher.validate_epochs(None, "city_full"), 30)

        # Valid explicit bounds
        self.assertEqual(launcher.validate_epochs(1, "city_warmup"), 1)
        self.assertEqual(launcher.validate_epochs(100, "city_warmup"), 100)
        self.assertEqual(launcher.validate_epochs(10, "city_full"), 10)

        # Non-integers / booleans
        with self.assertRaises(ValueError):
            launcher.validate_epochs(True, "city_warmup")  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            launcher.validate_epochs("5", "city_warmup")  # type: ignore[arg-type]

        # Out-of-bounds
        with self.assertRaises(ValueError):
            launcher.validate_epochs(0, "city_warmup")
        with self.assertRaises(ValueError):
            launcher.validate_epochs(-1, "city_warmup")
        with self.assertRaises(ValueError):
            launcher.validate_epochs(101, "city_warmup")

    def test_resolve_attempt_paths_warmup_defaults(self) -> None:
        paths = launcher.resolve_attempt_paths("attempt_02", stage="city_warmup")
        self.assertEqual(paths["attempt_id"], "attempt_02")
        self.assertEqual(paths["stage"], "city_warmup")
        self.assertTrue(paths["output_dir"].endswith("/attempt_02"))
        self.assertTrue(paths["stage_dir"].endswith("/attempt_02/city_warmup"))
        self.assertEqual(paths["systemd_unit"], "aria-city-warmup-20261031-attempt-02")

    def test_resolve_attempt_paths_empty_attempt(self) -> None:
        paths = launcher.resolve_attempt_paths("", stage="city_warmup")
        self.assertEqual(paths["attempt_id"], "attempt_01")
        self.assertEqual(paths["stage_dir"], f"{launcher.GUEST_BASE_DIR}/city_warmup")
        self.assertEqual(paths["systemd_unit"], "aria-city-warmup-20261031")

    def test_resolve_attempt_paths_later_stages(self) -> None:
        # city_full
        cf = launcher.resolve_attempt_paths("attempt_03", stage="city_full")
        self.assertEqual(cf["attempt_id"], "attempt_03")
        self.assertEqual(cf["stage"], "city_full")
        self.assertTrue(cf["output_dir"].endswith("/attempt_03"))
        self.assertTrue(cf["stage_dir"].endswith("/attempt_03/city_full"))
        self.assertEqual(cf["systemd_unit"], "aria-city-full-20261031-attempt-03")

        # mixed_decoder
        md = launcher.resolve_attempt_paths("attempt_03", stage="mixed_decoder")
        self.assertEqual(md["systemd_unit"], "aria-mixed-decoder-20261031-attempt-03")
        self.assertTrue(md["stage_dir"].endswith("/attempt_03/mixed_decoder"))

        # stage_b_head
        sb = launcher.resolve_attempt_paths("attempt_04", stage="stage_b_head")
        self.assertEqual(sb["systemd_unit"], "aria-stage-b-head-20261031-attempt-04")

    def test_resolve_host_paths_preserves_city_warmup_defaults(self) -> None:
        base = Path("/fake/full-training")
        hp_default = launcher.resolve_host_paths("attempt_02", "city_warmup", host_base=base)
        self.assertEqual(hp_default["receipt_path"], base / "launch_receipt.json")
        self.assertEqual(hp_default["status_path"], base / "launch_attempt_status.json")
        self.assertEqual(hp_default["keeper_dir"], base)

    def test_resolve_host_paths_later_stages(self) -> None:
        base = Path("/fake/full-training")
        hp = launcher.resolve_host_paths("attempt_03", "city_full", host_base=base)
        self.assertEqual(hp["receipt_path"], base / "attempt_03" / "city_full" / "launch_receipt.json")
        self.assertEqual(hp["status_path"], base / "attempt_03" / "city_full" / "launch_attempt_status.json")
        self.assertEqual(hp["keeper_dir"], base / "attempt_03" / "city_full")


class TestCommandConstruction(unittest.TestCase):
    """Verifies command construction for all stages and arguments."""

    def test_build_trainer_command_city_warmup_default(self) -> None:
        cmd = launcher.build_trainer_command(
            stage="city_warmup",
            output_dir="/fake/attempt_02",
        )
        self.assertIn("--stage", cmd)
        idx_stage = cmd.index("--stage")
        self.assertEqual(cmd[idx_stage + 1], "city_warmup")

        self.assertIn("--weights", cmd)
        idx_weights = cmd.index("--weights")
        self.assertEqual(cmd[idx_weights + 1], "imagenet")

        self.assertNotIn("--init-checkpoint", cmd)

        self.assertIn("--epochs", cmd)
        idx_epochs = cmd.index("--epochs")
        self.assertEqual(cmd[idx_epochs + 1], "5")

        self.assertIn("--learning-rate", cmd)
        idx_lr = cmd.index("--learning-rate")
        self.assertEqual(cmd[idx_lr + 1], "0.001")

        self.assertIn("--output-dir", cmd)
        idx_out = cmd.index("--output-dir")
        self.assertEqual(cmd[idx_out + 1], "/fake/attempt_02")

    def test_build_trainer_command_city_warmup_refuses_init_checkpoint(self) -> None:
        with self.assertRaises(ValueError) as ctx:
            launcher.build_trainer_command(
                stage="city_warmup",
                output_dir="/fake/attempt_02",
                init_checkpoint="/fake/ckpt",
            )
        self.assertIn("cannot accept --init-checkpoint", str(ctx.exception))

    def test_build_trainer_command_city_full_with_checkpoint(self) -> None:
        init_ckpt = "/home/user/.../attempt_03/city_warmup/checkpoints/best"
        cmd = launcher.build_trainer_command(
            stage="city_full",
            output_dir="/fake/attempt_03",
            init_checkpoint=init_ckpt,
            epochs=10,
        )
        self.assertIn("--stage", cmd)
        self.assertEqual(cmd[cmd.index("--stage") + 1], "city_full")

        self.assertNotIn("--weights", cmd)

        self.assertIn("--init-checkpoint", cmd)
        self.assertEqual(cmd[cmd.index("--init-checkpoint") + 1], init_ckpt)

        self.assertIn("--epochs", cmd)
        self.assertEqual(cmd[cmd.index("--epochs") + 1], "10")

        self.assertIn("--learning-rate", cmd)
        self.assertEqual(cmd[cmd.index("--learning-rate") + 1], "0.0001")

    def test_build_trainer_command_later_stages_require_init_checkpoint(self) -> None:
        for stage in ("city_full", "mixed_decoder", "mixed_block13", "stage_b_head", "stage_b_joint"):
            with self.subTest(stage=stage):
                with self.assertRaises(ValueError) as ctx:
                    launcher.build_trainer_command(
                        stage=stage,
                        output_dir="/fake/out",
                        init_checkpoint=None,
                    )
                self.assertIn("requires an --init-checkpoint", str(ctx.exception))

    def test_build_trainer_command_learning_rate_override(self) -> None:
        cmd = launcher.build_trainer_command(
            stage="city_warmup",
            output_dir="/fake/out",
            learning_rate=0.0005,
        )
        self.assertEqual(cmd[cmd.index("--learning-rate") + 1], "0.0005")


class TestCheckpointPredecessorValidation(unittest.TestCase):
    """Verifies predecessor checkpoint chain inspection."""

    def test_validate_checkpoint_metadata_valid(self) -> None:
        meta_warmup = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 5,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }
        # city_full predecessor is city_warmup / model_a
        launcher.validate_checkpoint_metadata(meta_warmup, stage="city_full")

        meta_city_full = {
            "stage": "city_full",
            "architecture": "model_a",
            "epoch": 10,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }
        # mixed_decoder predecessor is city_full / model_a
        launcher.validate_checkpoint_metadata(meta_city_full, stage="mixed_decoder")

    def test_validate_checkpoint_metadata_stage_mismatch(self) -> None:
        meta_wrong_stage = {
            "stage": "mixed_decoder",
            "architecture": "model_a",
            "epoch": 5,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }
        with self.assertRaises(ValueError) as ctx:
            launcher.validate_checkpoint_metadata(meta_wrong_stage, stage="city_full")
        self.assertIn("Initialization checkpoint must be city_warmup/model_a", str(ctx.exception))

    def test_validate_checkpoint_metadata_architecture_mismatch(self) -> None:
        meta_wrong_arch = {
            "stage": "city_warmup",
            "architecture": "model_b",
            "epoch": 5,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }
        with self.assertRaises(ValueError) as ctx:
            launcher.validate_checkpoint_metadata(meta_wrong_arch, stage="city_full")
        self.assertIn("Initialization checkpoint must be city_warmup/model_a", str(ctx.exception))

    def test_validate_checkpoint_metadata_epoch_zero_refused(self) -> None:
        meta_epoch_0 = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 0,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }
        with self.assertRaises(ValueError) as ctx:
            launcher.validate_checkpoint_metadata(meta_epoch_0, stage="city_full")
        self.assertIn("records no completed training epoch", str(ctx.exception))

    def test_validate_checkpoint_metadata_manifest_hash_mismatch(self) -> None:
        meta_bad_hash = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 5,
            "data_manifest_hash": "badhash123",
        }
        with self.assertRaises(ValueError) as ctx:
            launcher.validate_checkpoint_metadata(meta_bad_hash, stage="city_full")
        self.assertIn("manifest hash", str(ctx.exception))

    def test_supported_checkpoint_weight_names_contains_model_weights_and_legacy(self) -> None:
        self.assertIn("model_weights.weights.h5", launcher.SUPPORTED_CHECKPOINT_WEIGHT_NAMES)
        self.assertIn("weights.keras", launcher.SUPPORTED_CHECKPOINT_WEIGHT_NAMES)
        self.assertIn("weights.weights.h5", launcher.SUPPORTED_CHECKPOINT_WEIGHT_NAMES)
        self.assertIn("checkpoint", launcher.SUPPORTED_CHECKPOINT_WEIGHT_NAMES)

    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_check_checkpoint_weights_exist_accepted_and_safe_quoting(
        self, mock_run_wsl: MagicMock
    ) -> None:
        mock_run_wsl.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="exists\n"
        )
        exists = launcher.check_checkpoint_weights_exist("/path/with space/best")
        self.assertTrue(exists)

        called_cmd = mock_run_wsl.call_args[0][0]
        self.assertIn("'/path/with space/best/model_weights.weights.h5'", called_cmd)
        self.assertIn("'/path/with space/best/weights.keras'", called_cmd)
        self.assertIn("'/path/with space/best/weights.weights.h5'", called_cmd)
        self.assertIn("'/path/with space/best/checkpoint'", called_cmd)

        mock_run_wsl.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="missing\n"
        )
        self.assertFalse(launcher.check_checkpoint_weights_exist("/path/with space/best"))

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_validation_accepts_model_weights_checkpoint(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256
        ckpt_meta = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 1,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "metadata.json" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=json.dumps(ckpt_meta) + "\n"
                )
            if "model_weights.weights.h5" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="exists\n")
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="inactive\n")
            if "pgrep" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "checkpoints" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="empty\n")
            if "exit_code.txt" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="missing\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_full")

            # Must succeed without error when model_weights.weights.h5 is detected
            launcher.prelaunch_validation(
                src_dir,
                attempt_paths=attempt_paths,
                stage="city_full",
                init_checkpoint="/home/user/.local/share/aria-training/full-training-20261031/attempt_03/city_warmup/checkpoints/best",
            )

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_validation_rejects_missing_all_checkpoint_weights(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256
        ckpt_meta = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 1,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "metadata.json" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=json.dumps(ckpt_meta) + "\n"
                )
            if "model_weights.weights.h5" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="missing\n")
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="inactive\n")
            if "pgrep" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "checkpoints" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="empty\n")
            if "exit_code.txt" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="missing\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_full")

            with self.assertRaises(FileNotFoundError) as ctx:
                launcher.prelaunch_validation(
                    src_dir,
                    attempt_paths=attempt_paths,
                    stage="city_full",
                    init_checkpoint="/fake/ckpt/without/weights",
                )
            self.assertIn("No weights file found in initialization checkpoint directory", str(ctx.exception))



class TestDuplicateProtectionAndRefusal(unittest.TestCase):
    """Verifies prelaunch collision refusal for active trainer, active unit, and existing artifacts."""

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_refuses_when_trainer_already_running(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="4045\n")
            if "kill -0 4045" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_warmup")

            with self.assertRaises(RuntimeError) as ctx:
                launcher.prelaunch_validation(src_dir, attempt_paths=attempt_paths, stage="city_warmup")
            self.assertIn("Existing training process is already running (PID 4045)", str(ctx.exception))

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_refuses_when_systemd_unit_active(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="active\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_warmup")

            with self.assertRaises(RuntimeError) as ctx:
                launcher.prelaunch_validation(src_dir, attempt_paths=attempt_paths, stage="city_warmup")
            self.assertIn("already active! Refusing duplicate launch", str(ctx.exception))

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_refuses_when_completed_run_exists(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="inactive\n")
            if "pgrep" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "checkpoints" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="empty\n")
            if "exit_code.txt" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="exists\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_warmup")

            with self.assertRaises(FileExistsError) as ctx:
                launcher.prelaunch_validation(src_dir, attempt_paths=attempt_paths, stage="city_warmup")
            self.assertIn("already contains a completed run", str(ctx.exception))

    def test_global_trainer_pgrep_pattern_avoids_self_matching(self) -> None:
        pattern = launcher.GLOBAL_TRAINER_PGREP_PATTERN
        self.assertEqual(pattern, "[r]un_wsl_training.py")

        # Must match real trainer process invocation
        trainer_cmdlines = (
            "/home/user/.venvs/aria_wsl/bin/python /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py --manifest /path/manifest.json",
            "python3 run_wsl_training.py --stage mixed_decoder",
        )
        for cmdline in trainer_cmdlines:
            with self.subTest(cmdline=cmdline):
                self.assertIsNotNone(re.search(pattern, cmdline))

        # Must NOT match invoking bash wrappers or pgrep itself
        wrapper_cmdlines = (
            f"/bin/bash -lc pgrep -f '{pattern}' 2>/dev/null || true",
            f"/bin/bash -c pgrep -f '{pattern}' 2>/dev/null || true",
            f"pgrep -f {pattern}",
        )
        for cmdline in wrapper_cmdlines:
            with self.subTest(cmdline=cmdline):
                self.assertIsNone(re.search(pattern, cmdline))

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_queries_global_trainer_with_self_match_safe_pattern(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256

        def fake_run_wsl(cmd: str, check: bool = True, **kwargs: Any) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="inactive\n")
            if "pgrep" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "checkpoints" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="empty\n")
            if "exit_code.txt" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="missing\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_warmup")

            launcher.prelaunch_validation(src_dir, attempt_paths=attempt_paths, stage="city_warmup")

        # Find the pgrep call and verify it used the self-match-safe pattern
        pgrep_calls = [
            call_args[0][0]
            for call_args in mock_run_wsl.call_args_list
            if "pgrep" in call_args[0][0]
        ]
        self.assertEqual(len(pgrep_calls), 1)
        self.assertIn(launcher.GLOBAL_TRAINER_PGREP_PATTERN, pgrep_calls[0])
        self.assertIn("[r]un_wsl_training.py", pgrep_calls[0])
        self.assertNotIn("pgrep -f 'run_wsl_training.py'", pgrep_calls[0])

    @patch("tools.vision.training.environment.launch_full_training.sha256_file")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_prelaunch_refuses_when_competing_global_trainer_running(
        self, mock_run_wsl: MagicMock, mock_sha256: MagicMock
    ) -> None:
        mock_sha256.return_value = launcher.EXPECTED_BASE_MANIFEST_SHA256
        ckpt_meta = {
            "stage": "city_full",
            "architecture": "model_a",
            "epoch": 30,
            "data_manifest_hash": launcher.EXPECTED_BASE_MANIFEST_SHA256,
        }

        def fake_run_wsl(cmd: str, check: bool = True, **kwargs: Any) -> subprocess.CompletedProcess[str]:
            if "sha256sum" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=f"{launcher.EXPECTED_BASE_MANIFEST_SHA256}  manifest.json\n"
                )
            if "metadata.json" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=json.dumps(ckpt_meta) + "\n"
                )
            if "model_weights.weights.h5" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="exists\n")
            if "cat" in cmd and "trainer.pid" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="")
            if "is-active" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=1, stdout="inactive\n")
            if "pgrep" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="5123\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        with tempfile.TemporaryDirectory() as td:
            src_dir = Path(td)
            (src_dir / "manifest.json").write_text("{}", encoding="utf-8")
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="mixed_decoder")

            with self.assertRaises(RuntimeError) as ctx:
                launcher.prelaunch_validation(
                    src_dir,
                    attempt_paths=attempt_paths,
                    stage="mixed_decoder",
                    init_checkpoint="/fake/best",
                )
            self.assertIn("Another active training process (PID 5123) is currently running in WSL!", str(ctx.exception))


class TestRunWslDecoding(unittest.TestCase):
    """Verifies that run_wsl explicitly decodes WSL subprocess text as UTF-8 with safe replacement."""

    @patch("subprocess.run")
    def test_run_wsl_default_decoding_kwargs(self, mock_subprocess_run: MagicMock) -> None:
        mock_subprocess_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="ok\n", stderr=""
        )
        res = launcher.run_wsl("echo ok")
        self.assertEqual(res.stdout, "ok\n")
        mock_subprocess_run.assert_called_once()
        args, kwargs = mock_subprocess_run.call_args
        self.assertEqual(
            args[0],
            ["wsl", "-d", launcher.WSL_DISTRO, "-e", "/bin/bash", "-c", "echo ok"],
        )
        self.assertTrue(kwargs.get("capture_output"))
        self.assertTrue(kwargs.get("text"))
        self.assertEqual(kwargs.get("encoding"), "utf-8")
        self.assertEqual(kwargs.get("errors"), "replace")
        self.assertTrue(kwargs.get("check"))

    @patch("subprocess.run")
    def test_run_wsl_allows_custom_check_and_encoding_kwargs(
        self, mock_subprocess_run: MagicMock
    ) -> None:
        mock_subprocess_run.return_value = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr=""
        )
        launcher.run_wsl("false", check=False, encoding="utf-8", errors="ignore")
        _, kwargs = mock_subprocess_run.call_args
        self.assertFalse(kwargs.get("check"))
        self.assertEqual(kwargs.get("encoding"), "utf-8")
        self.assertEqual(kwargs.get("errors"), "ignore")


class TestStatusObservation(unittest.TestCase):
    """Verifies that snapshot_current_state inspects completed runs cleanly without launching."""

    @patch("tools.vision.training.environment.launch_full_training.read_proc_identity")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_snapshot_current_state_completed_run(
        self, mock_run_wsl: MagicMock, mock_read_proc: MagicMock
    ) -> None:
        mock_read_proc.return_value = {
            "pid": 4045,
            "cmdline": "python train.py",
            "ps_info": "",
            "start_ticks": "12345",
            "io_stats": {},
            "is_alive": False,
        }

        best_meta = {
            "stage": "city_warmup",
            "architecture": "model_a",
            "epoch": 5,
            "val_loss": 0.7878,
            "metrics": {"city_miou": 0.6720},
        }

        def fake_run_wsl(cmd: str, check: bool = True) -> subprocess.CompletedProcess[str]:
            if "training.log" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout="Epoch 5/5 complete\nTraining finished.\n"
                )
            if "best/metadata.json" in cmd:
                return subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=json.dumps(best_meta) + "\n"
                )
            if "exit_code.txt" in cmd:
                return subprocess.CompletedProcess(args=[], returncode=0, stdout="0\n")
            return subprocess.CompletedProcess(args=[], returncode=0, stdout="")

        mock_run_wsl.side_effect = fake_run_wsl

        attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_warmup")
        snap = launcher.snapshot_current_state(
            pids={"supervisor_pid": "4044", "trainer_pid": "4045"},
            attempt_paths=attempt_paths,
            keeper_info={"windows_keeper_pid": 10624, "is_alive": True},
            gpu_status={"name": "NVIDIA GTX 1660 SUPER"},
            stage="city_warmup",
        )

        self.assertEqual(snap["stage"], "city_warmup")
        self.assertEqual(snap["exit_code_txt"], "0")
        self.assertTrue(snap["completed"])
        self.assertEqual(snap["best_checkpoint_metadata"], best_meta)
        self.assertEqual(snap["training_log_lines"], 2)


class TestWindowsKeeperHandling(unittest.TestCase):
    """Verifies Windows keeper behavior when trainer is not alive."""

    def test_ensure_windows_keeper_trainer_not_alive(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            keeper_dir = Path(td)
            res = launcher.ensure_windows_keeper(
                trainer_pid="4045",
                start_ticks="12345",
                keeper_dir=keeper_dir,
                trainer_alive=False,
            )
            self.assertFalse(res["is_alive"])
            self.assertEqual(res["reason"], "trainer_not_alive")
            self.assertEqual(res["windows_keeper_pid"], None)

    def test_ensure_windows_keeper_reads_existing_file_when_dead(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            keeper_dir = Path(td)
            existing = {
                "windows_keeper_pid": 999999,
                "is_alive": True,
                "bound_trainer_pid": 4045,
                "bound_start_ticks": "12345",
            }
            (keeper_dir / "windows_keeper.json").write_text(json.dumps(existing), encoding="utf-8")

            with patch("tools.vision.training.environment.launch_full_training.check_windows_pid_alive", return_value=False):
                res = launcher.ensure_windows_keeper(
                    trainer_pid="4045",
                    start_ticks="12345",
                    keeper_dir=keeper_dir,
                    trainer_alive=False,
                )
            self.assertFalse(res["is_alive"])
            self.assertEqual(res["reason"], "trainer_not_alive")


class TestLaunchReceiptCreation(unittest.TestCase):
    """Verifies launch receipt content for generalized stages."""

    @patch("tools.vision.training.environment.launch_full_training.read_proc_identity")
    @patch("tools.vision.training.environment.launch_full_training.run_wsl")
    def test_create_launch_receipt_city_full(
        self, mock_run_wsl: MagicMock, mock_read_proc: MagicMock
    ) -> None:
        mock_run_wsl.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="")
        mock_read_proc.return_value = {
            "cmdline": "python train.py",
            "ps_info": "ps info",
            "start_ticks": "5555",
            "io_stats": {},
            "is_alive": True,
        }

        with tempfile.TemporaryDirectory() as td:
            receipt_path = Path(td) / "launch_receipt.json"
            attempt_paths = launcher.resolve_attempt_paths("attempt_03", stage="city_full")

            monitor_results = {
                "observed_banner": True,
                "observed_config": True,
                "observed_initial_eval": True,
                "observed_batch_1": True,
                "observed_batch_21": True,
                "observed_planned_batches": True,
                "planned_batches": 1275,
                "latest_loss": 0.55,
                "loss_is_finite": True,
                "batch_history": [(1, 1.2), (21, 0.55)],
                "elapsed_seconds": 120.0,
                "final_gpu_status": {"name": "GPU"},
            }

            receipt = launcher.create_launch_receipt(
                pids={"supervisor_pid": "100", "trainer_pid": "101"},
                attempt_paths=attempt_paths,
                monitor_results=monitor_results,
                initial_gpu_status={"name": "GPU"},
                keeper_info={"windows_keeper_pid": 200, "is_alive": True},
                host_receipt_path=receipt_path,
                stage="city_full",
                epochs=10,
                init_checkpoint="/home/user/.../best",
            )

            self.assertEqual(receipt["stage"], "city_full")
            self.assertEqual(receipt["configuration"]["epochs"], 10)
            self.assertEqual(receipt["configuration"]["init_checkpoint"], "/home/user/.../best")
            self.assertIn("--stage city_full", receipt["commands"]["resume_command"])
            self.assertIn("--epochs 10", receipt["commands"]["resume_command"])
            self.assertTrue(receipt_path.is_file())


if __name__ == "__main__":
    unittest.main()
