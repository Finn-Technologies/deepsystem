package app.finn.deepsystem.miniapps

import org.json.JSONObject

/**
 * Platform-independent, permission-gated JS bridge logic. All state is scoped to [appId].
 * Return values are JSON strings: {"ok":true,"value":...} or {"ok":false,"error":"..."}.
 */
class MiniAppBridgeCore(
    val appId: String,
    private val store: MiniAppKvStore,
    private val grants: () -> Set<MiniAppPermission>,
    private val clipboardWrite: (String) -> Unit,
    private val ask: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minAskIntervalMs: Long = 3000,
    private val maxAsksPerSession: Int = 20,
    private val gestureWindowMs: Long = 1500
) {
    @Volatile private var lastGestureAt = Long.MIN_VALUE / 2
    private var lastAskAt = Long.MIN_VALUE / 2
    private var askCount = 0
    private val lock = Any()

    fun noteUserGesture() { lastGestureAt = clock() }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()
    private fun ok(v: Any?) = JSONObject().put("ok", true).put("value", v ?: JSONObject.NULL).toString()
    private fun need(p: MiniAppPermission) = if (p in grants()) null else err("permission_denied:${p.name.lowercase()}")
    private fun validKey(k: String) = k.isNotEmpty() && k.length <= MAX_KEY && !k.contains('\u0000')

    private fun scoped(k: String) = "u:$k"

    fun storageGet(key: String): String {
        need(MiniAppPermission.STORAGE)?.let { return it }
        if (!validKey(key)) return err("bad_key")
        return ok(store.get(appId, scoped(key)))
    }

    fun storageSet(key: String, value: String): String {
        need(MiniAppPermission.STORAGE)?.let { return it }
        if (!validKey(key)) return err("bad_key")
        if (value.length > MAX_VALUE) return err("value_too_large")
        return if (store.set(appId, scoped(key), value)) ok(true) else err("write_failed")
    }

    fun storageRemove(key: String): String {
        need(MiniAppPermission.STORAGE)?.let { return it }
        if (!validKey(key)) return err("bad_key")
        store.delete(appId, scoped(key)); return ok(true)
    }

    fun clipboardWrite(text: String): String {
        need(MiniAppPermission.CLIPBOARD)?.let { return it }
        if (clock() - lastGestureAt > gestureWindowMs) return err("user_gesture_required")
        if (text.length > MAX_CLIP) return err("value_too_large")
        clipboardWrite.invoke(text); return ok(true)
    }

    /** Blocking; call off the UI thread. */
    fun askFinn(prompt: String): String {
        need(MiniAppPermission.ASK_FINN)?.let { return it }
        synchronized(lock) {
            val now = clock()
            if (askCount >= maxAsksPerSession) return err("rate_limited:session_quota")
            if (now - lastAskAt < minAskIntervalMs) return err("rate_limited:too_fast")
            lastAskAt = now; askCount++
        }
        return try { ok(ask(prompt.take(MAX_PROMPT))) } catch (t: Throwable) { err("ask_failed") }
    }

    companion object {
        const val MAX_KEY = 128
        const val MAX_VALUE = 64 * 1024
        const val MAX_CLIP = 16 * 1024
        const val MAX_PROMPT = 2000
        const val PERM_KEY = "__perm"

        /** JS shim exposing window.Finn on top of the raw bridge object "FinnNative". */
        val JS_SHIM = """
(function(){
  var pend={},n=0;
  function unwrap(s){var r=JSON.parse(s);if(!r.ok)throw new Error(r.error);return r.value;}
  window.__finnResolve=function(id,s){var p=pend[id];if(!p)return;delete pend[id];
    try{p.res(unwrap(s));}catch(e){p.rej(e);}};
  window.Finn={
    storage:{
      get:function(k){return Promise.resolve().then(function(){return unwrap(FinnNative.storageGet(String(k)));});},
      set:function(k,v){return Promise.resolve().then(function(){return unwrap(FinnNative.storageSet(String(k),String(v)));});},
      remove:function(k){return Promise.resolve().then(function(){return unwrap(FinnNative.storageRemove(String(k)));});}
    },
    clipboard:{write:function(t){return Promise.resolve().then(function(){return unwrap(FinnNative.clipboardWrite(String(t)));});}},
    ask:function(p){return new Promise(function(res,rej){var id=++n;pend[id]={res:res,rej:rej};FinnNative.askFinn(id,String(p));});}
  };
})();
""".trimIndent()
    }
}

enum class MiniAppPermission { STORAGE, CLIPBOARD, ASK_FINN;
    companion object {
        fun parse(s: String?): Set<MiniAppPermission> =
            s.orEmpty().split(",").mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.toSet()
        fun serialize(set: Set<MiniAppPermission>) = set.joinToString(",") { it.name }
    }
}
