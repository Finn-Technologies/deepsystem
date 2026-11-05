package app.finn.deepsystem

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.onboarding.OnboardingScreen
import app.finn.deepsystem.ui.theme.FinnTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

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
    fun testOnboardingCompleteFlow() {
        var completedPrompt: String? = null

        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                OnboardingScreen(
                    viewModel = viewModel,
                    onComplete = { completedPrompt = it }
                )
            }
        }

        // 1. Verify Welcome & Network Usage Disclosure
        composeTestRule.onNodeWithText("Welcome to Finn").assertIsDisplayed()
        composeTestRule.onNodeWithText("Network Usage Disclosure").assertIsDisplayed()
        composeTestRule.onNodeWithText("LFM3-Omni-2B Base Model").assertIsDisplayed()

        // 2. Click Continue to go to Permissions step
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()

        // 3. Verify Permissions step (Claude-style permission cards)
        composeTestRule.onNodeWithText("Permissions & Privacy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Microphone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Camera").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()

        // Verify Claude permission buttons
        composeTestRule.onAllNodesWithText("Allow once").onFirst().assertExists()
        composeTestRule.onAllNodesWithText("Always allow").onFirst().assertExists()
        composeTestRule.onAllNodesWithText("Decline").onFirst().assertExists()

        // 4. Click Continue to go to Demo Prompts step
        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.waitForIdle()

        // 5. Verify Demo Prompts step
        composeTestRule.onNodeWithText("You're All Set!").assertIsDisplayed()
        composeTestRule.onNodeWithText("Explain quantum computing in one short sentence.").assertIsDisplayed()

        // 6. Tap the demo prompt
        composeTestRule.onNodeWithText("Explain quantum computing in one short sentence.").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Explain quantum computing in one short sentence.", completedPrompt)
    }
}
