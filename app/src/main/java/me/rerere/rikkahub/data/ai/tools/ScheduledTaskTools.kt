// Adapted from xiaoyuili/Yuihub, AGPL-3.0.
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.ScheduleType
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledTaskSchedule
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import kotlin.uuid.Uuid

/** scheduled_task 工具的 name，子代理过滤与 persona 白名单按名引用 */
const val SCHEDULED_TASK_TOOL_NAME = "scheduled_task"

/**
 * 让模型在对话中管理**当前助手自己的**定时任务（创建/编辑/删除/启用停用）。
 *
 * 与 UI 的约束对齐：
 * - 所有操作强制绑定 [assistantId]，模型无法读到也无法改到别的助手的任务；
 *   试图传别的助手 id 只会得到 “not found” 而不是越权成功。
 * - 新建任务的触发时刻与 UI 一致：DAILY 用 time_of_day（HH:mm），INTERVAL 用 interval_minutes，
 *   ONCE 用 trigger_at（"yyyy-MM-dd HH:mm"）。
 * - 写操作走 ScheduledTaskRepository，精确闹钟调度自动同步。
 *
 * 普通检查请求立即执行，只有明确的定时管理请求才可申请写操作。
 */
fun createScheduledTaskTools(
    repository: ScheduledTaskRepository,
    assistantId: Uuid,
    conversationRepository: ConversationRepository? = null,
    getSettings: () -> Settings = { Settings() },
    scheduledExecution: Boolean = false,
    getMessages: () -> List<UIMessage> = { emptyList() },
): List<Tool> = listOf(
    Tool(
        name = SCHEDULED_TASK_TOOL_NAME,
        description = """
            Manage scheduled tasks that belong to THIS assistant only (tasks of other assistants are not accessible).
            `action`: list | get | options | create | update | delete | set_enabled | run_now | history | cancel_run.
            Use get to read complete settings; options returns configured models and this assistant's conversations, plus user messages when target_conversation_id is supplied. Use id when renaming a task.
            Only use write actions when the human explicitly requests scheduling or task management. Ordinary requests such as "help me check" / "帮我检查某件事" mean do the work now, not create a schedule. An empty list is not an instruction to create a task.
            Schedule types: DAILY (`time_of_day` "HH:mm"), WEEKLY (`time_of_day` and `weekdays` 1=Mon..7=Sun), INTERVAL (`interval_minutes` >= 15), ONCE (`trigger_at` "yyyy-MM-dd HH:mm").
            DAILY and WEEKLY support optional inclusive `start_date` and `end_date` (yyyy-MM-dd). Null clears a date bound. Creating an enabled task requires exact-alarm permission; `enabled=false` saves a draft.
            create needs `name` + `prompt` (+ one schedule spec); update only needs the fields to change.
            Execution modes: NEW_CHAT (default), FOLLOW_UP (target_conversation_id), REGENERATE (target_conversation_id and target_user_message_id; copies context to a new chat; prompt may be empty). Null clears optional IDs. Optional model_override_id applies only to the run. notify/show_preview default true. create/update/run_now require separate human approval within 30 seconds; timeout means denied. Never retry a denied/timed-out request or recreate it under another name. run_now does not change the schedule. Busy conversations wait until idle.
            ${if (scheduledExecution) "This is an execution of an EXISTING scheduled task. Complete its content now. Only list/get/options/history are allowed. Never create, change, delete, enable, cancel, or trigger scheduled tasks." else ""}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                (if (scheduledExecution) scheduledReadActions else scheduledReadActions +
                                    setOf("create", "update", "delete", "set_enabled", "run_now", "cancel_run")).forEach { add(it) }
                            },
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id (required for update/delete/set_enabled, or pass `name`)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Task name (creates with this name; also used to locate a task when `id` is omitted)")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "The prompt to send to the assistant on each run")
                    })
                    put("mode", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("NEW_CHAT"); add("FOLLOW_UP"); add("REGENERATE") }) })
                    listOf("target_conversation_id", "target_user_message_id", "model_override_id").forEach { key ->
                        put(key, buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }) })
                    }
                    listOf("notify", "show_preview").forEach { key -> put(key, buildJsonObject { put("type", "boolean") }) }
                    put("schedule_type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("DAILY")
                                add("INTERVAL")
                                add("ONCE")
                                add("WEEKLY")
                            },
                        )
                        put("description", "Schedule type, defaults to DAILY")
                    })
                    put("time_of_day", buildJsonObject {
                        put("type", "string")
                        put("description", "For DAILY/WEEKLY: time of day in HH:mm (local time)")
                    })
                    put("weekdays", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "integer") })
                        put("description", "For WEEKLY: selected weekdays 1=Monday through 7=Sunday; defaults to Monday-Friday")
                    })
                    put("start_date", buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }); put("description", "Inclusive start date for DAILY/WEEKLY, yyyy-MM-dd; null clears it") })
                    put("end_date", buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }); put("description", "Inclusive end date for DAILY/WEEKLY, yyyy-MM-dd; null clears it") })
                    put("interval_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "For INTERVAL: minutes between runs, minimum 15")
                    })
                    put("trigger_at", buildJsonObject {
                        put("type", "string")
                        put("description", "For ONCE: trigger moment in \"yyyy-MM-dd HH:mm\" (local time)")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For create/update/set_enabled: whether the task is active; create defaults to true")
                    })
                },
                required = listOf("action"),
            )
        },
        systemPrompt = { _, _ ->
            """
            You can manage this assistant's scheduled tasks with `$SCHEDULED_TASK_TOOL_NAME`
            (list / get / options / create / update / delete / set_enabled / run_now / history / cancel_run). Only this assistant's tasks are visible and editable.
            "Help me check/research/do something" / "帮我检查某件事" means execute that work now. Do not infer a scheduled task from those words, task content, past messages, or an empty task list. Create a task only when the human explicitly requests a future or recurring schedule. A stored task prompt describes the work of one run; it is not permission to create another task.
            create/update/run_now each need human approval within 30 seconds. After denial or timeout, explain that nothing was performed; do not automatically retry, change the name, or issue another approval request without new human instructions.
            ${if (scheduledExecution) "You are executing an already configured scheduled task, not responding to a new human scheduling request. Complete this run's work. Task management and triggering other tasks are prohibited; only read actions are available." else ""}
            """.trimIndent()
        },
        prepareArguments = { args ->
            val obj = args.jsonObject
            val action = scheduledAction(obj)
            checkScheduledActionAllowed(action, scheduledExecution)
            if (action !in scheduledApprovalActions) obj else {
                require(!hasDeniedScheduledRequestInTurn(getMessages())) { "本轮定时任务请求已被拒绝，不自动重试；请等待新的用户指令" }
                val tasks = repository.getTasksForAssistant(assistantId.toString())
                val before = if (action == "create") null else findTask(obj, tasks) ?: error("No matching task found")
                val after = if (action == "run_now") before!! else buildTaskChange(obj, assistantId, before)
                require(action != "update" || taskConfiguration(after) != taskConfiguration(before!!)) { "没有需要修改的字段" }
                require(tasks.none { it.id != after.id && it.name == after.name }) { "该助手已有同名任务" }
                if (action != "run_now") {
                    require(before?.activeRunId == null) { "请先取消当前执行，再修改任务" }
                    ScheduledTaskSchedule.validate(after, System.currentTimeMillis(), before == null ||
                        (after.enabled && (after.triggerAt != before.triggerAt || after.scheduleType != before.scheduleType || !before.enabled)))
                    require(!after.enabled || before?.enabled == true || repository.hasExactAlarmPermission()) { "请先允许精确闹钟，或保存为停用任务" }
                }
                val settings = getSettings()
                validateTaskTargets(after, conversationRepository, settings)
                val beforeSnapshot = before?.let { approvalTaskConfiguration(it, conversationRepository, settings) }
                val afterSnapshot = approvalTaskConfiguration(after, conversationRepository, settings)
                buildJsonObject {
                    obj.filterKeys { it != SCHEDULED_PREPARED_ARGUMENT }.forEach { (key, value) -> put(key, value) }
                    put("id", after.id)
                    put(SCHEDULED_PREPARED_ARGUMENT, buildJsonObject {
                        put("request_id", Uuid.random().toString())
                        put("expected_revision", before?.revision)
                        put("before", beforeSnapshot ?: JsonNull)
                        put("after", afterSnapshot)
                    })
                }
            }
        },
        needsApproval = { scheduledAction(it) in scheduledApprovalActions },
        execute = { args ->
            val obj = args.jsonObject
            val action = scheduledAction(obj)
            checkScheduledActionAllowed(action, scheduledExecution)
            val tasks = repository.getTasksForAssistant(assistantId.toString())
            when (action) {
                "list" -> listOf(UIMessagePart.Text(renderTasks(tasks, repository.hasExactAlarmPermission())))
                "get" -> listOf(UIMessagePart.Text(findTask(obj, tasks)?.let { taskConfiguration(it).toString() } ?: "No matching task found"))
                "options" -> listOf(UIMessagePart.Text(taskOptions(obj, assistantId, conversationRepository, getSettings()).toString()))
                "create", "update", "run_now" -> {
                    val prepared = obj[SCHEDULED_PREPARED_ARGUMENT]?.jsonObject ?: error("审批配置缺失，请重新请求")
                    val task = taskFromConfiguration(prepared.getValue("after").jsonObject)
                    require(task.assistantId == assistantId.toString()) { "No matching task found" }
                    validateTaskTargets(task, conversationRepository, getSettings())
                    val revision = prepared["expected_revision"]?.jsonPrimitive?.contentOrNull
                    require(action == "create" || !revision.isNullOrBlank()) { "审批版本缺失，请重新请求" }
                    if (action == "run_now") {
                        val result = repository.runNow(task.id, revision, prepared.getValue("request_id").jsonPrimitive.content)
                        listOf(UIMessagePart.Text("Run ${result.activeRunId ?: result.lastRunId}: ${result.lastRunStatus}"))
                    } else {
                        repository.upsert(task, expectedRevision = revision, approvedCreate = action == "create")
                        listOf(UIMessagePart.Text("${if (action == "create") "Created" else "Updated"} task '${task.name}' (id=${task.id}, ${describe(task)})"))
                    }
                }

                "delete" -> deleteTask(repository, obj, tasks)

                "set_enabled" -> setEnabled(repository, obj, tasks)
                "history", "cancel_run" -> {
                    val target = findTask(obj, tasks)
                    if (target == null) listOf(UIMessagePart.Text("No matching task found")) else {
                        val text = when (action) {
                            "cancel_run" -> { repository.cancelRun(target.id); "Cancelled current run" }
                            else -> buildJsonArray {
                                repository.history(target.id).forEach { run -> add(buildJsonObject {
                                    put("id", run.id); put("source", run.source); put("due_at", run.dueAt); put("status", run.status)
                                    put("conversation_id", run.conversationId); put("preview", run.preview); put("error", run.error)
                                }) }
                            }.toString()
                        }
                        listOf(UIMessagePart.Text(text))
                    }
                }

                else -> listOf(UIMessagePart.Text("Unknown action '$action'"))
            }
        },
    ),
)

internal fun checkScheduledActionAllowed(action: String, scheduledExecution: Boolean) {
    require(!scheduledExecution || action in scheduledReadActions) { "定时任务执行期间禁止变更或触发定时任务" }
}

internal fun buildTaskChange(obj: JsonObject, assistantId: Uuid, before: ScheduledTaskEntity? = null,
    now: Long = System.currentTimeMillis()): ScheduledTaskEntity {
    val base = before ?: ScheduledTaskEntity(id = Uuid.random().toString(), name = "", prompt = "",
        assistantId = assistantId.toString(), createdAt = now, updatedAt = now, revision = Uuid.random().toString())
    fun text(key: String, old: String): String = if (key !in obj) old else
        obj[key]?.jsonPrimitive?.contentOrNull?.trim() ?: error("$key must be a string")
    val schedule = parseSchedule(obj, before)
    val changed = base.copy(name = text("name", base.name), prompt = text("prompt", base.prompt),
        scheduleType = schedule.type.name, triggerAt = schedule.triggerAt, intervalMinutes = schedule.intervalMinutes,
        timeOfDayMinutes = schedule.timeOfDayMinutes, weekdaysMask = schedule.weekdaysMask,
        startDate = schedule.startDate, endDate = schedule.endDate)
    return applyExecutionFields(changed, obj)
}

/** Raw, round-trippable configuration; no runtime ownership fields or provider credentials. */
internal fun taskConfiguration(task: ScheduledTaskEntity): JsonObject = buildJsonObject {
    put("id", task.id); put("assistant_id", task.assistantId); put("name", task.name); put("prompt", task.prompt)
    put("schedule_type", task.scheduleType); put("trigger_at_ms", task.triggerAt)
    put("interval_minutes", task.intervalMinutes); put("time_of_day_minutes", task.timeOfDayMinutes)
    put("time_of_day", "%02d:%02d".format(Locale.ROOT, task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60))
    put("weekdays", buildJsonArray { (1..7).filter { task.weekdaysMask and (1 shl (it - 1)) != 0 }.forEach { add(it) } })
    if (task.scheduleType == "ONCE") put("trigger_at", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(task.triggerAt)))
    put("weekdays_mask", task.weekdaysMask); put("start_date", task.startDate); put("end_date", task.endDate)
    put("mode", task.mode); put("target_conversation_id", task.targetConversationId)
    put("target_user_message_id", task.targetUserMessageId); put("model_override_id", task.modelOverrideId)
    put("enabled", task.enabled); put("notify", task.notify); put("show_preview", task.showPreview)
    put("created_at", task.createdAt); put("revision", task.revision); put("schedule", describe(task))
}

internal fun taskFromConfiguration(obj: JsonObject): ScheduledTaskEntity {
    fun text(key: String) = obj.getValue(key).jsonPrimitive.content
    fun optional(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
    return ScheduledTaskEntity(id = text("id"), name = text("name"), prompt = text("prompt"),
        assistantId = text("assistant_id"), scheduleType = text("schedule_type"),
        triggerAt = obj.getValue("trigger_at_ms").jsonPrimitive.longOrNull ?: error("Invalid trigger"),
        intervalMinutes = obj.getValue("interval_minutes").jsonPrimitive.intOrNull ?: error("Invalid interval"),
        timeOfDayMinutes = obj.getValue("time_of_day_minutes").jsonPrimitive.intOrNull ?: error("Invalid time"),
        weekdaysMask = obj.getValue("weekdays_mask").jsonPrimitive.intOrNull ?: error("Invalid weekdays"),
        startDate = optional("start_date"), endDate = optional("end_date"),
        mode = text("mode"), targetConversationId = optional("target_conversation_id"),
        targetUserMessageId = optional("target_user_message_id"), modelOverrideId = optional("model_override_id"),
        enabled = text("enabled").toBooleanStrict(), notify = text("notify").toBooleanStrict(),
        showPreview = text("show_preview").toBooleanStrict(), createdAt = text("created_at").toLong(),
        updatedAt = System.currentTimeMillis(), revision = text("revision"))
}

private suspend fun validateTaskTargets(task: ScheduledTaskEntity, conversations: ConversationRepository?, settings: Settings) {
    val conversation = if (task.mode == "NEW_CHAT") null else
        task.targetConversationId?.let { conversations?.getConversationById(Uuid.parse(it)) }
    validateTaskTargetSelection(task, conversation, settings)
}

private suspend fun approvalTaskConfiguration(task: ScheduledTaskEntity, conversations: ConversationRepository?, settings: Settings): JsonObject {
    val target = task.targetConversationId?.let { Uuid.parse(it) }?.let { conversations?.getConversationById(it) }
    return buildJsonObject {
        taskConfiguration(task).forEach { (key, value) -> put(key, value) }
        put("target_conversation_title", target?.title)
        put("model_name", task.modelOverrideId?.let { settings.findModelById(Uuid.parse(it))?.displayName })
    }
}

internal fun validateTaskTargetSelection(task: ScheduledTaskEntity, conversation: Conversation?, settings: Settings) {
    task.modelOverrideId?.let { require(settings.findModelById(Uuid.parse(it)) != null) { "任务模型已删除或未配置" } }
    if (task.mode == "NEW_CHAT") return
    require(conversation != null && conversation.id.toString() == task.targetConversationId) { "目标会话已删除" }
    require(conversation.assistantId.toString() == task.assistantId) { "目标会话不属于任务助手" }
    if (task.mode == "REGENERATE") require(conversation.currentMessages.any {
        it.id.toString() == task.targetUserMessageId && it.role == MessageRole.USER
    }) { "目标用户消息已删除或分支已改变" }
}

private suspend fun taskOptions(obj: JsonObject, assistantId: Uuid, conversations: ConversationRepository?, settings: Settings): JsonObject {
    val targetId = obj["target_conversation_id"]?.jsonPrimitive?.contentOrNull
    val target = targetId?.let { conversations?.getConversationById(Uuid.parse(it)) ?: error("目标会话已删除") }
    require(target == null || target.assistantId == assistantId) { "目标会话不属于当前助手" }
    return buildJsonObject {
        put("models", buildJsonArray { settings.providers.forEach { provider -> provider.models.forEach { model ->
            add(buildJsonObject { put("id", model.id.toString()); put("name", model.displayName); put("provider", provider.name) })
        } } })
        put("conversations", buildJsonArray { conversations?.getRecentConversations(assistantId, 30)?.forEach { conversation ->
            add(buildJsonObject { put("id", conversation.id.toString()); put("title", conversation.title) })
        } })
        put("user_messages", buildJsonArray { target?.currentMessages?.filter { it.role == MessageRole.USER }?.forEach { message ->
            add(buildJsonObject { put("id", message.id.toString()); put("preview", message.toText().take(300)) })
        } })
    }
}

private suspend fun deleteTask(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return listOf(UIMessagePart.Text("No matching task found"))
    repository.delete(target)
    return listOf(UIMessagePart.Text("Deleted task '${target.name}'"))
}

private suspend fun setEnabled(
    repository: ScheduledTaskRepository,
    obj: JsonObject,
    tasks: List<ScheduledTaskEntity>,
): List<UIMessagePart> {
    val target = findTask(obj, tasks) ?: return listOf(UIMessagePart.Text("No matching task found"))
    val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.trim()?.toBooleanStrictOrNull()
        ?: return listOf(UIMessagePart.Text("enabled (true/false) is required for action=set_enabled"))
    repository.setEnabled(target.id, enabled, System.currentTimeMillis())
    return listOf(
        UIMessagePart.Text("${if (enabled) "Enabled" else "Disabled"} task '${target.name}'"),
    )
}

/** 定位任务：优先 id，其次唯一 name；只在传入的（已按助手限定的）列表内查找 */
internal fun findTask(obj: JsonObject, tasks: List<ScheduledTaskEntity>): ScheduledTaskEntity? {
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!id.isNullOrEmpty()) {
        return tasks.firstOrNull { it.id == id }
    }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
    if (!name.isNullOrEmpty()) {
        return tasks.firstOrNull { it.name == name }
    }
    return null
}

internal data class ParsedSchedule(
    val type: ScheduleType,
    val triggerAt: Long,
    val intervalMinutes: Int,
    val timeOfDayMinutes: Int,
    val weekdaysMask: Int,
    val startDate: String?,
    val endDate: String?,
)

/**
 * 解析调度参数。[fallback] 为更新场景下的原任务（未给的字段沿用原值）；
 * 新建时兜底为 DAILY 09:00 / 24h。
 */
internal fun parseSchedule(obj: JsonObject, fallback: ScheduledTaskEntity? = null): ParsedSchedule {
    val type = obj["schedule_type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        ScheduleType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid schedule_type '$raw' (expected DAILY / WEEKLY / INTERVAL / ONCE)")
    } ?: fallback?.let { runCatching { ScheduleType.valueOf(it.scheduleType) }.getOrDefault(ScheduleType.DAILY) }
        ?: ScheduleType.DAILY

    var triggerAt = fallback?.triggerAt ?: 0L
    var intervalMinutes = fallback?.intervalMinutes ?: DEFAULT_INTERVAL_MINUTES
    var timeOfDayMinutes = fallback?.timeOfDayMinutes ?: DEFAULT_TIME_OF_DAY_MINUTES
    var weekdaysMask = fallback?.weekdaysMask ?: 0x1f
    var startDate = fallback?.startDate
    var endDate = fallback?.endDate

    when (type) {
        ScheduleType.DAILY, ScheduleType.WEEKLY -> {
            obj["time_of_day"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                timeOfDayMinutes = parseTimeOfDay(text).coerceIn(0, 1439)
            }
            if (type == ScheduleType.WEEKLY) {
                if (fallback?.scheduleType != ScheduleType.WEEKLY.name && "weekdays" !in obj) weekdaysMask = 0x1f
                obj["weekdays"]?.let { value ->
                    val days = value.jsonArray.map { it.jsonPrimitive.intOrNull ?: error("weekdays must contain integers") }
                    require(days.isNotEmpty() && days.all { it in 1..7 }) { "weekdays must contain 1..7" }
                    weekdaysMask = days.fold(0) { mask, day -> mask or (1 shl (day - 1)) }
                }
            } else require("weekdays" !in obj) { "weekdays requires WEEKLY" }
            fun date(key: String, prior: String?): String? = if (key !in obj) prior else obj[key].let { value ->
                if (value == null || value is JsonNull) null else value.jsonPrimitive.content.also { LocalDate.parse(it) }
            }
            startDate = date("start_date", startDate)
            endDate = date("end_date", endDate)
        }

        ScheduleType.INTERVAL -> {
            require("weekdays" !in obj && "start_date" !in obj && "end_date" !in obj) { "date fields require DAILY or WEEKLY" }
            obj["interval_minutes"]?.let { value ->
                val minutes = value.jsonPrimitive.intOrNull ?: error("interval_minutes must be an integer")
                require(minutes >= 15) { "interval_minutes must be at least 15" }
                intervalMinutes = minutes
            }
        }

        ScheduleType.ONCE -> {
            require("weekdays" !in obj && "start_date" !in obj && "end_date" !in obj) { "date fields require DAILY or WEEKLY" }
            val text = obj["trigger_at"]?.jsonPrimitive?.contentOrNull?.trim()
            triggerAt = if (text != null) parseTriggerAt(text) else fallback?.triggerAt
                ?: error("trigger_at (yyyy-MM-dd HH:mm) is required for schedule_type=ONCE")
        }
    }

    if (type != ScheduleType.DAILY && type != ScheduleType.WEEKLY) { startDate = null; endDate = null }

    return ParsedSchedule(
        type = type,
        triggerAt = triggerAt,
        intervalMinutes = intervalMinutes,
        timeOfDayMinutes = timeOfDayMinutes,
        weekdaysMask = weekdaysMask,
        startDate = startDate,
        endDate = endDate,
    )
}

internal fun parseTimeOfDay(text: String): Int {
    val match = Regex("^(\\d{1,2}):(\\d{1,2})$").find(text)
        ?: throw IllegalArgumentException("Invalid time_of_day '$text' (expected HH:mm)")
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    require(hour in 0..23 && minute in 0..59) { "Invalid time_of_day '$text' (expected HH:mm)" }
    return hour * 60 + minute
}

internal fun parseTriggerAt(text: String): Long {
    require(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(text)) { "Invalid trigger_at" }
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { isLenient = false }
    return runCatching { format.parse(text)?.time }
        .getOrNull()
        ?: throw IllegalArgumentException("Invalid trigger_at '$text' (expected \"yyyy-MM-dd HH:mm\")")
}

internal fun describe(task: ScheduledTaskEntity): String = when (
    runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.DAILY)
) {
    ScheduleType.DAILY -> "daily at %02d:%02d%s".format(task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60, describeRange(task))

    ScheduleType.WEEKLY -> "weekly %s at %02d:%02d%s".format(
        (1..7).filter { task.weekdaysMask and (1 shl (it - 1)) != 0 }.joinToString(","),
        task.timeOfDayMinutes / 60, task.timeOfDayMinutes % 60, describeRange(task))

    ScheduleType.INTERVAL -> "every ${task.intervalMinutes.coerceAtLeast(15)} min"

    ScheduleType.ONCE -> "once at " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(task.triggerAt))
}

private fun describeRange(task: ScheduledTaskEntity) = listOfNotNull(
    task.startDate?.let { "from $it" }, task.endDate?.let { "through $it" },
).joinToString(" ").let { if (it.isEmpty()) "" else " ($it)" }

private fun renderTasks(tasks: List<ScheduledTaskEntity>, exactAlarmAllowed: Boolean): String {
    if (tasks.isEmpty()) {
        return "No scheduled tasks for this assistant."
    }
    // id 用原始 JSON 数组输出，避免模型把 id 抄错
    val lines = tasks.map { task ->
        buildJsonObject {
            taskConfiguration(task).forEach { (key, value) -> put(key, value) }
            put("delivery", if (task.enabled && !exactAlarmAllowed) "waiting_for_exact_alarm_permission" else if (task.enabled) "scheduled" else "disabled")
            put("last_run_status", task.lastRunStatus.ifBlank { "NEVER" })
            task.nextRunAt?.let { put("next_run_at", it) }
        }
    }
    return buildString {
        appendLine("Scheduled tasks of this assistant (${tasks.size}):")
        appendLine(JsonInstantPretty.encodeToString(buildJsonArray { lines.forEach { add(it) } }))
    }
}

/** 与 ScheduledTasksVM 保持一致的默认值（此处不能依赖 UI 层，故独立声明） */
private const val DEFAULT_TIME_OF_DAY_MINUTES = 9 * 60
private const val DEFAULT_INTERVAL_MINUTES = 24 * 60

internal fun applyExecutionFields(task: ScheduledTaskEntity, obj: JsonObject): ScheduledTaskEntity {
    fun id(key: String, old: String?): String? = if (key !in obj) old else obj[key]?.jsonPrimitive?.contentOrNull?.also { Uuid.parse(it) }
    fun flag(key: String, old: Boolean): Boolean = if (key !in obj) old else
        obj[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: error("$key must be true or false")
    val mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.let { me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.valueOf(it).name } ?: task.mode
    val conversation = id("target_conversation_id", task.targetConversationId)
    return task.copy(mode = mode,
        targetConversationId = if (mode == "NEW_CHAT") null else conversation,
        targetUserMessageId = if (mode != "REGENERATE") null else id("target_user_message_id", if (conversation == task.targetConversationId) task.targetUserMessageId else null),
        modelOverrideId = id("model_override_id", task.modelOverrideId), enabled = flag("enabled", task.enabled), notify = flag("notify", task.notify), showPreview = flag("show_preview", task.showPreview))
}
