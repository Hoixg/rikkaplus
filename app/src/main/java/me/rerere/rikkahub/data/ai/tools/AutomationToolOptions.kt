package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** Only load the configuration the operation actually needs; UUID preparation never reads chat history. */
internal suspend fun buildAutomationOptions(
    arguments: JsonObject,
    conversationId: Uuid,
    models: suspend () -> JsonArray,
    conversations: suspend () -> JsonArray,
    userMessages: suspend (Uuid) -> JsonArray,
): JsonObject {
    fun include(key: String): Boolean = arguments[key]?.let {
        val primitive = it as? JsonPrimitive
        require(primitive != null && !primitive.isString) { "$key must be a boolean" }
        requireNotNull(primitive.booleanOrNull) { "$key must be a boolean" }
    } ?: false
    val includeModels = include("include_models")
    val includeConversations = include("include_conversations")
    val targetId = arguments["target_conversation_id"]?.jsonPrimitive?.contentOrNull?.let(Uuid::parse)
    return buildJsonObject {
        put("new_task_id", Uuid.random().toString())
        put("new_request_id", Uuid.random().toString())
        put("current_conversation_id", conversationId.toString())
        if (includeModels) put("models", models())
        if (includeConversations) put("conversations", conversations())
        if (targetId != null) put("user_messages", userMessages(targetId))
    }
}
