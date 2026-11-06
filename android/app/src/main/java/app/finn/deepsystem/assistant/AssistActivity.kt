package app.finn.deepsystem.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.finn.deepsystem.ui.theme.FinnTheme

/**
 * Handles ACTION_ASSIST intents dispatched by custom launchers, accessibility services,
 * or direct intent commands (`am start -a android.intent.action.ASSIST`).
 */
class AssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FinnTheme(darkTheme = true) {
                // Dimmed background that dismisses when tapped outside
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { finish() }
                ) {
                    AssistOverlayContent(
                        onDismiss = { finish() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {} // Block tap from dismissing when tapping inside sheet
                    )
                }
            }
        }
    }
}
