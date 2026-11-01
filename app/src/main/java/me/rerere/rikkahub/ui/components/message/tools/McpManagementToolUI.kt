@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManagementOperation
import me.rerere.rikkahub.data.ai.mcp.mcpWriteOperation
import me.rerere.rikkahub.data.ai.tools.McpManagementToolNames
import me.rerere.rikkahub.ui.context.LocalSettings

object McpManagementToolUI {
    val all: List<ToolUIRenderer> = listOf(
        McpConfigRenderer(McpManagementToolNames.LIST),
        McpConfigRenderer(McpManagementToolNames.WRITE),
        McpConfigRenderer(McpManagementToolNames.DELETE),
    )
}

private class McpConfigRenderer(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.McpServer

    @Composable
    override fun title(context: ToolUIContext): String {
        val label = stringResource(
            when (toolName) {
                McpManagementToolNames.LIST -> R.string.setting_mcp_management_operation_list
                McpManagementToolNames.WRITE -> if (mcpWriteOperation(context.arguments ?: JsonObject(emptyMap())) ==
                    McpManagementOperation.CREATE) {
                    R.string.setting_mcp_management_operation_create
                } else {
                    R.string.setting_mcp_management_operation_edit
                }
                else -> R.string.setting_mcp_management_operation_delete
            }
        )
        val id = context.arguments.getStringContent("server_id")
        val name = LocalSettings.current.mcpServers.find { it.id.toString() == id }?.commonOptions?.name
            ?: context.arguments.getStringContent("name")
        return if (name.isNullOrBlank()) label else "$label · $name"
    }
}
