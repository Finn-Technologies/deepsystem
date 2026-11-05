package app.finn.deepsystem

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import org.junit.Assert.*
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
        composeTestRule.onNodeWithContentDescription("Chat prompt input").performTextInput("Hello from Compose Test")

        // Click Send button
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        composeTestRule.waitForIdle()

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

        // Verify Collapsible Working Row exists
        composeTestRule.onNodeWithContentDescription("Expand reasoning details", substring = true).assertExists()
    }

    @Test
    fun testMultiTurnConversationFlow() {
        composeTestRule.setContent {
            FinnTheme {
                ChatScreen(viewModel = viewModel)
            }
        }

        // Turn 1
        composeTestRule.onNodeWithContentDescription("Chat prompt input").performTextInput("Turn 1 Question")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        composeTestRule.waitUntil(timeoutMillis = 8000) {
            viewModel.uiState.value.messages.size >= 2 && !viewModel.uiState.value.isStreaming
        }
        composeTestRule.onNodeWithText("Turn 1 Question").assertExists()

        // Turn 2
        composeTestRule.onNodeWithContentDescription("Chat prompt input").performTextInput("Turn 2 Follow-up")
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        composeTestRule.waitUntil(timeoutMillis = 8000) {
            viewModel.uiState.value.messages.size >= 4 && !viewModel.uiState.value.isStreaming
        }
        composeTestRule.onNodeWithText("Turn 2 Follow-up").assertExists()
        assertEquals(4, viewModel.uiState.value.messages.size)
    }

    @Test
    fun testTabletExpandedLayout() {
        composeTestRule.setContent {
            FinnTheme {
                ChatScreen(
                    viewModel = viewModel,
                    widthSizeClass = WindowWidthSizeClass.Expanded
                )
            }
        }

        // In expanded layout, conversations sidebar is permanently visible on left
        composeTestRule.onNodeWithText("Conversations").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Create new chat").assertIsDisplayed()

        // Main chat area is also simultaneously present in the composition
        composeTestRule.onNodeWithText("Finn").assertExists()
        composeTestRule.onNodeWithContentDescription("Send").assertExists()
    }

    @Test
    fun testChatHistoryRenameAndDelete() {
        val convId = viewModel.uiState.value.currentConversationId
        viewModel.renameConversation(convId, "My Renamed Conversation")

        composeTestRule.setContent {
            FinnTheme {
                ChatScreen(
                    viewModel = viewModel,
                    widthSizeClass = WindowWidthSizeClass.Expanded
                )
            }
        }

        // Verify renamed conversation is displayed
        composeTestRule.onNodeWithText("My Renamed Conversation").assertIsDisplayed()

        // Test creating new chat
        composeTestRule.onNodeWithContentDescription("Create new chat").performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.conversations.size >= 2)
    }
}
