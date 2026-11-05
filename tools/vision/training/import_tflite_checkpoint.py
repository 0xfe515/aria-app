#!/usr/bin/env python3
"""
TFLite checkpoint importer for Aria vision models.

This script extracts weights from a production TFLite model and creates
a TensorFlow checkpoint compatible with the training code.

Task ID: TASK_BASELINE_TFLITE_WARMSTART_20261102
"""

import os
import sys
import hashlib
import numpy as np
import tensorflow as tf
from pathlib import Path
from typing import Dict, List, Tuple, Optional

# Add the repository root to sys.path
_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.vision.training.model import (
    build_model_a,
    NUM_SEMANTIC_CLASSES,
    SEMANTIC_CLASSES,
    IMAGE_HEIGHT,
    IMAGE_WIDTH,
    IMAGE_CHANNELS,
    INPUT_SHAPE,
    ENCODER_SKIP_NAMES,
)


def compute_file_sha256(path: Path) -> str:
    """Computes SHA-256 digest of a file."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def extract_tflite_tensors(tflite_path: Path) -> Dict[str, np.ndarray]:
    """
    Extract all tensor values from a TFLite model.
    
    Args:
        tflite_path: Path to the TFLite model file
        
    Returns:
        Dictionary mapping tensor names to numpy arrays
    """
    # Load the TFLite model
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    
    # Get tensor details
    tensor_details = interpreter.get_tensor_details()
    
    # Extract tensor values
    tensors = {}
    for tensor_detail in tensor_details:
        tensor_id = tensor_detail['index']
        name = tensor_detail['name'].decode('utf-8') if isinstance(tensor_detail['name'], bytes) else tensor_detail['name']
        shape = tensor_detail['shape']
        dtype = tensor_detail['dtype']
        
        # Get the tensor value
        value = interpreter.get_tensor(tensor_id)
        tensors[name] = value
        
    return tensors


def build_deterministic_mapping(tflite_tensors: Dict[str, np.ndarray], keras_variables: List[tf.Variable]) -> List[np.ndarray]:
    """
    Build a deterministic mapping from TFLite tensors to Keras variables.
    Requires EXACT match for every variable - no fallbacks, no fuzzy matching.
    
    Args:
        tflite_tensors: Dictionary mapping tensor names to numpy arrays from TFLite model
        keras_variables: List of trainable variables from Keras model
        
    Returns:
        List of numpy arrays in the same order as keras_variables
        
    Raises:
        ValueError: If any Keras variable cannot be mapped to exactly one TFLite tensor
                   with matching name and shape, or if mapping is ambiguous
    """
    # Create reverse lookup: tensor name -> tensor value for quick access
    mapped_values = []
    used_tflite_tensors = set()
    
    print(f"Building deterministic mapping for {len(keras_variables)} Keras variables...")
    
    for var in keras_variables:
        var_name = var.name
        var_shape = var.shape
        
        # Find EXACT matching tensor in TFLite by name
        matching_tensors = []
        for tflite_name, tflite_value in tflite_tensors.items():
            # Names must match EXACTLY after normalizing separators
            norm_var_name = var_name.replace('/', '_').replace(':', '_')
            norm_tflite_name = tflite_name.replace('/', '_').replace(':', '_')
            
            if norm_var_name == norm_tflite_name:
                # Additionally require exact shape match
                if tflite_value.shape == var_shape:
                    matching_tensors.append((tflite_name, tflite_value))
                else:
                    raise ValueError(
                        f"Shape mismatch for {var_name}: "
                        f"Keras shape {var_shape} vs TFLite tensor '{tflite_name}' shape {tflite_value.shape}"
                    )
        
        # Validate we have exactly one match
        if len(matching_tensors) == 0:
            raise ValueError(
                f"No matching TFLite tensor found for Keras variable '{var_name}' "
                f"(shape {var_shape}). Available TFLite tensors: {list(tflite_tensors.keys())[:10]}..."
            )
        elif len(matching_tensors) > 1:
            raise ValueError(
                f"Ambiguous mapping for Keras variable '{var_name}': "
                f"matches {len(matching_tensors)} TFLite tensors: {[name for name, _ in matching_tensors]}"
            )
        
        # We have exactly one match - use it
        tflite_name, tflite_value = matching_tensors[0]
        
        # Check if this tensor was already used (shouldn't happen with good naming, but check anyway)
        if tflite_name in used_tflite_tensors:
            raise ValueError(
                f"TFLite tensor '{tflite_name}' mapped to multiple Keras variables. "
                f"This indicates duplicated naming in the model."
            )
        
        mapped_values.append(tflite_value)
        used_tflite_tensors.add(tflite_name)
        
        if len(used_tflite_tensors) % 50 == 0:
            print(f"  Mapped {len(used_tflite_tensors)}/{len(keras_variables)} variables...")
    
    # Verify we used all TFLite tensors that should be used
    unused_tensors = set(tflite_tensors.keys()) - used_tflite_tensors
    if unused_tensors:
        print(f"Warning: {len(unused_tensors)} TFLite tensors not used in mapping: {list(unused_tensors)[:5]}...")
        # This might be OK if there are extra tensors like dropout masks, etc.
        # But for safety in a production importer, we might want to treat this as an error
        # For now, we'll warn but continue - the core requirement is no missing mappings
    
    print(f"Successfully mapped {len(mapped_values)} variables deterministically.")
    return mapped_values


def create_keras_model_from_tflite(tflite_path: Path) -> tf.keras.Model:
    """
    Create a Keras model matching the TFLite architecture and populate it with weights
    ONLY if deterministic mapping and numerical parity can be proven.
    
    Args:
        tflite_path: Path to the TFLite model file
        
    Returns:
        Keras model with weights extracted from the TFLite model
        
    Raises:
        ValueError: If deterministic mapping fails, numerical parity fails, 
                   or any other validation issue occurs
    """
    # Verify the TFLite model SHA256
    expected_sha256 = "312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1"
    actual_sha256 = compute_file_sha256(tflite_path)
    
    if actual_sha256.lower() != expected_sha256.lower():
        raise ValueError(
            f"TFLite model SHA-256 mismatch! Expected {expected_sha256}, got {actual_sha256}"
        )
    
    # Extract tensors from TFLite model
    print(f"Extracting tensors from TFLite model: {tflite_path}")
    tflite_tensors = extract_tflite_tensors(tflite_path)
    print(f"Extracted {len(tflite_tensors)} tensors")
    
    # Build the Keras model (Model A architecture)
    print("Building Keras Model A...")
    keras_model = build_model_a(
        input_shape=INPUT_SHAPE,
        weights=None,  # We'll load our own weights
        freeze_bn=False,  # We'll set this after loading weights
        input_range="0_255"
    )
    
    # Get Keras trainable variables
    keras_variables = keras_model.trainable_variables
    print(f"Keras model has {len(keras_variables)} trainable variables")
    
    # Create deterministic mapping - NO FALLBACKS ALLOWED
    print("Building deterministic tensor mapping...")
    var_values = build_deterministic_mapping(tflite_tensors, keras_variables)
    
    # Set the weights in the model
    print("Setting weights in Keras model...")
    keras_model.set_weights(var_values)
    
    # VERIFY NUMERICAL PARITY BEFORE PROCEEDING
    print("Verifying numerical parity between TFLite and Keras models...")
    _verify_numerical_parity(keras_model, tflite_path)
    
    # Freeze batch normalization layers as expected
    print("Freezing batch normalization layers...")
    from tools.vision.training.model import freeze_batch_normalization
    frozen_count = freeze_batch_normalization(keras_model)
    print(f"Frozen {frozen_count} batch normalization layers")
    
    return keras_model


def _verify_numerical_parity(keras_model: tf.keras.Model, tflite_path: Path) -> None:
    """
    Verify that Keras model produces numerically equivalent outputs to TFLite model.
    Uses deterministic input for reproducible testing.
    
    Args:
        keras_model: The Keras model with loaded weights
        tflite_path: Path to the original TFLite model
        
    Raises:
        ValueError: If numerical parity fails beyond tolerance
    """
    import numpy as np
    
    # Create deterministic test input - all zeros for simplicity
    # Shape must match: [1, 512, 288, 3] as specified in task context
    test_input = np.zeros((1, 512, 288, 3), dtype=np.float32)
    
    # Get Keras model output
    keras_output = keras_model(test_input, training=False)
    keras_output_np = keras_output.numpy()
    
    # Get TFLite model output
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    # Set input and run inference
    interpreter.set_tensor(input_details[0]['index'], test_input)
    interpreter.invoke()
    tflite_output = interpreter.get_tensor(output_details[0]['index'])
    
    # Calculate difference
    diff = np.abs(keras_output_np - tflite_output)
    max_diff = np.max(diff)
    mean_diff = np.mean(diff)
    
    print(f"Numerical parity check:")
    print(f"  Max absolute difference: {max_diff}")
    print(f"  Mean absolute difference: {mean_diff}")
    print(f"  Keras output shape: {keras_output_np.shape}")
    print(f"  TFLite output shape: {tflite_output.shape}")
    
    # Require very tight tolerance - weights should map exactly
    parity_tolerance = 1e-5
    
    if max_diff > parity_tolerance:
        raise ValueError(
            f"Numerical parity FAILED! Max difference {max_diff} exceeds tolerance {parity_tolerance}. "
            f"This indicates the weight mapping is incorrect. "
            f"Keras and TFLite outputs must match exactly for deterministic mapping to be valid."
        )
    
    print(f"✓ Numerical parity PASSED (max diff: {max_diff})")


def save_checkpoint(model: tf.keras.Model, checkpoint_dir: Path) -> None:
    """
    Save the Keras model weights to a checkpoint directory.
    
    Args:
        model: Keras model to save
        checkpoint_dir: Directory where checkpoint should be saved
    """
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    
    # Save weights in TensorFlow checkpoint format
    checkpoint_prefix = checkpoint_dir / "model_weights"
    checkpoint = tf.train.Checkpoint(model=model)
    save_path = checkpoint.save(file_prefix=str(checkpoint_prefix))
    
    # Also save as H5 for compatibility
    h5_path = checkpoint_dir / "model_weights.h5"
    model.save_weights(str(h5_path))
    
    # Create metadata file
    metadata = {
        "architecture": "model_a",
        "input_shape": list(INPUT_SHAPE),
        "num_semantic_classes": NUM_SEMANTIC_CLASSES,
        "semantic_classes": SEMANTIC_CLASSES,
        "created_by": "import_tflite_checkpoint.py",
        "source_tflite_sha256": "312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1"
    }
    
    import json
    metadata_path = checkpoint_dir / "metadata.json"
    with open(metadata_path, 'w') as f:
        json.dump(metadata, f, indent=2)
    
    print(f"Checkpoint saved to: {checkpoint_dir}")
    print(f"  - TensorFlow checkpoint: {save_path}")
    print(f"  - HDF5 weights: {h5_path}")
    print(f"  - Metadata: {metadata_path}")


def main():
    """Main function to run the TFLite to checkpoint conversion."""
    if len(sys.argv) < 3:
        print("Usage: python import_tflite_checkpoint.py <tflite_model_path> <output_checkpoint_dir>")
        print("Example: python import_tflite_checkpoint.py app/src/main/assets/models/walkable_seg_aria5_portrait_unfused.tflite checkpoints/portrait_baseline")
        sys.exit(1)
    
    tflite_path = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    
    if not tflite_path.exists():
        print(f"Error: TFLite model not found at {tflite_path}")
        sys.exit(1)
    
    print("=" * 70)
    print("Aria TFLite to Checkpoint Importer")
    print(f"TFLite Model: {tflite_path}")
    print(f"Output Dir:   {output_dir}")
    print("=" * 70)
    
    try:
        # Create Keras model from TFLite
        model = create_keras_model_from_tflite(tflite_path)
        
        # Save checkpoint
        save_checkpoint(model, output_dir)
        
        print("\n" + "=" * 70)
        print("Import Completed Successfully!")
        print("=" * 70)
        
    except Exception as e:
        print(f"Error during import: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()