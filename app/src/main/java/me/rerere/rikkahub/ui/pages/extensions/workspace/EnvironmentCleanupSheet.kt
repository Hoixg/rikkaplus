package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.UnusedWorkspaceEnvironment
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.utils.fileSizeToString

@Composable
internal fun EnvironmentCleanupSheet(
    state: EnvironmentCleanupState,
    onScan: () -> Unit,
    onClean: (UnusedWorkspaceEnvironment) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf<UnusedWorkspaceEnvironment?>(null) }
    val busy = state.loading || state.cleaningRoot != null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.workspace_cleanup_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.workspace_cleanup_description), style = MaterialTheme.typography.bodyMedium)
            if (!state.loading && state.entries.isNotEmpty() && !state.sharedReady) {
                Text(stringResource(R.string.workspace_cleanup_shared_required), color = MaterialTheme.colorScheme.error)
            }
            state.error?.let {
                Text(stringResource(R.string.error_title_operation), color = MaterialTheme.colorScheme.error)
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(stringResource(if (state.loading) R.string.workspace_detail_loading else R.string.workspace_cleanup_cleaning))
                }
            }
            if (!busy && state.error == null && state.entries.isEmpty()) {
                Text(stringResource(R.string.workspace_cleanup_empty))
            }
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.entries, key = { it.environment.root }) { entry ->
                    CardGroup {
                        formItem {
                            Text(entry.workspaceName ?: stringResource(R.string.workspace_cleanup_unassigned), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "workspaces/${entry.environment.root}/linux",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.workspace_cleanup_size, entry.environment.sizeBytes.fileSizeToString()),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(enabled = !busy && state.sharedReady, onClick = { pending = entry }) {
                                    Text(stringResource(R.string.common_delete))
                                }
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy, onClick = onScan) { Text(stringResource(R.string.workspace_cleanup_scan)) }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_card_close))
                }
            }
        }
    }
    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.workspace_cleanup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.workspaceName ?: stringResource(R.string.workspace_cleanup_unassigned))
                    Text("workspaces/${entry.environment.root}/linux")
                    Text(stringResource(R.string.workspace_cleanup_confirm_description))
                }
            },
            confirmButton = {
                TextButton(onClick = { pending = null; onClean(entry) }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
