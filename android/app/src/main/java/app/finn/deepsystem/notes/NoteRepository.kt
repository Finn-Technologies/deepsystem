package app.finn.deepsystem.notes

import android.util.Log
import app.finn.deepsystem.engine.NativeFinnBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

interface NoteRepository {
    fun init(dbPath: String): Boolean
    fun createNote(note: NoteItem): Boolean
    fun updateNote(note: NoteItem): Boolean
    fun deleteNote(id: String): Boolean
    fun getNote(id: String): NoteItem?
    fun listNotes(folder: String? = null, tag: String? = null): List<NoteItem>
    fun searchNotes(query: String): List<NoteItem>
    fun togglePin(id: String): Boolean
    fun getAllFolders(): List<String>
    fun getAllTags(): List<String>
    fun close()
}

class NativeNoteRepository : NoteRepository {
    private val lock = Any()
    private var handle: Long = 0
    private var usingFallback = false

    // In-memory fallback for unit tests and environments without native lib
    private val fallbackNotes = ConcurrentHashMap<String, NoteItem>()

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

    override fun createNote(note: NoteItem): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            fallbackNotes[note.id] = note
            return true
        }
        val tagsStr = note.tags.joinToString(",")
        val res = NativeFinnBridge.nativeStorageCreateNote(
            handle,
            note.id,
            note.title,
            note.content,
            note.folder,
            tagsStr,
            note.isPinned,
            note.mediaPath ?: "",
            note.mediaType,
            note.aiSummary ?: "",
            note.aiTranscript ?: ""
        )
        return res == 0
    }

    override fun updateNote(note: NoteItem): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            fallbackNotes[note.id] = note
            return true
        }
        val tagsStr = note.tags.joinToString(",")
        val res = NativeFinnBridge.nativeStorageUpdateNote(
            handle,
            note.id,
            note.title,
            note.content,
            note.folder,
            tagsStr,
            note.isPinned,
            note.mediaPath ?: "",
            note.mediaType,
            note.aiSummary ?: "",
            note.aiTranscript ?: ""
        )
        return res == 0
    }

    override fun deleteNote(id: String): Boolean = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            fallbackNotes.remove(id)
            return true
        }
        val res = NativeFinnBridge.nativeStorageDeleteNote(handle, id)
        return res == 0
    }

    override fun getNote(id: String): NoteItem? = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            return fallbackNotes[id]
        }
        val json = NativeFinnBridge.nativeStorageGetNoteJson(handle, id) ?: return null
        return parseNoteFromJson(json)
    }

    override fun listNotes(folder: String?, tag: String?): List<NoteItem> = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            var list = fallbackNotes.values.toList()
            if (!folder.isNullOrBlank()) {
                list = list.filter { it.folder.equals(folder, ignoreCase = true) }
            }
            if (!tag.isNullOrBlank()) {
                list = list.filter { it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
            }
            return list.sortedWith(
                compareByDescending<NoteItem> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        }
        val json = NativeFinnBridge.nativeStorageListNotesJson(handle, folder, tag) ?: return emptyList()
        return parseNotesListFromJson(json)
    }

    override fun searchNotes(query: String): List<NoteItem> = synchronized(lock) {
        if (usingFallback || handle == 0L) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return listNotes()
            return fallbackNotes.values.filter { note ->
                note.title.lowercase().contains(q) ||
                note.content.lowercase().contains(q) ||
                note.tags.any { it.lowercase().contains(q) } ||
                (note.aiSummary?.lowercase()?.contains(q) == true) ||
                (note.aiTranscript?.lowercase()?.contains(q) == true)
            }.sortedWith(
                compareByDescending<NoteItem> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        }
        val json = NativeFinnBridge.nativeStorageSearchNotesJson(handle, query) ?: return emptyList()
        return parseNotesListFromJson(json)
    }

    override fun togglePin(id: String): Boolean = synchronized(lock) {
        val note = getNote(id) ?: return false
        val updated = note.copy(isPinned = !note.isPinned, updatedAt = System.currentTimeMillis())
        return updateNote(updated)
    }

    override fun getAllFolders(): List<String> = synchronized(lock) {
        val all = listNotes()
        val folders = all.map { it.folder.trim() }.filter { it.isNotBlank() }.distinct()
        return if (folders.isEmpty()) listOf("General") else folders
    }

    override fun getAllTags(): List<String> = synchronized(lock) {
        val all = listNotes()
        return all.flatMap { it.tags }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeStorageClose(handle)
            NativeFinnBridge.nativeStorageDestroy(handle)
            handle = 0L
        }
    }

    private fun parseNotesListFromJson(json: String): List<NoteItem> {
        val list = mutableListOf<NoteItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                parseNoteObject(obj)?.let { list.add(it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing notes JSON: ${t.message}")
        }
        return list
    }

    private fun parseNoteFromJson(json: String): NoteItem? {
        return try {
            val obj = JSONObject(json)
            parseNoteObject(obj)
        } catch (t: Throwable) {
            Log.e(TAG, "Error parsing note JSON: ${t.message}")
            null
        }
    }

    private fun parseNoteObject(obj: JSONObject): NoteItem? {
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        val tagsStr = obj.optString("tags", "")
        val tagsList = if (tagsStr.isNotBlank()) {
            tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        return NoteItem(
            id = id,
            title = obj.optString("title", ""),
            content = obj.optString("content", ""),
            folder = obj.optString("folder", "General").ifBlank { "General" },
            tags = tagsList,
            isPinned = obj.optBoolean("is_pinned", false),
            mediaPath = obj.optString("media_path").takeIf { it.isNotBlank() },
            mediaType = obj.optString("media_type", "none").ifBlank { "none" },
            aiSummary = obj.optString("ai_summary").takeIf { it.isNotBlank() },
            aiTranscript = obj.optString("ai_transcript").takeIf { it.isNotBlank() },
            createdAt = obj.optLong("created_at", System.currentTimeMillis()),
            updatedAt = obj.optLong("updated_at", System.currentTimeMillis())
        )
    }

    companion object {
        private const val TAG = "NativeNoteRepository"
    }
}
