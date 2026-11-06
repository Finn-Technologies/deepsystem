package app.finn.deepsystem.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Service that instantiates and manages the lifecycle of FinnVoiceInteractionSession.
 */
class FinnVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "Creating new FinnVoiceInteractionSession")
        return FinnVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "FinnVoiceSessionService"
    }
}
