package app.finn.deepsystem.miniapps

/**
 * Sanitises model-generated HTML. This is defence-in-depth: the real enforcement is the WebView
 * lock-down (request interception + CSP + no file/content access).
 */
object MiniAppSanitizer {
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; " +
        "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'; worker-src 'none'"
    const val MAX_BYTES = 256 * 1024

    private val fence = Regex("```(?:html|HTML)?\\s*\\n([\\s\\S]*?)```")
    private val htmlStart = Regex("<!doctype html|<html", RegexOption.IGNORE_CASE)
    private val dangerousTags = Regex(
        "<\\s*(iframe|frame|frameset|object|embed|applet|base|link|meta|portal)\\b[^>]*>(\\s*</\\s*\\1\\s*>)?",
        RegexOption.IGNORE_CASE
    )
    private val externalScript = Regex("<\\s*script\\b[^>]*\\bsrc\\s*=[^>]*>\\s*</\\s*script\\s*>", RegexOption.IGNORE_CASE)
    private val remoteAttr = Regex("""\s(src|href|action|formaction|poster|srcset|data|xlink:href)\s*=\s*(["']?)\s*(?!data:|blob:|#)[^"'\s>]*""", RegexOption.IGNORE_CASE)
    private val cssUrl = Regex("""url\(\s*["']?\s*(?!data:|blob:|#)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val cssImport = Regex("""@import[^;]*;?""", RegexOption.IGNORE_CASE)
    private val inlineHandlerUrl = Regex("""javascript\s*:""", RegexOption.IGNORE_CASE)

    /** Pulls an HTML document out of a model reply (fenced block or raw). */
    fun extractHtml(reply: String): String {
        fence.find(reply)?.let { return it.groupValues[1].trim() }
        val m = htmlStart.find(reply)
        return (if (m != null) reply.substring(m.range.first) else reply).trim().removeSuffix("```").trim()
    }

    fun sanitize(raw: String): String {
        var h = extractHtml(raw).take(MAX_BYTES)
        h = externalScript.replace(h, "")
        h = dangerousTags.replace(h, "")
        h = remoteAttr.replace(h, " data-blocked=\"\"")
        h = cssUrl.replace(h, "none")
        h = cssImport.replace(h, "")
        h = inlineHandlerUrl.replace(h, "blocked:")
        // Strip any pre-existing html/head wrapper then rebuild with our CSP first.
        val body = h.replace(Regex("(?is)<!doctype[^>]*>"), "")
            .replace(Regex("(?is)</?html[^>]*>"), "")
            .replace(Regex("(?is)</?head[^>]*>"), "")
            .replace(Regex("(?is)</?body[^>]*>"), "")
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "</head><body>$body</body></html>"
    }

    fun looksLikeHtml(reply: String): Boolean = extractHtml(reply).contains("<", ignoreCase = true)

    fun titleOf(html: String, fallback: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()?.take(60)?.ifBlank { null } ?: fallback
}
