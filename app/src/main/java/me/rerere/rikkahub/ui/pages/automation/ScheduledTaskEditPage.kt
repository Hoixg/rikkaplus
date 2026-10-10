// Adapted from xiaoyuili/Yuihub, AGPL-3.0.
package me.rerere.rikkahub.ui.pages.automation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.material3.Switch
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.model.ScheduledTaskFile
import me.rerere.rikkahub.data.model.encodeScheduledTaskFiles
import me.rerere.rikkahub.data.model.isScheduledTaskTextFile
import me.rerere.rikkahub.data.model.parseScheduledTaskFiles
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledTaskMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.ui.components.ai.AssistantPickerSheet
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.SystemPermissions
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Calendar
import kotlin.uuid.Uuid

/**
 * 定时任务编辑页（新建 / 修改共用）。
 *
 * 紧凑表单：名称与提示词 → 执行设置 → 时间安排 → 通知，底部固定保存。
 * 时间选择用 M3 的 TimePicker / DatePicker 弹窗，与系统观感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskEditPage(
    taskId: String?,
    defaultAssistantId: String? = null,
    vm: ScheduledTasksVM = koinViewModel(),
) {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val error by vm.error.collectAsStateWithLifecycle()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val allTasks by vm.tasks.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val existing = remember(taskId, allTasks) {
        taskId?.let { id -> allTasks.find { it.id == id } }
    }

    // 表单状态：现有任务回填，新建用默认值
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by remember(existing?.id) { mutableStateOf(existing?.prompt.orEmpty()) }
    var assistantId by remember(existing?.id) {
        mutableStateOf(
            existing?.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: defaultAssistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: settings.assistantId
        )
    }
    val conversationRepo: ConversationRepository = koinInject()
    var mode by remember(existing?.id) { mutableStateOf(existing?.mode ?: "NEW_CHAT") }
    var targetConversationId by remember(existing?.id) { mutableStateOf(existing?.targetConversationId) }
    var targetUserMessageId by remember(existing?.id) { mutableStateOf(existing?.targetUserMessageId) }
    var modelOverrideId by remember(existing?.id) { mutableStateOf(existing?.modelOverrideId) }
    var notify by remember(existing?.id) { mutableStateOf(existing?.notify ?: true) }
    var showPreview by remember(existing?.id) { mutableStateOf(existing?.showPreview ?: true) }
    var files by remember(existing?.id) { mutableStateOf(parseScheduledTaskFiles(existing?.filesJson ?: "[]")) }
    val createdFiles = remember(existing?.createdFilesJson) {
        parseScheduledTaskFiles(existing?.createdFilesJson ?: "[]")
    }
    var filesEnabled by remember(existing?.id) { mutableStateOf(existing?.filesEnabled ?: false) }
    var creationFolderUri by remember(existing?.id) { mutableStateOf(existing?.creationFolderUri) }
    var allowFileCreate by remember(existing?.id) { mutableStateOf(existing?.allowFileCreate ?: false) }
    var allowFileRead by remember(existing?.id) { mutableStateOf(existing?.allowFileRead ?: false) }
    var allowFileWrite by remember(existing?.id) { mutableStateOf(existing?.allowFileWrite ?: false) }
    var allowFileDelete by remember(existing?.id) { mutableStateOf(existing?.allowFileDelete ?: false) }
    var resetContextBeforeRun by remember(existing?.id) { mutableStateOf(existing?.resetContextBeforeRun ?: false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selected = uris.mapNotNull { uri ->
            val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
            if (!isScheduledTaskTextFile(name)) {
                vm.error.value = "仅支持 Markdown、TXT 等文本文件：$name"
                return@mapNotNull null
            }
            val resolver = context.contentResolver
            val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching { resolver.takePersistableUriPermission(uri, read or write) }
                .recoverCatching { resolver.takePersistableUriPermission(uri, read) }
                .isSuccess
            if (!granted) {
                vm.error.value = "无法取得文件的长期访问权限：$name"
                return@mapNotNull null
            }
            ScheduledTaskFile(uri.toString(), name)
        }
        files = (files + selected).distinctBy { it.uri }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val granted = runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (granted) creationFolderUri = uri.toString()
            else vm.error.value = "无法取得文件夹的长期读写权限"
        }
    }
    val creationFolderName = remember(creationFolderUri) {
        creationFolderUri?.let { uri ->
            runCatching { DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri))?.name }.getOrNull()
        }
    }
    var picker by remember { mutableStateOf<String?>(null) }
    val conversationFlow = remember(assistantId) { conversationRepo.getConversationsOfAssistant(assistantId) }
    val conversations by conversationFlow.collectAsStateWithLifecycle(emptyList())
    var targetConversation by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(targetConversationId) {
        targetConversation = targetConversationId?.let { runCatching { conversationRepo.getConversationById(Uuid.parse(it)) }.getOrNull() }
    }
    var scheduleType by remember(existing?.id) {
        mutableStateOf(
            existing?.scheduleType?.let { runCatching { ScheduleType.valueOf(it) }.getOrNull() }
                ?: ScheduleType.DAILY
        )
    }
    var intervalMinutes by remember(existing?.id) {
        mutableIntStateOf(existing?.intervalMinutes ?: ScheduledTasksVM.DEFAULT_INTERVAL_MINUTES)
    }
    var intervalInput by remember(existing?.id) { mutableStateOf((existing?.intervalMinutes ?: ScheduledTasksVM.DEFAULT_INTERVAL_MINUTES).toString()) }
    val intervalValid = intervalInput.toIntOrNull()?.let { it >= 15 } == true
    var timeOfDayMinutes by remember(existing?.id) {
        mutableIntStateOf(existing?.timeOfDayMinutes ?: ScheduledTasksVM.DEFAULT_TIME_OF_DAY_MINUTES)
    }
    var triggerAt by remember(existing?.id) {
        mutableLongStateOf(existing?.triggerAt ?: ScheduledTasksVM.defaultTriggerAt())
    }
    var weekdaysMask by remember(existing?.id) { mutableIntStateOf(existing?.weekdaysMask ?: 0x1f) }
    var startDate by remember(existing?.id) { mutableStateOf(existing?.startDate) }
    var endDate by remember(existing?.id) { mutableStateOf(existing?.endDate) }
    // Enable/disable is managed on the task list; editing must preserve its current state.
    val enabled = existing?.enabled ?: SystemPermissions.canScheduleExactAlarms(context)

    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf("once") }
    var showAssistantPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(error) { if (error != null) saving = false }

    val selectedAssistant: Assistant = settings.assistants.find { it.id == assistantId }
        ?: settings.getCurrentAssistant()

    val canChooseAssistant = defaultAssistantId == null
    val assistantName = selectedAssistant.name.ifBlank {
        stringResource(R.string.assistant_page_default_assistant)
    }
    val modelListState = rememberModelListState(
        modelId = modelOverrideId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    val canSave = !saving &&
        existing?.activeRunId == null &&
        name.isNotBlank() &&
        (mode == "REGENERATE" || prompt.isNotBlank()) &&
        (mode == "NEW_CHAT" || targetConversationId != null) &&
        (mode != "REGENERATE" || targetUserMessageId != null) &&
        (taskId == null || existing != null) &&
        settings.assistants.any { it.id == assistantId } &&
        (scheduleType != ScheduleType.INTERVAL || intervalValid) &&
        (scheduleType != ScheduleType.WEEKLY || weekdaysMask != 0)

    fun saveTask() {
        // 防重复提交：保存期间按钮禁用，避免连点创建多条任务
        if (saving) return
        vm.error.value = null
        saving = true
        if (existing == null) {
            vm.create(
                name = name.trim(),
                prompt = prompt.trim(),
                assistantId = assistantId,
                scheduleType = scheduleType,
                triggerAt = triggerAt,
                intervalMinutes = intervalMinutes,
                timeOfDayMinutes = timeOfDayMinutes,
                weekdaysMask = weekdaysMask,
                startDate = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) startDate else null,
                endDate = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) endDate else null,
                enabled = enabled,
                mode = mode, targetConversationId = if (mode == "NEW_CHAT") null else targetConversationId,
                targetUserMessageId = if (mode == "REGENERATE") targetUserMessageId else null,
                modelOverrideId = modelOverrideId, notify = notify, showPreview = showPreview,
                filesJson = encodeScheduledTaskFiles(files), filesEnabled = filesEnabled,
                creationFolderUri = creationFolderUri, allowFileCreate = allowFileCreate,
                allowFileRead = allowFileRead,
                allowFileWrite = allowFileWrite, allowFileDelete = allowFileDelete,
                resetContextBeforeRun = mode == "FOLLOW_UP" && resetContextBeforeRun,
                onDone = {
                    // 保存成功后回到任务列表，而不是停留在编辑页
                    navController.popBackStack()
                },
            )
        } else {
            vm.update(
                existing.copy(
                    name = name.trim(),
                    prompt = prompt.trim(),
                    assistantId = assistantId.toString(),
                    scheduleType = scheduleType.name,
                    triggerAt = triggerAt,
                    intervalMinutes = intervalMinutes,
                    timeOfDayMinutes = timeOfDayMinutes,
                    weekdaysMask = weekdaysMask,
                    startDate = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) startDate else null,
                    endDate = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) endDate else null,
                    enabled = enabled,
                    mode = mode, targetConversationId = if (mode == "NEW_CHAT") null else targetConversationId,
                    targetUserMessageId = if (mode == "REGENERATE") targetUserMessageId else null,
                    modelOverrideId = modelOverrideId, notify = notify, showPreview = showPreview,
                    filesJson = encodeScheduledTaskFiles(files), filesEnabled = filesEnabled,
                    creationFolderUri = creationFolderUri, allowFileCreate = allowFileCreate,
                    allowFileRead = allowFileRead,
                    allowFileWrite = allowFileWrite, allowFileDelete = allowFileDelete,
                    resetContextBeforeRun = mode == "FOLLOW_UP" && resetContextBeforeRun,
                ),
                onDone = {
                    navController.popBackStack()
                },
            )
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing == null) R.string.automation_edit_title_create
                            else R.string.automation_edit_title_edit
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            Surface(color = CustomColors.topBarColors.containerColor) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Button(
                        onClick = ::saveTask,
                        enabled = canSave,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = 52.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface, 0.08f),
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp),
                                alpha = if (canSave) 1f else 0f,
                            ),
                    ) {
                        Text("保存任务", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.automation_edit_name), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text(stringResource(R.string.automation_edit_name_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            trailingIcon = {
                                if (name.isNotEmpty()) {
                                    IconButton(onClick = { name = "" }) {
                                        Box(
                                            modifier = Modifier.size(20.dp).background(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                                                RoundedCornerShape(50),
                                            ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                HugeIcons.Cancel01,
                                                contentDescription = "清除任务名称",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.surface,
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (mode != "REGENERATE") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.automation_edit_prompt), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                placeholder = { Text(stringResource(R.string.automation_edit_prompt_hint)) },
                                minLines = 3,
                                maxLines = 8,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("执行设置", style = MaterialTheme.typography.titleMedium)
                    TaskSegmentedChoices(
                        labels = ScheduledTaskMode.entries.map { taskModeText(it.name) },
                        icons = listOf(HugeIcons.BubbleChat, HugeIcons.BubbleChat, HugeIcons.Refresh01),
                        selectedIndex = ScheduledTaskMode.entries.indexOfFirst { it.name == mode },
                        onSelected = { mode = ScheduledTaskMode.entries[it].name },
                    )
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                        if (mode != "NEW_CHAT") {
                            TaskSettingRow(
                                label = "目标会话",
                                value = conversations.find { it.id.toString() == targetConversationId }
                                    ?.title?.ifBlank { "未命名会话" } ?: "请选择",
                                onClick = { picker = "conversation" },
                            )
                            if (targetConversationId == null) {
                                Text(
                                    "请选择目标会话后保存",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            if (mode == "FOLLOW_UP") {
                                TaskToggleRow(
                                    label = "每次重置模型上下文",
                                    description = "保留会话记录，但本次执行只向模型发送当前任务的内容。",
                                    checked = resetContextBeforeRun,
                                    onCheckedChange = { resetContextBeforeRun = it },
                                )
                            }
                            if (mode == "REGENERATE") {
                                TaskSettingRow(
                                    label = "用户消息",
                                    value = targetConversation?.currentMessages
                                        ?.find { it.id.toString() == targetUserMessageId }
                                        ?.toText()?.take(60)?.ifBlank { "附件消息" } ?: "请选择",
                                    enabled = targetConversation != null,
                                    onClick = { picker = "message" },
                                )
                                Text(
                                    "复制截至所选用户消息的上下文，在新会话生成。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        TaskSettingRow(
                            label = stringResource(R.string.automation_edit_assistant),
                            value = assistantName,
                            onClick = if (canChooseAssistant) ({ showAssistantPicker = true }) else null,
                            valueIcon = {
                                UIAvatar(name = assistantName, value = selectedAssistant.avatar, modifier = Modifier.size(32.dp))
                            },
                        )
                        TaskSettingRow(
                            label = "本次模型",
                            value = modelListState.currentModel?.displayName
                                ?: if (modelOverrideId == null) "跟随助手" else "模型不可用",
                            onClick = { modelListState.open() },
                            onClear = if (modelOverrideId != null) ({ modelOverrideId = null }) else null,
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("任务文件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(HugeIcons.Add01, contentDescription = "添加文本文件")
                        }
                    }
                    Text(
                        "仅此任务可访问清单中的文本文件；创建位置内的其他文件不会自动授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    files.forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { files = files.filterNot { it.uri == file.uri } }) {
                                Icon(HugeIcons.Delete01, contentDescription = "移除${file.name}")
                            }
                        }
                    }
                    createdFiles.forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${file.name} · 已创建", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(
                                onClick = { existing?.let { vm.forgetCreatedFile(it, file.uri) } },
                                enabled = existing?.activeRunId == null,
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = "移除${file.name}的任务访问权限")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TaskToggleRow("启用任务文件", null, filesEnabled, { filesEnabled = it })
                    TaskSettingRow(
                        label = "创建位置",
                        value = creationFolderName ?: "未选择文件夹",
                        onClick = { folderPicker.launch(null) },
                        onClear = if (creationFolderUri != null) ({ creationFolderUri = null; allowFileCreate = false }) else null,
                    )
                    TaskToggleRow("创建文件", null, allowFileCreate, { allowFileCreate = it }, enabled = filesEnabled)
                    TaskToggleRow("读取文件", null, allowFileRead, { allowFileRead = it }, enabled = filesEnabled)
                    TaskToggleRow("修改文件", null, allowFileWrite, { allowFileWrite = it }, enabled = filesEnabled)
                    TaskToggleRow("删除文件", null, allowFileDelete, { allowFileDelete = it }, enabled = filesEnabled)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("时间安排", style = MaterialTheme.typography.titleMedium)
                    TaskSegmentedChoices(
                        labels = ScheduleType.entries.map { scheduleTypeLabel(it) },
                        icons = listOf(HugeIcons.Calendar03, HugeIcons.Clock01, HugeIcons.Repeat, HugeIcons.Calendar03),
                        selectedIndex = ScheduleType.entries.indexOf(scheduleType),
                        onSelected = { index ->
                            val type = ScheduleType.entries[index]
                            if (type == ScheduleType.WEEKLY && scheduleType != ScheduleType.WEEKLY && existing?.scheduleType != ScheduleType.WEEKLY.name) {
                                weekdaysMask = 0x1f
                            }
                            scheduleType = type
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                    when (scheduleType) {
                        ScheduleType.ONCE -> {
                            Column {
                                TaskSettingRow(
                                    label = "执行日期",
                                    value = java.text.SimpleDateFormat("yyyy-MM-dd", locale).format(java.util.Date(triggerAt)),
                                    onClick = { dateTarget = "once"; showDatePicker = true },
                                    icon = HugeIcons.Calendar03,
                                )
                                TaskSettingRow(
                                    label = "执行时间",
                                    value = java.text.SimpleDateFormat("HH:mm", locale).format(java.util.Date(triggerAt)),
                                    onClick = { showTimePicker = true },
                                    icon = HugeIcons.Clock01,
                                    emphasizeValue = true,
                                )
                            }
                        }
                        ScheduleType.DAILY -> {
                            TaskSettingRow(
                                label = stringResource(R.string.automation_edit_daily_at),
                                value = "%02d:%02d".format(
                                    ScheduledTasksVM.minutesToHour(timeOfDayMinutes),
                                    ScheduledTasksVM.minutesToMinute(timeOfDayMinutes),
                                ),
                                onClick = { showTimePicker = true },
                                icon = HugeIcons.Clock01,
                                emphasizeValue = true,
                            )
                        }
                        ScheduleType.WEEKLY -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("执行日期", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { weekdaysMask = 0x1f }) { Text("工作日") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                                        FilterChip(
                                            selected = weekdaysMask and (1 shl index) != 0,
                                            onClick = { weekdaysMask = weekdaysMask xor (1 shl index) },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                if (weekdaysMask == 0) {
                                    Text("请至少选择一天", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                TaskSettingRow(
                                    label = "执行时间",
                                    value = "%02d:%02d".format(
                                        ScheduledTasksVM.minutesToHour(timeOfDayMinutes),
                                        ScheduledTasksVM.minutesToMinute(timeOfDayMinutes),
                                    ),
                                    onClick = { showTimePicker = true },
                                    icon = HugeIcons.Clock01,
                                    emphasizeValue = true,
                                )
                            }
                        }
                        ScheduleType.INTERVAL -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(30, 60, 180, 360, 720, 1440).chunked(3).forEach { presets ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        presets.forEach { minutes ->
                                            FilterChip(
                                                selected = intervalMinutes == minutes,
                                                onClick = { intervalMinutes = minutes; intervalInput = minutes.toString() },
                                                label = { Text(intervalLabel(minutes)) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = intervalInput,
                                    onValueChange = { input ->
                                        intervalInput = input
                                        input.toIntOrNull()?.let { intervalMinutes = it }
                                    },
                                    isError = !intervalValid,
                                    supportingText = { if (!intervalValid) Text("请输入至少 15 分钟的整数") },
                                    label = { Text(stringResource(R.string.automation_edit_interval_custom)) },
                                    suffix = { Text(stringResource(R.string.automation_edit_minutes)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TaskDateRangeField(
                                label = "开始日期",
                                date = startDate,
                                onClick = { dateTarget = "start"; showDatePicker = true },
                                onClear = { startDate = null },
                                modifier = Modifier.weight(1f),
                            )
                            TaskDateRangeField(
                                label = "结束日期",
                                date = endDate,
                                onClick = { dateTarget = "end"; showDatePicker = true },
                                onClear = { endDate = null },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item {
                TaskSettingRow(
                    label = "通知提醒",
                    value = when { !notify -> "不通知"; showPreview -> "显示内容"; else -> "仅显示状态" },
                    onClick = { picker = "notification" },
                    icon = HugeIcons.Notification01,
                )
            }
            if (existing == null && !enabled) item {
                Text(
                    "未授权精确闹钟，保存后暂不自动执行；可在任务列表立即执行或授权后启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (existing?.activeRunId != null) item {
                Text("当前任务正在执行，取消后才能修改内容。", color = MaterialTheme.colorScheme.error)
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }

    if (canChooseAssistant && showAssistantPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = selectedAssistant,
            onAssistantSelected = { assistant ->
                assistantId = assistant.id
                targetConversationId = null
                targetUserMessageId = null
                showAssistantPicker = false
            },
            onDismiss = { showAssistantPicker = false },
        )
    }
    ModelListSheet(
        state = modelListState,
        onSelect = { model ->
            modelOverrideId = model.takeIf { it.modelId.isNotBlank() }?.id?.toString()
        },
    )

    if (picker != null) {
        val choices = when (picker) {
            "conversation" -> conversations.map { it.id.toString() to it.title.ifBlank { "未命名会话" } }
            "message" -> targetConversation?.currentMessages.orEmpty().filter { it.role == MessageRole.USER }.map { it.id.toString() to it.toText().take(100).ifBlank { "附件消息" } }
            "notification" -> listOf("off" to "不通知", "status" to "仅显示状态", "preview" to "显示内容")
            else -> emptyList()
        }
        TaskChoiceDialog("选择${when(picker) { "conversation" -> "会话"; "message" -> "用户消息"; "notification" -> "通知提醒"; else -> "模型" }}", choices, { id ->
            when (picker) {
                "conversation" -> { targetConversationId = id; targetUserMessageId = null }
                "message" -> targetUserMessageId = id
                "notification" -> {
                    notify = id != "off"
                    if (notify) showPreview = id == "preview"
                }
                else -> Unit
            }
            picker = null
        }, { picker = null })
    }
    if (showTimePicker) {
        // 复用 M3 TimePicker 弹窗；已有任务可能同时含日期与时刻
        val initialHour = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) {
            ScheduledTasksVM.minutesToHour(timeOfDayMinutes)
        } else {
            remember(triggerAt) {
                Calendar.getInstance().apply { timeInMillis = triggerAt }.get(Calendar.HOUR_OF_DAY)
            }
        }
        val initialMinute = if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) {
            ScheduledTasksVM.minutesToMinute(timeOfDayMinutes)
        } else {
            remember(triggerAt) {
                Calendar.getInstance().apply { timeInMillis = triggerAt }.get(Calendar.MINUTE)
            }
        }
        val timeState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (scheduleType == ScheduleType.DAILY || scheduleType == ScheduleType.WEEKLY) {
                            timeOfDayMinutes = ScheduledTasksVM.timeOfDayToMinutes(
                                timeState.hour, timeState.minute
                            )
                        } else {
                            triggerAt = Calendar.getInstance().apply {
                                timeInMillis = triggerAt
                                set(Calendar.HOUR_OF_DAY, timeState.hour)
                                set(Calendar.MINUTE, timeState.minute)
                                set(Calendar.SECOND, 0)
                            }.timeInMillis
                        }
                        showTimePicker = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = (when (dateTarget) {
                "start" -> startDate?.let(java.time.LocalDate::parse)
                "end" -> endDate?.let(java.time.LocalDate::parse)
                else -> java.time.Instant.ofEpochMilli(triggerAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            } ?: java.time.LocalDate.now()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { selected ->
                            // 保留原有时刻，只替换日期部分
                            val old = Calendar.getInstance().apply { timeInMillis = triggerAt }
                            val date = java.time.Instant.ofEpochMilli(selected).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            when (dateTarget) {
                                "start" -> startDate = date.toString()
                                "end" -> endDate = date.toString()
                                else -> triggerAt = date.atTime(old.get(Calendar.HOUR_OF_DAY), old.get(Calendar.MINUTE))
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            }
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

/** Equal-width choices remain on one line; long translations or large fonts can scroll. */
@Composable
private fun TaskSegmentedChoices(
    labels: List<String>,
    icons: List<ImageVector>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.bodyMedium
    val widestLabel = labels.maxOf { label ->
        textMeasurer.measure(AnnotatedString(label), style = textStyle, softWrap = false, maxLines = 1).size.width
    }
    // 16dp horizontal padding + 20dp icon + 8dp icon/label gap.
    val minimumItemWidth = with(density) { widestLabel.toDp() + 44.dp }.coerceAtLeast(48.dp)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth.coerceAtLeast(minimumItemWidth * labels.size)
        val scrollState = rememberScrollState()
        val viewportWidth = with(density) { maxWidth.roundToPx() }
        val itemWidth = with(density) { rowWidth.toPx() } / labels.size
        LaunchedEffect(selectedIndex, rowWidth, maxWidth, scrollState.maxValue) {
            if (selectedIndex in labels.indices) {
                val itemStart = (itemWidth * selectedIndex).toInt()
                val itemEnd = (itemWidth * (selectedIndex + 1)).toInt()
                val target = when {
                    itemStart < scrollState.value -> itemStart
                    itemEnd > scrollState.value + viewportWidth -> itemEnd - viewportWidth
                    else -> scrollState.value
                }
                scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
            }
        }
        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(rowWidth)) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedIndex == index,
                        onClick = { onSelected(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, labels.size, RoundedCornerShape(8.dp)),
                        icon = { Icon(icons[index], contentDescription = null, modifier = Modifier.size(20.dp)) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            activeContentColor = MaterialTheme.colorScheme.primary,
                            activeBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                            inactiveContainerColor = Color.Transparent,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, style = textStyle, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TaskSettingRow(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    emphasizeValue: Boolean = false,
    valueIcon: (@Composable () -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(if (valueIcon == null) 0.4f else 0.28f),
            )
            Row(
                modifier = Modifier.weight(if (valueIcon == null) 0.6f else 0.72f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                valueIcon?.invoke()
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        emphasizeValue -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = valueIcon != null),
                )
                if (onClick != null) {
                    Icon(
                        HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onClear != null) {
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(HugeIcons.Cancel01, contentDescription = "清除$label", modifier = Modifier.size(18.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
    }
}

@Composable
private fun TaskDateRangeField(
    label: String,
    date: String?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = CustomColors.topBarColors.containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, end = if (date == null) 12.dp else 0.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(date ?: "不限", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (date != null) {
                    IconButton(onClick = onClear) {
                        Icon(HugeIcons.Cancel01, contentDescription = "清除$label", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun scheduleTypeLabel(type: ScheduleType): String = when (type) {
    ScheduleType.ONCE -> stringResource(R.string.automation_edit_type_once)
    ScheduleType.DAILY -> stringResource(R.string.automation_edit_type_daily)
    ScheduleType.INTERVAL -> stringResource(R.string.automation_edit_type_interval)
    ScheduleType.WEEKLY -> "每周"
}

@Composable
private fun intervalLabel(minutes: Int): String = when (minutes) {
    30 -> stringResource(R.string.automation_edit_interval_30m)
    60 -> stringResource(R.string.automation_edit_interval_1h)
    180 -> stringResource(R.string.automation_edit_interval_3h)
    360 -> stringResource(R.string.automation_edit_interval_6h)
    720 -> stringResource(R.string.automation_edit_interval_12h)
    else -> stringResource(R.string.automation_edit_interval_24h)
}
