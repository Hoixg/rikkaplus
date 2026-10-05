package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.service.SubagentManager
import me.rerere.rikkahub.service.SubagentTaskStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

val LocalSubagentConversationId = staticCompositionLocalOf<Uuid?> { null }

class SubagentToolUI(override val toolName: String) : ToolUIRenderer {
    @Composable
    override fun title(context: ToolUIContext): String = when (toolName) {
        "spawn_agent" -> "子代理：${context.arguments.getStringContent("description").orEmpty()}"
        "followup_agent" -> "追问子代理"
        "poll_agent" -> "子代理进度"
        "cancel_agent" -> "取消子代理"
        else -> "子代理列表"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val manager: SubagentManager = koinInject()
        val tasks by manager.tasks.collectAsStateWithLifecycle()
        val parentId = LocalSubagentConversationId.current
        val id = context.content.getStringContent("sessionId") ?: context.content.getStringContent("taskId")
            ?: context.arguments.getStringContent("sessionId") ?: context.arguments.getStringContent("taskId")
        val task = tasks.values.lastOrNull {
            it.parentConversationId == parentId &&
                (if (id != null) it.taskId.toString() == id else it.description == context.arguments.getStringContent("description"))
        }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(task?.taskId, task?.status) {
            while (task?.status == SubagentTaskStatus.RUNNING) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        val detail = if (task?.status == SubagentTaskStatus.RUNNING) {
            val latest = task.messages.flatMap { it.parts }.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Tool>().lastOrNull()?.toolName
            "运行中 · ${(now - task.startedAt).coerceAtLeast(0) / 1000} 秒${latest?.let { " · $it" }.orEmpty()}"
        } else if (task == null && context.content.getStringContent("status") == "running") {
            "运行句柄已失效 · 可在抽屉查看已保存的轨迹"
        } else {
            val completed = (task?.resultJson ?: context.content.getStringContent("result"))?.let {
                runCatching { Json.parseToJsonElement(it) }.getOrNull()
            }
            val summary = completed.getStringContent("result") ?: completed.getStringContent("status")
                ?: context.content.getStringContent("result") ?: context.content.getStringContent("status")
                ?: context.content.getStringContent("count")?.let { "$it 个子代理" }.orEmpty()
            val duration = completed.getStringContent("durationMs") ?: context.content.getStringContent("durationMs")
            duration?.toLongOrNull()?.let { "耗时 ${it / 1000} 秒 · $summary" } ?: summary
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3)
    }
}
