package app.finn.deepsystem.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-type Foreground Service compliant with Android 14/15 restrictions.
 *
 * Supports foreground types:
 * - FOREGROUND_SERVICE_TYPE_MICROPHONE: for ongoing live conversational assistant sessions.
 * - FOREGROUND_SERVICE_TYPE_DATA_SYNC: for offline local agent data synchronization and indexing.
 * - FOREGROUND_SERVICE_TYPE_SHORT_SERVICE: for rapid agent tasks (strictly respects 3-min timeout).
 */
class FinnForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val activeShortTasks = ConcurrentHashMap<String, Runnable>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START_LIVE_AUDIO -> handleStartLiveAudio()
            ACTION_STOP_LIVE_AUDIO -> handleStopLiveAudio()
            ACTION_START_TASK -> handleStartTask(intent)
            ACTION_CANCEL_TASK -> handleCancelTask(intent)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }

        return START_NOT_STICKY
    }

    private fun handleStartLiveAudio() {
        Log.i(TAG, "Starting foreground service with MICROPHONE type")
        val notification = NotificationHelper.createAssistantLiveNotification(
            this,
            "Finn Live audio session active"
        )

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0

        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID_LIVE,
            notification,
            serviceType
        )
    }

    private fun handleStopLiveAudio() {
        Log.i(TAG, "Stopping live audio foreground session")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleStartTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "Background Task"
        val isShortService = intent.getBooleanExtra(EXTRA_IS_SHORT_SERVICE, false)

        val taskManager = TaskManager.getInstance(this)
        val taskRecord = taskManager.getTask(taskId)
        val initialProgress = taskRecord?.progress ?: 0

        val notification = NotificationHelper.createTaskNotification(
            this,
            taskId,
            title,
            initialProgress,
            "Executing task locally..."
        )

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (isShortService) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID_TASK_BASE + taskId.hashCode().coerceAtLeast(0) % 1000,
            notification,
            serviceType
        )

        if (isShortService) {
            // Android 14+ enforces a 3-minute hard limit on shortService.
            // Setup a safety watchdog at 150 seconds to gracefully complete before system timeout.
            val timeoutRunnable = Runnable {
                Log.w(TAG, "ShortService watchdog reached for task $taskId, stopping gracefully")
                taskManager.updateStatus(taskId, TaskStatus.COMPLETED)
                stopForegroundTask(taskId)
            }
            activeShortTasks[taskId] = timeoutRunnable
            handler.postDelayed(timeoutRunnable, SHORT_SERVICE_TIMEOUT_MS)
        }
    }

    private fun handleCancelTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.i(TAG, "Cancelling task via foreground service: $taskId")
        val taskManager = TaskManager.getInstance(this)
        taskManager.cancelTask(taskId)
        stopForegroundTask(taskId)
    }

    private fun stopForegroundTask(taskId: String) {
        activeShortTasks.remove(taskId)?.let { handler.removeCallbacks(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        Log.w(TAG, "Android system onTimeout called for shortService startId $startId")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeShortTasks.values.forEach { handler.removeCallbacks(it) }
        activeShortTasks.clear()
        Log.i(TAG, "FinnForegroundService destroyed")
    }

    companion object {
        private const val TAG = "FinnForegroundService"
        const val SHORT_SERVICE_TIMEOUT_MS = 150_000L // 2.5 minutes (well under Android 3m ceiling)

        const val ACTION_START_LIVE_AUDIO = "app.finn.deepsystem.action.START_LIVE_AUDIO"
        const val ACTION_STOP_LIVE_AUDIO = "app.finn.deepsystem.action.STOP_LIVE_AUDIO"
        const val ACTION_START_TASK = "app.finn.deepsystem.action.START_TASK"
        const val ACTION_CANCEL_TASK = "app.finn.deepsystem.action.CANCEL_TASK"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_IS_SHORT_SERVICE = "extra_is_short_service"

        fun startLiveAudio(context: Context) {
            val intent = Intent(context, FinnForegroundService::class.java).apply {
                action = ACTION_START_LIVE_AUDIO
            }
            context.startService(intent)
        }

        fun stopLiveAudio(context: Context) {
            val intent = Intent(context, FinnForegroundService::class.java).apply {
                action = ACTION_STOP_LIVE_AUDIO
            }
            context.startService(intent)
        }

        fun startTask(context: Context, taskId: String, title: String, isShortService: Boolean = false) {
            val intent = Intent(context, FinnForegroundService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_TITLE, title)
                putExtra(EXTRA_IS_SHORT_SERVICE, isShortService)
            }
            context.startService(intent)
        }
    }
}
