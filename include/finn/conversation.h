#pragma once

#include "finn/types.h"
#include <string>
#include <vector>
#include <sstream>

namespace finn {

struct Conversation {
    std::string id;
    std::string title;
    int64_t created_at = 0;
    int64_t updated_at = 0;
    std::vector<ChatMessage> messages;
};

class ChatTemplate {
public:
    // Format conversation messages into a prompt string for LLM generation
    static std::string format(const std::vector<ChatMessage>& messages, const std::string& system_prompt = "") {
        std::ostringstream ss;

        if (!system_prompt.empty()) {
            ss << "<|im_start|>system\n" << system_prompt << "<|im_end|>\n";
        }

        for (const auto& msg : messages) {
            std::string role_str = role_to_string(msg.role);
            ss << "<|im_start|>" << role_str << "\n";
            for (const auto& part : msg.parts) {
                if (part.type == InputPartType::Image || part.type == InputPartType::AudioPcm16k) {
                    ss << "<__media__>\n";
                }
            }
            ss << msg.get_text_content();
            ss << "<|im_end|>\n";
        }

        ss << "<|im_start|>assistant\n";
        return ss.str();
    }

    // Build multimodal input parts sequence from messages (appending multimodal attachments)
    static std::vector<InputPart> build_multimodal_inputs(const std::vector<ChatMessage>& messages, const std::string& system_prompt = "") {
        std::vector<InputPart> parts;
        // Text header and prompt
        std::string prompt_text = format(messages, system_prompt);
        parts.push_back(InputPart::from_text(prompt_text));

        // Append attachments from the latest user message
        for (auto it = messages.rbegin(); it != messages.rend(); ++it) {
            if (it->role == MessageRole::User) {
                for (const auto& part : it->parts) {
                    if (part.type != InputPartType::Text) {
                        parts.push_back(part);
                    }
                }
                break;
            }
        }
        return parts;
    }
};

} // namespace finn
