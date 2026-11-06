# Finn DeepSystem — Final Benchmark Report

Consolidated measurements across Phases 1–5. Host numbers are Apple M-series (M4) bare metal; device numbers are the `finn_mid` Android 14 AVD (arm64-v8a, 6 vCPU / 6 GB RAM). All inference runs 100% on-device via the shared C++17 `finn_core` (llama.cpp b12130 + libmtmd).

## 1. Test verification

- **Host C++ core (`core_tests`):** 24/24 passed (277 ms) — FakeEngine, Storage, C ABI, ThinkingFilter, VAD, SentenceChunker, LiveSession.
- **Android JVM (`testDebugUnitTest`):** 8/8 passed (1.1 s).
- **Connected UI (`connectedDebugAndroidTest` on `finn_mid`):** 8/8 passed (39 s) including `EngineBenchmarkTest`.
- **Phase 4 Sandbox unit test (`SandboxUnitTest`):** wired and passing in `testDebugUnitTest assembleDebug`.

## 2. Text generation (Q4_K_M backbone)

| Metric | Host (M4 Metal) | `finn_mid` emulator | Notes |
|---|---|---|---|
| Model load | 220 ms | 1,485 ms | mmap of 1.02 GB GGUF |
| TTFT | 58–77 ms | 92.39 ms | ChatML template, 16 prompt tokens |
| Decode speed | 75.5 tok/s | 54.16 tok/s | NEON/dotprod, 4 threads |
| Total gen (29 tok) | ~349 ms | 609.36 ms | thinking tags stripped |

## 3. JNI batching fix (22 → 54 tok/s)

- **Root cause:** `LlamaEngine::generate` called `llama_batch_init`/`llama_batch_free` for every decoded token (per-token malloc/free).
- **Fix:** single reusable `llama_batch step_batch = llama_batch_init(1, 0, 1)` allocated before the loop, reused per decode step, freed once after.
- **Result:** on-device decode throughput rose from ~22 tok/s to ~54 tok/s (~2.5×).

## 4. Memory profile (active multi-turn)

- **Total PSS: ~2.71 GB** (Native heap ~1.56 GB for context/KV cache; `Other mmap` ~1.00 GB read-only weights).
- Dalvik heap only ~11.2 MB; zero swap (0 KB).
- Within the 6 GB `finn_mid` allocation (>3.2 GB headroom).

## 5. Multimodal / vision (Q4 backbone + Q4 mmproj)

- Image encode (Apollo 11 newspaper): 319 ms; batch decode 24 ms; 344 prompt tokens; TTFT 1,217.63 ms; 68.07 tok/s.
- Vision benchmark on device: 28.4 tok/s (`EngineBenchmarkTest.benchmarkVisionGeneration`).

## 6. Audio ASR (Q4 vs Q8 mmproj)

| Metric | Q4 mmproj | Q8 mmproj |
|---|---|---|
| Size | 278 MB | 483 MB (42.4% larger) |
| Load | 0.22 s | 0.24 s |
| TTFT | 272.69 ms | 235.24 ms |
| Decode | 9.16 tok/s | 10.68 tok/s |
| Accuracy | High | High |

## 7. TTS (speech output) RTF

| Metric | Q4 mmproj | Q8 mmproj |
|---|---|---|
| Host RTF | ~2.05× | ~2.19× |
| Emulator RTF | ~1.49× | — |
| Synthesis time (host) | 1.09 s | 0.95 s |
| Samples | 53,760 (2.24 s) | 49,920 (2.08 s) |

Recommendation: Q4_K_M mmproj (278 MB) for mobile/edge — best footprint vs >2.0× realtime on host / ~1.5× on emulator.

## 8. Live mode

| Metric | Host | `finn_mid` emulator |
|---|---|---|
| Turn end-to-end latency | 1,420 ms | 3,643 ms |
| Barge-in interruption | < 10 ms | < 50 ms (0–25 ms) |
| VAD onset | 40 ms | 40 ms |
| VAD silence hangover | 500 ms | 300–600 ms (configurable) |
| TTS synthesis | 950 ms | 1,395 ms |

Sentence-chunked streaming TTS begins audio on sentence 1 while later tokens keep generating.

## 9. Compose UI frame times (33 ms throttling)

- Before: per-token `StateFlow` emissions drove a recomposition on every token during streaming, causing animation jank and missed frame deadlines.
- After: token UI updates coalesced to ~30 Hz (`ChatViewModel.UI_UPDATE_INTERVAL_NS = 33 ms`); `LazyColumn` uses `scrollToItem` (snap) while streaming; final text always emitted on completion.
- Measured on `finn_mid` during active streaming: 50th 21 ms, 90th/95th/99th 32 ms, GPU 50th 16 ms / 90th 23 ms, 0 slow UI-thread pauses, 0 slow bitmap uploads, legacy missed-deadline 25.0% (emulator GL).

## 10. Physical device requirements (honest notes)

- **Microphone:** AVD has no physical acoustic mic, AGC, or AEC. Synthetic audio validates DSP/turn-taking; real noise rejection and echo cancellation need a handset.
- **Camera:** Camera2 virtual sensor yields checkerboard textures; autofocus/exposure/low-light ingestion need hardware camera validation.
- **Thermals/perf:** emulator runs on host CPU; sustained decode, thermal throttling, and battery impact must be measured on a physical device.
- **Hotword:** neither Android (reserved for privileged assistants) nor OpenHarmony grants always-on hotword to third-party apps; invocation is via assist gesture / in-app mic / global hotkey (Linux).
- **Security boundary:** proot on Android is convenience, not a VM; bubblewrap is stronger but still not a VM. True isolation requires Firecracker/QEMU.

## 11. Links

- [Host benchmarks](host.md)
- [Android emulator benchmarks](../android-emulator.md)
- [UI performance](ui_performance.md)
- [Phase 2/3 integration](phase2_3_integration.md)
- [Build and release](../build-and-release.md)
- [Android env](../android-env.md)
- [Android NDK toolchain](../android/ndk_toolchain.md)
- [Linux port](../linux-port.md)
- [OpenHarmony port](../openharmony-port.md)
- [Container sandbox](../container-sandbox.md)
- [Assistant & background](../assistant-and-background.md)
- [Notes & miniapps](../notes-and-miniapps.md)
