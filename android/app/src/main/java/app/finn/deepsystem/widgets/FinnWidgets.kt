package app.finn.deepsystem.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import app.finn.deepsystem.MainActivity
import app.finn.deepsystem.notes.NativeNoteRepository
import app.finn.deepsystem.notes.NoteItem
import java.io.File

private val Bg = ColorProvider(Color(0xFF161E31))
private val Fg = ColorProvider(Color(0xFFF8FAFC))
private val Accent = ColorProvider(Color(0xFF8B5CF6))
private val Muted = ColorProvider(Color(0xFF94A3B8))

const val EXTRA_QUICK_ASK = "app.finn.deepsystem.QUICK_ASK"

class QuickAskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(24.dp).padding(16.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java).putExtra(EXTRA_QUICK_ASK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Finn", style = TextStyle(color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                Spacer(GlanceModifier.height(6.dp))
                Text("Ask Finn anything…", style = TextStyle(color = Fg, fontSize = 18.sp))
            }
        }
    }
}

class RecentNotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = loadRecent(context)
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(24.dp).padding(14.dp)) {
                Text("Recent notes", style = TextStyle(color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                Spacer(GlanceModifier.height(6.dp))
                if (notes.isEmpty()) Text("No notes yet", style = TextStyle(color = Muted, fontSize = 13.sp))
                notes.forEach { n ->
                    Column(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(
                        actionStartActivity(Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, n.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)))) {
                        Text(n.title.ifBlank { "Untitled" }, maxLines = 1, style = TextStyle(color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        Text(n.getSnippet(50), maxLines = 1, style = TextStyle(color = Muted, fontSize = 12.sp))
                    }
                }
            }
        }
    }

    private fun loadRecent(context: Context): List<NoteItem> = try {
        val repo = NativeNoteRepository()
        repo.init(File(context.filesDir, "finn_storage.db").absolutePath)
        try { repo.listNotes().take(3) } finally { repo.close() }
    } catch (t: Throwable) { emptyList() }
}

class QuickAskWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuickAskWidget() }
class RecentNotesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = RecentNotesWidget() }
