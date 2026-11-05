#include "finn/live_session.h"
#include <iostream>
#include <cstring>

namespace finn {

LiveSession::LiveSession(InferenceEngine* engine, const LiveSessionConfig& config)
    : engine_(engine), config_(config) {
    int hangover_frames = config_.vad_silence_timeout_ms / 20;
    if (hangover_frames < 5) hangover_frames = 5;
    vad_ = std::make_unique<EnergyVad>(config_.vad_energy_threshold, 2, hangover_frames);
}

LiveSession::~LiveSession() {
    stop();
}

void LiveSession::start() {
    is_active_ = true;
    barge_in_requested_ = false;
    barge_in_loud_frames_ = 0;
    if (vad_) vad_->reset();
    set_state(LiveState::Listening);
}

void LiveSession::stop() {
    is_active_ = false;
    barge_in_requested_ = true;

    if (engine_) {
        engine_->cancel();
    }

    if (turn_thread_.joinable()) {
        turn_thread_.join();
    }
    turn_thread_running_ = false;

    set_state(LiveState::Idle);
}

bool LiveSession::is_active() const {
    return is_active_.load();
}

LiveState LiveSession::get_state() const {
    return state_.load();
}

void LiveSession::set_state_callback(StateCallback cb) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    state_cb_ = std::move(cb);
}

void LiveSession::set_audio_output_callback(AudioOutputCallback cb) {
    audio_cb_ = std::move(cb);
}

void LiveSession::set_vad(std::unique_ptr<Vad> vad) {
    vad_ = std::move(vad);
}

double LiveSession::get_last_latency_ms() const {
    return last_latency_ms_.load();
}

void LiveSession::set_state(LiveState new_state) {
    LiveState old_state = state_.exchange(new_state);
    if (old_state != new_state) {
        StateCallback cb;
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            cb = state_cb_;
        }
        if (cb) {
            cb(old_state, new_state);
        }
    }
}

void LiveSession::feed_camera_frame(const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(frame_mutex_);
    if (data && size > 0) {
        latest_camera_frame_.assign(data, data + size);
    } else {
        latest_camera_frame_.clear();
    }
}

void LiveSession::feed_audio(const int16_t* samples, size_t count) {
    if (!is_active_.load() || !samples || count == 0) {
        return;
    }

    if (vad_) {
        vad_->process_frame(samples, count);
    }

    LiveState current = state_.load();

    if (current == LiveState::Idle) {
        if (vad_ && vad_->is_speech_active()) {
            set_state(LiveState::Listening);
            std::lock_guard<std::mutex> lock(audio_buf_mutex_);
            user_audio_buffer_.assign(samples, samples + count);
        }
    } else if (current == LiveState::Listening) {
        {
            std::lock_guard<std::mutex> lock(audio_buf_mutex_);
            user_audio_buffer_.insert(user_audio_buffer_.end(), samples, samples + count);
        }

        auto* evad = dynamic_cast<EnergyVad*>(vad_.get());
        if (evad && evad->just_ended_speech()) {
            trigger_end_of_speech();
        }
    } else if (current == LiveState::Thinking || current == LiveState::Speaking) {
        // Fast barge-in check
        auto* evad = dynamic_cast<EnergyVad*>(vad_.get());
        float rms = evad ? evad->get_last_rms() : 0.0f;

        if (rms >= config_.vad_energy_threshold) {
            barge_in_loud_frames_++;
            // 3 consecutive loud frames (~60ms) triggers barge-in
            if (barge_in_loud_frames_ >= 3) {
                handle_barge_in();
                std::lock_guard<std::mutex> lock(audio_buf_mutex_);
                user_audio_buffer_.assign(samples, samples + count);
            }
        } else {
            barge_in_loud_frames_ = 0;
        }
    }
}

void LiveSession::trigger_barge_in() {
    handle_barge_in();
}

void LiveSession::handle_barge_in() {
    barge_in_requested_ = true;
    barge_in_loud_frames_ = 0;

    if (engine_) {
        engine_->cancel();
    }

    set_state(LiveState::Listening);
}

void LiveSession::trigger_end_of_speech() {
    if (!is_active_.load()) return;

    t_eos_ = std::chrono::steady_clock::now();
    measured_latency_for_turn_ = false;

    set_state(LiveState::Thinking);
    start_turn();
}

void LiveSession::start_turn() {
    if (turn_thread_.joinable()) {
        turn_thread_.join();
    }

    barge_in_requested_ = false;
    turn_thread_running_ = true;
    turn_thread_ = std::thread(&LiveSession::run_turn_generation, this);
}

void LiveSession::run_turn_generation() {
    std::vector<int16_t> audio_copy;
    {
        std::lock_guard<std::mutex> lock(audio_buf_mutex_);
        audio_copy = std::move(user_audio_buffer_);
        user_audio_buffer_.clear();
    }

    std::vector<uint8_t> frame_copy;
    {
        std::lock_guard<std::mutex> lock(frame_mutex_);
        frame_copy = latest_camera_frame_;
    }

    if (!engine_ || !engine_->is_loaded()) {
        turn_thread_running_ = false;
        if (is_active_.load()) set_state(LiveState::Listening);
        return;
    }

    // Build multimodal inputs
    std::vector<InputPart> parts;
    if (!frame_copy.empty()) {
        parts.push_back(InputPart::from_image(frame_copy, "image/jpeg"));
    }

    if (!audio_copy.empty()) {
        std::vector<uint8_t> pcm_bytes(audio_copy.size() * sizeof(int16_t));
        std::memcpy(pcm_bytes.data(), audio_copy.data(), pcm_bytes.size());
        parts.push_back(InputPart::from_audio_pcm16k(pcm_bytes, config_.sample_rate, 1));
    }

    std::string prompt = "<|im_start|>system\n" + config_.system_prompt + "<|im_end|>\n"
                       + "<|im_start|>user\n";
    if (!frame_copy.empty() && !audio_copy.empty()) {
        prompt += "Answer questions about the video using what you see and hear.\n";
    } else if (!audio_copy.empty()) {
        prompt += "Perform ASR in English or respond to what you hear.\n";
    } else {
        prompt += "Hello!\n";
    }
    prompt += "<|im_end|>\n<|im_start|>assistant\n";

    parts.push_back(InputPart::from_text(prompt));

    GenerationConfig gen_cfg;
    gen_cfg.temperature = 0.6f;
    gen_cfg.max_tokens = 256;

    // Sentence chunking callback: as soon as a sentence finishes, synthesize & speak it!
    auto on_sentence = [this](const std::string& sentence) {
        if (barge_in_requested_.load() || !is_active_.load()) return;

        engine_->speak(sentence, [this](const int16_t* pcm, size_t sample_count, int srate) {
            if (barge_in_requested_.load() || !is_active_.load()) {
                return false; // abort immediately on barge-in
            }

            if (!measured_latency_for_turn_.exchange(true)) {
                t_first_audio_ = std::chrono::steady_clock::now();
                double latency = std::chrono::duration<double, std::milli>(t_first_audio_ - t_eos_).count();
                last_latency_ms_.store(latency);
                set_state(LiveState::Speaking);
            }

            if (audio_cb_) {
                return audio_cb_(pcm, sample_count, srate);
            }
            return true;
        }, config_.voice);
    };

    SentenceChunker chunker(on_sentence);

    engine_->generate(parts, gen_cfg, [&chunker, this](const std::string& tok) -> bool {
        if (barge_in_requested_.load() || !is_active_.load()) {
            return false;
        }
        chunker.add_token(tok);
        return true;
    });

    // Flush any trailing sentence tokens
    chunker.flush();

    turn_thread_running_ = false;

    // Return to Listening if we weren't interrupted
    if (!barge_in_requested_.load() && is_active_.load()) {
        set_state(LiveState::Listening);
        if (vad_) vad_->reset();
    }
}

} // namespace finn
