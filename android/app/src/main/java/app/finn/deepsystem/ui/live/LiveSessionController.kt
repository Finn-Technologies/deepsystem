package app.finn.deepsystem.ui.live

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface LiveEngineState {
    object Idle : LiveEngineState
    object Connecting : LiveEngineState
    data class Listening(val audioLevel: Float = 0f) : LiveEngineState
    data class Thinking(val progress: Float = 0f) : LiveEngineState
    data class Speaking(val audioLevel: Float = 0f, val currentUtterance: String = "") : LiveEngineState
    data class Error(val message: String) : LiveEngineState
}

data class LiveCaption(
    val speaker: String, // "You" or "Finn"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface LiveSessionController {
    val state: StateFlow<LiveEngineState>
    val isCameraEnabled: StateFlow<Boolean>
    val isMuted: StateFlow<Boolean>
    val captions: StateFlow<List<LiveCaption>>

    fun start()
    fun stop()
    fun interrupt()
    fun toggleMute()
    fun toggleCamera()
    fun simulateUtterance(text: String)
}

/**
 * High-fidelity Fake implementation of the Live Session Controller.
 * Provides realistic conversational state transitions, dynamic audio levels,
 * streaming captions, and responsive interrupt capabilities until the concurrent
 * C++ live-engine work lands.
 */
class FakeLiveSessionController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : LiveSessionController {

    private val _state = MutableStateFlow<LiveEngineState>(LiveEngineState.Idle)
    override val state: StateFlow<LiveEngineState> = _state.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _captions = MutableStateFlow<List<LiveCaption>>(emptyList())
    override val captions: StateFlow<List<LiveCaption>> = _captions.asStateFlow()

    private var sessionJob: Job? = null
    private var speakingJob: Job? = null

    override fun start() {
        if (_state.value != LiveEngineState.Idle) return

        sessionJob?.cancel()
        sessionJob = scope.launch {
            _state.value = LiveEngineState.Connecting
            delay(600)
            _state.value = LiveEngineState.Listening(0.1f)

            // Add welcome caption
            _captions.value = listOf(
                LiveCaption(
                    speaker = "Finn",
                    text = "Live session active. I am listening on-device."
                )
            )

            // Start simulated ambient listening loop
            var phase = 0f
            while (isActive) {
                delay(120)
                if (_state.value is LiveEngineState.Listening) {
                    phase += 0.2f
                    val level = ((Math.sin(phase.toDouble()).toFloat() + 1f) / 2f * 0.4f + 0.1f).coerceIn(0f, 1f)
                    _state.value = LiveEngineState.Listening(if (_isMuted.value) 0f else level)
                }
            }
        }
    }

    override fun stop() {
        sessionJob?.cancel()
        speakingJob?.cancel()
        sessionJob = null
        speakingJob = null
        _state.value = LiveEngineState.Idle
        _isCameraEnabled.value = false
    }

    override fun interrupt() {
        speakingJob?.cancel()
        speakingJob = null
        _state.update {
            if (it is LiveEngineState.Speaking || it is LiveEngineState.Thinking) {
                LiveEngineState.Listening(0.2f)
            } else it
        }
        _captions.update {
            it + LiveCaption("System", "Interrupted")
        }
    }

    override fun toggleMute() {
        _isMuted.update { !it }
    }

    override fun toggleCamera() {
        _isCameraEnabled.update { !it }
    }

    override fun simulateUtterance(text: String) {
        if (_state.value == LiveEngineState.Idle) return

        speakingJob?.cancel()
        speakingJob = scope.launch {
            // User speaks
            _captions.update { it + LiveCaption("You", text) }

            // Assistant thinks
            _state.value = LiveEngineState.Thinking(0.2f)
            delay(400)
            _state.value = LiveEngineState.Thinking(0.7f)
            delay(300)

            // Assistant speaks
            val responseText = "I hear you! All audio and vision processing is executing entirely offline with zero cloud latency."
            _captions.update { it + LiveCaption("Finn", responseText) }

            val words = responseText.split(" ")
            val partial = StringBuilder()
            for (word in words) {
                if (!isActive) break
                partial.append(word).append(" ")
                val audioLvl = (0.3f + (Math.random().toFloat() * 0.6f)).coerceIn(0.1f, 1f)
                _state.value = LiveEngineState.Speaking(audioLvl, partial.toString())
                delay(160)
            }

            // Return to listening
            _state.value = LiveEngineState.Listening(0.1f)
        }
    }
}
