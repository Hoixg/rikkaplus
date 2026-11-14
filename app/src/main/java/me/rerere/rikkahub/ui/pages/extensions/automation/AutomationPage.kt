@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.components.automation.taskModelLabel
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

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
                    Text("按指定日期、每天、每周或固定间隔执行，支持一天多个时间点；结果保存在聊天中。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (tasks.isNotEmpty()) item {
                Text("${tasks.count { it.enabled }} 个已启用 · ${runs.size} 个进行中",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(tasks, key = { it.id }) { task ->
                val run = runs.firstOrNull { it.taskId == task.id }
                val assistant = settings.assistants.firstOrNull { it.id.toString() == task.assistantId }
                AutomationTaskCard(
                    task = task, run = run, busy = task.id in busy,
                    model = run?.executionModelName?.takeIf { it.isNotBlank() } ?: taskModelLabel(task.definition, settings),
                    assistant = assistant?.name?.ifBlank { "默认助手" } ?: "助手已删除",
                    onEdit = { nav.navigate(Screen.AutomationEditor(task.id)) },
                    onEnabledChange = { enabled -> action(task.id) { repository.setEnabled(task.id, enabled, task.revision) } },
                    onRun = { action(task.id) {
                        if (run != null) chat.cancelAutomation(run.id) else { repository.queue(task.id, task.revision); repository.kick() }
                    } },
                    onHistory = { history = task.id }, onDelete = { deleting = task },
                )
            }
        }
    }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除任务？") },
        text = { Text("删除“${task.definition.name}”及其运行记录，生成的聊天仍保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; action(task.id) { repository.delete(task.id, task.revision) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    history?.let { id -> AutomationHistorySheet(id, repository) { history = null } }
    AutomationError(error) { error = null }
}

@Composable
internal fun AutomationBackgroundButton() {
    val nav = LocalNavController.current
    IconButton(onClick = { nav.navigate(Screen.SettingBackground) }) {
        Icon(HugeIcons.Settings03, "后台运行与权限")
    }
}

@Composable
internal fun AutomationError(error: String?, onDismiss: () -> Unit) {
    if (error != null) AlertDialog(onDismissRequest = onDismiss, title = { Text("操作未完成") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
