package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.configureMcpServer
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.ai.mcp.transportType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

object McpManagementToolNames {
    const val LIST = "list_mcp_servers"
    const val WRITE = "configure_mcp_server"
    const val DELETE = "delete_mcp_server"
}

/** Use the existing settings store and agent approval mechanism; no separate config file or runtime. */
fun createMcpManagementTools(store: SettingsStore): List<Tool> {
    if (!store.settingsFlow.value.allowAiManageMcp) return emptyList()
    return listOf(
        Tool(
            name = McpManagementToolNames.LIST,
            description = "List saved MCP connections and their IDs. Header values and OAuth secrets are not returned.",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = {
                val settings = store.settingsFlow.value.also(::requireMcpManagementAllowed)
                output(buildJsonObject {
                    put("servers", JsonArray(settings.mcpServers.map(::describeMcpServer)))
                })
            },
        ),
        Tool(
            name = McpManagementToolNames.WRITE,
            description = """
                Create or edit one saved MCP connection. Omit server_id to create; provide an ID from list_mcp_servers
                to edit. New connections require name and url. For edits, omitted fields retain their latest values,
                including headers. Provided headers replace the header map; {} clears it. Names use ASCII letters
                and digits. Supports the app's existing SSE and Streamable HTTP transports. Tool permissions and
                OAuth state are preserved. This tool cannot remove a server; use delete_mcp_server for deletion.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {
                    put("server_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Existing server ID to edit. Omit to create a new connection.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Unique name containing only ASCII letters and digits.")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("sse", "streamable_http").map(::JsonPrimitive)))
                    })
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "HTTP or HTTPS MCP endpoint URL.")
                    })
                    put("headers", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", buildJsonObject { put("type", "string") })
                        put("description", "Connection headers. Omit to preserve existing values.")
                    })
                    put("enabled", buildJsonObject { put("type", "boolean") })
                })
            },
            execute = { input ->
                val args = input.jsonObject
                val id = args.string("server_id")?.let(Uuid::parse)
                var saved: McpServerConfig? = null
                store.update { latest ->
                    requireMcpManagementAllowed(latest)
                    val current = id?.let { serverId ->
                        latest.mcpServers.find { it.id == serverId } ?: error("MCP server not found")
                    }
                    val name = args.string("name")?.trim() ?: current?.commonOptions?.name
                        ?: error("name is required for a new MCP connection")
                    require(latest.mcpServers.none { it.id != id && it.commonOptions.name == name }) {
                        "An MCP server with this name already exists; edit its server_id instead"
                    }
                    val updated = configureMcpServer(name, args, current)
                    saved = updated
                    latest.copy(mcpServers = if (current == null) {
                        latest.mcpServers + updated
                    } else {
                        latest.mcpServers.map { if (it.id == id) updated else it }
                    })
                }
                output(describeMcpServer(checkNotNull(saved)))
            },
        ),
        Tool(
            name = McpManagementToolNames.DELETE,
            description = "Delete one MCP connection after user approval. " +
                "Use the ID and current name from list_mcp_servers.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("server_id", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Current server name, shown in the approval request.")
                        })
                    },
                    required = listOf("server_id", "name"),
                )
            },
            execute = { input ->
                val args = input.jsonObject
                val id = Uuid.parse(args.string("server_id") ?: error("server_id is required"))
                val name = args.string("name") ?: error("name is required")
                store.update { latest ->
                    requireMcpManagementAllowed(latest)
                    val current = latest.mcpServers.find { it.id == id } ?: error("MCP server not found")
                    require(current.commonOptions.name == name) { "MCP server was renamed; request approval again" }
                    latest.copy(mcpServers = latest.mcpServers.filterNot { it.id == id })
                }
                output(buildJsonObject {
                    put("deleted", true)
                    put("server_id", id.toString())
                    put("name", name)
                })
            },
        ),
    )
}

private fun requireMcpManagementAllowed(settings: Settings) {
    check(settings.allowAiManageMcp) { "AI MCP management is disabled in Extensions > MCP" }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun output(value: JsonObject): List<UIMessagePart> = listOf(UIMessagePart.Text(value.toString()))

private fun describeMcpServer(server: McpServerConfig): JsonObject = buildJsonObject {
    put("server_id", server.id.toString())
    put("name", server.commonOptions.name)
    put("type", server.transportType)
    put("url", server.serverUrl)
    put("enabled", server.commonOptions.enable)
    put("header_names", JsonArray(server.commonOptions.headers.map { JsonPrimitive(it.first) }))
    put("oauth_authorized", server.commonOptions.oauth?.isAuthorized == true)
    put("tools", JsonArray(server.commonOptions.tools.map { JsonPrimitive(it.name) }))
}
