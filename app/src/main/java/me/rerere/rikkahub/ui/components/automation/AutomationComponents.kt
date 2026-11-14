package me.rerere.rikkahub.ui.components.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

internal fun modeLabel(mode: TaskMode) = when (mode) {
    TaskMode.NEW_CHAT -> "新建聊天"
    TaskMode.FOLLOW_UP -> "继续聊天"
    TaskMode.REGENERATE -> "重新生成"
}

internal fun taskModelLabel(definition: TaskDefinition, settings: Settings, followingModel: Model? = null): String {
    val model = definition.modelId?.let { runCatching { settings.findModelById(Uuid.parse(it)) }.getOrNull() }
        ?: followingModel?.takeIf { definition.modelId == null }
        ?: if (definition.modelId == null && definition.mode == TaskMode.NEW_CHAT) {
            settings.assistants.firstOrNull { it.id.toString() == definition.assistantId }?.let {
                settings.findModelById(it.chatModelId ?: settings.chatModelId)
            }
        } else null
    return model?.let { listOfNotNull(it.displayName.ifBlank { it.modelId }, it.findProvider(settings.providers)?.name).joinToString(" · ") }
        ?: if (definition.modelId != null) "指定模型已删除" else if (definition.mode == TaskMode.NEW_CHAT) "跟随助手模型" else "跟随目标聊天模型"
}

internal fun formatTaskTime(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

@Composable
internal fun AutomationSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    clearDescription: String = "清除$label",
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClear != null) IconButton(onClick = onClear) {
            Icon(HugeIcons.Cancel01, clearDescription, modifier = Modifier.size(18.dp))
        } else Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The full prompt belongs in the detail sheet, never inside a chat step's nested scroll area. */
@Composable
internal fun AutomationDefinitionDetails(definition: TaskDefinition, settings: Settings, enabled: Boolean?) {
    val nav = LocalNavController.current
    CardGroup {
        item(headlineContent = { Text("时间安排") }, supportingContent = { Text(definition.schedule.describe()) })
        item(headlineContent = { Text("执行方式") }, supportingContent = { Text(modeLabel(definition.mode)) })
        item(headlineContent = { Text("助手与模型") }, supportingContent = {
            val assistant = settings.assistants.firstOrNull { it.id.toString() == definition.assistantId }
            Text("${assistant?.name?.ifBlank { "默认助手" } ?: "助手已删除"} · ${taskModelLabel(definition, settings)}")
        })
        definition.conversationId?.let { id -> item(
            onClick = { nav.navigate(Screen.Chat(id)) }, headlineContent = { Text("目标聊天") },
            supportingContent = { Text("查看聊天", color = MaterialTheme.colorScheme.primary) },
        ) }
        item(
            headlineContent = { Text("后续排程") }, supportingContent = { Text(when (enabled) { true -> "启用"; false -> "停用"; null -> "保持当前状态" }) },
        )
        item(headlineContent = { Text("执行结果通知") }, supportingContent = { Text(if (definition.notify) "开启" else "关闭") })
        if (definition.resetContextBeforeRun) item(
            headlineContent = { Text("每次清空上下文和记录", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("每次执行前删除目标聊天的旧消息、分支和压缩摘要，仅保留本轮记录。") },
        )
        if (definition.mode != TaskMode.REGENERATE) formItem(headlineContent = { Text("执行提示词") }) {
            SelectionContainer { Text(definition.prompt, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun AutomationRunContent(state: String, time: Long?, detail: String?, model: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state, style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.weight(1f))
            time?.let { Text(formatTaskTime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!model.isNullOrBlank()) Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}
