#include "finn/llama_engine.h"
#include "finn/thinking_filter.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <chrono>
#include <iostream>
#include <algorithm>
#include <vector>
#include <cstring>

namespace finn {

static std::vector<uint8_t> make_wav_buffer(const uint8_t* pcm_data, size_t pcm_size, int sample_rate = 16000, int channels = 1) {
    if (pcm_size >= 4 && std::memcmp(pcm_data, "RIFF", 4) == 0) {
        return std::vector<uint8_t>(pcm_data, pcm_data + pcm_size);
    }

    std::vector<uint8_t> wav(44 + pcm_size);
    uint32_t total_data_len = static_cast<uint32_t>(pcm_size + 36);
    uint32_t byte_rate = static_cast<uint32_t>(sample_rate * channels * 2);
    uint16_t block_align = static_cast<uint16_t>(channels * 2);
    uint16_t bits_per_sample = 16;
    uint32_t subchunk1_size = 16;
    uint16_t audio_format = 1; // PCM
    uint16_t num_channels = static_cast<uint16_t>(channels);
    uint32_t srate = static_cast<uint32_t>(sample_rate);
    uint32_t data_len = static_cast<uint32_t>(pcm_size);

    std::memcpy(&wav[0], "RIFF", 4);
    std::memcpy(&wav[4], &total_data_len, 4);
    std::memcpy(&wav[8], "WAVEfmt ", 8);
    std::memcpy(&wav[16], &subchunk1_size, 4);
    std::memcpy(&wav[20], &audio_format, 2);
    std::memcpy(&wav[22], &num_channels, 2);
    std::memcpy(&wav[24], &srate, 4);
    std::memcpy(&wav[28], &byte_rate, 4);
    std::memcpy(&wav[32], &block_align, 2);
    std::memcpy(&wav[34], &bits_per_sample, 2);
    std::memcpy(&wav[36], "data", 4);
    std::memcpy(&wav[40], &data_len, 4);
    if (pcm_size > 0 && pcm_data) {
        std::memcpy(&wav[44], pcm_data, pcm_size);
    }
    return wav;
}

LlamaEngine::LlamaEngine() {
    llama_backend_init();
}

LlamaEngine::~LlamaEngine() {
    unload();
}

void LlamaEngine::cleanup_handles() {
    if (mctx_) {
        mtmd_free(mctx_);
        mctx_ = nullptr;
    }
    if (ctx_) {
        llama_free(ctx_);
        ctx_ = nullptr;
    }
    if (model_) {
        llama_model_free(model_);
        model_ = nullptr;
    }
    vocab_ = nullptr;
    is_loaded_ = false;
}

bool LlamaEngine::load(const EngineLoadConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    cleanup_handles();

    config_ = config;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = config.n_gpu_layers;
    mparams.load_mode = config.use_mmap ? 
        (config.use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP) :
        (config.use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);

    model_ = llama_model_load_from_file(config.model_path.c_str(), mparams);
    if (!model_) {
        std::cerr << "[finn] Failed to load model: " << config.model_path << std::endl;
        return false;
    }

    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = config.n_ctx > 0 ? config.n_ctx : 4096;
    cparams.n_threads = config.n_threads > 0 ? config.n_threads : 4;
    cparams.n_threads_batch = config.n_threads > 0 ? config.n_threads : 4;
    cparams.embeddings = true;

    ctx_ = llama_init_from_model(model_, cparams);
    if (!ctx_) {
        std::cerr << "[finn] Failed to initialize llama context" << std::endl;
        cleanup_handles();
        return false;
    }

    if (!config.mmproj_path.empty()) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = config.n_gpu_layers > 0;
        mctx_ = mtmd_init_from_file(config.mmproj_path.c_str(), model_, mtmd_params);
        if (!mctx_) {
            std::cerr << "[finn] Warning: Failed to load mmproj: " << config.mmproj_path << std::endl;
        }
    }

    is_loaded_ = true;
    is_cancelled_ = false;
    return true;
}

void LlamaEngine::unload() {
    std::lock_guard<std::mutex> lock(mutex_);
    cleanup_handles();
}

bool LlamaEngine::is_loaded() const {
    return is_loaded_.load();
}

void LlamaEngine::cancel() {
    is_cancelled_ = true;
}

bool LlamaEngine::is_cancelled() const {
    return is_cancelled_.load();
}

EngineLoadConfig LlamaEngine::get_config() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return config_;
}

bool LlamaEngine::generate(
    const std::vector<InputPart>& parts,
    const GenerationConfig& config,
    TokenCallback token_cb,
    EngineStats* stats
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_loaded_ || !model_ || !ctx_ || !vocab_) {
        return false;
    }

    is_cancelled_ = false;
    auto start_time = std::chrono::steady_clock::now();

    // Reset KV cache memory
    llama_memory_t mem = llama_get_memory(ctx_);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    llama_pos n_past = 0;
    const int n_batch = 512;

    // Check for multimodal inputs
    std::vector<mtmd_bitmap*> loaded_bitmaps;
    std::string text_prompt;
    bool has_multimodal = false;

    for (const auto& part : parts) {
        if (part.type == InputPartType::Text) {
            text_prompt += part.text;
        } else if (part.type == InputPartType::Image && mctx_ != nullptr) {
            auto wrapper = mtmd_helper_bitmap_init_from_buf(
                mctx_,
                part.image.data.data(),
                part.image.data.size(),
                false,
                mtmd_helper_init_opt_default()
            );
            if (wrapper.bitmap) {
                loaded_bitmaps.push_back(wrapper.bitmap);
                has_multimodal = true;
            }
        } else if (part.type == InputPartType::AudioPcm16k && mctx_ != nullptr) {
            auto wav = make_wav_buffer(part.audio.pcm_data.data(), part.audio.pcm_data.size(), part.audio.sample_rate, part.audio.channels);
            auto wrapper = mtmd_helper_bitmap_init_from_buf(
                mctx_,
                wav.data(),
                wav.size(),
                false,
                mtmd_helper_init_opt_default()
            );
            if (wrapper.bitmap) {
                loaded_bitmaps.push_back(wrapper.bitmap);
                has_multimodal = true;
            }
        }
    }

    int prompt_tokens_count = 0;

    if (has_multimodal && mctx_ != nullptr && !loaded_bitmaps.empty()) {
        // Ensure prompt has media marker
        const char* default_marker = mtmd_default_marker();
        if (text_prompt.find(default_marker) == std::string::npos) {
            std::string markers;
            for (size_t i = 0; i < loaded_bitmaps.size(); ++i) {
                markers += std::string(default_marker) + "\n";
            }
            text_prompt = markers + text_prompt;
        }

        mtmd_input_chunks* chunks = mtmd_input_chunks_init();
        mtmd_input_text inp_text{};
        inp_text.text = text_prompt.c_str();
        inp_text.text_len = text_prompt.size();
        inp_text.add_special = true;
        inp_text.parse_special = true;

        int32_t tok_res = mtmd_tokenize(mctx_, chunks, &inp_text, loaded_bitmaps.data(), loaded_bitmaps.size());
        if (tok_res != 0) {
            std::cerr << "[finn] mtmd_tokenize failed with code: " << tok_res << std::endl;
            mtmd_input_chunks_free(chunks);
            for (auto* bmp : loaded_bitmaps) mtmd_bitmap_free(bmp);
            return false;
        }

        prompt_tokens_count = static_cast<int>(mtmd_helper_get_n_tokens(chunks));

        int32_t eval_res = mtmd_helper_eval_chunks(mctx_, ctx_, chunks, 0, 0, n_batch, true, &n_past);
        mtmd_input_chunks_free(chunks);
        for (auto* bmp : loaded_bitmaps) mtmd_bitmap_free(bmp);

        if (eval_res != 0) {
            std::cerr << "[finn] mtmd_helper_eval_chunks failed with code: " << eval_res << std::endl;
            return false;
        }
    } else {
        // Pure text generation
        for (auto* bmp : loaded_bitmaps) mtmd_bitmap_free(bmp);

        std::vector<llama_token> tokens(text_prompt.size() + 16);
        int32_t n_tokens = llama_tokenize(
            vocab_,
            text_prompt.c_str(),
            static_cast<int32_t>(text_prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
        );

        if (n_tokens < 0) {
            tokens.resize(-n_tokens);
            n_tokens = llama_tokenize(
                vocab_,
                text_prompt.c_str(),
                static_cast<int32_t>(text_prompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                true
            );
        }

        if (n_tokens <= 0) {
            std::cerr << "[finn] Failed to tokenize prompt" << std::endl;
            return false;
        }

        tokens.resize(n_tokens);
        prompt_tokens_count = n_tokens;

        for (int i = 0; i < n_tokens; i += n_batch) {
            int chunk_size = std::min(static_cast<int>(tokens.size()) - i, n_batch);
            llama_batch b = llama_batch_init(chunk_size, 0, 1);
            for (int j = 0; j < chunk_size; ++j) {
                b.token[j] = tokens[i + j];
                b.pos[j] = n_past + j;
                b.n_seq_id[j] = 1;
                b.seq_id[j][0] = 0;
                b.logits[j] = (i + j == n_tokens - 1);
            }
            b.n_tokens = chunk_size;
            int res = llama_decode(ctx_, b);
            llama_batch_free(b);
            if (res != 0) {
                std::cerr << "[finn] llama_decode failed during prompt eval: " << res << std::endl;
                return false;
            }
            n_past += chunk_size;
        }
    }

    // Set up modern sampler chain
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (config.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(config.top_k));
    }
    if (config.top_p > 0.0f && config.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(config.top_p, 1));
    }
    if (config.temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(config.temperature));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }
    // Prevent degeneration / repetition loops
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), 64, 1.10f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    bool first_token = true;
    int completion_tokens = 0;
    ThinkingFilter think_filter(token_cb, true);

    for (int i = 0; i < config.max_tokens; ++i) {
        if (is_cancelled_.load()) {
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, ctx_, -1);
        llama_sampler_accept(smpl, new_token);

        if (llama_vocab_is_eog(vocab_, new_token)) {
            break;
        }

        if (llama_vocab_is_control(vocab_, new_token)) {
            char piece_special[64];
            int sp_len = llama_token_to_piece(vocab_, new_token, piece_special, sizeof(piece_special), 0, true);
            if (sp_len > 0) {
                std::string sp(piece_special, sp_len);
                if (sp.find("<|im_end|>") != std::string::npos ||
                    sp.find("<|im_start|>") != std::string::npos ||
                    sp.find("<|endoftext|>") != std::string::npos ||
                    sp.find("<|eot_id|>") != std::string::npos) {
                    break;
                }
            }
        }

        if (first_token) {
            auto first_tok_time = std::chrono::steady_clock::now();
            if (stats) {
                stats->ttft_ms = std::chrono::duration<double, std::milli>(first_tok_time - start_time).count();
            }
            first_token = false;
        }

        char piece_buf[256];
        int n_piece = llama_token_to_piece(vocab_, new_token, piece_buf, sizeof(piece_buf), 0, false);
        if (n_piece > 0) {
            std::string piece(piece_buf, n_piece);

            // Additional stop token guard
            if (piece == "<|im_end|>" || piece == "<|im_start|>" || piece == "<|endoftext|>" || piece == "<|eot_id|>") {
                break;
            }

            completion_tokens++;
            bool cont = think_filter.on_token(piece);
            if (!cont) {
                is_cancelled_ = true;
                break;
            }
        }

        // Decode token to advance model state
        llama_batch b = llama_batch_init(1, 0, 1);
        b.token[0] = new_token;
        b.pos[0] = n_past++;
        b.n_seq_id[0] = 1;
        b.seq_id[0][0] = 0;
        b.logits[0] = true;
        b.n_tokens = 1;

        int res = llama_decode(ctx_, b);
        llama_batch_free(b);
        if (res != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);

    auto end_time = std::chrono::steady_clock::now();
    double total_ms = std::chrono::duration<double, std::milli>(end_time - start_time).count();

    if (stats) {
        stats->prompt_tokens = prompt_tokens_count;
        stats->completion_tokens = completion_tokens;
        stats->total_time_ms = total_ms;
        double gen_ms = total_ms - stats->ttft_ms;
        if (gen_ms > 0 && completion_tokens > 1) {
            stats->tokens_per_second = ((completion_tokens - 1) * 1000.0) / gen_ms;
        } else if (total_ms > 0 && completion_tokens > 0) {
            stats->tokens_per_second = (completion_tokens * 1000.0) / total_ms;
        }
    }

    return !is_cancelled_.load();
}

bool LlamaEngine::speak(
    const std::string& text,
    AudioCallback audio_cb,
    const std::string& voice
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_loaded_ || !model_ || !ctx_ || !mctx_) {
        return false;
    }

    auto audio_info = mtmd_gen_audio_get_info(mctx_);
    if (audio_info.type == MTMD_GEN_AUDIO_TYPE_NONE) {
        std::cerr << "[finn] Active mmproj does not support audio generation" << std::endl;
        return false;
    }

    is_cancelled_ = false;

    // Reset KV cache memory
    llama_memory_t mem = llama_get_memory(ctx_);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    mtmd::bitmap_ptr voice_bitmap;
    if (!voice.empty()) {
        voice_bitmap.reset(mtmd_gen_audio_get_voice(mctx_, voice.c_str()));
    } else if (audio_info.n_voices > 0) {
        const char* default_voice_name = mtmd_gen_audio_voice_name(mctx_, 0);
        if (default_voice_name) {
            voice_bitmap.reset(mtmd_gen_audio_get_voice(mctx_, default_voice_name));
        }
    }

    std::string v = voice.empty() ? "female" : voice;
    std::string tts_prompt = "<|im_start|>system\nPerform TTS in American English using a " + v + " voice.<|im_end|>\n"
                           + "<|im_start|>user\n" + text + "<|im_end|>\n"
                           + "<|im_start|>assistant\n<think></think><|audio_start|>";

    mtmd_helper::gen_audio gen;
    gen.init(ctx_, mctx_);

    mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
    inp.seq_id = 0;
    inp.prompt = tts_prompt.c_str();
    inp.prompt_len = tts_prompt.size();
    inp.speaker_ref = voice_bitmap.get();
    inp.top_k = 40;
    inp.top_p = 0.9f;

    if (gen.set_input(&inp) != 0) {
        std::cerr << "[finn] gen_audio set_input failed" << std::endl;
        return false;
    }

    while (!is_cancelled_.load()) {
        int32_t ret = gen.step_prompt(config_.n_threads);
        if (ret < 0) {
            return false;
        }
        if (ret == 0) {
            break;
        }
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_token sampled = llama_sampler_sample(smpl, ctx_, -1);
    llama_sampler_accept(smpl, sampled);
    const float* h_state = llama_get_embeddings_ith(ctx_, -1);

    bool stop = false;
    int n_frames = 0;
    const int max_frames = 512;

    while (!stop && !is_cancelled_.load() && n_frames < max_frames) {
        const float* h_next = nullptr;
        if (gen.step_gen(sampled, h_state, &h_next, &stop) != 0 || !h_next) {
            break;
        }
        n_frames++;
        h_state = h_next;
        sampled = llama_sampler_sample(smpl, ctx_, -1);
        llama_sampler_accept(smpl, sampled);
    }

    llama_sampler_free(smpl);

    int32_t sample_rate = 0;
    const char* data = nullptr;
    size_t data_len = 0;
    int64_t n_samples = 0;

    if (gen.get_output(&sample_rate, &data, &data_len, &n_samples) != 0) {
        return false;
    }

    if (data && data_len > 44 && audio_cb) {
        const int16_t* pcm_samples = reinterpret_cast<const int16_t*>(data + 44);
        size_t sample_count = (data_len - 44) / sizeof(int16_t);
        return audio_cb(pcm_samples, sample_count, sample_rate > 0 ? sample_rate : 24000);
    }

    return true;
}

} // namespace finn
