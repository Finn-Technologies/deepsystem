#include <gtest/gtest.h>
#include "finn/fake_engine.h"
#include <thread>
#include <chrono>

TEST(FakeEngineTest, LoadAndUnload) {
    finn::FakeEngine engine;
    EXPECT_FALSE(engine.is_loaded());

    finn::EngineLoadConfig cfg;
    cfg.model_path = "mock_model.gguf";
    cfg.n_ctx = 2048;

    EXPECT_TRUE(engine.load(cfg));
    EXPECT_TRUE(engine.is_loaded());
    EXPECT_EQ(engine.get_config().model_path, "mock_model.gguf");
    EXPECT_EQ(engine.get_config().n_ctx, 2048);

    engine.unload();
    EXPECT_FALSE(engine.is_loaded());
}

TEST(FakeEngineTest, TextGenerationStreaming) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig cfg;
    cfg.model_path = "mock.gguf";
    ASSERT_TRUE(engine.load(cfg));

    std::vector<std::string> mock_tokens = {"The", " ", "quick", " ", "brown", " ", "fox"};
    engine.set_mock_tokens(mock_tokens);

    std::vector<finn::InputPart> parts = {finn::InputPart::from_text("Say the fox phrase")};
    finn::GenerationConfig gen_cfg;
    gen_cfg.max_tokens = 50;

    std::string accumulated;
    finn::EngineStats stats;

    bool result = engine.generate(parts, gen_cfg, [&](const std::string& tok) {
        accumulated += tok;
        return true;
    }, &stats);

    EXPECT_TRUE(result);
    EXPECT_EQ(accumulated, "The quick brown fox");
    EXPECT_EQ(stats.completion_tokens, 7);
    EXPECT_GT(stats.total_time_ms, 0.0);
}

TEST(FakeEngineTest, CancellationViaCallback) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig cfg;
    ASSERT_TRUE(engine.load(cfg));

    std::vector<std::string> tokens = {"1", "2", "3", "4", "5", "6", "7", "8"};
    engine.set_mock_tokens(tokens);

    std::vector<finn::InputPart> parts = {finn::InputPart::from_text("Count to 8")};
    finn::GenerationConfig gen_cfg;

    int received_count = 0;
    bool result = engine.generate(parts, gen_cfg, [&](const std::string& /*tok*/) {
        received_count++;
        // Cancel after 3 tokens
        if (received_count == 3) {
            return false;
        }
        return true;
    });

    EXPECT_FALSE(result);
    EXPECT_TRUE(engine.is_cancelled());
    EXPECT_EQ(received_count, 3);
}

TEST(FakeEngineTest, AsyncCancellation) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig cfg;
    ASSERT_TRUE(engine.load(cfg));

    std::vector<std::string> tokens;
    for (int i = 0; i < 100; ++i) {
        tokens.push_back("tok" + std::to_string(i) + " ");
    }
    engine.set_mock_tokens(tokens);
    engine.set_delay_per_token_ms(20); // 20ms per token

    std::vector<finn::InputPart> parts = {finn::InputPart::from_text("Generate long sequence")};
    finn::GenerationConfig gen_cfg;
    gen_cfg.max_tokens = 100;

    int count = 0;
    std::thread gen_thread([&]() {
        engine.generate(parts, gen_cfg, [&](const std::string& /*tok*/) {
            count++;
            return true;
        });
    });

    // Let it generate a few tokens then cancel asynchronously
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    engine.cancel();

    gen_thread.join();

    EXPECT_TRUE(engine.is_cancelled());
    EXPECT_GT(count, 0);
    EXPECT_LT(count, 50);
}

TEST(FakeEngineTest, MultimodalInputs) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig cfg;
    ASSERT_TRUE(engine.load(cfg));

    std::vector<uint8_t> fake_img = {0xFF, 0xD8, 0xFF, 0xE0}; // JPEG magic
    std::vector<uint8_t> fake_pcm(3200, 0); // 100ms 16kHz PCM
    std::vector<finn::VideoFrame> frames = {
        { {1, 2, 3}, 0 },
        { {4, 5, 6}, 33 }
    };

    std::vector<finn::InputPart> parts = {
        finn::InputPart::from_text("Look at this image and audio:"),
        finn::InputPart::from_image(fake_img, "image/jpeg"),
        finn::InputPart::from_audio_pcm16k(fake_pcm, 16000, 1),
        finn::InputPart::from_video_frames(frames)
    };

    finn::GenerationConfig gen_cfg;
    bool ok = engine.generate(parts, gen_cfg, [](const std::string&) { return true; });

    EXPECT_TRUE(ok);
    EXPECT_EQ(engine.get_last_parts().size(), 4);
    EXPECT_EQ(engine.get_last_parts()[1].type, finn::InputPartType::Image);
    EXPECT_EQ(engine.get_last_parts()[2].type, finn::InputPartType::AudioPcm16k);
    EXPECT_EQ(engine.get_last_parts()[3].type, finn::InputPartType::VideoFrames);
}

TEST(FakeEngineTest, SpeakCallback) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig cfg;
    ASSERT_TRUE(engine.load(cfg));

    bool received_audio = false;
    size_t samples_received = 0;
    int sample_rate_received = 0;

    bool ok = engine.speak("Hello", [&](const int16_t* samples, size_t count, int rate) {
        if (samples && count > 0) {
            received_audio = true;
            samples_received = count;
            sample_rate_received = rate;
        }
        return true;
    });

    EXPECT_TRUE(ok);
    EXPECT_TRUE(received_audio);
    EXPECT_GT(samples_received, 0);
    EXPECT_EQ(sample_rate_received, 16000);
}
