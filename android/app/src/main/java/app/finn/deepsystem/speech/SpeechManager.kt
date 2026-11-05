package app.finn.deepsystem.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import app.finn.deepsystem.audio.AudioTrackPlayer
import app.finn.deepsystem.engine.AudioChunkCallback
import app.finn.deepsystem.engine.FinnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SpeechManager(
    private val context: Context,
    private val engineProvider: () -> FinnEngine?,
    private val audioPlayer: AudioTrackPlayer = AudioTrackPlayer()
) : TextToSpeech.OnInitListener {

    private val _isUsingNativeTts = MutableStateFlow(true)
    val isUsingNativeTts: StateFlow<Boolean> = _isUsingNativeTts.asStateFlow()

    private val _ttsEngineStatus = MutableStateFlow("Initializing TTS...")
    val ttsEngineStatus: StateFlow<String> = _ttsEngineStatus.asStateFlow()

    private val _lastRtf = MutableStateFlow(0.0)
    val lastRtf: StateFlow<Double> = _lastRtf.asStateFlow()

    private val _selectedVoice = MutableStateFlow("female")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private var androidTts: TextToSpeech? = null
    private val isAndroidTtsReady = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)

    init {
        try {
            androidTts = TextToSpeech(context.applicationContext, this)
        } catch (t: Throwable) {
            Log.w(TAG, "Android TextToSpeech init failed: ${t.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            androidTts?.language = Locale.US
            isAndroidTtsReady.set(true)
            Log.i(TAG, "Android TextToSpeech fallback ready")
        } else {
            Log.w(TAG, "Android TextToSpeech init failed with code: $status")
        }
    }

    fun setVoice(voice: String) {
        _selectedVoice.value = if (voice.equals("male", ignoreCase = true)) "male" else "female"
    }

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false

        val engine = engineProvider()
        val voice = _selectedVoice.value
        val startTime = System.currentTimeMillis()
        var totalSamplesGenerated = 0
        var sampleRateUsed = 24000

        isSpeaking.set(true)

        // Try native core TTS first
        if (engine != null && engine.isLoaded) {
            try {
                val ok = engine.speak(text, voice) { pcm, rate ->
                    if (!isSpeaking.get()) {
                        audioPlayer.stopImmediately()
                        return@speak false
                    }
                    totalSamplesGenerated += pcm.size
                    sampleRateUsed = rate
                    audioPlayer.playChunk(pcm, rate)
                    true
                }

                if (ok && totalSamplesGenerated > 0) {
                    val synthesisTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val audioDurationSec = totalSamplesGenerated.toDouble() / sampleRateUsed
                    val rtf = if (synthesisTimeSec > 0) audioDurationSec / synthesisTimeSec else 0.0

                    _lastRtf.value = rtf
                    _isUsingNativeTts.value = true
                    _ttsEngineStatus.value = "Active: LiquidAI Native TTS (24kHz, ${String.format(Locale.US, "%.2f", rtf)}x RTF)"
                    isSpeaking.set(false)
                    return@withContext true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Native TTS failed: ${t.message}. Falling back to system TTS.")
            }
        }

        // Fallback to Android TextToSpeech
        _isUsingNativeTts.value = false
        _ttsEngineStatus.value = "Fallback: Android System TTS (native TTS unavailable)"
        Log.i(TAG, "Using Android TextToSpeech fallback for: \"$text\"")

        if (isAndroidTtsReady.get() && androidTts != null) {
            androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FinnTTS")
            isSpeaking.set(false)
            return@withContext true
        }

        isSpeaking.set(false)
        return@withContext false
    }

    fun stop() {
        isSpeaking.set(false)
        val engine = engineProvider()
        engine?.cancel()
        audioPlayer.stopImmediately()
        try {
            androidTts?.stop()
        } catch (_: Throwable) {}
    }

    fun release() {
        stop()
        audioPlayer.release()
        try {
            androidTts?.shutdown()
        } catch (_: Throwable) {}
        androidTts = null
    }

    companion object {
        private const val TAG = "SpeechManager"
    }
}
