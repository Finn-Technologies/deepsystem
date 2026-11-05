package app.finn.deepsystem.model

data class Conversation(
    val id: String,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis()
)
