package app.finn.deepsystem.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.model.Conversation
import app.finn.deepsystem.model.EngineStats
import app.finn.deepsystem.model.KnownModels
import app.finn.deepsystem.network.ModelDownloader
import app.finn.deepsystem.storage.ConversationRepository
import app.finn.deepsystem.storage.NativeFinnStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputPrompt: String = "",
    val selectedImageUri: String? = null,
    val selectedImageBytes: ByteArray? = null,
    val isStreaming: Boolean = false,
    val latestStats: EngineStats? = null,
    val isModelReady: Boolean = false,
    val isModelLoading: Boolean = false,
    val modelLoadError: String? = null,
    val currentConversationId: String = "default_conv",
    val conversations: List<Conversation> = emptyList(),
    val showModelManager: Boolean = false,
    val isUsingFakeEngine: Boolean = false
)

class ChatViewModel(
    application: Application,
    private val storage: ConversationRepository = NativeFinnStorage(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val engineFactory: () -> FinnEngine = { EngineProvider.getEngine(application) }
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val downloader = ModelDownloader(application)
    private var activeGenerationJob: Job? = null
    private var engine: FinnEngine = engineFactory()

    init {
        val dbFile = File(application.filesDir, "finn_chat.db").absolutePath
        storage.init(dbFile)

        // Ensure default conversation exists
        val defaultId = "conv_${UUID.randomUUID().toString().take(8)}"
        storage.createConversation(defaultId, "New Chat")

        _uiState.update {
            it.copy(
                currentConversationId = defaultId,
                isUsingFakeEngine = EngineProvider.useFakeEngine,
                isModelReady = downloader.areRequiredModelsReady() || EngineProvider.useFakeEngine
            )
        }

        refreshConversations()
        checkAndAutoLoadModel()
    }

    fun onInputPromptChanged(text: String) {
        _uiState.update { it.copy(inputPrompt = text) }
    }

    fun onImageSelected(uri: String?, bytes: ByteArray?) {
        _uiState.update { it.copy(selectedImageUri = uri, selectedImageBytes = bytes) }
    }

    fun removeImage() {
        _uiState.update { it.copy(selectedImageUri = null, selectedImageBytes = null) }
    }

    fun openModelManager() {
        _uiState.update { it.copy(showModelManager = true) }
    }

    fun closeModelManager() {
        _uiState.update { it.copy(showModelManager = false) }
        checkAndAutoLoadModel()
    }

    fun toggleFakeEngine(forceFake: Boolean) {
        EngineProvider.useFakeEngine = forceFake
        engine = EngineProvider.switchEngine(forceFake)
        _uiState.update {
            it.copy(
                isUsingFakeEngine = forceFake,
                isModelReady = forceFake || downloader.areRequiredModelsReady()
            )
        }
        if (!forceFake) {
            checkAndAutoLoadModel()
        }
    }

    fun refreshModelStatus() {
        val ready = downloader.areRequiredModelsReady() || _uiState.value.isUsingFakeEngine
        _uiState.update { it.copy(isModelReady = ready) }
    }

    fun checkAndAutoLoadModel() {
        refreshModelStatus()
        if (_uiState.value.isUsingFakeEngine) {
            return
        }

        val textModel = downloader.findModelFile(KnownModels.TEXT_MODEL.filename)
        if (textModel != null && textModel.exists()) {
            val mmprojModel = downloader.findModelFile(KnownModels.MMPROJ_MODEL.filename)
            loadModel(textModel.absolutePath, mmprojModel?.absolutePath ?: "")
        }
    }

    fun loadModel(modelPath: String, mmprojPath: String = "") {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isModelLoading = true, modelLoadError = null) }
            try {
                Log.i(TAG, "Loading model: $modelPath (mmproj: $mmprojPath)")
                val ok = engine.load(
                    modelPath = modelPath,
                    mmprojPath = mmprojPath,
                    nCtx = 2048,
                    nThreads = 4,
                    nGpuLayers = 0,
                    useMmap = true
                )
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Log.i(TAG, "Model loaded successfully into engine")
                        _uiState.update { it.copy(isModelLoading = false, isModelReady = true) }
                    } else {
                        Log.e(TAG, "Engine load returned failure")
                        _uiState.update {
                            it.copy(
                                isModelLoading = false,
                                modelLoadError = "Failed to initialize llama.cpp model."
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error loading model", t)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isModelLoading = false,
                            modelLoadError = t.message ?: "Failed to load model"
                        )
                    }
                }
            }
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val prompt = state.inputPrompt.trim()
        val imageBytes = state.selectedImageBytes
        val imageUri = state.selectedImageUri

        if (prompt.isEmpty() && imageBytes == null) return
        if (state.isStreaming) return

        val userMessage = ChatMessage(
            role = "user",
            content = prompt.ifEmpty { "[Image attached]" },
            imageUri = imageUri
        )

        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = ChatMessage(
            id = assistantMessageId,
            role = "assistant",
            content = "",
            isStreaming = true
        )

        // Clear input field immediately
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + initialAssistantMessage,
                inputPrompt = "",
                selectedImageUri = null,
                selectedImageBytes = null,
                isStreaming = true
            )
        }

        // Persist user message to storage
        val convId = state.currentConversationId
        storage.addMessage(convId, userMessage.role, userMessage.content)

        activeGenerationJob = viewModelScope.launch(ioDispatcher) {
            val responseContent = StringBuilder()

            try {
                val stats = engine.generate(
                    prompt = prompt.ifEmpty { "Describe this image in detail." },
                    imageBytes = imageBytes,
                    temperature = 0.7f,
                    maxTokens = 512
                ) { token ->
                    responseContent.append(token)
                    val currentText = responseContent.toString()

                    // Dispatch partial updates to UI
                    _uiState.update { s ->
                        val updated = s.messages.map { msg ->
                            if (msg.id == assistantMessageId) {
                                msg.copy(content = currentText)
                            } else msg
                        }
                        s.copy(messages = updated)
                    }
                    true // continue generation
                }

                val finalText = responseContent.toString().trim()
                withContext(Dispatchers.Main) {
                    _uiState.update { s ->
                        val updated = s.messages.map { msg ->
                            if (msg.id == assistantMessageId) {
                                msg.copy(
                                    content = finalText,
                                    isStreaming = false,
                                    stats = stats
                                )
                            } else msg
                        }
                        s.copy(
                            messages = updated,
                            isStreaming = false,
                            latestStats = stats
                        )
                    }
                }

                // Persist completed assistant message
                storage.addMessage(convId, "assistant", finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "Generation error", t)
                withContext(Dispatchers.Main) {
                    _uiState.update { s ->
                        val updated = s.messages.map { msg ->
                            if (msg.id == assistantMessageId) {
                                msg.copy(
                                    content = responseContent.toString().ifEmpty { "Generation error: ${t.message}" },
                                    isStreaming = false
                                )
                            } else msg
                        }
                        s.copy(messages = updated, isStreaming = false)
                    }
                }
            } finally {
                activeGenerationJob = null
            }
        }
    }

    fun stopGeneration() {
        engine.cancel()
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        _uiState.update { s ->
            val updated = s.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }
            s.copy(messages = updated, isStreaming = false)
        }
    }

    fun createNewConversation() {
        val newId = "conv_${UUID.randomUUID().toString().take(8)}"
        storage.createConversation(newId, "Chat ${System.currentTimeMillis() % 1000}")
        _uiState.update {
            it.copy(
                currentConversationId = newId,
                messages = emptyList(),
                inputPrompt = ""
            )
        }
        refreshConversations()
    }

    fun selectConversation(id: String) {
        val msgs = storage.getMessages(id)
        _uiState.update {
            it.copy(
                currentConversationId = id,
                messages = msgs,
                inputPrompt = ""
            )
        }
    }

    private fun refreshConversations() {
        val convs = storage.listConversations()
        _uiState.update { it.copy(conversations = convs) }
    }

    override fun onCleared() {
        super.onCleared()
        engine.cancel()
        storage.close()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
