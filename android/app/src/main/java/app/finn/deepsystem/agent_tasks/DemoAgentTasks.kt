package app.finn.deepsystem.agent_tasks

import android.content.Context
import app.finn.deepsystem.background.TaskTypes

/**
 * Pre-configured background agent tasks that can be triggered from UI or tests.
 */
object DemoAgentTasks {

    fun queueKnowledgeDigest(context: Context, allowOnBattery: Boolean = false): String {
        return AgentTaskScheduler.scheduleImmediateTask(
            context = context,
            title = "Offline Knowledge Digest",
            description = "Synthesizing recent conversational context and key memory notes",
            taskType = TaskTypes.KNOWLEDGE_DIGEST,
            isExpedited = true,
            allowOnBattery = allowOnBattery
        )
    }

    fun queueModelVerification(context: Context, allowOnBattery: Boolean = false): String {
        return AgentTaskScheduler.scheduleImmediateTask(
            context = context,
            title = "Model Weights Integrity Check",
            description = "Verifying GGUF tensors, SHA256 integrity, and quantization headers",
            taskType = TaskTypes.MODEL_VERIFICATION,
            isExpedited = true,
            allowOnBattery = allowOnBattery
        )
    }

    fun queueContextIndexing(context: Context, allowOnBattery: Boolean = false): String {
        return AgentTaskScheduler.scheduleImmediateTask(
            context = context,
            title = "Context Indexing & Embedding",
            description = "Building local offline vector search index for instant recall",
            taskType = TaskTypes.CONTEXT_INDEXING,
            isExpedited = false,
            allowOnBattery = allowOnBattery
        )
    }
}
