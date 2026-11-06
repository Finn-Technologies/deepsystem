package app.finn.deepsystem.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.attachments.AttachmentProcessor
import app.finn.deepsystem.audio.AudioRecorder
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.speech.SpeechManager
import app.finn.deepsystem.speech.SpeechOutputEngine
import app.finn.deepsystem.video.ProcessedVideo
import kotlinx.coroutines.isActive
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
    val selectedAudioPcm: ByteArray? = null,
    val selectedVideoFrames: List<ByteArray>? = null,
    val attachmentStatus: String? = null,
    val speakingMessageId: String? = null,
    val isVoiceRecording: Boolean = false,
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

class ChatViewModel @JvmOverloads constructor(
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
        val audioPcm = state.selectedAudioPcm
        val videoFrames = state.selectedVideoFrames

        val hasMedia = imageBytes != null || audioPcm != null || !videoFrames.isNullOrEmpty()
        if (prompt.isEmpty() && !hasMedia) return
        if (state.isStreaming) return

        val label = when {
            audioPcm != null && prompt.isEmpty() -> "[Voice message]"
            !videoFrames.isNullOrEmpty() && prompt.isEmpty() -> "[Video attached]"
            imageBytes != null && prompt.isEmpty() -> "[Image attached]"
            else -> prompt
        }
        val userMessage = ChatMessage(role = "user", content = label, imageUri = imageUri)

        // Clear input field immediately
        _uiState.update {
            it.copy(
                inputPrompt = "",
                selectedImageUri = null,
                selectedImageBytes = null,
                selectedAudioPcm = null,
                selectedVideoFrames = null,
                attachmentStatus = null
            )
        }
        storage.addMessage(state.currentConversationId, userMessage.role, userMessage.content)

        val history = state.messages + userMessage
        val promptText = if (prompt.isEmpty() && (audioPcm != null || !videoFrames.isNullOrEmpty())) {
            val q = if (audioPcm != null) VOICE_DEFAULT_PROMPT else ProcessedVideo.DEFAULT_VIDEO_PROMPT
            buildChatMLPrompt(state.messages + userMessage.copy(content = q))
        } else buildChatMLPrompt(history)

        startGeneration(
            baseMessages = history,
            fullPrompt = promptText,
            imageBytes = imageBytes,
            audioPcm = audioPcm,
            videoFrames = videoFrames
        )
    }

    /** Runs engine generation with token UI updates coalesced to at most ~30 Hz. */
    private fun startGeneration(
        baseMessages: List<ChatMessage>,
        fullPrompt: String,
        imageBytes: ByteArray? = null,
        audioPcm: ByteArray? = null,
        videoFrames: List<ByteArray>? = null
    ) {
        val assistantMessageId = UUID.randomUUID().toString()
        val convId = _uiState.value.currentConversationId
        _uiState.update {
            it.copy(
                messages = baseMessages + ChatMessage(
                    id = assistantMessageId, role = "assistant", content = "", isStreaming = true
                ),
                isStreaming = true
            )
        }

        activeGenerationJob = viewModelScope.launch(ioDispatcher) {
            val responseContent = StringBuilder()
            var lastEmitNs = 0L

            fun emit(text: String) {
                _uiState.update { s ->
                    val idx = s.messages.indexOfLast { it.id == assistantMessageId }
                    if (idx < 0) s else {
                        val list = s.messages.toMutableList()
                        list[idx] = list[idx].copy(content = text)
                        s.copy(messages = list)
                    }
                }
            }

            try {
                val cb = TokenCallback { token ->
                    responseContent.append(token)
                    val now = System.nanoTime()
                    if (now - lastEmitNs >= UI_UPDATE_INTERVAL_NS) {
                        lastEmitNs = now
                        emit(responseContent.toString())
                    }
                    true
                }
                val stats = if (audioPcm != null || !videoFrames.isNullOrEmpty()) {
                    engine.generateMultimodal(
                        prompt = fullPrompt,
                        imageBytes = imageBytes,
                        audioBytes = audioPcm,
                        videoFrames = videoFrames?.toTypedArray(),
                        temperature = 0.7f,
                        maxTokens = 512,
                        callback = cb
                    )
                } else {
                    engine.generate(
                        prompt = fullPrompt,
                        imageBytes = imageBytes,
                        temperature = 0.7f,
                        maxTokens = 512,
                        callback = cb
                    )
                }

                val finalText = responseContent.toString().trim()
                _uiState.update { s ->
                    val idx = s.messages.indexOfLast { it.id == assistantMessageId }
                    val list = s.messages.toMutableList()
                    if (idx >= 0) list[idx] = list[idx].copy(content = finalText, isStreaming = false, stats = stats)
                    s.copy(messages = list, isStreaming = false, latestStats = stats ?: s.latestStats)
                }
                storage.addMessage(convId, "assistant", finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "Generation error", t)
                _uiState.update { s ->
                    val idx = s.messages.indexOfLast { it.id == assistantMessageId }
                    val list = s.messages.toMutableList()
                    if (idx >= 0) list[idx] = list[idx].copy(
                        content = responseContent.toString().ifEmpty { "Generation error: ${t.message}" },
                        isStreaming = false
                    )
                    s.copy(messages = list, isStreaming = false)
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

    fun renameConversation(id: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        storage.renameConversation(id, trimmed)
        refreshConversations()
    }

    fun deleteConversation(id: String) {
        storage.deleteConversation(id)
        val remaining = storage.listConversations()
        if (remaining.isEmpty()) {
            createNewConversation()
        } else {
            val nextConv = remaining.first()
            _uiState.update {
                it.copy(
                    conversations = remaining,
                    currentConversationId = nextConv.id,
                    messages = storage.getMessages(nextConv.id),
                    inputPrompt = ""
                )
            }
        }
    }

    fun regenerateResponse() {
        val msgs = _uiState.value.messages
        if (msgs.isEmpty() || _uiState.value.isStreaming) return
        val lastUserIdx = msgs.indexOfLast { it.role == "user" }
        if (lastUserIdx == -1) return
        val trimmed = msgs.take(lastUserIdx + 1)
        startGeneration(
            baseMessages = trimmed,
            fullPrompt = buildChatMLPrompt(trimmed),
            imageBytes = _uiState.value.selectedImageBytes
        )
    }

    // ---- Attachments (audio / video / image) ------------------------------------------

    private val attachmentProcessor = AttachmentProcessor(application)

    fun onAudioSelected(uri: Uri) {
        _uiState.update { it.copy(attachmentStatus = "Processing audio…") }
        viewModelScope.launch(ioDispatcher) {
            val pcm = attachmentProcessor.processAudio(uri)
            _uiState.update {
                it.copy(
                    selectedAudioPcm = pcm,
                    attachmentStatus = if (pcm == null) "Could not decode audio" else null
                )
            }
        }
    }

    fun onVideoSelected(uri: Uri) {
        _uiState.update { it.copy(attachmentStatus = "Processing video…") }
        viewModelScope.launch(ioDispatcher) {
            val video = attachmentProcessor.processVideo(uri)
            _uiState.update {
                it.copy(
                    selectedVideoFrames = video?.frameJpegs,
                    selectedAudioPcm = video?.audioPcm16k,
                    attachmentStatus = if (video == null) "Could not read video" else null
                )
            }
        }
    }

    fun clearAttachments() {
        _uiState.update {
            it.copy(
                selectedImageUri = null, selectedImageBytes = null,
                selectedAudioPcm = null, selectedVideoFrames = null, attachmentStatus = null
            )
        }
    }

    // ---- Voice recording --------------------------------------------------------------

    private var voiceRecorder: AudioRecorder? = null
    private var voiceJob: Job? = null

    /** Starts mic capture (16 kHz mono PCM). Returns false when the mic can't be opened. */
    fun startVoiceRecording(recorder: AudioRecorder = AudioRecorder(AudioRecorder.SAMPLE_RATE_16K)): Boolean {
        if (voiceRecorder != null) return true
        if (!recorder.start()) return false
        voiceRecorder = recorder
        _uiState.update { it.copy(isVoiceRecording = true) }
        voiceJob = viewModelScope.launch(Dispatchers.IO) {
            val buf = ShortArray(1600)
            while (isActive && recorder.recording) {
                if (recorder.read(buf, 0, buf.size) < 0) break
            }
        }
        return true
    }

    fun cancelVoiceRecording() {
        voiceJob?.cancel(); voiceJob = null
        voiceRecorder?.stop(); voiceRecorder = null
        _uiState.update { it.copy(isVoiceRecording = false) }
    }

    /** Stops recording and sends the captured audio to the multimodal engine. */
    fun stopVoiceRecordingAndSend() {
        val rec = voiceRecorder ?: return
        voiceJob?.cancel(); voiceJob = null
        val pcm = rec.stop()
        voiceRecorder = null
        _uiState.update { it.copy(isVoiceRecording = false) }
        if (pcm.size < MIN_VOICE_BYTES) return
        sendVoiceMessage(pcm)
    }

    fun sendVoiceMessage(pcm16kMono: ByteArray) {
        _uiState.update { it.copy(selectedAudioPcm = pcm16kMono, inputPrompt = "") }
        sendMessage()
    }

    // ---- Speak reply (TTS) ------------------------------------------------------------

    private var speechManager: SpeechOutputEngine? = null

    fun speakReply(message: ChatMessage) {
        if (_uiState.value.speakingMessageId == message.id) {
            speechManager?.stop()
            _uiState.update { it.copy(speakingMessageId = null) }
            return
        }
        val mgr = speechManager ?: SpeechOutputEngine(getApplication(), { engine }).also { speechManager = it }
        _uiState.update { it.copy(speakingMessageId = message.id) }
        viewModelScope.launch {
            try {
                mgr.speak(message.content)
            } finally {
                _uiState.update { s ->
                    if (s.speakingMessageId == message.id) s.copy(speakingMessageId = null) else s
                }
            }
        }
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
        voiceRecorder?.stop()
        speechManager?.release()
        storage.close()
    }

    fun buildChatMLPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("You are Finn, an intelligent, helpful, and concise on-device AI assistant. Answer questions directly without repeating the user's prompt.\n")
        sb.append("<|im_end|>\n")

        for (msg in messages) {
            if (msg.role == "assistant" && msg.isStreaming && msg.content.isBlank()) continue
            if (msg.content.isBlank()) continue

            sb.append("<|im_start|>").append(msg.role).append("\n")
            sb.append(msg.content.trim())
            sb.append("<|im_end|>\n")
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    companion object {
        private const val TAG = "ChatViewModel"
        const val UI_UPDATE_INTERVAL_NS = 33_000_000L // ~30 Hz
        const val VOICE_DEFAULT_PROMPT = "Respond to what the user said in this voice message."
        private const val MIN_VOICE_BYTES = 3200 // 0.1 s @16kHz s16
    }
}
