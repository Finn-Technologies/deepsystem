package app.finn.deepsystem.model

data class EngineStats(
    val ttftMs: Double = 0.0,
    val tokensPerSecond: Double = 0.0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTimeMs: Double = 0.0
)
