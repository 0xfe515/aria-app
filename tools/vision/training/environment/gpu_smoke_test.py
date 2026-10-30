#!/usr/bin/env python3
"""
GPU / TF Training Smoke Test for Aria Native Portrait Segmentation Model Environment.

NOTE ON SCOPE:
- This script is an ENVIRONMENT SMOKE TEST designed to verify TensorFlow 2.19 runtime
  execution, autodiff/gradient descent loops, checkpoint serialization, and TFLite export.
- It uses a lightweight synthetic dual-head CNN with shapes matching Aria portrait input [B, 512, 288, 3],
  semantic output [B, 512, 288, 7], and hazard output [B, 512, 288, 8].
- It does NOT evaluate MobileNetV2 accuracy, feature extraction capacity, or production model quality.
"""

import argparse
import glob
import os
import site
import sys
import tempfile
import time
import numpy as np


def _ensure_wsl_cuda_env() -> None:
    if os.name != "posix" or os.environ.get("_ARIA_WSL_CUDA_ENV_SET") == "1":
        return

    lib_dirs: list[str] = []
    if os.path.isdir("/usr/lib/wsl/lib"):
        lib_dirs.append("/usr/lib/wsl/lib")

    for sp in site.getsitepackages():
        for d in sorted(glob.glob(os.path.join(sp, "nvidia", "*", "lib"))):
            if os.path.isdir(d) and d not in lib_dirs:
                lib_dirs.append(d)

    bin_dirs: list[str] = []
    for sp in site.getsitepackages():
        for d in sorted(glob.glob(os.path.join(sp, "nvidia", "*", "bin"))):
            if os.path.isdir(d) and d not in bin_dirs:
                bin_dirs.append(d)

    current_ld = [p for p in os.environ.get("LD_LIBRARY_PATH", "").split(":") if p]
    missing_ld = [p for p in lib_dirs if p not in current_ld]

    current_path = [p for p in os.environ.get("PATH", "").split(":") if p]
    missing_bin = [p for p in bin_dirs if p not in current_path]
    if os.path.isdir("/usr/lib/wsl/lib") and "/usr/lib/wsl/lib" not in current_path:
        missing_bin.append("/usr/lib/wsl/lib")

    if missing_ld or missing_bin:
        os.environ["LD_LIBRARY_PATH"] = ":".join(lib_dirs + current_ld)
        os.environ["PATH"] = ":".join(missing_bin + current_path)
        os.environ["_ARIA_WSL_CUDA_ENV_SET"] = "1"
        os.execv(sys.executable, [sys.executable] + sys.argv)



def parse_args():
    parser = argparse.ArgumentParser(description="Aria Native Portrait TF GPU Smoke Test")
    parser.add_argument("--steps", type=int, default=100, help="Number of training steps (default: 100)")
    parser.add_argument("--batch-size", type=int, default=2, help="Batch size (default: 2)")
    parser.add_argument("--height", type=int, default=512, help="Input height (default: 512)")
    parser.add_argument("--width", type=int, default=288, help="Input width (default: 288)")
    parser.add_argument("--num-classes", type=int, default=7, help="Semantic classes (default: 7)")
    parser.add_argument("--num-hazards", type=int, default=8, help="Hazard classes (default: 8)")
    parser.add_argument("--work-dir", type=str, default="", help="Directory for checkpoint and tflite artifacts")
    parser.add_argument("--require-gpu", action="store_true", help="Fail if no GPU device is detected")
    return parser.parse_args()


def build_smoke_model(height: int, width: int, num_classes: int, num_hazards: int, prefix: str = ""):
    """
    Constructs a lightweight representative portrait segmentation model
    matching the MobileNetV2 / U-Net dual-head architecture:
    - Input: [B, H, W, 3] FLOAT32 (0.0 to 1.0)
    - Head 1 (semantic): [B, H, W, num_classes] (logits / softmax)
    - Head 2 (hazard): [B, H, W, num_hazards] (sigmoid)
    """
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers

    inputs = keras.Input(shape=(height, width, 3), name=f"{prefix}image_input")

    # Lightweight encoder feature extractor with explicit names
    x = layers.Conv2D(16, (3, 3), strides=2, padding="same", activation="relu", name=f"{prefix}enc_conv1")(inputs)
    x = layers.DepthwiseConv2D((3, 3), padding="same", activation="relu", name=f"{prefix}enc_dw1")(x)
    x = layers.Conv2D(32, (1, 1), padding="same", activation="relu", name=f"{prefix}enc_proj1")(x)

    x2 = layers.Conv2D(32, (3, 3), strides=2, padding="same", activation="relu", name=f"{prefix}enc_conv2")(x)
    x2 = layers.DepthwiseConv2D((3, 3), padding="same", activation="relu", name=f"{prefix}enc_dw2")(x2)
    x2 = layers.Conv2D(64, (1, 1), padding="same", activation="relu", name=f"{prefix}enc_proj2")(x2)

    # Lightweight decoder with explicit names
    d = layers.UpSampling2D(size=(2, 2), interpolation="bilinear", name=f"{prefix}dec_up1")(x2)
    d = layers.Concatenate(name=f"{prefix}dec_concat")([d, x])
    d = layers.Conv2D(32, (3, 3), padding="same", activation="relu", name=f"{prefix}dec_conv1")(d)

    d = layers.UpSampling2D(size=(2, 2), interpolation="bilinear", name=f"{prefix}dec_up2")(d)
    d = layers.Conv2D(16, (3, 3), padding="same", activation="relu", name=f"{prefix}dec_conv2")(d)

    # Dual heads
    semantic_out = layers.Conv2D(num_classes, (1, 1), padding="same", name=f"{prefix}semantic_output")(d)
    hazard_out = layers.Conv2D(num_hazards, (1, 1), padding="same", activation="sigmoid", name=f"{prefix}hazard_output")(d)

    model = keras.Model(inputs=inputs, outputs={"semantic": semantic_out, "hazard": hazard_out}, name=f"{prefix}portrait_smoke_model")
    return model


def main():
    _ensure_wsl_cuda_env()
    args = parse_args()

    print(f"=== Aria Portrait Training Environment Smoke Test ===")
    print(f"Timestamp: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}")
    print(f"Python executable: {sys.executable}")
    print(f"Python version: {sys.version.split()[0]}")

    import tensorflow as tf
    print(f"TensorFlow version: {tf.__version__}")
    print(f"Keras version: {tf.keras.__version__ if hasattr(tf.keras, '__version__') else 'integrated'}")

    gpus = tf.config.list_physical_devices("GPU")
    cpus = tf.config.list_physical_devices("CPU")
    print(f"Physical GPUs detected: {len(gpus)} -> {gpus}")
    print(f"Physical CPUs detected: {len(cpus)} -> {cpus}")

    if args.require_gpu and len(gpus) == 0:
        print("ERROR: --require-gpu specified but 0 GPUs detected by TensorFlow.")
        sys.exit(1)

    device_used = "GPU" if len(gpus) > 0 else "CPU"
    print(f"Active compute target: {device_used}")

    work_dir = args.work_dir
    if not work_dir:
        tmp = tempfile.mkdtemp(prefix="aria_tf_smoke_")
        work_dir = tmp
    os.makedirs(work_dir, exist_ok=True)
    print(f"Artifact directory: {work_dir}")

    # 1. Build Model
    print(f"\n[1/5] Building portrait model: shape=[B, {args.height}, {args.width}, 3]...")
    model = build_smoke_model(args.height, args.width, args.num_classes, args.num_hazards)
    optimizer = tf.keras.optimizers.Adam(learning_rate=1e-3)
    loss_fn_semantic = tf.keras.losses.CategoricalCrossentropy(from_logits=True)
    loss_fn_hazard = tf.keras.losses.BinaryCrossentropy()

    # 2. Run 100 Training Steps
    print(f"\n[2/5] Running {args.steps} training steps (batch_size={args.batch_size})...")
    start_time = time.time()
    for step in range(1, args.steps + 1):
        # Generate synthetic batch [B, H, W, 3] in [0, 1]
        x_batch = np.random.uniform(0.0, 1.0, size=(args.batch_size, args.height, args.width, 3)).astype(np.float32)
        # Synthetic one-hot semantic labels [B, H, W, 7]
        y_sem_idx = np.random.randint(0, args.num_classes, size=(args.batch_size, args.height, args.width))
        y_sem_batch = tf.one_hot(y_sem_idx, depth=args.num_classes)
        # Synthetic binary hazard labels [B, H, W, 8]
        y_haz_batch = np.random.randint(0, 2, size=(args.batch_size, args.height, args.width, args.num_hazards)).astype(np.float32)

        with tf.GradientTape() as tape:
            preds = model(x_batch, training=True)
            loss_sem = loss_fn_semantic(y_sem_batch, preds["semantic"])
            loss_haz = loss_fn_hazard(y_haz_batch, preds["hazard"])
            total_loss = loss_sem + 0.5 * loss_haz

        grads = tape.gradient(total_loss, model.trainable_variables)
        optimizer.apply_gradients(zip(grads, model.trainable_variables))

        if step == 1:
            sem_dev = preds["semantic"].device
            loss_dev = total_loss.device
            print(f"  Step 1 tensor placement: semantic={sem_dev}, total_loss={loss_dev}")
            if args.require_gpu and ("GPU:0" not in sem_dev or "GPU:0" not in loss_dev):
                print(f"ERROR: --require-gpu specified but tensors placed on {sem_dev} / {loss_dev}.")
                sys.exit(1)

        if step % 25 == 0 or step == 1 or step == args.steps:
            elapsed = time.time() - start_time
            rate = step / elapsed if elapsed > 0 else 0
            print(f"  Step {step:3d}/{args.steps} - Total Loss: {total_loss.numpy():.4f} "
                  f"(sem: {loss_sem.numpy():.4f}, haz: {loss_haz.numpy():.4f}) - {rate:.1f} steps/s")

    train_time = time.time() - start_time
    print(f"Completed {args.steps} steps in {train_time:.2f}s ({args.steps / train_time:.1f} steps/s on {device_used}).")

    # 3. Checkpoint Save & Restore
    print(f"\n[3/5] Testing Checkpoint Save and Restore...")
    ckpt_dir = os.path.join(work_dir, "checkpoint")
    os.makedirs(ckpt_dir, exist_ok=True)

    # Save standard Keras weights checkpoint
    weights_path = os.path.join(ckpt_dir, "model.weights.h5")
    model.save_weights(weights_path)
    print(f"Saved model weights to: {weights_path}")

    # Save TF Checkpoint containing step, optimizer iteration state, and model
    step_var = tf.Variable(args.steps, dtype=tf.int64)
    ckpt = tf.train.Checkpoint(step=step_var, optimizer=optimizer, model=model)
    save_path = ckpt.save(os.path.join(ckpt_dir, "ckpt"))
    print(f"Saved TF checkpoint to: {save_path}")

    # Build fresh model and fresh optimizer to test restore from cold state
    sample_input = np.random.uniform(0.0, 1.0, size=(1, args.height, args.width, 3)).astype(np.float32)
    fresh_model = build_smoke_model(args.height, args.width, args.num_classes, args.num_hazards)
    _ = fresh_model(sample_input, training=False)  # Initialize weights

    fresh_optimizer = tf.keras.optimizers.Adam(learning_rate=1e-3)
    fresh_optimizer.build(fresh_model.trainable_variables)

    fresh_step = tf.Variable(0, dtype=tf.int64)
    fresh_ckpt = tf.train.Checkpoint(step=fresh_step, optimizer=fresh_optimizer, model=fresh_model)
    fresh_ckpt.restore(save_path).expect_partial()

    print(f"Restored TF Checkpoint: step={int(fresh_step.numpy())}, optimizer_iterations={int(fresh_optimizer.iterations.numpy())}")
    assert int(fresh_step.numpy()) == args.steps, f"Restored step mismatch: {int(fresh_step.numpy())} vs {args.steps}"
    assert int(fresh_optimizer.iterations.numpy()) == args.steps, f"Restored optimizer iterations mismatch: {int(fresh_optimizer.iterations.numpy())} vs {args.steps}"

    # Verify weight parity between original and restored model
    orig_pred = model(sample_input, training=False)
    rest_pred = fresh_model(sample_input, training=False)
    np.testing.assert_allclose(orig_pred["semantic"].numpy(), rest_pred["semantic"].numpy(), rtol=1e-5, atol=1e-5)
    np.testing.assert_allclose(orig_pred["hazard"].numpy(), rest_pred["hazard"].numpy(), rtol=1e-5, atol=1e-5)
    print("Checkpoint weight and iteration parity verified: step/iterations match exactly, predictions match within 1e-5 tolerance.")

    # 4. TFLite Export
    print(f"\n[4/5] Exporting to TFLite [1, {args.height}, {args.width}, 3]...")
    # Wrap model for fixed single-batch inference export
    class ExportModel(tf.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        @tf.function(input_signature=[tf.TensorSpec(shape=[1, args.height, args.width, 3], dtype=tf.float32, name="image_input")])
        def __call__(self, x):
            outputs = self.model(x, training=False)
            # Softmax on semantic output
            sem_softmax = tf.nn.softmax(outputs["semantic"], axis=-1, name="semantic_softmax")
            return {"semantic": sem_softmax, "hazard": outputs["hazard"]}

    export_module = ExportModel(fresh_model)
    concrete_func = export_module.__call__.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete_func])
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model_bytes = converter.convert()

    tflite_path = os.path.join(work_dir, "portrait_smoke_model.tflite")
    with open(tflite_path, "wb") as f:
        f.write(tflite_model_bytes)
    tflite_size_kb = len(tflite_model_bytes) / 1024.0
    print(f"Exported TFLite model: {tflite_path} ({tflite_size_kb:.1f} KB)")

    # 5. TFLite Interpreter Validation
    print(f"\n[5/5] Validating TFLite Interpreter inference...")
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print(f"TFLite input tensor: shape={input_details[0]['shape']}, dtype={input_details[0]['dtype'].__name__}")
    assert list(input_details[0]["shape"]) == [1, args.height, args.width, 3], f"Unexpected input shape: {input_details[0]['shape']}"

    for out in output_details:
        print(f"TFLite output tensor '{out['name']}': shape={out['shape']}, dtype={out['dtype'].__name__}")

    # Run inference with sample input
    interpreter.set_tensor(input_details[0]["index"], sample_input)
    interpreter.invoke()

    for out in output_details:
        out_data = interpreter.get_tensor(out["index"])
        print(f"Output '{out['name']}' valid: min={out_data.min():.4f}, max={out_data.max():.4f}")

    print(f"\n=== SMOKE TEST RESULT: SUCCESS ===")
    print(f"Summary: {args.steps} training steps ({train_time:.2f}s), Checkpoint Save/Restore verified, TFLite Export ({tflite_size_kb:.1f} KB) verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
