package app.finn.deepsystem.agent_tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.finn.deepsystem.background.BackgroundTaskRecord
import app.finn.deepsystem.background.TaskManager
import app.finn.deepsystem.background.TaskStatus
import app.finn.deepsystem.background.TaskTypes
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * High-level scheduler that bridges TaskManager SQLite storage and Android WorkManager.
 */
object AgentTaskScheduler {

    fun scheduleImmediateTask(
        context: Context,
        title: String,
        description: String = "",
        taskType: String = TaskTypes.KNOWLEDGE_DIGEST,
        payload: String? = null,
        isExpedited: Boolean = true,
        allowOnBattery: Boolean = false
    ): String {
        val taskId = UUID.randomUUID().toString()
        val record = BackgroundTaskRecord(
            id = taskId,
            title = title,
            description = description,
            taskType = taskType,
            status = TaskStatus.QUEUED,
            progress = 0,
            payload = payload,
            isExpedited = isExpedited
        )
        TaskManager.getInstance(context).enqueue(record)

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .apply {
                if (!allowOnBattery) {
                    setRequiresBatteryNotLow(true)
                }
            }
            .build()

        val requestBuilder = OneTimeWorkRequestBuilder<AgentWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    AgentWorker.KEY_TASK_ID to taskId,
                    AgentWorker.KEY_TASK_TITLE to title,
                    AgentWorker.KEY_TASK_TYPE to taskType,
                    AgentWorker.KEY_ALLOW_ON_BATTERY to allowOnBattery
                )
            )
            .addTag(taskId)
            .addTag(taskType)

        if (isExpedited) {
            requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        val request = requestBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_$taskId",
            ExistingWorkPolicy.REPLACE,
            request
        )

        return taskId
    }

    fun schedulePeriodicTask(
        context: Context,
        title: String,
        description: String = "",
        intervalMinutes: Long = 60,
        taskType: String = TaskTypes.CONTEXT_INDEXING,
        allowOnBattery: Boolean = false
    ): String {
        val taskId = "periodic_${taskType}_$intervalMinutes"
        val record = BackgroundTaskRecord(
            id = taskId,
            title = title,
            description = description,
            taskType = taskType,
            status = TaskStatus.QUEUED,
            progress = 0,
            isExpedited = false
        )
        TaskManager.getInstance(context).enqueue(record)

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .apply {
                if (!allowOnBattery) {
                    setRequiresBatteryNotLow(true)
                }
            }
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<AgentWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    AgentWorker.KEY_TASK_ID to taskId,
                    AgentWorker.KEY_TASK_TITLE to title,
                    AgentWorker.KEY_TASK_TYPE to taskType,
                    AgentWorker.KEY_ALLOW_ON_BATTERY to allowOnBattery
                )
            )
            .addTag(taskId)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            taskId,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        return taskId
    }

    fun cancelTask(context: Context, taskId: String) {
        TaskManager.getInstance(context).cancelTask(taskId)
        WorkManager.getInstance(context).cancelUniqueWork("task_$taskId")
        WorkManager.getInstance(context).cancelAllWorkByTag(taskId)
    }
}
