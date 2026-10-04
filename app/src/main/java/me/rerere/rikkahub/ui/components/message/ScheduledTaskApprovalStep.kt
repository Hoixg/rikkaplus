package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope

/** Dedicated timed card. Rendering never decides approval or changes another tool's controls. */
@Composable
internal fun ChainOfThoughtScope.ScheduledTaskApprovalStep(
    tool: UIMessagePart.Tool,
    onApproval: ((String, Boolean, String, String?) -> Unit)?,
) {
    var now by remember(tool.toolCallId) { mutableLongStateOf(System.currentTimeMillis()) }
    val deadline = scheduledApprovalDeadline(tool)
    LaunchedEffect(tool.toolCallId, tool.isPending, deadline) {
        while (tool.isPending && deadline != null && now < deadline) {
            now = System.currentTimeMillis()
            delay(200)
        }
        now = System.currentTimeMillis()
    }
    val action = scheduledAction(tool.inputAsJson())
    val title = when (action) { "create" -> "创建定时任务"; "update" -> "修改定时任务"; else -> "立即执行定时任务" }
    val metadata = scheduledApprovalMetadata(tool)
    val before = metadata?.get("before") as? JsonObject
    val after = metadata?.get("after") as? JsonObject
    val expired = scheduledApprovalExpired(tool, now)
    var expanded by remember(tool.toolCallId) { mutableStateOf(true) }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = { Text(title + (after?.get("name")?.jsonPrimitive?.contentOrNull?.let { " · $it" } ?: "")) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (after != null) {
                    val fields = listOf("name" to "名称", "prompt" to "提示词", "schedule" to "时间安排",
                        "mode" to "执行模式", "target_conversation_id" to "目标会话",
                        "target_user_message_id" to "用户消息", "model_override_id" to "模型",
                        "enabled" to "启用", "notify" to "通知", "show_preview" to "内容预览")
                    fields.forEach { (key, label) ->
                        val old = before?.get(key)
                        val value = after[key]
                        if (action != "update" || old != value) {
                            fun display(element: JsonElement?): String = when {
                                element == null || element == JsonNull -> "未设置"
                                element is JsonPrimitive && element.content == "true" -> "开启"
                                element is JsonPrimitive && element.content == "false" -> "关闭"
                                element is JsonPrimitive -> element.content.ifBlank { "空" }
                                else -> element.toString()
                            }
                            fun named(element: JsonElement?, config: JsonObject?): String {
                                val nameKey = when (key) { "target_conversation_id" -> "target_conversation_title"; "model_override_id" -> "model_name"; else -> null }
                                val name = nameKey?.let { config?.get(it)?.jsonPrimitive?.contentOrNull }?.takeIf { it.isNotBlank() }
                                return name?.let { "$it (${display(element)})" } ?: when {
                                    key == "model_override_id" && (element == null || element == JsonNull) -> "沿用助手或会话模型"
                                    key == "mode" -> when ((element as? JsonPrimitive)?.contentOrNull) {
                                        "NEW_CHAT" -> "新建会话"; "FOLLOW_UP" -> "继续会话"; "REGENERATE" -> "重新生成"; else -> display(element)
                                    }
                                    else -> display(element)
                                }
                            }
                            Text("$label：" + (if (action == "update") "${named(old, before)} → " else "") + named(value, after),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                when {
                    tool.approvalState is ToolApprovalState.Denied -> Text(
                        (tool.approvalState as ToolApprovalState.Denied).reason.ifBlank { "已拒绝，未执行" },
                        color = MaterialTheme.colorScheme.error)
                    expired -> Text("审批超时，未批准", color = MaterialTheme.colorScheme.error)
                    tool.isPending -> Text("${((deadline!! - now + 999) / 1000).coerceIn(0, 30)}秒内批准，超时默认拒绝")
                    tool.isExecuted -> Text(tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                    tool.approvalState == ToolApprovalState.Approved -> Text("已批准，等待执行")
                }
                if (tool.isPending && !expired && onApproval != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onApproval(tool.toolCallId, false, "用户拒绝", null) }) { Text("拒绝") }
                        OutlinedButton(onClick = { onApproval(tool.toolCallId, true, "", null) }) { Text("批准") }
                    }
                }
            }
        },
    )
}
