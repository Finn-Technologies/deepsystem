package app.finn.deepsystem.engine

import android.util.Log
import app.finn.deepsystem.model.EngineStats

class NativeFinnEngine(private val isFakeEngine: Boolean = false) : FinnEngine {
    private val lock = Any()
    private var handle: Long = 0

    init {
        check(NativeFinnBridge.isNativeLoaded()) {
            "Native finn library is not loaded. Error: ${NativeFinnBridge.loadError?.message}"
        }
        handle = NativeFinnBridge.nativeEngineCreate(isFakeEngine)
        Log.i(TAG, "NativeFinnEngine created with handle=$handle (isFake=$isFakeEngine)")
    }

    val nativeHandle: Long
        get() = synchronized(lock) { handle }

    override val isLoaded: Boolean
        get() = synchronized(lock) {
            if (handle == 0L) false else NativeFinnBridge.nativeEngineIsLoaded(handle)
        }

    override fun load(
        modelPath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean
    ): Boolean = synchronized(lock) {
        if (handle == 0L) return false
        val res = NativeFinnBridge.nativeEngineLoad(
            handle,
            modelPath,
            mmprojPath,
            nCtx,
            nThreads,
            nGpuLayers,
            useMmap
        )
        return res == 0
    }

    override fun unload() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeEngineUnload(handle)
        }
    }

    override fun generate(
        prompt: String,
        imageBytes: ByteArray?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback
    ): EngineStats? {
        return generateMultimodal(
            prompt = prompt,
            imageBytes = imageBytes,
            audioBytes = null,
            videoFrames = null,
            temperature = temperature,
            maxTokens = maxTokens,
            callback = callback
        )
    }

    override fun generateMultimodal(
        prompt: String,
        imageBytes: ByteArray?,
        audioBytes: ByteArray?,
        videoFrames: Array<ByteArray>?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback
    ): EngineStats? {
        val h = synchronized(lock) { handle }
        if (h == 0L) return null

        val statsOut = DoubleArray(5)
        val res = NativeFinnBridge.nativeEngineGenerateMultimodal(
            h,
            prompt,
            imageBytes,
            audioBytes,
            videoFrames,
            temperature,
            maxTokens,
            callback,
            statsOut
        )

        return if (res == 0) {
            EngineStats(
                ttftMs = statsOut[0],
                tokensPerSecond = statsOut[1],
                promptTokens = statsOut[2].toInt(),
                completionTokens = statsOut[3].toInt(),
                totalTimeMs = statsOut[4]
            )
        } else {
            if (statsOut[3] > 0) {
                EngineStats(
                    ttftMs = statsOut[0],
                    tokensPerSecond = statsOut[1],
                    promptTokens = statsOut[2].toInt(),
                    completionTokens = statsOut[3].toInt(),
                    totalTimeMs = statsOut[4]
                )
            } else null
        }
    }

    override fun speak(
        text: String,
        voice: String,
        callback: AudioChunkCallback?
    ): Boolean {
        val h = synchronized(lock) { handle }
        if (h == 0L) return false
        val res = NativeFinnBridge.nativeEngineSpeak(h, text, voice, callback)
        return res == 0
    }

    override fun cancel() {
        val h = synchronized(lock) { handle }
        if (h != 0L) {
            NativeFinnBridge.nativeEngineCancel(h)
        }
    }

    override fun isCancelled(): Boolean {
        val h = synchronized(lock) { handle }
        return if (h != 0L) NativeFinnBridge.nativeEngineIsCancelled(h) else true
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeFinnBridge.nativeEngineDestroy(handle)
            handle = 0L
        }
    }

    protected fun finalize() {
        close()
    }

    companion object {
        private const val TAG = "NativeFinnEngine"
    }
}
