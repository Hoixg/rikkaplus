@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import me.rerere.rikkahub.data.datastore.Settings as AppSettings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun AutomationPage() {
    val repository = koinInject<AutomationRepository>()
    val chat = koinInject<ChatService>()
    val tasks by repository.tasks.collectAsStateWithLifecycle(emptyList())
    val runs by repository.activeRuns.collectAsStateWithLifecycle(emptyList())
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AutomationTask?>(null) }
    var history by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun action(id: String, block: suspend () -> Unit) {
        if (id in busy) return
        busy += id
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy -= id }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("定时任务") }, navigationIcon = { BackButton() },
            actions = { AutomationBackgroundButton() }, colors = CustomColors.topBarColors) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.navigate(Screen.AutomationEditor()) },
            icon = { Icon(HugeIcons.Add01, null) }, text = { Text("新建任务") }) },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tasks.isEmpty()) item {
                Card { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("让助手按时完成工作", style = MaterialTheme.typography.titleLarge)
                    Text("按单次、每天、每周或固定间隔执行；结果保存在聊天中。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            items(tasks, key = { it.id }) { task ->
                val run = runs.firstOrNull { it.taskId == task.id }
                val assistant = settings.assistants.firstOrNull { it.id.toString() == task.assistantId }
                CardGroup {
                    item(onClick = { nav.navigate(Screen.AutomationEditor(task.id)) },
                        headlineContent = { Text(task.definition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(task.definition.schedule.describe()) },
                        trailingContent = { Switch(checked = task.enabled, enabled = task.id !in busy,
                            onCheckedChange = { enabled -> action(task.id) { repository.setEnabled(task.id, enabled, task.revision) } }) })
                    formItem {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val model = run?.executionModelName?.takeIf { it.isNotBlank() } ?: taskModelLabel(task.definition, settings)
                            Text("${assistant?.name?.ifBlank { "默认助手" } ?: "助手已删除"} · $model", maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(run?.state?.label ?: task.nextRunAt?.let { "下次执行：${formatTaskTime(it)}" } ?: "未启用排程",
                                color = if (run == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium)
                            if (task.definition.prompt.isNotBlank()) Text(task.definition.prompt, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(onClick = { nav.navigate(Screen.AutomationEditor(task.id)) }) { Text("编辑") }
                                TextButton(enabled = task.id !in busy, onClick = { action(task.id) {
                                    if (run != null) chat.cancelAutomation(run.id) else { repository.queue(task.id, task.revision); repository.kick() }
                                } }) { Text(if (run == null) "执行" else "取消本次") }
                                TextButton(onClick = { history = task.id }) { Text("记录") }
                                IconButton(enabled = run == null && task.id !in busy, onClick = { deleting = task }) {
                                    Icon(HugeIcons.Delete01, "删除任务")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除任务？") },
        text = { Text("删除“${task.definition.name}”及其运行记录，生成的聊天仍保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; action(task.id) { repository.delete(task.id, task.revision) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    history?.let { id ->
        val entries by remember(id) { repository.historyFlow(id) }.collectAsStateWithLifecycle(emptyList())
        ModalBottomSheet(onDismissRequest = { history = null }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("运行记录", style = MaterialTheme.typography.headlineSmall) }
                if (entries.isEmpty()) item { Text("尚未执行") }
                items(entries, key = { it.id }) { run ->
                    Card(onClick = { run.conversationId?.let { history = null; nav.navigate(Screen.Chat(it)) } }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(run.state.label, style = MaterialTheme.typography.titleMedium)
                            Text("${formatTaskTime(run.dueAt)} · ${if (run.manual) "手动执行" else "定时执行"}", style = MaterialTheme.typography.labelMedium)
                            Text(run.executionModelName.ifBlank { taskModelLabel(run.definition, settings) }, style = MaterialTheme.typography.bodySmall)
                            if (run.detail.isNotBlank()) Text(run.detail, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (run.conversationId != null) Text("查看聊天", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    AutomationError(error) { error = null }
}

@Composable
internal fun AutomationBackgroundButton() {
    val nav = LocalNavController.current
    IconButton(onClick = { nav.navigate(Screen.SettingBackground) }) {
        Icon(HugeIcons.Settings03, "后台运行与权限")
    }
}

internal fun taskModelLabel(definition: TaskDefinition, settings: AppSettings, followingModel: me.rerere.ai.provider.Model? = null): String {
    val model = definition.modelId?.let { runCatching { settings.findModelById(Uuid.parse(it)) }.getOrNull() }
        ?: followingModel?.takeIf { definition.modelId == null }
        ?: if (definition.modelId == null && definition.mode == TaskMode.NEW_CHAT) {
            settings.assistants.firstOrNull { it.id.toString() == definition.assistantId }?.let {
                settings.findModelById(it.chatModelId ?: settings.chatModelId)
            }
        } else null
    return model?.let { listOfNotNull(it.displayName.ifBlank { it.modelId }, it.findProvider(settings.providers)?.name).joinToString(" · ") }
        ?: if (definition.modelId != null) "指定模型已删除" else if (definition.mode == TaskMode.NEW_CHAT) "跟随助手模型" else "跟随目标聊天模型"
}

internal fun formatTaskTime(time: Long): String = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable
internal fun AutomationError(error: String?, onDismiss: () -> Unit) {
    if (error != null) AlertDialog(onDismissRequest = onDismiss, title = { Text("操作未完成") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
