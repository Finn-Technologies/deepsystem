package app.finn.deepsystem.background

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.finn.deepsystem.agent_tasks.AgentTaskScheduler
import app.finn.deepsystem.agent_tasks.DemoAgentTasks
import app.finn.deepsystem.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val taskManager = remember { TaskManager.getInstance(context) }
    val deviceMonitor = remember { DeviceMonitor.getInstance(context) }

    val tasks by taskManager.tasks.collectAsState()
    val thermalLevel by deviceMonitor.thermalStatus.collectAsState()
    val batteryState by deviceMonitor.batteryState.collectAsState()

    var allowOnBattery by remember { mutableStateOf(false) }

    val runningTasks = remember(tasks) { tasks.filter { !it.isFinished } }
    val finishedTasks = remember(tasks) { tasks.filter { it.isFinished } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Background Agent Tasks", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (finishedTasks.isNotEmpty()) {
                        IconButton(
                            onClick = { taskManager.clearFinished() },
                            modifier = Modifier.semantics {
                                role = Role.Button
                                contentDescription = "Clear completed tasks"
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Hardware & Resource Status Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Hardware & Resource Awareness",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Battery
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (batteryState.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                                    contentDescription = null,
                                    tint = if (batteryState.isLowBattery) ErrorRose else SuccessGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${batteryState.levelPercent}% • ${if (batteryState.isCharging) "Charging" else "On Battery"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // Thermal
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Thermostat,
                                    contentDescription = null,
                                    tint = when (thermalLevel) {
                                        ThermalLevel.NONE, ThermalLevel.LIGHT -> SuccessGreen
                                        ThermalLevel.MODERATE -> WarningAmber
                                        else -> ErrorRose
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${thermalLevel.description} (${deviceMonitor.getRecommendedThreadCount()} threads)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Allow Heavy Tasks on Low Battery",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Normally deferred when < 20% or in power-save mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = allowOnBattery,
                                onCheckedChange = { allowOnBattery = it }
                            )
                        }
                    }
                }
            }

            // Quick Queue Task Buttons
            item {
                Text(
                    text = "Launch Background Job",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { DemoAgentTasks.queueKnowledgeDigest(context, allowOnBattery) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Digest", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { DemoAgentTasks.queueModelVerification(context, allowOnBattery) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Verify", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { DemoAgentTasks.queueContextIndexing(context, allowOnBattery) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Index", fontSize = 12.sp)
                    }
                }
            }

            // Active / Running Tasks
            item {
                Text(
                    text = "Active Tasks (${runningTasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (runningTasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No active background tasks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(runningTasks, key = { it.id }) { task ->
                    ActiveTaskItem(
                        task = task,
                        onCancel = { AgentTaskScheduler.cancelTask(context, task.id) }
                    )
                }
            }

            // Finished Tasks History
            item {
                Text(
                    text = "Finished Tasks (${finishedTasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (finishedTasks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No finished tasks yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(finishedTasks, key = { it.id }) { task ->
                    FinishedTaskItem(task = task)
                }
            }
        }
    }
}

@Composable
fun ActiveTaskItem(
    task: BackgroundTaskRecord,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = task.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    if (task.description.isNotBlank()) {
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel task", tint = ErrorRose, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { task.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = FinnIndigo
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.status.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = FinnIndigo
                )
                Text(
                    text = "${task.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun FinishedTaskItem(task: BackgroundTaskRecord) {
    val dateStr = remember(task.updatedAt) {
        val sdf = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        sdf.format(Date(task.updatedAt))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = task.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "$dateStr • ${task.taskType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                if (task.errorMessage != null) {
                    Text(text = task.errorMessage, color = ErrorRose, style = MaterialTheme.typography.labelSmall)
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when (task.status) {
                    TaskStatus.COMPLETED -> SuccessGreen.copy(alpha = 0.15f)
                    TaskStatus.CANCELLED -> WarningAmber.copy(alpha = 0.15f)
                    else -> ErrorRose.copy(alpha = 0.15f)
                },
                modifier = Modifier.height(24.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = task.status.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (task.status) {
                            TaskStatus.COMPLETED -> SuccessGreen
                            TaskStatus.CANCELLED -> WarningAmber
                            else -> ErrorRose
                        }
                    )
                }
            }
        }
    }
}
