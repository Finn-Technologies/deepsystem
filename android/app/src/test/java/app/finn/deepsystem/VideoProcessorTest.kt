package app.finn.deepsystem

import app.finn.deepsystem.video.ProcessedVideo
import app.finn.deepsystem.video.VideoProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoProcessorTest {

    @Test
    fun testDefaultVideoPromptMatchesLfm3Card() {
        assertEquals("Answer questions about the video using what you see and hear.", ProcessedVideo.DEFAULT_VIDEO_PROMPT)
    }

    @Test
    fun testResampleAndDownmixStereoTo16kMono() {
        // 1000 frames of stereo audio at 48000 Hz = 2000 shorts = 4000 bytes
        val inputFrames = 1000
        val inputChannels = 2
        val inputSampleRate = 48000

        val inBytes = ByteArray(inputFrames * inputChannels * 2)
        val inBuffer = ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Write stereo sine wave
        for (i in 0 until inputFrames) {
            val s = (5000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / inputSampleRate)).toInt().toShort()
            inBuffer.putShort(s) // Left
            inBuffer.putShort(s) // Right
        }

        val outBytes = VideoProcessor.resampleAndDownmixTo16kMono(inBytes, inputSampleRate, inputChannels)
        // Ratio 16000 / 48000 = 1/3 -> ~333 output frames * 2 bytes = ~666 bytes
        val expectedFrames = (inputFrames.toLong() * 16000 / inputSampleRate).toInt()
        assertEquals(expectedFrames * 2, outBytes.size)

        // Verify output is valid 16-bit PCM
        val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var maxSample: Short = 0
        while (outBuffer.hasRemaining()) {
            val sample = Math.abs(outBuffer.get().toInt()).toShort()
            if (sample > maxSample) maxSample = sample
        }
        assertTrue(maxSample > 1000)
    }

    @Test
    fun testResampleEmptyBuffer() {
        val out = VideoProcessor.resampleAndDownmixTo16kMono(ByteArray(0), 44100, 2)
        assertEquals(0, out.size)
    }
}
