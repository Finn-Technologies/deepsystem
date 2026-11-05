#pragma once

#include "finn/inference_engine.h"
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
#include <string>

namespace finn {

class FakeEngine : public InferenceEngine {
public:
    FakeEngine() = default;
    ~FakeEngine() override = default;

    bool load(const EngineLoadConfig& config) override {
        config_ = config;
        is_loaded_ = true;
        cancelled_ = false;
        return true;
    }

    void unload() override {
        is_loaded_ = false;
        cancelled_ = false;
    }

    bool is_loaded() const override {
        return is_loaded_;
    }

    void set_mock_tokens(const std::vector<std::string>& tokens) {
        mock_tokens_ = tokens;
    }

    void set_delay_per_token_ms(int ms) {
        delay_per_token_ms_ = ms;
    }

    bool generate(
        const std::vector<InputPart>& parts,
        const GenerationConfig& config,
        TokenCallback token_cb,
        EngineStats* stats = nullptr
    ) override {
        if (!is_loaded_) return false;

        cancelled_ = false;
        generation_count_++;
        last_parts_ = parts;

        auto start_time = std::chrono::steady_clock::now();
        bool first_token = true;
        int token_count = 0;

        std::vector<std::string> tokens = mock_tokens_.empty() ?
            std::vector<std::string>{"Hello", " ", "world", "!", " ", "This", " ", "is", " ", "Finn", "."} :
            mock_tokens_;

        for (const auto& tok : tokens) {
            if (cancelled_.load()) {
                break;
            }

            if (delay_per_token_ms_ > 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(delay_per_token_ms_));
            }

            if (cancelled_.load()) {
                break;
            }

            if (first_token && stats) {
                auto now = std::chrono::steady_clock::now();
                stats->ttft_ms = std::chrono::duration<double, std::milli>(now - start_time).count();
                first_token = false;
            }

            token_count++;
            if (token_cb) {
                bool keep_going = token_cb(tok);
                if (!keep_going) {
                    cancelled_ = true;
                    break;
                }
            }

            if (token_count >= config.max_tokens) {
                break;
            }
        }

        auto end_time = std::chrono::steady_clock::now();
        double total_ms = std::chrono::duration<double, std::milli>(end_time - start_time).count();

        if (stats) {
            stats->completion_tokens = token_count;
            stats->total_time_ms = total_ms;
            if (total_ms > 0) {
                stats->tokens_per_second = (token_count * 1000.0) / total_ms;
            }
        }

        return !cancelled_.load();
    }

    void cancel() override {
        cancelled_ = true;
    }

    bool is_cancelled() const override {
        return cancelled_.load();
    }

    bool speak(
        const std::string& text,
        AudioCallback audio_cb,
        const std::string& /*voice*/ = ""
    ) override {
        if (!is_loaded_) return false;
        cancelled_ = false;

        // Generate synthetic sine wave audio (PCM 16k mono) for testing audio callbacks
        const int sample_rate = 16000;
        const size_t chunk_samples = 320; // 20ms of audio
        std::vector<int16_t> buffer(chunk_samples, 0);

        for (size_t i = 0; i < chunk_samples; ++i) {
            buffer[i] = static_cast<int16_t>(1000 * ((i % 40 < 20) ? 1 : -1));
        }

        if (audio_cb) {
            return audio_cb(buffer.data(), buffer.size(), sample_rate);
        }
        return true;
    }

    EngineLoadConfig get_config() const override {
        return config_;
    }

    int get_generation_count() const {
        return generation_count_;
    }

    const std::vector<InputPart>& get_last_parts() const {
        return last_parts_;
    }

private:
    std::atomic<bool> is_loaded_{false};
    std::atomic<bool> cancelled_{false};
    EngineLoadConfig config_;
    std::vector<std::string> mock_tokens_;
    int delay_per_token_ms_ = 0;
    int generation_count_ = 0;
    std::vector<InputPart> last_parts_;
};

} // namespace finn
