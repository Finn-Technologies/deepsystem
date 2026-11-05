package app.finn.deepsystem.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    val sampleRate: Int = SAMPLE_RATE_16K
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val recordingBuffer = ByteArrayOutputStream()
    private val lock = Any()

    val recording: Boolean
        get() = isRecording.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean = synchronized(lock) {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord: $minBufferSize")
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(3200)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return false
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)
            recordingBuffer.reset()
            Log.i(TAG, "AudioRecord started at ${sampleRate}Hz")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start AudioRecord: ${t.message}", t)
            false
        }
    }

    fun read(buffer: ShortArray, offset: Int = 0, length: Int = buffer.size): Int {
        val record = synchronized(lock) { audioRecord } ?: return -1
        if (!isRecording.get()) return -1

        val readSamples = record.read(buffer, offset, length)
        if (readSamples > 0) {
            // Buffer into recording memory for voice message retrieval
            synchronized(recordingBuffer) {
                for (i in offset until (offset + readSamples)) {
                    val s = buffer[i].toInt()
                    recordingBuffer.write(s and 0xFF)
                    recordingBuffer.write((s shr 8) and 0xFF)
                }
            }
        }
        return readSamples
    }

    fun stop(): ByteArray = synchronized(lock) {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping AudioRecord: ${t.message}")
        } finally {
            audioRecord = null
        }

        return synchronized(recordingBuffer) {
            val bytes = recordingBuffer.toByteArray()
            recordingBuffer.reset()
            Log.i(TAG, "AudioRecord stopped, captured ${bytes.size} bytes PCM")
            bytes
        }
    }

    fun release() {
        stop()
    }

    companion object {
        const val SAMPLE_RATE_16K = 16000
        private const val TAG = "AudioRecorder"

        fun computeRms(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sumSq = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sumSq += s * s
            }
            return Math.sqrt(sumSq / count).toFloat()
        }
    }
}
