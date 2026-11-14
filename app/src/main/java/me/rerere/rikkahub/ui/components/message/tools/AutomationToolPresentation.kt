package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.automation.RunState
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.utils.JsonInstant

internal data class AutomationTaskPreview(
    val name: String,
    val schedule: String,
    val enabled: Boolean?,
    val nextRunAt: Long?,
    val definition: TaskDefinition?,
)

internal data class AutomationRunPreview(val state: String, val time: Long?, val detail: String?, val conversationId: String?)

/** Read the operation's persisted arguments/results only, never a mutable task from the repository. */
internal class AutomationToolPresentation(context: ToolUIContext) {
    private val args = context.arguments as? JsonObject
    private val result = context.content as? JsonObject
    val action = args.getStringContent("action").orEmpty()
    val error = result.getStringContent("error")
    private val draft = context.tool.output.firstNotNullOfOrNull { it.metadata?.get("automation_draft") as? JsonObject }
    val task = when (action) {
        "create", "update", "set_enabled" -> preview(draft ?: result)
        "save" -> preview(JsonObject(args.orEmpty() + result.orEmpty().filterKeys { it == "enabled" || it == "next_run_at" }))
        "get" -> preview(result)
        else -> preview(args?.get("task_preview") as? JsonObject, includeState = false)
    }
    val tasks = if (action == "list") (context.content as? JsonArray).orEmpty().mapNotNull { preview(it as? JsonObject) } else emptyList()
    val runs = if (action == "history") (context.content as? JsonArray).orEmpty().mapNotNull { element ->
        val run = element as? JsonObject ?: return@mapNotNull null
        AutomationRunPreview(
            state = run.getStringContent("state")?.let { name -> RunState.entries.firstOrNull { it.name == name }?.label } ?: "运行记录",
            time = run.long("due_at"), detail = run.getStringContent("detail"), conversationId = run.getStringContent("conversation_id"),
        )
    } else emptyList()
    val choices = listOf("models" to "可选模型", "conversations" to "目标聊天", "user_messages" to "用户消息").mapNotNull { (key, title) ->
        val items = (result?.get(key) as? JsonArray)?.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            row.getStringContent("name") ?: row.getStringContent("title") ?: row.getStringContent("preview")
        } ?: return@mapNotNull null
        title to items
    }
    val title = when (action) {
        "create" -> "新建任务草稿"
        "update" -> if (args?.containsKey("old_text") == true) "修改提示词草稿" else "编辑任务草稿"
        "save" -> "保存定时任务"
        "delete" -> "删除定时任务"
        "set_enabled" -> "修改启停草稿"
        "run" -> "立即执行任务"
        "cancel" -> "取消本次执行"
        "list" -> "查询任务列表"
        "get" -> "查看任务详情"
        "history" -> "查看运行记录"
        "options" -> when {
            args.getStringContent("target_conversation_id") != null -> "选择用户消息"
            args.bool("include_conversations") == true && args.bool("include_models") == true -> "查询任务可选配置"
            args.bool("include_conversations") == true -> "选择目标聊天"
            args.bool("include_models") == true -> "查询可选模型"
            else -> "准备任务操作参数"
        }
        else -> "定时任务操作"
    }
    val status = when {
        context.tool.approvalState is ToolApprovalState.Denied -> "未批准，本次操作未执行"
        error != null -> "操作失败"
        context.tool.isPending -> "等待审批"
        !context.tool.isExecuted -> if (context.loading) "正在处理…" else "尚未执行"
        action in setOf("create", "update", "set_enabled") || result.bool("draft") == true -> "草稿待保存，尚未生效"
        action == "save" -> "已保存"
        action == "delete" -> "已删除任务"
        action == "run" -> "已提交执行请求"
        action == "cancel" -> "已取消本次执行"
        action == "list" -> if (tasks.isEmpty()) "暂无定时任务" else "共 ${tasks.size} 个任务"
        action == "history" -> if (runs.isEmpty()) "尚无运行记录" else "最近 ${runs.size} 次执行"
        action == "options" -> "操作参数已准备"
        else -> null
    }
    val showSummary = context.tool.approvalState !is ToolApprovalState.Denied &&
        (error != null || task != null && action !in setOf("create", "update", "set_enabled") ||
            context.tool.isExecuted && action in setOf("list", "history"))

    private fun preview(source: JsonObject?, includeState: Boolean = true): AutomationTaskPreview? {
        if (source == null) return null
        val definition = source["definition"]?.let { runCatching { JsonInstant.decodeFromJsonElement<TaskDefinition>(it) }.getOrNull() }
        val name = definition?.name ?: source.getStringContent("name") ?: return null
        return AutomationTaskPreview(
            name, definition?.schedule?.let { runCatching { it.describe() }.getOrNull() } ?: source.getStringContent("schedule").orEmpty(),
            source.bool("enabled").takeIf { includeState }, source.long("next_run_at").takeIf { includeState }, definition,
        )
    }
}

private fun JsonObject?.bool(key: String) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject?.long(key: String) = (this?.get(key) as? JsonPrimitive)?.longOrNull
