package app.finn.deepsystem.ui.live

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.ui.theme.*

@Composable
fun LiveScreen(
    controller: LiveSessionController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsState()
    val isCameraEnabled by controller.isCameraEnabled.collectAsState()
    val isMuted by controller.isMuted.collectAsState()
    val captions by controller.captions.collectAsState()
    val captionsListState = rememberLazyListState()

    // Auto-start live session on entry
    LaunchedEffect(Unit) {
        controller.start()
    }

    // Scroll captions to bottom as dialogue occurs
    LaunchedEffect(captions.size, captions.lastOrNull()?.text) {
        if (captions.isNotEmpty()) {
            captionsListState.animateScrollToItem(captions.size - 1)
        }
    }

    // Soft Gemini-like atmosphere gradient
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            BackgroundDarkTop,
            Color(0xFF0C1322),
            BackgroundDarkBottom
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Live Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        controller.stop()
                        onClose()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Exit Live Mode"
                        }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = OnSurfaceDark
                    )
                }

                // Live Mode Pill Indicator
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceDarkElevated.copy(alpha = 0.8f),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isMuted) WarningAmber else FinnEmerald)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMuted) "Muted" else "Finn Live",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OnSurfaceDark
                        )
                    }
                }

                // Quick Prompt Simulation Action (for manual testing/demoing)
                IconButton(
                    onClick = {
                        controller.simulateUtterance("What capabilities are running on this device?")
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Simulate user voice query"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = FinnCyan
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Center: Breathing Orb
            BreathingOrb(
                state = state,
                onTapOrb = {
                    if (state is LiveEngineState.Speaking || state is LiveEngineState.Thinking) {
                        controller.interrupt()
                    } else {
                        controller.simulateUtterance("Hello Finn, can you hear me?")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // State Label Badge
            StateLabelBadge(state = state, isMuted = isMuted)

            Spacer(modifier = Modifier.height(16.dp))

            // Real-Time Dialogue Captions
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceDark.copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.2f)
                    .padding(horizontal = 24.dp)
            ) {
                LazyColumn(
                    state = captionsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (captions.isEmpty()) {
                        item {
                            Text(
                                text = "Start speaking or tap the orb to ask a question...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceDarkVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp)
                            )
                        }
                    }

                    items(captions) { caption ->
                        LiveCaptionItemView(caption = caption)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Floating Pill Controls Bar
            FloatingLiveControls(
                state = state,
                isMuted = isMuted,
                isCameraEnabled = isCameraEnabled,
                onToggleMute = { controller.toggleMute() },
                onToggleCamera = { controller.toggleCamera() },
                onInterrupt = { controller.interrupt() },
                onEndSession = {
                    controller.stop()
                    onClose()
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        // Camera Preview Floating Picture-in-Picture
        if (isCameraEnabled) {
            LiveCameraPip(
                onClose = { controller.toggleCamera() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 70.dp, end = 16.dp)
            )
        }
    }
}

@Composable
fun StateLabelBadge(
    state: LiveEngineState,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val (label, dotColor) = when {
        isMuted -> "Microphone Muted" to WarningAmber
        state is LiveEngineState.Connecting -> "Connecting to Engine..." to FinnIndigo
        state is LiveEngineState.Listening -> "Listening..." to FinnCyan
        state is LiveEngineState.Thinking -> "Thinking..." to FinnViolet
        state is LiveEngineState.Speaking -> "Speaking..." to FinnTeal
        state is LiveEngineState.Error -> "Error" to ErrorRose
        else -> "Ready" to FinnEmerald
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDarkElevated.copy(alpha = 0.9f),
        modifier = modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnSurfaceDark
            )
        }
    }
}

@Composable
fun LiveCaptionItemView(caption: LiveCaption) {
    val isUser = caption.speaker == "You"
    val isSystem = caption.speaker == "System"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = caption.speaker,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    isUser -> FinnCyan
                    isSystem -> WarningAmber
                    else -> FinnIndigo
                }
            )
        }

        Text(
            text = caption.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSystem) OnSurfaceDarkVariant else OnSurfaceDark,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun FloatingLiveControls(
    state: LiveEngineState,
    isMuted: Boolean,
    isCameraEnabled: Boolean,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onInterrupt: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canInterrupt = state is LiveEngineState.Speaking || state is LiveEngineState.Thinking

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = SurfaceDarkElevated.copy(alpha = 0.95f),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute / Unmute Button
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isMuted) WarningAmber.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isMuted) "Unmute microphone" else "Mute microphone"
                    }
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isMuted) WarningAmber else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Camera Toggle Button
            IconButton(
                onClick = onToggleCamera,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isCameraEnabled) FinnCyan.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isCameraEnabled) "Turn off camera preview" else "Turn on camera preview"
                    }
            ) {
                Icon(
                    imageVector = if (isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = if (isCameraEnabled) FinnCyan else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Interrupt Button
            IconButton(
                onClick = onInterrupt,
                enabled = canInterrupt,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canInterrupt) WarningAmber else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .semantics {
                        role = Role.Button
                        contentDescription = "Interrupt assistant"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = if (canInterrupt) Color.Black else OnSurfaceDarkVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // End Live Session Button
            IconButton(
                onClick = onEndSession,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ErrorRose)
                    .semantics {
                        role = Role.Button
                        contentDescription = "End live session"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
