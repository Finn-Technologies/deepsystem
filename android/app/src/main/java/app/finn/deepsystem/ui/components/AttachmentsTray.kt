package app.finn.deepsystem.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.ui.theme.*
import coil.compose.AsyncImage

enum class AttachmentType {
    IMAGE, AUDIO, VIDEO, FILE
}

data class AttachmentItem(
    val uri: String,
    val type: AttachmentType,
    val name: String,
    val bytes: ByteArray? = null
)

@Composable
fun AttachmentsTray(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onAttachmentSelected: (AttachmentItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Launchers
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            onAttachmentSelected(AttachmentItem(uri.toString(), AttachmentType.IMAGE, "Image", bytes))
            onDismiss()
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            onAttachmentSelected(AttachmentItem(uri.toString(), AttachmentType.AUDIO, "Audio File", bytes))
            onDismiss()
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            onAttachmentSelected(AttachmentItem(uri.toString(), AttachmentType.VIDEO, "Video File", null))
            onDismiss()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            onAttachmentSelected(AttachmentItem(uri.toString(), AttachmentType.FILE, "Document", null))
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrayItemButton(
                    icon = Icons.Default.AddPhotoAlternate,
                    label = "Photo",
                    tint = FinnIndigo,
                    onClick = { imageLauncher.launch("image/*") }
                )
                TrayItemButton(
                    icon = Icons.Default.Audiotrack,
                    label = "Audio",
                    tint = FinnViolet,
                    onClick = { audioLauncher.launch("audio/*") }
                )
                TrayItemButton(
                    icon = Icons.Default.Videocam,
                    label = "Video",
                    tint = FinnCyan,
                    onClick = { videoLauncher.launch("video/*") }
                )
                TrayItemButton(
                    icon = Icons.Default.AttachFile,
                    label = "Document",
                    tint = FinnTeal,
                    onClick = { fileLauncher.launch("*/*") }
                )
            }
        }
    }
}

@Composable
fun TrayItemButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Attach $label"
            }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
fun AttachmentChipPreview(
    attachment: AttachmentItem,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .padding(bottom = 8.dp)
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (attachment.type) {
                AttachmentType.IMAGE -> {
                    AsyncImage(
                        model = attachment.uri,
                        contentDescription = "Image preview",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                AttachmentType.AUDIO -> {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FinnViolet.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Audiotrack, contentDescription = null, tint = FinnViolet, modifier = Modifier.size(20.dp))
                    }
                }
                AttachmentType.VIDEO -> {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FinnCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = FinnCyan, modifier = Modifier.size(20.dp))
                    }
                }
                AttachmentType.FILE -> {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FinnTeal.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, tint = FinnTeal, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(28.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Remove attachment"
                    }
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
