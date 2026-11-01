package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

enum class McpManagementOperation(val key: String) {
    LIST("list"),
    CREATE("create"),
    EDIT("edit"),
    DELETE("delete"),
}

@Serializable
data class McpManagementApproval(
    val skipApproval: Boolean = false,
    val exemptOperations: Set<String> = emptySet(),
) {
    fun needsApproval(operation: McpManagementOperation): Boolean =
        !skipApproval || operation.key !in exemptOperations
}

/** A supplied server ID selects editing; invalid IDs still cannot bypass edit approval. */
fun mcpWriteOperation(arguments: JsonElement): McpManagementOperation {
    val args = arguments as? JsonObject ?: return McpManagementOperation.EDIT
    return if (args["server_id"] == null || args["server_id"] == JsonNull) {
        McpManagementOperation.CREATE
    } else {
        McpManagementOperation.EDIT
    }
}
