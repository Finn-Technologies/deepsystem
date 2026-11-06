package app.finn.deepsystem.notes

import app.finn.deepsystem.engine.FinnEngine
import app.finn.deepsystem.engine.TokenCallback
import app.finn.deepsystem.model.EngineStats

/** Text-only generation convenience used by notes and mini apps. */
fun FinnEngine.generateText(
    prompt: String,
    temperature: Float = 0.7f,
    maxTokens: Int = 512,
    callback: TokenCallback
): EngineStats? = generate(prompt, null, temperature, maxTokens, callback)

/** Video understanding: sampled JPEG frames plus 16 kHz mono PCM audio. */
fun FinnEngine.generateVideo(
    prompt: String,
    videoFrames: List<ByteArray>,
    audioBytes: ByteArray?,
    temperature: Float = 0.7f,
    maxTokens: Int = 512,
    callback: TokenCallback
): EngineStats? = generateMultimodal(
    prompt = prompt,
    imageBytes = null,
    audioBytes = audioBytes,
    videoFrames = videoFrames.toTypedArray(),
    temperature = temperature,
    maxTokens = maxTokens,
    callback = callback
)
