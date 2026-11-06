package app.finn.deepsystem.miniapps

/** Generation prompt templates for mini apps. */
object MiniAppPrompts {
    private const val RULES = """Rules:
- Output ONE complete HTML document (inline <style> and <script> only) inside a single ```html code block, no commentary.
- No network access: no fetch/XHR/WebSocket, no external scripts, fonts, images or links. Use only inline CSS/JS and data: URIs.
- Mobile-first, touch friendly, dark-mode aware via prefers-color-scheme, set a <title>.
- Optional JS bridge (already injected as window.Finn): Finn.storage.get(key)->Promise<string|null>, Finn.storage.set(key,value), Finn.storage.remove(key), Finn.clipboard.write(text) (call from a tap handler), Finn.ask(prompt)->Promise<string> (rate limited, use sparingly).
- Persist user data with Finn.storage, never localStorage or cookies."""

    fun generate(request: String): String =
        "<|im_start|>system\nYou are Finn, an expert front-end developer building tiny single-file mini apps.\n$RULES<|im_end|>\n" +
            "<|im_start|>user\nBuild this mini app: ${request.trim().take(1000)}<|im_end|>\n<|im_start|>assistant\n"

    fun regenerate(request: String, previousHtml: String, feedback: String): String =
        "<|im_start|>system\nYou are Finn, an expert front-end developer building tiny single-file mini apps.\n$RULES<|im_end|>\n" +
            "<|im_start|>user\nOriginal request: ${request.trim().take(500)}\n\nCurrent app:\n```html\n${previousHtml.take(12000)}\n```\n\n" +
            "Change request: ${feedback.trim().take(500)}\nReturn the full updated document.<|im_end|>\n<|im_start|>assistant\n"
}
