package app.finn.deepsystem

import app.finn.deepsystem.miniapps.*
import app.finn.deepsystem.notes.NoteAi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniAppSecurityUnitTest {
    private class MemKv : MiniAppKvStore {
        val m = HashMap<String, String>()
        override fun get(appId: String, key: String) = m["$appId|$key"]
        override fun set(appId: String, key: String, value: String): Boolean { m["$appId|$key"] = value; return true }
        override fun delete(appId: String, key: String): Boolean { m.remove("$appId|$key"); return true }
    }

    private fun core(
        id: String, kv: MiniAppKvStore, perms: Set<MiniAppPermission>, now: () -> Long = { 0L },
        clip: MutableList<String> = mutableListOf()
    ) = MiniAppBridgeCore(id, kv, { perms }, { clip += it }, { "answer:$it" }, now)

    private fun JSONObject_(s: String) = JSONObject(s)

    @Test fun sanitizerExtractsFenceAndInjectsCsp() {
        val out = MiniAppSanitizer.sanitize("Sure!\n```html\n<html><head><title>T</title></head><body>hi</body></html>\n```\nenjoy")
        assertTrue(out.contains("Content-Security-Policy"))
        assertTrue(out.indexOf("Content-Security-Policy") < out.indexOf("<title>"))
        assertFalse(out.contains("Sure!"))
        assertEquals("T", MiniAppSanitizer.titleOf(out, "x"))
    }

    @Test fun sanitizerStripsNetworkVectors() {
        val evil = """<html><head><meta http-equiv="refresh" content="0;url=http://evil"><link rel=stylesheet href="http://evil/x.css"><base href="http://evil/"></head>
<body><iframe src="http://evil"></iframe><script src="http://evil/a.js"></script><img src="https://evil/p.png"><a href="http://evil">x</a>
<form action="http://evil"><div style="background:url(http://evil/b.png)"></div><style>@import url(http://evil/c.css);</style><img src="data:image/png;base64,AAAA"></body></html>"""
        val out = MiniAppSanitizer.sanitize(evil)
        assertFalse(out, out.contains("evil"))
        assertTrue(out.contains("data:image/png"))
        assertEquals(1, Regex("Content-Security-Policy").findAll(out).count())
        assertFalse(out.contains("refresh"))
    }

    @Test fun sanitizerCapsSize() {
        val out = MiniAppSanitizer.sanitize("<html>" + "a".repeat(MiniAppSanitizer.MAX_BYTES * 2))
        assertTrue(out.length < MiniAppSanitizer.MAX_BYTES + 1000)
    }

    @Test fun storageIsScopedPerApp() {
        val kv = MemKv()
        val a = core("A", kv, setOf(MiniAppPermission.STORAGE))
        val b = core("B", kv, setOf(MiniAppPermission.STORAGE))
        a.storageSet("secret", "a-data")
        assertTrue(JSONObject_(b.storageGet("secret")).isNull("value"))
        assertTrue(JSONObject_(b.storageGet("A|u:secret")).isNull("value"))
        assertEquals("a-data", JSONObject_(a.storageGet("secret")).getString("value"))
    }

    @Test fun appCannotForgeOrReadPermissionKey() {
        val kv = MemKv()
        val a = core("A", kv, setOf(MiniAppPermission.STORAGE))
        a.storageSet(MiniAppBridgeCore.PERM_KEY, "STORAGE,CLIPBOARD,ASK_FINN")
        assertNull(kv.get("A", MiniAppBridgeCore.PERM_KEY))
        assertTrue(JSONObject_(a.storageGet(MiniAppBridgeCore.PERM_KEY)).getString("value").contains("CLIPBOARD"))
        assertFalse(JSONObject_(a.askFinn("hi")).getBoolean("ok"))
    }

    @Test fun permissionsGateEverything() {
        val c = core("A", MemKv(), emptySet())
        for (r in listOf(c.storageGet("k"), c.storageSet("k", "v"), c.clipboardWrite("x"), c.askFinn("q")))
            assertTrue(JSONObject_(r).getString("error").startsWith("permission_denied"))
    }

    @Test fun clipboardNeedsGesture() {
        var t = 10_000L
        val clip = mutableListOf<String>()
        val c = core("A", MemKv(), setOf(MiniAppPermission.CLIPBOARD), { t }, clip)
        assertEquals("user_gesture_required", JSONObject_(c.clipboardWrite("x")).getString("error"))
        c.noteUserGesture()
        assertTrue(JSONObject_(c.clipboardWrite("x")).getBoolean("ok"))
        t += 5000
        assertFalse(JSONObject_(c.clipboardWrite("y")).getBoolean("ok"))
        assertEquals(listOf("x"), clip)
    }

    @Test fun askIsRateLimited() {
        var t = 100_000L
        val c = core("A", MemKv(), setOf(MiniAppPermission.ASK_FINN), { t })
        assertTrue(JSONObject_(c.askFinn("1")).getBoolean("ok"))
        assertTrue(JSONObject_(c.askFinn("2")).getString("error").startsWith("rate_limited"))
        var okCount = 1
        repeat(40) { t += 4000; if (JSONObject_(c.askFinn("x")).getBoolean("ok")) okCount++ }
        assertEquals(20, okCount)
    }

    @Test fun inputLimits() {
        val c = core("A", MemKv(), setOf(MiniAppPermission.STORAGE))
        assertEquals("value_too_large", JSONObject_(c.storageSet("k", "x".repeat(MiniAppBridgeCore.MAX_VALUE + 1))).getString("error"))
        assertEquals("bad_key", JSONObject_(c.storageGet("")).getString("error"))
    }

    @Test fun webViewUrlPolicy() {
        val origin = miniAppOrigin("abc")
        assertTrue(MiniAppWebViewFactory.isAllowedUrl(origin, "abc"))
        assertFalse(MiniAppWebViewFactory.isAllowedUrl("https://example.com/", "abc"))
        assertFalse(MiniAppWebViewFactory.isAllowedUrl("file:///data/data/x", "abc"))
        assertFalse(MiniAppWebViewFactory.isAllowedUrl("content://x/y", "abc"))
        assertFalse(MiniAppWebViewFactory.isAllowedUrl(miniAppOrigin("other"), "abc"))
    }

    @Test fun promptsAndOrganizeParsing() {
        assertTrue(MiniAppPrompts.generate("tip calc").contains("tip calc"))
        assertTrue(MiniAppPrompts.regenerate("a", "<p>", "bigger").contains("bigger"))
        val (t, tags) = NoteAi.parseOrganize("TITLE: \"Trip plan\"\nTAGS: #Travel, plans, x")
        assertEquals("Trip plan", t); assertEquals(listOf("travel", "plans", "x"), tags)
        assertTrue(NoteAi.ASR_PROMPT.contains("ASR"))
    }
}
