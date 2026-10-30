#!/usr/bin/env python3
"""
Reproducible WSL2 GPU & AVX/XSAVE verification script for Aria Portrait Training.
Verifies Linux kernel version, CPU flags (avx, avx2, xsave), TensorFlow 2.19.1 import,
CUDA/cuDNN build info, physical GPU discovery, and actual GPU:0 tensor/op execution
(matmul and conv2d + autodiff gradient).
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import platform
import site
import sys
import time


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


def _read_cpu_flags() -> list[str]:
    if not os.path.isfile("/proc/cpuinfo"):
        return []
    with open("/proc/cpuinfo", "r", encoding="utf-8") as f:
        for line in f:
            if line.lower().startswith("flags"):
                parts = line.split(":", 1)
                if len(parts) == 2:
                    return sorted(set(parts[1].strip().split()))
    return []


def main() -> int:
    _ensure_wsl_cuda_env()

    parser = argparse.ArgumentParser(description="Verify WSL2 AVX/XSAVE and TensorFlow GPU execution.")
    parser.add_argument("--output-json", type=str, default="", help="Optional path to write JSON verification report.")
    args = parser.parse_args()

    uname_info = platform.uname()
    cpu_flags = _read_cpu_flags()
    required_flags = ["avx", "avx2", "xsave"]
    missing_flags = [fl for fl in required_flags if fl not in cpu_flags]

    if missing_flags:
        print(f"ERROR: Missing required CPU flags: {missing_flags}", file=sys.stderr)
        return 1

    import tensorflow as tf

    build_info = tf.sysconfig.get_build_info()
    gpus = tf.config.list_physical_devices("GPU")
    cpus = tf.config.list_physical_devices("CPU")

    gpu_details = []
    for gpu in gpus:
        details = tf.config.experimental.get_device_details(gpu)
        gpu_details.append({
            "name": gpu.name,
            "device_type": gpu.device_type,
            "details": {k: str(v) for k, v in details.items()},
        })

    if not gpus:
        print("ERROR: TensorFlow detected 0 physical GPUs.", file=sys.stderr)
        return 1

    with tf.device("/GPU:0"):
        a = tf.constant([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]], dtype=tf.float32)
        b = tf.constant([[7.0, 8.0], [9.0, 10.0], [11.0, 12.0]], dtype=tf.float32)
        matmul_out = tf.matmul(a, b)

        x = tf.ones([2, 64, 64, 3], dtype=tf.float32)
        w = tf.ones([3, 3, 3, 8], dtype=tf.float32)
        with tf.GradientTape() as tape:
            tape.watch(w)
            conv_out = tf.nn.conv2d(x, w, strides=1, padding="SAME")
            loss = tf.reduce_mean(tf.square(conv_out))
        grad_w = tape.gradient(loss, w)

    matmul_device = matmul_out.device
    conv_device = conv_out.device
    grad_device = grad_w.device

    if "GPU:0" not in matmul_device or "GPU:0" not in conv_device or "GPU:0" not in grad_device:
        print(
            f"ERROR: Expected ops on GPU:0, got matmul={matmul_device}, conv={conv_device}, grad={grad_device}",
            file=sys.stderr,
        )
        return 1

    report = {
        "timestamp_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "python_executable": sys.executable,
        "python_version": sys.version.split()[0],
        "kernel_release": uname_info.release,
        "kernel_version": uname_info.version,
        "cpu_flags_present": {fl: (fl in cpu_flags) for fl in ["avx", "avx2", "fma", "xsave", "xsaveopt", "xsavec"]},
        "tensorflow_version": tf.__version__,
        "keras_version": getattr(tf.keras, "__version__", "unknown"),
        "is_built_with_cuda": bool(tf.test.is_built_with_cuda()),
        "cuda_build_version": str(build_info.get("cuda_version", "")),
        "cudnn_build_version": str(build_info.get("cudnn_version", "")),
        "physical_gpu_count": len(gpus),
        "physical_gpus": gpu_details,
        "physical_cpu_count": len(cpus),
        "gpu_op_verification": {
            "matmul_device": matmul_device,
            "matmul_result": matmul_out.numpy().tolist(),
            "conv2d_device": conv_device,
            "conv2d_shape": list(conv_out.shape),
            "grad_device": grad_device,
            "grad_norm": float(tf.linalg.global_norm([grad_w]).numpy()),
        },
        "status": "PASS",
    }

    print(json.dumps(report, indent=2))
    if args.output_json:
        os.makedirs(os.path.dirname(os.path.abspath(args.output_json)), exist_ok=True)
        with open(args.output_json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(report, f, indent=2)
            f.write("\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
