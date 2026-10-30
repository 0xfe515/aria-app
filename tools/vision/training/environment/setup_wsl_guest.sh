#!/usr/bin/env bash
# ==============================================================================
# Aria Vision: WSL2 Ubuntu 22.04 Guest Environment Setup Script
#
# Purpose:
#   Installs Python 3.11, sets up an isolated virtual environment under
#   build/vision-training/environment/venv_wsl, installs TensorFlow 2.19 with
#   locked CUDA GPU acceleration, and verifies training smoke test with --require-gpu.
#
# Usage inside WSL2:
#   cd /mnt/c/Users/felice/orca/aria-app
#   bash tools/vision/training/environment/setup_wsl_guest.sh
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

echo "=== Aria Vision Model: WSL2 Ubuntu 22.04 Guest Setup ==="
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Repo root: ${REPO_ROOT}"
echo "Host kernel: $(uname -r)"

# 1. Verify NVIDIA GPU passthrough
echo -e "\n[1/5] Checking NVIDIA GPU passthrough in WSL2..."
if command -v nvidia-smi &>/dev/null; then
    nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
    echo "[OK] NVIDIA GPU detected via WSL2 passthrough."
else
    echo "ERROR: nvidia-smi not found. Verify NVIDIA Windows host driver 617.42+ is installed and WSL2 GPU passthrough is active."
    exit 1
fi

# 2. Install Python 3.11 and system dependencies
echo -e "\n[2/5] Installing Python 3.11 and system dependencies..."
sudo apt-get update -y
sudo apt-get install -y --no-install-recommends \
    software-properties-common \
    build-essential \
    curl \
    git

# Add deadsnakes PPA for Python 3.11 on Ubuntu 22.04 if python3.11 binary is absent
if ! command -v python3.11 &>/dev/null; then
    sudo add-apt-repository -y ppa:deadsnakes/ppa
    sudo apt-get update -y
    sudo apt-get install -y --no-install-recommends \
        python3.11 \
        python3.11-venv \
        python3.11-dev \
        python3.11-distutils
fi

echo "[OK] Python 3.11 version: $(python3.11 --version)"

# 3. Create isolated virtual environment under owned build directory
VENV_DIR="${REPO_ROOT}/build/vision-training/environment/venv_wsl"
echo -e "\n[3/5] Setting up isolated venv at ${VENV_DIR}..."
mkdir -p "$(dirname "${VENV_DIR}")"
if [ ! -d "${VENV_DIR}" ]; then
    python3.11 -m venv "${VENV_DIR}"
fi

source "${VENV_DIR}/bin/activate"
pip install --upgrade pip==24.0 setuptools wheel

# 4. Install TensorFlow 2.19 with locked dependencies
REQ_FILE="${SCRIPT_DIR}/requirements_wsl_gpu.txt"
echo -e "\n[4/5] Installing dependencies from ${REQ_FILE}..."
pip install -r "${REQ_FILE}"

# Configure LD_LIBRARY_PATH for pip-installed nvidia CUDA libraries if present
NVIDIA_SITE_PACKAGES="$(python -c 'import site; print(site.getsitepackages()[0])')/nvidia"
if [ -d "${NVIDIA_SITE_PACKAGES}" ]; then
    export LD_LIBRARY_PATH="${NVIDIA_SITE_PACKAGES}/cuda_runtime/lib:${NVIDIA_SITE_PACKAGES}/cudnn/lib:${LD_LIBRARY_PATH:-}"
fi

echo "[OK] Installed packages in venv:"
python -c "import tensorflow as tf; print('TensorFlow:', tf.__version__, 'Physical GPUs:', tf.config.list_physical_devices('GPU'))"

# 5. Run 100-step GPU smoke test with strict --require-gpu flag
WORK_DIR="${REPO_ROOT}/build/vision-training/environment/wsl_gpu_smoke"
echo -e "\n[5/5] Running 100-step GPU smoke test (strictly requiring GPU)..."

python "${SCRIPT_DIR}/gpu_smoke_test.py" \
    --steps 100 \
    --batch-size 2 \
    --require-gpu \
    --work-dir "${WORK_DIR}"

echo -e "\n=== WSL2 ENVIRONMENT SETUP COMPLETE ==="
echo "Virtual environment: ${VENV_DIR}"
echo "Activation command: source ${VENV_DIR}/bin/activate"
