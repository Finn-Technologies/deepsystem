#pragma once

#include "finn/conversation.h"
#include <string>
#include <vector>
#include <memory>
#include <mutex>

struct sqlite3;

namespace finn {

struct Note {
    std::string id;
    std::string title;
    std::string content;
    std::string folder;
    std::string tags;
    bool is_pinned = false;
    std::string media_path;
    std::string media_type; // "none", "text", "image", "video", "audio"
    std::string ai_summary;
    std::string ai_transcript;
    int64_t created_at = 0;
    int64_t updated_at = 0;
};

struct MiniApp {
    std::string id;
    std::string title;
    std::string description;
    std::string html_content;
    std::string prompt;
    bool is_pinned = false;
    int64_t created_at = 0;
    int64_t updated_at = 0;
};

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

    // Note operations
    bool create_note(const Note& note);
    bool update_note(const Note& note);
    bool delete_note(const std::string& id);
    bool get_note(const std::string& id, Note* out_note);
    std::vector<Note> list_notes(const std::string& folder = "", const std::string& tag = "");
    std::vector<Note> search_notes(const std::string& query);
    std::string export_notes_json(const std::string& folder = "", const std::string& tag = "");
    std::string export_note_json(const std::string& id);
    std::string export_search_notes_json(const std::string& query);

    // Mini App operations
    bool create_mini_app(const MiniApp& app);
    bool update_mini_app(const MiniApp& app);
    bool delete_mini_app(const std::string& id);
    bool get_mini_app(const std::string& id, MiniApp* out_app);
    std::vector<MiniApp> list_mini_apps();
    std::string export_mini_apps_json();
    std::string export_mini_app_json(const std::string& id);

    // Mini App Scoped Key-Value Storage
    bool set_mini_app_kv(const std::string& app_id, const std::string& key, const std::string& value);
    bool get_mini_app_kv(const std::string& app_id, const std::string& key, std::string* out_value);
    bool delete_mini_app_kv(const std::string& app_id, const std::string& key);
    std::vector<std::string> list_mini_app_keys(const std::string& app_id);

private:
    sqlite3* db_ = nullptr;
    mutable std::mutex mutex_;

    bool execute_sql(const std::string& sql);
    bool create_tables();
};

} // namespace finn
