#pragma once

#include <string>
#include <vector>
#include <functional>
#include <cctype>

namespace finn {

class SentenceChunker {
public:
    using ChunkCallback = std::function<void(const std::string& sentence)>;

    explicit SentenceChunker(ChunkCallback cb) : callback_(std::move(cb)) {}

    void add_token(const std::string& token) {
        buffer_ += token;
        check_sentence_boundary();
    }

    void flush() {
        if (!buffer_.empty()) {
            std::string remaining = trim(buffer_);
            if (!remaining.empty() && callback_) {
                callback_(remaining);
            }
            buffer_.clear();
        }
    }

    void reset() {
        buffer_.clear();
    }

private:
    ChunkCallback callback_;
    std::string buffer_;

    static std::string trim(const std::string& s) {
        size_t first = s.find_first_not_of(" \t\n\r");
        if (first == std::string::npos) return "";
        size_t last = s.find_last_not_of(" \t\n\r");
        return s.substr(first, (last - first + 1));
    }

    void check_sentence_boundary() {
        // Look for end-of-sentence delimiters: '.', '!', '?', '\n'
        for (size_t i = 0; i < buffer_.size(); ++i) {
            char c = buffer_[i];
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                // If followed by space or newline, or end of significant chunk (>12 chars)
                if (i + 1 < buffer_.size() && (std::isspace(static_cast<unsigned char>(buffer_[i + 1])) != 0)) {
                    std::string sentence = trim(buffer_.substr(0, i + 1));
                    buffer_ = buffer_.substr(i + 1);
                    if (!sentence.empty() && callback_) {
                        callback_(sentence);
                    }
                    i = 0;
                    continue;
                }
            }
            // Fallback: If buffer gets long (>100 chars) and has a comma, semicolon, or colon followed by space
            if (buffer_.size() > 100 && (c == ',' || c == ';' || c == ':')) {
                if (i + 1 < buffer_.size() && std::isspace(static_cast<unsigned char>(buffer_[i + 1])) != 0) {
                    std::string chunk = trim(buffer_.substr(0, i + 1));
                    buffer_ = buffer_.substr(i + 1);
                    if (!chunk.empty() && callback_) {
                        callback_(chunk);
                    }
                    i = 0;
                    continue;
                }
            }
        }
    }
};

} // namespace finn
