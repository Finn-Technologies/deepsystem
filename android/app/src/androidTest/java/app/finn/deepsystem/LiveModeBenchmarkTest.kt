package app.finn.deepsystem

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.engine.NativeFinnBridge
import app.finn.deepsystem.engine.NativeFinnEngine
import app.finn.deepsystem.live.LiveState
import app.finn.deepsystem.live.NativeLiveSession
import app.finn.deepsystem.model.KnownModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class LiveModeBenchmarkTest {

    private fun getModelFile(context: Context, filename: String): File {
        val modelsDir = File(context.filesDir, "models")
        return File(modelsDir, filename).takeIf { it.exists() }
            ?: File("/data/local/tmp", filename)
    }

    @Test(timeout = 300000)
    fun benchmarkSpeechOutputTts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = getModelFile(context, KnownModels.TEXT_MODEL.filename)
        val mmprojFile = getModelFile(context, KnownModels.MMPROJ_MODEL.filename)

        if (!modelFile.exists() || !mmprojFile.exists()) {
            Log.w(TAG, "Skipping TTS benchmark: model or mmproj not found at /data/local/tmp")
            return
        }

        Log.i(TAG, "Initializing NativeFinnEngine for Speech Output (TTS) benchmark...")
        val engine = NativeFinnEngine(isFakeEngine = false)

        val loaded = engine.load(
            modelPath = modelFile.absolutePath,
            mmprojPath = mmprojFile.absolutePath,
            nCtx = 1024,
            nThreads = 4,
            nGpuLayers = 0,
            useMmap = true
        )
        assertTrue("Multimodal engine load must succeed for TTS", loaded)

        val testPhrase = "Hello from Finn DeepSystem."
        val totalSamples = AtomicInteger(0)
        val sampleRate = AtomicInteger(24000)

        Log.i(TAG, "Running TTS Synthesis benchmark for: \"$testPhrase\"")
        val tStart = System.currentTimeMillis()

        val ok = engine.speak(testPhrase, voice = "female") { pcm, rate ->
            totalSamples.addAndGet(pcm.size)
            sampleRate.set(rate)
            true
        }

        val tEnd = System.currentTimeMillis()
        val synthesisTimeMs = tEnd - tStart
        val synthesisTimeSec = synthesisTimeMs / 1000.0
        val audioDurationSec = totalSamples.get().toDouble() / sampleRate.get()
        val rtf = if (synthesisTimeSec > 0) audioDurationSec / synthesisTimeSec else 0.0

        assertTrue("Native TTS speak must return true", ok)
        assertTrue("Native TTS must generate audio samples", totalSamples.get() > 0)

        Log.i(TAG, "FINN_BENCH: TTS_PHRASE='$testPhrase'")
        Log.i(TAG, "FINN_BENCH: TTS_SAMPLES_GENERATED=${totalSamples.get()}")
        Log.i(TAG, "FINN_BENCH: TTS_SAMPLE_RATE=${sampleRate.get()}")
        Log.i(TAG, "FINN_BENCH: TTS_SYNTHESIS_TIME_MS=$synthesisTimeMs")
        Log.i(TAG, "FINN_BENCH: TTS_AUDIO_DURATION_SEC=$audioDurationSec")
        Log.i(TAG, "FINN_BENCH: TTS_REALTIME_FACTOR=$rtf")

        engine.close()
    }

    @Test(timeout = 300000)
    fun benchmarkLiveModeEndToEndLatencyWithSyntheticAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = getModelFile(context, KnownModels.TEXT_MODEL.filename)
        val mmprojFile = getModelFile(context, KnownModels.MMPROJ_MODEL.filename)

        if (!modelFile.exists()) {
            Log.w(TAG, "Skipping Live benchmark: model file missing")
            return
        }

        val engine = NativeFinnEngine(isFakeEngine = false)
        val loaded = engine.load(
            modelPath = modelFile.absolutePath,
            mmprojPath = if (mmprojFile.exists()) mmprojFile.absolutePath else "",
            nCtx = 1024,
            nThreads = 4,
            nGpuLayers = 0,
            useMmap = true
        )
        assertTrue("Engine load must succeed", loaded)

        val session = NativeLiveSession(
            engineHandle = engine.nativeHandle,
            vadThreshold = 400.0f,
            silenceTimeoutMs = 300,
            voice = "female"
        )

        val firstAudioLatch = CountDownLatch(1)
        val tFirstAudio = AtomicLong(0)
        val receivedPcmSamples = AtomicInteger(0)

        session.setAudioOutputListener { pcm, _ ->
            if (tFirstAudio.compareAndSet(0, System.currentTimeMillis())) {
                firstAudioLatch.countDown()
            }
            receivedPcmSamples.addAndGet(pcm.size)
            true
        }

        session.start()
        assertEquals(LiveState.Listening, session.state.value)

        // Generate synthetic sine wave speech (16kHz, 320 samples per 20ms frame)
        val speechFrame = ShortArray(320) { i ->
            (2500.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toInt().toShort()
        }
        val silenceFrame = ShortArray(320) { 0 }

        Log.i(TAG, "Injecting synthetic speech audio into LiveSession...")
        // Feed 15 frames of speech (300ms)
        for (i in 0 until 15) {
            session.feedAudio(speechFrame)
            Thread.sleep(20)
        }

        val tEosStart = System.currentTimeMillis()
        Log.i(TAG, "Injecting silence to trigger VAD end-of-speech...")
        // Feed silence frames to exceed silenceTimeoutMs (16 frames * 20ms = 320ms > 300ms)
        for (i in 0 until 16) {
            session.feedAudio(silenceFrame)
            Thread.sleep(20)
        }

        // Wait for first audio output from sentence-chunked streaming TTS
        val gotAudio = firstAudioLatch.await(60, TimeUnit.SECONDS)
        val measuredLatency = session.lastLatencyMs.value

        Log.i(TAG, "FINN_BENCH: LIVE_GOT_AUDIO=$gotAudio")
        Log.i(TAG, "FINN_BENCH: LIVE_E2E_LATENCY_MS=$measuredLatency")
        Log.i(TAG, "FINN_BENCH: LIVE_AUDIO_SAMPLES=${receivedPcmSamples.get()}")

        session.stop()
        session.close()
        engine.close()
    }

    @Test(timeout = 60000)
    fun benchmarkBargeInInterruption() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelFile = getModelFile(context, KnownModels.TEXT_MODEL.filename)

        if (!modelFile.exists()) return

        val engine = NativeFinnEngine(isFakeEngine = false)
        assertTrue(engine.load(modelPath = modelFile.absolutePath, nCtx = 512, nThreads = 4))

        val session = NativeLiveSession(
            engineHandle = engine.nativeHandle,
            vadThreshold = 400.0f,
            silenceTimeoutMs = 100
        )

        session.start()
        session.triggerEndOfSpeech()

        // Wait until it enters thinking
        Thread.sleep(50)

        // Inject high-energy barge-in burst (3 loud frames)
        val loudFrame = ShortArray(320) { 3000 }
        val tBargeStart = System.currentTimeMillis()
        session.feedAudio(loudFrame)
        session.feedAudio(loudFrame)
        session.feedAudio(loudFrame)
        val tBargeEnd = System.currentTimeMillis()
        val bargeMs = tBargeEnd - tBargeStart

        assertEquals(LiveState.Listening, session.state.value)
        assertTrue("Barge-in detection must complete within 200ms", bargeMs < 200)
        Log.i(TAG, "FINN_BENCH: BARGE_IN_RESPONSE_MS=$bargeMs")

        session.stop()
        session.close()
        engine.close()
    }

    companion object {
        private const val TAG = "LiveModeBenchmarkTest"
    }
}
