package app.finn.deepsystem.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.finn.deepsystem.MainActivity
import java.io.File

/** Handles ACTION_SEND (text/image/audio/video) "Share to Finn" and saves it as a note. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = NativeNoteRepository()
        repo.init(File(filesDir, "finn_storage.db").absolutePath)
        val note = try { NoteIngestor.ingest(this, intent, repo) } catch (t: Throwable) { null }
        if (note != null) {
            Toast.makeText(this, "Saved to Finn Notes", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, note.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } else Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
        finish()
    }
}
