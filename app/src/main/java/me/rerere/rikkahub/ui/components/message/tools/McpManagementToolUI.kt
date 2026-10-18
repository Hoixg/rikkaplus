@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.rikkahub.data.ai.tools.McpManagementToolNames
import me.rerere.rikkahub.ui.context.LocalSettings

object McpManagementToolUI {
    val all: List<ToolUIRenderer> = listOf(
        McpConfigRenderer(McpManagementToolNames.LIST, "查看 MCP 配置"),
        McpConfigRenderer(McpManagementToolNames.WRITE, "配置 MCP 服务"),
        McpConfigRenderer(McpManagementToolNames.DELETE, "删除 MCP 服务"),
    )
}

private class McpConfigRenderer(override val toolName: String, private val label: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.McpServer

    @Composable
    override fun title(context: ToolUIContext): String {
        val id = context.arguments.getStringContent("server_id")
        val name = LocalSettings.current.mcpServers.find { it.id.toString() == id }?.commonOptions?.name
            ?: context.arguments.getStringContent("name")
        return if (name.isNullOrBlank()) label else "$label · $name"
    }
}
