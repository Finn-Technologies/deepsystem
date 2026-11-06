package app.finn.deepsystem.background

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Room-free standard SQLite database helper for background tasks and network ledger.
 * Zero external ORM overhead, zero annotation-processor latency, rock solid.
 */
class TaskDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TASKS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_DESCRIPTION TEXT,
                $COL_TASK_TYPE TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_PROGRESS INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_ERROR_MESSAGE TEXT,
                $COL_PAYLOAD TEXT,
                $COL_IS_EXPEDITED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LEDGER (
                $COL_LEDGER_ID TEXT PRIMARY KEY,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_URL TEXT NOT NULL,
                $COL_HOST TEXT NOT NULL,
                $COL_PURPOSE TEXT NOT NULL,
                $COL_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_LEDGER_STATUS TEXT NOT NULL,
                $COL_LEDGER_ERROR TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migration logic if schema changes in future versions
    }

    // --- Task Operations ---

    fun insertOrUpdateTask(task: BackgroundTaskRecord) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_ID, task.id)
            put(COL_TITLE, task.title)
            put(COL_DESCRIPTION, task.description)
            put(COL_TASK_TYPE, task.taskType)
            put(COL_STATUS, task.status.name)
            put(COL_PROGRESS, task.progress)
            put(COL_CREATED_AT, task.createdAt)
            put(COL_UPDATED_AT, task.updatedAt)
            put(COL_ERROR_MESSAGE, task.errorMessage)
            put(COL_PAYLOAD, task.payload)
            put(COL_IS_EXPEDITED, if (task.isExpedited) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_TASKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllTasks(): List<BackgroundTaskRecord> {
        val list = mutableListOf<BackgroundTaskRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TASKS,
            null,
            null,
            null,
            null,
            null,
            "$COL_CREATED_AT DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val statusStr = c.getString(c.getColumnIndexOrThrow(COL_STATUS))
                val status = try { TaskStatus.valueOf(statusStr) } catch (_: Exception) { TaskStatus.QUEUED }
                list.add(
                    BackgroundTaskRecord(
                        id = c.getString(c.getColumnIndexOrThrow(COL_ID)),
                        title = c.getString(c.getColumnIndexOrThrow(COL_TITLE)),
                        description = c.getString(c.getColumnIndexOrThrow(COL_DESCRIPTION)) ?: "",
                        taskType = c.getString(c.getColumnIndexOrThrow(COL_TASK_TYPE)),
                        status = status,
                        progress = c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS)),
                        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_UPDATED_AT)),
                        errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)),
                        payload = c.getString(c.getColumnIndexOrThrow(COL_PAYLOAD)),
                        isExpedited = c.getInt(c.getColumnIndexOrThrow(COL_IS_EXPEDITED)) == 1
                    )
                )
            }
        }
        return list
    }

    fun getTaskById(taskId: String): BackgroundTaskRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TASKS,
            null,
            "$COL_ID = ?",
            arrayOf(taskId),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val statusStr = c.getString(c.getColumnIndexOrThrow(COL_STATUS))
                val status = try { TaskStatus.valueOf(statusStr) } catch (_: Exception) { TaskStatus.QUEUED }
                return BackgroundTaskRecord(
                    id = c.getString(c.getColumnIndexOrThrow(COL_ID)),
                    title = c.getString(c.getColumnIndexOrThrow(COL_TITLE)),
                    description = c.getString(c.getColumnIndexOrThrow(COL_DESCRIPTION)) ?: "",
                    taskType = c.getString(c.getColumnIndexOrThrow(COL_TASK_TYPE)),
                    status = status,
                    progress = c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS)),
                    createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow(COL_UPDATED_AT)),
                    errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR_MESSAGE)),
                    payload = c.getString(c.getColumnIndexOrThrow(COL_PAYLOAD)),
                    isExpedited = c.getInt(c.getColumnIndexOrThrow(COL_IS_EXPEDITED)) == 1
                )
            }
        }
        return null
    }

    fun deleteCompletedTasks() {
        val db = writableDatabase
        db.delete(
            TABLE_TASKS,
            "$COL_STATUS IN (?, ?, ?)",
            arrayOf(TaskStatus.COMPLETED.name, TaskStatus.FAILED.name, TaskStatus.CANCELLED.name)
        )
    }

    // --- Network Ledger Operations ---

    fun saveNetworkLedgerEntry(entry: NetworkLedgerEntry) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_LEDGER_ID, entry.id)
            put(COL_TIMESTAMP, entry.timestamp)
            put(COL_URL, entry.url)
            put(COL_HOST, entry.host)
            put(COL_PURPOSE, entry.purpose)
            put(COL_BYTES, entry.bytesTransferred)
            put(COL_LEDGER_STATUS, entry.status)
            put(COL_LEDGER_ERROR, entry.errorMessage)
        }
        db.insertWithOnConflict(TABLE_LEDGER, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getNetworkLedgerEntries(): List<NetworkLedgerEntry> {
        val list = mutableListOf<NetworkLedgerEntry>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LEDGER,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            "100" // latest 100 entries
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    NetworkLedgerEntry(
                        id = c.getString(c.getColumnIndexOrThrow(COL_LEDGER_ID)),
                        timestamp = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        url = c.getString(c.getColumnIndexOrThrow(COL_URL)),
                        host = c.getString(c.getColumnIndexOrThrow(COL_HOST)),
                        purpose = c.getString(c.getColumnIndexOrThrow(COL_PURPOSE)),
                        bytesTransferred = c.getLong(c.getColumnIndexOrThrow(COL_BYTES)),
                        status = c.getString(c.getColumnIndexOrThrow(COL_LEDGER_STATUS)),
                        errorMessage = c.getString(c.getColumnIndexOrThrow(COL_LEDGER_ERROR))
                    )
                )
            }
        }
        return list
    }

    fun clearNetworkLedger() {
        writableDatabase.delete(TABLE_LEDGER, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "finn_background_tasks.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_TASKS = "tasks"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_DESCRIPTION = "description"
        const val COL_TASK_TYPE = "task_type"
        const val COL_STATUS = "status"
        const val COL_PROGRESS = "progress"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_ERROR_MESSAGE = "error_message"
        const val COL_PAYLOAD = "payload"
        const val COL_IS_EXPEDITED = "is_expedited"

        const val TABLE_LEDGER = "network_ledger"
        const val COL_LEDGER_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_URL = "url"
        const val COL_HOST = "host"
        const val COL_PURPOSE = "purpose"
        const val COL_BYTES = "bytes_transferred"
        const val COL_LEDGER_STATUS = "status"
        const val COL_LEDGER_ERROR = "error_message"

        @Volatile
        private var instance: TaskDatabaseHelper? = null

        fun getInstance(context: Context): TaskDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: TaskDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
