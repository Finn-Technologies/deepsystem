#ifndef FINN_C_H
#define FINN_C_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#    if defined(FINN_BUILD_SHARED)
#        define FINN_API __declspec(dllexport)
#    else
#        define FINN_API __declspec(dllimport)
#    endif
#else
#    define FINN_API __attribute__((visibility("default")))
#endif

/* Opaque handles */
typedef struct finn_engine_t finn_engine_t;
typedef struct finn_storage_t finn_storage_t;

/* Callback types */
/* Return 0 to continue generation, 1 to cancel */
typedef int (*finn_token_callback_fn)(const char* token, void* user_data);

/* Return 0 to continue speech playback, 1 to cancel */
typedef int (*finn_audio_callback_fn)(const int16_t* pcm_samples, size_t sample_count, int sample_rate, void* user_data);

/* Engine Configuration struct */
typedef struct {
    const char* model_path;
    const char* mmproj_path;
    int32_t n_ctx;
    int32_t n_threads;
    int32_t n_gpu_layers;
    int32_t use_mmap;
} finn_engine_config_t;

/* Generation Statistics */
typedef struct {
    double ttft_ms;
    double tokens_per_second;
    int32_t prompt_tokens;
    int32_t completion_tokens;
    double total_time_ms;
} finn_engine_stats_t;

/* ========================================================================= */
/* Engine API                                                                */
/* ========================================================================= */

/* Create a new real LlamaEngine instance */
FINN_API finn_engine_t* finn_engine_create(void);

/* Create a FakeEngine instance for testing without model weights */
FINN_API finn_engine_t* finn_engine_create_fake(void);

/* Free engine instance */
FINN_API void finn_engine_destroy(finn_engine_t* engine);

/* Load model from configuration. Returns 0 on success, -1 on error. */
FINN_API int finn_engine_load(finn_engine_t* engine, const finn_engine_config_t* config);

/* Unload currently loaded model */
FINN_API void finn_engine_unload(finn_engine_t* engine);

/* Check if model is loaded. Returns 1 if loaded, 0 otherwise. */
FINN_API int finn_engine_is_loaded(const finn_engine_t* engine);

/* Text generation. Returns 0 on success, -1 on error/cancelled. */
FINN_API int finn_engine_generate_text(
    finn_engine_t* engine,
    const char* prompt,
    float temperature,
    int32_t max_tokens,
    finn_token_callback_fn cb,
    void* user_data,
    finn_engine_stats_t* stats
);

/* Multimodal generation (text + optional image/audio). Returns 0 on success. */
FINN_API int finn_engine_generate_multimodal(
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
);

/* Request immediate cancellation of ongoing generation or speech */
FINN_API void finn_engine_cancel(finn_engine_t* engine);

/* Check if engine generation is cancelled */
FINN_API int finn_engine_is_cancelled(const finn_engine_t* engine);

/* Stream speech synthesis (TTS) for the given text. Returns 0 on success. */
FINN_API int finn_engine_speak(
    finn_engine_t* engine,
    const char* text,
    const char* voice,
    finn_audio_callback_fn cb,
    void* user_data
);

/* Multimodal video generation (frames + audio soundtrack + text prompt) */
FINN_API int finn_engine_generate_video(
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
);

/* ========================================================================= */
/* Live Session API (VAD, Turn-Taking, Barge-in, Sentence-Chunked Streaming)  */
/* ========================================================================= */

typedef enum {
    FINN_LIVE_STATE_IDLE = 0,
    FINN_LIVE_STATE_LISTENING = 1,
    FINN_LIVE_STATE_THINKING = 2,
    FINN_LIVE_STATE_SPEAKING = 3
} finn_live_state_t;

typedef void (*finn_live_state_cb_fn)(int32_t old_state, int32_t new_state, void* user_data);

typedef struct finn_live_session_t finn_live_session_t;

typedef struct {
    float vad_energy_threshold;
    int32_t vad_silence_timeout_ms;
    const char* voice;
    const char* system_prompt;
} finn_live_config_t;

FINN_API finn_live_session_t* finn_live_create(finn_engine_t* engine, const finn_live_config_t* config);
FINN_API void finn_live_destroy(finn_live_session_t* session);

FINN_API void finn_live_start(finn_live_session_t* session);
FINN_API void finn_live_stop(finn_live_session_t* session);

FINN_API void finn_live_feed_audio(finn_live_session_t* session, const int16_t* pcm, size_t count);
FINN_API void finn_live_feed_camera_frame(finn_live_session_t* session, const uint8_t* data, size_t size);

FINN_API void finn_live_set_state_callback(finn_live_session_t* session, finn_live_state_cb_fn cb, void* user_data);
FINN_API void finn_live_set_audio_callback(finn_live_session_t* session, finn_audio_callback_fn cb, void* user_data);

FINN_API int32_t finn_live_get_state(const finn_live_session_t* session);
FINN_API double finn_live_get_last_latency_ms(const finn_live_session_t* session);
FINN_API void finn_live_trigger_barge_in(finn_live_session_t* session);
FINN_API void finn_live_trigger_end_of_speech(finn_live_session_t* session);

/* ========================================================================= */
/* Storage API                                                               */
/* ========================================================================= */

FINN_API finn_storage_t* finn_storage_create(void);
FINN_API void finn_storage_destroy(finn_storage_t* storage);

FINN_API int finn_storage_open(finn_storage_t* storage, const char* db_path);
FINN_API void finn_storage_close(finn_storage_t* storage);

FINN_API int finn_storage_create_conversation(finn_storage_t* storage, const char* id, const char* title);
FINN_API int finn_storage_delete_conversation(finn_storage_t* storage, const char* id);
FINN_API int finn_storage_add_message(finn_storage_t* storage, const char* conv_id, const char* role, const char* content);

/* Returns a heap-allocated JSON string. Must be freed with finn_free_string(). */
FINN_API char* finn_storage_list_conversations_json(finn_storage_t* storage);
FINN_API char* finn_storage_get_messages_json(finn_storage_t* storage, const char* conv_id);

/* Free heap memory returned by finn API */
FINN_API void finn_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif /* FINN_C_H */
