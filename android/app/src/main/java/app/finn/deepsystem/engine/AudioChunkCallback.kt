package app.finn.deepsystem.engine

fun interface AudioChunkCallback {
    /**
     * Called when a chunk of synthesized PCM audio is produced.
     * @param pcm 16-bit PCM samples
     * @param sampleRate sample rate (e.g. 24000 Hz or 16000 Hz)
     * @return true to continue playback/synthesis, false to cancel
     */
    fun onAudioChunk(pcm: ShortArray, sampleRate: Int): Boolean
}
