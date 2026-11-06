package app.finn.deepsystem.assistant

import android.content.ComponentName
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android VoiceInteractionService for Finn.
 * Bound by the Android OS when Finn is configured as the active Assistant.
 */
class FinnVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        val component = ComponentName(this, FinnVoiceInteractionService::class.java)
        val isActive = isActiveService(this, component)
        Log.i(TAG, "FinnVoiceInteractionService onReady (isActiveAssistant=$isActive)")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "FinnVoiceInteractionService onShutdown")
    }

    companion object {
        private const val TAG = "FinnVoiceInteractionService"
    }
}
