package app.finn.deepsystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import app.finn.deepsystem.storage.FinnPreferences
import app.finn.deepsystem.home.AppShell
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.onboarding.OnboardingScreen
import app.finn.deepsystem.ui.theme.FinnTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = FinnPreferences(this)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val widthSizeClass = windowSizeClass.widthSizeClass
            var showOnboarding by remember { mutableStateOf(!prefs.isOnboardingCompleted) }

            FinnTheme(
                darkTheme = prefs.isDarkModeForced ?: androidx.compose.foundation.isSystemInDarkTheme(),
                forceReduceMotion = if (prefs.isReduceMotionForced) true else null
            ) {
                if (showOnboarding) {
                    OnboardingScreen(
                        viewModel = viewModel,
                        onComplete = { demoPrompt ->
                            showOnboarding = false
                            if (demoPrompt != null) {
                                viewModel.onInputPromptChanged(demoPrompt)
                                viewModel.sendMessage()
                            }
                        }
                    )
                } else {
                    AppShell(
                        chatViewModel = viewModel,
                        widthSizeClass = widthSizeClass,
                        onReplayOnboarding = { showOnboarding = true },
                        openNoteId = intent?.getStringExtra(EXTRA_OPEN_NOTE_ID)
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_NOTE_ID = "app.finn.deepsystem.OPEN_NOTE_ID"
    }
}
