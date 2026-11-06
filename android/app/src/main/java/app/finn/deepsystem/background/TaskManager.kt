package app.finn.deepsystem.background

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-level coordinator for background agent tasks.
 * Combines in-memory reactive state (StateFlow) with durable SQLite persistence.
 */
class TaskManager private constructor(private val appContext: Context?) {

    private val dbHelper = appContext?.let { TaskDatabaseHelper.getInstance(it) }
    private val taskList = CopyOnWriteArrayList<BackgroundTaskRecord>()
    private val _tasks = MutableStateFlow<List<BackgroundTaskRecord>>(emptyList())
    val tasks: StateFlow<List<BackgroundTaskRecord>> = _tasks.asStateFlow()

    private val cancelListeners = ConcurrentHashMap<String, () -> Unit>()

    init {
        loadPersistedTasks()
    }

    private fun loadPersistedTasks() {
        if (dbHelper != null) {
            try {
                val loaded = dbHelper.getAllTasks()
                taskList.addAll(loaded)
                _tasks.value = taskList.toList()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load tasks from DB: ${t.message}")
            }
        }
    }

    fun enqueue(record: BackgroundTaskRecord) {
        taskList.removeIf { it.id == record.id }
        taskList.add(0, record)
        _tasks.value = taskList.toList()
        try {
            dbHelper?.insertOrUpdateTask(record)
        } catch (t: Throwable) {
            Log.w(TAG, "DB error on enqueue: ${t.message}")
        }
        Log.i(TAG, "Task enqueued: ${record.title} [${record.id}]")
    }

    fun updateProgress(taskId: String, progress: Int, status: TaskStatus = TaskStatus.RUNNING) {
        val index = taskList.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = taskList[index]
            val updated = old.copy(
                progress = progress.coerceIn(0, 100),
                status = status,
                updatedAt = System.currentTimeMillis()
            )
            taskList[index] = updated
            _tasks.value = taskList.toList()
            try {
                dbHelper?.insertOrUpdateTask(updated)
            } catch (t: Throwable) {
                Log.w(TAG, "DB error on progress update: ${t.message}")
            }
        }
    }

    fun updateStatus(taskId: String, status: TaskStatus, errorMessage: String? = null) {
        val index = taskList.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = taskList[index]
            val updated = old.copy(
                status = status,
                errorMessage = errorMessage ?: old.errorMessage,
                updatedAt = System.currentTimeMillis()
            )
            taskList[index] = updated
            _tasks.value = taskList.toList()
            try {
                dbHelper?.insertOrUpdateTask(updated)
            } catch (t: Throwable) {
                Log.w(TAG, "DB error on status update: ${t.message}")
            }
        }
    }

    fun completeTask(taskId: String) {
        val index = taskList.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = taskList[index]
            val updated = old.copy(
                status = TaskStatus.COMPLETED,
                progress = 100,
                updatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            taskList[index] = updated
            _tasks.value = taskList.toList()
            try {
                dbHelper?.insertOrUpdateTask(updated)
            } catch (t: Throwable) {
                Log.w(TAG, "DB error on complete: ${t.message}")
            }
            cancelListeners.remove(taskId)
            Log.i(TAG, "Task completed: ${old.title} [${old.id}]")
        }
    }

    fun failTask(taskId: String, error: String) {
        val index = taskList.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = taskList[index]
            val updated = old.copy(
                status = TaskStatus.FAILED,
                updatedAt = System.currentTimeMillis(),
                errorMessage = error
            )
            taskList[index] = updated
            _tasks.value = taskList.toList()
            try {
                dbHelper?.insertOrUpdateTask(updated)
            } catch (t: Throwable) {
                Log.w(TAG, "DB error on fail: ${t.message}")
            }
            cancelListeners.remove(taskId)
            Log.w(TAG, "Task failed: ${old.title} [${old.id}]: $error")
        }
    }

    fun cancelTask(taskId: String) {
        cancelListeners[taskId]?.invoke()
        cancelListeners.remove(taskId)

        val index = taskList.indexOfFirst { it.id == taskId }
        if (index != -1) {
            val old = taskList[index]
            val updated = old.copy(
                status = TaskStatus.CANCELLED,
                updatedAt = System.currentTimeMillis()
            )
            taskList[index] = updated
            _tasks.value = taskList.toList()
            try {
                dbHelper?.insertOrUpdateTask(updated)
            } catch (t: Throwable) {
                Log.w(TAG, "DB error on cancel: ${t.message}")
            }
            Log.i(TAG, "Task cancelled: ${old.title} [${old.id}]")
        }
    }

    fun registerCancelListener(taskId: String, onCancel: () -> Unit) {
        cancelListeners[taskId] = onCancel
    }

    fun unregisterCancelListener(taskId: String) {
        cancelListeners.remove(taskId)
    }

    fun getTask(taskId: String): BackgroundTaskRecord? {
        return taskList.firstOrNull { it.id == taskId } ?: dbHelper?.getTaskById(taskId)
    }

    fun clearFinished() {
        taskList.removeIf { it.isFinished }
        _tasks.value = taskList.toList()
        try {
            dbHelper?.deleteCompletedTasks()
        } catch (t: Throwable) {
            Log.w(TAG, "DB error on clearFinished: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "TaskManager"

        @Volatile
        private var instance: TaskManager? = null

        fun getInstance(context: Context?): TaskManager {
            return instance ?: synchronized(this) {
                instance ?: TaskManager(context?.applicationContext).also { instance = it }
            }
        }
    }
}
