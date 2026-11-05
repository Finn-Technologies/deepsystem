# Host Benchmarks & Multimodal Findings

**System Environment:**
- **OS:** macOS Darwin 24.6.0 (Apple Silicon arm64)
- **Chip:** Apple M4 (GPU MTLGPUFamilyMetal3, unified memory)
- **Compiler:** AppleClang 17.0.0, CMake 4.4.5, Ninja 1.13.2
- **Runtime:** Pure C++17 shared core (`finn_core`) powered by llama.cpp `b12130` + `libmtmd`
- **Backbone Model:** LiquidAI `LFM3-Omni-2B-Q4_K_M.gguf` (1.05 GB)
- **Multimodal Projectors:**
  - `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` (278 MB)
  - `mmproj-LFM3-Omni-2B-Q8_0.gguf` (483 MB)

---

## 1. Test Suite Verification
All 12 GoogleTest unit tests pass cleanly in 71 ms via `ctest` / `core_tests`:
- `FakeEngineTest.LoadAndUnload`: PASSED
- `FakeEngineTest.TextGenerationStreaming`: PASSED
- `FakeEngineTest.CancellationViaCallback`: PASSED
- `FakeEngineTest.AsyncCancellation`: PASSED (verifies background thread cancellation)
- `FakeEngineTest.MultimodalInputs`: PASSED (text, image, PCM16k audio, video frames)
- `FakeEngineTest.SpeakCallback`: PASSED
- `StorageTest.InitAndClose`: PASSED
- `StorageTest.ConversationCRUD`: PASSED
- `StorageTest.MessagesManagement`: PASSED
- `StorageTest.JsonExport`: PASSED
- `CAbiTest.EngineLifecycleAndGeneration`: PASSED
- `CAbiTest.StorageCInterface`: PASSED

---

## 2. Text Generation Benchmark (Q4 Backbone)
- **Prompt:** `"Explain quantum computing in one short sentence."`
- **Prompt tokens:** 16
- **Completion tokens:** 21
- **Time to First Token (TTFT):** **58.47 ms**
- **Generation Speed:** **75.50 tokens/sec**
- **Total Time:** 323.38 ms

---

## 3. Image Understanding Benchmark (Q4 Backbone + Q4 mmproj)
- **Image:** `test_assets/test_image.png` (Apollo 11 newspaper article)
- **Prompt:** `"Describe what is in this image briefly."`
- **Vision Encoding Time:** 319 ms
- **Image Context Batch Decode:** 24 ms
- **Prompt tokens (text + image tokens):** 344
- **Completion tokens:** 64
- **Time to First Token (TTFT):** **1217.63 ms**
- **Generation Speed:** **68.07 tokens/sec**
- **Output:** Accurately transcribed the newspaper headline *"A Powdery Surface Is Closely Explored"* and details regarding Apollo 11 astronauts Neil Armstrong and Buzz Aldrin.

---

## 4. Audio ASR & Multimodal Projector A/B Comparison (Q4 vs Q8)

| Metric | Q4 mmproj (`Q4_K_M`) | Q8 mmproj (`Q8_0`) | Delta / Observation |
|---|---|---|---|
| **File Size** | 278 MB | 483 MB | Q4 is **42.4% smaller** |
| **Model Load Time** | 0.22 s | 0.24 s | Near identical |
| **Audio Preprocessing/Decode** | 3 ms | 3 ms | Real-time audio ingestion |
| **Prompt Tokens** | 74 | 74 | Identical token sequence |
| **Time to First Token (TTFT)** | **272.69 ms** | **235.24 ms** | Q8 is ~37 ms faster on TTFT |
| **Generation Speed** | 9.16 tokens/s | 10.68 tokens/s | Q8 is slightly faster on small batches |
| **Transcription Accuracy** | High | High | Both correctly transcribed input speech |

---

## 5. Speech Output (TTS) Findings & A/B Comparison

### Architecture & Embedding Analysis:
- **Embedded in Core Library:** Speech synthesis (TTS) **does NOT require an external or vendored third-party library**. `libmtmd` in llama.cpp natively compiles `mtmd-helper-gen.cpp` and `models/lfm3a-gen.cpp`.
- **Backend Architecture:** `mtmd_helper::gen_audio` utilizes the backbone model's hidden states via `llama_get_embeddings_ith()` and decodes audio frames through the vocoder in `mmproj`.
- **Template Requirement:** LFM3 requires its dedicated ChatML TTS prompt rendered as:
  `<|im_start|>system\nPerform TTS in American English using a {voice} voice.<|im_end|>\n<|im_start|>user\n{text}<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>`
- **Output Audio:** Native 24 kHz mono 16-bit PCM WAV waveform.

### Performance Comparison:
| Metric | Q4 mmproj | Q8 mmproj |
|---|---|---|
| **Phrase** | `"Hello from Finn DeepSystem."` | `"Hello from Finn DeepSystem."` |
| **Generated PCM Samples** | 53,760 samples (2.24s audio) | 49,920 samples (2.08s audio) |
| **Synthesis Time** | **1.09 s** | **0.95 s** |
| **Realtime Factor** | **2.05x realtime** (faster than realtime) | **2.19x realtime** |
| **Audio File Size** | 107,564 bytes | 99,884 bytes |
| **Quality** | Clear speech, minor quantization noise | Clear speech, slightly cleaner timbre |

**Recommendation:**
For mobile and edge deployment, `Q4_K_M` mmproj provides the best tradeoff between disk footprint (278 MB vs 483 MB) and real-time responsiveness (>2.0x realtime speech output).
