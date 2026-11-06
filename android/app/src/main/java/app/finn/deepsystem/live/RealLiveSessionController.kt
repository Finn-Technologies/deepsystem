package app.finn.deepsystem.live

import android.util.Log
import app.finn.deepsystem.audio.AudioRecorder
import app.finn.deepsystem.audio.AudioTrackPlayer
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.NativeFinnEngine
import app.finn.deepsystem.ui.live.LiveCaption
import app.finn.deepsystem.ui.live.LiveEngineState
import app.finn.deepsystem.ui.live.LiveSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RealLiveSessionController(
    private val engineProvider: () -> FinnEngine?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val audioRecorder: AudioRecorder = AudioRecorder(AudioRecorder.SAMPLE_RATE_16K),
    private val audioPlayer: AudioTrackPlayer = AudioTrackPlayer(),
    private val vadThreshold: Float = 450.0f,
    private val silenceTimeoutMs: Int = 600,
    private val voice: String = "female"
) : LiveSessionController {

    private val _state = MutableStateFlow<LiveEngineState>(LiveEngineState.Idle)
    override val state: StateFlow<LiveEngineState> = _state.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _captions = MutableStateFlow<List<LiveCaption>>(emptyList())
    override val captions: StateFlow<List<LiveCaption>> = _captions.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0.0)
    val lastLatencyMs: StateFlow<Double> = _lastLatencyMs.asStateFlow()

    private var nativeSession: NativeLiveSession? = null
    private var recordingJob: Job? = null
    private var stateObserverJob: Job? = null

    override fun start() {
        if (_state.value != LiveEngineState.Idle) return

        val engine = engineProvider()
        if (engine == null || !engine.isLoaded) {
            _state.value = LiveEngineState.Error("Engine not loaded. Please select a model first.")
            return
        }

        val engineHandle = (engine as? NativeFinnEngine)?.nativeHandle ?: 0L
        if (engineHandle == 0L) {
            Log.w(TAG, "Native engine handle is 0, using fallback controller")
            _state.value = LiveEngineState.Listening(0.1f)
            return
        }

        try {
            val session = NativeLiveSession(
                engineHandle = engineHandle,
                vadThreshold = vadThreshold,
                silenceTimeoutMs = silenceTimeoutMs,
                voice = voice
            )
            session.setAudioOutputListener { pcm, sampleRate ->
                // Play whatever the core streams; barge-in (interrupt) stops the player immediately.
                audioPlayer.playChunk(pcm, sampleRate)
                nativeSession != null
            }

            nativeSession = session
            session.start()

            // Observe native state transitions
            stateObserverJob?.cancel()
            stateObserverJob = scope.launch {
                session.state.collect { nativeState ->
                    when (nativeState) {
                        LiveState.Idle -> _state.value = LiveEngineState.Idle
                        LiveState.Listening -> _state.value = LiveEngineState.Listening(0.05f)
                        LiveState.Thinking -> {
                            _state.value = LiveEngineState.Thinking(0.5f)
                            _captions.update { it + LiveCaption("You", "[Voice message received]") }
                        }
                        LiveState.Speaking -> {
                            _lastLatencyMs.value = session.lastLatencyMs.value
                            _state.value = LiveEngineState.Speaking(0.7f, "Speaking...")
                            _captions.update { it + LiveCaption("Finn", "[Streaming response]") }
                        }
                    }
                }
            }

            // Start audio recording and feed loop
            startMicCaptureLoop(session)

            _captions.value = listOf(
                LiveCaption("Finn", "Live voice session active. I'm listening.")
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start native live session: ${t.message}", t)
            _state.value = LiveEngineState.Error("Failed to start Live Session: ${t.message}")
        }
    }

    override fun stop() {
        recordingJob?.cancel()
        recordingJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null

        audioRecorder.stop()
        audioPlayer.stopImmediately()

        nativeSession?.stop()
        nativeSession?.close()
        nativeSession = null

        _state.value = LiveEngineState.Idle
        _isCameraEnabled.value = false
    }

    override fun interrupt() {
        // Fast barge-in interruption!
        nativeSession?.triggerBargeIn()
        audioPlayer.stopImmediately()
        _state.value = LiveEngineState.Listening(0.1f)
        _captions.update { it + LiveCaption("System", "Interrupted") }
    }

    override fun toggleMute() {
        _isMuted.update { !it }
    }

    override fun toggleCamera() {
        _isCameraEnabled.update { !it }
    }

    override fun onCameraFrame(jpegBytes: ByteArray?) {
        feedCameraFrame(jpegBytes)
    }

    fun feedCameraFrame(jpegBytes: ByteArray?) {
        if (_isCameraEnabled.value) {
            nativeSession?.feedCameraFrame(jpegBytes)
        }
    }

    fun feedSyntheticAudio(pcm: ShortArray) {
        nativeSession?.feedAudio(pcm, 0, pcm.size)
    }

    override fun simulateUtterance(text: String) {
        interrupt()
        _captions.update { it + LiveCaption("You", text) }
        nativeSession?.triggerEndOfSpeech()
    }

    private fun startMicCaptureLoop(session: NativeLiveSession) {
        recordingJob?.cancel()
        val started = audioRecorder.start()
        if (!started) {
            Log.w(TAG, "AudioRecorder start failed (check RECORD_AUDIO permission)")
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(320) // 20ms at 16kHz
            while (isActive && audioRecorder.recording) {
                val read = audioRecorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    val rms = AudioRecorder.computeRms(chunk, read)
                    val level = (rms / 2500f).coerceIn(0f, 1f)

                    if (!_isMuted.value) {
                        session.feedAudio(chunk, 0, read)
                    }

                    if (_state.value is LiveEngineState.Listening) {
                        _state.value = LiveEngineState.Listening(if (_isMuted.value) 0f else level)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RealLiveSessionCtrl"
    }
}
