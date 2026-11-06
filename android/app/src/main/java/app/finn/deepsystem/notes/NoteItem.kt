package app.finn.deepsystem.notes

data class NoteItem(
    val id: String,
    val title: String,
    val content: String,
    val folder: String = "General",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val mediaPath: String? = null,
    val mediaType: String = "none", // "none", "text", "image", "video", "audio"
    val aiSummary: String? = null,
    val aiTranscript: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getSnippet(maxLength: Int = 120): String {
        val clean = content.replace("\n", " ").trim()
        return if (clean.length > maxLength) {
            clean.substring(0, maxLength) + "..."
        } else {
            clean
        }
    }
}
