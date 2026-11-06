package app.finn.deepsystem.background

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Record of an external network transmission.
 * Stored in the visible Privacy ledger to prove zero hidden telemetry.
 */
data class NetworkLedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val url: String,
    val host: String = try { URI(url).host ?: "unknown" } catch (_: Exception) { "unknown" },
    val purpose: String,
    val bytesTransferred: Long = 0L,
    val status: String = "IN_PROGRESS", // "IN_PROGRESS", "SUCCESS", "FAILED"
    val errorMessage: String? = null
)

/**
 * Manages the Privacy Network Ledger.
 * Every network connection initiated by the app MUST be registered here.
 */
class NetworkLedger private constructor(private val appContext: Context?) {

    private val entriesList = CopyOnWriteArrayList<NetworkLedgerEntry>()
    private val _entries = MutableStateFlow<List<NetworkLedgerEntry>>(emptyList())
    val entries: StateFlow<List<NetworkLedgerEntry>> = _entries.asStateFlow()

    init {
        loadPersistedEntries()
    }

    private fun loadPersistedEntries() {
        if (appContext == null) return
        try {
            val db = TaskDatabaseHelper.getInstance(appContext)
            val loaded = db.getNetworkLedgerEntries()
            entriesList.addAll(loaded)
            _entries.value = entriesList.toList()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load persisted ledger entries: ${t.message}")
        }
    }

    fun logStart(url: String, purpose: String): String {
        val entry = NetworkLedgerEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            url = url,
            purpose = purpose,
            bytesTransferred = 0L,
            status = "IN_PROGRESS"
        )
        entriesList.add(0, entry)
        _entries.value = entriesList.toList()
        persistEntry(entry)
        Log.i(TAG, "Network activity started: ${entry.host} [${entry.purpose}]")
        return entry.id
    }

    fun logProgress(id: String, bytes: Long) {
        val index = entriesList.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = entriesList[index]
            val updated = old.copy(bytesTransferred = bytes)
            entriesList[index] = updated
            _entries.value = entriesList.toList()
            persistEntry(updated)
        }
    }

    fun logSuccess(id: String, totalBytes: Long) {
        val index = entriesList.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = entriesList[index]
            val updated = old.copy(
                bytesTransferred = totalBytes,
                status = "SUCCESS",
                errorMessage = null
            )
            entriesList[index] = updated
            _entries.value = entriesList.toList()
            persistEntry(updated)
            Log.i(TAG, "Network activity completed successfully: ${updated.host} ($totalBytes bytes)")
        }
    }

    fun logFailure(id: String, error: String) {
        val index = entriesList.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = entriesList[index]
            val updated = old.copy(
                status = "FAILED",
                errorMessage = error
            )
            entriesList[index] = updated
            _entries.value = entriesList.toList()
            persistEntry(updated)
            Log.w(TAG, "Network activity failed: ${updated.host} - $error")
        }
    }

    fun clear() {
        entriesList.clear()
        _entries.value = emptyList()
        appContext?.let { ctx ->
            try {
                TaskDatabaseHelper.getInstance(ctx).clearNetworkLedger()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to clear persisted ledger: ${t.message}")
            }
        }
    }

    private fun persistEntry(entry: NetworkLedgerEntry) {
        appContext?.let { ctx ->
            try {
                TaskDatabaseHelper.getInstance(ctx).saveNetworkLedgerEntry(entry)
            } catch (t: Throwable) {
                // SQLite may not be available in some pure JVM test environments
            }
        }
    }

    companion object {
        private const val TAG = "NetworkLedger"

        @Volatile
        private var instance: NetworkLedger? = null

        fun getInstance(context: Context?): NetworkLedger {
            return instance ?: synchronized(this) {
                instance ?: NetworkLedger(context?.applicationContext).also { instance = it }
            }
        }

        fun get(): NetworkLedger {
            return getInstance(null)
        }
    }
}
