package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal val automationReadActions = setOf("list", "get", "options", "history")
internal val automationApprovalActions = setOf("save", "delete", "run", "cancel")
internal val automationWriteActions = setOf("create", "update", "set_enabled") + automationApprovalActions
internal fun requireAutomationAction(action: String, scheduled: Boolean) {
    require(action in automationReadActions || (!scheduled && action in automationWriteActions)) {
        "定时执行只能查询任务，不能创建、修改或触发其他任务" }
}

/** Draft edits stay in chat messages; only the final save changes the task after normal approval. */
suspend fun createAutomationTools(
    repository: AutomationRepository,
    conversations: ConversationRepository,
    store: SettingsStore,
    assistantId: Uuid,
    conversationId: Uuid,
    cancel: suspend (String) -> Unit,
    getMessages: () -> List<UIMessage> = { emptyList() },
): List<Tool> {
    val scheduled = repository.forConversation(conversationId.toString()) != null
    // Tool results enter getMessages at the next model step; preserve sequential edits within the same batch too.
    val draftCache = mutableMapOf<String, JsonObject>()
    return listOf(Tool(
        name = "scheduled_task",
        description = """
            Query and manage scheduled tasks of the current assistant. Read actions: list, get, options, history.
            For questions about existing tasks, call list DIRECTLY with only action=list. No conversation lookup, options, recent_chats or conversation_search is needed first; the current assistant is already known.
            list returns task IDs, names, modes, linked conversation IDs and schedules. Use get for a task's full definition or history for its runs, using the ID from list. An empty list means no tasks for this assistant; do not search chats to invent task state.
            Draft actions: create, update, set_enabled. These only edit a draft in this chat, need NO approval and NEVER change saved tasks or alarms. Final actions: save, delete, run, cancel require human approval.
            Only CREATE schedules when the human explicitly requests future/recurring work. Requests to list, inspect or check existing tasks are read operations and should use this tool directly. Other ordinary requests mean do the work now.
            A saved task prompt is one execution's content, never permission to schedule another task.
            options is NOT task lookup or a required preflight. By default it only returns fresh task/request UUIDs and current_conversation_id. Use it only when preparing a create/run operation or selecting configuration.
            Set include_models=true only when selecting an explicit model override. Set include_conversations=true only when selecting another target chat for FOLLOW_UP/REGENERATE. Neither list is loaded by default.
            With target_conversation_id, options returns that chat's selected-branch user message IDs for REGENERATE. For a task targeting this chat, use current_conversation_id without enumerating chats.
            create: use a fresh id from options, definition, enabled (default true). update: use id and revision from list/get, then send ONLY changed definition fields. Multiple updates accumulate in the draft. Omitted fields, including nested schedule fields and enabled, keep their saved values. Do not rewrite unchanged prompts or settings. get returns the draft if present, otherwise the saved task.
            After ALL edits to one task are finished, call save ONCE with only id, in a later step after the draft calls return. The app resolves and freezes the final draft for one approval, then saves it only if approved. Do not send definition or replay prompt text in save. Never claim draft edits are already saved; always finish the requested edits with save. The original task keeps running unchanged until then.
            For partial prompt edits, update accepts old_text, new_text and optional replace_all, exactly like workspace_edit_file. Replace a unique existing passage; include its surrounding text in new_text to insert content, or use new_text="" to delete it. Whitespace-tolerant matching is shared with workspace editing. Do not combine a full definition.prompt replacement with old_text/new_text. No workspace binding or file operation is needed.
            set_enabled stages the enabled flag and also needs a final save. delete/run/cancel require id and revision from get. run also requires a fresh request_id from options; retries MUST reuse it. cancel requires run_id from get.
            definition fields: name, prompt, mode (NEW_CHAT/FOLLOW_UP/REGENERATE), conversationId, userMessageId, modelId, notify, resetContextBeforeRun, schedule.
            NEW_CHAT starts a chat; FOLLOW_UP appends to conversationId when idle; REGENERATE copies the selected user message and preceding context into a NEW chat (prompt can be empty).
            resetContextBeforeRun defaults false and is allowed only for FOLLOW_UP. When true, every run permanently deletes the target chat's previous messages, branches and compression summaries before sending the prompt, keeping chat settings and only the current run's records. Enable only when the human explicitly requests clearing previous chat records.
            schedule fields: kind (ONCE/DAILY/WEEKLY/INTERVAL), at (one ONCE Unix epoch millisecond instant), atTimes (optional ONCE array of Unix epoch millisecond instants), timeMinutes (one recurring time, minutes after midnight), timesMinutes (optional DAILY/WEEKLY array of minutes after midnight), intervalMinutes (>=15), weekdays (1=Mon..7=Sun), startDate/endDate (inclusive yyyy-MM-dd, nullable).
            Multiple times belong to ONE task: DAILY at 10:00 and 18:00 uses timesMinutes=[600,1080]; WEEKLY also sets weekdays. For several runs on a chosen date, use ONCE with atTimes containing those absolute instants. Nonempty arrays take precedence over their scalar at/timeMinutes; repeated and unordered times are sorted and deduplicated for scheduling. ONCE stops after its last instant. Do not create separate tasks for each time unless requested.
            Arrays replace the saved array; null clears nullable fields such as modelId or endDate. Updating timeMinutes alone switches to that single recurring time; updating at alone switches to that single ONCE instant, clearing the corresponding multi-time array.
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
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "update only: draft prompt passage to edit, using workspace_edit_file matching. Must be unique unless replace_all=true.")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement passage. Include the anchor to insert text; use an empty string to delete it. Requires old_text.")
                })
                put("replace_all", field("boolean"))
                put("definition", buildJsonObject {
                    put("type", "object")
                    put("description", "create: task definition with name, prompt and schedule. update: only changed fields; omitted fields and nested schedule fields are preserved.")
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
                                listOf("weekdays", "timesMinutes", "atTimes").forEach {
                                    put(it, buildJsonObject { put("type", "array"); put("items", field("integer")) })
                                }
                                listOf("startDate", "endDate").forEach {
                                    put(it, buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }) })
                                }
                            })
                        })
                    })
                })
            }, required = listOf("action"))
        },
        systemPrompt = { _, _ ->
            "For questions about existing scheduled tasks, use scheduled_task action=list directly, then get/history only as needed. " +
                "Do not first query chats or call options. Current conversation ID is $conversationId; task scope is already the current assistant. " +
                "options prepares IDs; chat/model choices are loaded only with include_conversations/include_models=true. " +
                "create/update/set_enabled only edit drafts without approval; use old_text/new_text for prompt passages. After all edits, call save once with only id for final approval and persistence. Never claim draft changes are saved. " +
                "Creating schedules requires an explicit future/recurring-work request. A scheduled run may query tasks but cannot modify or trigger schedules. " +
                "Never retry a denied task operation without new human instructions."
        },
        needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content in automationApprovalActions },
        prepareArguments = { input ->
            val args = input.jsonObject
            if (args["action"]?.jsonPrimitive?.content == "save") {
                val id = args.getValue("id").jsonPrimitive.content
                val prepared = prepareAutomationSave(getMessages(), id, assistantId.toString())
                repository.validate(JsonInstant.decodeFromJsonElement(prepared.getValue("definition")))
                prepared
            } else if (args["action"]?.jsonPrimitive?.content in setOf("delete", "run", "cancel")) {
                // Keep approval and historical cards tied to this operation, not a later task edit.
                val task = args["id"]?.jsonPrimitive?.content?.let { repository.task(it) }
                JsonObject(args + (task?.takeIf { it.assistantId == assistantId.toString() }?.let {
                    mapOf("task_preview" to taskSummary(it))
                } ?: emptyMap()))
            } else {
                input
            }
        },
        execute = { input ->
            val args = input.jsonObject
            fun text(key: String) = args[key]?.jsonPrimitive?.contentOrNull
            val action = text("action").orEmpty()
            val scheduledExecution = scheduled || (action in automationWriteActions &&
                repository.forConversation(conversationId.toString()) != null)
            requireAutomationAction(action, scheduledExecution)
            val task = text("id")?.let { repository.task(it) }
            require(task == null || task.assistantId == assistantId.toString()) { "找不到当前助手的任务" }
            val draft = text("id")?.let { id ->
                if (id in draftCache) draftCache[id]?.takeUnless { it["saved"]?.jsonPrimitive?.booleanOrNull == true }
                else findAutomationDraft(getMessages(), id, assistantId.toString())
            }
            var draftResult: JsonObject? = null
            fun requireTask(): AutomationTask = requireNotNull(task) { "任务已删除或不属于当前助手" }.also {
                require(text("revision") == it.revision) { "任务已变化，请重新读取并请求审批" }
            }
            val result = when (action) {
                "list" -> buildJsonArray { repository.tasks(assistantId.toString()).forEach { add(taskSummary(it)) } }
                "get" -> buildJsonObject {
                    if (draft != null && draft["revision"]?.jsonPrimitive?.contentOrNull == task?.revision) {
                        automationDraftSummary(draft).forEach { (key, value) -> put(key, value) }
                        put("definition", draft.getValue("definition"))
                    } else {
                        val current = requireNotNull(task) { "任务不存在" }
                        taskSummary(current).forEach { (key, value) -> put(key, value) }
                        put("definition", JsonInstant.encodeToJsonElement(current.definition))
                    }
                    put("active_runs", JsonArray(repository.active().filter { it.taskId == text("id") }.map { JsonInstant.encodeToJsonElement(it.id) }))
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
                "create", "update", "set_enabled" -> {
                    if (action == "set_enabled") args.getValue("enabled").jsonPrimitive.boolean
                    val edited = editAutomationDraft(args, task, draft, assistantId.toString())
                    draftResult = edited
                    automationDraftSummary(edited)
                }
                "save" -> {
                    val old = if (text("revision") != null) requireTask() else null
                    val id = text("id") ?: error("缺少任务 ID")
                    val definition = JsonInstant.decodeFromJsonElement<TaskDefinition>(args.getValue("definition"))
                        .copy(assistantId = assistantId.toString())
                    repository.save(id, definition, args["enabled"]?.jsonPrimitive?.booleanOrNull, old?.revision)
                    draftResult = buildJsonObject { put("id", id); put("assistant_id", assistantId.toString()); put("saved", true) }
                    taskSummary(requireNotNull(repository.task(id)))
                }
                "delete" -> { repository.delete(requireTask().id, task!!.revision); JsonPrimitive("已删除任务") }
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
            draftResult?.let { draftCache[it.getValue("id").jsonPrimitive.content] = it }
            listOf(UIMessagePart.Text(result.toString(), metadata = draftResult?.let {
                buildJsonObject { put("automation_draft", it) }
            }))
        },
    ))
}

/** The latest draft lives in normal tool-result metadata, so approval resumption needs no separate storage. */
internal fun findAutomationDraft(messages: List<UIMessage>, id: String, assistantId: String): JsonObject? {
    for (message in messages.asReversed()) {
        for (tool in message.getTools().asReversed().filter { it.toolName == "scheduled_task" }) {
            for (part in tool.output.asReversed()) {
                val draft = part.metadata?.get("automation_draft") as? JsonObject ?: continue
                if (draft["id"]?.jsonPrimitive?.content != id || draft["assistant_id"]?.jsonPrimitive?.content != assistantId) continue
                return draft.takeUnless { it["saved"]?.jsonPrimitive?.booleanOrNull == true }
            }
        }
    }
    return null
}

internal fun editAutomationDraft(args: JsonObject, task: AutomationTask?, draft: JsonObject?, assistantId: String): JsonObject {
    val id = args["id"]?.jsonPrimitive?.content ?: error("缺少任务 ID")
    Uuid.parse(id)
    val revision = args["revision"]?.jsonPrimitive?.contentOrNull
    require(revision == task?.revision) { "任务已变化，请重新读取后编辑" }
    val previous = draft?.takeIf {
        it["id"]?.jsonPrimitive?.content == id && it["assistant_id"]?.jsonPrimitive?.content == assistantId &&
            it["revision"]?.jsonPrimitive?.contentOrNull == revision
    }
    require(args["action"]?.jsonPrimitive?.content == "create" || task != null || previous != null) { "任务不存在" }
    require(args["action"]?.jsonPrimitive?.content != "create" || task == null) { "任务 ID 已存在，请修改已有任务" }
    val base = previous?.get("definition")?.let { JsonInstant.decodeFromJsonElement<TaskDefinition>(it) } ?: task?.definition
    val definition = resolveAutomationDefinition(args, base, assistantId)
    return buildJsonObject {
        put("id", id); put("assistant_id", assistantId); put("revision", revision)
        put("definition", JsonInstant.encodeToJsonElement(definition))
        put("enabled", args["enabled"] ?: previous?.get("enabled") ?: if (task == null) JsonPrimitive(true) else JsonNull)
    }
}

internal fun prepareAutomationSave(messages: List<UIMessage>, id: String, assistantId: String): JsonObject {
    require(messages.lastOrNull()?.getTools().orEmpty().none {
        it.toolName == "scheduled_task" && !it.isExecuted && runCatching {
            val pending = JsonInstant.parseToJsonElement(it.input).jsonObject
            pending["action"]?.jsonPrimitive?.content in setOf("create", "update", "set_enabled") &&
                pending["id"]?.jsonPrimitive?.content == id
        }.getOrDefault(false)
    }) { "请先完成全部草稿编辑，再单独提交保存" }
    val draft = requireNotNull(findAutomationDraft(messages, id, assistantId)) { "没有待保存的任务草稿，请先编辑任务" }
    return buildJsonObject {
        put("action", "save")
        listOf("id", "revision", "definition", "enabled").forEach { put(it, draft.getValue(it)) }
    }
}

private fun automationDraftSummary(draft: JsonObject) = buildJsonObject {
    val definition = JsonInstant.decodeFromJsonElement<TaskDefinition>(draft.getValue("definition"))
    put("id", draft.getValue("id")); put("revision", draft.getValue("revision"))
    put("name", definition.name); put("schedule", definition.schedule.describe()); put("draft", true)
    put("next_step", "Finish all edits, then call save once with this id for approval. The saved task is unchanged.")
}

/** Apply partial fields and reuse the workspace's existing text replacers. */
internal fun resolveAutomationDefinition(args: JsonObject, current: TaskDefinition?, assistantId: String): TaskDefinition {
    val changes = args["definition"]?.jsonObject ?: if (current != null) JsonObject(emptyMap()) else error("缺少任务定义")
    require(current != null || changes.keys.containsAll(listOf("name", "prompt", "schedule"))) {
        "创建任务需要 name、prompt 和 schedule"
    }
    val saved = current?.let { JsonInstant.encodeToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    val merged = saved.toMutableMap().apply {
        putAll(changes)
        (changes["schedule"] as? JsonObject)?.let { schedule ->
            put("schedule", JsonObject((saved["schedule"] as? JsonObject).orEmpty().toMutableMap().apply {
                putAll(schedule)
                if ("timeMinutes" in schedule && "timesMinutes" !in schedule) put("timesMinutes", JsonArray(emptyList()))
                if ("at" in schedule && "atTimes" !in schedule) put("atTimes", JsonArray(emptyList()))
            }))
        }
    }
    val definition = JsonInstant.decodeFromJsonElement<TaskDefinition>(JsonObject(merged)).copy(assistantId = assistantId)
    if ("old_text" !in args && "new_text" !in args && "replace_all" !in args) return definition
    require(current != null) { "提示词局部编辑只用于修改已有任务" }
    require("prompt" !in changes) { "请使用提示词局部编辑或完整替换，不要同时提交" }
    val oldText = (args["old_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("缺少 old_text")
    val newText = (args["new_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("缺少 new_text")
    val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
    return definition.copy(prompt = replaceText(definition.prompt, oldText, newText, replaceAll).updated)
}

internal fun taskSummary(task: AutomationTask) = buildJsonObject {
    put("id", task.id); put("revision", task.revision); put("name", task.definition.name)
    put("schedule", task.definition.schedule.describe()); put("enabled", task.enabled); put("next_run_at", task.nextRunAt)
    put("mode", task.definition.mode.name)
    put("conversation_id", task.definition.conversationId)
    put("model_id", task.definition.modelId)
}
