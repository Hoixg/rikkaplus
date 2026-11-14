package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.automation.AutomationDefinitionDetails
import me.rerere.rikkahub.ui.components.automation.AutomationRunContent
import me.rerere.rikkahub.ui.components.automation.formatTaskTime
import me.rerere.rikkahub.ui.components.automation.modeLabel
import me.rerere.rikkahub.ui.components.automation.taskModelLabel
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings

object AutomationToolUI : ToolUIRenderer {
    override val toolName = "scheduled_task"
    override fun icon(context: ToolUIContext) = when (context.arguments.getStringContent("action")) {
        "list", "get", "options" -> HugeIcons.Search01
        "history" -> HugeIcons.Clock01
        "update", "set_enabled" -> HugeIcons.FileEdit
        "save" -> HugeIcons.Tick01
        "delete" -> HugeIcons.Delete01
        "run" -> HugeIcons.Play
        "cancel" -> HugeIcons.Cancel01
        else -> HugeIcons.Calendar03
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val data = remember(context) { AutomationToolPresentation(context) }
        return listOfNotNull(data.title, data.task?.name?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    override fun hasSummary(context: ToolUIContext) = AutomationToolPresentation(context).showSummary

    @Composable
    override fun Summary(context: ToolUIContext) {
        val data = remember(context) { AutomationToolPresentation(context) }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (data.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                if (data.error != null) {
                    Text(data.error.lineSequence().first(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                } else {
                    data.task?.let {
                        TaskPreview(it, showName = false, pendingSave = context.tool.isPending && data.action == "save")
                        if (it.definition?.resetContextBeforeRun == true) Text("每次执行前清空目标聊天记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    data.tasks.take(3).forEach { TaskPreview(it) }
                    data.runs.firstOrNull()?.let { AutomationRunContent(it.state, it.time, null) }
                    if (context.tool.isPending) Text("点击步骤查看完整配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (data.tasks.size > 3 || data.runs.isNotEmpty()) Text("点击步骤查看全部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val data = remember(context) { AutomationToolPresentation(context) }
        val settings = LocalSettings.current
        val nav = LocalNavController.current
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(data.title, style = MaterialTheme.typography.headlineSmall)
                data.status?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = if (data.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
            data.task?.let { task ->
                item { Text(task.name, style = MaterialTheme.typography.titleLarge) }
                item {
                    task.definition?.let { AutomationDefinitionDetails(it, settings, task.enabled) } ?: TaskPreview(task, showName = false, compact = false)
                }
            }
            data.error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            items(data.tasks) { task ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) { TaskPreview(task, compact = false) }
                }
            }
            items(data.runs) { run ->
                Surface(
                    onClick = { run.conversationId?.let { onDismissRequest(); nav.navigate(Screen.Chat(it)) } },
                    enabled = run.conversationId != null,
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutomationRunContent(run.state, run.time, run.detail)
                        if (run.conversationId != null) Text("查看聊天", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            data.choices.forEach { (title, choices) ->
                item { Text(title, style = MaterialTheme.typography.titleMediumEmphasized) }
                if (choices.isEmpty()) item { Text("暂无可选内容", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(choices) { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun TaskPreview(task: AutomationTaskPreview, showName: Boolean = true, compact: Boolean = true, pendingSave: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showName) Text(task.name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        if (task.schedule.isNotBlank()) Text(task.schedule, maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        task.definition?.let { Text("${modeLabel(it.mode)} · ${taskModelLabel(it, LocalSettings.current)}", style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (task.enabled != null) Text(
            if (pendingSave) { if (task.enabled) "保存后启用排程" else "保存后停用排程" }
            else if (!task.enabled) "已停用" else task.nextRunAt?.let { "下次：${formatTaskTime(it)}" } ?: "排程已启用",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
