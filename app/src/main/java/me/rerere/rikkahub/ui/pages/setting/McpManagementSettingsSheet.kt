package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManagementOperation
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem

@Composable
internal fun McpManagementSettingsSheet(
    settings: Settings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val approval = settings.mcpManagementApproval
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_mcp_management_settings_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            CardGroup {
                switchItem(
                    checked = settings.allowAiManageMcp,
                    onCheckedChange = { enabled -> onUpdate { it.copy(allowAiManageMcp = enabled) } },
                    headlineContent = { Text(stringResource(R.string.setting_mcp_management_enabled_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_mcp_management_enabled_desc)) },
                )
            }
            CardGroup {
                switchItem(
                    checked = approval.skipApproval,
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(mcpManagementApproval = it.mcpManagementApproval.copy(skipApproval = enabled)) }
                    },
                    headlineContent = { Text(stringResource(R.string.setting_mcp_management_skip_approval_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_mcp_management_skip_approval_desc)) },
                )
            }
            if (approval.skipApproval) {
                CardGroup(title = { Text(stringResource(R.string.setting_mcp_management_exempt_operations_title)) }) {
                    listOf(
                        McpManagementOperation.LIST to R.string.setting_mcp_management_operation_list,
                        McpManagementOperation.CREATE to R.string.setting_mcp_management_operation_create,
                        McpManagementOperation.EDIT to R.string.setting_mcp_management_operation_edit,
                        McpManagementOperation.DELETE to R.string.setting_mcp_management_operation_delete,
                    ).forEach { (operation, title) ->
                        switchItem(
                            checked = operation.key in approval.exemptOperations,
                            onCheckedChange = { exempt ->
                                onUpdate { latest ->
                                    val current = latest.mcpManagementApproval
                                    latest.copy(mcpManagementApproval = current.copy(
                                        exemptOperations = if (exempt) current.exemptOperations + operation.key
                                        else current.exemptOperations - operation.key,
                                    ))
                                }
                            },
                            headlineContent = { Text(stringResource(title)) },
                        )
                    }
                }
            }
        }
    }
}
