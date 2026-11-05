#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>
#include <memory>

namespace finn {

enum class InputPartType {
    Text,
    Image,
    AudioPcm16k,
    VideoFrames
};

struct ImagePart {
    std::vector<uint8_t> data;
    std::string mime_type; // e.g., "image/jpeg", "image/png"
    int width = 0;
    int height = 0;
};

struct AudioPart {
    // 16kHz mono PCM 16-bit
    std::vector<uint8_t> pcm_data;
    int sample_rate = 16000;
    int channels = 1;
};

struct VideoFrame {
    std::vector<uint8_t> data;
    int64_t timestamp_ms = 0;
};

struct InputPart {
    InputPartType type = InputPartType::Text;
    std::string text;
    ImagePart image;
    AudioPart audio;
    std::vector<VideoFrame> video_frames;

    static InputPart from_text(const std::string& t) {
        InputPart p;
        p.type = InputPartType::Text;
        p.text = t;
        return p;
    }

    static InputPart from_image(const std::vector<uint8_t>& bytes, const std::string& mime = "image/jpeg") {
        InputPart p;
        p.type = InputPartType::Image;
        p.image.data = bytes;
        p.image.mime_type = mime;
        return p;
    }

    static InputPart from_audio_pcm16k(const std::vector<uint8_t>& pcm, int sample_rate = 16000, int channels = 1) {
        InputPart p;
        p.type = InputPartType::AudioPcm16k;
        p.audio.pcm_data = pcm;
        p.audio.sample_rate = sample_rate;
        p.audio.channels = channels;
        return p;
    }

    static InputPart from_video_frames(const std::vector<VideoFrame>& frames) {
        InputPart p;
        p.type = InputPartType::VideoFrames;
        p.video_frames = frames;
        return p;
    }
};

enum class MessageRole {
    System,
    User,
    Assistant,
    Tool
};

inline std::string role_to_string(MessageRole role) {
    switch (role) {
        case MessageRole::System: return "system";
        case MessageRole::User: return "user";
        case MessageRole::Assistant: return "assistant";
        case MessageRole::Tool: return "tool";
    }
    return "user";
}

inline MessageRole string_to_role(const std::string& str) {
    if (str == "system") return MessageRole::System;
    if (str == "assistant") return MessageRole::Assistant;
    if (str == "tool") return MessageRole::Tool;
    return MessageRole::User;
}

struct ChatMessage {
    std::string id;
    MessageRole role = MessageRole::User;
    std::vector<InputPart> parts;
    int64_t timestamp = 0;

    std::string get_text_content() const {
        std::string result;
        for (const auto& part : parts) {
            if (part.type == InputPartType::Text) {
                result += part.text;
            }
        }
        return result;
    }
};

struct GenerationConfig {
    float temperature = 0.7f;
    float top_p = 0.9f;
    int top_k = 40;
    int max_tokens = 512;
    std::vector<std::string> stop_sequences;
};

struct EngineLoadConfig {
    std::string model_path;
    std::string mmproj_path;
    int n_ctx = 4096;
    int n_threads = 4;
    int n_gpu_layers = 99; // GPU offload default
    bool use_mmap = true;
    bool use_mlock = false;
};

struct EngineStats {
    double ttft_ms = 0.0;             // Time to first token (milliseconds)
    double tokens_per_second = 0.0;   // Generation speed (tokens/sec)
    int prompt_tokens = 0;
    int completion_tokens = 0;
    double total_time_ms = 0.0;
};

// Token callback returns false to request early cancellation
using TokenCallback = std::function<bool(const std::string& token_text)>;

// Audio callback receives PCM samples stream, returns false to cancel
using AudioCallback = std::function<bool(const int16_t* pcm_samples, size_t sample_count, int sample_rate)>;

} // namespace finn
