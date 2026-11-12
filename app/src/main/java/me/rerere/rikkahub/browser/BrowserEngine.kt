package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

data class BrowserViewState(
    val hasContent: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val back: Boolean = false,
    val forward: Boolean = false,
    val error: String? = null,
    val crashed: Boolean = false,
)

data class BrowserFileChooser(
    val tabId: String,
    val intent: Intent,
    val callback: ValueCallback<Array<Uri>>,
    val fromAgent: Boolean = false,
)
data class BrowserDialog(val tabId: String, val message: String, val defaultValue: String?, val result: JsResult)
data class BrowserPermission(val tabId: String, val origin: String, val request: PermissionRequest)

internal interface BrowserEngineEvents {
    fun changed(url: String?, title: String?, view: BrowserViewState)
    fun download(url: String, name: String?, mime: String?)
    fun chooser(request: BrowserFileChooser)
    fun dialog(request: BrowserDialog)
    fun permission(request: BrowserPermission)
    fun popup(message: Message): Boolean
    fun close()
    fun resource(request: WebResourceRequest): WebResourceResponse?
    fun gesture(value: JsonObject)
}

/** Native Android WebView. The page callback only reports visual pointer positions. */
@SuppressLint("SetJavaScriptEnabled")
internal class BrowserEngine(
    context: Context,
    private val tabId: String,
    settings: BrowserSettings,
    private val events: BrowserEngineEvents,
) {
    val contextWrapper = MutableContextWrapper(context.applicationContext)
    val webView = WebView(contextWrapper)
    val console = ArrayDeque<String>()
    var viewState = BrowserViewState()
        private set
    var disposed = false
        private set
    private val defaultUserAgent = webView.settings.userAgentString

    init {
        webView.addJavascriptInterface(object {
            @androidx.annotation.Keep
            @android.webkit.JavascriptInterface
            fun showGesture(encoded: String) {
                if (encoded.length > 2048) return
                val value = runCatching { JsonInstant.parseToJsonElement(encoded) as? JsonObject }.getOrNull() ?: return
                webView.post { if (!disposed) events.gesture(value) }
            }
        }, "__rikkaBrowserFeedback")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
        }
        applySettings(settings)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                publish(viewState.copy(loading = true, error = null, hasContent = true))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                publish(viewState.copy(loading = false, progress = 100))
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = publish(viewState)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) publish(viewState.copy(loading = false, error = error.description.toString()))
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                publish(viewState.copy(loading = false, error = "网站证书验证失败"))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (scheme in setOf("http", "https") || request.url.toString() == "about:blank") return false
                if (request.hasGesture()) {
                    runCatching {
                        contextWrapper.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = events.resource(request)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                publish(viewState.copy(loading = false, crashed = true, error = "网页进程已退出，请重新加载"))
                disposed = true
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
                contextWrapper.baseContext = contextWrapper.applicationContext
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) = publish(viewState.copy(progress = progress))
            override fun onReceivedTitle(view: WebView, title: String?) = publish(viewState)
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (console.size >= 100) console.removeFirst()
                console.addLast("${message.messageLevel()}: ${message.message()}")
                return true
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                return runCatching { events.chooser(BrowserFileChooser(tabId, params.createIntent(), callback)); true }
                    .getOrElse { callback.onReceiveValue(null); false }
            }

            override fun onJsAlert(view: WebView, url: String?, message: String, result: JsResult): Boolean {
                events.dialog(BrowserDialog(tabId, message, null, result)); return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String, result: JsResult): Boolean {
                events.dialog(BrowserDialog(tabId, message, null, result)); return true
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                events.dialog(BrowserDialog(tabId, message, defaultValue ?: "", result)); return true
            }

            override fun onPermissionRequest(request: PermissionRequest) =
                events.permission(BrowserPermission(tabId, request.origin.toString(), request))

            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, message: Message) = events.popup(message)
            override fun onCloseWindow(window: WebView) = events.close()
        }
        webView.setDownloadListener { url, _, disposition, mime, _ ->
            events.download(url, android.webkit.URLUtil.guessFileName(url, disposition, mime), mime)
        }
        val metrics = context.resources.displayMetrics
        resize(metrics.widthPixels, metrics.heightPixels)
        webView.onResume()
    }

    private fun publish(value: BrowserViewState) {
        viewState = value.copy(back = webView.canGoBack(), forward = webView.canGoForward())
        events.changed(webView.url, webView.title, viewState)
    }

    fun resize(width: Int, height: Int) {
        if (webView.parent != null || disposed) return
        webView.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    fun applySettings(settings: BrowserSettings) {
        webView.settings.apply {
            useWideViewPort = settings.desktopMode
            loadWithOverviewMode = settings.desktopMode
            userAgentString = settings.customUserAgent.takeIf { it.isNotBlank() } ?: if (settings.desktopMode) {
                defaultUserAgent.replace(Regex("\\(Linux; Android[^)]+\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
            } else defaultUserAgent
        }
    }

    private suspend fun evaluateOnce(script: String): kotlinx.serialization.json.JsonElement =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { raw ->
                if (continuation.isActive) continuation.resume(runCatching { JsonInstant.parseToJsonElement(raw ?: "null") }.getOrDefault(JsonNull))
            }
        }

    /** Await page promises using page-local result slots, without exposing an Android JavascriptInterface. */
    suspend fun evaluate(script: String): kotlinx.serialization.json.JsonElement {
        check(!disposed) { "网页进程已退出，请重新加载" }
        val token = JsonPrimitive(Uuid.random().toString()).toString()
        val slot = "window.__rikkaBrowserCalls[$token]"
        return try {
            withTimeoutOrNull(15_000) {
                evaluateOnce("""(() => { window.__rikkaBrowserCalls ??= {}; $slot = {done:false}; (async () => { $script })().then(value => { $slot = {done:true,value:value ?? null}; }, error => { $slot = {done:true,error:String(error.message || error)}; }); return true; })()""")
                while (true) {
                    val value = evaluateOnce("(() => { const value = $slot; return value?.done ? value : null; })()")
                    if (value is JsonObject) {
                        value["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
                        return@withTimeoutOrNull value["value"] ?: JsonNull
                    }
                    delay(60)
                }
                @Suppress("UNREACHABLE_CODE")
                JsonNull
            } ?: error("browser_script_timeout: 网页脚本等待超时；操作可能已完成，请先读取页面结果")
        } finally {
            if (!disposed) webView.evaluateJavascript("if (window.__rikkaBrowserCalls) delete $slot", null)
        }
    }

    suspend fun screenshot(): Pair<ByteArray, Pair<Int, Int>> {
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val scale = minOf(1.0, 1568.0 / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            webView.draw(canvas)
            withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray() to (bitmap.width to bitmap.height)
                }
            }
        } finally { bitmap.recycle() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("__rikkaBrowserFeedback")
        webView.webChromeClient = null
        webView.destroy()
        contextWrapper.baseContext = contextWrapper.applicationContext
    }
}
