package app.finn.deepsystem

import android.app.Application
import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.model.EngineStats
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.live.FakeLiveSessionController
import app.finn.deepsystem.ui.live.LiveSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Engine that records multimodal inputs and the number of token callbacks. */
private class RecordingEngine(private val inner: FakeFinnEngine) : FinnEngine by inner {
    var lastAudio: ByteArray? = null
    var lastFrames: Array<ByteArray>? = null
    override fun generate(
        prompt: String, imageBytes: ByteArray?, temperature: Float, maxTokens: Int, callback: TokenCallback
    ): EngineStats? = generateMultimodal(prompt, imageBytes, null, null, temperature, maxTokens, callback)

    override fun generateMultimodal(
        prompt: String, imageBytes: ByteArray?, audioBytes: ByteArray?, videoFrames: Array<ByteArray>?,
        temperature: Float, maxTokens: Int, callback: TokenCallback
    ): EngineStats? {
        lastAudio = audioBytes
        lastFrames = videoFrames
        return inner.generateMultimodal(prompt, imageBytes, audioBytes, videoFrames, temperature, maxTokens, callback)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationWiringTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application

    @Before fun setUp() { Dispatchers.setMain(dispatcher); app = FakeApplication() }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(engine: FinnEngine) = ChatViewModel(app, FakeStorage(), dispatcher) { engine }

    @Test
    fun voiceMessageReachesEngineAsAudio() = runTest(dispatcher) {
        val engine = RecordingEngine(FakeFinnEngine(delayPerTokenMs = 0L))
        val model = vm(engine)
        val pcm = ByteArray(6400) { (it % 100).toByte() }
        model.sendVoiceMessage(pcm)
        advanceUntilIdle()
        assertArrayEquals(pcm, engine.lastAudio)
        val msgs = model.uiState.value.messages
        assertEquals("[Voice message]", msgs[0].content)
        assertTrue(msgs[1].content.isNotEmpty())
        assertNull("audio attachment cleared after send", model.uiState.value.selectedAudioPcm)
    }

    @Test
    fun videoFramesReachEngine() = runTest(dispatcher) {
        val engine = RecordingEngine(FakeFinnEngine(delayPerTokenMs = 0L))
        val model = vm(engine)
        val f = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))
        // simulate processed video attachment state through the public state holder
        val field = ChatViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(model) as kotlinx.coroutines.flow.MutableStateFlow<app.finn.deepsystem.ui.chat.ChatUiState>
        flow.value = flow.value.copy(selectedVideoFrames = f)
        model.sendMessage()
        advanceUntilIdle()
        assertEquals(2, engine.lastFrames?.size)
        assertEquals("[Video attached]", model.uiState.value.messages[0].content)
    }

    @Test
    fun streamingFinalTextIsCompleteDespiteBatching() = runTest(dispatcher) {
        val fake = FakeFinnEngine(delayPerTokenMs = 0L)
        fake.customTokens = (1..200).map { "w$it " }
        val model = vm(fake)
        model.onInputPromptChanged("go")
        model.sendMessage()
        advanceUntilIdle()
        val text = model.uiState.value.messages[1].content
        assertTrue(text.startsWith("w1 ") && text.trim().endsWith("w200"))
        assertFalse(model.uiState.value.messages[1].isStreaming)
    }

    @Test
    fun factoryForceFakeReturnsFakeController() {
        val c = LiveSessionFactory.create(scope = CoroutineScope(dispatcher), forceFakeOverride = true)
        assertTrue(c is FakeLiveSessionController)
        // Without native lib (JVM unit tests) the factory also falls back to fake.
        assertTrue(LiveSessionFactory.create(scope = CoroutineScope(dispatcher)) is FakeLiveSessionController)
    }
}
