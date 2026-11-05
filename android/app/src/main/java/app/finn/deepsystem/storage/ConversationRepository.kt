package app.finn.deepsystem.storage

import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.model.Conversation

interface ConversationRepository {
    fun init(dbPath: String): Boolean
    fun createConversation(id: String, title: String): Boolean
    fun renameConversation(id: String, newTitle: String): Boolean
    fun deleteConversation(id: String): Boolean
    fun addMessage(convId: String, role: String, content: String): Boolean
    fun listConversations(): List<Conversation>
    fun getMessages(convId: String): List<ChatMessage>
    fun close()
}
