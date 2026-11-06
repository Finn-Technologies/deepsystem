package app.finn.deepsystem.miniapps

import android.util.Log
import app.finn.deepsystem.engine.NativeFinnBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Per-app scoped key/value storage. Every operation is bound to one appId. */
interface MiniAppKvStore {
    fun get(appId: String, key: String): String?
    fun set(appId: String, key: String, value: String): Boolean
    fun delete(appId: String, key: String): Boolean
}

interface MiniAppRepository : MiniAppKvStore {
    fun init(dbPath: String): Boolean
    fun create(app: MiniApp): Boolean
    fun update(app: MiniApp): Boolean
    fun delete(id: String): Boolean
    fun get(id: String): MiniApp?
    fun list(): List<MiniApp>
    fun close()
}

class NativeMiniAppRepository : MiniAppRepository {
    private val lock = Any()
    private var handle = 0L
    private var fallback = false
    private val apps = ConcurrentHashMap<String, MiniApp>()
    private val kv = ConcurrentHashMap<String, String>()

    init {
        if (NativeFinnBridge.isNativeLoaded()) {
            try { handle = NativeFinnBridge.nativeStorageCreate() } catch (t: Throwable) { fallback = true }
        } else fallback = true
    }

    private fun useFallback() = fallback || handle == 0L

    override fun init(dbPath: String): Boolean = synchronized(lock) {
        if (useFallback()) { fallback = true; return true }
        if (NativeFinnBridge.nativeStorageOpen(handle, dbPath) != 0) {
            Log.w(TAG, "native open failed, in-memory fallback"); fallback = true
        }
        true
    }

    override fun create(app: MiniApp) = synchronized(lock) {
        if (useFallback()) { apps[app.id] = app; true }
        else NativeFinnBridge.nativeStorageCreateMiniApp(handle, app.id, app.title, app.description, app.html, app.prompt, app.isPinned) == 0
    }

    override fun update(app: MiniApp) = synchronized(lock) {
        if (useFallback()) { apps[app.id] = app.copy(updatedAt = System.currentTimeMillis()); true }
        else NativeFinnBridge.nativeStorageUpdateMiniApp(handle, app.id, app.title, app.description, app.html, app.prompt, app.isPinned) == 0
    }

    override fun delete(id: String) = synchronized(lock) {
        if (useFallback()) {
            apps.remove(id); kv.keys.removeAll { it.startsWith("$id\u0000") }; true
        } else NativeFinnBridge.nativeStorageDeleteMiniApp(handle, id) == 0
    }

    override fun get(id: String): MiniApp? = synchronized(lock) {
        if (useFallback()) apps[id]
        else NativeFinnBridge.nativeStorageGetMiniAppJson(handle, id)?.let { parse(JSONObject(it)) }
    }

    override fun list(): List<MiniApp> = synchronized(lock) {
        val l = if (useFallback()) apps.values.toList() else {
            val arr = JSONArray(NativeFinnBridge.nativeStorageListMiniAppsJson(handle) ?: "[]")
            (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
        }
        l.sortedWith(compareByDescending<MiniApp> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    override fun get(appId: String, key: String): String? = synchronized(lock) {
        if (useFallback()) kv["$appId\u0000$key"] else NativeFinnBridge.nativeStorageGetMiniAppKv(handle, appId, key)
    }

    override fun set(appId: String, key: String, value: String) = synchronized(lock) {
        if (useFallback()) { kv["$appId\u0000$key"] = value; true }
        else NativeFinnBridge.nativeStorageSetMiniAppKv(handle, appId, key, value) == 0
    }

    override fun delete(appId: String, key: String) = synchronized(lock) {
        if (useFallback()) { kv.remove("$appId\u0000$key"); true }
        else NativeFinnBridge.nativeStorageDeleteMiniAppKv(handle, appId, key) == 0
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) { try { NativeFinnBridge.nativeStorageDestroy(handle) } catch (_: Throwable) {}; handle = 0 }
    }

    private fun parse(o: JSONObject): MiniApp? {
        val id = o.optString("id"); if (id.isEmpty()) return null
        return MiniApp(id, o.optString("title"), o.optString("description"), o.optString("html_content"),
            o.optString("prompt"), o.optBoolean("is_pinned"), o.optLong("created_at"), o.optLong("updated_at"))
    }

    companion object { private const val TAG = "MiniAppRepo" }
}
