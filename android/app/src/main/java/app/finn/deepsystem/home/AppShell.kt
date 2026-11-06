package app.finn.deepsystem.home

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import app.finn.deepsystem.background.NetworkLedger
import app.finn.deepsystem.background.PrivacyLedgerScreen
import app.finn.deepsystem.background.TaskManager
import app.finn.deepsystem.background.TasksScreen
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.miniapps.MiniAppHostScreen
import app.finn.deepsystem.miniapps.MiniAppsViewModel
import app.finn.deepsystem.notes.NotesScreen
import app.finn.deepsystem.notes.NotesViewModel
import app.finn.deepsystem.sandbox.LinuxSandboxManager
import app.finn.deepsystem.sandbox.SandboxPermissionGate
import app.finn.deepsystem.sandbox.SandboxPermissions
import app.finn.deepsystem.sandbox.SandboxStep
import app.finn.deepsystem.sandbox.SandboxStepStatus
import app.finn.deepsystem.sandbox.SandboxTerminalSheet
import app.finn.deepsystem.ui.chat.ChatScreen
import app.finn.deepsystem.ui.chat.ChatViewModel
import app.finn.deepsystem.ui.live.LiveScreen
import app.finn.deepsystem.ui.live.LiveSessionFactory
import java.io.File
import java.util.UUID

enum class FinnTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home), Chat("Chat", Icons.Default.Chat), Notes("Notes", Icons.Default.Description)
}

/** Top-level navigation Home | Chat | Notes: bottom bar on compact widths, rail otherwise. */
@Composable
fun AppShell(
    chatViewModel: ChatViewModel,
    widthSizeClass: WindowWidthSizeClass,
    onReplayOnboarding: () -> Unit,
    openNoteId: String? = null,
    initialTab: FinnTab = FinnTab.Home
) {
    val notesVm: NotesViewModel = viewModel()
    val miniVm: MiniAppsViewModel = viewModel()
    var tab by rememberSaveable { mutableStateOf(if (openNoteId != null) FinnTab.Notes else initialTab) }
    var showMiniApps by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var showPrivacyLedger by remember { mutableStateOf(false) }
    var showSandbox by remember { mutableStateOf(false) }
    var sandboxSteps by remember { mutableStateOf(listOf<SandboxStep>()) }
    val context = LocalContext.current
    val notes by notesVm.uiState.collectAsState()
    val mini by miniVm.state.collectAsState()
    val taskRecords by TaskManager.getInstance(context).tasks.collectAsState()
    val compact = widthSizeClass == WindowWidthSizeClass.Compact
    val liveController = remember { LiveSessionFactory.create(engineProvider = { EngineProvider.getEngine(context) }) }
    val sandboxManager = remember { LinuxSandboxManager(context, File(context.filesDir, "sandbox")) }
    val sandboxGate = remember { SandboxPermissionGate(NetworkLedger.getInstance(context)) }

    fun runSandboxDemo() {
        val perms = SandboxPermissions()
        if (!sandboxGate.checkProcessSpawn(perms)) return
        val result = sandboxManager.runCommand("echo 'Hello from Finn sandbox'", 5000L, false)
        val step = SandboxStep(
            id = UUID.randomUUID().toString(),
            tool = "bash",
            command = "echo 'Hello from Finn sandbox'",
            stdout = result.stdout.trim(),
            stderr = result.stderr.trim(),
            status = if (result.exitCode == 0) SandboxStepStatus.Success else SandboxStepStatus.Failed
        )
        sandboxSteps = sandboxSteps + step
    }

    LaunchedEffect(tab) { if (tab == FinnTab.Home) { notesVm.loadNotes(); miniVm.refresh() } }

    Row(Modifier.fillMaxSize()) {
        if (!compact) NavigationRail(Modifier.testTag("nav_rail")) {
            Spacer(Modifier.weight(1f))
            FinnTab.entries.forEach { t ->
                NavigationRailItem(tab == t, { tab = t; showMiniApps = false }, { Icon(t.icon, null) }, label = { Text(t.label) },
                    modifier = Modifier.testTag("nav_${t.name.lowercase()}"))
            }
            Spacer(Modifier.weight(1f))
        }
        Scaffold(Modifier.weight(1f), bottomBar = {
            if (compact) NavigationBar(Modifier.testTag("nav_bar")) {
                FinnTab.entries.forEach { t ->
                    NavigationBarItem(tab == t, { tab = t; showMiniApps = false }, { Icon(t.icon, null) }, label = { Text(t.label) },
                        modifier = Modifier.testTag("nav_${t.name.lowercase()}"))
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    showLive -> LiveScreen(controller = liveController, onClose = { liveController.stop(); showLive = false })
                    showPrivacyLedger -> PrivacyLedgerScreen(onClose = { showPrivacyLedger = false })
                    showTasks -> TasksScreen(onClose = { showTasks = false })
                    tab == FinnTab.Home && showMiniApps -> Column {
                        TextButton({ showMiniApps = false; miniVm.close() }) { Text("‹ Home") }
                        MiniAppHostScreen(miniVm)
                    }
                    tab == FinnTab.Home && mini.running != null -> MiniAppHostScreen(miniVm)
                    tab == FinnTab.Home -> HomeScreen(
                        recentNotes = notes.notes.take(4),
                        tasks = taskRecords.take(3).map { HomeTask(it.id, it.title, it.status.displayName, it.progress) },
                        miniApps = mini.apps,
                        onQuickAsk = { q -> chatViewModel.onInputPromptChanged(q); chatViewModel.sendMessage(); tab = FinnTab.Chat },
                        onOpenNote = { notesVm.openNoteForEdit(it); tab = FinnTab.Notes },
                        onOpenNotes = { tab = FinnTab.Notes },
                        onOpenMiniApp = { miniVm.open(it) },
                        onOpenMiniApps = { showMiniApps = true },
                        onOpenTasks = { showTasks = true },
                        onOpenLive = { showLive = true },
                        onOpenPrivacyLedger = { showPrivacyLedger = true },
                        onOpenSandbox = { runSandboxDemo(); showSandbox = true }
                    )
                    tab == FinnTab.Chat -> ChatScreen(chatViewModel, widthSizeClass = widthSizeClass, onReplayOnboarding = onReplayOnboarding)
                    else -> NotesScreen(notesVm, openNoteId = openNoteId)
                }
                if (showSandbox) {
                    SandboxTerminalSheet(
                        steps = sandboxSteps,
                        onKill = { id -> sandboxSteps = sandboxSteps.map { if (it.id == id) it.copy(status = SandboxStepStatus.Killed) else it } },
                        onPermissionResponse = { id, allowed ->
                            val idx = sandboxSteps.indexOfFirst { it.id == id }
                            if (idx >= 0) {
                                val step = sandboxSteps[idx]
                                val url = step.pendingPermission
                                val perms = SandboxPermissions(networkAllowed = allowed)
                                val granted = if (url != null) sandboxGate.requestNetwork(url, "sandbox", perms) else allowed
                                sandboxSteps = sandboxSteps.toMutableList().apply {
                                    this[idx] = step.copy(pendingPermission = null, status = if (granted) SandboxStepStatus.Success else SandboxStepStatus.Failed)
                                }
                            }
                        },
                        onDismiss = { showSandbox = false }
                    )
                }
            }
        }
    }
}
