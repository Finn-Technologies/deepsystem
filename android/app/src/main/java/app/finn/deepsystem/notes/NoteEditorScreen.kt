package app.finn.deepsystem.notes

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.engine.EngineProvider
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.ui.theme.*
import app.finn.deepsystem.video.VideoProcessor
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    initialNote: NoteItem,
    onSave: (NoteItem) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    engine: FinnEngine = EngineProvider.getEngine()
) {
    var title by remember { mutableStateOf(initialNote.title) }
    var content by remember { mutableStateOf(initialNote.content) }
    var folder by remember { mutableStateOf(initialNote.folder) }
    var tags by remember { mutableStateOf(initialNote.tags) }
    var isPinned by remember { mutableStateOf(initialNote.isPinned) }
    var aiSummary by remember { mutableStateOf(initialNote.aiSummary) }
    var aiTranscript by remember { mutableStateOf(initialNote.aiTranscript) }

    var isProcessingAi by remember { mutableStateOf(false) }
    var aiStatusMessage by remember { mutableStateOf<String?>(null) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Auto-save helper
    fun buildCurrentNote(): NoteItem {
        return initialNote.copy(
            title = title,
            content = content,
            folder = folder,
            tags = tags,
            isPinned = isPinned,
            aiSummary = aiSummary,
            aiTranscript = aiTranscript,
            updatedAt = System.currentTimeMillis()
        )
    }

    // AI Summary generation
    fun generateAiSummary() {
        if (content.isBlank()) {
            Toast.makeText(context, "Note has no content to summarize", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessingAi = true
        aiStatusMessage = "Finn is summarizing note..."
        coroutineScope.launch(Dispatchers.IO) {
            val prompt = "<|im_start|>user\nProvide a concise bulleted summary of the following note:\n\n$content<|im_end|>\n<|im_start|>assistant\n"
            val sb = StringBuilder()
            val callback = object : TokenCallback {
                override fun onToken(token: String): Boolean {
                    sb.append(token)
                    return true
                }
            }
            try {
                engine.generateText(prompt, temperature = 0.3f, maxTokens = 256, callback = callback)
                val result = sb.toString().trim()
                withContext(Dispatchers.Main) {
                    aiSummary = result
                    isProcessingAi = false
                    aiStatusMessage = null
                    onSave(buildCurrentNote().copy(aiSummary = result))
                    Toast.makeText(context, "Summary generated!", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessingAi = false
                    aiStatusMessage = null
                    Toast.makeText(context, "Summary failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // AI Transcription for Audio/Video
    fun transcribeMedia() {
        val path = initialNote.mediaPath
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(context, "No media file attached to transcribe", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessingAi = true
        aiStatusMessage = "Finn is transcribing media..."

        coroutineScope.launch(Dispatchers.IO) {
            val file = File(path)
            val sb = StringBuilder()
            val callback = object : TokenCallback {
                override fun onToken(token: String): Boolean {
                    sb.append(token)
                    return true
                }
            }

            try {
                if (initialNote.mediaType == "audio") {
                    val pcmBytes = NoteAi.audioFileToPcm16k(file)
                    val prompt = NoteAi.ASR_PROMPT
                    engine.generateMultimodal(
                        prompt = prompt,
                        imageBytes = null,
                        audioBytes = pcmBytes,
                        temperature = 0.2f,
                        maxTokens = 512,
                        callback = callback
                    )
                } else if (initialNote.mediaType == "video") {
                    // Extract video frames
                    val result = VideoProcessor().processVideoFile(file)
                    val frames = result.frameJpegs
                    val prompt = "<|im_start|>user\nTranscribe speech and describe key actions occurring in this video.<|im_end|>\n<|im_start|>assistant\n"
                    engine.generateVideo(
                        prompt = prompt,
                        videoFrames = frames,
                        audioBytes = result.audioPcm16k,
                        temperature = 0.2f,
                        maxTokens = 512,
                        callback = callback
                    )
                }

                val transcript = sb.toString().trim()
                withContext(Dispatchers.Main) {
                    aiTranscript = transcript
                    isProcessingAi = false
                    aiStatusMessage = null
                    // If note has no content yet, populate content with transcript
                    if (content.isBlank()) {
                        content = transcript
                    }
                    onSave(buildCurrentNote().copy(aiTranscript = transcript, content = content))
                    Toast.makeText(context, "Transcription complete!", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessingAi = false
                    aiStatusMessage = null
                    Toast.makeText(context, "Transcription failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // AI OCR & Image Description
    fun describeOrOcrImage() {
        val path = initialNote.mediaPath
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(context, "No image file attached", Toast.LENGTH_SHORT).show()
            return
        }
        isProcessingAi = true
        aiStatusMessage = "Finn is inspecting image (OCR)..."

        coroutineScope.launch(Dispatchers.IO) {
            val file = File(path)
            val imgBytes = file.readBytes()
            val sb = StringBuilder()
            val callback = object : TokenCallback {
                override fun onToken(token: String): Boolean {
                    sb.append(token)
                    return true
                }
            }

            try {
                val prompt = "<|im_start|>user\nExtract all text visible (OCR) and describe the content of this image in detail.<|im_end|>\n<|im_start|>assistant\n"
                engine.generateMultimodal(
                    prompt = prompt,
                    imageBytes = imgBytes,
                    audioBytes = null,
                    temperature = 0.2f,
                    maxTokens = 512,
                    callback = callback
                )

                val result = sb.toString().trim()
                withContext(Dispatchers.Main) {
                    aiSummary = result
                    if (content.isBlank()) {
                        content = result
                    }
                    isProcessingAi = false
                    aiStatusMessage = null
                    onSave(buildCurrentNote().copy(aiSummary = result, content = content))
                    Toast.makeText(context, "Image analyzed!", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessingAi = false
                    aiStatusMessage = null
                    Toast.makeText(context, "OCR failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Gemini 'just start writing' gradient atmospheric header
    val geminiWritingGradient = Brush.verticalGradient(
        listOf(
            FinnIndigo.copy(alpha = 0.28f),
            FinnTeal.copy(alpha = 0.12f),
            Color.Transparent
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = {
                        onSave(buildCurrentNote())
                        onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back and save")
                    }
                },
                actions = {
                    // Pin toggle
                    IconButton(onClick = {
                        isPinned = !isPinned
                        onSave(buildCurrentNote())
                    }) {
                        Icon(
                            imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "Unpin note" else "Pin note",
                            tint = if (isPinned) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Share note
                    IconButton(onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TITLE, title)
                            putExtra(Intent.EXTRA_TEXT, "$title\n\n$content")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share note via"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share note")
                    }

                    // Delete note
                    IconButton(onClick = {
                        onDelete(initialNote.id)
                        onClose()
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete note", tint = ErrorRose)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            // Quick AI Action Toolbar
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // AI Summary button
                    OutlinedButton(
                        onClick = { generateAiSummary() },
                        enabled = !isProcessingAi,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = FinnIndigo)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AI Summary", style = MaterialTheme.typography.labelMedium)
                    }

                    // Media AI button (Transcribe or OCR)
                    if (initialNote.mediaType == "audio" || initialNote.mediaType == "video") {
                        Button(
                            onClick = { transcribeMedia() },
                            enabled = !isProcessingAi,
                            colors = ButtonDefaults.buttonColors(containerColor = FinnIndigo),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Transcribe, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Transcribe", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (initialNote.mediaType == "image") {
                        Button(
                            onClick = { describeOrOcrImage() },
                            enabled = !isProcessingAi,
                            colors = ButtonDefaults.buttonColors(containerColor = FinnTeal),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("OCR / Describe", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Add Tag Button
                    IconButton(onClick = { showAddTagDialog = true }) {
                        Icon(Icons.Default.Label, contentDescription = "Add tag", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(geminiWritingGradient)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Media preview banner if attached
                initialNote.mediaPath?.let { mediaPath ->
                    val file = File(mediaPath)
                    if (file.exists()) {
                        when (initialNote.mediaType) {
                            "image" -> {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Attached note image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            "audio" -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Audiotrack, contentDescription = null, tint = FinnIndigo)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text("Audio Voice Note", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            "video" -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Videocam, contentDescription = null, tint = FinnTeal)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text("Video Recording", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }

                // AI Processing Status Bar
                AnimatedVisibility(visible = isProcessingAi) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = FinnIndigo.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = FinnIndigo)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(aiStatusMessage ?: "Processing with Finn on-device engine...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // AI Summary Card if present
                aiSummary?.let { summary ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, FinnIndigo.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = FinnIndigo, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI Summary", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = FinnIndigo)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // AI Transcript Card if present
                aiTranscript?.let { transcript ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, FinnTeal.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Transcribe, contentDescription = null, tint = FinnTeal, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("AI Verbatim Transcript", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = FinnTeal)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(transcript, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Folder & Tag Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Folder badge
                    SuggestionChip(
                        onClick = {
                            val newF = if (folder == "General") "Work" else if (folder == "Work") "Personal" else "General"
                            folder = newF
                            onSave(buildCurrentNote())
                        },
                        label = { Text("📁 $folder", style = MaterialTheme.typography.labelSmall) }
                    )

                    // Tag chips
                    tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = {
                                tags = tags - tag
                                onSave(buildCurrentNote())
                            },
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Remove tag", modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                }

                // Title Input
                TextField(
                    value = title,
                    onValueChange = {
                        title = it
                        onSave(buildCurrentNote())
                    },
                    placeholder = {
                        Text(
                            "Title",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Note title" }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Serif Writing Surface (Gemini 'just start writing' style)
                TextField(
                    value = content,
                    onValueChange = {
                        content = it
                        onSave(buildCurrentNote())
                    },
                    placeholder = {
                        Text(
                            "Just start writing...",
                            style = TextStyle(
                                fontFamily = FontFamily.Serif,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .semantics { contentDescription = "Note serif writing surface" }
                )
            }
        }
    }

    // Add Tag Dialog
    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("Add Tag") },
            text = {
                TextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    placeholder = { Text("e.g. project, ideas, meeting") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = newTagInput.trim().replace("#", "")
                    if (clean.isNotBlank() && !tags.contains(clean)) {
                        tags = tags + clean
                        onSave(buildCurrentNote())
                    }
                    newTagInput = ""
                    showAddTagDialog = false
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
