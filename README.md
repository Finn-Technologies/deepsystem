# Finn DeepSystem

Fully local, privacy-first multimodal AI assistant for **Android**, **OpenHarmony**, and **Linux**. Base model: **LiquidAI LFM3-Omni-2B (Q4_K_M)** served through a pure C++17 shared core (`finn_core`) on top of pinned llama.cpp (`b12130`) + `libmtmd`. All inference, vision, audio ASR, and TTS run 100% on-device; the network is used only for the one-time model download.

## LFM3-Omni-2B research findings

- **Backbone:** `LFM3-Omni-2B-Q4_K_M.gguf` (~1.02–1.05 GB). Q4_K_M gives the best size/quality tradeoff for mobile.
- **Multimodal projectors:** `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` (278 MB) vs `mmproj-LFM3-Omni-2B-Q8_0.gguf` (483 MB, 42.4% larger). Q4 loads in 0.22 s vs 0.24 s; Q8 is ~37 ms faster on TTFT and slightly cleaner TTS timbre. Q4 recommended for edge.
- **Capabilities:** text generation, image understanding (vision encoder), audio ASR speech-to-text, and native speech output (TTS) via `mtmd_helper::gen_audio` + the LFM3 vocoder embedded in mmproj. No third-party TTS vendor.
- **TTS template:** LFM3 requires ChatML TTS prompt `Perform TTS in American English using a {voice} voice.` and outputs 24 kHz mono 16-bit PCM WAV.
- **Thinking tags:** model emits `<think></think>` even when reasoning inactive; suppressed in core by `ThinkingFilter`.
- **ChatML template:** multi-turn requires role tags (`system`/`user`/`assistant`); raw completion mode caused loop degeneration.

## Architecture

- **Core Library (`finn_core`):** pure C++17. `InferenceEngine` interface, `LlamaEngine` (llama.cpp + libmtmd multimodal + TTS), `FakeEngine` (deterministic, weightless), SQLite storage, `ThinkingFilter`, VAD, `SentenceChunker`, `LiveSession`, `AgentToolDispatcher`.
- **C ABI (`finn_c.h` / `finn_c_api.h`):** stable opaque-handle ABI consumed by JNI (Android), NAPI (OpenHarmony), and GTK (Linux).
- **Android:** Kotlin + Jetpack Compose, JNI bridge to `finn_core`; Room/SQLite; WorkManager downloads; CameraX; Glance widgets.
- **OpenHarmony:** ArkTS + ArkUI (Stage model, API 12+), NAPI bridge `libfinn_napi.so`.
- **Linux:** GTK4 + libadwaita, WebKitGTK sandbox, bubblewrap container runner, D-Bus desktop assistant.

## Feature breakdown (Phases 1–5)

- **Phase 1 — Core & Android chat:** model loading, streaming text generation, image understanding, SQLite conversations, ChatML multi-turn fix, thinking-tag suppression, NDK optimization (-O3, dotprod/fp16) 1.69 → 54.2 tok/s.
- **Phase 2 — Multimodal & Live:** audio ASR, embedded TTS, video frame sampling + soundtrack extraction, Live turn-taking state machine, sentence-chunked streaming TTS, barge-in <50 ms, VAD, CameraX PIP, onboarding/network disclosure, WorkManager resumable download.
- **Phase 3 — Notes, widgets, miniapps, assistant & background:** SQLite FTS5 notes, Glance AppWidgets, sandboxed WebView miniapps with CSP/bridge, VoiceInteractionService assistant, foreground services, task scheduling, privacy ledger, adaptive layouts, accessibility.
- **Phase 4 — Sandbox terminal & agent tools:** `AgentToolDispatcher` (bash_exec, python_run, file_read/write, web_fetch), permission gates, scoped workspace, proot/bubblewrap, `SandboxTerminalSheet`, `SandboxUnitTest`.
- **Phase 5 — Linux & OpenHarmony ports + finalization:** native Linux GTK4/libadwaita app with bubblewrap + D-Bus tray, OpenHarmony ArkTS/NAPI port, final benchmark report, README.

## Setup & build

### 1. Fetch dependencies & models
```bash
./scripts/fetch_llama.sh b12130
./scripts/fetch_models.sh
```

### 2. Core library & tests (macOS/Linux host)
```bash
cmake -B build -G Ninja
ninja -C build
ctest --test-dir build --output-on-failure
```

### 3. CLI
```bash
./build/finn_cli -p "Explain quantum computing in one short sentence."
./build/finn_cli --image test_assets/test_image.png -p "Describe this image."
./build/finn_cli --tts "Hello from Finn DeepSystem." -o speech.wav
```

### 4. Android
```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
AVD: `finn_mid` (Android 14, arm64-v8a). Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### 5. Linux
```bash
cmake -S linux -B build-linux -G Ninja
ninja -C build-linux
ctest --test-dir build-linux --output-on-failure
```

### 6. OpenHarmony
Open `openharmony/` in DevEco Studio or run `hvigorw assembleHap`.

### 7. Benchmarks
```bash
./scripts/bench.sh
```

## Documentation index

- [Final benchmark report](docs/benchmarks/final_benchmark_report.md)
- [Host benchmarks](docs/benchmarks/host.md)
- [Android emulator benchmarks](docs/benchmarks/android-emulator.md)
- [UI performance](docs/benchmarks/ui_performance.md)
- [Phase 2/3 integration](docs/benchmarks/phase2_3_integration.md)
- [Build and release](docs/build-and-release.md)
- [Android environment](docs/android-env.md)
- [Android NDK toolchain](docs/android/ndk_toolchain.md)
- [Linux port](docs/linux-port.md)
- [OpenHarmony port](docs/openharmony-port.md)
- [Container sandbox & agent tools](docs/container-sandbox.md)
- [Assistant & background](docs/assistant-and-background.md)
- [Notes, widgets & miniapps](docs/notes-and-miniapps.md)

## Privacy

No telemetry or crash reporters. `ModelDownloader` is the only network path; every request is recorded in `NetworkLedger` and surfaced in Settings > Privacy. Enforced by `PrivacyNoNetworkTest`.
