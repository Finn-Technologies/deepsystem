package app.finn.deepsystem.audio

/**
 * Prompt formatting helper for Audio and ASR modes based on LiquidAI LFM3 model card.
 */
object AudioPromptHelper {
    /**
     * Format prompt for Automatic Speech Recognition (ASR).
     * Template: "Perform ASR in <lang>."
     */
    fun buildAsrPrompt(language: String = "English"): String {
        return "<|im_start|>user\nPerform ASR in $language.<|im_end|>\n<|im_start|>assistant\n"
    }

    /**
     * Format prompt for direct audio understanding / question answering.
     */
    fun buildAudioQAPrompt(userQuestion: String = "What is being said or heard in this audio?"): String {
        return "<|im_start|>user\n$userQuestion<|im_end|>\n<|im_start|>assistant\n"
    }
}
