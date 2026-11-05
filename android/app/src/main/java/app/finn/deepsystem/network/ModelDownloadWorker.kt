package app.finn.deepsystem.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.finn.deepsystem.model.DownloadState
import app.finn.deepsystem.model.KnownModels
import app.finn.deepsystem.model.ModelTarget

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val downloader = ModelDownloader(appContext)

    override suspend fun doWork(): Result {
        val filename = inputData.getString(KEY_FILENAME) ?: KnownModels.TEXT_MODEL.filename
        val target = KnownModels.ALL_TARGETS.firstOrNull { it.filename == filename }
            ?: KnownModels.TEXT_MODEL
        val modelTitle = target.description.ifEmpty { target.filename }

        createNotificationChannel()
        val initialNotification = createNotification(modelTitle, 0, "Starting download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        val success = downloader.downloadTarget(target) { state ->
            when (state) {
                is DownloadState.Progress -> {
                    val progressInt = (state.percent * 100).toInt().coerceIn(0, 100)
                    val speedMb = state.speedBytesPerSec / (1024 * 1024.0)
                    val desc = String.format("%.1f MB/s • %d%%", speedMb, progressInt)
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(modelTitle, progressInt, desc)
                    )
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_PERCENT to progressInt,
                            KEY_PROGRESS_BYTES to state.bytesDownloaded,
                            KEY_TOTAL_BYTES to state.totalBytes,
                            KEY_SPEED to state.speedBytesPerSec
                        )
                    )
                }
                is DownloadState.Verifying -> {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(modelTitle, 100, "Verifying model integrity...")
                    )
                }
                is DownloadState.Completed -> {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                            .setContentTitle("Model Download Complete")
                            .setContentText("$modelTitle is ready for local offline inference.")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .build()
                    )
                }
                is DownloadState.Error -> {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                            .setContentTitle("Download Failed")
                            .setContentText(state.message)
                            .setSmallIcon(android.R.drawable.stat_notify_error)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .build()
                    )
                }
                else -> Unit
            }
        }

        return if (success) Result.success() else Result.retry()
    }

    private fun createNotification(title: String, progress: Int, status: String): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of offline AI model downloads"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "finn_model_downloads"
        const val NOTIFICATION_ID = 2001
        const val KEY_FILENAME = "target_filename"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_PROGRESS_BYTES = "progress_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED = "speed"

        fun enqueueDownload(context: Context, target: ModelTarget, requiresBatteryNotLow: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(requiresBatteryNotLow)
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_FILENAME to target.filename))
                .addTag("model_download_${target.filename}")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_${target.filename}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelDownload(context: Context, target: ModelTarget) {
            WorkManager.getInstance(context).cancelUniqueWork("download_${target.filename}")
        }
    }
}
