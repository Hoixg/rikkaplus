package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneControlBackend
import me.rerere.rikkahub.accessibility.PhoneControlConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlToolsTest {
    private var config = PhoneControlConfig(enabled = true)
    private val backend = FakeBackend()
    private fun tools() = createPhoneControlTools(backend) { config }
    private val click = buildJsonObject {
        put("by", "text")
        put("value", "Send")
    }

    @Test fun `disabled access exposes only status and revokes previously created tools`() = runBlocking {
        val previous = tools()
        config = config.copy(enabled = false)
        assertEquals(listOf("phone_status"), tools().map { it.name })
        previous.filter { it.name != "phone_status" }.forEach {
            assertTrue(runCatching { it.execute(click) }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0, backend.calls)
        previous.single { it.name == "phone_status" }.execute(buildJsonObject {})
        assertEquals(1, backend.statusCalls)
    }

    @Test fun `node actions accept upstream selectors without a snapshot protocol`() = runBlocking {
        val pending = throughAgent("click_node", click, ToolApprovalState.Auto)
        assertEquals(AgentFinishReason.AwaitingApproval(setOf("phone-call")), pending.reason)
        assertTrue(pending.message.getTools().single().input.contains("example.app"))
        assertEquals(0, backend.calls)
        assertEquals(
            AgentFinishReason.Completed,
            throughAgent("click_node", click, ToolApprovalState.Denied("stop")).reason
        )
        assertEquals(0, backend.calls)
        assertEquals(AgentFinishReason.Completed, throughAgent("click_node", click, ToolApprovalState.Approved).reason)
        assertEquals(1, backend.calls)
    }

    @Test fun `every upstream write tool follows the existing configurable approval`() = runBlocking {
        tools().filter { it.name in PHONE_ACTION_TOOLS }.forEach { tool ->
            assertTrue(tool.needsApproval(click))
            assertEquals(
                AgentFinishReason.AwaitingApproval(setOf("phone-call")),
                throughAgent(tool.name, click, ToolApprovalState.Auto).reason
            )
            assertEquals(
                AgentFinishReason.Completed,
                throughAgent(tool.name, click, ToolApprovalState.Denied("stop")).reason
            )
        }
        assertEquals(0, backend.calls)
    }

    @Test fun `tree screenshot selectors and app listing are read operations`() = runBlocking {
        listOf("read_window_tree", "take_screenshot", "find_node", "list_installed_apps").forEach { name ->
            assertFalse(tools().single { it.name == name }.needsApproval(buildJsonObject {}))
        }
    }

    @Test fun `live approval settings are reused rather than copied into another store`() = runBlocking {
        val tap = tools().single { it.name == "tap" }
        assertTrue(tap.needsApproval(click))
        config = config.copy(approvalRequired = false)
        assertFalse(tap.needsApproval(click))
        assertEquals(AgentFinishReason.Completed, throughAgent("tap", click, ToolApprovalState.Auto).reason)
        assertEquals(1, backend.calls)
        config = config.copy(approvalRequired = true)
        assertTrue(tap.needsApproval(click))
    }

    @Test fun `screenshots stay as image attachments without requiring a preceding tree read`() = runBlocking {
        val parts = tools().single { it.name == "take_screenshot" }.execute(buildJsonObject {})
        assertEquals(1, backend.calls)
        assertEquals("take_screenshot", backend.lastTool)
        assertTrue(parts.first() is UIMessagePart.Image)
    }

    @Test fun `disabling access while a tool runs stops returning captured data`() = runBlocking {
        backend.afterExecute = { config = config.copy(enabled = false) }
        assertTrue(
            runCatching { tools().single { it.name == "read_window_tree" }.execute(buildJsonObject {}) }.isFailure
        )
        assertEquals(1, backend.calls)
    }
    private suspend fun throughAgent(name: String, args: JsonObject, state: ToolApprovalState): AgentEvent.Finished {
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams
            ) =
                TextGenerationResult("done", "test", UIMessage.assistant("done"))
            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams
            ): Flow<StreamChunk> =
                error("No network in tests")
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(
                messages: List<UIMessage>
            ) = StepRequest(messages, TextGenerationParams(Model(modelId = "test")))
        }
        val part = UIMessagePart.Tool(
            toolCallId = "phone-call",
            toolName = name,
            input = args.toString(),
            approvalState = state
        )
        return runAgent(AgentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))),
            tools = tools(),
            stream = false
        ), hooks)
            .toList().last() as AgentEvent.Finished
    }

    private class FakeBackend : PhoneControlBackend {
        var calls = 0
        var statusCalls = 0
        var lastTool: String? = null
        var afterExecute: () -> Unit = {}
        override fun tools(): List<Tool> = (PHONE_TOOL_NAMES - "phone_status").map { name ->
            Tool(name, "test", execute = { emptyList() })
        }
        override suspend fun status(): JsonObject {
            statusCalls++
            return buildJsonObject { put("service_connected", false) }
        }
        override suspend fun prepare(arguments: JsonObject): JsonObject = buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("target_package", "example.app")
        }
        override suspend fun execute(tool: Tool, arguments: JsonObject): List<UIMessagePart> {
            calls++
            lastTool = tool.name
            afterExecute()
            return (if (tool.name ==
                "take_screenshot") {
                listOf(UIMessagePart.Image("file:///phone-test.png"))
            } else {
                emptyList()
            }) +
                UIMessagePart.Text("{}")
        }
    }
}
