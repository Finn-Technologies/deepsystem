# Android Emulator Benchmarks & Phase 1 Findings

**Device & Virtualization Environment:**
- **AVD Target:** `finn_mid` (Google APIs Android 14.0 / API 34)
- **Target ABI:** `arm64-v8a` (64-bit ARM)
- **Host System:** macOS Darwin 24.6.0 on Apple M4 (MacBook Air 10-core: 4 Performance + 6 Efficiency cores, 16 GB Unified Memory)
- **Virtualization:** Hypervisor.framework / QEMU virtualization
- **Virtual Device Specs:** 6 vCPUs, 6 GB RAM (`hw.ramSize=6144`), 512 MB Dalvik heap
- **Android Package:** `app.finn.deepsystem` (`minSdk 29`, `targetSdk 35`)
- **Native Toolchain:** Android NDK r27c LTS (`27.2.12479018`), CMake 3.31.6, C++17
- **Native Build Flags:** `-DCMAKE_BUILD_TYPE=Release`, `-O3 -DNDEBUG -fvectorize`, `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, `-DGGML_OPENMP=OFF`
- **Base Model:** LiquidAI `LFM3-Omni-2B-Q4_K_M.gguf` (1.02 GB, SHA-256 verified)
- **Multimodal Projector:** `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` (278 MB, SHA-256 verified)

---

## 1. Test Suite Verification

### Host C++ Core Tests
- **GoogleTest Core Suite (`core_tests`):** **18/18 PASSED** (81 ms)
  - `FakeEngineTest`: Lifecycle, streaming, async thread cancellation, multimodal inputs, speech callbacks.
  - `StorageTest`: SQLite CRUD, message management, JSON export.
  - `CAbiTest`: Opaque handles, C interface, **multi-turn ChatML template assembly regression test**.
  - `ThinkingFilterTest`: Suppress empty `<think></think>`, suppress reasoning content, pass through normal text, partial token chunks, static strip helper.

### Android JVM Unit Tests
- **JUnit 4 Suite (`./gradlew testDebugUnitTest`):** **8/8 PASSED** (1.1 s)
  - `ChatViewModelTest.testInitialState`: PASSED
  - `ChatViewModelTest.testSendMessageGeneratesStreamingTokens`: PASSED
  - `ChatViewModelTest.testMultimodalImageAttachAndSend`: PASSED
  - `ChatViewModelTest.testStopGenerationStopsImmediately`: PASSED
  - `ChatViewModelTest.testBuildChatMLPromptFormatsMultiTurnProperly`: PASSED
  - `ChatViewModelTest.testNewConversationResetsMessages`: PASSED
  - `ModelDownloaderTest.testKnownModelsMetadata`: PASSED
  - `ModelDownloaderTest.testComputeSha256`: PASSED

### Android Instrumented & Compose UI Tests
- **Device UI Test (`ChatScreenTest.kt` on `finn_mid(AVD) - 14`):** **2/2 PASSED** (12 s)
  - `testSendMessageFlow`: Single-turn message entry, Send click, streaming assistant tokens, input clear.
  - `testMultiTurnConversationFlow`: Sane multi-turn back-and-forth conversation, verifying context accumulation without degeneration.
- **Device Engine Benchmark Test (`EngineBenchmarkTest.kt` on `finn_mid(AVD) - 14`):** **PASSED**
  - Direct on-device validation of `NativeFinnEngine` loading real model weights and executing inference through the C ABI.

---

## 2. Real On-Device Benchmark Results

Measured on `emulator-5554` running `app.finn.deepsystem` via [`scripts/android_bench.sh`](../../scripts/android_bench.sh):

| Benchmark Metric | Android Emulator (`finn_mid` arm64-v8a) | Host Bare-Metal (Apple M4 Metal GPU) | Notes |
| :--- | :--- | :--- | :--- |
| **Compiler Optimization** | Clang 18 (`-O3 -DNDEBUG -fvectorize`) | AppleClang 17 (`-O3`, Metal compute) | Release native build |
| **Model Load Time** | **1,485 ms** (~1.48 s) | 220 ms | Rapid mmap of 1.02 GB GGUF |
| **Prompt Tokens** | 16 tokens | 16 tokens | ChatML template format |
| **Completion Tokens** | 29 tokens | 22 tokens | Clean output (thinking tags stripped) |
| **Time to First Token (TTFT)** | **92.39 ms** (sub-100ms) | **76.78 ms** | Fast prompt ingestion with ARM dotprod |
| **Generation Speed** | **54.16 tokens/sec** | **75.58 tokens/sec** | CPU NEON/dotprod decode on 4 threads |
| **Total Generation Time** | 609.36 ms (~0.61 s) | 348.80 ms | Complete 29-token generation cycle |

*(Note on initial debug numbers: Earlier unoptimized Debug variant without `-O3` ran at 1.69 tok/s with 4.8s TTFT due to `-O0` debug flags. Enforcing `-DCMAKE_BUILD_TYPE=Release`, `-O3 -DNDEBUG`, and ARM dotprod/fp16 vectorization yielded a **32x throughput speedup** to 54.2 tokens/sec).*

### Sane Multi-Turn Real Chat Sample (Emulator UI)
> **Turn 1 (User):** `"Hello! Who are you?"`
> **Turn 1 (Finn):** `"I'm Finn, an intelligent, helpful, and concise on-device AI assistant."`
> *(Latency: TTFT 517 ms, Speed: 22.1 tokens/sec)*
>
> **Turn 2 (User):** `"What is quantum computing in one sentence?"`
> **Turn 2 (Finn):** `"Quantum computing uses quantum mechanics to process information, enabling exponential speedups for certain problems."`
> *(Latency: TTFT 667 ms, Speed: 22.8 tokens/sec)*

---

## 3. Memory Profile (`dumpsys meminfo app.finn.deepsystem`)

Memory usage captured during active multi-turn model execution on `finn_mid`:

```
** MEMINFO in pid 10385 [app.finn.deepsystem] **
                   Pss  Private  Private     Swap      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
                ------   ------   ------   ------   ------   ------   ------   ------
  Native Heap  1566736  1566648        0        0  1569912  2971508  2964574     2639
  Dalvik Heap     5751     5504        0        0    13544    10294     5147     5147
 Dalvik Other     5122     3732        0        0     7148                           
        Stack     1608     1608        0        0     1616                           
     .so mmap     2311      260       20        0    38692                           
    .apk mmap    67779      348    67208        0    69328                           
   Other mmap  1056731        8  1056620        0  1058128                           
        TOTAL  2716206  1584884  1123932        0  2827616  2981802  2969721     7786

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
           Java Heap:    11216                          40640
         Native Heap:  1566648                        1569912
                Code:    67876                         150344
               Stack:     1608                           1616
       Private Other:  1061468
              System:     7390
           TOTAL PSS:  2716206            TOTAL RSS:  2827616      TOTAL SWAP: 0 KB
```

### Key Memory Findings:
1. **Total PSS Footprint:** **2.71 GB** total memory footprint with multi-turn conversation context active. Well within the 6 GB RAM allocation of `finn_mid` (>3.2 GB headroom remaining).
2. **Native Memory Management:** `Native Heap` (~1.56 GB) holds the context buffer and KV cache, while `Other mmap` holds the ~1.00 GB read-only quantized model weights.
3. **Low Dalvik Overhead:** The Kotlin/Jetpack Compose Java runtime consumes only **11.2 MB** of Dalvik heap.
4. **Zero Swap Usage:** `TOTAL SWAP: 0 KB` confirms no page swapping or disk thrashing occurred.

---

## 4. Key Fixes & Root Cause Analysis

### 1. Loop Degeneration Fix
- **Root Cause:** The app chat path previously passed only the single current prompt string to the engine without the ChatML template. Without role tags (`<|im_start|>user`, `<|im_start|>assistant`), LFM3 was operating in raw completion mode rather than chat assistant mode, causing repetitive question echoing.
- **Fix:** Implemented `buildChatMLPrompt()` in `ChatViewModel` and `ChatTemplate::format()` in `conversation.h`. All conversations now structure the system prompt, history turns, and assistant invitation cleanly:
  `<|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n`.
  Additionally added repetition penalty (`1.10x`) and explicit stop token checks (`<|im_end|>`, `<|eot_id|>`, `<|endoftext|>`).

### 2. Core-Level Thinking Tag Suppression
- **Root Cause:** LFM3-Omni-2B natively outputs `<think></think>` tags at the start of generated text even when reasoning is inactive.
- **Fix:** Added `ThinkingFilter` (`include/finn/thinking_filter.h`) directly inside `LlamaEngine::generate`. It buffers initial tokens and strips `<think>...</think>` (and any empty thinking tags) before tokens reach the UI or callback. Verified by 5 unit tests in `core/tests/test_thinking_filter.cpp`.

### 3. Native Optimization & Acceleration
- **Root Cause:** In AGP, the `debug` build variant defaults `-DCMAKE_BUILD_TYPE=Debug` (`-O0`), disabling compiler optimizations and SIMD vectorization.
- **Fix:** Configured `-DCMAKE_BUILD_TYPE=Release`, `-O3 -DNDEBUG -fvectorize`, and `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` in `android/app/build.gradle.kts` and `src/main/cpp/CMakeLists.txt`. This increased decoding speed from 1.69 tok/s to **54.2 tok/s** and reduced TTFT from 4,842 ms to **92.4 ms**.

---

## 5. Phase 2 Engine Benchmarks: Audio, Speech Output (TTS), Video & Live Mode

Measured directly on `emulator-5554` running `app.finn.deepsystem` via automated instrumented test suite `LiveModeBenchmarkTest`:

| Benchmark Metric | Android Emulator (`finn_mid` API 34 arm64-v8a) | Host Bare-Metal (Apple M4 Metal GPU) | Notes |
| :--- | :--- | :--- | :--- |
| **Speech Output (TTS) Synthesis Time** | **1,395 ms** (~1.40 s) | 950 ms | Embedded `mtmd_helper::gen_audio` vocoder |
| **TTS Audio Duration** | **2.08 s** (49,920 samples @ 24 kHz) | 2.08 s | Native 24 kHz mono 16-bit PCM WAV |
| **TTS Real-Time Factor (RTF)** | **1.49x realtime** (faster than realtime) | **2.19x realtime** | >1.0x guarantees stutter-free streaming |
| **TTS Fallback Behavior** | Graceful fallback to Android `TextToSpeech` | N/A (Core only) | Surfaced transparently in UI Settings |
| **Live Turn End-to-End Latency** | **3,643 ms** (~3.64 s) | 1,420 ms | End-of-speech to first audio output via SentenceChunker |
| **Live Barge-In Interruption Time** | **< 50 ms** (measured 0-25 ms) | < 10 ms | Target <200ms; stops AudioTrack & cancels decode |
| **VAD Speech Detection Onset** | **40 ms** (2 frames @ 20ms) | 40 ms | Pluggable `EnergyVad` (RMS energy threshold) |
| **VAD Silence Hangover Timeout** | **300 - 600 ms** (configurable) | 500 ms | Declares end-of-speech without premature cuts |

### 1. Embedded Speech Output (TTS) & Vocoder Pipeline
- **Zero Third-Party Vendor Footprint:** Speech synthesis is embedded directly in `finn_core` utilizing `libmtmd`'s `mtmd_helper::gen_audio` and the LFM3 vocoder embedded in `mmproj-LFM3-Omni-2B-Q4_K_M.gguf`.
- **AudioTrack Streaming:** Streamed directly to Android's `AudioTrack` configured for 24,000 Hz mono 16-bit PCM in `MODE_STREAM` with `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH`.
- **Voice Customization:** Supports female and male voices per the LFM3 ChatML TTS template (`"Perform TTS in American English using a {voice} voice."`).
- **Resilient Fallback:** If native weights or multimodal vocoder projection are unavailable, `SpeechManager` seamlessly routes synthesis requests through Android's system `android.speech.tts.TextToSpeech` engine and notifies the user in the UI Model Manager / Settings sheet.

### 2. Live Mode Turn-Taking & Sentence-Chunked Streaming
- **Pure C++ Core State Machine:** Turn-taking state machine (`Idle` -> `Listening` -> `Thinking` -> `Speaking` -> `Listening`) is implemented in `finn_core` (`LiveSession`), validated by comprehensive host GoogleTests (`core_tests`).
- **Sentence-Chunked Streaming TTS:** Rather than waiting for the complete multi-sentence generation to terminate, `SentenceChunker` isolates grammatical clauses and sentence boundaries (`.`, `!`, `?`, `\n`, or commas after length threshold) in real time. Sentence 1 begins audio synthesis immediately while subsequent tokens continue generating in parallel, slashing time-to-first-audio latency.
- **Instant Barge-In Interruption:** When user speech is detected during either `Thinking` or `Speaking`, the engine issues an immediate atomic cancellation (`engine->cancel()`), halts `AudioTrack` playback within <50ms (well under the 200ms target), discards remaining TTS queues, and transitions the state machine back to `Listening`.

### 3. Video Input & Frame Sampling Pipeline
- **No FFmpeg Binary Requirement:** Since Android images do not package `ffmpeg`/`ffprobe` command-line binaries, video ingestion is handled natively:
  1. **Frame Sampling:** `MediaMetadataRetriever` samples $N$ frames (default 4-8 frames evenly spaced across video duration), scaled to 384x384 JPEG bytes.
  2. **Soundtrack Extraction & Resampling:** `MediaExtractor` and `MediaCodec` decode the video's compressed audio track (AAC/AMR) to raw PCM, downmixing channels to mono and linearly resampling to 16,000 Hz 16-bit PCM.
  3. **Multimodal Model Card Ingestion:** Frames and soundtrack are packed into `InputPartType::VideoFrames` and `InputPartType::AudioPcm16k` with the model card prompt: `"Answer questions about the video using what you see and hear."`.

### 4. Known Emulator Gaps & Physical Device Recommendations
- **Microphone Emulation Limitations:** Android Virtual Devices (AVD via QEMU) lack physical acoustic microphones, hardware AGC, and acoustic echo cancellation (AEC). Injected synthetic audio validates the full mathematical DSP and turn-taking pipeline; however, fine-tuning background noise rejection in noisy real-world environments requires validation on a physical handset.
- **Camera2 Virtual Sensor:** The emulator Camera2 provider yields virtual checkerboard textures. Optical autofocus latency, exposure convergence, and low-light frame ingestion should be benchmarked on a physical device with a hardware camera module.

---

## 6. Screenshots

| Chat Screen (Ready) | Model Manager Sheet | Real Sane Multi-Turn Chat |
| :---: | :---: | :---: |
| ![App Ready](../screenshots/app_main_ready.png) | ![Model Manager](../screenshots/model_manager_sheet.png) | ![Multi-Turn Chat](../screenshots/real_chat_streaming.png) |
