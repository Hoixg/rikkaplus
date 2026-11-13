package me.rerere.rikkahub.ui.pages.browser

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.browser.BrowserDownload
import me.rerere.rikkahub.browser.BrowserDownloadView
import me.rerere.rikkahub.browser.BrowserManager
import me.rerere.rikkahub.browser.BrowserSettings
import me.rerere.rikkahub.browser.BrowserTab
import me.rerere.rikkahub.browser.BrowserPage as SavedPage
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
internal fun BrowserLibrary(
    pages: List<SavedPage>,
    bookmarks: Boolean,
    onOpen: (String) -> Unit,
    onRemove: ((SavedPage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable(bookmarks) { mutableStateOf("") }
    val filtered = remember(pages, query) {
        pages.filter { it.title.contains(query, true) || it.url.contains(query, true) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pages.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (bookmarks) "搜索书签" else "搜索历史记录") },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(HugeIcons.Cancel01, "清除搜索内容")
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            BrowserPanelEmpty(
                text = when {
                    pages.isEmpty() -> if (bookmarks) "还没有收藏的网页" else "暂无浏览记录"
                    query.isNotBlank() -> if (bookmarks) "未找到匹配的书签" else "未找到匹配的历史记录"
                    bookmarks -> "还没有收藏的网页"
                    else -> "暂无浏览记录"
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(filtered, key = { it.url }, contentType = { "page" }) { page ->
                    BrowserListRow(
                        title = page.title.ifBlank { page.url },
                        subtitle = page.url,
                        onOpen = { onOpen(page.url) },
                        onRemove = onRemove?.let { { it(page) } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BrowserListRow(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null,
    icon: ImageVector = HugeIcons.Earth,
    progress: Float? = null,
) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(icon)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmallEmphasized)
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(HugeIcons.Cancel01, "移除") }
            else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
internal fun BrowserTabCard(tab: BrowserTab, selected: Boolean = false, onOpen: () -> Unit, onClose: (() -> Unit)? = null) {
    val site = when {
        tab.url == "about:blank" -> "空白页"
        tab.url.startsWith("rikkaplus://workspace/") -> "工作区网页"
        else -> Uri.parse(tab.url).host ?: tab.url
    }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 148.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(HugeIcons.Earth, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (onClose != null) IconButton(onClick = onClose) { Icon(HugeIcons.Cancel01, "关闭标签", Modifier.size(18.dp)) }
            }
            Text(tab.title.ifBlank { "新标签页" }, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmallEmphasized)
            Text(site, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrowserPanelEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BrowserDownloads(
    manager: BrowserManager,
    records: List<BrowserDownload>,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by produceState(emptyList<BrowserDownloadView>(), manager, records) {
        while (true) { value = manager.downloadStatus(); delay(1_000) }
    }
    if (records.isEmpty()) {
        BrowserPanelEmpty("暂无下载", modifier)
    } else LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(rows, key = { it.record.id }, contentType = { "download" }) { item ->
            val status = when (item.status) {
                DownloadManager.STATUS_SUCCESSFUL -> "已完成"
                DownloadManager.STATUS_FAILED -> "下载失败"
                DownloadManager.STATUS_PAUSED -> "已暂停"
                DownloadManager.STATUS_PENDING -> "等待下载"
                else -> Formatter.formatFileSize(context, item.received) +
                    if (item.total > 0) " / ${Formatter.formatFileSize(context, item.total)}" else " · 下载中"
            }
            BrowserListRow(
                title = item.record.name,
                subtitle = status,
                icon = HugeIcons.Download01,
                progress = if (item.status == DownloadManager.STATUS_RUNNING && item.total > 0) {
                    (item.received.toFloat() / item.total).coerceIn(0f, 1f)
                } else null,
                onOpen = {
                    runCatching {
                        manager.downloadedUri(item.record.id)?.let { uri ->
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }.onFailure { onError(it.message ?: "无法打开文件") }
                },
                onRemove = { scope.launch { manager.removeDownload(item.record.id) } },
            )
        }
    }
}

private val searchEngines = listOf("bing" to "Bing", "google" to "Google", "duckduckgo" to "DuckDuckGo", "baidu" to "百度")

@Composable
internal fun BrowserSettingsPanel(
    settings: BrowserSettings,
    onSave: (BrowserSettings) -> Unit,
    onClearWebsiteData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var engineMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrowserSettingsHeading("搜索")
            CardGroup {
                item(
                    onClick = { engineMenu = true },
                    headlineContent = { Text("搜索引擎") },
                    leadingContent = { CardGroupIcon(HugeIcons.Search01) },
                    trailingContent = {
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CardGroupValue(searchEngines.find { it.first == draft.searchEngine }?.second ?: "Bing")
                                Icon(HugeIcons.ArrowDown01, contentDescription = null)
                            }
                            DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                                searchEngines.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { draft = draft.copy(searchEngine = id); engineMenu = false },
                                        trailingIcon = { RadioButton(selected = draft.searchEngine == id, onClick = null) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
            BrowserSettingsHeading("浏览")
            CardGroup {
                switchItem(
                    checked = draft.desktopMode,
                    onCheckedChange = { draft = draft.copy(desktopMode = it) },
                    headlineContent = { Text("桌面网站") },
                    supportingContent = { Text("以电脑布局打开网页") },
                )
                switchItem(
                    checked = draft.restoreTabs,
                    onCheckedChange = { draft = draft.copy(restoreTabs = it) },
                    headlineContent = { Text("恢复标签页") },
                    supportingContent = { Text("下次打开时恢复之前的标签") },
                )
                switchItem(
                    checked = draft.saveHistory,
                    onCheckedChange = { draft = draft.copy(saveHistory = it) },
                    headlineContent = { Text("记录浏览历史") },
                )
            }
            BrowserSettingsHeading("高级")
            CardGroup {
                formItem(
                    headlineContent = { Text("自定义 User-Agent") },
                    supportingContent = { Text("留空使用手机或桌面网站的默认标识") },
                ) {
                    OutlinedTextField(
                        value = draft.customUserAgent,
                        onValueChange = { draft = draft.copy(customUserAgent = it) },
                        placeholder = { Text("默认") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        maxLines = 4,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            BrowserSettingsHeading("网站数据")
            CardGroup {
                item(
                    onClick = onClearWebsiteData,
                    headlineContent = { Text("清理网站数据") },
                    supportingContent = { Text("清除网站登录状态、存储和缓存") },
                    leadingContent = { CardGroupIcon(HugeIcons.Delete01) },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        Button(
            onClick = { onSave(draft) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            contentPadding = PaddingValues(16.dp),
        ) { Text("保存设置") }
    }
}

@Composable
private fun BrowserSettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp))
}
