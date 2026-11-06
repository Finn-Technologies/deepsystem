package app.finn.deepsystem.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.util.Log

/**
 * On-device RecognitionService for voice recognition callbacks.
 */
class FinnRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening called")
        // Ready for speech
        listener?.readyForSpeech(Bundle())
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening called")
        listener?.endOfSpeech()
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel called")
    }

    companion object {
        private const val TAG = "FinnRecognitionService"
    }
}
