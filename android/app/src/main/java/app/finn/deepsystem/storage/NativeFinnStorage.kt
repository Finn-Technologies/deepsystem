package app.finn.deepsystem.storage

import android.util.Log
import app.finn.deepsystem.engine.NativeFinnBridge
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.model.Conversation
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class NativeFinnStorage : ConversationRepository {
    private val lock = Any()
    private var handle: Long = 0
    private var usingFallback = false

    // Fallback in-memory storage for unit tests / when native lib is absent
    private val fallbackConversations = ConcurrentHashMap<String, Conversation>()
    private val fallbackMessages = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()

    init {
        if (NativeFinnBridge.isNativeLoaded()) {
            try {
                handle = NativeFinnBridge.nativeStorageCreate()
            } catch (t: Throwable) {
                Log.w(TAG, "Native storage create failed, using fallback: ${t.message}")
                usingFallback = true
            }
        } else {
            usingFallback = true
        }
    }

    override fun init(dbPath: String): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            usingFallback = true
            return true
        }
        val res = NativeFinnBridge.nativeStorageOpen(handle, dbPath)
        if (res != 0) {
            Log.w(TAG, "Failed to open native SQLite db at $dbPath, falling back to in-memory")
            usingFallback = true
        }
        return true
    }

    override fun createConversation(id: String, title: String): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            fallbackConversations[id] = Conversation(id, title)
            return true
        }
        val res = NativeFinnBridge.nativeStorageCreateConversation(handle, id, title)
        return res == 0
    }

    override fun deleteConversation(id: String): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            fallbackConversations.remove(id)
            fallbackMessages.remove(id)
            return true
        }
        val res = NativeFinnBridge.nativeStorageDeleteConversation(handle, id)
        return res == 0
    }

    override fun addMessage(convId: String, role: String, content: String): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            val list = fallbackMessages.getOrPut(convId) { CopyOnWriteArrayList() }
            list.add(ChatMessage(role = role, content = content))
            return true
        }
        val res = NativeFinnBridge.nativeStorageAddMessage(handle, convId, role, content)
        return res == 0
    }

    override fun listConversations(): List<Conversation> = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            return fallbackConversations.values.sortedByDescending { it.updatedAt }
        }
        val json = NativeFinnBridge.nativeStorageListConversationsJson(handle) ?: return emptyList()
        val result = mutableListOf<Conversation>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    Conversation(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing conversations JSON: ${t.message}")
        }
        return result
    }

    override fun getMessages(convId: String): List<ChatMessage> = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            return fallbackMessages[convId]?.toList() ?: emptyList()
        }
        val json = NativeFinnBridge.nativeStorageGetMessagesJson(handle, convId) ?: return emptyList()
        val result = mutableListOf<ChatMessage>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    ChatMessage(
                        id = obj.optString("id"),
                        role = obj.getString("role"),
                        content = obj.getString("content"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing messages JSON: ${t.message}")
        }
        return result
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeStorageClose(handle)
            NativeFinnBridge.nativeStorageDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "NativeFinnStorage"
    }
}
