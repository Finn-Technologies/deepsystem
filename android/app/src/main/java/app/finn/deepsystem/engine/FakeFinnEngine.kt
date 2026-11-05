package app.finn.deepsystem.engine

import app.finn.deepsystem.model.EngineStats
import java.util.concurrent.atomic.AtomicBoolean

class FakeFinnEngine(
    private val delayPerTokenMs: Long = 35L
) : FinnEngine {
    private val loaded = AtomicBoolean(true)
    private val cancelled = AtomicBoolean(false)
    var customTokens: List<String>? = null

    override val isLoaded: Boolean
        get() = loaded.get()

    override fun load(
        modelPath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean
    ): Boolean {
        loaded.set(true)
        cancelled.set(false)
        return true
    }

    override fun unload() {
        loaded.set(false)
        cancelled.set(false)
    }

    override fun generate(
        prompt: String,
        imageBytes: ByteArray?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback
    ): EngineStats? {
        if (!loaded.get()) return null
        cancelled.set(false)

        val startTime = System.currentTimeMillis()
        var ttftMs = 0.0

        val tokens = customTokens ?: when {
            imageBytes != null -> listOf(
                "I", " see", " an", " attached", " image", "!", " It", " appears", " to", " have",
                " clear", " visual", " features", " with", " vibrant", " colors.",
                " In", " Finn", " DeepSystem", ",", " multimodal", " vision", " is", " processed",
                " fully", " locally", " via", " LiquidAI", " LFM3", "-Omni", "-2B", "."
            )
            prompt.contains("hello", ignoreCase = true) || prompt.contains("hi", ignoreCase = true) -> listOf(
                "Hello", " there", "!", " I", " am", " Finn", ",", " your", " private",
                " on", "-device", " AI", " assistant", ".", " How", " can", " I",
                " help", " you", " today", "?"
            )
            else -> listOf(
                "Finn", " DeepSystem", " is", " running", " locally", " on", " your", " device", ".",
                " All", " computation", " stays", " strictly", " private", " without", " sending",
                " data", " to", " any", " cloud", " server", ".", " Fast", " on", "-device",
                " intelligence", " powered", " by", " LFM3", "-Omni", " and", " llama", ".cpp", "."
            )
        }

        var completedTokens = 0
        for ((index, token) in tokens.withIndex()) {
            if (cancelled.get()) break
            if (completedTokens >= maxTokens) break

            if (delayPerTokenMs > 0) {
                try {
                    Thread.sleep(delayPerTokenMs)
                } catch (e: InterruptedException) {
                    cancelled.set(true)
                    break
                }
            }

            if (cancelled.get()) break

            if (index == 0) {
                ttftMs = (System.currentTimeMillis() - startTime).toDouble()
            }

            completedTokens++
            val keepGoing = callback.onToken(token)
            if (!keepGoing) {
                cancelled.set(true)
                break
            }
        }

        val totalTimeMs = (System.currentTimeMillis() - startTime).toDouble()
        val tokensPerSec = if (totalTimeMs > 0) (completedTokens * 1000.0) / totalTimeMs else 0.0

        return EngineStats(
            ttftMs = if (ttftMs > 0) ttftMs else 45.0,
            tokensPerSecond = tokensPerSec,
            promptTokens = prompt.split(" ").size,
            completionTokens = completedTokens,
            totalTimeMs = totalTimeMs
        )
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun isCancelled(): Boolean = cancelled.get()

    override fun close() {
        unload()
    }
}
