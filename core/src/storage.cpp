#include "finn/storage.h"
#include <sqlite3.h>
#include <iostream>
#include <sstream>
#include <chrono>

namespace finn {

static std::string escape_json_string(const std::string& input) {
    std::ostringstream ss;
    for (char c : input) {
        switch (c) {
            case '"': ss << "\\\""; break;
            case '\\': ss << "\\\\"; break;
            case '\b': ss << "\\b"; break;
            case '\f': ss << "\\f"; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default:
                if ('\x00' <= c && c <= '\x1f') {
                    // Control char
                    ss << "\\u00" << ((c >> 4) < 10 ? (char)('0' + (c >> 4)) : (char)('a' + (c >> 4) - 10))
                                  << ((c & 0x0f) < 10 ? (char)('0' + (c & 0x0f)) : (char)('a' + (c & 0x0f) - 10));
                } else {
                    ss << c;
                }
        }
    }
    return ss.str();
}

ConversationStorage::ConversationStorage() = default;

ConversationStorage::~ConversationStorage() {
    close();
}

bool ConversationStorage::init(const std::string& db_path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (db_) {
        sqlite3_close(db_);
        db_ = nullptr;
    }

    int rc = sqlite3_open(db_path.c_str(), &db_);
    if (rc != SQLITE_OK) {
        if (db_) {
            sqlite3_close(db_);
            db_ = nullptr;
        }
        return false;
    }

    return create_tables();
}

void ConversationStorage::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (db_) {
        sqlite3_close(db_);
        db_ = nullptr;
    }
}

bool ConversationStorage::is_open() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return db_ != nullptr;
}

bool ConversationStorage::execute_sql(const std::string& sql) {
    char* err_msg = nullptr;
    int rc = sqlite3_exec(db_, sql.c_str(), nullptr, nullptr, &err_msg);
    if (rc != SQLITE_OK) {
        if (err_msg) {
            sqlite3_free(err_msg);
        }
        return false;
    }
    return true;
}

bool ConversationStorage::create_tables() {
    const char* schema = 
        "PRAGMA foreign_keys = ON;"
        "CREATE TABLE IF NOT EXISTS conversations ("
        "  id TEXT PRIMARY KEY,"
        "  title TEXT NOT NULL,"
        "  created_at INTEGER NOT NULL,"
        "  updated_at INTEGER NOT NULL"
        ");"
        "CREATE TABLE IF NOT EXISTS messages ("
        "  id TEXT PRIMARY KEY,"
        "  conversation_id TEXT NOT NULL,"
        "  role TEXT NOT NULL,"
        "  content TEXT NOT NULL,"
        "  timestamp INTEGER NOT NULL,"
        "  FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE"
        ");"
        "CREATE TABLE IF NOT EXISTS notes ("
        "  id TEXT PRIMARY KEY,"
        "  title TEXT NOT NULL,"
        "  content TEXT NOT NULL,"
        "  folder TEXT NOT NULL DEFAULT '',"
        "  tags TEXT NOT NULL DEFAULT '',"
        "  is_pinned INTEGER NOT NULL DEFAULT 0,"
        "  media_path TEXT NOT NULL DEFAULT '',"
        "  media_type TEXT NOT NULL DEFAULT '',"
        "  ai_summary TEXT NOT NULL DEFAULT '',"
        "  ai_transcript TEXT NOT NULL DEFAULT '',"
        "  created_at INTEGER NOT NULL,"
        "  updated_at INTEGER NOT NULL"
        ");"
        "CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5("
        "  note_id UNINDEXED,"
        "  title,"
        "  content,"
        "  tags,"
        "  ai_summary,"
        "  ai_transcript"
        ");"
        "CREATE TABLE IF NOT EXISTS mini_apps ("
        "  id TEXT PRIMARY KEY,"
        "  title TEXT NOT NULL,"
        "  description TEXT NOT NULL DEFAULT '',"
        "  html_content TEXT NOT NULL,"
        "  prompt TEXT NOT NULL DEFAULT '',"
        "  is_pinned INTEGER NOT NULL DEFAULT 0,"
        "  created_at INTEGER NOT NULL,"
        "  updated_at INTEGER NOT NULL"
        ");"
        "CREATE TABLE IF NOT EXISTS mini_app_storage ("
        "  app_id TEXT NOT NULL,"
        "  key TEXT NOT NULL,"
        "  value TEXT NOT NULL,"
        "  updated_at INTEGER NOT NULL,"
        "  PRIMARY KEY (app_id, key),"
        "  FOREIGN KEY (app_id) REFERENCES mini_apps(id) ON DELETE CASCADE"
        ");";

    return execute_sql(schema);
}

bool ConversationStorage::create_conversation(const Conversation& conv) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    int64_t created = conv.created_at > 0 ? conv.created_at : now;
    int64_t updated = conv.updated_at > 0 ? conv.updated_at : now;

    const char* sql = "INSERT OR REPLACE INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, conv.id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, conv.title.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 3, created);
    sqlite3_bind_int64(stmt, 4, updated);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    return rc == SQLITE_DONE;
}

bool ConversationStorage::get_conversation(const std::string& id, Conversation* out_conv) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || !out_conv) return false;

    const char* sql = "SELECT id, title, created_at, updated_at FROM conversations WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);

    bool found = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        out_conv->id = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        out_conv->title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        out_conv->created_at = sqlite3_column_int64(stmt, 2);
        out_conv->updated_at = sqlite3_column_int64(stmt, 3);
        found = true;
    }
    sqlite3_finalize(stmt);

    if (found) {
        // Fetch messages without locking mutex again (already locked)
        const char* msql = "SELECT id, role, content, timestamp FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC;";
        sqlite3_stmt* mstmt = nullptr;
        if (sqlite3_prepare_v2(db_, msql, -1, &mstmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_text(mstmt, 1, id.c_str(), -1, SQLITE_STATIC);
            out_conv->messages.clear();
            while (sqlite3_step(mstmt) == SQLITE_ROW) {
                ChatMessage msg;
                msg.id = reinterpret_cast<const char*>(sqlite3_column_text(mstmt, 0));
                msg.role = string_to_role(reinterpret_cast<const char*>(sqlite3_column_text(mstmt, 1)));
                std::string content = reinterpret_cast<const char*>(sqlite3_column_text(mstmt, 2));
                msg.parts.push_back(InputPart::from_text(content));
                msg.timestamp = sqlite3_column_int64(mstmt, 3);
                out_conv->messages.push_back(msg);
            }
            sqlite3_finalize(mstmt);
        }
    }

    return found;
}

bool ConversationStorage::update_conversation_title(const std::string& id, const std::string& new_title) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    const char* sql = "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, new_title.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 2, now);
    sqlite3_bind_text(stmt, 3, id.c_str(), -1, SQLITE_STATIC);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

bool ConversationStorage::delete_conversation(const std::string& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    // Delete messages first
    const char* msql = "DELETE FROM messages WHERE conversation_id = ?;";
    sqlite3_stmt* mstmt = nullptr;
    if (sqlite3_prepare_v2(db_, msql, -1, &mstmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(mstmt, 1, id.c_str(), -1, SQLITE_STATIC);
        sqlite3_step(mstmt);
        sqlite3_finalize(mstmt);
    }

    const char* sql = "DELETE FROM conversations WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

std::vector<Conversation> ConversationStorage::list_conversations() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Conversation> result;
    if (!db_) return result;

    const char* sql = "SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return result;
    }

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        Conversation conv;
        conv.id = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        conv.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        conv.created_at = sqlite3_column_int64(stmt, 2);
        conv.updated_at = sqlite3_column_int64(stmt, 3);
        result.push_back(conv);
    }
    sqlite3_finalize(stmt);
    return result;
}

bool ConversationStorage::add_message(const std::string& conv_id, const ChatMessage& msg) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    int64_t ts = msg.timestamp > 0 ? msg.timestamp : now;

    std::string msg_id = msg.id;
    if (msg_id.empty()) {
        msg_id = conv_id + "_" + std::to_string(ts) + "_" + std::to_string(rand() % 10000);
    }

    const char* sql = "INSERT INTO messages (id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    std::string role_str = role_to_string(msg.role);
    std::string content = msg.get_text_content();

    sqlite3_bind_text(stmt, 1, msg_id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, conv_id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 3, role_str.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 4, content.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 5, ts);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    if (rc == SQLITE_DONE) {
        // Update conversation timestamp
        const char* usql = "UPDATE conversations SET updated_at = ? WHERE id = ?;";
        sqlite3_stmt* ustmt = nullptr;
        if (sqlite3_prepare_v2(db_, usql, -1, &ustmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int64(ustmt, 1, now);
            sqlite3_bind_text(ustmt, 2, conv_id.c_str(), -1, SQLITE_STATIC);
            sqlite3_step(ustmt);
            sqlite3_finalize(ustmt);
        }
        return true;
    }

    return false;
}

std::vector<ChatMessage> ConversationStorage::get_messages(const std::string& conv_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<ChatMessage> result;
    if (!db_) return result;

    const char* sql = "SELECT id, role, content, timestamp FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return result;
    }

    sqlite3_bind_text(stmt, 1, conv_id.c_str(), -1, SQLITE_STATIC);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        ChatMessage msg;
        msg.id = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        msg.role = string_to_role(reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)));
        std::string content = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
        msg.parts.push_back(InputPart::from_text(content));
        msg.timestamp = sqlite3_column_int64(stmt, 3);
        result.push_back(msg);
    }
    sqlite3_finalize(stmt);
    return result;
}

bool ConversationStorage::delete_messages(const std::string& conv_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    const char* sql = "DELETE FROM messages WHERE conversation_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, conv_id.c_str(), -1, SQLITE_STATIC);
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

std::string ConversationStorage::export_conversations_json() {
    auto list = list_conversations();
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < list.size(); ++i) {
        if (i > 0) ss << ",";
        ss << "{"
           << "\"id\":\"" << escape_json_string(list[i].id) << "\","
           << "\"title\":\"" << escape_json_string(list[i].title) << "\","
           << "\"created_at\":" << list[i].created_at << ","
           << "\"updated_at\":" << list[i].updated_at
           << "}";
    }
    ss << "]";
    return ss.str();
}

std::string ConversationStorage::export_messages_json(const std::string& conv_id) {
    auto msgs = get_messages(conv_id);
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < msgs.size(); ++i) {
        if (i > 0) ss << ",";
        ss << "{"
           << "\"id\":\"" << escape_json_string(msgs[i].id) << "\","
           << "\"role\":\"" << role_to_string(msgs[i].role) << "\","
           << "\"content\":\"" << escape_json_string(msgs[i].get_text_content()) << "\","
           << "\"timestamp\":" << msgs[i].timestamp
           << "}";
    }
    ss << "]";
    return ss.str();
}

static std::string note_to_json(const Note& note) {
    std::ostringstream ss;
    ss << "{"
       << "\"id\":\"" << escape_json_string(note.id) << "\","
       << "\"title\":\"" << escape_json_string(note.title) << "\","
       << "\"content\":\"" << escape_json_string(note.content) << "\","
       << "\"folder\":\"" << escape_json_string(note.folder) << "\","
       << "\"tags\":\"" << escape_json_string(note.tags) << "\","
       << "\"is_pinned\":" << (note.is_pinned ? "true" : "false") << ","
       << "\"media_path\":\"" << escape_json_string(note.media_path) << "\","
       << "\"media_type\":\"" << escape_json_string(note.media_type) << "\","
       << "\"ai_summary\":\"" << escape_json_string(note.ai_summary) << "\","
       << "\"ai_transcript\":\"" << escape_json_string(note.ai_transcript) << "\","
       << "\"created_at\":" << note.created_at << ","
       << "\"updated_at\":" << note.updated_at
       << "}";
    return ss.str();
}

static std::string mini_app_to_json(const MiniApp& app) {
    std::ostringstream ss;
    ss << "{"
       << "\"id\":\"" << escape_json_string(app.id) << "\","
       << "\"title\":\"" << escape_json_string(app.title) << "\","
       << "\"description\":\"" << escape_json_string(app.description) << "\","
       << "\"html_content\":\"" << escape_json_string(app.html_content) << "\","
       << "\"prompt\":\"" << escape_json_string(app.prompt) << "\","
       << "\"is_pinned\":" << (app.is_pinned ? "true" : "false") << ","
       << "\"created_at\":" << app.created_at << ","
       << "\"updated_at\":" << app.updated_at
       << "}";
    return ss.str();
}

static std::string sanitize_fts_query(const std::string& raw) {
    std::ostringstream ss;
    std::string token;
    for (char c : raw) {
        if (std::isalnum(static_cast<unsigned char>(c))) {
            token += c;
        } else {
            if (!token.empty()) {
                if (ss.tellp() > 0) ss << " ";
                ss << "\"" << token << "\"*";
                token.clear();
            }
        }
    }
    if (!token.empty()) {
        if (ss.tellp() > 0) ss << " ";
        ss << "\"" << token << "\"*";
    }
    return ss.str();
}

static Note parse_note_row(sqlite3_stmt* stmt) {
    Note note;
    note.id = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
    note.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
    note.content = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
    note.folder = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
    note.tags = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
    note.is_pinned = sqlite3_column_int(stmt, 5) != 0;
    note.media_path = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 6));
    note.media_type = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7));
    note.ai_summary = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 8));
    note.ai_transcript = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9));
    note.created_at = sqlite3_column_int64(stmt, 10);
    note.updated_at = sqlite3_column_int64(stmt, 11);
    return note;
}

bool ConversationStorage::create_note(const Note& note) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    int64_t created = note.created_at > 0 ? note.created_at : now;
    int64_t updated = note.updated_at > 0 ? note.updated_at : now;

    const char* sql = "INSERT OR REPLACE INTO notes "
        "(id, title, content, folder, tags, is_pinned, media_path, media_type, ai_summary, ai_transcript, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, note.id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, note.title.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 3, note.content.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 4, note.folder.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 5, note.tags.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int(stmt, 6, note.is_pinned ? 1 : 0);
    sqlite3_bind_text(stmt, 7, note.media_path.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 8, note.media_type.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 9, note.ai_summary.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 10, note.ai_transcript.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 11, created);
    sqlite3_bind_int64(stmt, 12, updated);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    if (rc != SQLITE_DONE) return false;

    // Sync to FTS5 table
    const char* dsql = "DELETE FROM notes_fts WHERE note_id = ?;";
    sqlite3_stmt* dstmt = nullptr;
    if (sqlite3_prepare_v2(db_, dsql, -1, &dstmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(dstmt, 1, note.id.c_str(), -1, SQLITE_STATIC);
        sqlite3_step(dstmt);
        sqlite3_finalize(dstmt);
    }

    const char* fsql = "INSERT INTO notes_fts (note_id, title, content, tags, ai_summary, ai_transcript) VALUES (?, ?, ?, ?, ?, ?);";
    sqlite3_stmt* fstmt = nullptr;
    if (sqlite3_prepare_v2(db_, fsql, -1, &fstmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(fstmt, 1, note.id.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(fstmt, 2, note.title.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(fstmt, 3, note.content.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(fstmt, 4, note.tags.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(fstmt, 5, note.ai_summary.c_str(), -1, SQLITE_STATIC);
        sqlite3_bind_text(fstmt, 6, note.ai_transcript.c_str(), -1, SQLITE_STATIC);
        sqlite3_step(fstmt);
        sqlite3_finalize(fstmt);
    }

    return true;
}

bool ConversationStorage::update_note(const Note& note) {
    Note updated_note = note;
    updated_note.updated_at = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return create_note(updated_note);
}

bool ConversationStorage::delete_note(const std::string& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    const char* dsql = "DELETE FROM notes_fts WHERE note_id = ?;";
    sqlite3_stmt* dstmt = nullptr;
    if (sqlite3_prepare_v2(db_, dsql, -1, &dstmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(dstmt, 1, id.c_str(), -1, SQLITE_STATIC);
        sqlite3_step(dstmt);
        sqlite3_finalize(dstmt);
    }

    const char* sql = "DELETE FROM notes WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

bool ConversationStorage::get_note(const std::string& id, Note* out_note) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || !out_note) return false;

    const char* sql = "SELECT id, title, content, folder, tags, is_pinned, media_path, media_type, ai_summary, ai_transcript, created_at, updated_at "
                      "FROM notes WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);
    bool found = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        *out_note = parse_note_row(stmt);
        found = true;
    }
    sqlite3_finalize(stmt);
    return found;
}

std::vector<Note> ConversationStorage::list_notes(const std::string& folder, const std::string& tag) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Note> results;
    if (!db_) return results;

    std::string sql = "SELECT id, title, content, folder, tags, is_pinned, media_path, media_type, ai_summary, ai_transcript, created_at, updated_at FROM notes WHERE 1=1";
    if (!folder.empty()) {
        sql += " AND folder = ?";
    }
    if (!tag.empty()) {
        sql += " AND tags LIKE ?";
    }
    sql += " ORDER BY is_pinned DESC, updated_at DESC;";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &stmt, nullptr) != SQLITE_OK) {
        return results;
    }

    int param_idx = 1;
    if (!folder.empty()) {
        sqlite3_bind_text(stmt, param_idx++, folder.c_str(), -1, SQLITE_STATIC);
    }
    std::string tag_pattern = "%" + tag + "%";
    if (!tag.empty()) {
        sqlite3_bind_text(stmt, param_idx++, tag_pattern.c_str(), -1, SQLITE_STATIC);
    }

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        results.push_back(parse_note_row(stmt));
    }
    sqlite3_finalize(stmt);
    return results;
}

std::vector<Note> ConversationStorage::search_notes(const std::string& query) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Note> results;
    if (!db_) return results;

    if (query.empty()) {
        const char* sql = "SELECT id, title, content, folder, tags, is_pinned, media_path, media_type, ai_summary, ai_transcript, created_at, updated_at "
                          "FROM notes ORDER BY is_pinned DESC, updated_at DESC;";
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) == SQLITE_OK) {
            while (sqlite3_step(stmt) == SQLITE_ROW) {
                results.push_back(parse_note_row(stmt));
            }
            sqlite3_finalize(stmt);
        }
        return results;
    }

    std::string fts_q = sanitize_fts_query(query);
    bool fts_success = false;

    if (!fts_q.empty()) {
        const char* fts_sql = 
            "SELECT n.id, n.title, n.content, n.folder, n.tags, n.is_pinned, n.media_path, n.media_type, n.ai_summary, n.ai_transcript, n.created_at, n.updated_at "
            "FROM notes n "
            "JOIN (SELECT note_id, rank FROM notes_fts WHERE notes_fts MATCH ? ORDER BY rank) f ON n.id = f.note_id "
            "ORDER BY n.is_pinned DESC, f.rank ASC, n.updated_at DESC;";
        
        sqlite3_stmt* stmt = nullptr;
        if (sqlite3_prepare_v2(db_, fts_sql, -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_text(stmt, 1, fts_q.c_str(), -1, SQLITE_STATIC);
            while (sqlite3_step(stmt) == SQLITE_ROW) {
                results.push_back(parse_note_row(stmt));
            }
            sqlite3_finalize(stmt);
            fts_success = true;
        }
    }

    // Fallback to LIKE search if FTS yielded no results or was bypassed
    if (results.empty()) {
        const char* like_sql = 
            "SELECT id, title, content, folder, tags, is_pinned, media_path, media_type, ai_summary, ai_transcript, created_at, updated_at "
            "FROM notes "
            "WHERE title LIKE ? OR content LIKE ? OR tags LIKE ? OR ai_summary LIKE ? OR ai_transcript LIKE ? "
            "ORDER BY is_pinned DESC, updated_at DESC;";
        sqlite3_stmt* lstmt = nullptr;
        if (sqlite3_prepare_v2(db_, like_sql, -1, &lstmt, nullptr) == SQLITE_OK) {
            std::string pat = "%" + query + "%";
            for (int i = 1; i <= 5; ++i) {
                sqlite3_bind_text(lstmt, i, pat.c_str(), -1, SQLITE_STATIC);
            }
            while (sqlite3_step(lstmt) == SQLITE_ROW) {
                results.push_back(parse_note_row(lstmt));
            }
            sqlite3_finalize(lstmt);
        }
    }

    return results;
}

std::string ConversationStorage::export_notes_json(const std::string& folder, const std::string& tag) {
    auto notes = list_notes(folder, tag);
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < notes.size(); ++i) {
        if (i > 0) ss << ",";
        ss << note_to_json(notes[i]);
    }
    ss << "]";
    return ss.str();
}

std::string ConversationStorage::export_note_json(const std::string& id) {
    Note note;
    if (get_note(id, &note)) {
        return note_to_json(note);
    }
    return "{}";
}

std::string ConversationStorage::export_search_notes_json(const std::string& query) {
    auto notes = search_notes(query);
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < notes.size(); ++i) {
        if (i > 0) ss << ",";
        ss << note_to_json(notes[i]);
    }
    ss << "]";
    return ss.str();
}

/* Mini App Operations */

static MiniApp parse_mini_app_row(sqlite3_stmt* stmt) {
    MiniApp app;
    app.id = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
    app.title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
    app.description = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
    app.html_content = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3));
    app.prompt = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4));
    app.is_pinned = sqlite3_column_int(stmt, 5) != 0;
    app.created_at = sqlite3_column_int64(stmt, 6);
    app.updated_at = sqlite3_column_int64(stmt, 7);
    return app;
}

bool ConversationStorage::create_mini_app(const MiniApp& app) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    int64_t created = app.created_at > 0 ? app.created_at : now;
    int64_t updated = app.updated_at > 0 ? app.updated_at : now;

    const char* sql = "INSERT OR REPLACE INTO mini_apps "
        "(id, title, description, html_content, prompt, is_pinned, created_at, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, app.id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, app.title.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 3, app.description.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 4, app.html_content.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 5, app.prompt.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int(stmt, 6, app.is_pinned ? 1 : 0);
    sqlite3_bind_int64(stmt, 7, created);
    sqlite3_bind_int64(stmt, 8, updated);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

bool ConversationStorage::update_mini_app(const MiniApp& app) {
    MiniApp updated = app;
    updated.updated_at = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return create_mini_app(updated);
}

bool ConversationStorage::delete_mini_app(const std::string& id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_) return false;

    // Delete scoped KV storage first
    const char* ksql = "DELETE FROM mini_app_storage WHERE app_id = ?;";
    sqlite3_stmt* kstmt = nullptr;
    if (sqlite3_prepare_v2(db_, ksql, -1, &kstmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(kstmt, 1, id.c_str(), -1, SQLITE_STATIC);
        sqlite3_step(kstmt);
        sqlite3_finalize(kstmt);
    }

    const char* sql = "DELETE FROM mini_apps WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);
    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

bool ConversationStorage::get_mini_app(const std::string& id, MiniApp* out_app) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || !out_app) return false;

    const char* sql = "SELECT id, title, description, html_content, prompt, is_pinned, created_at, updated_at "
                      "FROM mini_apps WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, id.c_str(), -1, SQLITE_STATIC);
    bool found = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        *out_app = parse_mini_app_row(stmt);
        found = true;
    }
    sqlite3_finalize(stmt);
    return found;
}

std::vector<MiniApp> ConversationStorage::list_mini_apps() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<MiniApp> results;
    if (!db_) return results;

    const char* sql = "SELECT id, title, description, html_content, prompt, is_pinned, created_at, updated_at "
                      "FROM mini_apps ORDER BY is_pinned DESC, updated_at DESC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return results;
    }

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        results.push_back(parse_mini_app_row(stmt));
    }
    sqlite3_finalize(stmt);
    return results;
}

std::string ConversationStorage::export_mini_apps_json() {
    auto apps = list_mini_apps();
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < apps.size(); ++i) {
        if (i > 0) ss << ",";
        ss << mini_app_to_json(apps[i]);
    }
    ss << "]";
    return ss.str();
}

std::string ConversationStorage::export_mini_app_json(const std::string& id) {
    MiniApp app;
    if (get_mini_app(id, &app)) {
        return mini_app_to_json(app);
    }
    return "{}";
}

/* Scoped Mini App Key-Value Storage */

bool ConversationStorage::set_mini_app_kv(const std::string& app_id, const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || app_id.empty() || key.empty()) return false;

    int64_t now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    const char* sql = "INSERT OR REPLACE INTO mini_app_storage (app_id, key, value, updated_at) VALUES (?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, app_id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, key.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 3, value.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_int64(stmt, 4, now);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

bool ConversationStorage::get_mini_app_kv(const std::string& app_id, const std::string& key, std::string* out_value) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || !out_value || app_id.empty() || key.empty()) return false;

    const char* sql = "SELECT value FROM mini_app_storage WHERE app_id = ? AND key = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, app_id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, key.c_str(), -1, SQLITE_STATIC);

    bool found = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        *out_value = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        found = true;
    }
    sqlite3_finalize(stmt);
    return found;
}

bool ConversationStorage::delete_mini_app_kv(const std::string& app_id, const std::string& key) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!db_ || app_id.empty() || key.empty()) return false;

    const char* sql = "DELETE FROM mini_app_storage WHERE app_id = ? AND key = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return false;
    }

    sqlite3_bind_text(stmt, 1, app_id.c_str(), -1, SQLITE_STATIC);
    sqlite3_bind_text(stmt, 2, key.c_str(), -1, SQLITE_STATIC);

    int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return rc == SQLITE_DONE;
}

std::vector<std::string> ConversationStorage::list_mini_app_keys(const std::string& app_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::string> keys;
    if (!db_ || app_id.empty()) return keys;

    const char* sql = "SELECT key FROM mini_app_storage WHERE app_id = ? ORDER BY key ASC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &stmt, nullptr) != SQLITE_OK) {
        return keys;
    }

    sqlite3_bind_text(stmt, 1, app_id.c_str(), -1, SQLITE_STATIC);
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        keys.push_back(reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0)));
    }
    sqlite3_finalize(stmt);
    return keys;
}

} // namespace finn
