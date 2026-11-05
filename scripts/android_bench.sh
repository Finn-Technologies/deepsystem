#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_SH="${ROOT_DIR}/scripts/env.sh"

if [ -f "${ENV_SH}" ]; then
    source "${ENV_SH}"
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

echo "=========================================================="
echo "      Finn DeepSystem Android Emulator Benchmark Suite    "
echo "=========================================================="

# 1. Verify / wait for Android device or emulator
echo ""
echo "--- 1. Checking Android Device / Emulator ---"
DEVICE=$(adb devices | grep -E "emulator-[0-9]+|device$" | head -n 1 | awk '{print $1}' || true)

if [ -z "${DEVICE}" ]; then
    echo "No running emulator found. Attempting to boot 'finn_mid'..."
    emulator -avd finn_mid -no-window -no-audio -no-snapshot-save -no-boot-anim &
    EMU_PID=$!
    echo "Waiting for emulator (PID: ${EMU_PID}) to boot..."
    adb wait-for-device
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
    done
    echo "Emulator boot completed."
else
    echo "Connected device: ${DEVICE}"
fi

# 2. Push model weights to device if not already present
echo ""
echo "--- 2. Checking On-Device Model Files ---"
MODEL_FILE="${ROOT_DIR}/models/LFM3-Omni-2B-Q4_K_M.gguf"
MMPROJ_FILE="${ROOT_DIR}/models/mmproj-LFM3-Omni-2B-Q4_K_M.gguf"
TEST_IMG="${ROOT_DIR}/test_assets/test_image.png"

if ! adb shell "[ -f /data/local/tmp/LFM3-Omni-2B-Q4_K_M.gguf ]"; then
    echo "Pushing LFM3-Omni-2B-Q4_K_M.gguf to /data/local/tmp/..."
    adb push "${MODEL_FILE}" /data/local/tmp/
fi

if ! adb shell "[ -f /data/local/tmp/mmproj-LFM3-Omni-2B-Q4_K_M.gguf ]"; then
    echo "Pushing mmproj-LFM3-Omni-2B-Q4_K_M.gguf to /data/local/tmp/..."
    adb push "${MMPROJ_FILE}" /data/local/tmp/
fi

if ! adb shell "[ -f /data/local/tmp/test_image.png ]"; then
    adb push "${TEST_IMG}" /data/local/tmp/
fi

adb shell "chmod 644 /data/local/tmp/*.gguf /data/local/tmp/*.png" || true
echo "Model files present in /data/local/tmp."

# 3. Build & Run Unit Tests
echo ""
echo "--- 3. Running Android Unit Tests & Assembling APK ---"
cd "${ROOT_DIR}/android"
./gradlew assembleDebug testDebugUnitTest

# 4. Install App on Device
echo ""
echo "--- 4. Installing APK to Emulator ---"
adb install -r "${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk"

# Ensure models are copied into internal files dir
adb shell "run-as app.finn.deepsystem mkdir -p /data/user/0/app.finn.deepsystem/files/models" || true
adb shell "run-as app.finn.deepsystem cp /data/local/tmp/*.gguf /data/user/0/app.finn.deepsystem/files/models/" || true

# 5. Run Compose UI Test
echo ""
echo "--- 5. Running Compose UI Instrumented Test ---"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finn.deepsystem.ChatScreenTest

# 6. Run Real Native LlamaEngine Benchmark
echo ""
echo "--- 6. Running Real Engine Benchmark on Android Device ---"
adb logcat -c
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.finn.deepsystem.EngineBenchmarkTest#benchmarkTextGeneration

echo ""
echo "--- 7. Benchmark Results & Memory Analysis ---"
adb logcat -d | grep "FINN_BENCH" || true

# Measure Memory Usage
adb shell am start -n app.finn.deepsystem/.MainActivity >/dev/null 2>&1 || true
sleep 3
MEMINFO=$(adb shell dumpsys meminfo app.finn.deepsystem)
echo ""
echo "${MEMINFO}" | grep -E "TOTAL PSS:|Native Heap|Dalvik Heap|Other mmap" || true

# Take Screenshots
mkdir -p "${ROOT_DIR}/docs/screenshots"
adb shell screencap -p /sdcard/bench_screenshot.png
adb pull /sdcard/bench_screenshot.png "${ROOT_DIR}/docs/screenshots/chat_screen_benchmark.png" >/dev/null 2>&1 || true

echo ""
echo "=========================================================="
echo "Android emulator benchmark completed successfully."
echo "=========================================================="
