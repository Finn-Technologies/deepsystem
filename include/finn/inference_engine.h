#pragma once

#include "finn/types.h"
#include <vector>
#include <string>

namespace finn {

class InferenceEngine {
public:
    virtual ~InferenceEngine() = default;

    // Load model weights and optional multimodal projection
    virtual bool load(const EngineLoadConfig& config) = 0;

    // Unload active model and free resources
    virtual void unload() = 0;

    // Returns whether an engine is currently loaded
    virtual bool is_loaded() const = 0;

    // Generate output tokens from multimodal input parts with streamed callback
    virtual bool generate(
        const std::vector<InputPart>& parts,
        const GenerationConfig& config,
        TokenCallback token_cb,
        EngineStats* stats = nullptr
    ) = 0;

    // Cancel an in-flight generation or speak operation
    virtual void cancel() = 0;

    // Returns whether cancellation was requested
    virtual bool is_cancelled() const = 0;

    // Stream spoken audio (TTS) for the given text.
    // AudioCallback receives PCM audio chunks.
    virtual bool speak(
        const std::string& text,
        AudioCallback audio_cb,
        const std::string& voice = ""
    ) = 0;

    // Get current engine configuration
    virtual EngineLoadConfig get_config() const = 0;
};

} // namespace finn
