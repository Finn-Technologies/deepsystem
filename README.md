# Finn DeepSystem

Fully local, privacy-first multimodal AI assistant (Android, OpenHarmony, Linux). Base model: LFM3-Omni-2B (Q4) via llama.cpp. Pure C++17 shared core, native UI per platform.

## Architecture
- **Core Library (`finn_core`):** Pure C++17 library encapsulating `InferenceEngine` interface, `LlamaEngine` implementation with multimodal support via `libmtmd`, SQLite conversation storage, and a stable extern "C" ABI (`finn_c.h`).
- **Engines:**
  - `LlamaEngine`: Production engine backed by pinned llama.cpp (`b12130`) + `libmtmd` for multimodal vision/audio input and TTS generation.
  - `FakeEngine`: Deterministic mock engine for weightless unit tests and CI.
- **Multimodal Capabilities:** Text generation, image understanding, audio ASR speech-to-text, and real-time speech output (TTS).
- **Storage:** SQLite-backed persistence for conversations and messages with JSON export.
- **C ABI (`finn_c.h`):** Stable ABI designed for JNI (Android), NAPI (Node/Desktop), and GTK (Linux).

## Quick Start (macOS Host)

### 1. Fetch Dependencies & Models
```bash
# Clone llama.cpp at pinned tag b12130
./scripts/fetch_llama.sh b12130

# Download LFM3-Omni-2B weights & multimodal projectors
./scripts/fetch_models.sh
```

### 2. Build Core Library & CLI
```bash
cmake -B build -G Ninja
ninja -C build
```

### 3. Run Unit Tests
```bash
ctest --test-dir build --output-on-failure
# Or directly:
./build/core_tests
```

### 4. Run CLI Tool
```bash
# Text generation
./build/finn_cli -p "Explain quantum computing in one short sentence."

# Image understanding
./build/finn_cli --image test_assets/test_image.png -p "Describe this image."

# Audio speech recognition (ASR)
./build/finn_cli --audio test_assets/test_audio.mp3 -p "Transcribe the speech in this audio."

# Speech synthesis (TTS)
./build/finn_cli --tts "Hello from Finn DeepSystem." -o speech.wav
```

### 5. Repeatable Benchmarks
Run the full benchmark suite across text, vision, audio ASR, and TTS:
```bash
./scripts/bench.sh
```

Detailed performance numbers and cross-compilation guides:
- [Host Benchmarks](docs/benchmarks/host.md)
- [Android NDK Toolchain Guide](docs/android/ndk_toolchain.md)
