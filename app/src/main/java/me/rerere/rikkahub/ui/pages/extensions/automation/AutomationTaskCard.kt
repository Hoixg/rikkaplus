package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.data.automation.AutomationRun
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.ui.components.automation.formatTaskTime

@Composable
internal fun AutomationTaskCard(
    task: AutomationTask, run: AutomationRun?, busy: Boolean, assistant: String, model: String,
    onEdit: () -> Unit, onEnabledChange: (Boolean) -> Unit, onRun: () -> Unit, onHistory: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onEdit, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(task.definition.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Switch(checked = task.enabled, enabled = !busy, onCheckedChange = onEnabledChange)
            }
            Text(task.definition.schedule.describe(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$assistant · $model", style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(task.nextRunAt?.let { "下次执行：${formatTaskTime(it)}" } ?: if (task.enabled) "暂无后续时间" else "排程已停用",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                run?.let { Text(it.state.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(enabled = !busy, onClick = onRun) { Text(if (run == null) "立即执行" else "取消本次") }
                TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("编辑") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, "任务更多操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("运行记录") }, onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text("删除任务", color = MaterialTheme.colorScheme.error) }, enabled = run == null && !busy,
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
