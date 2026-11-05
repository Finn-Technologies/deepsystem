package app.finn.deepsystem.engine

fun interface TokenCallback {
    /**
     * Called for each generated token.
     * Return true to continue generation, false to cancel.
     */
    fun onToken(token: String): Boolean
}
