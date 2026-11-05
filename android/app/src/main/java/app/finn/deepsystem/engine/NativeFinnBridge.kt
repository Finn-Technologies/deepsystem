package app.finn.deepsystem.engine

import android.util.Log

object NativeFinnBridge {
    private const val TAG = "NativeFinnBridge"
    private var nativeLoaded = false
    var loadError: Throwable? = null
        private set

    init {
        try {
            // Pre-load dependencies if available
            tryLoadLibrary("ggml-base")
            tryLoadLibrary("ggml-cpu")
            tryLoadLibrary("ggml")
            tryLoadLibrary("llama-common")
            tryLoadLibrary("llama")
            tryLoadLibrary("mtmd")
            System.loadLibrary("finn_jni")
            nativeLoaded = true
            Log.i(TAG, "Native finn_jni library loaded successfully")
        } catch (t: Throwable) {
            loadError = t
            Log.w(TAG, "Failed to load native library (expected in unit tests): ${t.message}")
        }
    }

    private fun tryLoadLibrary(name: String) {
        try {
            System.loadLibrary(name)
        } catch (t: Throwable) {
            Log.d(TAG, "Optional library $name not found or already linked: ${t.message}")
        }
    }

    fun isNativeLoaded(): Boolean = nativeLoaded

    // Engine JNI declarations
    @JvmStatic
    external fun nativeEngineCreate(isFake: Boolean): Long

    @JvmStatic
    external fun nativeEngineDestroy(handle: Long)

    @JvmStatic
    external fun nativeEngineLoad(
        handle: Long,
        modelPath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean
    ): Int

    @JvmStatic
    external fun nativeEngineUnload(handle: Long)

    @JvmStatic
    external fun nativeEngineIsLoaded(handle: Long): Boolean

    @JvmStatic
    external fun nativeEngineGenerate(
        handle: Long,
        prompt: String,
        imageBytes: ByteArray?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback,
        statsOut: DoubleArray?
    ): Int

    @JvmStatic
    external fun nativeEngineCancel(handle: Long)

    @JvmStatic
    external fun nativeEngineIsCancelled(handle: Long): Boolean

    // Storage JNI declarations
    @JvmStatic
    external fun nativeStorageCreate(): Long

    @JvmStatic
    external fun nativeStorageDestroy(handle: Long)

    @JvmStatic
    external fun nativeStorageOpen(handle: Long, dbPath: String): Int

    @JvmStatic
    external fun nativeStorageClose(handle: Long)

    @JvmStatic
    external fun nativeStorageCreateConversation(handle: Long, id: String, title: String): Int

    @JvmStatic
    external fun nativeStorageDeleteConversation(handle: Long, id: String): Int

    @JvmStatic
    external fun nativeStorageAddMessage(handle: Long, convId: String, role: String, content: String): Int

    @JvmStatic
    external fun nativeStorageListConversationsJson(handle: Long): String?

    @JvmStatic
    external fun nativeStorageGetMessagesJson(handle: Long, convId: String): String?
}
