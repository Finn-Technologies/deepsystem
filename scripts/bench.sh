#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build"
CLI="${BUILD_DIR}/finn_cli"

MODEL_Q4="${ROOT_DIR}/models/LFM3-Omni-2B-Q4_K_M.gguf"
MMPROJ_Q4="${ROOT_DIR}/models/mmproj-LFM3-Omni-2B-Q4_K_M.gguf"
MMPROJ_Q8="${ROOT_DIR}/models/mmproj-LFM3-Omni-2B-Q8_0.gguf"

TEST_IMAGE="${ROOT_DIR}/test_assets/test_image.png"
TEST_AUDIO="${ROOT_DIR}/test_assets/test_audio.mp3"

echo "=========================================================="
echo "         Finn DeepSystem Host Benchmark Suite             "
echo "=========================================================="

if [ ! -f "${CLI}" ]; then
    echo "Building finn_cli and tests..."
    cmake -B "${BUILD_DIR}" -G Ninja
    ninja -C "${BUILD_DIR}"
fi

echo ""
echo "--- Running Unit Tests ---"
"${BUILD_DIR}/core_tests"

echo ""
echo "--- 1. Text Generation Benchmark (Q4 Backbone) ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    -p "Explain quantum computing in one short sentence." \
    --max-tokens 64 \
    --temp 0.0 \
    --benchmark

echo ""
echo "--- 2. Image Multimodal Benchmark (Q4 Backbone + Q4 mmproj) ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    --mmproj "${MMPROJ_Q4}" \
    --image "${TEST_IMAGE}" \
    -p "Describe what is in this image briefly." \
    --max-tokens 64 \
    --temp 0.0 \
    --benchmark

echo ""
echo "--- 3. Audio ASR Benchmark: Q4 mmproj ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    --mmproj "${MMPROJ_Q4}" \
    --audio "${TEST_AUDIO}" \
    -p "Transcribe the speech in this audio." \
    --max-tokens 32 \
    --temp 0.0 \
    --benchmark

echo ""
echo "--- 4. Audio ASR Benchmark: Q8 mmproj ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    --mmproj "${MMPROJ_Q8}" \
    --audio "${TEST_AUDIO}" \
    -p "Transcribe the speech in this audio." \
    --max-tokens 32 \
    --temp 0.0 \
    --benchmark

echo ""
echo "--- 5. Speech Output (TTS) Benchmark: Q4 mmproj ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    --mmproj "${MMPROJ_Q4}" \
    --tts "Hello from Finn DeepSystem. Local AI assistant is ready." \
    -o "${ROOT_DIR}/test_assets/speech_bench_q4.wav"

echo ""
echo "--- 6. Speech Output (TTS) Benchmark: Q8 mmproj ---"
"${CLI}" \
    --model "${MODEL_Q4}" \
    --mmproj "${MMPROJ_Q8}" \
    --tts "Hello from Finn DeepSystem. Local AI assistant is ready." \
    -o "${ROOT_DIR}/test_assets/speech_bench_q8.wav"

echo ""
echo "=========================================================="
echo "Benchmark suite completed successfully."
echo "=========================================================="
