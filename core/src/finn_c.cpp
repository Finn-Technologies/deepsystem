#include "finn/finn_c.h"
#include "finn/llama_engine.h"
#include "finn/fake_engine.h"
#include "finn/storage.h"
#include "finn/live_session.h"

#include <cstring>
#include <cstdlib>
#include <memory>

struct finn_engine_t {
    std::unique_ptr<finn::InferenceEngine> engine;
    finn_engine_stats_t last_stats{};
};

struct finn_storage_t {
    std::unique_ptr<finn::ConversationStorage> storage;
};

static char* duplicate_string(const std::string& str) {
    char* copy = static_cast<char*>(std::malloc(str.size() + 1));
    if (copy) {
        std::memcpy(copy, str.c_str(), str.size() + 1);
    }
    return copy;
}

finn_engine_t* finn_engine_create(void) {
    auto handle = new finn_engine_t();
    handle->engine = std::make_unique<finn::LlamaEngine>();
    return handle;
}

finn_engine_t* finn_engine_create_fake(void) {
    auto handle = new finn_engine_t();
    handle->engine = std::make_unique<finn::FakeEngine>();
    return handle;
}

void finn_engine_destroy(finn_engine_t* engine) {
    if (engine) {
        delete engine;
    }
}

int finn_engine_load(finn_engine_t* engine, const finn_engine_config_t* config) {
    if (!engine || !engine->engine || !config) return -1;

    finn::EngineLoadConfig cfg;
    cfg.model_path = config->model_path ? config->model_path : "";
    cfg.mmproj_path = config->mmproj_path ? config->mmproj_path : "";
    cfg.n_ctx = config->n_ctx > 0 ? config->n_ctx : 4096;
    cfg.n_threads = config->n_threads > 0 ? config->n_threads : 4;
    cfg.n_gpu_layers = config->n_gpu_layers;
    cfg.use_mmap = config->use_mmap != 0;

    return engine->engine->load(cfg) ? 0 : -1;
}

void finn_engine_unload(finn_engine_t* engine) {
    if (engine && engine->engine) {
        engine->engine->unload();
    }
}

int finn_engine_is_loaded(const finn_engine_t* engine) {
    return (engine && engine->engine && engine->engine->is_loaded()) ? 1 : 0;
}

int finn_engine_generate_text(
    finn_engine_t* engine,
    const char* prompt,
    float temperature,
    int32_t max_tokens,
    finn_token_callback_fn cb,
    void* user_data,
    finn_engine_stats_t* stats
) {
    if (!engine || !engine->engine || !prompt) return -1;

    std::string p = prompt;
    if (p.find("<|im_start|>") == std::string::npos) {
        p = "<|im_start|>user\n" + p + "<|im_end|>\n<|im_start|>assistant\n";
    }

    std::vector<finn::InputPart> parts;
    parts.push_back(finn::InputPart::from_text(p));

    finn::GenerationConfig gen_cfg;
    gen_cfg.temperature = temperature;
    gen_cfg.max_tokens = max_tokens > 0 ? max_tokens : 512;

    finn::EngineStats finn_stats{};
    auto callback_wrapper = [cb, user_data](const std::string& token) -> bool {
        if (!cb) return true;
        // 0 to continue, 1 to cancel
        return cb(token.c_str(), user_data) == 0;
    };

    bool ok = engine->engine->generate(parts, gen_cfg, callback_wrapper, &finn_stats);

    engine->last_stats.ttft_ms = finn_stats.ttft_ms;
    engine->last_stats.tokens_per_second = finn_stats.tokens_per_second;
    engine->last_stats.prompt_tokens = finn_stats.prompt_tokens;
    engine->last_stats.completion_tokens = finn_stats.completion_tokens;
    engine->last_stats.total_time_ms = finn_stats.total_time_ms;

    if (stats) {
        *stats = engine->last_stats;
    }

    return ok ? 0 : -1;
}

int finn_engine_generate_multimodal(
    finn_engine_t* engine,
    const char* prompt,
    const uint8_t* image_bytes,
    size_t image_size,
    const uint8_t* audio_pcm,
    size_t audio_size,
    float temperature,
    int32_t max_tokens,
    finn_token_callback_fn cb,
    void* user_data,
    finn_engine_stats_t* stats
) {
    if (!engine || !engine->engine) return -1;

    std::vector<finn::InputPart> parts;
    if (prompt && std::strlen(prompt) > 0) {
        std::string p = prompt;
        if (p.find("<|im_start|>") == std::string::npos) {
            p = "<|im_start|>user\n" + p + "<|im_end|>\n<|im_start|>assistant\n";
        }
        parts.push_back(finn::InputPart::from_text(p));
    }
    if (image_bytes && image_size > 0) {
        std::vector<uint8_t> img(image_bytes, image_bytes + image_size);
        parts.push_back(finn::InputPart::from_image(img));
    }
    if (audio_pcm && audio_size > 0) {
        std::vector<uint8_t> aud(audio_pcm, audio_pcm + audio_size);
        parts.push_back(finn::InputPart::from_audio_pcm16k(aud));
    }

    finn::GenerationConfig gen_cfg;
    gen_cfg.temperature = temperature;
    gen_cfg.max_tokens = max_tokens > 0 ? max_tokens : 512;

    finn::EngineStats finn_stats{};
    auto callback_wrapper = [cb, user_data](const std::string& token) -> bool {
        if (!cb) return true;
        return cb(token.c_str(), user_data) == 0;
    };

    bool ok = engine->engine->generate(parts, gen_cfg, callback_wrapper, &finn_stats);

    engine->last_stats.ttft_ms = finn_stats.ttft_ms;
    engine->last_stats.tokens_per_second = finn_stats.tokens_per_second;
    engine->last_stats.prompt_tokens = finn_stats.prompt_tokens;
    engine->last_stats.completion_tokens = finn_stats.completion_tokens;
    engine->last_stats.total_time_ms = finn_stats.total_time_ms;

    if (stats) {
        *stats = engine->last_stats;
    }

    return ok ? 0 : -1;
}

void finn_engine_cancel(finn_engine_t* engine) {
    if (engine && engine->engine) {
        engine->engine->cancel();
    }
}

int finn_engine_is_cancelled(const finn_engine_t* engine) {
    return (engine && engine->engine && engine->engine->is_cancelled()) ? 1 : 0;
}

int finn_engine_speak(
    finn_engine_t* engine,
    const char* text,
    const char* voice,
    finn_audio_callback_fn cb,
    void* user_data
) {
    if (!engine || !engine->engine || !text) return -1;

    auto callback_wrapper = [cb, user_data](const int16_t* pcm_samples, size_t sample_count, int sample_rate) -> bool {
        if (!cb) return true;
        return cb(pcm_samples, sample_count, sample_rate, user_data) == 0;
    };

    std::string v = voice ? voice : "";
    return engine->engine->speak(text, callback_wrapper, v) ? 0 : -1;
}

/* Storage API */

finn_storage_t* finn_storage_create(void) {
    auto handle = new finn_storage_t();
    handle->storage = std::make_unique<finn::ConversationStorage>();
    return handle;
}

void finn_storage_destroy(finn_storage_t* storage) {
    if (storage) {
        delete storage;
    }
}

int finn_storage_open(finn_storage_t* storage, const char* db_path) {
    if (!storage || !storage->storage || !db_path) return -1;
    return storage->storage->init(db_path) ? 0 : -1;
}

void finn_storage_close(finn_storage_t* storage) {
    if (storage && storage->storage) {
        storage->storage->close();
    }
}

int finn_storage_create_conversation(finn_storage_t* storage, const char* id, const char* title) {
    if (!storage || !storage->storage || !id || !title) return -1;
    finn::Conversation conv;
    conv.id = id;
    conv.title = title;
    return storage->storage->create_conversation(conv) ? 0 : -1;
}

int finn_storage_delete_conversation(finn_storage_t* storage, const char* id) {
    if (!storage || !storage->storage || !id) return -1;
    return storage->storage->delete_conversation(id) ? 0 : -1;
}

int finn_storage_add_message(finn_storage_t* storage, const char* conv_id, const char* role, const char* content) {
    if (!storage || !storage->storage || !conv_id || !role || !content) return -1;
    finn::ChatMessage msg;
    msg.role = finn::string_to_role(role);
    msg.parts.push_back(finn::InputPart::from_text(content));
    return storage->storage->add_message(conv_id, msg) ? 0 : -1;
}

char* finn_storage_list_conversations_json(finn_storage_t* storage) {
    if (!storage || !storage->storage) return nullptr;
    std::string json = storage->storage->export_conversations_json();
    return duplicate_string(json);
}

char* finn_storage_get_messages_json(finn_storage_t* storage, const char* conv_id) {
    if (!storage || !storage->storage || !conv_id) return nullptr;
    std::string json = storage->storage->export_messages_json(conv_id);
    return duplicate_string(json);
}

void finn_free_string(char* s) {
    if (s) {
        std::free(s);
    }
}

/* Video Multimodal API */

int finn_engine_generate_video(
    finn_engine_t* engine,
    const char* prompt,
    const uint8_t** frame_buffers,
    const size_t* frame_sizes,
    size_t frame_count,
    const uint8_t* audio_pcm,
    size_t audio_size,
    float temperature,
    int32_t max_tokens,
    finn_token_callback_fn cb,
    void* user_data,
    finn_engine_stats_t* stats
) {
    if (!engine || !engine->engine) return -1;

    std::vector<finn::InputPart> parts;
    if (frame_buffers && frame_sizes && frame_count > 0) {
        std::vector<finn::VideoFrame> frames;
        for (size_t i = 0; i < frame_count; ++i) {
            if (frame_buffers[i] && frame_sizes[i] > 0) {
                finn::VideoFrame vf;
                vf.data.assign(frame_buffers[i], frame_buffers[i] + frame_sizes[i]);
                vf.timestamp_ms = static_cast<int64_t>(i * 250);
                frames.push_back(std::move(vf));
            }
        }
        if (!frames.empty()) {
            parts.push_back(finn::InputPart::from_video_frames(frames));
        }
    }

    if (audio_pcm && audio_size > 0) {
        std::vector<uint8_t> aud(audio_pcm, audio_pcm + audio_size);
        parts.push_back(finn::InputPart::from_audio_pcm16k(aud));
    }

    std::string p = prompt ? prompt : "Answer questions about the video using what you see and hear.";
    if (p.find("<|im_start|>") == std::string::npos) {
        p = "<|im_start|>user\n" + p + "<|im_end|>\n<|im_start|>assistant\n";
    }
    parts.push_back(finn::InputPart::from_text(p));

    finn::GenerationConfig gen_cfg;
    gen_cfg.temperature = temperature;
    gen_cfg.max_tokens = max_tokens > 0 ? max_tokens : 512;

    finn::EngineStats finn_stats{};
    auto callback_wrapper = [cb, user_data](const std::string& token) -> bool {
        if (!cb) return true;
        return cb(token.c_str(), user_data) == 0;
    };

    bool ok = engine->engine->generate(parts, gen_cfg, callback_wrapper, &finn_stats);

    engine->last_stats.ttft_ms = finn_stats.ttft_ms;
    engine->last_stats.tokens_per_second = finn_stats.tokens_per_second;
    engine->last_stats.prompt_tokens = finn_stats.prompt_tokens;
    engine->last_stats.completion_tokens = finn_stats.completion_tokens;
    engine->last_stats.total_time_ms = finn_stats.total_time_ms;

    if (stats) {
        *stats = engine->last_stats;
    }

    return ok ? 0 : -1;
}

/* Live Session API */

struct finn_live_session_t {
    std::unique_ptr<finn::LiveSession> session;
};

finn_live_session_t* finn_live_create(finn_engine_t* engine, const finn_live_config_t* config) {
    if (!engine || !engine->engine) return nullptr;

    finn::LiveSessionConfig cfg;
    if (config) {
        if (config->vad_energy_threshold > 0.0f) cfg.vad_energy_threshold = config->vad_energy_threshold;
        if (config->vad_silence_timeout_ms > 0) cfg.vad_silence_timeout_ms = config->vad_silence_timeout_ms;
        if (config->voice) cfg.voice = config->voice;
        if (config->system_prompt) cfg.system_prompt = config->system_prompt;
    }

    auto handle = new finn_live_session_t();
    handle->session = std::make_unique<finn::LiveSession>(engine->engine.get(), cfg);
    return handle;
}

void finn_live_destroy(finn_live_session_t* session) {
    if (session) {
        delete session;
    }
}

void finn_live_start(finn_live_session_t* session) {
    if (session && session->session) {
        session->session->start();
    }
}

void finn_live_stop(finn_live_session_t* session) {
    if (session && session->session) {
        session->session->stop();
    }
}

void finn_live_feed_audio(finn_live_session_t* session, const int16_t* pcm, size_t count) {
    if (session && session->session) {
        session->session->feed_audio(pcm, count);
    }
}

void finn_live_feed_camera_frame(finn_live_session_t* session, const uint8_t* data, size_t size) {
    if (session && session->session) {
        session->session->feed_camera_frame(data, size);
    }
}

void finn_live_set_state_callback(finn_live_session_t* session, finn_live_state_cb_fn cb, void* user_data) {
    if (session && session->session) {
        if (cb) {
            session->session->set_state_callback([cb, user_data](finn::LiveState old_s, finn::LiveState new_s) {
                cb(static_cast<int32_t>(old_s), static_cast<int32_t>(new_s), user_data);
            });
        } else {
            session->session->set_state_callback(nullptr);
        }
    }
}

void finn_live_set_audio_callback(finn_live_session_t* session, finn_audio_callback_fn cb, void* user_data) {
    if (session && session->session) {
        if (cb) {
            session->session->set_audio_output_callback([cb, user_data](const int16_t* pcm, size_t count, int rate) -> bool {
                return cb(pcm, count, rate, user_data) == 0;
            });
        } else {
            session->session->set_audio_output_callback(nullptr);
        }
    }
}

int32_t finn_live_get_state(const finn_live_session_t* session) {
    if (session && session->session) {
        return static_cast<int32_t>(session->session->get_state());
    }
    return static_cast<int32_t>(finn::LiveState::Idle);
}

double finn_live_get_last_latency_ms(const finn_live_session_t* session) {
    if (session && session->session) {
        return session->session->get_last_latency_ms();
    }
    return 0.0;
}

void finn_live_trigger_barge_in(finn_live_session_t* session) {
    if (session && session->session) {
        session->session->trigger_barge_in();
    }
}

void finn_live_trigger_end_of_speech(finn_live_session_t* session) {
    if (session && session->session) {
        session->session->trigger_end_of_speech();
    }
}
