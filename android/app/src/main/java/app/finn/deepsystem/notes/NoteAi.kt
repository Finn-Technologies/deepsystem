package app.finn.deepsystem.notes

import app.finn.deepsystem.audio.AudioPromptHelper
import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.video.VideoProcessor
import java.io.File

/** Prompt templates and pipelines for AI note features (ASR, summary, image description, auto-organise). */
object NoteAi {
    val ASR_PROMPT: String get() = AudioPromptHelper.buildAsrPrompt()

    fun summaryPrompt(content: String): String =
        "<|im_start|>user\nProvide a concise bulleted summary of the following note:\n\n${content.take(6000)}<|im_end|>\n<|im_start|>assistant\n"

    fun imagePrompt(): String =
        "<|im_start|>user\nExtract all text visible (OCR) and describe the content of this image in detail.<|im_end|>\n<|im_start|>assistant\n"

    fun organizePrompt(content: String): String =
        "<|im_start|>user\nSuggest a short note title (max 6 words) and up to 3 single-word lowercase tags for this note. " +
            "Reply exactly as:\nTITLE: <title>\nTAGS: tag1, tag2\n\nNote:\n${content.take(3000)}<|im_end|>\n<|im_start|>assistant\n"

    /** Parses the reply to [organizePrompt]. */
    fun parseOrganize(reply: String): Pair<String, List<String>> {
        var title = ""
        var tags = emptyList<String>()
        for (line in reply.lines()) {
            val l = line.trim()
            if (l.startsWith("TITLE:", true)) title = l.substringAfter(':').trim().trim('"')
            else if (l.startsWith("TAGS:", true)) tags = l.substringAfter(':').split(",")
                .map { it.trim().trimStart('#').lowercase() }.filter { it.isNotBlank() && it.length < 24 }.take(3)
        }
        return title to tags
    }

    /** Looks at a (possibly non-PCM) audio file and returns 16 kHz mono PCM16 bytes for the engine. */
    fun audioFileToPcm16k(file: File): ByteArray {
        val ext = file.extension.lowercase()
        if (ext == "pcm" || ext == "raw") return file.readBytes()
        return try {
            VideoProcessor().extractAudioTrack(file.absolutePath) ?: file.readBytes()
        } catch (t: Throwable) {
            file.readBytes()
        }
    }

    fun run(engine: FinnEngine, block: (TokenCallback) -> Unit): String {
        val sb = StringBuilder()
        block(TokenCallback { sb.append(it); true })
        return sb.toString().trim()
    }

    fun summarize(engine: FinnEngine, content: String): String =
        run(engine) { engine.generateText(summaryPrompt(content), 0.3f, 256, it) }

    fun transcribeAudio(engine: FinnEngine, file: File): String =
        run(engine) { engine.generateMultimodal(ASR_PROMPT, null, audioFileToPcm16k(file), null, 0.2f, 512, it) }

    fun describeImage(engine: FinnEngine, file: File): String =
        run(engine) { engine.generateMultimodal(imagePrompt(), file.readBytes(), null, null, 0.2f, 512, it) }

    suspend fun transcribeVideo(engine: FinnEngine, file: File): String {
        val v = VideoProcessor().processVideoFile(file)
        val prompt = "<|im_start|>user\nTranscribe speech and describe key actions occurring in this video.<|im_end|>\n<|im_start|>assistant\n"
        return run(engine) { engine.generateVideo(prompt, v.frameJpegs, v.audioPcm16k, 0.2f, 512, it) }
    }
}
