package app.finn.deepsystem.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ProcessedVideo(
    val frameJpegs: List<ByteArray>,
    val audioPcm16k: ByteArray?,
    val defaultPrompt: String = DEFAULT_VIDEO_PROMPT
) {
    companion object {
        const val DEFAULT_VIDEO_PROMPT = "Answer questions about the video using what you see and hear."
    }
}

class VideoProcessor(
    private val targetFrameCount: Int = 4,
    private val maxDimension: Int = 384
) {

    suspend fun processVideoFile(file: File): ProcessedVideo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val frames = sampleFrames(retriever)
        retriever.release()

        val audio = extractAudioTrack(file.absolutePath)
        ProcessedVideo(frames, audio)
    }

    suspend fun processVideoUri(context: Context, uri: Uri): ProcessedVideo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val frames = sampleFrames(retriever)
        retriever.release()

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        val audio = pfd?.use {
            extractAudioFromFd(it.fileDescriptor)
        }

        ProcessedVideo(frames, audio)
    }

    fun sampleFrames(retriever: MediaMetadataRetriever): List<ByteArray> {
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 2000L
        val frames = mutableListOf<ByteArray>()

        for (i in 0 until targetFrameCount) {
            val timeUs = if (targetFrameCount > 1) {
                ((durationMs * 1000L * i) / (targetFrameCount - 1)).coerceAtLeast(0L)
            } else 0L

            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs)

            if (bitmap != null) {
                val scaled = resizeBitmap(bitmap, maxDimension)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                frames.add(stream.toByteArray())
            }
        }

        Log.i(TAG, "Sampled ${frames.size} video frames across ${durationMs}ms")
        return frames
    }

    fun extractAudioTrack(filePath: String): ByteArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            decodeAudioExtractor(extractor)
        } catch (t: Throwable) {
            Log.w(TAG, "Audio extraction failed from file: ${t.message}")
            null
        } finally {
            extractor.release()
        }
    }

    fun extractAudioFromFd(fd: FileDescriptor): ByteArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(fd)
            decodeAudioExtractor(extractor)
        } catch (t: Throwable) {
            Log.w(TAG, "Audio extraction failed from FD: ${t.message}")
            null
        } finally {
            extractor.release()
        }
    }

    private fun decodeAudioExtractor(extractor: MediaExtractor): ByteArray? {
        var audioTrackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            Log.i(TAG, "No audio track found in video")
            return null
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val inputChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val rawPcmOut = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val timeoutUs = 10000L

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    outputBuffer.get(chunk)
                    rawPcmOut.write(chunk)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true
                }
            }
        }

        codec.stop()
        codec.release()

        val pcmBytes = rawPcmOut.toByteArray()
        Log.i(TAG, "Decoded ${pcmBytes.size} bytes raw PCM (${inputSampleRate}Hz, $inputChannels channels)")

        return resampleAndDownmixTo16kMono(pcmBytes, inputSampleRate, inputChannels)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (width > height) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).toInt().coerceAtLeast(1)
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    companion object {
        private const val TAG = "VideoProcessor"

        /**
         * Downmix multi-channel 16-bit PCM to mono and resample to 16,000 Hz.
         */
        fun resampleAndDownmixTo16kMono(
            rawPcm: ByteArray,
            inputSampleRate: Int,
            inputChannels: Int
        ): ByteArray {
            if (rawPcm.isEmpty()) return ByteArray(0)

            val shortBuffer = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val totalShorts = shortBuffer.remaining()
            val frameCount = totalShorts / inputChannels.coerceAtLeast(1)

            // Step 1: Downmix channels to mono
            val monoSamples = FloatArray(frameCount)
            for (i in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until inputChannels) {
                    sum += shortBuffer.get(i * inputChannels + c).toFloat()
                }
                monoSamples[i] = sum / inputChannels
            }

            // Step 2: Resample to 16,000 Hz via linear interpolation
            val targetSampleRate = 16000
            val targetFrameCount = ((frameCount.toLong() * targetSampleRate) / inputSampleRate).toInt()
            val outBytes = ByteArray(targetFrameCount * 2)
            val outByteBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

            val ratio = inputSampleRate.toDouble() / targetSampleRate.toDouble()
            for (i in 0 until targetFrameCount) {
                val srcIndex = i * ratio
                val i0 = srcIndex.toInt().coerceIn(0, monoSamples.size - 1)
                val i1 = (i0 + 1).coerceIn(0, monoSamples.size - 1)
                val frac = (srcIndex - i0).toFloat()

                val sample = monoSamples[i0] * (1f - frac) + monoSamples[i1] * frac
                val clamped = sample.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                outByteBuffer.putShort(clamped)
            }

            return outBytes
        }
    }
}
