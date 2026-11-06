package app.finn.deepsystem.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.finn.deepsystem.engine.EngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Turns shared/imported content (text, image, audio, video) into notes and enriches them with AI in the background. */
object NoteIngestor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun mediaTypeOf(mime: String?): String = when {
        mime == null -> "text"
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("video/") -> "video"
        else -> "text"
    }

    /** Persists the note immediately and returns it; AI enrichment continues asynchronously. */
    fun ingest(context: Context, intent: Intent, repo: NoteRepository, enrich: Boolean = true): NoteItem? {
        if (intent.action != Intent.ACTION_SEND) return null
        val mime = intent.type
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val type = if (uri != null) mediaTypeOf(mime ?: context.contentResolver.getType(uri)) else "text"
        val path = uri?.let { copyToPrivate(context, it, type) }
        if (uri == null && text.isBlank()) return null
        val now = System.currentTimeMillis()
        val note = NoteItem(
            id = "note_${UUID.randomUUID()}", title = subject, content = text,
            folder = "Shared", tags = listOf("shared"), mediaPath = path,
            mediaType = if (path != null) type else "text", createdAt = now, updatedAt = now
        )
        repo.createNote(note)
        if (enrich) enrich(repo, note)
        return note
    }

    fun enrich(repo: NoteRepository, note: NoteItem) {
        scope.launch {
            try {
                val engine = EngineProvider.getEngine()
                val file = note.mediaPath?.let(::File)
                var n = note
                when (note.mediaType) {
                    "audio" -> file?.let { val t = NoteAi.transcribeAudio(engine, it); n = n.copy(aiTranscript = t, content = n.content.ifBlank { t }) }
                    "video" -> file?.let { val t = NoteAi.transcribeVideo(engine, it); n = n.copy(aiTranscript = t, content = n.content.ifBlank { t }) }
                    "image" -> file?.let { val d = NoteAi.describeImage(engine, it); n = n.copy(aiSummary = d, content = n.content.ifBlank { d }) }
                }
                if (n.content.length > 200 && n.aiSummary == null) n = n.copy(aiSummary = NoteAi.summarize(engine, n.content))
                if (n.content.isNotBlank() && n.title.isBlank()) {
                    val (title, tags) = NoteAi.parseOrganize(NoteAi.run(engine) { engine.generateText(NoteAi.organizePrompt(n.content), 0.2f, 64, it) })
                    n = n.copy(title = title, tags = (n.tags + tags).distinct())
                }
                repo.updateNote(n.copy(updatedAt = System.currentTimeMillis()))
            } catch (_: Throwable) { /* note stays un-enriched; user can retry from editor */ }
        }
    }

    private fun copyToPrivate(context: Context, uri: Uri, type: String): String? = try {
        val dir = File(context.filesDir, "note_media").apply { mkdirs() }
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString) else null
        } ?: "shared.$type"
        val out = File(dir, "${UUID.randomUUID()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
        out.absolutePath
    } catch (t: Throwable) { null }
}
