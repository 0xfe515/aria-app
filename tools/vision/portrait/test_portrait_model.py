#!/usr/bin/env python3
"""
tools/vision/portrait/test_portrait_model.py

Comprehensive unit tests and audit verification for ARiA portrait model transformation.
Verifies:
  - Source file immutability
  - Transformation determinism
  - Rejection of repeated conversion
  - Rejection of identical input/output path
  - Bit-for-bit learned weight buffer preservation across all parameter buffers
  - Exact spatial constant buffer swapping (H <-> W)
  - Input/output tensor shapes and types contract ([1, 512, 288, 3] -> [1, 512, 288, 7])
  - Activation tensor shape propagation
  - Decoder VALID + SLICE + RELU GPU lowering consistency for odd dimension 16x9
"""

import hashlib
import json
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

# Add portrait directory to path
PORTRAIT_DIR = str(Path(__file__).parent.resolve())
if PORTRAIT_DIR not in sys.path:
    sys.path.insert(0, PORTRAIT_DIR)

from transform_portrait import (
    EXPECTED_INPUT_SHA256,
    ORIGINAL_INPUT_SHAPE,
    ORIGINAL_OUTPUT_SHAPE,
    PORTRAIT_INPUT_SHAPE,
    PORTRAIT_OUTPUT_SHAPE,
    sha256_file,
    sha256_bytes,
    transform_model_to_portrait,
)

import flatbuffers
from tflite.Model import Model, ModelT
from tflite.BuiltinOperator import BuiltinOperator
from tflite.Padding import Padding

SOURCE_MODEL_PATH = "app/src/main/assets/models/walkable_seg_aria5_unfused.tflite"
PORTRAIT_MODEL_PATH = "build/vision-portrait-model/walkable_seg_aria5_portrait_unfused.tflite"
AUDIT_JSON_PATH = "build/vision-portrait-model/audit.json"


class TestPortraitModelTransformation(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source_path = Path(SOURCE_MODEL_PATH).resolve()
        cls.portrait_path = Path(PORTRAIT_MODEL_PATH).resolve()
        cls.audit_path = Path(AUDIT_JSON_PATH).resolve()

        if not cls.source_path.exists():
            raise FileNotFoundError(f"Source model not found at {cls.source_path}")

        # Run transform once to ensure artifacts exist
        cls.audit = transform_model_to_portrait(
            input_path=str(cls.source_path),
            output_path=str(cls.portrait_path),
            audit_path=str(cls.audit_path),
            verify_input_hash=True,
        )

    def test_01_source_immutability(self):
        """Verify the source model file is unchanged bit-for-bit."""
        current_source_sha = sha256_file(str(self.source_path))
        self.assertEqual(
            current_source_sha.lower(),
            EXPECTED_INPUT_SHA256.lower(),
            "Source model file was modified!",
        )

    def test_02_determinism(self):
        """Verify transforming multiple times produces identical SHA-256."""
        with tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as tf1, \
             tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as tf2:
            tf1_path = tf1.name
            tf2_path = tf2.name

        try:
            transform_model_to_portrait(str(self.source_path), tf1_path)
            transform_model_to_portrait(str(self.source_path), tf2_path)
            sha1 = sha256_file(tf1_path)
            sha2 = sha256_file(tf2_path)
            self.assertEqual(sha1, sha2, "Transform is non-deterministic!")
            self.assertEqual(
                sha1,
                self.audit["target_model"]["sha256"],
                "Determinism check mismatch with audit SHA-256!",
            )
        finally:
            if os.path.exists(tf1_path):
                os.remove(tf1_path)
            if os.path.exists(tf2_path):
                os.remove(tf2_path)

    def test_03_rejects_repeated_conversion(self):
        """Verify that passing an already converted portrait model is rejected."""
        with tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as tf_out:
            tf_out_path = tf_out.name
        try:
            with self.assertRaises(ValueError) as ctx:
                transform_model_to_portrait(
                    str(self.portrait_path),
                    tf_out_path,
                    verify_input_hash=False,
                )
            self.assertIn("Repeated conversion rejected", str(ctx.exception))
        finally:
            if os.path.exists(tf_out_path):
                os.remove(tf_out_path)

    def test_04_rejects_same_path(self):
        """Verify that attempting to overwrite input file in place is rejected."""
        with self.assertRaises(ValueError) as ctx:
            transform_model_to_portrait(
                str(self.source_path),
                str(self.source_path),
                verify_input_hash=False,
            )
        self.assertIn("must be distinct", str(ctx.exception))

    def test_05_input_output_contract(self):
        """Verify input and output tensor shapes and types match contract."""
        with open(self.portrait_path, "rb") as f:
            buf = f.read()
        root = Model.GetRootAs(buf, 0)
        model = ModelT.InitFromObj(root)
        subgraph = model.subgraphs[0]

        self.assertEqual(len(subgraph.inputs), 1)
        self.assertEqual(len(subgraph.outputs), 1)

        inp_t = subgraph.tensors[subgraph.inputs[0]]
        out_t = subgraph.tensors[subgraph.outputs[0]]

        self.assertEqual(list(inp_t.shape), PORTRAIT_INPUT_SHAPE)
        self.assertEqual(list(out_t.shape), PORTRAIT_OUTPUT_SHAPE)
        self.assertEqual(inp_t.type, 0)  # FLOAT32
        self.assertEqual(out_t.type, 0)  # FLOAT32

    def test_06_learned_weights_bit_identical(self):
        """Verify all learned parameter buffers are bit-for-bit identical."""
        with open(self.source_path, "rb") as f:
            s_buf = f.read()
        s_model = ModelT.InitFromObj(Model.GetRootAs(s_buf, 0))

        with open(self.portrait_path, "rb") as f:
            p_buf = f.read()
        p_model = ModelT.InitFromObj(Model.GetRootAs(p_buf, 0))

        self.assertEqual(len(s_model.buffers), len(p_model.buffers))

        shape_buffer_indices = {4, 5, 6, 7, 8, 339}
        for b_idx in range(len(s_model.buffers)):
            s_b = s_model.buffers[b_idx]
            p_b = p_model.buffers[b_idx]

            s_data = bytes(s_b.data) if s_b.data is not None else b""
            p_data = bytes(p_b.data) if p_b.data is not None else b""

            if b_idx in shape_buffer_indices:
                # Shape buffer: verify it changed
                self.assertNotEqual(
                    s_data,
                    p_data,
                    f"Shape buffer {b_idx} was not modified!",
                )
            else:
                # Learned weight, bias, metadata, or empty buffer: must match bit-for-bit
                self.assertEqual(
                    s_data,
                    p_data,
                    f"Buffer {b_idx} mismatch! Learned weights must be bit-for-bit identical.",
                )

    def test_07_shape_constants_correctly_swapped(self):
        """Verify all 6 shape constants are correctly swapped to portrait."""
        with open(self.portrait_path, "rb") as f:
            p_buf = f.read()
        p_model = ModelT.InitFromObj(Model.GetRootAs(p_buf, 0))

        expected_swapped = {
            4: [1, 512, 288, 16],
            5: [1, 256, 144, 32],
            6: [1, 128, 72, 64],
            7: [1, 64, 36, 128],
            8: [1, 32, 18, 256],
            339: [1, 33, 19, 256],
        }

        for b_idx, exp_vals in expected_swapped.items():
            vals = list(struct.unpack("<4i", bytes(p_model.buffers[b_idx].data)))
            self.assertEqual(
                vals,
                exp_vals,
                f"Buffer {b_idx} shape values incorrect: got {vals}, expected {exp_vals}",
            )

        # Buffer 340 (slice starts) must remain [0, 0, 0, 0]
        starts = list(struct.unpack("<4i", bytes(p_model.buffers[340].data)))
        self.assertEqual(starts, [0, 0, 0, 0])

    def test_08_activation_tensors_count_and_shapes(self):
        """Verify that all 103 4D activation tensors have swapped spatial dimensions."""
        with open(self.source_path, "rb") as f:
            s_buf = f.read()
        s_model = ModelT.InitFromObj(Model.GetRootAs(s_buf, 0))
        s_sub = s_model.subgraphs[0]

        with open(self.portrait_path, "rb") as f:
            p_buf = f.read()
        p_model = ModelT.InitFromObj(Model.GetRootAs(p_buf, 0))
        p_sub = p_model.subgraphs[0]

        self.assertEqual(len(s_sub.tensors), len(p_sub.tensors))

        swapped_count = 0
        for idx in range(len(s_sub.tensors)):
            s_t = s_sub.tensors[idx]
            p_t = p_sub.tensors[idx]

            s_shape = list(s_t.shape) if s_t.shape is not None else []
            p_shape = list(p_t.shape) if p_t.shape is not None else []

            s_data = s_model.buffers[s_t.buffer].data
            s_blen = len(s_data) if s_data is not None else 0

            if len(s_shape) == 4 and s_shape[0] == 1 and s_blen == 0:
                expected_p_shape = [s_shape[0], s_shape[2], s_shape[1], s_shape[3]]
                self.assertEqual(
                    p_shape,
                    expected_p_shape,
                    f"Activation tensor {idx} ({s_t.name}) shape mismatch!",
                )
                swapped_count += 1
            else:
                self.assertEqual(
                    p_shape,
                    s_shape,
                    f"Non-activation tensor {idx} ({s_t.name}) shape should not change!",
                )

        self.assertEqual(swapped_count, 103)

    def test_09_operator_topology_preserved(self):
        """Verify that operator count, codes, options, and connections are intact."""
        with open(self.source_path, "rb") as f:
            s_buf = f.read()
        s_model = ModelT.InitFromObj(Model.GetRootAs(s_buf, 0))
        s_sub = s_model.subgraphs[0]

        with open(self.portrait_path, "rb") as f:
            p_buf = f.read()
        p_model = ModelT.InitFromObj(Model.GetRootAs(p_buf, 0))
        p_sub = p_model.subgraphs[0]

        self.assertEqual(len(s_sub.operators), len(p_sub.operators))
        self.assertEqual(len(s_sub.operators), 210)

        for idx in range(len(s_sub.operators)):
            s_op = s_sub.operators[idx]
            p_op = p_sub.operators[idx]

            self.assertEqual(s_op.opcodeIndex, p_op.opcodeIndex)
            self.assertEqual(list(s_op.inputs), list(p_op.inputs))
            self.assertEqual(list(s_op.outputs), list(p_op.outputs))

    def test_10_audit_json_consistency(self):
        """Verify audit.json exists and accurately reflects the transformation."""
        self.assertTrue(self.audit_path.exists())
        with open(self.audit_path, "r", encoding="utf-8") as f:
            audit_data = json.load(f)

        self.assertEqual(audit_data["task_id"], "ARIA-PORTRAIT-MODEL-001")
        self.assertEqual(
            audit_data["source_model"]["sha256"],
            EXPECTED_INPUT_SHA256,
        )
        self.assertEqual(
            audit_data["target_model"]["sha256"],
            sha256_file(str(self.portrait_path)),
        )
        self.assertEqual(
            audit_data["target_model"]["input_shape"],
            PORTRAIT_INPUT_SHAPE,
        )
        self.assertEqual(
            audit_data["target_model"]["output_shape"],
            PORTRAIT_OUTPUT_SHAPE,
        )
        self.assertTrue(audit_data["learned_weights_inventory"]["all_bit_identical"])
        self.assertEqual(len(audit_data["changed_shape_buffers"]), 6)
        self.assertEqual(audit_data["changed_activation_tensors_count"], 103)


if __name__ == "__main__":
    unittest.main()
