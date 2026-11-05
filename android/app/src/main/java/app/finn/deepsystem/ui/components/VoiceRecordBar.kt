package app.finn.deepsystem.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun VoiceRecordOverlay(
    isRecording: Boolean,
    onStopAndSend: (simulatedText: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var elapsedSeconds by remember { mutableStateOf(0) }
    val reduceMotion = LocalReduceMotion.current

    LaunchedEffect(isRecording) {
        if (isRecording) {
            elapsedSeconds = 0
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Pulsing animation for mic halo
    val infiniteTransition = rememberInfiniteTransition(label = "VoicePulse")
    val pulseScale by if (!reduceMotion) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseScale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    AnimatedVisibility(
        visible = isRecording,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Cancel Button
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Cancel voice recording"
                        }
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = ErrorRose)
                }

                // Audio Wave Visualizer & Timer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(ErrorRose.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = ErrorRose,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = String.format("Recording 00:%02d", elapsedSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Listening to your voice...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Finish & Send Button
                IconButton(
                    onClick = {
                        val demoUtterance = "Voice query: Explain how on-device AI models protect user privacy."
                        onStopAndSend(demoUtterance)
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(FinnIndigo)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Stop recording and send query"
                        }
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
