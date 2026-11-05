#pragma once

#include "finn/inference_engine.h"
#include "finn/vad.h"
#include "finn/sentence_chunker.h"

#include <atomic>
#include <chrono>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace finn {

enum class LiveState {
    Idle = 0,
    Listening = 1,
    Thinking = 2,
    Speaking = 3
};

inline const char* live_state_to_string(LiveState state) {
    switch (state) {
        case LiveState::Idle: return "Idle";
        case LiveState::Listening: return "Listening";
        case LiveState::Thinking: return "Thinking";
        case LiveState::Speaking: return "Speaking";
    }
    return "Unknown";
}

struct LiveSessionConfig {
    float vad_energy_threshold = 450.0f;
    int vad_silence_timeout_ms = 600; // 600ms silence = end of speech
    int sample_rate = 16000;
    std::string voice = "female";
    std::string system_prompt = "You are Finn, an intelligent, helpful, and concise conversational AI assistant. Keep responses brief and conversational.";
};

class LiveSession {
public:
    using StateCallback = std::function<void(LiveState old_state, LiveState new_state)>;
    using AudioOutputCallback = std::function<bool(const int16_t* pcm_samples, size_t sample_count, int sample_rate)>;

    LiveSession(InferenceEngine* engine, const LiveSessionConfig& config = LiveSessionConfig());
    ~LiveSession();

    void start();
    void stop();

    bool is_active() const;
    LiveState get_state() const;

    // Feed 16kHz mono PCM samples from microphone/test injector
    void feed_audio(const int16_t* samples, size_t count);

    // Provide latest camera frame (e.g., JPEG bytes) as visual context
    void feed_camera_frame(const uint8_t* data, size_t size);

    void set_state_callback(StateCallback cb);
    void set_audio_output_callback(AudioOutputCallback cb);

    // Last measured end-to-end latency in ms (end of speech -> first audio chunk)
    double get_last_latency_ms() const;

    // Direct barge-in trigger (programmatic or via external detector)
    void trigger_barge_in();

    // Helper for testing: trigger end-of-speech manually
    void trigger_end_of_speech();

    // Set custom VAD implementation
    void set_vad(std::unique_ptr<Vad> vad);

private:
    void set_state(LiveState new_state);
    void start_turn();
    void run_turn_generation();
    void handle_barge_in();

    InferenceEngine* engine_;
    LiveSessionConfig config_;

    std::unique_ptr<Vad> vad_;
    StateCallback state_cb_;
    AudioOutputCallback audio_cb_;

    std::atomic<LiveState> state_{LiveState::Idle};
    std::atomic<bool> is_active_{false};
    std::atomic<bool> barge_in_requested_{false};

    mutable std::mutex state_mutex_;
    mutable std::mutex audio_buf_mutex_;
    std::vector<int16_t> user_audio_buffer_;

    std::mutex frame_mutex_;
    std::vector<uint8_t> latest_camera_frame_;

    std::chrono::steady_clock::time_point t_eos_;
    std::chrono::steady_clock::time_point t_first_audio_;
    std::atomic<double> last_latency_ms_{0.0};
    std::atomic<bool> measured_latency_for_turn_{false};

    std::thread turn_thread_;
    std::atomic<bool> turn_thread_running_{false};

    int barge_in_loud_frames_ = 0;
};

} // namespace finn
