@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.automation.AutomationAlarms
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.ScheduleKind
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import me.rerere.rikkahub.data.automation.TaskSchedule
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.automation.AutomationSettingRow
import me.rerere.rikkahub.ui.components.automation.modeLabel
import me.rerere.rikkahub.ui.components.automation.taskModelLabel
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private enum class TaskPicker { ASSISTANT, CONVERSATION, MESSAGE }

@Composable
fun AutomationEditorPage(taskId: String?) {
    val repository = koinInject<AutomationRepository>()
    val conversations = koinInject<ConversationRepository>()
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var definition by rememberSaveable(taskId, stateSaver = Saver<TaskDefinition?, String>(
        save = { it?.let { value -> JsonInstant.encodeToString(value) } },
        restore = { JsonInstant.decodeFromString<TaskDefinition>(it) },
    )) { mutableStateOf<TaskDefinition?>(null) }
    var revision by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    val id by rememberSaveable(taskId) { mutableStateOf(taskId ?: Uuid.random().toString()) }
    var enabled by rememberSaveable(taskId) { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LifecycleResumeEffect(taskId) {
        if (taskId == null) enabled = AutomationAlarms.allowed(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(taskId) {
        if (definition != null) return@LaunchedEffect
        val loadedSettings = store.awaitLoaded()
        val existing = taskId?.let { repository.task(it) }
        if (taskId != null && existing == null) { missing = true; return@LaunchedEffect }
        definition = existing?.definition ?: TaskDefinition(assistantId = loadedSettings.assistantId.toString(),
            schedule = TaskSchedule(at = System.currentTimeMillis() + 3_600_000))
        revision = existing?.revision
        enabled = existing?.enabled ?: AutomationAlarms.allowed(context)
    }
    fun change(value: TaskDefinition) { definition = value }
    val current = definition
    var picker by rememberSaveable(taskId) { mutableStateOf<TaskPicker?>(null) }
    var target by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(current?.conversationId) {
        target = current?.conversationId?.let { conversations.getConversationById(Uuid.parse(it)) }
    }
    val targetList by remember(current?.assistantId) {
        conversations.getConversationsOfAssistant(Uuid.parse(current?.assistantId ?: settings.assistantId.toString()))
    }.collectAsStateWithLifecycle(emptyList())
    val assistant = settings.assistants.firstOrNull { it.id.toString() == current?.assistantId }
    val modelPicker = rememberModelListState(current?.modelId?.let(Uuid::parse), settings.providers, ModelType.CHAT)
    var interval by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    val runs by repository.activeRuns.collectAsStateWithLifecycle(emptyList())
    val hasActiveRun = taskId != null && runs.any { it.taskId == taskId }
    val intervalValid = current?.schedule?.kind != ScheduleKind.INTERVAL || (interval ?: current.schedule.intervalMinutes.toString()).toIntOrNull()?.let { it >= 15 } == true
    val valid = current?.let { runCatching { it.validate() }.isSuccess } == true && intervalValid

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text(if (taskId == null) "新建定时任务" else "编辑定时任务") }, navigationIcon = { BackButton() },
            actions = { AutomationBackgroundButton() }, colors = CustomColors.topBarColors) },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Button(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), enabled = valid && !saving,
                onClick = { val value = current ?: return@Button; saving = true; scope.launch {
                    try {
                        repository.save(id, value.copy(name = value.name.trim(), prompt = value.prompt.trim()), if (taskId == null) enabled else null, revision)
                        nav.popBackStack()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { saving = false }
                } }) { Text(if (saving) "正在保存…" else if (taskId != null) "保存修改" else if (!enabled) "保存为停用任务" else "创建任务") }
        } },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (current == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (missing) Text("任务已删除") else CircularProgressIndicator()
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup(title = { Text("任务内容") }) {
                    formItem {
                        OutlinedTextField(value = current.name, onValueChange = { change(current.copy(name = it)) }, label = { Text("任务名称") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (current.mode != TaskMode.REGENERATE) {
                            OutlinedTextField(value = current.prompt, onValueChange = { change(current.copy(prompt = it)) }, label = { Text("执行提示词") },
                                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                        }
                        if (hasActiveRun) Text("本次继续按原配置执行，修改用于后续执行。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (taskId == null && !enabled) TextButton(onClick = { nav.navigate(Screen.SettingBackground) }) {
                            Text("授权精确闹钟后可启用自动执行")
                        }
                    }
                }
            }
            item {
                AutomationScheduleEditor(current.schedule, interval, onIntervalChange = { value ->
                    interval = value
                    value.toIntOrNull()?.let { change(current.copy(schedule = current.schedule.copy(intervalMinutes = it))) }
                }, onChange = { change(current.copy(schedule = it)) })
            }
            item {
                CardGroup(title = { Text("执行设置") }) {
                    formItem {
                        ConnectedChoiceButtons(options = TaskMode.entries, selected = current.mode,
                            onSelect = { change(current.copy(mode = it, conversationId = null, userMessageId = null,
                                resetContextBeforeRun = false)) }) { mode, selected ->
                            ConnectedChoiceLabel(modeLabel(mode), selected)
                        }
                        Text(when (current.mode) {
                            TaskMode.NEW_CHAT -> "每次新建聊天，结果独立保存。"
                            TaskMode.FOLLOW_UP -> "聊天空闲后发送，用户消息优先。"
                            TaskMode.REGENERATE -> "复制所选消息及上下文，另建聊天生成。"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column {
                            AutomationSettingRow("助手", assistant?.name?.ifBlank { "默认助手" } ?: "选择助手", { picker = TaskPicker.ASSISTANT })
                            if (current.mode != TaskMode.NEW_CHAT) AutomationSettingRow("目标聊天", target?.title?.ifBlank { "未命名聊天" } ?: "选择聊天", { picker = TaskPicker.CONVERSATION })
                            if (current.mode == TaskMode.REGENERATE) AutomationSettingRow("用户消息",
                                target?.currentMessages?.firstOrNull { it.id.toString() == current.userMessageId }?.toText()?.ifBlank { "附件消息" } ?: "选择消息",
                                { picker = TaskPicker.MESSAGE })
                            AutomationSettingRow("模型", taskModelLabel(current, settings, target?.let { settings.getChatModelOf(it) }), { modelPicker.open() },
                                onClear = if (current.modelId != null) { { change(current.copy(modelId = null)) } } else null,
                                clearDescription = "跟随默认模型")
                        }
                    }
                    if (current.mode == TaskMode.FOLLOW_UP) switchItem(
                        checked = current.resetContextBeforeRun,
                        onCheckedChange = { change(current.copy(resetContextBeforeRun = it)) },
                        headlineContent = { Text("每次清空上下文和记录") },
                        supportingContent = { Text("每次执行前删除目标聊天的旧消息、分支和压缩摘要，仅保留本轮记录。") },
                    )
                    item(onClick = { change(current.copy(notify = !current.notify)) }, headlineContent = { Text("执行结果通知") },
                        trailingContent = { Switch(current.notify, onCheckedChange = { change(current.copy(notify = it)) }) })
                }
            }
        }
    }
    if (current != null) {
        ModelListSheet(modelPicker) { change(current.copy(modelId = it.id.toString())) }
        if (picker == TaskPicker.ASSISTANT) (assistant ?: settings.assistants.firstOrNull())?.let { AssistantPickerSheet(settings, it, onAssistantSelected = { selected ->
            change(current.copy(assistantId = selected.id.toString(), conversationId = null, userMessageId = null)); picker = null
        }, onDismiss = { picker = null }) }
        if (picker == TaskPicker.CONVERSATION || picker == TaskPicker.MESSAGE) {
            val choices = if (picker == TaskPicker.CONVERSATION) targetList.map { it.id.toString() to it.title.ifBlank { "未命名聊天" } }
                else target?.currentMessages.orEmpty().filter { it.role == MessageRole.USER }.map { it.id.toString() to it.toText().ifBlank { "附件消息" } }
            AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (picker == TaskPicker.CONVERSATION) "选择聊天" else "选择用户消息") },
                text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (choices.isEmpty()) item { Text("没有可选内容") }
                    items(choices, key = { it.first }) { (choiceId, label) -> TextButton(onClick = {
                        change(if (picker == TaskPicker.CONVERSATION) current.copy(conversationId = choiceId, userMessageId = null) else current.copy(userMessageId = choiceId)); picker = null
                    }) { Text(label, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                } }, confirmButton = { TextButton(onClick = { picker = null }) { Text("取消") } })
        }
    }
    AutomationError(error) { error = null }
}
