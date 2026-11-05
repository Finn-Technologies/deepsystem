#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODELS_DIR="${ROOT_DIR}/models"

mkdir -p "${MODELS_DIR}"

BASE_URL="https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main"

FILES=(
    "LFM3-Omni-2B-Q4_K_M.gguf"
    "mmproj-LFM3-Omni-2B-Q4_K_M.gguf"
    "mmproj-LFM3-Omni-2B-Q8_0.gguf"
)

for file in "${FILES[@]}"; do
    echo "Downloading ${file} to ${MODELS_DIR}..."
    curl -L -C - --retry 5 --retry-delay 3 -o "${MODELS_DIR}/${file}" "${BASE_URL}/${file}"
done

echo "All models downloaded successfully."
