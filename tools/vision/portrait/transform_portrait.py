#!/usr/bin/env python3
"""
tools/vision/portrait/transform_portrait.py

Transform walkable_seg_aria5_unfused.tflite (landscape: input [1, 288, 512, 3], output [1, 288, 512, 7])
into walkable_seg_aria5_portrait_unfused.tflite (portrait: input [1, 512, 288, 3], output [1, 512, 288, 7])
with NO retraining and NO learned-weight modification.

Preserves all 122 learned weight and bias buffers bit-for-bit identical (SHA256 verified).
Preserves all normalizer scalars and model metadata buffers bit-for-bit identical.
Swaps spatial dimensions (H <-> W) on:
  1. 103 4D activation tensors ([1, H, W, C] -> [1, W, H, C]) and any static shapeSignature
  2. 6 decoder / deconv / slice shape constant buffers:
     - Buffer 4 (Op 205 input_sizes): [1, 288, 512, 16] -> [1, 512, 288, 16]
     - Buffer 5 (Op 201 input_sizes): [1, 144, 256, 32] -> [1, 256, 144, 32]
     - Buffer 6 (Op 197 input_sizes): [1, 72, 128, 64] -> [1, 128, 72, 64]
     - Buffer 7 (Op 193 input_sizes): [1, 36, 64, 128] -> [1, 64, 36, 128]
     - Buffer 8 (Op 189 sizes tensor): [1, 18, 32, 256] -> [1, 32, 18, 256]
     - Buffer 339 (Op 189 valid output shape): [1, 19, 33, 256] -> [1, 33, 19, 256]

PAD Inspection Note:
  The graph contains zero PAD / PADV2 operators. All 51 convolution and depthwise convolution
  nodes have BuiltinOptions.padding = SAME (or VALID on 1x1 final conv) with symmetric strides
  ((1,1) or (2,2)) and square kernels (1x1 or 3x3). No asymmetric Keras correct_pad matrices
  exist in the compiled TFLite flatbuffers.
"""

import argparse
import copy
import hashlib
import json
import os
import struct
import sys
import tempfile
from pathlib import Path


def _configure_import_paths(schema_dir: str = None, pydeps_dir: str = None):
    """Configure search path for flatbuffers and tflite schema python bindings."""
    candidates = []
    if schema_dir and os.path.isdir(schema_dir):
        candidates.append(schema_dir)
    if pydeps_dir and os.path.isdir(pydeps_dir):
        candidates.append(pydeps_dir)

    # Check environment variables
    env_schema = os.environ.get("TFLITE_SCHEMA_DIR")
    if env_schema and os.path.isdir(env_schema):
        candidates.append(env_schema)
    env_pydeps = os.environ.get("TFLITE_PYDEPS_DIR")
    if env_pydeps and os.path.isdir(env_pydeps):
        candidates.append(env_pydeps)

    # Dynamic user-agnostic temp directory discovery
    temp_dir = tempfile.gettempdir()
    dyn_tflite = os.path.join(temp_dir, "aria-vision-transform")
    dyn_pydeps = os.path.join(temp_dir, "aria-vision-transform", "pydeps")
    for d in [dyn_tflite, dyn_pydeps]:
        if os.path.isdir(d):
            candidates.append(d)

    # Local appdata fallback (Windows)
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        lad_tflite = os.path.join(local_app_data, "Temp", "aria-vision-transform")
        lad_pydeps = os.path.join(local_app_data, "Temp", "aria-vision-transform", "pydeps")
        for d in [lad_tflite, lad_pydeps]:
            if os.path.isdir(d):
                candidates.append(d)

    for p in candidates:
        resolved = str(Path(p).resolve())
        if resolved not in sys.path:
            sys.path.insert(0, resolved)


_configure_import_paths()

try:
    import flatbuffers
    from tflite.Model import Model, ModelT
    from tflite.Buffer import BufferT
    from tflite.Tensor import TensorT
    from tflite.BuiltinOperator import BuiltinOperator
except ImportError as e:
    raise ImportError(
        f"Required flatbuffers/tflite modules not importable: {e}. "
        "Ensure flatbuffers package and generated tflite schema python bindings are on PYTHONPATH, "
        "or pass --schema-dir / --pydeps-dir or set TFLITE_SCHEMA_DIR environment variable."
    ) from e

EXPECTED_INPUT_SHA256 = "84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847"
ORIGINAL_INPUT_SHAPE = [1, 288, 512, 3]
ORIGINAL_OUTPUT_SHAPE = [1, 288, 512, 7]
PORTRAIT_INPUT_SHAPE = [1, 512, 288, 3]
PORTRAIT_OUTPUT_SHAPE = [1, 512, 288, 7]


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def transform_model_to_portrait(
    input_path: str,
    output_path: str,
    audit_path: str = None,
    verify_input_hash: bool = True,
) -> dict:
    input_p = Path(input_path).resolve()
    output_p = Path(output_path).resolve()

    if not input_p.is_file():
        raise FileNotFoundError(f"Input model not found: {input_p}")

    # Ensure source file is not the same as output file (immutability)
    if input_p == output_p:
        raise ValueError(
            f"Output path must be distinct from input path to ensure source immutability! ({input_p})"
        )

    actual_input_sha = sha256_file(str(input_p))
    if verify_input_hash and actual_input_sha.lower() != EXPECTED_INPUT_SHA256.lower():
        raise ValueError(
            f"Input SHA-256 mismatch! Expected {EXPECTED_INPUT_SHA256}, got {actual_input_sha}"
        )

    with open(input_p, "rb") as f:
        orig_buf = f.read()

    root = Model.GetRootAs(orig_buf, 0)
    model = ModelT.InitFromObj(root)
    subgraph = model.subgraphs[0]

    # Pre-validation: reject unsupported graph or repeated conversion
    inp_tensor = subgraph.tensors[subgraph.inputs[0]]
    out_tensor = subgraph.tensors[subgraph.outputs[0]]
    current_inp_shape = list(inp_tensor.shape) if inp_tensor.shape is not None else []
    current_out_shape = list(out_tensor.shape) if out_tensor.shape is not None else []

    if current_inp_shape == PORTRAIT_INPUT_SHAPE and current_out_shape == PORTRAIT_OUTPUT_SHAPE:
        raise ValueError(
            f"Repeated conversion rejected! Model is already portrait: input={current_inp_shape}, output={current_out_shape}"
        )

    if current_inp_shape != ORIGINAL_INPUT_SHAPE or current_out_shape != ORIGINAL_OUTPUT_SHAPE:
        raise ValueError(
            f"Unsupported graph topology! Expected input {ORIGINAL_INPUT_SHAPE} and output {ORIGINAL_OUTPUT_SHAPE}, "
            f"got input {current_inp_shape} and output {current_out_shape}"
        )

    # Verify no PAD operators exist and record rationale
    pad_op_indices = []
    for idx, op in enumerate(subgraph.operators):
        code = model.operatorCodes[op.opcodeIndex].builtinCode
        if code in (BuiltinOperator.PAD, BuiltinOperator.PADV2):
            pad_op_indices.append(idx)
    if pad_op_indices:
        raise ValueError(
            f"Unexpected PAD operators detected at indices {pad_op_indices}; graph requires PAD analysis"
        )

    # Classify and inventory ALL buffers before modification
    buf_to_tensors = {}
    for t_idx, t in enumerate(subgraph.tensors):
        buf_to_tensors.setdefault(t.buffer, []).append(t_idx)

    # Known shape buffer indices
    shape_buffer_indices = {4, 5, 6, 7, 8, 339, 340}

    learned_weights_inventory = {}
    normalizer_scalars_inventory = {}
    model_metadata_inventory = {}

    for b_idx, buf in enumerate(model.buffers):
        data = bytes(buf.data) if buf.data is not None else b""
        if len(data) == 0:
            continue
        if b_idx in shape_buffer_indices:
            continue

        t_indices = buf_to_tensors.get(b_idx, [])
        if not t_indices:
            # Metadata buffer without associated tensor
            model_metadata_inventory[b_idx] = {
                "len": len(data),
                "sha256": sha256_bytes(data),
            }
            continue

        t = subgraph.tensors[t_indices[0]]
        t_shape = [int(x) for x in t.shape] if t.shape is not None else []
        t_name = t.name.decode("utf-8", "ignore") if t.name else ""

        if len(data) <= 8 and (t.type in (0, 1)):
            normalizer_scalars_inventory[b_idx] = {
                "tensor_index": t_indices[0],
                "name": t_name,
                "shape": t_shape,
                "dtype": int(t.type),
                "len": len(data),
                "sha256": sha256_bytes(data),
            }
        else:
            learned_weights_inventory[b_idx] = {
                "tensor_index": t_indices[0],
                "name": t_name,
                "shape": t_shape,
                "dtype": int(t.type),
                "len": len(data),
                "sha256": sha256_bytes(data),
            }

    # Track audit details
    changed_tensors_audit = []
    changed_buffers_audit = []

    # 1. Transform spatial activation tensors: swap H and W on 4D activation tensors [1, H, W, C]
    for idx, t in enumerate(subgraph.tensors):
        shape = list(t.shape) if t.shape is not None else []
        b = model.buffers[t.buffer]
        b_len = len(b.data) if b.data is not None else 0
        name = t.name.decode("utf-8", "ignore") if t.name else ""

        # 4D activation tensor with empty buffer
        if len(shape) == 4 and shape[0] == 1 and b_len == 0:
            old_shape = [int(x) for x in shape]
            new_shape = [old_shape[0], old_shape[2], old_shape[1], old_shape[3]]
            t.shape = new_shape

            # Also check and update shapeSignature if concrete signature is present
            old_sig = None
            new_sig = None
            if t.shapeSignature is not None:
                sig_list = list(t.shapeSignature)
                if len(sig_list) == 4 and sig_list[0] == 1:
                    old_sig = [int(x) for x in sig_list]
                    new_sig = [old_sig[0], old_sig[2], old_sig[1], old_sig[3]]
                    t.shapeSignature = new_sig

            changed_tensors_audit.append(
                {
                    "tensor_index": idx,
                    "name": name,
                    "old_shape": old_shape,
                    "new_shape": new_shape,
                    "old_shapeSignature": old_sig,
                    "new_shapeSignature": new_sig,
                    "type": int(t.type),
                }
            )

    # 2. Transform shape constant buffers
    # There are exactly 6 shape constants with H/W values to swap
    expected_shape_buffers = {
        4: [1, 288, 512, 16],
        5: [1, 144, 256, 32],
        6: [1, 72, 128, 64],
        7: [1, 36, 64, 128],
        8: [1, 18, 32, 256],
        339: [1, 19, 33, 256],
    }

    for b_idx, exp_old_vals in expected_shape_buffers.items():
        buf = model.buffers[b_idx]
        cur_vals = list(struct.unpack("<4i", bytes(buf.data)))
        if cur_vals != exp_old_vals:
            raise ValueError(
                f"Unexpected buffer {b_idx} contents! Expected {exp_old_vals}, got {cur_vals}"
            )
        new_vals = [cur_vals[0], cur_vals[2], cur_vals[1], cur_vals[3]]
        new_data = list(struct.pack("<4i", *new_vals))
        old_sha = sha256_bytes(bytes(buf.data))
        buf.data = new_data
        new_sha = sha256_bytes(bytes(buf.data))

        t_indices = buf_to_tensors.get(b_idx, [])
        t_name = (
            subgraph.tensors[t_indices[0]].name.decode("utf-8", "ignore")
            if t_indices
            else ""
        )
        changed_buffers_audit.append(
            {
                "buffer_index": b_idx,
                "tensor_index": t_indices[0] if t_indices else None,
                "name": t_name,
                "old_values": cur_vals,
                "new_values": new_vals,
                "old_sha256": old_sha,
                "new_sha256": new_sha,
            }
        )

    # Verify Buffer 340 (slice starts) remains [0, 0, 0, 0]
    b340_vals = list(struct.unpack("<4i", bytes(model.buffers[340].data)))
    if b340_vals != [0, 0, 0, 0]:
        raise ValueError(f"Buffer 340 slice starts unexpected: {b340_vals}")

    # Verify ALL non-shape buffers (learned weights, normalizer scalars, metadata) are bit-identical
    for b_idx, info in learned_weights_inventory.items():
        cur_data = bytes(model.buffers[b_idx].data)
        cur_sha = sha256_bytes(cur_data)
        if cur_sha != info["sha256"]:
            raise AssertionError(
                f"Learned buffer {b_idx} ({info['name']}) modified! Expected {info['sha256']}, got {cur_sha}"
            )

    for b_idx, info in normalizer_scalars_inventory.items():
        cur_data = bytes(model.buffers[b_idx].data)
        cur_sha = sha256_bytes(cur_data)
        if cur_sha != info["sha256"]:
            raise AssertionError(
                f"Normalizer scalar buffer {b_idx} ({info['name']}) modified! Expected {info['sha256']}, got {cur_sha}"
            )

    for b_idx, info in model_metadata_inventory.items():
        cur_data = bytes(model.buffers[b_idx].data)
        cur_sha = sha256_bytes(cur_data)
        if cur_sha != info["sha256"]:
            raise AssertionError(
                f"Model metadata buffer {b_idx} modified! Expected {info['sha256']}, got {cur_sha}"
            )

    # Serialize transformed model
    builder = flatbuffers.Builder(1024 * 1024 * 16)
    offset = model.Pack(builder)
    builder.Finish(offset, b"TFL3")
    out_buf = builder.Output()

    output_p.parent.mkdir(parents=True, exist_ok=True)
    temp_p = output_p.with_suffix(".tmp")
    with open(temp_p, "wb") as f:
        f.write(out_buf)
    os.replace(temp_p, output_p)

    actual_output_sha = sha256_file(str(output_p))

    audit = {
        "task_id": "ARIA-PORTRAIT-MODEL-001",
        "source_model": {
            "path": str(input_p),
            "sha256": actual_input_sha,
            "input_shape": ORIGINAL_INPUT_SHAPE,
            "output_shape": ORIGINAL_OUTPUT_SHAPE,
        },
        "target_model": {
            "path": str(output_p),
            "sha256": actual_output_sha,
            "size_bytes": len(out_buf),
            "input_shape": PORTRAIT_INPUT_SHAPE,
            "output_shape": PORTRAIT_OUTPUT_SHAPE,
        },
        "buffer_categories": {
            "learned_weights_and_biases_count": len(learned_weights_inventory),
            "normalizer_scalars_count": len(normalizer_scalars_inventory),
            "model_metadata_count": len(model_metadata_inventory),
            "modified_shape_constants_count": len(changed_buffers_audit),
            "unmodified_shape_constants_count": 1,
            "all_non_shape_buffers_bit_identical": True,
        },
        "learned_weights_inventory": {
            "count": len(learned_weights_inventory),
            "all_bit_identical": True,
            "buffers": learned_weights_inventory,
        },
        "normalizer_scalars_inventory": normalizer_scalars_inventory,
        "model_metadata_inventory": model_metadata_inventory,
        "changed_shape_buffers": changed_buffers_audit,
        "changed_activation_tensors_count": len(changed_tensors_audit),
        "changed_activation_tensors": changed_tensors_audit,
        "pad_inspection": {
            "pad_operators_found": 0,
            "rationale": "All 51 conv/depthwise ops compile with BuiltinOptions.padding=SAME (or VALID on 1x1 conv) with symmetric strides and square kernels. No explicit PAD/PADV2 nodes or correct_pad tensors exist.",
        },
    }

    if audit_path:
        audit_p = Path(audit_path).resolve()
        audit_p.parent.mkdir(parents=True, exist_ok=True)
        with open(audit_p, "w", encoding="utf-8") as f:
            json.dump(audit, f, indent=2)

    return audit


def main():
    parser = argparse.ArgumentParser(
        description="Transform ARiA segmentation model to portrait (512x288) with bit-identical learned weights"
    )
    parser.add_argument(
        "--input",
        "-i",
        default="app/src/main/assets/models/walkable_seg_aria5_unfused.tflite",
        help="Input landscape model path",
    )
    parser.add_argument(
        "--output",
        "-o",
        default="build/vision-portrait-model/walkable_seg_aria5_portrait_unfused.tflite",
        help="Output portrait model path",
    )
    parser.add_argument(
        "--audit",
        "-a",
        default="build/vision-portrait-model/audit.json",
        help="Output audit JSON path",
    )
    parser.add_argument(
        "--schema-dir",
        default=None,
        help="Directory containing generated tflite schema python bindings",
    )
    parser.add_argument(
        "--pydeps-dir",
        default=None,
        help="Directory containing flatbuffers python package",
    )
    parser.add_argument(
        "--no-verify-input-sha",
        action="store_true",
        help="Skip strict input SHA-256 assertion",
    )

    args = parser.parse_args()
    if args.schema_dir or args.pydeps_dir:
        _configure_import_paths(args.schema_dir, args.pydeps_dir)

    print(f"Transforming {args.input} -> {args.output}")
    audit = transform_model_to_portrait(
        input_path=args.input,
        output_path=args.output,
        audit_path=args.audit,
        verify_input_hash=not args.no_verify_input_sha,
    )
    print("Transformation successful!")
    print(f"Output model: {audit['target_model']['path']}")
    print(f"Output SHA-256: {audit['target_model']['sha256']}")
    print(f"Output size: {audit['target_model']['size_bytes']} bytes")
    print(
        f"Learned weights ({audit['buffer_categories']['learned_weights_and_biases_count']}), "
        f"normalizers ({audit['buffer_categories']['normalizer_scalars_count']}), "
        f"metadata ({audit['buffer_categories']['model_metadata_count']}): ALL bit-identical!"
    )
    print(f"Changed shape buffers: {len(audit['changed_shape_buffers'])}")
    print(f"Changed activation tensors: {audit['changed_activation_tensors_count']}")
    if args.audit:
        print(f"Audit written to: {args.audit}")


if __name__ == "__main__":
    main()
