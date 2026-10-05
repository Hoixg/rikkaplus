package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

internal fun CardGroupScope.subagentControls(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    item(
        headlineContent = { Text("启用子代理") },
        supportingContent = { Text("允许主模型拆分独立任务，子代理沿用本次模型与工作区") },
        trailingContent = { Switch(checked = assistant.enableSubagents, onCheckedChange = { onUpdate(assistant.copy(enableSubagents = it)) }) },
    )
}
