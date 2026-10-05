package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理工具的参数契约单测（不需要真实子代理运行）。
 * 重点覆盖输入校验分支：这些分支直接决定模型拿到的是可执行的调用还是可读的错误。
 */
class SubagentToolsTest {

    @Test
    fun `all five tools preserve callback JSON and trim control identifiers`() = runBlocking {
        val response = """{"status":"ok","result":"kept","durationMs":42,"files":["/workspace/subagents/result.md"]}"""
        var followup: Pair<String, String>? = null
        var polled: String? = null
        var cancelled: String? = null
        var listCalls = 0
        val tools = listOf(
            createSubagentTool { _, _, _, _, _ -> response } to buildJsonObject { put("prompt", "prompt") },
            createFollowupAgentTool { id, message -> followup = id to message; response } to buildJsonObject { put("sessionId", " child "); put("message", "next") },
            createPollAgentTool { id -> polled = id; response } to buildJsonObject { put("taskId", " child ") },
            createCancelAgentTool { id -> cancelled = id; response } to buildJsonObject { put("taskId", " child ") },
            createListAgentsTool { listCalls++; response } to buildJsonObject {},
        )
        assertEquals(SUBAGENT_TOOL_NAMES, tools.map { it.first.name }.toSet())
        tools.forEach { (tool, input) -> assertEquals(response, (tool.execute(input).single() as UIMessagePart.Text).text) }
        assertEquals("child" to "next", followup)
        assertEquals("child", polled)
        assertEquals("child", cancelled)
        assertEquals(1, listCalls)
    }

    @Test
    fun `spawn rejects negative limits before callback`() {
        val tool = createSubagentTool { _, _, _, _, _ -> error("must not execute") }
        listOf("timeoutMs" to 0, "timeoutMs" to -1, "maxToolCalls" to -1).forEach { (name, value) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { tool.execute(buildJsonObject { put("prompt", "prompt"); put(name, value) }) }
            }
        }
    }

    @Test
    fun `spawn rejects blank prompt`() {
        val tool = createSubagentTool { _, _, _, _, _ -> "unused" }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { tool.execute(buildJsonObject { put("description", "x"); put("prompt", "  ") }) }
        }
        assertTrue(e.message.orEmpty().contains("non-empty prompt"))
    }

    @Test
    fun `spawn forwards parsed options to callback`() = runBlocking {
        var captured: List<Any?>? = null
        val tool = createSubagentTool { description, prompt, async, timeoutMs, maxToolCalls ->
            captured = listOf(description, prompt, async, timeoutMs, maxToolCalls)
            """{"status":"ok","result":"done"}"""
        }
        val result = tool.execute(
            buildJsonObject {
                put("description", "research")
                put("prompt", "do it")
                put("async", true)
                put("timeoutMs", 5000)
                put("maxToolCalls", 3)
            }
        )
        assertEquals(listOf<Any?>("research", "do it", true, 5000L, 3), captured)
        assertTrue((result.single() as UIMessagePart.Text).text.contains("done"))
    }

    @Test
    fun `spawn ignores legacy agent fields and delegates the same task`() = runBlocking {
        val captured = mutableListOf<List<Any?>>()
        val tool = createSubagentTool { description, prompt, async, timeoutMs, maxToolCalls ->
            captured.add(listOf(description, prompt, async, timeoutMs, maxToolCalls))
            """{"status":"ok"}"""
        }
        val task = buildJsonObject { put("description", "review"); put("prompt", "Check changes and report issues") }
        tool.execute(task)
        listOf(JsonPrimitive("deleted-role"), JsonNull, buildJsonObject { put("name", "old") }).forEach { legacy ->
            tool.execute(buildJsonObject { task.forEach { (key, value) -> put(key, value) }; put("agent", legacy) })
        }
        assertEquals(4, captured.size)
        assertTrue(captured.all { it == listOf<Any?>("review", "Check changes and report issues", false, null, null) })
    }

    @Test
    fun `spawn delegates task instructions without additional configuration`() = runBlocking {
        var capturedPrompt: String? = null
        val tool = createSubagentTool { description, prompt, async, timeoutMs, maxToolCalls ->
            capturedPrompt = prompt
            assertEquals("subtask", description)
            assertFalse(async)
            assertEquals(null, timeoutMs)
            assertEquals(null, maxToolCalls)
            """{"status":"ok"}"""
        }
        val prompt = "Analyze the failure; use inherited workspace; report cause and fix."
        tool.execute(buildJsonObject { put("prompt", prompt) })
        assertEquals(prompt, capturedPrompt)
    }

    @Test
    fun `followup rejects blank sessionId or message with structured error`() = runBlocking {
        val tool = createFollowupAgentTool { _, _ -> "should-not-run" }
        val blankSession = tool.execute(buildJsonObject { put("sessionId", " "); put("message", "hi") })
        val blankMessage = tool.execute(buildJsonObject { put("sessionId", "abc"); put("message", "") })
        listOf(blankSession, blankMessage).forEach { result ->
            val json = result.single().let { (it as UIMessagePart.Text).text }
            assertTrue(json.contains("\"status\":\"error\""))
        }
    }

    @Test
    fun `poll rejects blank taskId`() = runBlocking {
        val tool = createPollAgentTool { "should-not-run" }
        val result = tool.execute(buildJsonObject { put("taskId", "") })
        assertTrue((result.single() as UIMessagePart.Text).text.contains("poll_agent requires a non-empty taskId"))
    }

    @Test
    fun `cancel rejects blank taskId`() = runBlocking {
        val tool = createCancelAgentTool { "should-not-run" }
        val result = tool.execute(buildJsonObject { put("taskId", "  ") })
        assertTrue((result.single() as UIMessagePart.Text).text.contains("cancel_agent requires a non-empty taskId"))
    }

    @Test
    fun `spawn schema exposes only task and execution options`() {
        val tool = createSubagentTool { _, _, _, _, _ -> "" }
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(setOf("description", "prompt", "async", "timeoutMs", "maxToolCalls"), schema.properties.keys)
        assertEquals(listOf("description", "prompt"), schema.required)
        assertFalse(tool.description.contains("specialist"))
        assertTrue(tool.description.contains("expected deliverables"))
    }
}
