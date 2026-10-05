package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test

class ChildAgentToolsTest {
    @Test fun `effective parent prompt and assistant configuration preserve child restrictions`() {
        val assistant = Assistant(enableSubagents = true, systemPrompt = "assistant prompt", allowConversationSystemPrompt = true)
        val parent = Conversation.ofId(kotlin.uuid.Uuid.random()).copy(customSystemPrompt = "actual override")
        val child = childAssistant(assistant, parent)
        assertTrue(child.systemPrompt.startsWith("actual override"))
        assertFalse(child.systemPrompt.contains("<agent_role>"))
        assertTrue(child.systemPrompt.contains("cannot spawn or manage agents"))
        assertFalse(child.enableSubagents)
        assertFalse(child.allowConversationSystemPrompt)
        assertTrue(childAssistant(assistant.copy(allowConversationSystemPrompt = false), parent).systemPrompt.startsWith("assistant prompt"))
        assertEquals(assistant.copy(enableSubagents = false, allowConversationSystemPrompt = false, systemPrompt = child.systemPrompt), child)
        assertTrue(assistant.enableSubagents)
    }

    @Test fun `child inherits available tools except forbidden execution capabilities`() {
        val names = SUBAGENT_TOOL_NAMES + setOf("scheduled_task", "manage_skill", "manage_mcp_server", "ask_user", "grant_directory_access", "todo_write", "read", "write")
        val tools = names.map { name -> Tool(name, name, execute = { emptyList() }) }
        assertEquals(setOf("read", "write"), restrictChildTools(tools).map { it.name }.toSet())
    }

    @Test fun `approval is evaluated using prepared actual parameters and denied before side effect`() = runBlocking {
        var executions = 0
        val original = Tool("read_or_write", "test",
            prepareArguments = { JsonObject(it.jsonObject + ("prepared" to JsonPrimitive(true))) },
            needsApproval = { it.jsonObject["write"]?.jsonPrimitive?.booleanOrNull == true },
            execute = { executions++; listOf(UIMessagePart.Text("done")) },
        )
        val child = restrictChildTools(listOf(original)).single()
        val read = child.prepareArguments(buildJsonObject { put("write", false) })
        assertFalse(child.needsApproval(read))
        child.execute(read)
        val write = child.prepareArguments(buildJsonObject { put("write", true) })
        assertTrue(runCatching { child.execute(write) }.exceptionOrNull()?.message.orEmpty().contains("requires user approval"))
        assertEquals(1, executions)
    }

    @Test fun `adjacent agent calls overlap but normal writes and reads remain ordered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val bothStarted = CompletableDeferred<Unit>()
        var starts = 0
        var written = false
        val calls = listOf("spawn_agent", "followup_agent", "write", "read")
        val execution = async {
            executeToolsInOrder(calls, { it }) { call ->
                when (call) {
                    "spawn_agent", "followup_agent" -> {
                        starts++
                        if (starts == 2) bothStarted.complete(Unit)
                        gate.await()
                    }
                    "write" -> written = true
                    "read" -> assertTrue(written)
                }
                call
            }
        }
        withTimeout(5_000) { bothStarted.await() }
        assertFalse(written)
        gate.complete(Unit)
        assertEquals(calls, execution.await())
    }

    @Test fun `parent cancellation cancels every concurrent child call`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var active = 0
        val job = launch {
            executeToolsInOrder(listOf("spawn_agent", "spawn_agent"), { it }) {
                active++
                if (active == 2) started.complete(Unit)
                try { awaitCancellation() } finally { active-- }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, active)
    }
}
