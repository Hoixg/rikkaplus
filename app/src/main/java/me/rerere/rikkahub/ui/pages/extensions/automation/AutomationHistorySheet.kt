package me.rerere.rikkahub.ui.pages.extensions.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.automation.AutomationRepository
import me.rerere.rikkahub.ui.components.automation.AutomationRunContent
import me.rerere.rikkahub.ui.components.automation.taskModelLabel
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
internal fun AutomationHistorySheet(taskId: String, repository: AutomationRepository, onDismiss: () -> Unit) {
    val entries by remember(taskId) { repository.historyFlow(taskId) }.collectAsStateWithLifecycle(emptyList())
    val settings = LocalSettings.current
    val nav = LocalNavController.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("运行记录", style = MaterialTheme.typography.headlineSmall) }
            if (entries.isEmpty()) item { Text("尚未执行", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { run ->
                Surface(onClick = { run.conversationId?.let { onDismiss(); nav.navigate(Screen.Chat(it)) } }, enabled = run.conversationId != null,
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutomationRunContent(run.state.label, run.dueAt, run.detail,
                            "${if (run.manual) "手动执行" else "定时执行"} · ${run.executionModelName.ifBlank { taskModelLabel(run.definition, settings) }}")
                        if (run.conversationId != null) Text("查看聊天", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
