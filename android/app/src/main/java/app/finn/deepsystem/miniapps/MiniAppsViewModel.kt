package app.finn.deepsystem.miniapps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.notes.generateText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class MiniAppsUiState(
    val apps: List<MiniApp> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val running: MiniApp? = null
)

class MiniAppsViewModel(
    application: Application,
    val repository: MiniAppRepository = NativeMiniAppRepository(),
    private val engineFactory: () -> FinnEngine = { EngineProvider.getEngine() }
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MiniAppsUiState())
    val state: StateFlow<MiniAppsUiState> = _state.asStateFlow()

    init {
        repository.init(File(application.filesDir, "finn_storage.db").absolutePath)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(apps = repository.list()) } }
    }

    fun generate(request: String) {
        if (request.isBlank() || _state.value.isGenerating) return
        _state.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val reply = runModel(MiniAppPrompts.generate(request))
            if (!MiniAppSanitizer.looksLikeHtml(reply)) { fail("Finn couldn't produce an app. Try rephrasing."); return@launch }
            val html = MiniAppSanitizer.sanitize(reply)
            val app = MiniApp("mini_${UUID.randomUUID()}", MiniAppSanitizer.titleOf(html, request.take(40)),
                request.take(140), html, request)
            repository.create(app)
            _state.update { it.copy(isGenerating = false, apps = repository.list(), running = app) }
        }
    }

    fun regenerate(app: MiniApp, feedback: String) {
        if (_state.value.isGenerating) return
        _state.update { it.copy(isGenerating = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val reply = runModel(MiniAppPrompts.regenerate(app.prompt, app.html, feedback))
            if (!MiniAppSanitizer.looksLikeHtml(reply)) { fail("Finn couldn't update the app."); return@launch }
            val updated = app.copy(html = MiniAppSanitizer.sanitize(reply),
                prompt = if (feedback.isBlank()) app.prompt else app.prompt + "\n" + feedback, updatedAt = System.currentTimeMillis())
            repository.update(updated)
            _state.update { it.copy(isGenerating = false, apps = repository.list(), running = updated) }
        }
    }

    private fun fail(msg: String) = _state.update { it.copy(isGenerating = false, error = msg) }

    private fun runModel(prompt: String): String {
        val sb = StringBuilder()
        try { engineFactory().generateText(prompt, 0.4f, 2048) { sb.append(it); true } } catch (t: Throwable) { return "" }
        return sb.toString()
    }

    fun togglePin(app: MiniApp) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(app.copy(isPinned = !app.isPinned)); refresh()
    }

    fun delete(app: MiniApp) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(app.id)
        _state.update { it.copy(running = it.running?.takeIf { r -> r.id != app.id }) }
        refresh()
    }

    fun open(app: MiniApp) = _state.update { it.copy(running = app) }
    fun close() = _state.update { it.copy(running = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Reads grants persisted in the reserved (non-app-writable) key. */
    fun grants(appId: String): Set<MiniAppPermission> =
        MiniAppPermission.parse(repository.get(appId, MiniAppBridgeCore.PERM_KEY))

    fun setGrants(appId: String, g: Set<MiniAppPermission>) {
        repository.set(appId, MiniAppBridgeCore.PERM_KEY, MiniAppPermission.serialize(g))
    }

    fun askFinnSync(prompt: String): String {
        val sb = StringBuilder()
        engineFactory().generateText(
            "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n", 0.6f, 256) { sb.append(it); true }
        return sb.toString().trim()
    }

    override fun onCleared() { repository.close() }
}
