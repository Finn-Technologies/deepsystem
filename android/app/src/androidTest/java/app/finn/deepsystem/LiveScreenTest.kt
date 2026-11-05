package app.finn.deepsystem

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.ui.live.FakeLiveSessionController
import app.finn.deepsystem.ui.live.LiveEngineState
import app.finn.deepsystem.ui.live.LiveScreen
import app.finn.deepsystem.ui.theme.FinnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLiveScreenStateTransitionsAndInterrupt() {
        val controller = FakeLiveSessionController()
        var closed = false

        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                LiveScreen(
                    controller = controller,
                    onClose = { closed = true }
                )
            }
        }

        // Wait for connection to transition to listening
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            controller.state.value is LiveEngineState.Listening
        }

        // Verify Top Bar & Orb are displayed
        composeTestRule.onNodeWithText("Finn Live").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Breathing Orb", substring = true).assertExists()

        // Test Mute button toggle
        composeTestRule.onNodeWithContentDescription("Mute microphone").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Controller should be muted", controller.isMuted.value)
        composeTestRule.onNodeWithText("Microphone Muted").assertIsDisplayed()

        // Unmute
        composeTestRule.onNodeWithContentDescription("Unmute microphone").performClick()
        composeTestRule.waitForIdle()
        assertFalse("Controller should be unmuted", controller.isMuted.value)

        // Test Camera toggle
        composeTestRule.onNodeWithContentDescription("Turn on camera preview").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Camera should be enabled", controller.isCameraEnabled.value)

        // Simulate Utterance
        controller.simulateUtterance("Test live speech input")

        // Wait for Thinking state
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            controller.state.value is LiveEngineState.Thinking || controller.state.value is LiveEngineState.Speaking
        }

        // Wait for Speaking state
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            controller.state.value is LiveEngineState.Speaking
        }
        composeTestRule.onNodeWithText("Speaking...").assertIsDisplayed()

        // Interrupt assistant
        composeTestRule.onNodeWithContentDescription("Interrupt assistant").performClick()
        composeTestRule.waitForIdle()

        // Controller should immediately return to Listening
        composeTestRule.waitUntil(timeoutMillis = 3000) {
            controller.state.value is LiveEngineState.Listening
        }
        assertTrue(controller.state.value is LiveEngineState.Listening)

        // Test End Session
        composeTestRule.onNodeWithContentDescription("End live session").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Live session onClose should be invoked", closed)
    }
}
