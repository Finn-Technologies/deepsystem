package app.finn.deepsystem.live

import android.util.Log
import app.finn.deepsystem.engine.AudioChunkCallback
import app.finn.deepsystem.engine.NativeFinnBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NativeLiveSession(
    private val engineHandle: Long,
    vadThreshold: Float = 450.0f,
    silenceTimeoutMs: Int = 600,
    voice: String = "female",
    systemPrompt: String = "You are Finn, a helpful, fast on-device assistant. Keep responses concise."
) : AutoCloseable {

    private var handle: Long = 0L
    private val lock = Any()

    private val _state = MutableStateFlow(LiveState.Idle)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0.0)
    val lastLatencyMs: StateFlow<Double> = _lastLatencyMs.asStateFlow()

    private var audioChunkListener: AudioChunkCallback? = null

    init {
        check(NativeFinnBridge.isNativeLoaded()) {
            "Native finn library is not loaded"
        }
        check(engineHandle != 0L) {
            "Invalid engine handle: 0"
        }

        handle = NativeFinnBridge.nativeLiveCreate(
            engineHandle,
            vadThreshold,
            silenceTimeoutMs,
            voice,
            systemPrompt
        )

        if (handle != 0L) {
            NativeFinnBridge.nativeLiveSetCallbacks(
                handle,
                { _, newState ->
                    val s = LiveState.fromCode(newState)
                    _state.value = s
                    if (s == LiveState.Speaking) {
                        _lastLatencyMs.value = NativeFinnBridge.nativeLiveGetLastLatencyMs(handle)
                    }
                },
                { pcm, sampleRate ->
                    audioChunkListener?.onAudioChunk(pcm, sampleRate) ?: true
                }
            )
            Log.i(TAG, "NativeLiveSession created with handle=$handle")
        } else {
            Log.e(TAG, "Failed to create native live session")
        }
    }

    fun setAudioOutputListener(listener: AudioChunkCallback?) {
        audioChunkListener = listener
    }

    fun start() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveStart(handle)
            _state.value = LiveState.fromCode(NativeFinnBridge.nativeLiveGetState(handle))
        }
    }

    fun stop() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveStop(handle)
            _state.value = LiveState.Idle
        }
    }

    fun feedAudio(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size) = synchronized(lock) {
        if (handle != 0L && length > 0) {
            NativeFinnBridge.nativeLiveFeedAudio(handle, pcm, offset, length)
        }
    }

    fun feedCameraFrame(jpegBytes: ByteArray?) = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveFeedCameraFrame(handle, jpegBytes)
        }
    }

    fun triggerBargeIn() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveTriggerBargeIn(handle)
        }
    }

    fun triggerEndOfSpeech() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveTriggerEndOfSpeech(handle)
        }
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeLiveDestroy(handle)
            handle = 0L
            _state.value = LiveState.Idle
            Log.i(TAG, "NativeLiveSession destroyed")
        }
    }

    protected fun finalize() {
        close()
    }

    companion object {
        private const val TAG = "NativeLiveSession"
    }
}
