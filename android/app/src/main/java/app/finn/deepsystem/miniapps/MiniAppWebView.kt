package app.finn.deepsystem.miniapps

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.webkit.*
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Origin used for the mini app document; never resolvable on the network. */
fun miniAppOrigin(appId: String) = "https://app-${appId.filter { it.isLetterOrDigit() }.lowercase()}.miniapp.invalid/"

/** Raw JS object injected as "FinnNative". Thin adapter over [MiniAppBridgeCore]. */
class MiniAppJsBridge(
    private val core: MiniAppBridgeCore,
    private val postJs: (String) -> Unit
) {
    private val pool = Executors.newSingleThreadExecutor()

    @JavascriptInterface fun storageGet(key: String) = core.storageGet(key)
    @JavascriptInterface fun storageSet(key: String, value: String) = core.storageSet(key, value)
    @JavascriptInterface fun storageRemove(key: String) = core.storageRemove(key)
    @JavascriptInterface fun clipboardWrite(text: String) = core.clipboardWrite(text)
    @JavascriptInterface fun askFinn(id: Int, prompt: String) {
        pool.execute {
            val r = core.askFinn(prompt)
            postJs("window.__finnResolve($id,${org.json.JSONObject.quote(r)})")
        }
    }
    fun shutdown() = pool.shutdownNow()
}

object MiniAppWebViewFactory {
    private val EMPTY_BLOCK = { WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))) }

    /** Pure decision used by shouldInterceptRequest; exposed for tests. */
    fun isAllowedUrl(url: String, appId: String): Boolean =
        url == miniAppOrigin(appId) || url.startsWith("data:") || url.startsWith("blob:") || url == "about:blank"

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun create(
        context: Context,
        app: MiniApp,
        core: MiniAppBridgeCore,
        onBlocked: (String) -> Unit = {}
    ): WebView {
        val wv = WebView(context)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION") run {
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
        }
        s.domStorageEnabled = false
        @Suppress("DEPRECATION") run { s.databaseEnabled = false }
        s.setGeolocationEnabled(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.blockNetworkLoads = true
        s.blockNetworkImage = true
        s.mediaPlaybackRequiresUserGesture = true
        s.saveFormData = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        wv.setDownloadListener(null)
        wv.isLongClickable = false

        val main = Handler(Looper.getMainLooper())
        val bridge = MiniAppJsBridge(core) { js -> main.post { wv.evaluateJavascript(js, null) } }
        wv.addJavascriptInterface(bridge, "FinnNative")
        wv.setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_UP) core.noteUserGesture(); false }
        wv.setTag(bridge)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView?, d: Boolean, g: Boolean, m: android.os.Message?) = false
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(o: String?, c: GeolocationPermissions.Callback?) { c?.invoke(o, false, false) }
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<android.net.Uri>>?, p: FileChooserParams?): Boolean { cb?.onReceiveValue(null); return true }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isAllowedUrl(url, app.id)) return null
                onBlocked(url)
                return EMPTY_BLOCK()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                onBlocked(request.url.toString()); return true
            }
        }
        wv.loadDataWithBaseURL(miniAppOrigin(app.id), withShim(app.html), "text/html", "utf-8", null)
        return wv
    }

    /** Inserts the Finn JS shim as the first script so it runs before app code. */
    fun withShim(html: String): String {
        val tag = "<script>${MiniAppBridgeCore.JS_SHIM}</script>"
        val i = html.indexOf("</head>", ignoreCase = true)
        return if (i >= 0) html.substring(0, i) + tag + html.substring(i) else tag + html
    }

    fun destroy(wv: WebView) {
        (wv.tag as? MiniAppJsBridge)?.shutdown()
        wv.removeJavascriptInterface("FinnNative")
        wv.stopLoading(); wv.destroy()
    }
}

@Composable
fun MiniAppWebView(app: MiniApp, core: MiniAppBridgeCore, modifier: Modifier = Modifier) {
    androidx.compose.runtime.key(app.id, app.updatedAt, app.html.hashCode()) {
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose {} }
        AndroidView(
            modifier = modifier,
            factory = { ctx -> MiniAppWebViewFactory.create(ctx, app, core) },
            onRelease = { MiniAppWebViewFactory.destroy(it) }
        )
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Finn mini app", text))
}
