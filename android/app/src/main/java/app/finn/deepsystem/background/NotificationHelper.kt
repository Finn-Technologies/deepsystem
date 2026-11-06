package app.finn.deepsystem.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.finn.deepsystem.MainActivity

/**
 * Creates notification channels and standardized progress notifications
 * with cancel actions for foreground services and WorkManager background jobs.
 */
object NotificationHelper {

    const val CHANNEL_ASSISTANT_LIVE = "finn_assistant_live"
    const val CHANNEL_AGENT_TASKS = "finn_agent_tasks"

    const val NOTIFICATION_ID_LIVE = 3001
    const val NOTIFICATION_ID_TASK_BASE = 4000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val liveChannel = NotificationChannel(
                CHANNEL_ASSISTANT_LIVE,
                "Live Assistant Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing live voice and multimodal assistant session"
                setShowBadge(false)
            }

            val tasksChannel = NotificationChannel(
                CHANNEL_AGENT_TASKS,
                "Background Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and cancellation controls for background AI tasks"
                setShowBadge(false)
            }

            manager.createNotificationChannel(liveChannel)
            manager.createNotificationChannel(tasksChannel)
        }
    }

    fun createAssistantLiveNotification(
        context: Context,
        statusText: String = "Listening and thinking locally..."
    ): Notification {
        createChannels(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, FinnForegroundService::class.java).apply {
            action = FinnForegroundService.ACTION_STOP_LIVE_AUDIO
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ASSISTANT_LIVE)
            .setContentTitle("Finn Assistant Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End Session",
                stopPendingIntent
            )
            .build()
    }

    fun createTaskNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        progress: Int,
        statusText: String,
        isIndeterminate: Boolean = false
    ): Notification {
        createChannels(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, FinnForegroundService::class.java).apply {
            action = FinnForegroundService.ACTION_CANCEL_TASK
            putExtra(FinnForegroundService.EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            taskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_AGENT_TASKS)
            .setContentTitle(taskTitle)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )

        if (isIndeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        return builder.build()
    }

    fun createTaskCompletedNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        summary: String
    ): Notification {
        createChannels(context)

        return NotificationCompat.Builder(context, CHANNEL_AGENT_TASKS)
            .setContentTitle("$taskTitle Complete")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun createTaskFailedNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        error: String
    ): Notification {
        createChannels(context)

        return NotificationCompat.Builder(context, CHANNEL_AGENT_TASKS)
            .setContentTitle("$taskTitle Failed")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}
