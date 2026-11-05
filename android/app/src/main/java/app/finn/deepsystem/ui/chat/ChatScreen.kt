package app.finn.deepsystem.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.ui.models.ModelManagerSheet
import app.finn.deepsystem.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                viewModel.onImageSelected(uri.toString(), bytes)
            } catch (e: Exception) {
                // Handled
            }
        }
    }

    // Auto-scroll on new messages or streaming tokens
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Soft gradient background brush
    val isDark = MaterialTheme.colorScheme.background == BackgroundDarkTop
    val backgroundBrush = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                BackgroundDarkTop,
                Color(0xFF0F1523),
                BackgroundDarkBottom
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                BackgroundLightTop,
                Color(0xFFF3F6FA),
                BackgroundLightBottom
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top App Bar
            TopChatBar(
                uiState = uiState,
                onStatusClick = { viewModel.openModelManager() },
                onNewChat = { viewModel.createNewConversation() }
            )

            // Chat Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        EmptyStatePrompt(
                            isModelReady = uiState.isModelReady,
                            isFakeEngine = uiState.isUsingFakeEngine,
                            onOpenModelManager = { viewModel.openModelManager() }
                        )
                    }
                }

                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { it / 4 }
                    ) {
                        ChatMessageItem(message = message)
                    }
                }
            }
        }

        // Floating Pill Input Bar pinned to the bottom
        FloatingPillInput(
            inputPrompt = uiState.inputPrompt,
            selectedImageUri = uiState.selectedImageUri,
            isStreaming = uiState.isStreaming,
            isModelReady = uiState.isModelReady,
            onPromptChange = { viewModel.onInputPromptChanged(it) },
            onAttachImage = { imagePickerLauncher.launch("image/*") },
            onRemoveImage = { viewModel.removeImage() },
            onSend = {
                viewModel.sendMessage()
                coroutineScope.launch {
                    if (uiState.messages.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.messages.size)
                    }
                }
            },
            onStop = { viewModel.stopGeneration() },
            onOpenModelManager = { viewModel.openModelManager() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .imePadding()
        )

        // Model Manager Bottom Sheet
        if (uiState.showModelManager) {
            ModelManagerSheet(
                viewModel = viewModel,
                onDismiss = { viewModel.closeModelManager() }
            )
        }
    }
}

@Composable
fun TopChatBar(
    uiState: ChatUiState,
    onStatusClick: () -> Unit,
    onNewChat: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(FinnIndigo, FinnViolet))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = "Finn",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "Finn",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "DeepSystem Local AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Model Status Badge Pill (Clickable)
                Surface(
                    onClick = onStatusClick,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        uiState.isUsingFakeEngine -> FinnViolet
                                        uiState.isModelLoading -> WarningAmber
                                        uiState.isModelReady -> SuccessGreen
                                        else -> ModelMissingBadge
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                uiState.isUsingFakeEngine -> "Fake"
                                uiState.isModelLoading -> "Loading..."
                                uiState.isModelReady -> "LFM3 Ready"
                                else -> "Needs Model"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStatePrompt(
    isModelReady: Boolean,
    isFakeEngine: Boolean,
    onOpenModelManager: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(FinnIndigo.copy(alpha = 0.25f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = FinnIndigo,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Welcome to Finn",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Fast, multimodal, privacy-first AI running 100% locally on Apple Silicon and Android NDK.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (!isModelReady && !isFakeEngine) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onOpenModelManager,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setup or Download Model")
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    if (message.role == "user") {
        // Rounded User Bubble
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            // Optional attached image preview
            if (message.imageUri != null) {
                AsyncImage(
                    model = message.imageUri,
                    contentDescription = "Attached Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .padding(bottom = 6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 20.dp,
                            bottomEnd = 4.dp
                        )
                    )
                    .background(
                        Brush.linearGradient(
                            listOf(FinnIndigo, FinnIndigoDark)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.content,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 22.sp
                )
            }
        }
    } else {
        // Unbubbled Assistant Text with high typography clarity
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(FinnIndigo.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = FinnIndigo,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Finn",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FinnIndigo
                )

                // Benchmark stats chip if available
                if (message.stats != null && message.stats.completionTokens > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• ${String.format("%.1f", message.stats.tokensPerSecond)} tok/s (TTFT ${message.stats.ttftMs.toInt()}ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Assistant unbubbled body text
            Text(
                text = message.content.ifEmpty { if (message.isStreaming) "..." else "" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 24.sp,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
fun FloatingPillInput(
    inputPrompt: String,
    selectedImageUri: String?,
    isStreaming: Boolean,
    isModelReady: Boolean,
    onPromptChange: (String) -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Image preview chip if attached
        if (selectedImageUri != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .height(48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "Attachment preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Image attached",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove image",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Floating Pill Surface
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach Image Button
                IconButton(
                    onClick = onAttachImage,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Attach image",
                        tint = if (selectedImageUri != null) FinnIndigo else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Text Input
                TextField(
                    value = inputPrompt,
                    onValueChange = onPromptChange,
                    placeholder = {
                        Text(
                            text = if (isModelReady) "Ask Finn anything..." else "Model needed to chat...",
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
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )

                // Send or Stop Button with spring animation
                AnimatedContent(
                    targetState = isStreaming,
                    label = "SendStopAnimation"
                ) { streaming ->
                    if (streaming) {
                        // Stop button
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ErrorRose)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop generation",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        // Send button
                        val canSend = (inputPrompt.isNotBlank() || selectedImageUri != null)
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    if (!isModelReady) {
                                        onOpenModelManager()
                                    } else {
                                        onSend()
                                    }
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) FinnIndigo else MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
