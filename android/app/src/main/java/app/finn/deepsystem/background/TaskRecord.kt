package app.finn.deepsystem.background

/**
 * Lifecycle states of an asynchronous background agent task.
 */
enum class TaskStatus(val displayName: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    PAUSED_THERMAL("Paused (Thermal)"),
    PAUSED_BATTERY("Paused (Low Battery)")
}

/**
 * Types of background agent operations.
 */
object TaskTypes {
    const val KNOWLEDGE_DIGEST = "knowledge_digest"
    const val MODEL_VERIFICATION = "model_verification"
    const val CONTEXT_INDEXING = "context_indexing"
    const val BATCH_INFERENCE = "batch_inference"
    const val SHORT_SERVICE_AGENT = "short_service_agent"
}

/**
 * Record of a background agent task stored in the SQLite database.
 */
data class BackgroundTaskRecord(
    val id: String,
    val title: String,
    val description: String = "",
    val taskType: String = TaskTypes.KNOWLEDGE_DIGEST,
    val status: TaskStatus = TaskStatus.QUEUED,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val payload: String? = null,
    val isExpedited: Boolean = false
) {
    val isFinished: Boolean
        get() = status == TaskStatus.COMPLETED ||
                status == TaskStatus.FAILED ||
                status == TaskStatus.CANCELLED
}
