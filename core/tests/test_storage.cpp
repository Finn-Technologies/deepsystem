#include <gtest/gtest.h>
#include "finn/storage.h"

TEST(StorageTest, InitAndClose) {
    finn::ConversationStorage storage;
    EXPECT_FALSE(storage.is_open());

    EXPECT_TRUE(storage.init(":memory:"));
    EXPECT_TRUE(storage.is_open());

    storage.close();
    EXPECT_FALSE(storage.is_open());
}

TEST(StorageTest, ConversationCRUD) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::Conversation conv1;
    conv1.id = "conv-100";
    conv1.title = "Test Chat 1";

    EXPECT_TRUE(storage.create_conversation(conv1));

    finn::Conversation retrieved;
    EXPECT_TRUE(storage.get_conversation("conv-100", &retrieved));
    EXPECT_EQ(retrieved.id, "conv-100");
    EXPECT_EQ(retrieved.title, "Test Chat 1");

    // Update title
    EXPECT_TRUE(storage.update_conversation_title("conv-100", "Updated Chat 1"));
    EXPECT_TRUE(storage.get_conversation("conv-100", &retrieved));
    EXPECT_EQ(retrieved.title, "Updated Chat 1");

    // List conversations
    finn::Conversation conv2;
    conv2.id = "conv-200";
    conv2.title = "Second Chat";
    EXPECT_TRUE(storage.create_conversation(conv2));

    auto list = storage.list_conversations();
    EXPECT_EQ(list.size(), 2);

    // Delete conversation
    EXPECT_TRUE(storage.delete_conversation("conv-100"));
    EXPECT_FALSE(storage.get_conversation("conv-100", &retrieved));
    EXPECT_EQ(storage.list_conversations().size(), 1);
}

TEST(StorageTest, MessagesManagement) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::Conversation conv;
    conv.id = "c1";
    conv.title = "Chat with messages";
    ASSERT_TRUE(storage.create_conversation(conv));

    finn::ChatMessage msg1;
    msg1.id = "m1";
    msg1.role = finn::MessageRole::User;
    msg1.parts.push_back(finn::InputPart::from_text("Hello Finn!"));
    msg1.timestamp = 1000;
    EXPECT_TRUE(storage.add_message("c1", msg1));

    finn::ChatMessage msg2;
    msg2.id = "m2";
    msg2.role = finn::MessageRole::Assistant;
    msg2.parts.push_back(finn::InputPart::from_text("Hi! How can I help you today?"));
    msg2.timestamp = 1001;
    EXPECT_TRUE(storage.add_message("c1", msg2));

    auto messages = storage.get_messages("c1");
    ASSERT_EQ(messages.size(), 2);
    EXPECT_EQ(messages[0].id, "m1");
    EXPECT_EQ(messages[0].role, finn::MessageRole::User);
    EXPECT_EQ(messages[0].get_text_content(), "Hello Finn!");

    EXPECT_EQ(messages[1].id, "m2");
    EXPECT_EQ(messages[1].role, finn::MessageRole::Assistant);
    EXPECT_EQ(messages[1].get_text_content(), "Hi! How can I help you today?");

    // Get conversation includes messages
    finn::Conversation full_conv;
    EXPECT_TRUE(storage.get_conversation("c1", &full_conv));
    EXPECT_EQ(full_conv.messages.size(), 2);

    // Delete messages
    EXPECT_TRUE(storage.delete_messages("c1"));
    EXPECT_EQ(storage.get_messages("c1").size(), 0);
}

TEST(StorageTest, JsonExport) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::Conversation conv;
    conv.id = "conv_export";
    conv.title = "Export Chat";
    ASSERT_TRUE(storage.create_conversation(conv));

    finn::ChatMessage msg;
    msg.id = "m_exp";
    msg.role = finn::MessageRole::User;
    msg.parts.push_back(finn::InputPart::from_text("Testing JSON export with \"quotes\" & newline\n"));
    ASSERT_TRUE(storage.add_message("conv_export", msg));

    std::string conv_json = storage.export_conversations_json();
    EXPECT_NE(conv_json.find("\"conv_export\""), std::string::npos);
    EXPECT_NE(conv_json.find("\"Export Chat\""), std::string::npos);

    std::string msg_json = storage.export_messages_json("conv_export");
    EXPECT_NE(msg_json.find("\"m_exp\""), std::string::npos);
    EXPECT_NE(msg_json.find("Testing JSON export"), std::string::npos);
}

TEST(StorageTest, NotesCRUDAndPin) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::Note n1;
    n1.id = "note-1";
    n1.title = "Project Roadmap";
    n1.content = "Build on-device assistant with local intelligence.";
    n1.folder = "Work";
    n1.tags = "ai,roadmap,mobile";
    n1.is_pinned = false;
    n1.media_type = "text";
    EXPECT_TRUE(storage.create_note(n1));

    finn::Note n2;
    n2.id = "note-2";
    n2.title = "Grocery List";
    n2.content = "Apples, oats, almond milk, coffee.";
    n2.folder = "Personal";
    n2.tags = "shopping,food";
    n2.is_pinned = true;
    n2.media_type = "text";
    EXPECT_TRUE(storage.create_note(n2));

    // Get note
    finn::Note retrieved;
    EXPECT_TRUE(storage.get_note("note-1", &retrieved));
    EXPECT_EQ(retrieved.title, "Project Roadmap");
    EXPECT_EQ(retrieved.folder, "Work");
    EXPECT_FALSE(retrieved.is_pinned);

    // List notes (pinned first)
    auto list = storage.list_notes();
    ASSERT_EQ(list.size(), 2);
    EXPECT_EQ(list[0].id, "note-2"); // Pinned note first!
    EXPECT_EQ(list[1].id, "note-1");

    // Filter by folder
    auto work_notes = storage.list_notes("Work");
    ASSERT_EQ(work_notes.size(), 1);
    EXPECT_EQ(work_notes[0].id, "note-1");

    // Filter by tag
    auto shopping_notes = storage.list_notes("", "shopping");
    ASSERT_EQ(shopping_notes.size(), 1);
    EXPECT_EQ(shopping_notes[0].id, "note-2");

    // Update note & pin toggle
    retrieved.is_pinned = true;
    retrieved.content = "Updated content for Roadmap.";
    EXPECT_TRUE(storage.update_note(retrieved));

    finn::Note updated;
    EXPECT_TRUE(storage.get_note("note-1", &updated));
    EXPECT_TRUE(updated.is_pinned);
    EXPECT_EQ(updated.content, "Updated content for Roadmap.");

    // Delete note
    EXPECT_TRUE(storage.delete_note("note-2"));
    EXPECT_FALSE(storage.get_note("note-2", &retrieved));
    EXPECT_EQ(storage.list_notes().size(), 1);
}

TEST(StorageTest, NotesFullTextSearchFTS5) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::Note note1;
    note1.id = "n-ai";
    note1.title = "Transformer Attention Mechanics";
    note1.content = "Multi-head query, key, value projection matrices with softmax attention scaling.";
    note1.tags = "research,deeplearning";
    note1.ai_summary = "Key insights into self-attention scaling factor.";
    EXPECT_TRUE(storage.create_note(note1));

    finn::Note note2;
    note2.id = "n-audio";
    note2.title = "Voice Memo 2026-11-05";
    note2.content = "Audio recording from quarterly design sprint.";
    note2.tags = "meeting,audio";
    note2.media_type = "audio";
    note2.media_path = "/data/audio/memo1.wav";
    note2.ai_transcript = "Team agreed on responsive tablet rail navigation and offline mini apps sandbox.";
    EXPECT_TRUE(storage.create_note(note2));

    finn::Note note3;
    note3.id = "n-img";
    note3.title = "Whiteboard Architecture Diagram";
    note3.content = "Photo from meeting.";
    note3.tags = "whiteboard,vision";
    note3.media_type = "image";
    note3.media_path = "/data/images/wb.png";
    note3.ai_summary = "Diagram shows SQLite FTS5 engine communicating via C-ABI to Kotlin repository.";
    EXPECT_TRUE(storage.create_note(note3));

    // Search query in title
    auto res_attn = storage.search_notes("Transformer");
    ASSERT_GE(res_attn.size(), 1);
    EXPECT_EQ(res_attn[0].id, "n-ai");

    // Search query in content
    auto res_matrix = storage.search_notes("projection");
    ASSERT_GE(res_matrix.size(), 1);
    EXPECT_EQ(res_matrix[0].id, "n-ai");

    // Search query in transcript
    auto res_transcript = storage.search_notes("sandbox");
    ASSERT_GE(res_transcript.size(), 1);
    EXPECT_EQ(res_transcript[0].id, "n-audio");

    // Search query in AI summary
    auto res_summary = storage.search_notes("FTS5");
    ASSERT_GE(res_summary.size(), 1);
    EXPECT_EQ(res_summary[0].id, "n-img");

    // Search query with prefix match
    auto res_prefix = storage.search_notes("scal");
    ASSERT_GE(res_prefix.size(), 1);
    EXPECT_EQ(res_prefix[0].id, "n-ai");

    // Search JSON export
    std::string search_json = storage.export_search_notes_json("offline");
    EXPECT_NE(search_json.find("n-audio"), std::string::npos);
}

TEST(StorageTest, MiniAppCRUD) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::MiniApp app;
    app.id = "app-calc";
    app.title = "Scientific Calculator";
    app.description = "Offline scientific calculator with trig functions.";
    app.html_content = "<!DOCTYPE html><html><body><div id='calc'></div></body></html>";
    app.prompt = "Create a scientific calculator mini app";
    app.is_pinned = true;

    EXPECT_TRUE(storage.create_mini_app(app));

    finn::MiniApp retrieved;
    EXPECT_TRUE(storage.get_mini_app("app-calc", &retrieved));
    EXPECT_EQ(retrieved.title, "Scientific Calculator");
    EXPECT_TRUE(retrieved.is_pinned);
    EXPECT_NE(retrieved.html_content.find("<div id='calc'>"), std::string::npos);

    // Update mini app
    retrieved.title = "Pro Scientific Calculator";
    EXPECT_TRUE(storage.update_mini_app(retrieved));
    EXPECT_TRUE(storage.get_mini_app("app-calc", &retrieved));
    EXPECT_EQ(retrieved.title, "Pro Scientific Calculator");

    // List apps
    auto apps = storage.list_mini_apps();
    EXPECT_EQ(apps.size(), 1);

    // Delete mini app
    EXPECT_TRUE(storage.delete_mini_app("app-calc"));
    EXPECT_FALSE(storage.get_mini_app("app-calc", &retrieved));
    EXPECT_EQ(storage.list_mini_apps().size(), 0);
}

TEST(StorageTest, MiniAppScopedStorageIsolation) {
    finn::ConversationStorage storage;
    ASSERT_TRUE(storage.init(":memory:"));

    finn::MiniApp app1;
    app1.id = "app-notes";
    app1.title = "Quick Pad";
    app1.html_content = "<html>...</html>";
    ASSERT_TRUE(storage.create_mini_app(app1));

    finn::MiniApp app2;
    app2.id = "app-todo";
    app2.title = "Task List";
    app2.html_content = "<html>...</html>";
    ASSERT_TRUE(storage.create_mini_app(app2));

    // App1 sets its own key
    EXPECT_TRUE(storage.set_mini_app_kv("app-notes", "current_draft", "Secret note draft text"));
    // App2 sets the same key name
    EXPECT_TRUE(storage.set_mini_app_kv("app-todo", "current_draft", "Buy milk task"));

    // Verify isolation: App1 gets its own value
    std::string val1;
    EXPECT_TRUE(storage.get_mini_app_kv("app-notes", "current_draft", &val1));
    EXPECT_EQ(val1, "Secret note draft text");

    // App2 gets its own value
    std::string val2;
    EXPECT_TRUE(storage.get_mini_app_kv("app-todo", "current_draft", &val2));
    EXPECT_EQ(val2, "Buy milk task");

    // App1 sets unique key
    EXPECT_TRUE(storage.set_mini_app_kv("app-notes", "theme", "dark"));

    // App2 cannot read App1's unique key
    std::string leak_val;
    EXPECT_FALSE(storage.get_mini_app_kv("app-todo", "theme", &leak_val));

    // List keys for App1
    auto keys1 = storage.list_mini_app_keys("app-notes");
    ASSERT_EQ(keys1.size(), 2);
    EXPECT_EQ(keys1[0], "current_draft");
    EXPECT_EQ(keys1[1], "theme");

    // Delete key for App1
    EXPECT_TRUE(storage.delete_mini_app_kv("app-notes", "theme"));
    EXPECT_FALSE(storage.get_mini_app_kv("app-notes", "theme", &val1));

    // Deleting app-notes cascade-deletes its scoped storage
    EXPECT_TRUE(storage.delete_mini_app("app-notes"));
    EXPECT_FALSE(storage.get_mini_app_kv("app-notes", "current_draft", &val1));

    // App2's storage remains unaffected
    EXPECT_TRUE(storage.get_mini_app_kv("app-todo", "current_draft", &val2));
    EXPECT_EQ(val2, "Buy milk task");
}
