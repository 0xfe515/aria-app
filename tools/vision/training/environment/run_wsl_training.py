#!/usr/bin/env python3
"""Process-local WSL2 CUDA entrypoint for the Aria staged trainer.

Run it with the guest-native GPU interpreter (it must be a Linux Python whose
site-packages contain the pip ``nvidia/*`` CUDA/cuDNN wheels):

    /home/user/.venvs/aria_wsl/bin/python \\
        tools/vision/training/environment/run_wsl_training.py [runner options] -- \\
        --manifest MANIFEST --stage city_warmup --device gpu --output-dir DIR ...

Everything after ``--`` is forwarded unchanged to
``python -m tools.vision.training.train`` (same arguments, same ``--device``
semantics). The runner only prepares the *current process*: it discovers the
interpreter's ``nvidia/*/lib`` and ``nvidia/*/bin`` directories plus
``/usr/lib/wsl/lib`` (the WSL ``libcuda.so`` shim), prepends them to
``LD_LIBRARY_PATH``/``PATH`` and re-executes itself once, because the dynamic
linker reads ``LD_LIBRARY_PATH`` only at process start. This happens before
TensorFlow is imported, so the trainer's own device selection
(``train.configure_device``) still runs before any TensorFlow runtime
initialization. Nothing is written to shell profiles, ``/etc``, ``.wslconfig``,
or the parent environment; child processes inherit the variables, nothing else
does.

Runner options (before ``--``):
    --module MODULE   Module to run as ``__main__`` (default
                      ``tools.vision.training.train``).
    --deterministic   Also set TF_DETERMINISTIC_OPS=1 and TF_CUDNN_DETERMINISTIC=1
                      for this process (reproducibility checks).
    --allow-growth    Also set TF_FORCE_GPU_ALLOW_GROWTH=true so GPU memory use
                      reflects actual allocations (useful for peak-memory records).
    --print-env       Print the resolved process-local environment as JSON and
                      exit without importing TensorFlow.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import runpy
import site
import sys
import sysconfig
from pathlib import Path
from typing import Mapping

REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_MODULE = "tools.vision.training.train"
WSL_LIB_DIR = "/usr/lib/wsl/lib"
SENTINEL = "ARIA_WSL_CUDA_ENV_APPLIED"


def _site_package_dirs() -> list[str]:
    dirs: list[str] = []
    candidates = list(site.getsitepackages())
    for key in ("purelib", "platlib"):
        value = sysconfig.get_paths().get(key)
        if value:
            candidates.append(value)
    for candidate in candidates:
        if candidate and candidate not in dirs and os.path.isdir(candidate):
            dirs.append(candidate)
    return dirs


def discover_nvidia_dirs(site_dirs: list[str] | None = None) -> tuple[list[str], list[str]]:
    """Returns (lib_dirs, bin_dirs) of the interpreter's pip ``nvidia`` packages."""
    lib_dirs: list[str] = []
    bin_dirs: list[str] = []
    for site_dir in site_dirs if site_dirs is not None else _site_package_dirs():
        for pattern, target in (("lib", lib_dirs), ("bin", bin_dirs)):
            for found in sorted(glob.glob(os.path.join(site_dir, "nvidia", "*", pattern))):
                if os.path.isdir(found) and found not in target:
                    target.append(found)
    return lib_dirs, bin_dirs


def _prepend(new: list[str], current: str) -> str:
    existing = [part for part in current.split(":") if part]
    merged: list[str] = []
    for part in new + existing:
        if part not in merged:
            merged.append(part)
    return ":".join(merged)


def build_process_env(
    base_env: Mapping[str, str],
    *,
    site_dirs: list[str] | None = None,
    wsl_lib_dir: str | None = WSL_LIB_DIR,
    deterministic: bool = False,
    allow_growth: bool = False,
) -> dict[str, str]:
    """Returns a copy of ``base_env`` extended for this process only (pure function)."""
    lib_dirs, bin_dirs = discover_nvidia_dirs(site_dirs)
    if wsl_lib_dir and os.path.isdir(wsl_lib_dir):
        lib_dirs = [wsl_lib_dir] + lib_dirs
        bin_dirs = bin_dirs + [wsl_lib_dir]
    env = dict(base_env)
    env["LD_LIBRARY_PATH"] = _prepend(lib_dirs, env.get("LD_LIBRARY_PATH", ""))
    env["PATH"] = _prepend(bin_dirs, env.get("PATH", ""))
    if deterministic:
        env["TF_DETERMINISTIC_OPS"] = "1"
        env["TF_CUDNN_DETERMINISTIC"] = "1"
    if allow_growth:
        env["TF_FORCE_GPU_ALLOW_GROWTH"] = "true"
    return env


def ensure_process_cuda_env(*, deterministic: bool = False, allow_growth: bool = False) -> None:
    """Applies the CUDA environment to this process, re-executing once if needed.

    Must be called before TensorFlow (or anything importing it) is imported.
    """
    if os.name != "posix":
        raise SystemExit("run_wsl_training.py must run inside the WSL2 Linux guest, not on Windows.")
    if os.environ.get(SENTINEL) == "1":
        return
    if "tensorflow" in sys.modules:
        raise SystemExit("ensure_process_cuda_env() must run before TensorFlow is imported.")
    env = build_process_env(os.environ, deterministic=deterministic, allow_growth=allow_growth)
    env[SENTINEL] = "1"
    sys.stdout.flush()
    sys.stderr.flush()
    os.execve(sys.executable, [sys.executable] + sys.argv, env)


def _split_arguments(argv: list[str]) -> tuple[list[str], list[str]]:
    if "--" in argv:
        index = argv.index("--")
        return argv[:index], argv[index + 1:]
    return argv, []


def _parse_runner_arguments(runner_argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Process-local WSL2 CUDA entrypoint. Arguments after '--' are forwarded unchanged "
            "to the target module (default: python -m tools.vision.training.train)."
        ),
        epilog="Example: run_wsl_training.py --allow-growth -- --manifest M --stage city_warmup --device gpu",
    )
    parser.add_argument("--module", default=DEFAULT_MODULE, help=f"Module to run as __main__ (default {DEFAULT_MODULE}).")
    parser.add_argument("--deterministic", action="store_true", help="Set TF_DETERMINISTIC_OPS/TF_CUDNN_DETERMINISTIC for this process.")
    parser.add_argument("--allow-growth", action="store_true", help="Set TF_FORCE_GPU_ALLOW_GROWTH=true for this process.")
    parser.add_argument("--print-env", action="store_true", help="Print the resolved environment as JSON and exit (no TensorFlow import).")
    return parser.parse_args(runner_argv)


def main(argv: list[str] | None = None) -> int:
    full_argv = list(sys.argv[1:] if argv is None else argv)
    runner_argv, module_argv = _split_arguments(full_argv)
    args = _parse_runner_arguments(runner_argv)

    if args.print_env:
        if os.name != "posix":
            print("run_wsl_training.py must run inside the WSL2 Linux guest.", file=sys.stderr)
            return 2
        lib_dirs, bin_dirs = discover_nvidia_dirs()
        env = build_process_env(os.environ, deterministic=args.deterministic, allow_growth=args.allow_growth)
        print(json.dumps({
            "python": sys.executable,
            "repo_root": str(REPO_ROOT),
            "nvidia_lib_dirs": lib_dirs,
            "nvidia_bin_dirs": bin_dirs,
            "wsl_lib_dir_present": os.path.isdir(WSL_LIB_DIR),
            "LD_LIBRARY_PATH": env["LD_LIBRARY_PATH"],
            "PATH_head": env["PATH"].split(":")[: len(bin_dirs) + 1],
            "TF_DETERMINISTIC_OPS": env.get("TF_DETERMINISTIC_OPS"),
            "TF_CUDNN_DETERMINISTIC": env.get("TF_CUDNN_DETERMINISTIC"),
            "TF_FORCE_GPU_ALLOW_GROWTH": env.get("TF_FORCE_GPU_ALLOW_GROWTH"),
            "applies_to": "this process and its children only",
        }, indent=2))
        return 0

    ensure_process_cuda_env(deterministic=args.deterministic, allow_growth=args.allow_growth)

    if str(REPO_ROOT) not in sys.path:
        sys.path.insert(0, str(REPO_ROOT))
    sys.argv = [f"python -m {args.module}"] + module_argv
    runpy.run_module(args.module, run_name="__main__", alter_sys=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
