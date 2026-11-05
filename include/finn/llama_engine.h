#pragma once

#include "finn/inference_engine.h"
#include <memory>
#include <atomic>
#include <mutex>
#include <string>

struct llama_model;
struct llama_context;
struct llama_vocab;
struct mtmd_context;

namespace finn {

class LlamaEngine : public InferenceEngine {
public:
    LlamaEngine();
    ~LlamaEngine() override;

    bool load(const EngineLoadConfig& config) override;
    void unload() override;
    bool is_loaded() const override;

    bool generate(
        const std::vector<InputPart>& parts,
        const GenerationConfig& config,
        TokenCallback token_cb,
        EngineStats* stats = nullptr
    ) override;

    void cancel() override;
    bool is_cancelled() const override;

    bool speak(
        const std::string& text,
        AudioCallback audio_cb,
        const std::string& voice = ""
    ) override;

    EngineLoadConfig get_config() const override;

private:
    mutable std::mutex mutex_;
    std::atomic<bool> is_loaded_{false};
    std::atomic<bool> is_cancelled_{false};

    EngineLoadConfig config_;
    llama_model* model_ = nullptr;
    llama_context* ctx_ = nullptr;
    const llama_vocab* vocab_ = nullptr;
    mtmd_context* mctx_ = nullptr;

    void cleanup_handles();
};

} // namespace finn
