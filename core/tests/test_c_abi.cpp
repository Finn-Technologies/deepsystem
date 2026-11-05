#include <gtest/gtest.h>
#include "finn/finn_c.h"
#include "finn/conversation.h"
#include <string>

TEST(CAbiTest, EngineLifecycleAndGeneration) {
    finn_engine_t* engine = finn_engine_create_fake();
    ASSERT_NE(engine, nullptr);
    EXPECT_EQ(finn_engine_is_loaded(engine), 0);

    finn_engine_config_t cfg{};
    cfg.model_path = "mock.gguf";
    cfg.n_ctx = 1024;
    cfg.n_threads = 2;

    EXPECT_EQ(finn_engine_load(engine, &cfg), 0);
    EXPECT_EQ(finn_engine_is_loaded(engine), 1);

    std::string output;
    finn_token_callback_fn cb = [](const char* token, void* user_data) -> int {
        auto* str = static_cast<std::string*>(user_data);
        *str += token;
        return 0; // continue
    };

    finn_engine_stats_t stats{};
    int res = finn_engine_generate_text(engine, "Hello fake", 0.7f, 50, cb, &output, &stats);
    EXPECT_EQ(res, 0);
    EXPECT_FALSE(output.empty());
    EXPECT_GT(stats.completion_tokens, 0);

    // Test cancellation via callback
    std::string partial;
    int tok_idx = 0;
    auto cancel_cb = [](const char* token, void* user_data) -> int {
        int* idx = static_cast<int*>(user_data);
        (*idx)++;
        if (*idx == 2) return 1; // cancel
        return 0;
    };

    res = finn_engine_generate_text(engine, "Hello cancel", 0.7f, 50, cancel_cb, &tok_idx, nullptr);
    EXPECT_EQ(res, -1);
    EXPECT_EQ(finn_engine_is_cancelled(engine), 1);

    finn_engine_unload(engine);
    EXPECT_EQ(finn_engine_is_loaded(engine), 0);

    finn_engine_destroy(engine);
}

TEST(CAbiTest, StorageCInterface) {
    finn_storage_t* storage = finn_storage_create();
    ASSERT_NE(storage, nullptr);

    EXPECT_EQ(finn_storage_open(storage, ":memory:"), 0);

    EXPECT_EQ(finn_storage_create_conversation(storage, "c1", "C ABI Chat"), 0);
    EXPECT_EQ(finn_storage_add_message(storage, "c1", "user", "Message from C"), 0);
    EXPECT_EQ(finn_storage_add_message(storage, "c1", "assistant", "Response from C"), 0);

    char* conv_json = finn_storage_list_conversations_json(storage);
    ASSERT_NE(conv_json, nullptr);
    std::string s_conv(conv_json);
    EXPECT_NE(s_conv.find("C ABI Chat"), std::string::npos);
    finn_free_string(conv_json);

    char* msg_json = finn_storage_get_messages_json(storage, "c1");
    ASSERT_NE(msg_json, nullptr);
    std::string s_msg(msg_json);
    EXPECT_NE(s_msg.find("Message from C"), std::string::npos);
    EXPECT_NE(s_msg.find("Response from C"), std::string::npos);
    finn_free_string(msg_json);

    finn_storage_close(storage);
    finn_storage_destroy(storage);
}

TEST(CAbiTest, MultiTurnChatMLTemplateAssembly) {
    std::vector<finn::ChatMessage> history;

    finn::ChatMessage msg1;
    msg1.role = finn::MessageRole::User;
    msg1.parts.push_back(finn::InputPart::from_text("Hello Finn"));
    history.push_back(msg1);

    finn::ChatMessage msg2;
    msg2.role = finn::MessageRole::Assistant;
    msg2.parts.push_back(finn::InputPart::from_text("Hi! How can I assist you?"));
    history.push_back(msg2);

    finn::ChatMessage msg3;
    msg3.role = finn::MessageRole::User;
    msg3.parts.push_back(finn::InputPart::from_text("What is 2+2?"));
    history.push_back(msg3);

    std::string prompt = finn::ChatTemplate::format(history, "You are Finn.");

    // Verify system turn
    EXPECT_NE(prompt.find("<|im_start|>system\nYou are Finn.<|im_end|>\n"), std::string::npos);

    // Verify user turn 1
    EXPECT_NE(prompt.find("<|im_start|>user\nHello Finn<|im_end|>\n"), std::string::npos);

    // Verify assistant turn 1
    EXPECT_NE(prompt.find("<|im_start|>assistant\nHi! How can I assist you?<|im_end|>\n"), std::string::npos);

    // Verify user turn 2 (no duplicate of turn 1)
    EXPECT_NE(prompt.find("<|im_start|>user\nWhat is 2+2?<|im_end|>\n"), std::string::npos);

    // Verify prompt ends cleanly with assistant invitation
    EXPECT_EQ(prompt.substr(prompt.size() - 22), "<|im_start|>assistant\n");

    // Verify passing to finn_engine_generate_text does not re-wrap or corrupt
    finn_engine_t* engine = finn_engine_create_fake();
    ASSERT_NE(engine, nullptr);
    finn_engine_config_t cfg{};
    cfg.model_path = "mock.gguf";
    EXPECT_EQ(finn_engine_load(engine, &cfg), 0);

    std::string out;
    int res = finn_engine_generate_text(engine, prompt.c_str(), 0.7f, 32, [](const char* tok, void* u) {
        auto* s = static_cast<std::string*>(u);
        *s += tok;
        return 0;
    }, &out, nullptr);

    EXPECT_EQ(res, 0);
    EXPECT_FALSE(out.empty());

    finn_engine_unload(engine);
    finn_engine_destroy(engine);
}

TEST(CAbiTest, SpeakAndVideoAndLiveCInterface) {
    finn_engine_t* engine = finn_engine_create_fake();
    ASSERT_NE(engine, nullptr);
    finn_engine_config_t cfg{};
    cfg.model_path = "mock.gguf";
    EXPECT_EQ(finn_engine_load(engine, &cfg), 0);

    // Test Speak
    bool audio_received = false;
    auto audio_cb = [](const int16_t* pcm, size_t count, int rate, void* user_data) -> int {
        auto* flag = static_cast<bool*>(user_data);
        if (pcm && count > 0) {
            *flag = true;
        }
        return 0; // continue
    };

    int speak_res = finn_engine_speak(engine, "Hello from C ABI", "female", audio_cb, &audio_received);
    EXPECT_EQ(speak_res, 0);
    EXPECT_TRUE(audio_received);

    // Test Video Generation
    std::vector<uint8_t> frame1 = {0x01, 0x02};
    std::vector<uint8_t> frame2 = {0x03, 0x04};
    const uint8_t* frames[2] = {frame1.data(), frame2.data()};
    size_t sizes[2] = {frame1.size(), frame2.size()};
    std::vector<uint8_t> fake_audio(640, 0);

    std::string vid_out;
    int vid_res = finn_engine_generate_video(
        engine,
        "Describe this clip",
        frames,
        sizes,
        2,
        fake_audio.data(),
        fake_audio.size(),
        0.7f,
        32,
        [](const char* tok, void* u) {
            auto* s = static_cast<std::string*>(u);
            *s += tok;
            return 0;
        },
        &vid_out,
        nullptr
    );
    EXPECT_EQ(vid_res, 0);
    EXPECT_FALSE(vid_out.empty());

    // Test Live Session C interface
    finn_live_config_t live_cfg{};
    live_cfg.vad_energy_threshold = 400.0f;
    live_cfg.vad_silence_timeout_ms = 100;
    live_cfg.voice = "female";

    finn_live_session_t* live = finn_live_create(engine, &live_cfg);
    ASSERT_NE(live, nullptr);

    EXPECT_EQ(finn_live_get_state(live), FINN_LIVE_STATE_IDLE);
    finn_live_start(live);
    EXPECT_EQ(finn_live_get_state(live), FINN_LIVE_STATE_LISTENING);

    // Feed audio
    std::vector<int16_t> audio_chunk(320, 100);
    finn_live_feed_audio(live, audio_chunk.data(), audio_chunk.size());

    // Trigger end of speech
    finn_live_trigger_end_of_speech(live);
    EXPECT_EQ(finn_live_get_state(live), FINN_LIVE_STATE_THINKING);

    // Trigger barge-in
    finn_live_trigger_barge_in(live);
    EXPECT_EQ(finn_live_get_state(live), FINN_LIVE_STATE_LISTENING);

    finn_live_stop(live);
    EXPECT_EQ(finn_live_get_state(live), FINN_LIVE_STATE_IDLE);

    finn_live_destroy(live);
    finn_engine_unload(engine);
    finn_engine_destroy(engine);
}
