package app.finn.deepsystem.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.model.ChatMessage
import app.finn.deepsystem.ui.components.*
import app.finn.deepsystem.ui.live.FakeLiveSessionController
import app.finn.deepsystem.ui.live.LiveScreen
import app.finn.deepsystem.live.RealLiveSessionController
import app.finn.deepsystem.engine.NativeFinnBridge
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.ui.models.ModelManagerSheet
import app.finn.deepsystem.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    onReplayOnboarding: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Live session controller (uses native C++ live engine when loaded, otherwise Fake)
    val liveController = remember {
        if (NativeFinnBridge.isNativeLoaded() && !EngineProvider.useFakeEngine) {
            RealLiveSessionController(engineProvider = { EngineProvider.getEngine() })
        } else {
            FakeLiveSessionController()
        }
    }
    var isLiveScreenOpen by remember { mutableStateOf(false) }

    // Drawer state for compact phone layout
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Attachments & Voice overlay states
    var isAttachmentsTrayOpen by remember { mutableStateOf(false) }
    var activeAttachment by remember { mutableStateOf<AttachmentItem?>(null) }
    var isVoiceRecording by remember { mutableStateOf(false) }

    // Auto-scroll on new messages or streaming
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Gradient atmospheric background
    val isDark = MaterialTheme.colorScheme.background == BackgroundDarkTop
    val backgroundBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(BackgroundDarkTop, BackgroundDarkMiddle, BackgroundDarkBottom)
        )
    } else {
        Brush.verticalGradient(
            listOf(BackgroundLightTop, Color(0xFFF1F5F9), BackgroundLightBottom)
        )
    }

    // Adaptive Layout: Tablet / Expanded Two-Pane vs Compact Modal Drawer
    val isExpanded = widthSizeClass == WindowWidthSizeClass.Expanded

    if (isLiveScreenOpen) {
        LiveScreen(
            controller = liveController,
            onClose = { isLiveScreenOpen = false }
        )
        return
    }

    if (isExpanded) {
        // Persistent Two-Pane Layout for Tablets / Foldables
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            // Left Pane: Permanent Chat History Sidebar
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                ChatHistoryContent(
                    conversations = uiState.conversations,
                    currentConversationId = uiState.currentConversationId,
                    onSelectConversation = { convId ->
                        viewModel.selectConversation(convId)
                    },
                    onNewChat = { viewModel.createNewConversation() },
                    onRenameConversation = { id, title ->
                        viewModel.renameConversation(id, title)
                    },
                    onDeleteConversation = { id ->
                        viewModel.deleteConversation(id)
                    }
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Right Pane: Active Chat Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                ChatMainCanvas(
                    viewModel = viewModel,
                    uiState = uiState,
                    listState = listState,
                    isCompact = false,
                    onOpenDrawer = {},
                    onOpenLive = { isLiveScreenOpen = true },
                    onOpenModelManager = { viewModel.openModelManager() },
                    onReplayOnboarding = onReplayOnboarding,
                    isAttachmentsTrayOpen = isAttachmentsTrayOpen,
                    onToggleAttachmentsTray = { isAttachmentsTrayOpen = !isAttachmentsTrayOpen },
                    activeAttachment = activeAttachment,
                    onSelectAttachment = { att ->
                        activeAttachment = att
                        if (att.type == AttachmentType.IMAGE) {
                            viewModel.onImageSelected(att.uri, att.bytes)
                        }
                    },
                    onRemoveAttachment = {
                        activeAttachment = null
                        viewModel.removeImage()
                    },
                    isVoiceRecording = isVoiceRecording,
                    onStartVoiceRecording = { isVoiceRecording = true },
                    onStopVoiceRecording = { text ->
                        isVoiceRecording = false
                        viewModel.onInputPromptChanged(text)
                        viewModel.sendMessage()
                    },
                    onCancelVoiceRecording = { isVoiceRecording = false }
                )
            }
        }
    } else {
        // Phone / Compact Layout: Modal Drawer
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(300.dp)
                ) {
                    ChatHistoryContent(
                        conversations = uiState.conversations,
                        currentConversationId = uiState.currentConversationId,
                        onSelectConversation = { convId ->
                            viewModel.selectConversation(convId)
                            coroutineScope.launch { drawerState.close() }
                        },
                        onNewChat = {
                            viewModel.createNewConversation()
                            coroutineScope.launch { drawerState.close() }
                        },
                        onRenameConversation = { id, title ->
                            viewModel.renameConversation(id, title)
                        },
                        onDeleteConversation = { id ->
                            viewModel.deleteConversation(id)
                        }
                    )
                }
            }
        ) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
            ) {
                ChatMainCanvas(
                    viewModel = viewModel,
                    uiState = uiState,
                    listState = listState,
                    isCompact = true,
                    onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                    onOpenLive = { isLiveScreenOpen = true },
                    onOpenModelManager = { viewModel.openModelManager() },
                    onReplayOnboarding = onReplayOnboarding,
                    isAttachmentsTrayOpen = isAttachmentsTrayOpen,
                    onToggleAttachmentsTray = { isAttachmentsTrayOpen = !isAttachmentsTrayOpen },
                    activeAttachment = activeAttachment,
                    onSelectAttachment = { att ->
                        activeAttachment = att
                        if (att.type == AttachmentType.IMAGE) {
                            viewModel.onImageSelected(att.uri, att.bytes)
                        }
                    },
                    onRemoveAttachment = {
                        activeAttachment = null
                        viewModel.removeImage()
                    },
                    isVoiceRecording = isVoiceRecording,
                    onStartVoiceRecording = { isVoiceRecording = true },
                    onStopVoiceRecording = { text ->
                        isVoiceRecording = false
                        viewModel.onInputPromptChanged(text)
                        viewModel.sendMessage()
                    },
                    onCancelVoiceRecording = { isVoiceRecording = false }
                )
            }
        }
    }

    // Model Manager Bottom Sheet
    if (uiState.showModelManager) {
        ModelManagerSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.closeModelManager() }
        )
    }
}

@Composable
fun ChatMainCanvas(
    viewModel: ChatViewModel,
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isCompact: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenModelManager: () -> Unit,
    onReplayOnboarding: () -> Unit,
    isAttachmentsTrayOpen: Boolean,
    onToggleAttachmentsTray: () -> Unit,
    activeAttachment: AttachmentItem?,
    onSelectAttachment: (AttachmentItem) -> Unit,
    onRemoveAttachment: () -> Unit,
    isVoiceRecording: Boolean,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: (String) -> Unit,
    onCancelVoiceRecording: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar
        TopChatBar(
            uiState = uiState,
            isCompact = isCompact,
            onMenuClick = onOpenDrawer,
            onStatusClick = onOpenModelManager,
            onLiveClick = onOpenLive,
            onNewChat = { viewModel.createNewConversation() },
            onReplayOnboarding = onReplayOnboarding
        )

        // Chat Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp)
        ) {
            if (uiState.messages.isEmpty()) {
                item {
                    EmptyStatePrompt(
                        isModelReady = uiState.isModelReady,
                        isFakeEngine = uiState.isUsingFakeEngine,
                        onOpenModelManager = onOpenModelManager,
                        onSelectSamplePrompt = { prompt ->
                            viewModel.onInputPromptChanged(prompt)
                            viewModel.sendMessage()
                        }
                    )
                }
            }

            items(
                items = uiState.messages,
                key = { it.id }
            ) { message ->
                ChatMessageRow(
                    message = message,
                    onRegenerate = { viewModel.regenerateResponse() }
                )
            }
        }
    }

    // Attachments Tray Overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AttachmentsTray(
                isOpen = isAttachmentsTrayOpen,
                onDismiss = onToggleAttachmentsTray,
                onAttachmentSelected = onSelectAttachment
            )

            // Voice Recording Bar Overlay
            VoiceRecordOverlay(
                isRecording = isVoiceRecording,
                onStopAndSend = onStopVoiceRecording,
                onCancel = onCancelVoiceRecording
            )

            // Floating Pill Input Bar
            if (!isVoiceRecording) {
                FloatingPillInput(
                    inputPrompt = uiState.inputPrompt,
                    activeAttachment = activeAttachment,
                    isStreaming = uiState.isStreaming,
                    isModelReady = uiState.isModelReady,
                    onPromptChange = { viewModel.onInputPromptChanged(it) },
                    onToggleAttachments = onToggleAttachmentsTray,
                    onRemoveAttachment = onRemoveAttachment,
                    onVoiceRecord = onStartVoiceRecording,
                    onSend = {
                        viewModel.sendMessage()
                        coroutineScope.launch {
                            if (uiState.messages.isNotEmpty()) {
                                listState.animateScrollToItem(uiState.messages.size)
                            }
                        }
                    },
                    onStop = { viewModel.stopGeneration() },
                    onOpenModelManager = onOpenModelManager
                )
            }
        }
    }
}

@Composable
fun TopChatBar(
    uiState: ChatUiState,
    isCompact: Boolean,
    onMenuClick: () -> Unit,
    onStatusClick: () -> Unit,
    onLiveClick: () -> Unit,
    onNewChat: () -> Unit,
    onReplayOnboarding: () -> Unit
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCompact) {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Open conversation history drawer"
                            }
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(FinnIndigo, FinnViolet))),
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
                // Live Mode Glowing Pill Button
                FilledTonalButton(
                    onClick = onLiveClick,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = FinnCyan.copy(alpha = 0.18f),
                        contentColor = FinnCyan
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Enter real-time Live mode"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Live", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Model Status Badge Pill (Clickable)
                Surface(
                    onClick = onStatusClick,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .height(34.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Model Status: ${if (uiState.isModelReady) "Ready" else "Needs Model"}. Tap to open model manager."
                        }
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

                Spacer(modifier = Modifier.width(4.dp))

                // New Chat Button
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Create new chat conversation"
                        }
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
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
    onOpenModelManager: () -> Unit,
    onSelectSamplePrompt: (String) -> Unit
) {
    val samplePrompts = listOf(
        "Explain quantum computing in one short sentence.",
        "Write a haiku about local AI and privacy.",
        "What are the benefits of offline edge intelligence?"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, start = 16.dp, end = 16.dp),
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

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Private, multimodal intelligence running entirely on-device with zero cloud telemetry.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (!isModelReady && !isFakeEngine) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onOpenModelManager,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.sizeIn(minHeight = 48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setup or Download Model")
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Starter Chips Carousel
        Text(
            text = "Suggested Questions",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        samplePrompts.forEach { prompt ->
            Card(
                onClick = { onSelectSamplePrompt(prompt) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .sizeIn(minHeight = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = FinnIndigo, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageRow(
    message: ChatMessage,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current

    if (message.role == "user") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            if (message.imageUri != null) {
                AsyncImage(
                    model = message.imageUri,
                    contentDescription = "Attached Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .padding(bottom = 6.dp)
                )
            }

            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 20.dp,
                            bottomEnd = 4.dp
                        )
                    )
                    .background(Brush.linearGradient(listOf(FinnIndigo, FinnIndigoDark)))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.content,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 22.sp
                )
            }

            // User Message Actions (Copy & Share)
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("user_msg", message.content))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Copy message"
                        }
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                IconButton(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, message.content)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Message"))
                    },
                    modifier = Modifier
                        .size(28.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Share message"
                        }
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    } else {
        // Assistant Message
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(FinnIndigo.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = FinnIndigo,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Finn",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FinnIndigo
                )
            }

            // Collapsible Working / Reasoning Row
            CollapsibleWorkingRow(
                isStreaming = message.isStreaming,
                stats = message.stats
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Markdown Rendered Body
            if (message.content.isNotBlank()) {
                MarkdownRenderer(
                    text = message.content,
                    modifier = Modifier.padding(start = 2.dp)
                )
            } else if (message.isStreaming) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Assistant Actions (Copy, Regenerate, Share)
            if (!message.isStreaming && message.content.isNotBlank()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("assistant_msg", message.content))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Copy assistant response"
                            }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    IconButton(
                        onClick = onRegenerate,
                        modifier = Modifier
                            .size(32.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Regenerate response"
                            }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    IconButton(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, message.content)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Response"))
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Share assistant response"
                            }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingPillInput(
    inputPrompt: String,
    activeAttachment: AttachmentItem?,
    isStreaming: Boolean,
    isModelReady: Boolean,
    onPromptChange: (String) -> Unit,
    onToggleAttachments: () -> Unit,
    onRemoveAttachment: () -> Unit,
    onVoiceRecord: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Attachment preview chip if selected
        if (activeAttachment != null) {
            AttachmentChipPreview(
                attachment = activeAttachment,
                onRemove = onRemoveAttachment
            )
        }

        // Floating Pill Surface
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary Button: Attachment Tray Toggle
                IconButton(
                    onClick = onToggleAttachments,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Attachments tray: add photo, audio, or document"
                        }
                ) {
                    Icon(
                        imageVector = if (activeAttachment != null) Icons.Default.Attachment else Icons.Default.Add,
                        contentDescription = null,
                        tint = if (activeAttachment != null) FinnIndigo else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Text Field
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
                        .semantics {
                            contentDescription = "Chat prompt input"
                        }
                )

                // Secondary Button: Voice Record
                IconButton(
                    onClick = onVoiceRecord,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Record voice audio message"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Send or Stop Button
                AnimatedContent(
                    targetState = isStreaming,
                    label = "SendStopAnimation"
                ) { streaming ->
                    if (streaming) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ErrorRose)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Stop text generation"
                                }
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        val canSend = inputPrompt.isNotBlank() || activeAttachment != null
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    if (!isModelReady) onOpenModelManager()
                                    else onSend()
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) FinnIndigo else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .semantics {
                                    role = Role.Button
                                    contentDescription = "Send"
                                }
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = null,
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
