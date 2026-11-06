package app.finn.deepsystem.miniapps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Alias matching the host-screen name used by navigation wiring. */
@Composable
fun MiniAppHostScreen(viewModel: MiniAppsViewModel, modifier: Modifier = Modifier) = MiniAppsGallery(viewModel, modifier)

/** Gallery + generate prompt. Opening an app shows [MiniAppRunner] full screen. */
@Composable
fun MiniAppsGallery(viewModel: MiniAppsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    state.running?.let { app ->
        MiniAppRunner(app, viewModel, onClose = viewModel::close, modifier = modifier)
        return
    }
    var request by remember { mutableStateOf("") }
    Column(modifier.padding(16.dp).testTag("mini_gallery")) {
        Text("Mini apps", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = request, onValueChange = { request = it },
            modifier = Modifier.fillMaxWidth().testTag("mini_request"),
            placeholder = { Text("Describe an app: \"a tip calculator\"…") },
            trailingIcon = {
                if (state.isGenerating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { viewModel.generate(request); request = "" }, enabled = request.isNotBlank()) {
                    Icon(Icons.Default.AutoAwesome, "Generate mini app")
                }
            }
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        if (state.apps.isEmpty()) {
            Text("No mini apps yet. Ask Finn to build one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.apps, key = { it.id }) { app -> MiniAppCard(app, viewModel) }
        }
    }
}

@Composable
fun MiniAppCard(app: MiniApp, vm: MiniAppsViewModel, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().clickable { vm.open(app) }.testTag("mini_card_${app.id}"),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (app.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp))
                Box {
                    IconButton({ menu = true }, Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "Mini app options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (app.isPinned) "Unpin" else "Pin") }, { vm.togglePin(app); menu = false })
                        DropdownMenuItem({ Text("Delete") }, { vm.delete(app); menu = false })
                    }
                }
            }
            Text(app.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppRunner(app: MiniApp, vm: MiniAppsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var grants by remember(app.id) { mutableStateOf(vm.grants(app.id)) }
    var showEdit by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    // Ungranted permissions requested at first run: ask once.
    var askedPerms by remember(app.id) { mutableStateOf(vm.repository.get(app.id, MiniAppBridgeCore.PERM_KEY) != null) }
    val core = remember(app.id) {
        MiniAppBridgeCore(app.id, vm.repository, { vm.grants(app.id) },
            { copyToClipboard(context, it) }, { vm.askFinnSync(it) })
    }
    if (!askedPerms) {
        AlertDialog(
            onDismissRequest = { vm.setGrants(app.id, emptySet()); askedPerms = true },
            title = { Text("Permissions for ${app.title}") },
            text = { Text("This app runs offline in a sandbox. It can store its own data. Also allow it to copy to the clipboard and ask Finn questions (rate limited)?") },
            confirmButton = { TextButton({
                val all = MiniAppPermission.entries.toSet(); vm.setGrants(app.id, all); grants = all; askedPerms = true
            }) { Text("Allow all") } },
            dismissButton = { TextButton({
                val s = setOf(MiniAppPermission.STORAGE); vm.setGrants(app.id, s); grants = s; askedPerms = true
            }) { Text("Storage only") } }
        )
    }
    Column(modifier.testTag("mini_runner")) {
        TopAppBar(
            title = { Text(app.title, maxLines = 1) },
            navigationIcon = { IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Close mini app") } },
            actions = {
                if (state.isGenerating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton({ showEdit = true }) { Icon(Icons.Default.Edit, "Edit or regenerate") }
                IconButton({ vm.togglePin(app) }) { Icon(Icons.Default.PushPin, "Pin") }
                IconButton({ vm.delete(app) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        )
        if (askedPerms) MiniAppWebView(app, core, Modifier.fillMaxSize())
    }
    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Edit with Finn") },
            text = { OutlinedTextField(feedback, { feedback = it }, placeholder = { Text("What should change? (blank = regenerate)") }) },
            confirmButton = { TextButton({ vm.regenerate(app, feedback); feedback = ""; showEdit = false }) { Text("Regenerate") } },
            dismissButton = { TextButton({ showEdit = false }) { Text("Cancel") } }
        )
    }
}
