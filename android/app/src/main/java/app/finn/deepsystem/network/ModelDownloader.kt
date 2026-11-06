package app.finn.deepsystem.network

import android.content.Context
import android.util.Log
import app.finn.deepsystem.model.DownloadState
import app.finn.deepsystem.model.KnownModels
import app.finn.deepsystem.model.ModelTarget
import app.finn.deepsystem.background.NetworkLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloader(private val context: Context) {
    private val _downloadState = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadState: StateFlow<Map<String, DownloadState>> = _downloadState.asStateFlow()

    private val isCancelled = AtomicBoolean(false)

    val modelsDir: File
        get() {
            val external = context.getExternalFilesDir("models")
            val dir = external ?: File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getSearchPaths(): List<File> {
        val paths = mutableListOf<File>()
        context.getExternalFilesDir("models")?.let { paths.add(it) }
        paths.add(File(context.filesDir, "models"))
        paths.add(context.filesDir)
        paths.add(File("/data/local/tmp"))
        return paths.distinct()
    }

    /**
     * Checks all search locations for a model file (downloaded or adb pushed).
     */
    fun findModelFile(filename: String): File? {
        for (dir in getSearchPaths()) {
            val f = File(dir, filename)
            if (f.exists() && f.length() > 0) {
                return f
            }
        }
        return null
    }

    fun isModelReady(target: ModelTarget): Boolean {
        val f = findModelFile(target.filename) ?: return false
        // Allow close match or full match in size
        return f.length() == target.expectedBytes || f.length() > 100_000_000L
    }

    fun areRequiredModelsReady(): Boolean {
        return isModelReady(KnownModels.TEXT_MODEL)
    }

    fun cancelAll() {
        isCancelled.set(true)
    }

    suspend fun downloadTarget(
        target: ModelTarget,
        onProgress: ((DownloadState) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val targetFile = File(modelsDir, target.filename)
        val partFile = File(modelsDir, "${target.filename}.part")

        if (targetFile.exists() && targetFile.length() == target.expectedBytes) {
            updateState(target.filename, DownloadState.Completed)
            onProgress?.invoke(DownloadState.Completed)
            return@withContext true
        }

        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (existingBytes >= target.expectedBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val urlString = "${KnownModels.BASE_URL}/${target.relativeUrl}"
        Log.i(TAG, "Starting download for ${target.filename} from $urlString (resume from $existingBytes bytes)")
        val ledger = NetworkLedger.getInstance(context)
        val ledgerId = ledger.logStart(urlString, "Model weights: ${target.filename}")

        try {
            var currentUrl = urlString
            var connection: HttpURLConnection
            var redirects = 0
            val maxRedirects = 8

            // Loop to handle HTTP redirects (301, 302, 307, 308)
            while (true) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false

                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location != null && redirects < maxRedirects) {
                        currentUrl = location
                        redirects++
                        connection.disconnect()
                        continue
                    }
                }
                break
            }

            val responseCode = connection.responseCode
            val appendMode = (responseCode == HttpURLConnection.HTTP_PARTIAL)

            if (!appendMode && existingBytes > 0) {
                Log.w(TAG, "Server returned $responseCode instead of 206 Partial Content. Restarting download.")
                partFile.delete()
                existingBytes = 0L
            }

            val totalBytes = if (appendMode) {
                existingBytes + connection.contentLengthLong
            } else {
                connection.contentLengthLong.takeIf { it > 0 } ?: target.expectedBytes
            }

            val raf = RandomAccessFile(partFile, "rw")
            if (appendMode) {
                raf.seek(existingBytes)
            } else {
                raf.setLength(0)
            }

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalDownloaded = existingBytes
            var lastUpdateTime = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            connection.inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
                        raf.close()
                        connection.disconnect()
                        val state = DownloadState.Idle
                        updateState(target.filename, state)
                        onProgress?.invoke(state)
                        return@withContext false
                    }

                    raf.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead
                    bytesSinceLastUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 250) {
                        val durationSec = (now - lastUpdateTime) / 1000.0
                        val speed = (bytesSinceLastUpdate / durationSec).toLong()
                        val percent = if (totalBytes > 0) (totalDownloaded.toFloat() / totalBytes) else 0f
                        val progressState = DownloadState.Progress(
                            bytesDownloaded = totalDownloaded,
                            totalBytes = totalBytes,
                            percent = percent,
                            speedBytesPerSec = speed
                        )
                        ledger.logProgress(ledgerId, totalDownloaded)
                        updateState(target.filename, progressState)
                        onProgress?.invoke(progressState)

                        lastUpdateTime = now
                        bytesSinceLastUpdate = 0L
                    }
                }
            }
            raf.close()
            connection.disconnect()

            // Verify file size and checksum
            updateState(target.filename, DownloadState.Verifying)
            onProgress?.invoke(DownloadState.Verifying)

            if (partFile.length() != target.expectedBytes) {
                val msg = "Size mismatch: expected ${target.expectedBytes}, got ${partFile.length()}"
                Log.e(TAG, msg)
                ledger.logFailure(ledgerId, msg)
                updateState(target.filename, DownloadState.Error(msg))
                onProgress?.invoke(DownloadState.Error(msg))
                return@withContext false
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            Log.i(TAG, "Download and verification complete for ${target.filename}")
            ledger.logSuccess(ledgerId, totalDownloaded)
            updateState(target.filename, DownloadState.Completed)
            onProgress?.invoke(DownloadState.Completed)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Download error for ${target.filename}: ${t.message}", t)
            ledger.logFailure(ledgerId, t.message ?: "Download failed")
            val err = DownloadState.Error(t.message ?: "Download failed")
            updateState(target.filename, err)
            onProgress?.invoke(err)
            false
        }
    }

    private fun updateState(filename: String, state: DownloadState) {
        val current = _downloadState.value.toMutableMap()
        current[filename] = state
        _downloadState.value = current
    }

    companion object {
        private const val TAG = "ModelDownloader"

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
