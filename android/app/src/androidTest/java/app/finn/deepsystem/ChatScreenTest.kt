package app.finn.deepsystem

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.ui.chat.ChatScreen
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.theme.FinnTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeEngine: FakeFinnEngine
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        fakeEngine = FakeFinnEngine(delayPerTokenMs = 5L)
        EngineProvider.setTestEngine(fakeEngine)
        EngineProvider.useFakeEngine = true

        val context = ApplicationProvider.getApplicationContext<FinnApplication>()
        viewModel = ChatViewModel(
            application = context,
            engineFactory = { fakeEngine }
        )
    }

    @After
    fun tearDown() {
        EngineProvider.setTestEngine(null)
    }

    @Test
    fun testSendMessageFlow() {
        composeTestRule.setContent {
            FinnTheme {
                ChatScreen(viewModel = viewModel)
            }
        }

        // Verify Top Bar is visible
        composeTestRule.onNodeWithText("Finn").assertIsDisplayed()

        // Type a message in the input field
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Hello from Compose Test")

        // Click Send button
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        composeTestRule.waitForIdle()
        println("TEST DEBUG: messages size = ${viewModel.uiState.value.messages.size}, prompt = '${viewModel.uiState.value.inputPrompt}', isReady = ${viewModel.uiState.value.isModelReady}")

        // Verify User message appears in conversation
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.messages.any { it.role == "user" && it.content == "Hello from Compose Test" }
        }
        composeTestRule.onNodeWithText("Hello from Compose Test").assertExists()

        // Wait for assistant response text to stream in
        composeTestRule.waitUntil(timeoutMillis = 8000) {
            viewModel.uiState.value.messages.any { it.role == "assistant" && it.content.contains("Finn") }
        }

        // Verify assistant message exists
        composeTestRule.onAllNodesWithText("Finn", substring = true).onFirst().assertExists()
    }
}
