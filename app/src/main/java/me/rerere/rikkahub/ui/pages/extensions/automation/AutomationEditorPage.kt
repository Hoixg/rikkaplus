@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ConnectedMultiChoiceButtons
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.uuid.Uuid

@Composable
fun AutomationEditorPage(taskId: String?) {
    val repository = koinInject<AutomationRepository>()
    val conversations = koinInject<ConversationRepository>()
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var revision by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var id by rememberSaveable(taskId) { mutableStateOf(taskId ?: Uuid.random().toString()) }
    var enabled by rememberSaveable(taskId) { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LifecycleResumeEffect(taskId) {
        if (taskId == null) enabled = AutomationAlarms.allowed(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(taskId) {
        if (draft != null) return@LaunchedEffect
        val loadedSettings = store.awaitLoaded()
        val existing = taskId?.let { repository.task(it) }
        if (taskId != null && existing == null) { missing = true; return@LaunchedEffect }
        draft = JsonInstant.encodeToString(existing?.definition ?: TaskDefinition(assistantId = loadedSettings.assistantId.toString(),
            schedule = TaskSchedule(at = System.currentTimeMillis() + 3_600_000)))
        revision = existing?.revision
        enabled = existing?.enabled ?: AutomationAlarms.allowed(context)
    }
    val definition = draft?.let { JsonInstant.decodeFromString<TaskDefinition>(it) }
    fun change(value: TaskDefinition) { draft = JsonInstant.encodeToString(value) }
    var picker by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(definition?.conversationId) {
        target = definition?.conversationId?.let { conversations.getConversationById(Uuid.parse(it)) }
    }
    val targetList by remember(definition?.assistantId) {
        conversations.getConversationsOfAssistant(Uuid.parse(definition?.assistantId ?: settings.assistantId.toString()))
    }.collectAsStateWithLifecycle(emptyList())
    val assistant = settings.assistants.firstOrNull { it.id.toString() == definition?.assistantId }
    val modelPicker = rememberModelListState(definition?.modelId?.let(Uuid::parse), settings.providers, ModelType.CHAT)
    var interval by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var showDateBounds by rememberSaveable(taskId) { mutableStateOf(false) }
    val runs by repository.activeRuns.collectAsStateWithLifecycle(emptyList())
    val hasActiveRun = taskId != null && runs.any { it.taskId == taskId }
    val intervalValid = definition?.schedule?.kind != ScheduleKind.INTERVAL || (interval ?: definition.schedule.intervalMinutes.toString()).toIntOrNull()?.let { it >= 15 } == true
    val valid = definition?.let { runCatching { it.validate() }.isSuccess } == true && intervalValid

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text(if (taskId == null) "新建定时任务" else "编辑定时任务") }, navigationIcon = { BackButton() },
            actions = { AutomationBackgroundButton() }, colors = CustomColors.topBarColors) },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Button(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), enabled = valid && !saving,
                onClick = { val value = definition ?: return@Button; saving = true; scope.launch {
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
        if (definition == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (missing) Text("任务已删除") else CircularProgressIndicator()
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                CardGroup(title = { Text("任务内容") }) {
                    formItem {
                        OutlinedTextField(value = definition.name, onValueChange = { change(definition.copy(name = it)) }, label = { Text("任务名称") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (definition.mode != TaskMode.REGENERATE) {
                            OutlinedTextField(value = definition.prompt, onValueChange = { change(definition.copy(prompt = it)) }, label = { Text("执行提示词") },
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
                CardGroup(title = { Text("执行设置") }) {
                    formItem {
                        ConnectedChoiceButtons(options = TaskMode.entries, selected = definition.mode,
                            onSelect = { change(definition.copy(mode = it, conversationId = null, userMessageId = null)) }) { mode, selected ->
                            ConnectedChoiceLabel(modeLabel(mode), selected)
                        }
                        Text(when (definition.mode) {
                            TaskMode.NEW_CHAT -> "每次新建聊天，结果独立保存。"
                            TaskMode.FOLLOW_UP -> "聊天空闲后发送，用户消息优先。"
                            TaskMode.REGENERATE -> "复制所选消息及上下文，另建聊天生成。"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column {
                            TaskSettingRow("助手", assistant?.name?.ifBlank { "默认助手" } ?: "选择助手", { picker = "assistant" })
                            if (definition.mode != TaskMode.NEW_CHAT) TaskSettingRow("目标聊天", target?.title?.ifBlank { "未命名聊天" } ?: "选择聊天", { picker = "conversation" })
                            if (definition.mode == TaskMode.REGENERATE) TaskSettingRow("用户消息",
                                target?.currentMessages?.firstOrNull { it.id.toString() == definition.userMessageId }?.toText()?.ifBlank { "附件消息" } ?: "选择消息",
                                { picker = "message" })
                            TaskSettingRow("模型", taskModelLabel(definition, settings, target?.let { settings.getChatModelOf(it) }), { modelPicker.open() },
                                onClear = if (definition.modelId != null) { { change(definition.copy(modelId = null)) } } else null,
                                clearDescription = "跟随默认模型")
                        }
                    }
                    item(onClick = { change(definition.copy(notify = !definition.notify)) }, headlineContent = { Text("执行结果通知") },
                        trailingContent = { Switch(definition.notify, onCheckedChange = { change(definition.copy(notify = it)) }) })
                }
            }
            item {
                val schedule = definition.schedule
                CardGroup(title = { Text("时间安排") }) {
                    formItem {
                        ConnectedChoiceButtons(options = ScheduleKind.entries, selected = schedule.kind,
                            onSelect = { change(definition.copy(schedule = schedule.copy(kind = it))) }) { kind, selected ->
                            ConnectedChoiceLabel(scheduleLabel(kind), selected)
                        }
                        if (schedule.kind == ScheduleKind.INTERVAL) {
                            OutlinedTextField(value = interval ?: schedule.intervalMinutes.toString(), onValueChange = { value ->
                                interval = value
                                value.toIntOrNull()?.let { change(definition.copy(schedule = schedule.copy(intervalMinutes = it))) }
                            }, label = { Text("间隔（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !intervalValid, singleLine = true, supportingText = { Text("至少 15 分钟") }, modifier = Modifier.fillMaxWidth())
                        } else {
                            val minutes = if (schedule.kind == ScheduleKind.ONCE) Instant.ofEpochMilli(schedule.at).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute } else schedule.timeMinutes
                            val time = "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
                            if (schedule.kind == ScheduleKind.ONCE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { picker = "onceDate" }, modifier = Modifier.weight(1f)) {
                                    Text(Instant.ofEpochMilli(schedule.at).atZone(ZoneId.systemDefault()).toLocalDate().toString())
                                }
                                FilledTonalButton(onClick = { picker = "time" }, modifier = Modifier.weight(1f)) { Text(time) }
                            } else TaskSettingRow("执行时间", time, { picker = "time" })
                        }
                        if (schedule.kind == ScheduleKind.WEEKLY) ConnectedMultiChoiceButtons(options = (1..7).toList(), selected = schedule.weekdays,
                            onToggle = { day, checked -> change(definition.copy(schedule = schedule.copy(weekdays = if (checked) schedule.weekdays + day else schedule.weekdays - day))) },
                            minHeight = 40.dp) { day, selected -> ConnectedChoiceLabel("一二三四五六日"[day - 1].toString(), selected) }
                        if (schedule.kind in setOf(ScheduleKind.DAILY, ScheduleKind.WEEKLY)) {
                            if (showDateBounds || schedule.startDate != null || schedule.endDate != null) Column {
                                TaskSettingRow("开始日期", schedule.startDate ?: "不限", { picker = "startDate" },
                                    onClear = if (schedule.startDate != null) { { change(definition.copy(schedule = schedule.copy(startDate = null))) } } else null)
                                TaskSettingRow("结束日期", schedule.endDate ?: "不限", { picker = "endDate" },
                                    onClear = if (schedule.endDate != null) { { change(definition.copy(schedule = schedule.copy(endDate = null))) } } else null)
                            } else TextButton(onClick = { showDateBounds = true }) { Text("设置日期范围（可选）") }
                        }
                    }
                }
            }
        }
    }
    if (definition != null) {
        ModelListSheet(modelPicker) { change(definition.copy(modelId = it.id.toString())) }
        if (picker == "assistant") (assistant ?: settings.assistants.firstOrNull())?.let { AssistantPickerSheet(settings, it, onAssistantSelected = { selected ->
            change(definition.copy(assistantId = selected.id.toString(), conversationId = null, userMessageId = null)); picker = null
        }, onDismiss = { picker = null }) }
        if (picker == "conversation" || picker == "message") {
            val choices = if (picker == "conversation") targetList.map { it.id.toString() to it.title.ifBlank { "未命名聊天" } }
                else target?.currentMessages.orEmpty().filter { it.role == MessageRole.USER }.map { it.id.toString() to it.toText().ifBlank { "附件消息" } }
            AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (picker == "conversation") "选择聊天" else "选择用户消息") },
                text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (choices.isEmpty()) item { Text("没有可选内容") }
                    items(choices, key = { it.first }) { (choiceId, label) -> TextButton(onClick = {
                        change(if (picker == "conversation") definition.copy(conversationId = choiceId, userMessageId = null) else definition.copy(userMessageId = choiceId)); picker = null
                    }) { Text(label, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                } }, confirmButton = { TextButton(onClick = { picker = null }) { Text("取消") } })
        }
        if (picker in setOf("onceDate", "startDate", "endDate")) {
            val schedule = definition.schedule
            val initial = when (picker) {
                "onceDate" -> Instant.ofEpochMilli(schedule.at).atZone(ZoneId.systemDefault()).toLocalDate()
                "startDate" -> schedule.startDate?.let(LocalDate::parse)
                else -> schedule.endDate?.let(LocalDate::parse)
            } ?: LocalDate.now()
            val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { picker = null }, confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { selected ->
                    val date = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    change(definition.copy(schedule = when (picker) {
                        "startDate" -> schedule.copy(startDate = date.toString())
                        "endDate" -> schedule.copy(endDate = date.toString())
                        else -> schedule.copy(at = date.atTime(Instant.ofEpochMilli(schedule.at).atZone(ZoneId.systemDefault()).toLocalTime()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }))
                }; picker = null
            }) { Text("确定") } }, dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } }) { DatePicker(state) }
        }
        if (picker == "time") {
            val schedule = definition.schedule
            val local = Instant.ofEpochMilli(schedule.at).atZone(ZoneId.systemDefault())
            val minutes = if (schedule.kind == ScheduleKind.ONCE) local.hour * 60 + local.minute else schedule.timeMinutes
            val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
            AlertDialog(onDismissRequest = { picker = null }, confirmButton = { TextButton(onClick = {
                change(definition.copy(schedule = if (schedule.kind == ScheduleKind.ONCE) schedule.copy(at = local.toLocalDate().atTime(state.hour, state.minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) else schedule.copy(timeMinutes = state.hour * 60 + state.minute)))
                picker = null
            }) { Text("确定") } }, dismissButton = { TextButton(onClick = { picker = null }) { Text("取消") } }, text = { TimePicker(state) })
        }
    }
    AutomationError(error) { error = null }
}

@Composable
private fun TaskSettingRow(label: String, value: String, onClick: () -> Unit, onClear: (() -> Unit)? = null, clearDescription: String = "清除$label") {
    Row(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClear != null) IconButton(onClick = onClear) { Icon(HugeIcons.Cancel01, clearDescription, modifier = Modifier.size(18.dp)) }
        else Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun modeLabel(mode: TaskMode) = when (mode) { TaskMode.NEW_CHAT -> "新建聊天"; TaskMode.FOLLOW_UP -> "继续聊天"; TaskMode.REGENERATE -> "重新生成" }
internal fun scheduleLabel(kind: ScheduleKind) = when (kind) { ScheduleKind.ONCE -> "单次"; ScheduleKind.DAILY -> "每天"; ScheduleKind.WEEKLY -> "每周"; ScheduleKind.INTERVAL -> "间隔" }
