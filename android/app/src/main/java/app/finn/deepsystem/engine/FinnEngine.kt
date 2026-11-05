package app.finn.deepsystem.engine

import app.finn.deepsystem.model.EngineStats

interface FinnEngine {
    val isLoaded: Boolean

    fun load(
        modelPath: String,
        mmprojPath: String = "",
        nCtx: Int = 2048,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
        useMmap: Boolean = true
    ): Boolean

    fun unload()

    fun generate(
        prompt: String,
        imageBytes: ByteArray? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 512,
        callback: TokenCallback
    ): EngineStats?

    fun cancel()

    fun isCancelled(): Boolean

    fun close()
}
