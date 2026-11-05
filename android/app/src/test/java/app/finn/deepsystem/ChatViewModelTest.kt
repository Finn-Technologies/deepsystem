package app.finn.deepsystem

import android.app.Application
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.model.Conversation
import app.finn.deepsystem.storage.ConversationRepository
import app.finn.deepsystem.ui.chat.ChatViewModel
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
import java.io.File

class FakeStorage : ConversationRepository {
    private val conversations = mutableMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()

    override fun init(dbPath: String): Boolean = true

    override fun createConversation(id: String, title: String): Boolean {
        conversations[id] = Conversation(id, title)
        return true
    }

    override fun renameConversation(id: String, newTitle: String): Boolean {
        val existing = conversations[id]
        if (existing != null) {
            conversations[id] = existing.copy(title = newTitle)
            return true
        }
        return false
    }

    override fun deleteConversation(id: String): Boolean {
        conversations.remove(id)
        messages.remove(id)
        return true
    }

    override fun addMessage(convId: String, role: String, content: String): Boolean {
        val list = messages.getOrPut(convId) { mutableListOf() }
        list.add(ChatMessage(role = role, content = content))
        return true
    }

    override fun listConversations(): List<Conversation> = conversations.values.toList()

    override fun getMessages(convId: String): List<ChatMessage> = messages[convId] ?: emptyList()

    override fun close() {}
}

class FakeApplication : Application() {
    private val tempDir = File(System.getProperty("java.io.tmpdir"), "finn_test_${System.currentTimeMillis()}").apply { mkdirs() }
    override fun getFilesDir(): File = tempDir
    override fun getExternalFilesDir(type: String?): File = File(tempDir, type ?: "files").apply { mkdirs() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeStorage: FakeStorage
    private lateinit var fakeEngine: FakeFinnEngine
    private lateinit var app: Application
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = FakeApplication()
        fakeStorage = FakeStorage()
        fakeEngine = FakeFinnEngine(delayPerTokenMs = 0L) // 0 delay for fast tests
        EngineProvider.setTestEngine(fakeEngine)
        EngineProvider.useFakeEngine = true

        viewModel = ChatViewModel(
            application = app,
            storage = fakeStorage,
            ioDispatcher = testDispatcher,
            engineFactory = { fakeEngine }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        EngineProvider.setTestEngine(null)
    }

    @Test
    fun testInitialState() {
        val state = viewModel.uiState.value
        assertTrue("Fake engine should make model ready", state.isModelReady)
        assertTrue("Should be using fake engine", state.isUsingFakeEngine)
        assertEquals("Prompt should be empty initially", "", state.inputPrompt)
        assertFalse("Should not be streaming initially", state.isStreaming)
    }

    @Test
    fun testSendMessageGeneratesStreamingTokens() = runTest(testDispatcher) {
        viewModel.onInputPromptChanged("Hello Finn!")
        assertEquals("Hello Finn!", viewModel.uiState.value.inputPrompt)

        viewModel.sendMessage()

        // After sending, prompt should be cleared and messages added
        val intermediateState = viewModel.uiState.value
        assertEquals("Input prompt should clear on send", "", intermediateState.inputPrompt)
        assertEquals("Should have 2 messages (user + assistant)", 2, intermediateState.messages.size)
        assertEquals("user", intermediateState.messages[0].role)
        assertEquals("Hello Finn!", intermediateState.messages[0].content)
        assertEquals("assistant", intermediateState.messages[1].role)

        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertFalse("Streaming should finish", finalState.isStreaming)
        val assistantMsg = finalState.messages[1]
        assertTrue("Assistant message should not be empty", assistantMsg.content.isNotEmpty())
        assertTrue("Assistant should mention Finn or greeting", assistantMsg.content.contains("Finn"))
        assertNotNull("Engine stats should be populated", finalState.latestStats)
        assertTrue("Completed tokens should be > 0", finalState.latestStats!!.completionTokens > 0)
    }

    @Test
    fun testMultimodalImageAttachAndSend() = runTest(testDispatcher) {
        val dummyBytes = byteArrayOf(1, 2, 3, 4, 5)
        viewModel.onImageSelected("content://media/test.jpg", dummyBytes)
        viewModel.onInputPromptChanged("Describe what you see")

        viewModel.sendMessage()
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(2, finalState.messages.size)
        val userMsg = finalState.messages[0]
        assertEquals("content://media/test.jpg", userMsg.imageUri)
        val assistantMsg = finalState.messages[1]
        assertTrue("Should describe image", assistantMsg.content.contains("image", ignoreCase = true))
    }

    @Test
    fun testStopGenerationStopsImmediately() = runTest(testDispatcher) {
        val slowEngine = FakeFinnEngine(delayPerTokenMs = 0L)
        slowEngine.customTokens = (1..50).map { "tok$it" }
        val slowVm = ChatViewModel(app, fakeStorage, testDispatcher) { slowEngine }

        slowVm.onInputPromptChanged("Tell a long story")
        slowVm.sendMessage()

        assertTrue("Should be streaming", slowVm.uiState.value.isStreaming)

        slowVm.stopGeneration()

        assertFalse("Should stop streaming after stopGeneration()", slowVm.uiState.value.isStreaming)
        assertTrue("Engine should be marked cancelled", slowEngine.isCancelled())
    }

    @Test
    fun testBuildChatMLPromptFormatsMultiTurnProperly() {
        val messages = listOf(
            ChatMessage(role = "user", content = "First question"),
            ChatMessage(role = "assistant", content = "First answer", isStreaming = false),
            ChatMessage(role = "user", content = "Follow-up question")
        )

        val prompt = viewModel.buildChatMLPrompt(messages)

        assertTrue("Must include system block", prompt.contains("<|im_start|>system\n"))
        assertTrue("Must include first user turn", prompt.contains("<|im_start|>user\nFirst question<|im_end|>\n"))
        assertTrue("Must include first assistant turn", prompt.contains("<|im_start|>assistant\nFirst answer<|im_end|>\n"))
        assertTrue("Must include follow-up user turn", prompt.contains("<|im_start|>user\nFollow-up question<|im_end|>\n"))
        assertTrue("Must end with assistant invitation", prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testNewConversationResetsMessages() = runTest(testDispatcher) {
        viewModel.onInputPromptChanged("Message 1")
        viewModel.sendMessage()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.messages.size)

        viewModel.createNewConversation()

        val state = viewModel.uiState.value
        assertEquals("Messages should reset to empty on new chat", 0, state.messages.size)
        assertEquals("", state.inputPrompt)
    }
}
