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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

data class BrowserViewState(
    val hasContent: Boolean = false,
    val loading: Boolean = false,
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
    fun changed(view: BrowserViewState, url: String? = null, title: String? = null, completed: Boolean = false)
    fun progress(value: Int)
    fun download(url: String, name: String?, mime: String?)
    fun chooser(request: BrowserFileChooser)
    fun dialog(request: BrowserDialog)
    fun permission(request: BrowserPermission)
    fun popup(message: Message): Boolean
    fun close()
    fun resource(request: WebResourceRequest): WebResourceResponse?
    fun gesture(value: JsonObject)
}

/** Native WebView with completion callbacks for page scripts and visual pointer positions. */
@SuppressLint("SetJavaScriptEnabled")
internal class BrowserEngine(
    context: Context,
    private val tabId: String,
    private val workspaceHost: String,
    settings: BrowserSettings,
    private val asyncScript: String,
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
    private var pendingScript: Pair<String, CompletableDeferred<String>>? = null
    private val navigation = BrowserNavigation()
    private var requestedUrl: String? = null
    private var renderedHtml = false
    val awaitingDocument get() = navigation.pending

    init {
        webView.addJavascriptInterface(object {
            @androidx.annotation.Keep
            @android.webkit.JavascriptInterface
            fun complete(id: String, encoded: String) {
                webView.post { pendingScript?.takeIf { it.first == id }?.second?.complete(encoded) }
            }

            @androidx.annotation.Keep
            @android.webkit.JavascriptInterface
            fun showGesture(encoded: String) {
                if (encoded.length > 2048) return
                val value = runCatching { JsonInstant.parseToJsonElement(encoded) as? JsonObject }.getOrNull() ?: return
                webView.post { if (!disposed) events.gesture(value) }
            }
        }, "__rikkaBrowser")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
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
                if (url != null && !url.startsWith("data:") && url != "about:blank") renderedHtml = false
                navigation.started()
                requestedUrl = url
                cancelScript("browser_document_changed: 网页已切换，请读取当前页面")
                events.progress(0)
                publish(historyState().copy(loading = true, error = null, hasContent = true), url = pageUrl(url), title = "")
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (navigation.commit(pageUrl(url), pageUrl(view.url))) publish(historyState(), url = pageUrl(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!navigation.commit(pageUrl(url), pageUrl(view.url))) return
                events.progress(100)
                publish(historyState().copy(loading = false), url = pageUrl(url), title = view.title, completed = true)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = publish(historyState(), url = pageUrl(url))

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    failPending(error.description.toString())
                    publish(viewState.copy(loading = false, error = error.description.toString()))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (error.url == requestedUrl || error.url == view.url) {
                    failPending("网站证书验证失败")
                    publish(viewState.copy(loading = false, error = "网站证书验证失败"))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (browserHandlesNavigation(request.url.toString(), request.isForMainFrame)) return false
                if (request.hasGesture()) {
                    runCatching {
                        contextWrapper.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                if (request.url.host == workspaceHost) events.resource(request) else null

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                failPending("网页进程已退出，请重新加载")
                publish(viewState.copy(loading = false, crashed = true, error = "网页进程已退出，请重新加载"))
                disposed = true
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
                contextWrapper.baseContext = contextWrapper.applicationContext
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) = events.progress(progress)
            override fun onReceivedTitle(view: WebView, title: String?) = publish(viewState, title = title)
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

    private fun historyState() = viewState.copy(back = webView.canGoBack(), forward = webView.canGoForward())

    private fun publish(value: BrowserViewState, url: String? = null, title: String? = null, completed: Boolean = false) {
        if (value == viewState && url == null && title == null && !completed) return
        viewState = value
        events.changed(value, url, title, completed)
    }

    fun resize(width: Int, height: Int) {
        if (webView.parent != null || disposed) return
        webView.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    fun applySettings(settings: BrowserSettings) {
        webView.settings.apply {
            val agent = settings.customUserAgent.trim().takeIf { it.isNotEmpty() } ?: if (settings.desktopMode) {
                defaultUserAgent.replace(Regex("\\(Linux; Android[^)]+\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
            } else defaultUserAgent
            if (userAgentString != agent) userAgentString = agent
        }
    }

    /** BrowserManager serializes actions, so each engine needs only one pending result. */
    suspend fun evaluate(script: String): JsonElement {
        check(!disposed) { "网页进程已退出，请重新加载" }
        val id = Uuid.random().toString()
        val result = CompletableDeferred<String>()
        pendingScript = id to result
        val encoded = try {
            withTimeoutOrNull(15_000) {
                webView.evaluateJavascript(asyncScript.replace("__RIKKA_ID__", JsonPrimitive(id).toString()).replace("__RIKKA_BODY__", script), null)
                result.await()
            } ?: error("browser_script_timeout: 网页脚本等待超时；操作可能已完成，请先读取页面结果")
        } finally { pendingScript = null }
        return withContext(Dispatchers.Default) {
            val response = JsonInstant.parseToJsonElement(encoded).jsonObject
            if (response["ok"] == JsonPrimitive(false)) error(response["error"]?.jsonPrimitive?.contentOrNull ?: "browser_script_failed")
            response["value"] ?: JsonNull
        }
    }

    fun loadUrl(url: String) {
        renderedHtml = false
        if (!sameDocument(url)) beginNavigation(url)
        webView.loadUrl(url)
    }

    fun loadHtml(html: String) {
        renderedHtml = true
        beginNavigation("about:blank")
        webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", "about:blank")
    }

    private fun pageUrl(url: String?): String? =
        if (renderedHtml && (url == "about:blank" || url?.startsWith("data:") == true)) "about:blank" else url

    fun back(forward: Boolean) {
        if (if (forward) webView.canGoForward() else webView.canGoBack()) {
            val history = webView.copyBackForwardList()
            val target = history.getItemAtIndex(history.currentIndex + if (forward) 1 else -1)?.url
            if (!sameDocument(target)) beginNavigation(target)
            if (forward) webView.goForward() else webView.goBack()
        }
    }

    fun reload(stop: Boolean) {
        if (stop) {
            webView.stopLoading()
            failPending("browser_navigation_stopped")
            publish(viewState.copy(loading = false))
        } else { beginNavigation(webView.url); webView.reload() }
    }

    suspend fun waitForDom() {
        val page = navigation.document
        withTimeoutOrNull(15_000) {
            page.await()
            check(navigation.document === page) { "browser_navigation_superseded" }
            // Kelivo browser_native_engine.dart::waitForLoad, DOMContentLoaded branch.
            evaluate("""
                if (document.readyState === 'loading') {
                    await new Promise(resolve => document.addEventListener('DOMContentLoaded', resolve, {once:true}));
                }
                return true;
            """.trimIndent())
            check(navigation.document === page) { "browser_navigation_superseded" }
        } ?: error("browser_load_timeout: 网页尚未就绪，请先读取当前页面")
    }

    private fun sameDocument(url: String?) = webView.url?.let {
        url != null && it != url && it.substringBefore('#') == url.substringBefore('#')
    } == true

    private fun beginNavigation(url: String?) {
        cancelScript("browser_navigation_superseded")
        navigation.begin()
        requestedUrl = url
        events.progress(0)
        publish(viewState.copy(loading = true, error = null, hasContent = true))
    }

    private fun failPending(reason: String) {
        cancelScript(reason)
        navigation.fail(reason)
    }

    private fun cancelScript(reason: String) = pendingScript?.second?.completeExceptionally(IllegalStateException(reason))

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
        failPending("browser_closed: 浏览器标签已关闭")
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("__rikkaBrowser")
        webView.webChromeClient = null
        webView.destroy()
        contextWrapper.baseContext = contextWrapper.applicationContext
    }
}
