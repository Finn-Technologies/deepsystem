#include <gtest/gtest.h>
#include "finn/live_session.h"
#include "finn/fake_engine.h"
#include "finn/vad.h"
#include "finn/sentence_chunker.h"

#include <cmath>
#include <vector>
#include <chrono>
#include <thread>
#include <atomic>

// Helper to generate 20ms frames at 16kHz (320 samples)
static std::vector<int16_t> make_frame(float amplitude) {
    std::vector<int16_t> frame(320);
    for (size_t i = 0; i < frame.size(); ++i) {
        frame[i] = static_cast<int16_t>(amplitude * std::sin(2.0 * M_PI * 440.0 * i / 16000.0));
    }
    return frame;
}

TEST(VadTest, EnergyVadSilenceVsSpeech) {
    finn::EnergyVad vad(400.0f, 2, 5); // threshold 400, onset 2, hangover 5 frames

    auto silence = make_frame(50.0f);  // RMS ~35
    auto speech = make_frame(2000.0f); // RMS ~1414

    // Feed silence
    for (int i = 0; i < 5; ++i) {
        vad.process_frame(silence.data(), silence.size());
        EXPECT_FALSE(vad.is_speech_active());
    }

    // Feed 1 speech frame (onset requires 2)
    vad.process_frame(speech.data(), speech.size());
    EXPECT_FALSE(vad.is_speech_active());

    // Feed 2nd speech frame -> now active
    vad.process_frame(speech.data(), speech.size());
    EXPECT_TRUE(vad.is_speech_active());
    EXPECT_TRUE(vad.just_started_speech());

    // Feed 3rd speech frame -> stays active
    vad.process_frame(speech.data(), speech.size());
    EXPECT_TRUE(vad.is_speech_active());
    EXPECT_FALSE(vad.just_started_speech());

    // Feed 4 silence frames (hangover is 5)
    for (int i = 0; i < 4; ++i) {
        vad.process_frame(silence.data(), silence.size());
        EXPECT_TRUE(vad.is_speech_active());
    }

    // 5th silence frame -> speech ends
    vad.process_frame(silence.data(), silence.size());
    EXPECT_FALSE(vad.is_speech_active());
    EXPECT_TRUE(vad.just_ended_speech());
}

TEST(SentenceChunkerTest, SplitsOnPunctuation) {
    std::vector<std::string> sentences;
    finn::SentenceChunker chunker([&](const std::string& s) {
        sentences.push_back(s);
    });

    chunker.add_token("Hello");
    chunker.add_token(" there");
    chunker.add_token("!");
    chunker.add_token(" How");
    chunker.add_token(" are");
    chunker.add_token(" you");
    chunker.add_token(" today");
    chunker.add_token("?");
    chunker.flush();

    ASSERT_EQ(sentences.size(), 2);
    EXPECT_EQ(sentences[0], "Hello there!");
    EXPECT_EQ(sentences[1], "How are you today?");
}

TEST(LiveSessionTest, TurnTakingLifecycleAndLatency) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig load_cfg;
    load_cfg.model_path = "mock.gguf";
    ASSERT_TRUE(engine.load(load_cfg));

    std::vector<std::string> mock_tokens = {
        "I", " hear", " you", " loud", " and", " clear", "!", " How", " can", " I", " help", "?"
    };
    engine.set_mock_tokens(mock_tokens);
    engine.set_delay_per_token_ms(5);

    finn::LiveSessionConfig cfg;
    cfg.vad_energy_threshold = 400.0f;
    cfg.vad_silence_timeout_ms = 100; // 5 frames = 100ms for fast test

    finn::LiveSession session(&engine, cfg);

    std::vector<finn::LiveState> state_history;
    session.set_state_callback([&](finn::LiveState old_s, finn::LiveState new_s) {
        state_history.push_back(new_s);
    });

    std::atomic<int> audio_chunks_received{0};
    session.set_audio_output_callback([&](const int16_t* pcm, size_t count, int rate) {
        audio_chunks_received++;
        return true;
    });

    session.start();
    EXPECT_EQ(session.get_state(), finn::LiveState::Listening);

    // Feed user speech (3 frames)
    auto speech = make_frame(2500.0f);
    for (int i = 0; i < 3; ++i) {
        session.feed_audio(speech.data(), speech.size());
    }

    // Feed silence frames to trigger end of speech (6 frames > hangover)
    auto silence = make_frame(30.0f);
    for (int i = 0; i < 6; ++i) {
        session.feed_audio(silence.data(), silence.size());
    }

    // Wait for generation & speak to complete
    int retries = 0;
    while (session.get_state() != finn::LiveState::Listening && retries++ < 50) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    EXPECT_GT(audio_chunks_received.load(), 0);
    EXPECT_GT(session.get_last_latency_ms(), 0.0);
    EXPECT_LT(session.get_last_latency_ms(), 2000.0);

    session.stop();
    EXPECT_EQ(session.get_state(), finn::LiveState::Idle);
}

TEST(LiveSessionTest, BargeInInterruption) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig load_cfg;
    load_cfg.model_path = "mock.gguf";
    ASSERT_TRUE(engine.load(load_cfg));

    // Create a long generation
    std::vector<std::string> long_tokens;
    for (int i = 0; i < 50; ++i) {
        long_tokens.push_back("word" + std::to_string(i) + " ");
    }
    long_tokens.push_back(". More words.");
    engine.set_mock_tokens(long_tokens);
    engine.set_delay_per_token_ms(20);

    finn::LiveSessionConfig cfg;
    cfg.vad_energy_threshold = 400.0f;
    cfg.vad_silence_timeout_ms = 80;

    finn::LiveSession session(&engine, cfg);

    std::atomic<bool> entered_thinking_or_speaking{false};
    session.set_state_callback([&](finn::LiveState old_s, finn::LiveState new_s) {
        if (new_s == finn::LiveState::Thinking || new_s == finn::LiveState::Speaking) {
            entered_thinking_or_speaking = true;
        }
    });

    session.start();

    // Trigger end of speech to enter Thinking
    session.trigger_end_of_speech();

    // Wait until it enters thinking/speaking
    int retries = 0;
    while (!entered_thinking_or_speaking.load() && retries++ < 30) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    // Now feed loud barge-in speech frames (3 frames)
    auto loud = make_frame(3000.0f);
    auto t_start_barge = std::chrono::steady_clock::now();
    session.feed_audio(loud.data(), loud.size());
    session.feed_audio(loud.data(), loud.size());
    session.feed_audio(loud.data(), loud.size());
    auto t_end_barge = std::chrono::steady_clock::now();

    double barge_ms = std::chrono::duration<double, std::milli>(t_end_barge - t_start_barge).count();

    // Should immediately be Listening!
    EXPECT_EQ(session.get_state(), finn::LiveState::Listening);
    EXPECT_TRUE(engine.is_cancelled());
    // Barge-in latency must be well under 200ms
    EXPECT_LT(barge_ms, 200.0);

    session.stop();
}

TEST(LiveSessionTest, CameraFrameAttachedToTurn) {
    finn::FakeEngine engine;
    finn::EngineLoadConfig load_cfg;
    ASSERT_TRUE(engine.load(load_cfg));

    finn::LiveSessionConfig cfg;
    finn::LiveSession session(&engine, cfg);

    session.start();

    // Feed camera frame
    std::vector<uint8_t> mock_jpeg = {0xFF, 0xD8, 0xFF, 0xE0, 0x01, 0x02};
    session.feed_camera_frame(mock_jpeg.data(), mock_jpeg.size());

    // Trigger turn
    session.trigger_end_of_speech();

    // Wait for turn to process
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    // Verify FakeEngine got image part
    const auto& last_parts = engine.get_last_parts();
    bool found_image = false;
    for (const auto& p : last_parts) {
        if (p.type == finn::InputPartType::Image) {
            found_image = true;
            EXPECT_EQ(p.image.data.size(), mock_jpeg.size());
        }
    }
    EXPECT_TRUE(found_image);

    session.stop();
}
