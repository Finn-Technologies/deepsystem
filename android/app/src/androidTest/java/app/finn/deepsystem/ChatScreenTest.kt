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
        composeTestRule.onNodeWithText("Ask Finn anything...").performTextInput("Hello from Compose Test")

        // Click Send button
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        // Verify User message appears on screen
        composeTestRule.onNodeWithText("Hello from Compose Test").assertIsDisplayed()

        // Wait for assistant response text to stream in
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.messages.any { it.role == "assistant" && it.content.contains("Finn") }
        }

        // Verify assistant message is visible
        composeTestRule.onAllNodesWithText("Finn", substring = true).onFirst().assertIsDisplayed()
    }
}
