package me.rerere.rikkahub.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

interface BrowserController {
    suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject): List<UIMessagePart>
}

data class BrowserDownloadView(val record: BrowserDownload, val status: Int, val received: Long, val total: Long)

private val navigationActions = setOf("open", "new_tab", "back", "forward", "reload", "render")

/** Owns native views independently of Compose routes. Tab ownership and live-view budgets follow Kelivo. */
class BrowserManager(
    private val context: Context,
    private val scope: AppScope,
    private val files: FilesManager,
    private val workspaces: WorkspaceRepository,
) : BrowserController {
    private val store = BrowserStore(File(context.filesDir, "browser"))
    private val initialization = Mutex()
    private val actions = Mutex()
    private var initialized = false
    private lateinit var asyncScript: String
    private val mutableState = MutableStateFlow(BrowserState())
    val state = mutableState.asStateFlow()
    private val mutableViews = MutableStateFlow<Map<String, BrowserViewState>>(emptyMap())
    val views = mutableViews.asStateFlow()
    private val mutableChooser = MutableStateFlow<BrowserFileChooser?>(null)
    val chooser = mutableChooser.asStateFlow()
    private val mutableDialog = MutableStateFlow<BrowserDialog?>(null)
    val dialog = mutableDialog.asStateFlow()
    private val mutablePermission = MutableStateFlow<BrowserPermission?>(null)
    val permission = mutablePermission.asStateFlow()
    private val mutableRunning = MutableStateFlow<String?>(null)
    val running = mutableRunning.asStateFlow()
    private val mutableActivity = MutableStateFlow<BrowserActivity?>(null)
    val activity = mutableActivity.asStateFlow()
    private val mutableGestures = MutableSharedFlow<BrowserGesture>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val gestures = mutableGestures.asSharedFlow()
    private var gestureSequence = 0L
    private val mutablePaused = MutableStateFlow<Set<String>>(emptySet())
    val paused = mutablePaused.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val engines = LinkedHashMap<String, BrowserEngine>(4, .75f, true)
    private val visible = mutableSetOf<String>()
    private var busyTab: String? = null
    private var saveJob: Job? = null
    private var idleJob: Job? = null
    private var automation: String? = null
    private val downloads get() = context.getSystemService(DownloadManager::class.java)

    suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            val saved = store.read()
            asyncScript = withContext(Dispatchers.IO) { context.assets.open("browser/async.js").bufferedReader().use { it.readText() } }
            withContext(Dispatchers.Main.immediate) { mutableState.value = saved }
            initialized = true
        }
    }

    private fun update(value: BrowserState) {
        if (value == state.value) return
        mutableState.value = value
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.Main.immediate) {
            delay(300)
            try { store.write(state.value); mutableError.value = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = "浏览器状态保存失败：${failure.message}" }
        }
    }

    suspend fun newTab(owner: String, url: String = "about:blank", workspaceId: String? = null): BrowserTab {
        initialize()
        return withContext(Dispatchers.Main.immediate) {
            val tab = BrowserTab(Uuid.random().toString(), owner, browserAddress(url, allowSearch = false), workspaceId = workspaceId)
            update(state.value.copy(tabs = state.value.tabs + tab, selected = state.value.selected + (owner to tab.id)))
            tab
        }
    }

    suspend fun select(owner: String, id: String) {
        initialize()
        withContext(Dispatchers.Main.immediate) {
            state.value.requireOwned(owner, id)
            update(state.value.copy(selected = state.value.selected + (owner to id)))
        }
    }

    suspend fun navigate(owner: String, input: String, workspaceId: String? = null, tabId: String? = null) {
        initialize()
        withContext(Dispatchers.Main.immediate) {
            val url = browserAddress(input, state.value.settings.searchEngine)
            val tab = tabId?.let { state.value.requireOwned(owner, it) } ?: state.value.selectedTab(owner)
                ?: newTab(owner, workspaceId = workspaceId)
            val updated = tab.copy(url = url, workspaceId = workspaceId ?: tab.workspaceId)
            update(state.value.copy(tabs = state.value.tabs.map { if (it.id == tab.id) updated else it }))
            engine(updated, load = false).loadUrl(nativeUrl(updated, url))
        }
    }

    suspend fun close(owner: String, id: String) {
        initialize()
        withContext(Dispatchers.Main.immediate) {
            state.value.requireOwned(owner, id)
            require(id != busyTab) { "网页操作进行中，请稍后关闭" }
            resolveRequests(id)
            visible.remove(id)
            engines.remove(id)?.dispose()
            mutableViews.value = views.value - id
            update(state.value.removeTab(id))
        }
    }

    suspend fun show(owner: String, id: String, hostContext: Context): WebView {
        initialize()
        return withContext(Dispatchers.Main.immediate) {
            idleJob?.cancel()
            val tab = state.value.requireOwned(owner, id)
            val engine = engine(tab)
            engine.contextWrapper.baseContext = hostContext
            visible.add(id)
            engine.webView.onResume()
            engine.webView
        }
    }

    fun hide(id: String) {
        visible.remove(id)
        engines[id]?.let {
            it.contextWrapper.baseContext = context.applicationContext
            if (id != busyTab && !it.disposed) it.webView.onPause()
        }
        scheduleIdleRelease()
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        if (visible.isNotEmpty() || busyTab != null) return
        idleJob = scope.launch(Dispatchers.Main.immediate) {
            delay(30 * 60_000L)
            if (visible.isEmpty() && busyTab == null) {
                engines.keys.toList().forEach(::resolveRequests)
                engines.values.forEach { it.dispose() }
                engines.clear()
                mutableViews.value = emptyMap()
            }
        }
    }

    private fun engine(tab: BrowserTab, load: Boolean = true): BrowserEngine {
        engines[tab.id]?.let { if (!it.disposed) { it.webView.onResume(); return it } else engines.remove(tab.id) }
        if (engines.size >= 3) {
            val candidate = engines.keys.firstOrNull { it !in visible && it != busyTab }
                ?: error("浏览器页面繁忙，请先关闭一个页面")
            resolveRequests(candidate)
            engines.remove(candidate)?.dispose()
            mutableViews.value = views.value - candidate
        }
        val result = BrowserEngine(context, tab.id, state.value.settings, asyncScript, object : BrowserEngineEvents {
            override fun changed(url: String?, title: String?, view: BrowserViewState) {
                val before = views.value[tab.id]
                mutableViews.value = views.value + (tab.id to view)
                if (view.crashed) {
                    visible.remove(tab.id)
                    resolveRequests(tab.id)
                    scheduleIdleRelease()
                }
                val current = state.value.tabs.find { it.id == tab.id } ?: return
                val changed = current.copy(url = url?.let { publicUrl(tab.id, it) } ?: current.url, title = title ?: current.title)
                var next = state.value.copy(tabs = state.value.tabs.map { if (it.id == tab.id) changed else it })
                if (!view.loading && view.error == null && (before?.loading == true || changed.url != current.url)) {
                    next = next.recordVisit(BrowserPage(changed.url, changed.title))
                }
                update(next)
            }
            override fun download(url: String, name: String?, mime: String?) {
                scope.launch(Dispatchers.Main.immediate) { runCatching { download(url, name, mime, tab.id) }.onFailure { mutableError.value = it.message } }
            }
            override fun chooser(request: BrowserFileChooser) {
                mutableChooser.value?.callback?.onReceiveValue(null)
                mutableChooser.value = request.copy(fromAgent = running.value == tab.conversationId)
            }
            override fun dialog(request: BrowserDialog) {
                mutableDialog.value?.result?.cancel()
                mutableDialog.value = request
            }
            override fun permission(request: BrowserPermission) {
                mutablePermission.value?.request?.deny()
                mutablePermission.value = request
            }
            override fun popup(message: Message): Boolean {
                if (running.value == tab.conversationId && state.value.tabsFor(tab.conversationId).size >= 3) return false
                scope.launch(Dispatchers.Main.immediate) {
                    runCatching {
                        val child = newTab(tab.conversationId, workspaceId = state.value.tabs.find { it.id == tab.id }?.workspaceId)
                        val view = engine(child, load = false).webView
                        (message.obj as WebView.WebViewTransport).webView = view
                        message.sendToTarget()
                    }.onFailure { mutableError.value = it.message }
                }
                return true
            }
            override fun close() { scope.launch { runCatching { close(tab.conversationId, tab.id) } } }
            override fun resource(request: WebResourceRequest): WebResourceResponse? =
                state.value.tabs.find { it.id == tab.id }?.let { workspaceResource(it, request) }
            override fun gesture(value: JsonObject) {
                if (running.value != tab.conversationId || busyTab != tab.id) return
                decodeBrowserGesture(++gestureSequence, tab.id, value)?.let { mutableGestures.tryEmit(it) }
            }
        })
        engines[tab.id] = result
        mutableViews.value = views.value + (tab.id to result.viewState)
        if (load) result.loadUrl(nativeUrl(tab, tab.url))
        return result
    }

    suspend fun back(owner: String, forward: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        state.value.selectedTab(owner)?.let { tab -> engine(tab).back(forward) }
    }

    suspend fun reload(owner: String, stop: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        state.value.selectedTab(owner)?.let { tab -> engine(tab).reload(stop) }
    }

    suspend fun find(owner: String, text: String, forward: Boolean? = null) = withContext(Dispatchers.Main.immediate) {
        state.value.selectedTab(owner)?.let { tab -> engine(tab).webView.let {
            if (forward == null) it.findAllAsync(text) else it.findNext(forward)
        } }
    }

    fun pause(owner: String, pause: Boolean) {
        mutablePaused.value = if (pause) paused.value + owner else paused.value - owner
    }

    fun bookmark(page: BrowserPage) = update(state.value.copy(bookmarks =
        if (state.value.bookmarks.any { it.url == page.url }) state.value.bookmarks.filterNot { it.url == page.url }
        else listOf(page) + state.value.bookmarks))

    fun clearHistory() = update(state.value.copy(history = emptyList()))

    fun settings(value: BrowserSettings) {
        require(value.customUserAgent.length <= 2048 && value.customUserAgent.all { it.code in 32..126 }) { "无效的 User-Agent" }
        val old = state.value.settings
        update(state.value.copy(settings = value))
        engines.values.filterNot { it.disposed }.forEach { it.applySettings(value) }
        if (old.desktopMode != value.desktopMode || old.customUserAgent != value.customUserAgent) {
            engines.filterKeys { it in visible }.values.forEach { it.webView.reload() }
        }
    }

    fun finishChooser(uris: Array<Uri>?) { mutableChooser.value?.callback?.onReceiveValue(uris); mutableChooser.value = null }
    fun finishDialog(accept: Boolean, text: String = "") {
        mutableDialog.value?.result?.let { result ->
            if (!accept) result.cancel() else if (result is JsPromptResult) result.confirm(text) else result.confirm()
        }
        mutableDialog.value = null
    }
    fun finishPermission(resources: Array<String>) {
        mutablePermission.value?.request?.let { if (resources.isEmpty()) it.deny() else it.grant(resources) }
        mutablePermission.value = null
    }
    private fun resolveRequests(id: String) {
        if (chooser.value?.tabId == id) finishChooser(null)
        if (dialog.value?.tabId == id) finishDialog(false)
        if (permission.value?.tabId == id) finishPermission(emptyArray())
    }

    suspend fun clearWebsiteData() {
        initialize()
        withContext(Dispatchers.Main.immediate) {
            require(busyTab == null) { "网页操作进行中，请稍后清理" }
            state.value.tabs.toList().forEach { close(it.conversationId, it.id) }
            WebStorage.getInstance().deleteAllData()
            suspendCancellableCoroutine { continuation ->
                CookieManager.getInstance().removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
            }
            CookieManager.getInstance().flush()
            WebView(context).apply { clearCache(true); destroy() }
        }
    }

    private suspend fun download(url: String, name: String?, mime: String?, tabId: String) {
        val address = browserAddress(url, allowSearch = false)
        require(address.startsWith("http")) { "仅支持 HTTP(S) 下载" }
        val userAgent = engines[tabId]?.webView?.settings?.userAgentString
        val cookie = CookieManager.getInstance().getCookie(address)
        val label = (name ?: "download").substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(160).ifBlank { "download" }
        val id = withContext(Dispatchers.IO) {
            val request = DownloadManager.Request(Uri.parse(address))
                .setTitle(label).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "${Uuid.random()}-$label")
            mime?.let(request::setMimeType)
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            cookie?.let { request.addRequestHeader("Cookie", it) }
            downloads.enqueue(request)
        }
        update(state.value.copy(downloads = listOf(BrowserDownload(id, label, address)) + state.value.downloads))
    }

    suspend fun downloadStatus(): List<BrowserDownloadView> = withContext(Dispatchers.IO) {
        state.value.downloads.map { record ->
            downloads.query(DownloadManager.Query().setFilterById(record.id)).use { cursor ->
                if (!cursor.moveToFirst()) BrowserDownloadView(record, DownloadManager.STATUS_FAILED, 0, -1)
                else BrowserDownloadView(record, cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
            }
        }
    }
    fun downloadedUri(id: Long): Uri? = downloads.getUriForDownloadedFile(id)
    suspend fun removeDownload(id: Long) {
        withContext(Dispatchers.IO) { downloads.remove(id) }
        withContext(Dispatchers.Main.immediate) { update(state.value.copy(downloads = state.value.downloads.filterNot { it.id == id })) }
    }

    private fun workspaceHost(tabId: String) = "workspace-$tabId.rikkaplus.invalid"
    private fun publicUrl(tabId: String, url: String) = if (Uri.parse(url).host == workspaceHost(tabId)) {
        Uri.parse(url).buildUpon().scheme("rikkaplus").authority("workspace").build().toString()
    } else url
    private fun nativeUrl(tab: BrowserTab, url: String): String = if (url.startsWith("rikkaplus://workspace/")) {
        require(tab.workspaceId != null) { "当前聊天未绑定工作区" }
        Uri.parse(url).buildUpon().scheme("https").authority(workspaceHost(tab.id)).build().toString()
    } else url

    private fun workspaceResource(tab: BrowserTab, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != workspaceHost(tab.id)) return null
        return runCatching {
            require(tab.workspaceId != null)
            require(tab.url.startsWith("rikkaplus://workspace/")) { "工作区页面已关闭" }
            val path = request.url.path.orEmpty().removePrefix("/")
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(count.toLong() + length <= 16 * 1024 * 1024) { "网页资源过大" }
                    super.write(bytes, offset, length)
                }
                override fun write(value: Int) { require(count < 16 * 1024 * 1024); super.write(value) }
            }
            runBlocking { workspaces.exportFile(tab.workspaceId, WorkspaceStorageArea.FILES, path, output) }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
            WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(output.toByteArray()))
        }.getOrElse {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("无法读取工作区文件".toByteArray()))
        }
    }

    override suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject): List<UIMessagePart> {
        initialize()
        return actions.withLock { withContext(Dispatchers.Main.immediate) {
            val action = arguments["action"]?.jsonPrimitive?.content ?: error("action is required")
            if (action == "list_tabs") return@withContext listOf(UIMessagePart.Text(tabList(conversationId).toString()))
            check(conversationId !in paused.value) { "browser_paused: 用户已接管，请在浏览器中点击继续后再操作" }
            mutableRunning.value = conversationId
            idleJob?.cancel()
            try {
                val requested = arguments["tab_id"]?.jsonPrimitive?.contentOrNull
                var tab = requested?.let { state.value.requireOwned(conversationId, it) } ?: state.value.selectedTab(conversationId)
                if (action == "new_tab" || tab == null && action in setOf("open", "render")) {
                    require(state.value.tabsFor(conversationId).size < 3) { "当前聊天已有 3 个标签，请复用或关闭后再创建" }
                    tab = newTab(conversationId, workspaceId = workspaceId)
                }
                val current = tab ?: error("no_tab: open a URL first")
                busyTab = current.id
                mutableActivity.value = BrowserActivity(conversationId, current.id, action)
                if (action == "close_tab") {
                    busyTab = null
                    close(conversationId, current.id)
                    return@withContext listOf(UIMessagePart.Text(tabList(conversationId).toString()))
                }
                select(conversationId, current.id)
                when (action) {
                    "open", "new_tab" -> {
                        val url = arguments["url"]?.jsonPrimitive?.contentOrNull ?: if (action == "new_tab") "about:blank" else error("url is required")
                        browserAddress(url, allowSearch = false)
                        navigate(conversationId, url, workspaceId, current.id)
                    }
                    "render" -> engine(current).loadHtml(arguments["html"]?.jsonPrimitive?.content ?: error("html is required"))
                    "back" -> back(conversationId)
                    "forward" -> back(conversationId, true)
                    "reload" -> reload(conversationId)
                    "handle_dialog" -> {
                        require(dialog.value?.tabId == current.id) { "当前标签没有等待处理的网页对话框" }
                        finishDialog(arguments["accept"]?.jsonPrimitive?.booleanOrNull == true,
                            arguments["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                    "wait" -> delay(((arguments["seconds"]?.jsonPrimitive?.doubleOrNull ?: 1.0).coerceIn(0.0, 10.0) * 1000).toLong())
                    "switch_tab", "snapshot", "extract", "screenshot", "click", "type", "select_option", "press", "scroll", "evaluate", "console", "upload" -> Unit
                    else -> error("Unsupported browser action: $action")
                }
                val native = engine(state.value.requireOwned(conversationId, current.id))
                if (action in navigationActions || native.awaitingDocument) native.waitForDom()
                if (action == "upload") {
                    val pending = chooser.value?.takeIf { it.tabId == current.id } ?: error("请先点击网页的文件上传控件")
                    val bound = workspaceId ?: error("当前聊天未绑定工作区")
                    val uris = withContext(Dispatchers.IO) {
                        arguments["paths"]?.jsonArray?.map { entry ->
                            val file = workspaces.resolveFile(bound, WorkspaceStorageArea.FILES, entry.jsonPrimitive.content)
                                ?: error("远端文件请先下载到本地后通过文件选择器上传")
                            require(file.isFile)
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }?.toTypedArray() ?: error("paths is required")
                    }
                    check(conversationId !in paused.value) { "browser_paused: 用户已接管，文件尚未上传" }
                    check(chooser.value === pending) { "网页的文件选择请求已变化，请重新读取页面" }
                    pending.callback.onReceiveValue(uris)
                    mutableChooser.value = null
                }
                check(conversationId !in paused.value) { "browser_paused: 用户已接管；先确认页面结果，等待用户允许继续" }
                val domActions = setOf("snapshot", "extract", "click", "type", "select_option", "press", "scroll", "wait")
                var data: JsonElement = buildJsonObject { put("action", action) }
                if (action in domActions || action in navigationActions || action in setOf("switch_tab", "handle_dialog", "upload")) {
                    if (dialog.value?.tabId == current.id) {
                        data = buildJsonObject { put("dialog", dialog.value?.message); put("next_action", "handle_dialog") }
                    } else {
                        val domAction = if (action in domActions) action else "snapshot"
                        val args = buildJsonObject {
                            arguments.forEach { (key, value) -> put(key, value) }
                            put("snapshot_id", Uuid.random().toString())
                            if ("max_chars" !in arguments) put("max_chars", 12_000)
                            if ("include_text" !in arguments) put("include_text", true)
                        }
                        val body = automation ?: withContext(Dispatchers.IO) { context.assets.open("browser/automation.js").bufferedReader().use { it.readText() } }.also { automation = it }
                        val raw = native.evaluate("const action = ${JsonPrimitive(domAction)}; const args = $args; $body")
                        data = if (raw is JsonPrimitive && raw.isString) JsonInstant.parseToJsonElement(raw.content) else raw
                    }
                } else if (action == "evaluate") {
                    val script = arguments["script"]?.jsonPrimitive?.content ?: error("script is required")
                    data = native.evaluate("return await (0, eval)(${JsonPrimitive(script)});")
                } else if (action == "console") data = JsonArray(native.console.map(::JsonPrimitive))
                val result = buildJsonObject {
                    put("tab_id", current.id); put("conversation_id", conversationId)
                    put("url", state.value.requireOwned(conversationId, current.id).url)
                    put("workspace_id", state.value.requireOwned(conversationId, current.id).workspaceId)
                    put("page", data); put("website_content", "untrusted")
                }
                if (action == "screenshot") {
                    val viewport = native.evaluate("return {width:innerWidth,height:innerHeight};")
                    val (bytes, size) = native.screenshot()
                    val uri = withContext(Dispatchers.IO) {
                        files.createChatFilesByByteArrays(listOf(bytes), "browser.png", "image/png").first().toString()
                    }
                    return@withContext listOf(UIMessagePart.Image(uri), UIMessagePart.Text(buildJsonObject {
                        result.forEach { (key, value) -> put(key, value) }
                        put("viewport", viewport); put("image_width", size.first); put("image_height", size.second)
                        put("coordinates", "viewport CSS pixels; multiply image x/y by viewport width/height divided by image width/height")
                    }.toString()))
                }
                listOf(UIMessagePart.Text(result.toString()))
            } finally {
                mutableActivity.value = null
                busyTab = null
                mutableRunning.value = null
                engines.filterKeys { it !in visible }.values.filterNot { it.disposed }.forEach { it.webView.onPause() }
                scheduleIdleRelease()
            }
        } }
    }

    private fun tabList(owner: String) = buildJsonObject {
        put("scope", "conversation"); put("paused", owner in paused.value)
        put("tabs", buildJsonArray { state.value.tabsFor(owner).forEach { tab -> add(buildJsonObject {
            put("tab_id", tab.id); put("url", tab.url); put("title", tab.title)
            put("active", tab.id == state.value.selectedTab(owner)?.id)
        }) } })
    }
}
