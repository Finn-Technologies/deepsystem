package app.finn.deepsystem

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FakeFinnEngine
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.model.EngineStats
import app.finn.deepsystem.storage.ConversationRepository
import app.finn.deepsystem.ui.chat.ChatScreen
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.live.FakeLiveSessionController
import app.finn.deepsystem.ui.live.LiveCaption
import app.finn.deepsystem.ui.live.LiveEngineState
import app.finn.deepsystem.ui.live.LiveScreen
import app.finn.deepsystem.ui.onboarding.OnboardingScreen
import app.finn.deepsystem.ui.theme.FinnTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeEngine: FakeFinnEngine
    private lateinit var viewModel: ChatViewModel

    private fun saveScreenshot(name: String) {
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<FinnApplication>()
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        try {
            val pubDir = File("/sdcard/Download")
            if (pubDir.exists() && pubDir.canWrite()) {
                file.copyTo(File(pubDir, name), overwrite = true)
            }
        } catch (_: Throwable) {}
        println("Saved screenshot to ${file.absolutePath}")
    }

    @Before
    fun setUp() {
        fakeEngine = FakeFinnEngine(delayPerTokenMs = 2L)
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
    fun capturePhoneDarkOnboarding() {
        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                OnboardingScreen(
                    viewModel = viewModel,
                    onComplete = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        saveScreenshot("phone_dark_onboarding.png")
    }

    @Test
    fun capturePhoneDarkChat() {
        // Pre-populate with markdown and working row
        viewModel.onInputPromptChanged("Explain quantum computing with code sample")
        viewModel.sendMessage()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.messages.size >= 2 && !viewModel.uiState.value.isStreaming
        }

        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                ChatScreen(
                    viewModel = viewModel,
                    widthSizeClass = WindowWidthSizeClass.Compact
                )
            }
        }
        composeTestRule.waitForIdle()
        saveScreenshot("phone_dark_chat.png")
    }

    @Test
    fun capturePhoneLightChat() {
        viewModel.onInputPromptChanged("What are the advantages of on-device privacy?")
        viewModel.sendMessage()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.messages.size >= 2 && !viewModel.uiState.value.isStreaming
        }

        composeTestRule.setContent {
            FinnTheme(darkTheme = false) {
                ChatScreen(
                    viewModel = viewModel,
                    widthSizeClass = WindowWidthSizeClass.Compact
                )
            }
        }
        composeTestRule.waitForIdle()
        saveScreenshot("phone_light_chat.png")
    }

    @Test
    fun capturePhoneDarkLive() {
        val controller = FakeLiveSessionController()
        controller.start()

        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                LiveScreen(
                    controller = controller,
                    onClose = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        saveScreenshot("phone_dark_live.png")
    }

    @Test
    fun captureTabletExpandedChat() {
        viewModel.createNewConversation()
        viewModel.onInputPromptChanged("Tablet List-Detail view test")
        viewModel.sendMessage()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            viewModel.uiState.value.messages.size >= 2 && !viewModel.uiState.value.isStreaming
        }

        composeTestRule.setContent {
            FinnTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(
                        viewModel = viewModel,
                        widthSizeClass = WindowWidthSizeClass.Expanded
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        saveScreenshot("tablet_expanded_chat.png")
    }
}
