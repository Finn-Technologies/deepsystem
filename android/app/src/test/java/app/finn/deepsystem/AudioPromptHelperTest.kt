package app.finn.deepsystem

import app.finn.deepsystem.audio.AudioPromptHelper
import app.finn.deepsystem.audio.AudioRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPromptHelperTest {

    @Test
    fun testBuildAsrPromptEnglish() {
        val prompt = AudioPromptHelper.buildAsrPrompt("English")
        assertTrue(prompt.contains("<|im_start|>user\nPerform ASR in English.<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testBuildAsrPromptSpanish() {
        val prompt = AudioPromptHelper.buildAsrPrompt("Spanish")
        assertTrue(prompt.contains("Perform ASR in Spanish."))
    }

    @Test
    fun testBuildAudioQAPrompt() {
        val prompt = AudioPromptHelper.buildAudioQAPrompt("Summarize the conversation in this audio.")
        assertTrue(prompt.contains("Summarize the conversation in this audio."))
    }

    @Test
    fun testComputeRms() {
        val samples = ShortArray(100) { 1000 }
        val rms = AudioRecorder.computeRms(samples, samples.size)
        assertEquals(1000.0f, rms, 1.0f)
    }
}
