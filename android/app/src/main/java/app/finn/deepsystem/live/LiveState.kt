package app.finn.deepsystem.live

enum class LiveState(val code: Int) {
    Idle(0),
    Listening(1),
    Thinking(2),
    Speaking(3);

    companion object {
        fun fromCode(code: Int): LiveState = when (code) {
            0 -> Idle
            1 -> Listening
            2 -> Thinking
            3 -> Speaking
            else -> Idle
        }
    }
}

fun interface LiveStateCallback {
    fun onStateChanged(oldState: Int, newState: Int)
}
