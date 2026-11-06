package app.finn.deepsystem.attachments

import android.content.Context
import android.net.Uri
import android.util.Log
import app.finn.deepsystem.video.ProcessedVideo
import app.finn.deepsystem.video.VideoProcessor

/**
 * Turns picked media (audio / video content URIs) into engine-ready inputs:
 * 16 kHz mono s16le PCM for audio and sampled JPEG frames (+ audio track) for video.
 * Blocking work; call from an IO dispatcher.
 */
class AttachmentProcessor(
    private val context: Context,
    private val videoProcessor: VideoProcessor = VideoProcessor()
) {
    suspend fun processAudio(uri: Uri): ByteArray? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            videoProcessor.extractAudioFromFd(it.fileDescriptor)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Audio decode failed: ${t.message}")
        null
    }

    suspend fun processVideo(uri: Uri): ProcessedVideo? = try {
        videoProcessor.processVideoUri(context, uri)
    } catch (t: Throwable) {
        Log.w(TAG, "Video processing failed: ${t.message}")
        null
    }

    private companion object {
        const val TAG = "AttachmentProcessor"
    }
}
