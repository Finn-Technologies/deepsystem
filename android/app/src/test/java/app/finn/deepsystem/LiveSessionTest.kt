package app.finn.deepsystem

import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.live.LiveState
import app.finn.deepsystem.ui.live.FakeLiveSessionController
import app.finn.deepsystem.ui.live.LiveEngineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionTest {

    @Test
    fun testLiveStateCodes() {
        assertEquals(LiveState.Idle, LiveState.fromCode(0))
        assertEquals(LiveState.Listening, LiveState.fromCode(1))
        assertEquals(LiveState.Thinking, LiveState.fromCode(2))
        assertEquals(LiveState.Speaking, LiveState.fromCode(3))
        assertEquals(LiveState.Idle, LiveState.fromCode(99))
    }

    @Test
    fun testFakeEngineMultimodalVideoAndAudio() {
        val engine = FakeFinnEngine(delayPerTokenMs = 0L)
        val tokens = mutableListOf<String>()

        val frames = arrayOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val audio = byteArrayOf(0, 0, 10, 10)

        val stats = engine.generateMultimodal(
            prompt = "What is happening?",
            imageBytes = null,
            audioBytes = audio,
            videoFrames = frames,
            callback = { tok ->
                tokens.add(tok)
                true
            }
        )

        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.joinToString("").contains("video"))
        assertTrue(stats != null && stats.completionTokens > 0)
    }

    @Test
    fun testFakeEngineSpeakEmitsPcm() {
        val engine = FakeFinnEngine()
        var receivedSamples = 0
        var receivedSampleRate = 0

        val ok = engine.speak("Hello from Finn") { pcm, sampleRate ->
            receivedSamples = pcm.size
            receivedSampleRate = sampleRate
            true
        }

        assertTrue(ok)
        assertTrue(receivedSamples > 0)
        assertEquals(24000, receivedSampleRate)
    }

    @Test
    fun testFakeLiveSessionControllerLifecycleAndInterrupt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val controller = FakeLiveSessionController(scope)

        assertEquals(LiveEngineState.Idle, controller.state.value)

        controller.start()
        dispatcher.scheduler.advanceTimeBy(700)
        assertTrue(controller.state.value is LiveEngineState.Listening)

        // Simulate user speaking
        controller.simulateUtterance("What time is it?")
        dispatcher.scheduler.advanceTimeBy(100)
        assertTrue(controller.state.value is LiveEngineState.Thinking)

        // Advance to speaking
        dispatcher.scheduler.advanceTimeBy(800)
        assertTrue(controller.state.value is LiveEngineState.Speaking)

        // Test barge-in interrupt
        controller.interrupt()
        assertTrue(controller.state.value is LiveEngineState.Listening)

        controller.stop()
        assertEquals(LiveEngineState.Idle, controller.state.value)
    }
}
