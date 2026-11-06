package app.finn.deepsystem.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SandboxStepStatus { Running, Success, Failed, AwaitingPermission, Killed }

data class SandboxStep(
    val id: String,
    val tool: String,
    val command: String,
    val stdout: String = "",
    val stderr: String = "",
    val status: SandboxStepStatus = SandboxStepStatus.Running,
    val pendingPermission: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxTerminalSheet(
    steps: List<SandboxStep>,
    onKill: (String) -> Unit,
    onPermissionResponse: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Container Sandbox", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(steps) { step ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(step.tool, fontWeight = FontWeight.SemiBold, color = Color(0xFF80CBC4))
                                Text(step.status.name, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "$ ${step.command}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (step.stdout.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = step.stdout,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFA5D6A7)
                                )
                            }
                            if (step.stderr.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = step.stderr,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFEF9A9A)
                                )
                            }
                            step.pendingPermission?.let { perm ->
                                Spacer(Modifier.height(8.dp))
                                Text("Permission required: $perm", color = Color(0xFFFFB74D))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onPermissionResponse(step.id, true) }) { Text("Allow") }
                                    OutlinedButton(onClick = { onPermissionResponse(step.id, false) }) { Text("Deny") }
                                }
                            }
                            if (step.status == SandboxStepStatus.Running) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { onKill(step.id) }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Kill")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}