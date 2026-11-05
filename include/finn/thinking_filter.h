#pragma once

#include <string>
#include <functional>
#include <regex>

namespace finn {

class ThinkingFilter {
public:
    using TokenCallback = std::function<bool(const std::string&)>;

    explicit ThinkingFilter(TokenCallback downstream_cb, bool strip_reasoning = true)
        : cb_(downstream_cb), strip_reasoning_(strip_reasoning) {}

    bool on_token(const std::string& token) {
        if (!strip_reasoning_) {
            return cb_ ? cb_(token) : true;
        }

        if (resolved_) {
            if (strip_leading_ws_) {
                size_t first_non_ws = token.find_first_not_of(" \t\r\n");
                if (first_non_ws == std::string::npos) {
                    return true; // Still leading whitespace after </think>
                }
                strip_leading_ws_ = false;
                std::string stripped_tok = token.substr(first_non_ws);
                return cb_ ? cb_(stripped_tok) : true;
            }
            // Already finished handling initial thinking block, stream directly
            return cb_ ? cb_(token) : true;
        }

        buffer_ += token;

        // If inside thinking block, wait for closing </think>
        if (in_think_block_) {
            size_t close_pos = buffer_.find("</think>");
            if (close_pos != std::string::npos) {
                // Found close tag, consume up to </think> and any trailing newlines/spaces
                size_t after_tag = close_pos + 8; // length of "</think>"
                while (after_tag < buffer_.size() && 
                       (buffer_[after_tag] == '\r' || buffer_[after_tag] == '\n' || buffer_[after_tag] == ' ')) {
                    after_tag++;
                }

                std::string remainder = buffer_.substr(after_tag);
                buffer_.clear();
                in_think_block_ = false;
                resolved_ = true;
                strip_leading_ws_ = remainder.empty();

                if (!remainder.empty()) {
                    size_t non_ws = remainder.find_first_not_of(" \t\r\n");
                    if (non_ws != std::string::npos) {
                        strip_leading_ws_ = false;
                        if (cb_) return cb_(remainder.substr(non_ws));
                    }
                }
                return true;
            }
            // Still inside <think>...</think>, suppress
            return true;
        }

        // We have not resolved whether there is an opening <think>
        // Check leading whitespace
        std::string trimmed = buffer_;
        size_t first_non_ws = trimmed.find_first_not_of(" \t\r\n");
        if (first_non_ws == std::string::npos) {
            // Only whitespace so far, wait for more tokens
            return true;
        }
        trimmed = trimmed.substr(first_non_ws);

        const std::string target = "<think>";
        if (trimmed.rfind(target, 0) == 0) {
            // Starts with <think>!
            in_think_block_ = true;
            // Check if </think> is already in buffer
            size_t close_pos = buffer_.find("</think>");
            if (close_pos != std::string::npos) {
                size_t after_tag = close_pos + 8;
                while (after_tag < buffer_.size() && 
                       (buffer_[after_tag] == '\r' || buffer_[after_tag] == '\n' || buffer_[after_tag] == ' ')) {
                    after_tag++;
                }
                std::string remainder = buffer_.substr(after_tag);
                buffer_.clear();
                in_think_block_ = false;
                resolved_ = true;
                strip_leading_ws_ = remainder.empty();
                if (!remainder.empty()) {
                    size_t non_ws = remainder.find_first_not_of(" \t\r\n");
                    if (non_ws != std::string::npos) {
                        strip_leading_ws_ = false;
                        if (cb_) return cb_(remainder.substr(non_ws));
                    }
                }
                return true;
            }
            return true;
        } else if (trimmed.size() < target.size() && target.rfind(trimmed, 0) == 0) {
            // Partial match of "<think>", wait for more tokens to be certain
            return true;
        } else {
            // Does not start with <think>, flush buffer and mark resolved
            resolved_ = true;
            std::string to_emit = buffer_;
            buffer_.clear();
            return cb_ ? cb_(to_emit) : true;
        }
    }

    // Static helper to clean static full strings
    static std::string strip(const std::string& text) {
        static const std::regex think_regex(R"(<think>[\s\S]*?<\/think>[\r\n]*)");
        return std::regex_replace(text, think_regex, "");
    }

private:
    TokenCallback cb_;
    bool strip_reasoning_;
    bool in_think_block_ = false;
    bool resolved_ = false;
    bool strip_leading_ws_ = false;
    std::string buffer_;
};

} // namespace finn
