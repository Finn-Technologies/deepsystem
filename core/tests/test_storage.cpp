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
