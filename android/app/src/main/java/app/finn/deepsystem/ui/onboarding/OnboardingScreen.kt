package app.finn.deepsystem.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.model.DownloadState
import app.finn.deepsystem.model.KnownModels
import app.finn.deepsystem.model.ModelTarget
import app.finn.deepsystem.network.ModelDownloadWorker
import app.finn.deepsystem.storage.FinnPreferences
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: ChatViewModel,
    onComplete: (demoPrompt: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { FinnPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(0) } // 0: Welcome & Disclosure, 1: Permissions, 2: Demo Prompts

    val downloader = viewModel.downloader
    val downloadStates by downloader.downloadState.collectAsState()
    val isModelReady = remember(downloadStates) { downloader.areRequiredModelsReady() }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            BackgroundDarkTop,
            BackgroundDarkMiddle,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Progress Dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == currentStep) 24.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) FinnIndigo
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()).togetherWith(
                            slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()).togetherWith(
                            slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "OnboardingStepContent"
            ) { step ->
                when (step) {
                    0 -> WelcomeAndModelStep(
                        downloader = downloader,
                        downloadState = downloadStates[KnownModels.TEXT_MODEL.filename] ?: DownloadState.Idle,
                        isModelReady = isModelReady,
                        onStartDownload = {
                            coroutineScope.launch {
                                ModelDownloadWorker.enqueueDownload(context, KnownModels.TEXT_MODEL)
                                downloader.downloadTarget(KnownModels.TEXT_MODEL)
                            }
                        },
                        onEnableFakeMode = {
                            viewModel.toggleFakeEngine(true)
                        }
                    )
                    1 -> PermissionsStep(
                        prefs = prefs,
                        onAllDecided = { currentStep = 2 }
                    )
                    2 -> DemoPromptsStep(
                        onSelectPrompt = { prompt ->
                            prefs.isOnboardingCompleted = true
                            onComplete(prompt)
                        },
                        onSkip = {
                            prefs.isOnboardingCompleted = true
                            onComplete(null)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.height(24.dp))

            // Navigation Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep -= 1 },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.sizeIn(minWidth = 100.dp, minHeight = 48.dp)
                    ) {
                        Text("Back", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    TextButton(
                        onClick = {
                            prefs.isOnboardingCompleted = true
                            onComplete(null)
                        },
                        modifier = Modifier.sizeIn(minWidth = 100.dp, minHeight = 48.dp)
                    ) {
                        Text(
                            "Skip for now",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Button(
                    onClick = {
                        if (currentStep < 2) {
                            currentStep += 1
                        } else {
                            prefs.isOnboardingCompleted = true
                            onComplete(null)
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FinnIndigo),
                    modifier = Modifier.sizeIn(minWidth = 130.dp, minHeight = 48.dp)
                ) {
                    Text(
                        if (currentStep == 2) "Start Chatting" else "Continue",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeAndModelStep(
    downloader: app.finn.deepsystem.network.ModelDownloader,
    downloadState: DownloadState,
    isModelReady: Boolean,
    onStartDownload: () -> Unit,
    onEnableFakeMode: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Icon Aura
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(FinnIndigo.copy(alpha = 0.4f), Color.Transparent)
                    )
                )
                .border(2.dp, FinnIndigo.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Finn DeepSystem",
                tint = FinnIndigo,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Welcome to Finn",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDark
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Calm, private intelligence running fully on-device",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDarkVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Claude-style Clean Sheet / Card: Explicit Visible Network Disclosure
        Card(
            colors = CardDefaults.cardColors(
                containerColor = SurfaceDarkElevated
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = "Network Disclosure",
                        tint = FinnCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Network Usage Disclosure",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceDark
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Finn operates 100% locally and never sends your chats, voice, or images to any server. A one-time initial download (~1.3 GB) of the LFM3-Omni model is required over Wi-Fi or cellular.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDarkVariant,
                    lineHeight = 21.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Zero tracking • Zero cloud data • 100% Private",
                        style = MaterialTheme.typography.labelSmall,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Model Download Card with Resume & Progress
        Card(
            colors = CardDefaults.cardColors(
                containerColor = SurfaceDark
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LFM3-Omni-2B Base Model",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurfaceDark
                        )
                        Text(
                            text = "4-bit quantized • ~1.3 GB download",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDarkVariant
                        )
                    }

                    // Status Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isModelReady) SuccessGreen.copy(alpha = 0.2f) else WarningAmber.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = if (isModelReady) "Ready" else "Download Needed",
                            color = if (isModelReady) SuccessGreen else WarningAmber,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                when (downloadState) {
                    is DownloadState.Progress -> {
                        LinearProgressIndicator(
                            progress = { downloadState.percent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = FinnCyan,
                            trackColor = BorderDark
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val downloadedMb = downloadState.bytesDownloaded / (1024 * 1024)
                            val totalMb = downloadState.totalBytes / (1024 * 1024)
                            val speedMb = downloadState.speedBytesPerSec / (1024.0 * 1024.0)
                            Text(
                                text = "$downloadedMb MB / $totalMb MB (${(downloadState.percent * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceDarkVariant
                            )
                            Text(
                                text = String.format("%.1f MB/s", speedMb),
                                style = MaterialTheme.typography.labelSmall,
                                color = FinnCyan
                            )
                        }
                    }
                    is DownloadState.Verifying -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = FinnIndigo
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Verifying cryptographic checksum...",
                            style = MaterialTheme.typography.labelSmall,
                            color = FinnIndigo
                        )
                    }
                    is DownloadState.Error -> {
                        Text(
                            text = "Download error: ${downloadState.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ErrorRose
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onStartDownload,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FinnIndigo)
                        ) {
                            Text("Retry Download")
                        }
                    }
                    is DownloadState.Completed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Model downloaded and verified on disk.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SuccessGreen
                            )
                        }
                    }
                    DownloadState.Idle -> {
                        if (!isModelReady) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onStartDownload,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = FinnIndigo),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minHeight = 48.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download Model")
                                }

                                OutlinedButton(
                                    onClick = onEnableFakeMode,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                                ) {
                                    Text("Use Mock Engine")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Model ready for instant inference.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SuccessGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsStep(
    prefs: FinnPreferences,
    onAllDecided: () -> Unit
) {
    var micDecision by remember { mutableStateOf<String?>(if (prefs.micPermissionGranted) "always" else null) }
    var camDecision by remember { mutableStateOf<String?>(if (prefs.cameraPermissionGranted) "always" else null) }
    var notifDecision by remember { mutableStateOf<String?>(if (prefs.notificationsPermissionGranted) "always" else null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.micPermissionGranted = granted
        micDecision = if (granted) "always" else "declined"
    }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.cameraPermissionGranted = granted
        camDecision = if (granted) "always" else "declined"
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.notificationsPermissionGranted = granted
        notifDecision = if (granted) "always" else "declined"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Permissions & Privacy",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDark
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Finn needs permissions only for real-time live voice, vision, and alerts. All processing stays strictly on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDarkVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Claude-style Permission Card 1: Microphone
        ClaudePermissionCard(
            icon = Icons.Default.Mic,
            title = "Microphone",
            description = "Enables real-time voice conversations and voice queries. Speech is transcribed locally using on-device ASR.",
            currentDecision = micDecision,
            onDecline = {
                micDecision = "declined"
                prefs.micPermissionGranted = false
            },
            onAllowOnce = {
                micDecision = "once"
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onAlwaysAllow = {
                micDecision = "always"
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Claude-style Permission Card 2: Camera
        ClaudePermissionCard(
            icon = Icons.Default.Videocam,
            title = "Camera",
            description = "Enables multimodal vision and video input. Images and camera streams are analyzed locally by the vision projector.",
            currentDecision = camDecision,
            onDecline = {
                camDecision = "declined"
                prefs.cameraPermissionGranted = false
            },
            onAllowOnce = {
                camDecision = "once"
                camLauncher.launch(Manifest.permission.CAMERA)
            },
            onAlwaysAllow = {
                camDecision = "always"
                camLauncher.launch(Manifest.permission.CAMERA)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Claude-style Permission Card 3: Notifications
        ClaudePermissionCard(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            description = "Alerts you when large model downloads complete or background tasks finish.",
            currentDecision = notifDecision,
            onDecline = {
                notifDecision = "declined"
                prefs.notificationsPermissionGranted = false
            },
            onAllowOnce = {
                notifDecision = "once"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onAlwaysAllow = {
                notifDecision = "always"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
    }
}

@Composable
fun ClaudePermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    currentDecision: String?,
    onDecline: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDarkElevated
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(FinnIndigo.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = FinnIndigo,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurfaceDark
                    )
                    Text(
                        text = when (currentDecision) {
                            "always" -> "Allowed"
                            "once" -> "Allowed for this session"
                            "declined" -> "Declined"
                            else -> "Action required"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (currentDecision) {
                            "always", "once" -> SuccessGreen
                            "declined" -> WarningAmber
                            else -> OnSurfaceDarkVariant
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDarkVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3-Button Claude Permission Row: Decline | Allow once | Always allow
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (currentDecision == "declined") ErrorRose else OnSurfaceDarkVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Decline $title permission"
                        }
                ) {
                    Text("Decline", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onAllowOnce,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (currentDecision == "once") FinnCyan else OnSurfaceDark
                    ),
                    modifier = Modifier
                        .weight(1.1f)
                        .sizeIn(minHeight = 48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Allow $title once"
                        }
                ) {
                    Text("Allow once", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onAlwaysAllow,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentDecision == "always") SuccessGreen else FinnIndigo
                    ),
                    modifier = Modifier
                        .weight(1.2f)
                        .sizeIn(minHeight = 48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Always allow $title"
                        }
                ) {
                    Text(
                        "Always allow",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DemoPromptsStep(
    onSelectPrompt: (String) -> Unit,
    onSkip: () -> Unit
) {
    val samplePrompts = remember {
        listOf(
            "Explain quantum computing in one short sentence.",
            "Write a short haiku about privacy and local intelligence.",
            "What can you see and do completely on-device without internet?",
            "Give me 3 creative ideas for a personal coding side project."
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(FinnViolet.copy(alpha = 0.3f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = FinnViolet,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "You're All Set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurfaceDark
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Pick a prompt below to start your first private conversation with Finn, or jump right into an open chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDarkVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Demo Prompts Cards
        samplePrompts.forEach { prompt ->
            Card(
                onClick = { onSelectPrompt(prompt) },
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceDarkElevated
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .sizeIn(minHeight = 48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Demo prompt: $prompt"
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = FinnIndigo,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDark,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = OnSurfaceDarkVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
