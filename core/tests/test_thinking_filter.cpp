#include <gtest/gtest.h>
#include "finn/thinking_filter.h"
#include <vector>
#include <string>

TEST(ThinkingFilterTest, SuppressEmptyThinkingTags) {
    std::string collected;
    finn::ThinkingFilter filter([&](const std::string& tok) {
        collected += tok;
        return true;
    }, true);

    std::vector<std::string> tokens = {
        "<think>", "</think>", "Quantum", " computing", " is", " fast."
    };

    for (const auto& tok : tokens) {
        filter.on_token(tok);
    }

    EXPECT_EQ(collected, "Quantum computing is fast.");
}

TEST(ThinkingFilterTest, SuppressReasoningThoughtsContent) {
    std::string collected;
    finn::ThinkingFilter filter([&](const std::string& tok) {
        collected += tok;
        return true;
    }, true);

    std::vector<std::string> tokens = {
        "<think>", "\n", "Analyzing", " the", " user's", " question", "...", "\n", "</think>", "\n\n",
        "Here", " is", " the", " direct", " answer."
    };

    for (const auto& tok : tokens) {
        filter.on_token(tok);
    }

    EXPECT_EQ(collected, "Here is the direct answer.");
}

TEST(ThinkingFilterTest, PassThroughNormalOutputWithoutThinking) {
    std::string collected;
    finn::ThinkingFilter filter([&](const std::string& tok) {
        collected += tok;
        return true;
    }, true);

    std::vector<std::string> tokens = {
        "Hello", "!", " I", " am", " Finn", "."
    };

    for (const auto& tok : tokens) {
        filter.on_token(tok);
    }

    EXPECT_EQ(collected, "Hello! I am Finn.");
}

TEST(ThinkingFilterTest, PartialChunkedTags) {
    std::string collected;
    finn::ThinkingFilter filter([&](const std::string& tok) {
        collected += tok;
        return true;
    }, true);

    std::vector<std::string> tokens = {
        "<", "think", ">", "<", "/", "think", ">", "Result"
    };

    for (const auto& tok : tokens) {
        filter.on_token(tok);
    }

    EXPECT_EQ(collected, "Result");
}

TEST(ThinkingFilterTest, StaticStripHelper) {
    std::string input = "<think>internal reasoning</think>Direct response.";
    std::string stripped = finn::ThinkingFilter::strip(input);
    EXPECT_EQ(stripped, "Direct response.");

    std::string empty_think = "<think></think>No reasoning here.";
    EXPECT_EQ(finn::ThinkingFilter::strip(empty_think), "No reasoning here.");
}
