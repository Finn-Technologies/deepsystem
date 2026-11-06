package app.finn.deepsystem.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.finn.deepsystem.ui.theme.FinnTheme

/**
 * Handles the interactive overlay session when Finn is summoned via gesture,
 * long-press power, or ACTION_ASSIST.
 */
class FinnVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionLifecycleOwner = SessionLifecycleOwner()
    private val extractedTextState = mutableStateOf<String?>(null)
    private val screenshotState = mutableStateOf<Bitmap?>(null)

    override fun onCreate() {
        super.onCreate()
        sessionLifecycleOwner.onCreate()
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(sessionLifecycleOwner)
            setViewTreeViewModelStoreOwner(sessionLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(sessionLifecycleOwner)

            setContent {
                FinnTheme(darkTheme = true) {
                    AssistOverlayContent(
                        extractedScreenText = extractedTextState.value,
                        screenshot = screenshotState.value,
                        onDismiss = { hide() }
                    )
                }
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionLifecycleOwner.onStart()
        Log.i(TAG, "FinnVoiceInteractionSession onShow (flags=$showFlags)")
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        val extracted = extractTextFromStructure(structure)
        if (extracted.isNotBlank()) {
            Log.i(TAG, "Extracted ${extracted.length} chars of screen context")
            extractedTextState.value = extracted.take(1500)
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            Log.i(TAG, "Received screenshot: ${screenshot.width}x${screenshot.height}")
            screenshotState.value = screenshot
        }
    }

    override fun onHide() {
        super.onHide()
        sessionLifecycleOwner.onStop()
        extractedTextState.value = null
        screenshotState.value = null
        Log.i(TAG, "FinnVoiceInteractionSession onHide")
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionLifecycleOwner.onDestroy()
    }

    private fun extractTextFromStructure(structure: AssistStructure?): String {
        if (structure == null) return ""
        val sb = StringBuilder()
        val count = structure.windowNodeCount
        for (i in 0 until count) {
            val windowNode = structure.getWindowNodeAt(i)
            traverseNode(windowNode.rootViewNode, sb)
        }
        return sb.toString().trim()
    }

    private fun traverseNode(node: AssistStructure.ViewNode?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text
        if (!text.isNullOrBlank()) {
            sb.append(text).append("\n")
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), sb)
        }
    }

    companion object {
        private const val TAG = "FinnVoiceSession"
    }
}

/**
 * Custom lifecycle and saved state owner for hosting Compose inside VoiceInteractionSession.
 */
class SessionLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
