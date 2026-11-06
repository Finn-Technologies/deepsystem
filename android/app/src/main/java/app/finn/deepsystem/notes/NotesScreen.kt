package app.finn.deepsystem.notes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Notes tab: FTS5 search, folder/tag filters, pins, create from text/image/audio/video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel, modifier: Modifier = Modifier, openNoteId: String? = null) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var addMenu by remember { mutableStateOf(false) }

    fun importMedia(uri: Uri?, type: String) {
        uri ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(when (type) { "image" -> "image/*"; "audio" -> "audio/*"; else -> "video/*" })
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        viewModel.importShared(context, intent)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "image") }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "audio") }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importMedia(it, "video") }

    LaunchedEffect(openNoteId) { openNoteId?.let { viewModel.openById(it) } }

    state.selectedNoteForEdit?.let { note ->
        NoteEditorScreen(initialNote = note, onSave = viewModel::saveNote,
            onDelete = { viewModel.deleteNote(it); viewModel.closeNoteEditor() },
            onClose = viewModel::closeNoteEditor, modifier = modifier)
        return
    }

    Scaffold(modifier = modifier.testTag("notes_screen"),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }, modifier = Modifier.testTag("notes_add")) {
                    Icon(Icons.Default.Add, "New note")
                }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem({ Text("Write") }, { addMenu = false; viewModel.openNoteForEdit(viewModel.createNote()) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem({ Text("From image") }, { addMenu = false; pickImage.launch("image/*") }, leadingIcon = { Icon(Icons.Default.Image, null) })
                    DropdownMenuItem({ Text("From audio") }, { addMenu = false; pickAudio.launch("audio/*") }, leadingIcon = { Icon(Icons.Default.Mic, null) })
                    DropdownMenuItem({ Text("From video") }, { addMenu = false; pickVideo.launch("video/*") }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
                }
            }
        }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text("Notes", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.searchQuery, onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().testTag("notes_search"), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search notes") }
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(state.allFolders) { f ->
                    FilterChip(state.selectedFolder == f, { viewModel.onFolderSelect(f) }, { Text(f) },
                        leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) })
                }
                items(state.allTags) { t ->
                    FilterChip(state.selectedTag == t, { viewModel.onTagSelect(t) }, { Text("#$t") })
                }
            }
            if (state.notes.isEmpty()) {
                Text(if (state.searchQuery.isBlank()) "Just start writing — tap + to create your first note."
                else "No notes match \"${state.searchQuery}\".", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(state.notes, key = { it.id }) { n -> NoteRow(n, { viewModel.openNoteForEdit(n) }, { viewModel.togglePin(n.id) }) }
            }
        }
    }
}

@Composable
fun NoteRow(note: NoteItem, onClick: () -> Unit, onPin: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().clickable(onClick = onClick).testTag("note_${note.id}"), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(when (note.mediaType) {
                "image" -> Icons.Default.Image; "audio" -> Icons.Default.Mic; "video" -> Icons.Default.Videocam
                else -> Icons.Default.Description }, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(note.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note.aiSummary?.takeIf { note.content.isBlank() } ?: note.getSnippet(), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (note.tags.isNotEmpty()) Text(note.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onPin, Modifier.size(32.dp)) {
                Icon(if (note.isPinned) Icons.Default.PushPin else Icons.Default.PushPin, if (note.isPinned) "Unpin" else "Pin",
                    tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
        }
    }
}
