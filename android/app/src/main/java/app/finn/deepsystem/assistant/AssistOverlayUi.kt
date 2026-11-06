package app.finn.deepsystem.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.MainActivity
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Compact bottom overlay UI for the Android VoiceInteractionSession.
 * Provides on-device conversational intelligence and screen context awareness.
 */
@Composable
fun AssistOverlayContent(
    initialPrompt: String = "",
    extractedScreenText: String? = null,
    screenshot: Bitmap? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var inputPrompt by remember { mutableStateOf(initialPrompt) }
    var responseText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun executeQuery(promptText: String) {
        if (promptText.isBlank() || isStreaming) return
        inputPrompt = promptText
        responseText = ""
        isStreaming = true
        statusMessage = "Thinking on-device..."

        coroutineScope.launch(Dispatchers.IO) {
            val engine = EngineProvider.getEngine()
            var imageBytes: ByteArray? = null
            if (screenshot != null) {
                try {
                    val stream = ByteArrayOutputStream()
                    screenshot.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    imageBytes = stream.toByteArray()
                } catch (_: Throwable) {}
            }

            val finalPrompt = if (!extractedScreenText.isNullOrBlank()) {
                "Context from screen:\n$extractedScreenText\n\nUser request: $promptText"
            } else {
                promptText
            }

            engine.generateMultimodal(
                prompt = finalPrompt,
                imageBytes = imageBytes,
                callback = TokenCallback { token ->
                    coroutineScope.launch(Dispatchers.Main) {
                        responseText += token
                        statusMessage = null
                    }
                    true
                }
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(FinnIndigo, FinnViolet))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Finn Assistant",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Finn Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "On-Device Intelligence",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Open in Full App Button
                    IconButton(
                        onClick = {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra("assistant_prompt", inputPrompt)
                            }
                            context.startActivity(intent)
                            onDismiss()
                        },
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = "Open in Finn App"
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = "Dismiss Assistant"
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Context badges
            if (screenshot != null || !extractedScreenText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (screenshot != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FinnIndigo.copy(alpha = 0.15f),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = FinnIndigo, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Screen Attached", style = MaterialTheme.typography.labelSmall, color = FinnIndigo, fontSize = 11.sp)
                            }
                        }
                    }
                    if (!extractedScreenText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = FinnCyan.copy(alpha = 0.15f),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Article, contentDescription = null, tint = FinnCyan, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Text Extracted", style = MaterialTheme.typography.labelSmall, color = FinnCyan, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Quick Prompt Chips
            if (responseText.isEmpty() && !isStreaming) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val chips = if (screenshot != null || !extractedScreenText.isNullOrBlank()) {
                        listOf("Summarize screen", "What's this?", "Action items")
                    } else {
                        listOf("Quick note", "Explain this", "Ask Finn")
                    }
                    chips.forEach { chipText ->
                        Surface(
                            onClick = { executeQuery(chipText) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(chipText, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Response Area
            if (responseText.isNotEmpty() || isStreaming || statusMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (responseText.isNotEmpty()) {
                        Text(
                            text = responseText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else if (statusMessage != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = FinnIndigo
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = statusMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input Bar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputPrompt,
                        onValueChange = { inputPrompt = it },
                        placeholder = {
                            Text(
                                "Ask Finn anything...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { executeQuery(inputPrompt) },
                        enabled = inputPrompt.isNotBlank() && !isStreaming,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputPrompt.isNotBlank() && !isStreaming) FinnIndigo
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputPrompt.isNotBlank() && !isStreaming) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
