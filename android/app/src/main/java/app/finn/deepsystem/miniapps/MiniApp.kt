package app.finn.deepsystem.miniapps

data class MiniApp(
    val id: String,
    val title: String,
    val description: String,
    val html: String,
    val prompt: String = "",
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
