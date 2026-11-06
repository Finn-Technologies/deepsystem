package app.finn.deepsystem.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.finn.deepsystem.miniapps.MiniApp
import app.finn.deepsystem.notes.NoteItem

data class HomeTask(val id: String, val title: String, val status: String, val progress: Int)

val DefaultSuggestions = listOf(
    "Summarize my latest note", "Plan my day", "Explain something I'm curious about", "Build a mini app: habit tracker"
)

/** Home tab: widget-style cards for quick ask, recent notes, tasks, suggestions and pinned mini apps. */
@Composable
fun HomeScreen(
    recentNotes: List<NoteItem>,
    tasks: List<HomeTask>,
    miniApps: List<MiniApp>,
    onQuickAsk: (String) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenMiniApp: (MiniApp) -> Unit,
    onOpenMiniApps: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenLive: () -> Unit = {},
    onOpenPrivacyLedger: () -> Unit = {},
    onOpenSandbox: () -> Unit = {},
    modifier: Modifier = Modifier,
    suggestions: List<String> = DefaultSuggestions
) {
    var ask by remember { mutableStateOf("") }
    LazyColumn(modifier.padding(horizontal = 16.dp).testTag("home_screen"), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text("Good to see you", style = MaterialTheme.typography.headlineSmall) }
        item {
            HomeCard("Quick ask", Icons.Default.AutoAwesome, Modifier.testTag("card_quick_ask")) {
                OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth().testTag("home_ask_input"), singleLine = true,
                    placeholder = { Text("Ask Finn anything…") },
                    trailingIcon = { IconButton({ if (ask.isNotBlank()) { onQuickAsk(ask); ask = "" } }) { Icon(Icons.Default.Send, "Send quick ask") } })
            }
        }
        item {
            HomeCard("Recent notes", Icons.Default.Description, Modifier.testTag("card_recent_notes"), action = "All" to onOpenNotes) {
                if (recentNotes.isEmpty()) Text("No notes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                recentNotes.take(4).forEach { n ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenNote(n) }.padding(vertical = 6.dp)) {
                        Text(n.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.getSnippet(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            HomeCard("Tasks", Icons.Default.TaskAlt, Modifier.testTag("card_tasks"), action = "Open" to onOpenTasks) {
                if (tasks.isEmpty()) Text("No background tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                tasks.take(3).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.status} ${t.progress}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            HomeCard("Suggestions", Icons.Default.Lightbulb, Modifier.testTag("card_suggestions")) {
                suggestions.forEach { s ->
                    SuggestionChip(onClick = { onQuickAsk(s) }, label = { Text(s) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }
        item {
            HomeCard("Quick access", Icons.Default.Apps, Modifier.testTag("card_quick_access")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenLive, Modifier.weight(1f)) { Text("Live") }
                    OutlinedButton(onClick = onOpenPrivacyLedger, Modifier.weight(1f)) { Text("Privacy") }
                    OutlinedButton(onClick = onOpenSandbox, Modifier.weight(1f)) { Text("Sandbox") }
                }
            }
        }
        item {
            HomeCard("Mini apps", Icons.Default.Apps, Modifier.testTag("card_mini_apps"), action = "Gallery" to onOpenMiniApps) {
                if (miniApps.isEmpty()) Text("Ask Finn to build a tiny app for you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                miniApps.take(4).forEach { a ->
                    Text(a.title, Modifier.fillMaxWidth().clickable { onOpenMiniApp(a) }.padding(vertical = 6.dp), maxLines = 1,
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null, content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.let { (label, f) -> TextButton(f) { Text(label) } }
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
