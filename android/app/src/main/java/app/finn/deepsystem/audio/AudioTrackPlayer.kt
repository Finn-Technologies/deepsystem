package app.finn.deepsystem.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioTrackPlayer {
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 24000
    private val lock = Any()

    fun playChunk(pcm: ShortArray, sampleRate: Int = 24000) {
        synchronized(lock) {
            if (pcm.isEmpty()) return

            if (audioTrack == null || currentSampleRate != sampleRate) {
                initTrack(sampleRate)
            }

            val track = audioTrack ?: return
            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(pcm, 0, pcm.size)
            } catch (t: Throwable) {
                Log.e(TAG, "Error playing audio chunk: ${t.message}")
            }
        }
    }

    fun stopImmediately() = synchronized(lock) {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error in stopImmediately: ${t.message}")
        }
    }

    fun release() = synchronized(lock) {
        stopImmediately()
        try {
            audioTrack?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing audio track: ${t.message}")
        } finally {
            audioTrack = null
        }
    }

    private fun initTrack(sampleRate: Int) {
        try {
            audioTrack?.release()
        } catch (_: Throwable) {}

        currentSampleRate = sampleRate
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.i(TAG, "AudioTrack initialized at ${sampleRate}Hz")
    }

    companion object {
        private const val TAG = "AudioTrackPlayer"
    }
}
