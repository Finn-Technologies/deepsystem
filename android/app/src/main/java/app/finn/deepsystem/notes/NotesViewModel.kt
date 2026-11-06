package app.finn.deepsystem.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FinnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class NotesUiState(
    val notes: List<NoteItem> = emptyList(),
    val searchQuery: String = "",
    val selectedFolder: String? = null,
    val selectedTag: String? = null,
    val allFolders: List<String> = listOf("General"),
    val allTags: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val selectedNoteForEdit: NoteItem? = null
)

class NotesViewModel(
    application: Application,
    private val repository: NoteRepository = NativeNoteRepository(),
    private val engineFactory: () -> FinnEngine = { EngineProvider.getEngine() }
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    init {
        val dbFile = File(application.filesDir, "finn_storage.db")
        repository.init(dbFile.absolutePath)
        loadNotes()
    }

    fun loadNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            val q = _uiState.value.searchQuery.trim()
            val folder = _uiState.value.selectedFolder
            val tag = _uiState.value.selectedTag

            val list = if (q.isNotEmpty()) {
                repository.searchNotes(q)
            } else {
                repository.listNotes(folder, tag)
            }

            val folders = repository.getAllFolders()
            val tags = repository.getAllTags()

            _uiState.update {
                it.copy(
                    notes = list,
                    allFolders = folders,
                    allTags = tags,
                    isLoading = false
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        loadNotes()
    }

    fun onFolderSelect(folder: String?) {
        val next = if (_uiState.value.selectedFolder == folder) null else folder
        _uiState.update { it.copy(selectedFolder = next) }
        loadNotes()
    }

    fun onTagSelect(tag: String?) {
        val next = if (_uiState.value.selectedTag == tag) null else tag
        _uiState.update { it.copy(selectedTag = next) }
        loadNotes()
    }

    fun togglePin(noteId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.togglePin(noteId)
            loadNotes()
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteNote(noteId)
            loadNotes()
        }
    }

    fun createNote(
        title: String = "",
        content: String = "",
        folder: String = "General",
        tags: List<String> = emptyList(),
        mediaPath: String? = null,
        mediaType: String = "none",
        isPinned: Boolean = false
    ): NoteItem {
        val id = "note_${UUID.randomUUID()}"
        val note = NoteItem(
            id = id,
            title = title,
            content = content,
            folder = folder,
            tags = tags,
            isPinned = isPinned,
            mediaPath = mediaPath,
            mediaType = mediaType,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            repository.createNote(note)
            loadNotes()
        }
        return note
    }

    fun saveNote(note: NoteItem) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
            loadNotes()
        }
    }

    fun importShared(context: android.content.Context, intent: android.content.Intent) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = NoteIngestor.ingest(context, intent, repository)
            loadNotes()
            if (n != null) _uiState.update { it.copy(selectedNoteForEdit = n) }
        }
    }

    fun openById(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getNote(id)?.let { n -> _uiState.update { it.copy(selectedNoteForEdit = n) } }
        }
    }

    fun recentNotes(limit: Int = 4): List<NoteItem> = repository.listNotes().take(limit)

    fun openNoteForEdit(note: NoteItem) {
        _uiState.update { it.copy(selectedNoteForEdit = note) }
    }

    fun closeNoteEditor() {
        _uiState.update { it.copy(selectedNoteForEdit = null) }
        loadNotes()
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}
