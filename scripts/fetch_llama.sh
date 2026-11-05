#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LLAMA_DIR="${ROOT_DIR}/third_party/llama.cpp"
PINNED_TAG="${1:-b12130}"

echo "Fetching llama.cpp at pinned tag: ${PINNED_TAG}..."

mkdir -p "${ROOT_DIR}/third_party"

if [ ! -d "${LLAMA_DIR}/.git" ]; then
    echo "Cloning llama.cpp..."
    git clone --depth 1 --branch "${PINNED_TAG}" https://github.com/ggml-org/llama.cpp.git "${LLAMA_DIR}"
else
    echo "Updating existing llama.cpp repository..."
    cd "${LLAMA_DIR}"
    git fetch --depth 1 origin tag "${PINNED_TAG}"
    git checkout "${PINNED_TAG}"
fi

echo "llama.cpp successfully fetched at $(cd "${LLAMA_DIR}" && git rev-parse HEAD) (tag: ${PINNED_TAG})"
