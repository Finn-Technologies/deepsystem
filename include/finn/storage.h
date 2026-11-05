#pragma once

#include "finn/conversation.h"
#include <string>
#include <vector>
#include <memory>
#include <mutex>

struct sqlite3;

namespace finn {

class ConversationStorage {
public:
    ConversationStorage();
    ~ConversationStorage();

    // Disable copying
    ConversationStorage(const ConversationStorage&) = delete;
    ConversationStorage& operator=(const ConversationStorage&) = delete;

    // Open or create SQLite database at specified file path (or ":memory:")
    bool init(const std::string& db_path);

    // Close database connection
    void close();

    // Check if database is open and ready
    bool is_open() const;

    // Conversation operations
    bool create_conversation(const Conversation& conv);
    bool get_conversation(const std::string& id, Conversation* out_conv);
    bool update_conversation_title(const std::string& id, const std::string& new_title);
    bool delete_conversation(const std::string& id);
    std::vector<Conversation> list_conversations();

    // Message operations
    bool add_message(const std::string& conv_id, const ChatMessage& msg);
    std::vector<ChatMessage> get_messages(const std::string& conv_id);
    bool delete_messages(const std::string& conv_id);

    // Export messages or conversations to JSON string
    std::string export_conversations_json();
    std::string export_messages_json(const std::string& conv_id);

private:
    sqlite3* db_ = nullptr;
    mutable std::mutex mutex_;

    bool execute_sql(const std::string& sql);
    bool create_tables();
};

} // namespace finn
