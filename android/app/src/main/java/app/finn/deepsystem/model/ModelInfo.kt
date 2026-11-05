package app.finn.deepsystem.model

sealed class DownloadState {
    object Idle : DownloadState()
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Float,
        val speedBytesPerSec: Long = 0
    ) : DownloadState()
    object Verifying : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

data class ModelTarget(
    val filename: String,
    val relativeUrl: String,
    val expectedBytes: Long,
    val sha256Hex: String,
    val isRequired: Boolean = true,
    val description: String = ""
)

object KnownModels {
    val BASE_URL = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main"

    val TEXT_MODEL = ModelTarget(
        filename = "LFM3-Omni-2B-Q4_K_M.gguf",
        relativeUrl = "LFM3-Omni-2B-Q4_K_M.gguf",
        expectedBytes = 1099630080L,
        sha256Hex = "9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2",
        isRequired = true,
        description = "LFM3-Omni-2B Q4_K_M Base Weights (~1.02 GB)"
    )

    val MMPROJ_MODEL = ModelTarget(
        filename = "mmproj-LFM3-Omni-2B-Q4_K_M.gguf",
        relativeUrl = "mmproj-LFM3-Omni-2B-Q4_K_M.gguf",
        expectedBytes = 291586720L,
        sha256Hex = "0a746c0794782a16d4456b230c2a0b5888a2d94a6677211edbed7d7a4177a39a",
        isRequired = false,
        description = "Multimodal Projector Q4_K_M for Vision (~278 MB)"
    )

    val ALL_TARGETS = listOf(TEXT_MODEL, MMPROJ_MODEL)
}
