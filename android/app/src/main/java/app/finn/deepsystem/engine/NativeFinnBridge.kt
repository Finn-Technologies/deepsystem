package app.finn.deepsystem.engine

import android.util.Log
import app.finn.deepsystem.live.LiveStateCallback

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
    external fun nativeEngineGenerateMultimodal(
        handle: Long,
        prompt: String,
        imageBytes: ByteArray?,
        audioBytes: ByteArray?,
        videoFrames: Array<ByteArray>?,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback,
        statsOut: DoubleArray?
    ): Int

    @JvmStatic
    external fun nativeEngineSpeak(
        handle: Long,
        text: String,
        voice: String,
        callback: AudioChunkCallback?
    ): Int

    @JvmStatic
    external fun nativeEngineCancel(handle: Long)

    @JvmStatic
    external fun nativeEngineIsCancelled(handle: Long): Boolean

    // Live Session JNI declarations
    @JvmStatic
    external fun nativeLiveCreate(
        engineHandle: Long,
        vadThreshold: Float,
        silenceTimeoutMs: Int,
        voice: String,
        systemPrompt: String
    ): Long

    @JvmStatic
    external fun nativeLiveDestroy(handle: Long)

    @JvmStatic
    external fun nativeLiveStart(handle: Long)

    @JvmStatic
    external fun nativeLiveStop(handle: Long)

    @JvmStatic
    external fun nativeLiveFeedAudio(handle: Long, pcm: ShortArray, offset: Int, length: Int)

    @JvmStatic
    external fun nativeLiveFeedCameraFrame(handle: Long, frameBytes: ByteArray?)

    @JvmStatic
    external fun nativeLiveSetCallbacks(
        handle: Long,
        stateCallback: LiveStateCallback?,
        audioCallback: AudioChunkCallback?
    )

    @JvmStatic
    external fun nativeLiveGetState(handle: Long): Int

    @JvmStatic
    external fun nativeLiveGetLastLatencyMs(handle: Long): Double

    @JvmStatic
    external fun nativeLiveTriggerBargeIn(handle: Long)

    @JvmStatic
    external fun nativeLiveTriggerEndOfSpeech(handle: Long)

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

    // Notes JNI declarations
    @JvmStatic
    external fun nativeStorageCreateNote(
        handle: Long,
        id: String,
        title: String,
        content: String,
        folder: String,
        tags: String,
        isPinned: Boolean,
        mediaPath: String,
        mediaType: String,
        aiSummary: String,
        aiTranscript: String
    ): Int

    @JvmStatic
    external fun nativeStorageUpdateNote(
        handle: Long,
        id: String,
        title: String,
        content: String,
        folder: String,
        tags: String,
        isPinned: Boolean,
        mediaPath: String,
        mediaType: String,
        aiSummary: String,
        aiTranscript: String
    ): Int

    @JvmStatic
    external fun nativeStorageDeleteNote(handle: Long, id: String): Int

    @JvmStatic
    external fun nativeStorageGetNoteJson(handle: Long, id: String): String?

    @JvmStatic
    external fun nativeStorageListNotesJson(handle: Long, folder: String?, tag: String?): String?

    @JvmStatic
    external fun nativeStorageSearchNotesJson(handle: Long, query: String?): String?

    // Mini Apps JNI declarations
    @JvmStatic
    external fun nativeStorageCreateMiniApp(
        handle: Long,
        id: String,
        title: String,
        description: String,
        htmlContent: String,
        prompt: String,
        isPinned: Boolean
    ): Int

    @JvmStatic
    external fun nativeStorageUpdateMiniApp(
        handle: Long,
        id: String,
        title: String,
        description: String,
        htmlContent: String,
        prompt: String,
        isPinned: Boolean
    ): Int

    @JvmStatic
    external fun nativeStorageDeleteMiniApp(handle: Long, id: String): Int

    @JvmStatic
    external fun nativeStorageGetMiniAppJson(handle: Long, id: String): String?

    @JvmStatic
    external fun nativeStorageListMiniAppsJson(handle: Long): String?

    @JvmStatic
    external fun nativeStorageSetMiniAppKv(handle: Long, appId: String, key: String, value: String): Int

    @JvmStatic
    external fun nativeStorageGetMiniAppKv(handle: Long, appId: String, key: String): String?

    @JvmStatic
    external fun nativeStorageDeleteMiniAppKv(handle: Long, appId: String, key: String): Int
}
