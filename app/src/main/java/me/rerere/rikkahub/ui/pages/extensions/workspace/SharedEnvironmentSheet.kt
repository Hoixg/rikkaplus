package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.utils.fileSizeToString

@Composable
internal fun SharedEnvironmentSheet(
    state: SharedEnvironmentState,
    onRefresh: () -> Unit,
    onCleanup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showTools by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val overviewListState = rememberLazyListState()
    val toolsListState = rememberLazyListState()
    val info = state.info
    val tools = remember(info) {
        info?.let {
            (it.packages.map { pkg -> pkg.name to "软件包 · ${pkg.version}" } +
                it.commands.map { path -> path.substringAfterLast('/') to "命令 · $path" }).sortedBy { entry -> entry.first }
        }.orEmpty()
    }
    val matches = remember(tools, query) { tools.filter { it.first.contains(query.trim(), true) || it.second.contains(query.trim(), true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showTools) {
                    IconButton(onClick = { showTools = false }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回共享环境")
                    }
                }
                Text(if (showTools) "工具与软件包 · ${tools.size}" else "共享环境", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(HugeIcons.Refresh01, contentDescription = "刷新环境信息")
                }
            }
            if (showTools) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    label = { Text("搜索名称、版本或命令路径") },
                    singleLine = true,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = if (showTools) toolsListState else overviewListState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.loading) item {
                    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text("正在读取环境信息…")
                    }
                }
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (showTools) {
                    itemsIndexed(matches, key = { _, entry -> "${entry.first}@${entry.second}" }) { index, entry ->
                        Surface(shape = cardGroupItemShape(index, matches.size), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(entry.first, style = MaterialTheme.typography.bodyLarge)
                                Text(entry.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (matches.isEmpty()) item {
                        Text(if (query.isBlank()) "暂无软件包或自装命令记录" else "没有匹配的工具", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!showTools && info != null) {
                    item {
                        CardGroup(Modifier.padding(bottom = 16.dp)) {
                            item(
                                headlineContent = { Text("Rootfs · ${if (info.installed) "已就绪" else "未就绪"}") },
                                supportingContent = { Text(info.distribution ?: "未检测到 Linux 发行版信息") },
                            )
                            item(
                                headlineContent = { Text("环境占用 ${info.sizeBytes.fileSizeToString()}") },
                                supportingContent = {
                                    Text("Rootfs ${info.rootfsSizeBytes.fileSizeToString()} · 其他 ${(info.sizeBytes - info.rootfsSizeBytes).coerceAtLeast(0).fileSizeToString()}\n按文件大小估算，不含项目文件、独立环境或 SSH。")
                                },
                            )
                            formItem(headlineContent = { Text("Rootfs 位置") }) {
                                SelectionContainer { Text("${info.directory}/linux", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (info.incomplete) item {
                        Text("部分目录无法读取，当前显示已统计的信息。", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        CardGroup {
                            item(
                                onClick = { showTools = true },
                                headlineContent = { Text("工具与软件包 · ${tools.size}") },
                                supportingContent = { Text("${info.packages.size} 个软件包 · ${info.commands.size} 个命令，点击查看") },
                                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                            )
                        }
                    }
                }
                if (!showTools) item {
                    CardGroup(Modifier.padding(top = 24.dp)) {
                        item(
                            onClick = onCleanup,
                            leadingContent = { CardGroupIcon(HugeIcons.Clean) },
                            headlineContent = { Text(stringResource(R.string.workspace_cleanup_title)) },
                            supportingContent = { Text("清理已停用的旧 Linux 环境") },
                        )
                    }
                }
            }
        }
    }
}
