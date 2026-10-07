package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope

/** Dedicated timed card. The service remains authoritative when a deadline is reached. */
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
    val title = when (action) {
        "create" -> "创建定时任务"
        "update" -> "修改定时任务"
        else -> "立即执行定时任务"
    }
    val metadata = scheduledApprovalMetadata(tool)
    val before = metadata?.get("before") as? JsonObject
    val after = metadata?.get("after") as? JsonObject
    val name = after?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val previousName = before?.get("name")?.jsonPrimitive?.contentOrNull
    val displayName = if (action == "update" && previousName != null && previousName != name) {
        "$previousName → ${name ?: "未命名任务"}"
    } else name ?: "未命名任务"
    val expired = scheduledApprovalExpired(tool, now)
    val durationMs = scheduledApprovalDurationMs(tool)
    val remainingMs = (deadline?.minus(now) ?: 0L).coerceIn(0L, durationMs)
    val remainingSeconds = (remainingMs + 999L) / 1000L
    val denied = tool.approvalState as? ToolApprovalState.Denied
    val urgent = tool.isPending && (expired || remainingSeconds <= 10L)
    val status = when {
        denied != null -> denied.reason.ifBlank { "已拒绝，未执行" }
        expired -> "审批已超时，未执行"
        tool.isPending -> "剩余 $remainingSeconds 秒"
        tool.isExecuted -> "已执行"
        tool.approvalState == ToolApprovalState.Approved -> "已批准，等待执行"
        else -> "审批已结束"
    }
    var expanded by remember(tool.toolCallId) { mutableStateOf(true) }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = {
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        extra = {
            if (!expanded) {
                Surface(
                    color = if (urgent || denied != null) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        when {
                            denied != null -> "已拒绝"
                            expired -> "已超时"
                            tool.isPending -> "${remainingSeconds} 秒"
                            tool.isExecuted -> "已执行"
                            tool.approvalState == ToolApprovalState.Approved -> "已批准"
                            else -> "已结束"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (urgent || denied != null) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (urgent || denied != null) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    val foreground = if (urgent || denied != null) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tool.isPending && !expired) {
                            Text("${remainingSeconds} 秒", style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold, color = foreground)
                            Text("超时自动拒绝", style = MaterialTheme.typography.labelSmall, color = foreground)
                        } else {
                            Text(status, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, color = foreground)
                        }
                    }
                }
                if (tool.isPending && !expired && onApproval != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onApproval(tool.toolCallId, false, "用户拒绝", null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("拒绝")
                        }
                        Button(
                            onClick = { onApproval(tool.toolCallId, true, "", null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("批准")
                        }
                    }
                }
                if (after != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ApprovalDetail(
                                label = "时间",
                                oldValue = before?.let(::scheduledTaskScheduleDescription),
                                newValue = scheduledTaskScheduleDescription(after),
                                showChange = action == "update",
                            )
                            HorizontalDivider()
                            ApprovalDetail(
                                label = "提示词",
                                oldValue = before?.get("prompt")?.jsonPrimitive?.contentOrNull,
                                newValue = after["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                showChange = action == "update",
                                longText = true,
                            )
                        }
                    }
                } else {
                    Text("审批配置缺失", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun ApprovalDetail(
    label: String,
    oldValue: String?,
    newValue: String,
    showChange: Boolean,
    longText: Boolean = false,
) {
    val changed = showChange && oldValue != null && oldValue != newValue
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (changed) "$label · 已修改" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (changed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        if (changed && longText) {
            Text("修改前：$oldValue", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("修改后：$newValue", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(if (changed) "$oldValue → $newValue" else newValue.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Render persisted raw schedule fields instead of exposing the tool's English summary. */
internal fun scheduledTaskScheduleDescription(config: JsonObject): String {
    fun string(key: String) = config[key]?.jsonPrimitive?.contentOrNull
    val time = string("time_of_day") ?: "未设置时间"
    val range = listOfNotNull(
        string("start_date")?.let { "从 $it 起" },
        string("end_date")?.let { "至 $it 止" },
    ).joinToString("，").let { if (it.isEmpty()) "" else "（$it）" }
    return when (string("schedule_type")) {
        "ONCE" -> "仅执行一次：${string("trigger_at") ?: "未设置时间"}"
        "DAILY" -> "每天 $time$range"
        "WEEKLY" -> {
            val days = listOf("一", "二", "三", "四", "五", "六", "日")
            val weekdays = (config["weekdays"] as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.intOrNull?.let { index -> days.getOrNull(index - 1) }
            }?.joinToString("、") { "周$it" }.orEmpty()
            "每${weekdays.ifEmpty { "周" }} $time$range"
        }
        "INTERVAL" -> "每 ${string("interval_minutes") ?: "?"} 分钟"
        else -> string("schedule") ?: "未设置"
    }
}
