package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal val automationReadActions = setOf("list", "get", "options", "history")
internal val automationWriteActions = setOf("create", "update", "delete", "set_enabled", "run", "cancel")
internal fun requireAutomationAction(action: String, scheduled: Boolean) {
    require(action in automationReadActions || (!scheduled && action in automationWriteActions)) {
        "定时执行只能查询任务，不能创建、修改或触发其他任务" }
}

/** Uses the agent's normal approval flow. Updates contain only the complete new definition, with a revision guard. */
suspend fun createAutomationTools(
    repository: AutomationRepository,
    conversations: ConversationRepository,
    store: SettingsStore,
    assistantId: Uuid,
    conversationId: Uuid,
    cancel: suspend (String) -> Unit,
): List<Tool> {
    val scheduled = repository.forConversation(conversationId.toString()) != null
    return listOf(Tool(
        name = "scheduled_task",
        description = """
            Query and manage scheduled tasks of the current assistant. Read actions: list, get, options, history.
            For questions about existing tasks, call list DIRECTLY with only action=list. No conversation lookup, options, recent_chats or conversation_search is needed first; the current assistant is already known.
            list returns task IDs, names, modes, linked conversation IDs and schedules. Use get for a task's full definition or history for its runs, using the ID from list. An empty list means no tasks for this assistant; do not search chats to invent task state.
            Write actions: create, update, delete, set_enabled, run, cancel. Every write needs normal human approval.
            Only CREATE schedules when the human explicitly requests future/recurring work. Requests to list, inspect or check existing tasks are read operations and should use this tool directly. Other ordinary requests mean do the work now.
            A saved task prompt is one execution's content, never permission to schedule another task.
            options is NOT task lookup or a required preflight. By default it only returns fresh task/request UUIDs and current_conversation_id. Use it only when preparing a create/run operation or selecting configuration.
            Set include_models=true only when selecting an explicit model override. Set include_conversations=true only when selecting another target chat for FOLLOW_UP/REGENERATE. Neither list is loaded by default.
            With target_conversation_id, options returns that chat's selected-branch user message IDs for REGENERATE. For a task targeting this chat, use current_conversation_id without enumerating chats.
            create: use a fresh id from options, definition, enabled (default true). update: first get the task, then send its id, revision, complete NEW definition and enabled. Do not send or concatenate the old prompt.
            delete/set_enabled/run/cancel require id and revision from get. run also requires a fresh request_id from options; retries MUST reuse it. cancel requires run_id from get.
            definition fields: name, prompt, mode (NEW_CHAT/FOLLOW_UP/REGENERATE), conversationId, userMessageId, modelId, notify, resetContextBeforeRun, schedule.
            NEW_CHAT starts a chat; FOLLOW_UP appends to conversationId when idle; REGENERATE copies the selected user message and preceding context into a NEW chat (prompt can be empty).
            resetContextBeforeRun defaults false and is allowed only for FOLLOW_UP. When true, every run permanently deletes the target chat's previous messages, branches and compression summaries before sending the prompt, keeping chat settings and only the current run's records. Enable only when the human explicitly requests clearing previous chat records.
            schedule fields: kind (ONCE/DAILY/WEEKLY/INTERVAL), at (ONCE Unix epoch milliseconds), timeMinutes (minutes after midnight), intervalMinutes (>=15), weekdays (1=Mon..7=Sun), startDate/endDate (inclusive yyyy-MM-dd, nullable).
            Defaults: DAILY at 09:00, interval 1440 minutes, weekdays Mon-Fri. Requires exact-alarm access to enable; enabled=false saves a draft. run does not change future scheduling.
            After denial, do not retry without a new human request. ${if (scheduled) "This is a scheduled execution: ONLY READ ACTIONS ARE AVAILABLE." else ""}
        """.trimIndent(),
        parameters = {
            fun field(type: String) = buildJsonObject { put("type", type) }
            InputSchema.Obj(buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Query existing tasks: list directly, then get/history by task ID. options only prepares IDs or explicitly requested model/chat choices; never use it before list/get/history.")
                    put("enum", JsonArray((automationReadActions + if (scheduled) emptySet() else automationWriteActions).map(::JsonPrimitive)))
                })
                listOf("id", "revision", "request_id", "run_id", "target_conversation_id").forEach { put(it, field("string")) }
                put("include_models", buildJsonObject {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "options only: load configured model choices when selecting a model override. Default false.")
                })
                put("include_conversations", buildJsonObject {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "options only: load this assistant's chat choices when selecting another target chat. Default false. Not needed for querying tasks.")
                })
                put("enabled", field("boolean"))
                put("definition", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        listOf("name", "prompt", "mode").forEach { put(it, field("string")) }
                        listOf("conversationId", "userMessageId", "modelId").forEach {
                            put(it, buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }) })
                        }
                        put("notify", field("boolean"))
                        put("resetContextBeforeRun", buildJsonObject {
                            put("type", "boolean")
                            put("description", "FOLLOW_UP only, default false. Permanently delete old chat messages, branches and compression summaries before every run. Requires an explicit human request to clear previous records.")
                        })
                        put("schedule", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("kind", field("string"))
                                listOf("at", "timeMinutes", "intervalMinutes").forEach { put(it, field("integer")) }
                                put("weekdays", buildJsonObject { put("type", "array"); put("items", field("integer")) })
                                listOf("startDate", "endDate").forEach {
                                    put(it, buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }) })
                                }
                            })
                        })
                    })
                    put("required", buildJsonArray { add("name"); add("prompt"); add("schedule") })
                })
            }, required = listOf("action"))
        },
        systemPrompt = { _, _ ->
            "For questions about existing scheduled tasks, use scheduled_task action=list directly, then get/history only as needed. " +
                "Do not first query chats or call options. Current conversation ID is $conversationId; task scope is already the current assistant. " +
                "options prepares IDs; chat/model choices are loaded only with include_conversations/include_models=true. " +
                "Creating schedules requires an explicit future/recurring-work request. A scheduled run may query tasks but cannot modify or trigger schedules. " +
                "Never retry a denied task operation without new human instructions."
        },
        needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content in automationWriteActions },
        execute = { input ->
            val args = input.jsonObject
            fun text(key: String) = args[key]?.jsonPrimitive?.contentOrNull
            val action = text("action").orEmpty()
            val scheduledExecution = scheduled || (action in automationWriteActions &&
                repository.forConversation(conversationId.toString()) != null)
            requireAutomationAction(action, scheduledExecution)
            val task = text("id")?.let { repository.task(it) }
            require(task == null || task.assistantId == assistantId.toString()) { "找不到当前助手的任务" }
            fun requireTask(): AutomationTask = requireNotNull(task) { "任务已删除或不属于当前助手" }.also {
                require(text("revision") == it.revision) { "任务已变化，请重新读取并请求审批" }
            }
            val result = when (action) {
                "list" -> buildJsonArray { repository.tasks(assistantId.toString()).forEach { add(taskSummary(it)) } }
                "get" -> buildJsonObject {
                    val current = requireNotNull(task) { "任务不存在" }
                    taskSummary(current).forEach { (key, value) -> put(key, value) }
                    put("definition", JsonInstant.encodeToJsonElement(current.definition))
                    put("active_runs", JsonArray(repository.active().filter { it.taskId == current.id }.map { JsonInstant.encodeToJsonElement(it.id) }))
                }
                "options" -> buildAutomationOptions(
                    arguments = args,
                    conversationId = conversationId,
                    models = { buildJsonArray { store.awaitLoaded().providers.forEach { provider -> provider.models.forEach {
                        if (it.type == me.rerere.ai.provider.ModelType.CHAT) add(buildJsonObject {
                            put("id", it.id.toString()); put("name", it.displayName); put("provider", provider.name)
                        })
                    } } } },
                    conversations = { buildJsonArray { conversations.getRecentConversations(assistantId, 30).forEach {
                        add(buildJsonObject { put("id", it.id.toString()); put("title", it.title) })
                    } } },
                    userMessages = { id ->
                        val target = requireNotNull(conversations.getConversationById(id)) { "目标聊天不存在" }
                        require(target.assistantId == assistantId) { "目标聊天不属于当前助手" }
                        buildJsonArray { target.currentMessages.filter { it.role == me.rerere.ai.core.MessageRole.USER }.forEach {
                            add(buildJsonObject { put("id", it.id.toString()); put("preview", it.toText().take(200)) })
                        } }
                    },
                )
                "history" -> buildJsonArray { requireNotNull(task); repository.history(task.id).forEach {
                    add(buildJsonObject { put("id", it.id); put("state", it.state.name); put("detail", it.detail); put("conversation_id", it.conversationId); put("due_at", it.dueAt) })
                } }
                "create", "update" -> {
                    val old = if (action == "update") requireTask() else null
                    val definition = JsonInstant.decodeFromJsonElement<TaskDefinition>(args.getValue("definition"))
                        .copy(assistantId = assistantId.toString())
                    val id = text("id") ?: error("请先用 options 获取新任务 ID")
                    repository.save(id, definition, args["enabled"]?.jsonPrimitive?.booleanOrNull ?: if (old == null) true else null, old?.revision)
                    taskSummary(requireNotNull(repository.task(id)))
                }
                "delete" -> { repository.delete(requireTask().id, task!!.revision); JsonPrimitive("已删除任务") }
                "set_enabled" -> {
                    val current = requireTask()
                    repository.setEnabled(current.id, args.getValue("enabled").jsonPrimitive.boolean, current.revision)
                    JsonPrimitive("已更新启用状态")
                }
                "run" -> {
                    val current = requireTask()
                    val request = text("request_id") ?: error("请先用 options 获取执行请求 ID")
                    Uuid.parse(request)
                    val queued = repository.queue(current.id, current.revision, request)
                    repository.kick()
                    JsonPrimitive("执行 ${queued?.id}：${queued?.state?.label}")
                }
                "cancel" -> {
                    val current = requireTask()
                    val run = repository.run(text("run_id") ?: error("缺少运行 ID"))
                    require(run?.taskId == current.id) { "运行记录不属于该任务" }
                    cancel(run!!.id)
                    JsonPrimitive("已取消本次执行")
                }
                else -> error("未知操作")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    ))
}

internal fun taskSummary(task: AutomationTask) = buildJsonObject {
    put("id", task.id); put("revision", task.revision); put("name", task.definition.name)
    put("schedule", task.definition.schedule.describe()); put("enabled", task.enabled); put("next_run_at", task.nextRunAt)
    put("mode", task.definition.mode.name)
    put("conversation_id", task.definition.conversationId)
    put("model_id", task.definition.modelId)
}
