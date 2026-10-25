package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.automation.AutomationTask
import me.rerere.rikkahub.data.automation.TaskDefinition
import me.rerere.rikkahub.data.automation.TaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AutomationToolOptionsTest {
    private val current = Uuid.random()
    private val target = Uuid.random()
    private val models = JsonArray(listOf(JsonPrimitive("model-choice")))
    private val chats = JsonArray(listOf(JsonPrimitive("chat-choice")))
    private val messages = JsonArray(listOf(JsonPrimitive("user-message")))
    private val loaded = mutableListOf<String>()

    private suspend fun options(arguments: JsonObject = JsonObject(emptyMap())) = buildAutomationOptions(
        arguments = arguments,
        conversationId = current,
        models = {
            loaded += "models"
            models
        },
        conversations = {
            loaded += "chats"
            chats
        },
        userMessages = {
            assertEquals(target, it)
            loaded += "messages"
            messages
        },
    )

    @Test fun `preparing request ids never loads models chats or messages`() = runTest {
        val result = options()
        assertTrue(loaded.isEmpty())
        assertEquals(setOf("new_task_id", "new_request_id", "current_conversation_id"), result.keys)
        assertEquals(current.toString(), result["current_conversation_id"]?.jsonPrimitive?.content)
        val taskId = Uuid.parse(result.getValue("new_task_id").jsonPrimitive.content)
        val requestId = Uuid.parse(result.getValue("new_request_id").jsonPrimitive.content)
        assertNotEquals(taskId, requestId)
        assertNotEquals(result["new_task_id"], options()["new_task_id"])
    }

    @Test fun `explicit model selection does not enumerate chats`() = runTest {
        val result = options(buildJsonObject { put("include_models", true) })
        assertEquals(listOf("models"), loaded)
        assertEquals(models, result["models"])
        assertFalse(result.containsKey("conversations"))
    }

    @Test fun `explicit target selection does not load model choices`() = runTest {
        val result = options(buildJsonObject { put("include_conversations", true) })
        assertEquals(listOf("chats"), loaded)
        assertEquals(chats, result["conversations"])
        assertFalse(result.containsKey("models"))
    }

    @Test fun `a selected regeneration target only loads its user messages`() = runTest {
        val result = options(buildJsonObject { put("target_conversation_id", target.toString()) })
        assertEquals(listOf("messages"), loaded)
        assertEquals(messages, result["user_messages"])
        assertFalse(result.containsKey("conversations") || result.containsKey("models"))
    }

    @Test fun `both choice lists can still be requested explicitly`() = runTest {
        val result = options(buildJsonObject {
            put("include_models", true)
            put("include_conversations", true)
        })
        assertEquals(listOf("models", "chats"), loaded)
        assertEquals(models, result["models"])
        assertEquals(chats, result["conversations"])
    }

    @Test fun `false flags leave all configuration sources untouched`() = runTest {
        options(buildJsonObject {
            put("include_models", false)
            put("include_conversations", false)
        })
        assertTrue(loaded.isEmpty())
    }

    @Test fun `invalid selection flags fail before reading configuration`() = runTest {
        for (value in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull, JsonArray(emptyList()))) {
            assertTrue(runCatching { options(JsonObject(mapOf("include_conversations" to value))) }.isFailure)
        }
        assertTrue(loaded.isEmpty())
    }

    @Test fun `invalid target ids fail before reading any configuration`() = runTest {
        assertTrue(runCatching {
            options(buildJsonObject {
                put("include_models", true)
                put("target_conversation_id", "not-an-id")
            })
        }.isFailure)
        assertTrue(loaded.isEmpty())
    }

    @Test fun `task summary identifies target and execution mode without reading chats`() {
        val modelId = Uuid.random().toString()
        val definition = TaskDefinition(
            name = "daily follow up",
            prompt = "report",
            mode = TaskMode.FOLLOW_UP,
            assistantId = Uuid.random().toString(),
            conversationId = target.toString(),
            modelId = modelId
        )
        val task =
            AutomationTask(
                Uuid.random().toString(),
                definition.assistantId,
                definition,
                true,
                "revision",
                1000L,
                0L,
                0L
            )
        val result = taskSummary(task)
        assertEquals("FOLLOW_UP", result["mode"]?.jsonPrimitive?.content)
        assertEquals(target.toString(), result["conversation_id"]?.jsonPrimitive?.content)
        assertEquals(modelId, result["model_id"]?.jsonPrimitive?.content)
        assertEquals(task.id, result["id"]?.jsonPrimitive?.content)
    }

    @Test fun `new chat summaries keep absent targets and model overrides null`() {
        val definition = TaskDefinition(name = "daily", prompt = "report", assistantId = Uuid.random().toString())
        val task =
            AutomationTask(
                Uuid.random().toString(),
                definition.assistantId,
                definition,
                false,
                "revision",
                null,
                0L,
                0L
            )
        val result = taskSummary(task)
        assertEquals("NEW_CHAT", result["mode"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, result["conversation_id"])
        assertEquals(JsonNull, result["model_id"])
    }
}
