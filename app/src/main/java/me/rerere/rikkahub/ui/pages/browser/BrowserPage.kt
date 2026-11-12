package me.rerere.rikkahub.ui.pages.browser

import android.Manifest
import android.content.Intent
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.*
import me.rerere.rikkahub.browser.BrowserPage as SavedPage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun BrowserPage(conversationId: String, workspaceId: String? = null, tabId: String? = null, url: String = "") {
    val manager: BrowserManager = koinInject()
    val state by manager.state.collectAsStateWithLifecycle()
    val views by manager.views.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val activity by manager.activity.collectAsStateWithLifecycle()
    val paused by manager.paused.collectAsStateWithLifecycle()
    val storageError by manager.error.collectAsStateWithLifecycle()
    val chooser by manager.chooser.collectAsStateWithLifecycle()
    val dialog by manager.dialog.collectAsStateWithLifecycle()
    val permission by manager.permission.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    val focus = LocalFocusManager.current
    var panel by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var ready by remember(conversationId) { mutableStateOf(false) }
    var initializationError by remember(conversationId) { mutableStateOf<String?>(null) }
    var address by rememberSaveable(conversationId) { mutableStateOf("") }
    var findText by rememberSaveable(conversationId) { mutableStateOf("") }
    var showFind by rememberSaveable(conversationId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var clearData by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<BrowserFileChooser?>(null) }
    var requesting by remember { mutableStateOf<BrowserPermission?>(null) }
    var prompt by remember(dialog) { mutableStateOf(dialog?.defaultValue.orEmpty()) }
    val tab = remember(state.tabs, state.selected, conversationId) { state.selectedTab(conversationId) }
    val view = views[tab?.id] ?: BrowserViewState()

    fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { toaster.show(error.message ?: "浏览器操作失败") }
        }
    }
    fun go(value: String) {
        focus.clearFocus()
        run { manager.navigate(conversationId, value, workspaceId) }
    }
    LaunchedEffect(manager, conversationId, tabId, url) {
        try {
            manager.initialize()
            if (tabId != null && manager.state.value.tabsFor(conversationId).any { it.id == tabId }) {
                manager.select(conversationId, tabId)
            } else if (url.isNotBlank()) manager.navigate(conversationId, url, workspaceId)
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { initializationError = error.message }
    }
    LaunchedEffect(tab?.id, tab?.url) { address = tab?.url?.takeUnless { it == "about:blank" }.orEmpty() }
    BackHandler(view.back) { run { manager.back(conversationId) } }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (manager.chooser.value === choosing) {
            manager.finishChooser(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        }
        choosing = null
    }
    LaunchedEffect(chooser, conversationId) {
        chooser?.takeIf { request -> !request.fromAgent && state.tabsFor(conversationId).any { it.id == request.tabId } }?.let { request ->
            choosing = request
            try { filePicker.launch(request.intent) }
            catch (error: Exception) { manager.finishChooser(null); toaster.show(error.message ?: "无法打开文件选择器") }
        }
    }
    val websitePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pending = requesting
        if (pending != null && manager.permission.value === pending) {
            manager.finishPermission(pending.request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> grants[Manifest.permission.CAMERA] == true
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> grants[Manifest.permission.RECORD_AUDIO] == true
                    else -> false
                }
            }.toTypedArray())
        }
        requesting = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        placeholder = { Text("搜索或输入网址") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        shape = MaterialTheme.shapes.extraLarge,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                actions = {
                    TextButton(onClick = { panel = "tabs" }) { Text(state.tabsFor(conversationId).size.toString()) }
                    IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, "浏览器菜单") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("bookmarks" to "书签", "history" to "历史记录", "downloads" to "下载", "settings" to "浏览器设置").forEach { (key, title) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = { menu = false; panel = key })
                        }
                        DropdownMenuItem(text = { Text("页内查找") }, onClick = { menu = false; showFind = !showFind })
                        DropdownMenuItem(text = { Text("在外部浏览器打开") }, enabled = tab?.url?.startsWith("http") == true, onClick = {
                            menu = false
                            run { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tab?.url))) }
                        })
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = view.back, onClick = { run { manager.back(conversationId) } }) { Icon(HugeIcons.ArrowLeft01, "后退") }
                    IconButton(enabled = view.forward, onClick = { run { manager.back(conversationId, true) } }) { Icon(HugeIcons.ArrowRight01, "前进") }
                    IconButton(enabled = tab != null, onClick = { run { manager.reload(conversationId, view.loading) } }) {
                        Icon(if (view.loading) HugeIcons.Cancel01 else HugeIcons.Refresh01, if (view.loading) "停止加载" else "刷新")
                    }
                    IconButton(onClick = { run { manager.newTab(conversationId, workspaceId = workspaceId) } }) { Icon(HugeIcons.Add01, "新建标签") }
                    TextButton(enabled = tab != null && tab.url != "about:blank", onClick = {
                        tab?.let { manager.bookmark(SavedPage(it.url, it.title)) }
                    }) { Text(if (state.bookmarks.any { it.url == tab?.url }) "★" else "☆", style = MaterialTheme.typography.titleLarge) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (view.loading) LinearWavyProgressIndicator(progress = { view.progress / 100f }, modifier = Modifier.fillMaxWidth())
            (initializationError ?: storageError ?: view.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            chooser?.takeIf { it.tabId == tab?.id && it.fromAgent }?.let { request ->
                TextButton(onClick = {
                    choosing = request
                    try { filePicker.launch(request.intent) }
                    catch (failure: Exception) { manager.finishChooser(null); toaster.show(failure.message ?: "无法打开文件选择器") }
                }) { Text("网页正在选择文件，点击手动上传") }
            }
            if (showFind) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = findText, onValueChange = { text -> findText = text; run { manager.find(conversationId, text) } },
                        placeholder = { Text("查找网页文字") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { run { manager.find(conversationId, findText, true) } }) { Text("下一处") }
                    IconButton(onClick = { showFind = false; run { manager.find(conversationId, "") } }) { Icon(HugeIcons.Cancel01, "关闭查找") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!ready && initializationError == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (tab == null || tab.url == "about:blank" && !view.hasContent) {
                    BrowserHomePage(onSearch = ::go, modifier = Modifier.fillMaxSize())
                } else if (view.crashed) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("点击刷新重新加载网页") }
                } else {
                    key(tab.id) { BrowserContent(manager, tab, Modifier.fillMaxSize()) }
                }
                BrowserAgentStatus(
                    visible = running == conversationId || conversationId in paused,
                    paused = conversationId in paused,
                    activity = activity?.takeIf { it.conversationId == conversationId },
                    onToggle = { manager.pause(conversationId, conversationId !in paused) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }
    }

    if (panel != null) {
        ModalBottomSheet(onDismissRequest = { panel = null },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 16.dp)) {
                val title = when (panel) { "tabs" -> "标签页"; "bookmarks" -> "书签"; "history" -> "历史记录"; "downloads" -> "下载"; else -> "浏览器设置" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (panel == "tabs") TextButton(onClick = { run { manager.newTab(conversationId, workspaceId = workspaceId); panel = null } }) { Text("新建") }
                    if (panel == "history") TextButton(onClick = manager::clearHistory) { Text("清空") }
                    IconButton(onClick = { panel = null }) { Icon(HugeIcons.Cancel01, "关闭面板") }
                }
                Spacer(Modifier.height(12.dp))
                when (panel) {
                    "tabs" -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.tabsFor(conversationId), key = { it.id }, contentType = { "tab" }) { item ->
                            BrowserListRow(item.title.ifBlank { "新标签" }, item.url,
                                selected = item.id == tab?.id,
                                onOpen = { run { manager.select(conversationId, item.id); panel = null } },
                                onRemove = { run { manager.close(conversationId, item.id) } })
                        }
                        item(contentType = "all_tabs") {
                            val others = state.tabs.filterNot { it.conversationId == conversationId }
                            if (others.isNotEmpty()) Text("其他聊天的标签", modifier = Modifier.padding(vertical = 16.dp))
                        }
                        items(state.tabs.filterNot { it.conversationId == conversationId }, key = { "other:${it.id}" }, contentType = { "other_tab" }) { item ->
                            BrowserListRow(item.title.ifBlank { "新标签" }, item.url, onOpen = {
                                panel = null
                                nav.navigate(Screen.Browser(item.conversationId, item.workspaceId, item.id))
                            })
                        }
                    }
                    "bookmarks", "history" -> BrowserLibrary(
                        pages = if (panel == "bookmarks") state.bookmarks else state.history,
                        bookmarks = panel == "bookmarks",
                        onOpen = { go(it); panel = null },
                        onRemove = if (panel == "bookmarks") { page -> manager.bookmark(page) } else null,
                        modifier = Modifier.weight(1f),
                    )
                    "downloads" -> BrowserDownloads(manager, state.downloads, onError = { toaster.show(it) }, modifier = Modifier.weight(1f))
                    "settings" -> BrowserSettingsPanel(
                        settings = state.settings,
                        onSave = { value -> run { manager.settings(value); panel = null } },
                        onClearWebsiteData = { panel = null; clearData = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    dialog?.takeIf { request -> state.tabsFor(conversationId).any { it.id == request.tabId } }?.let { request ->
        AlertDialog(onDismissRequest = { manager.finishDialog(false) }, title = { Text("网页对话框") },
            text = { Column { Text(request.message); if (request.defaultValue != null) OutlinedTextField(prompt, { prompt = it }) } },
            confirmButton = { TextButton(onClick = { manager.finishDialog(true, prompt) }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { manager.finishDialog(false) }) { Text("取消") } })
    }
    permission?.takeIf { request -> state.tabsFor(conversationId).any { it.id == request.tabId } }?.let { request ->
        AlertDialog(onDismissRequest = { manager.finishPermission(emptyArray()) }, title = { Text("网站权限") },
            text = { Text("${request.origin}\n请求使用：" + request.request.resources.joinToString { resource -> when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"; else -> resource
            } }) },
            confirmButton = { TextButton(onClick = {
                requesting = request
                websitePermissions.launch(request.request.resources.mapNotNull { resource -> when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                } }.toTypedArray())
            }) { Text("允许") } },
            dismissButton = { TextButton(onClick = { manager.finishPermission(emptyArray()) }) { Text("拒绝") } })
    }
    if (clearData) AlertDialog(onDismissRequest = { clearData = false }, title = { Text("清理网站数据") },
        text = { Text("关闭所有浏览器标签并清理 Cookie、网站存储和缓存，网站登录将退出。书签和历史记录保留。") },
        confirmButton = { TextButton(onClick = { clearData = false; run { manager.clearWebsiteData() } }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { clearData = false }) { Text("取消") } })
}

@Composable
private fun BrowserContent(manager: BrowserManager, tab: BrowserTab, modifier: Modifier) {
    val context = LocalContext.current
    var view by remember(manager, tab.id) { mutableStateOf<WebView?>(null) }
    var error by remember(manager, tab.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(manager, tab.id, context) {
        try { view = manager.show(tab.conversationId, tab.id, context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    val native = view
    if (native != null) Box(modifier.fillMaxWidth().then(browserGestureFeedback(manager, tab.id))) {
        AndroidView(
            factory = { FrameLayout(it) },
            modifier = Modifier.matchParentSize(),
            onRelease = { host ->
                if (native.parent === host) { host.removeView(native); manager.hide(tab.id) }
            },
            update = { host ->
                if (native.parent !== host) {
                    (native.parent as? ViewGroup)?.removeView(native)
                    host.addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
        )
    } else Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (error != null) Text(error.orEmpty()) else CircularProgressIndicator()
    }
}
