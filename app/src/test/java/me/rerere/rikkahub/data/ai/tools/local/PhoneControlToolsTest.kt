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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.accessibility.PhoneAction
import me.rerere.rikkahub.accessibility.PhoneControlBackend
import me.rerere.rikkahub.accessibility.PhoneControlConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlToolsTest {
    private var config = PhoneControlConfig(enabled = true)
    private val backend = FakeBackend()
    private fun tools(owner: String = "chat-a") = createPhoneControlTools(owner, backend) { config }
    private val click = buildJsonObject {
        put("action", "click")
        put("snapshot_id", "snapshot")
        put("node_id", "1")
    }

    @Test fun `disabled control exposes only status and blocks previously created data tools`() = runBlocking {
        val stale = tools()
        config = config.copy(enabled = false)
        assertEquals(listOf("phone_status"), tools().map { it.name })
        stale.filter { it.name != "phone_status" }.forEach { tool ->
            assertTrue(runCatching { tool.execute(click) }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0, backend.calls)
        stale.single { it.name == "phone_status" }.execute(buildJsonObject {})
        assertEquals(1, backend.statusCalls)
    }

    @Test fun `write operations wait for approval denial does not operate and approval resumes`() = runBlocking {
        val pending = throughAgent("phone_action", click, ToolApprovalState.Auto)
        assertEquals(AgentFinishReason.AwaitingApproval(setOf("phone-call")), pending.reason)
        assertTrue(pending.message.getTools().single().input.contains("example.app"))
        assertEquals(0, backend.calls)
        val denied = throughAgent("phone_action", click, ToolApprovalState.Denied("stop"))
        assertEquals(AgentFinishReason.Completed, denied.reason)
        assertEquals(0, backend.calls)
        val approved = throughAgent("phone_action", click, ToolApprovalState.Approved)
        assertEquals(AgentFinishReason.Completed, approved.reason)
        assertEquals(1, backend.calls)
        assertEquals("chat-a", backend.lastOwner)
    }

    @Test fun `keyboard submission and directional scrolling retain the normal action approval`() = runBlocking {
        listOf("ime_enter", "scroll_up", "scroll_down", "scroll_left", "scroll_right").forEach { kind ->
            val args = buildJsonObject {
                put("action", kind)
                put("snapshot_id", "snapshot")
                put("node_id", "1")
            }
            assertEquals(
                AgentFinishReason.AwaitingApproval(setOf("phone-call")),
                throughAgent("phone_action", args, ToolApprovalState.Auto).reason
            )
            assertEquals(0, backend.calls)
            assertEquals(
                AgentFinishReason.Completed,
                throughAgent("phone_action", args, ToolApprovalState.Denied("stop")).reason
            )
            assertEquals(0, backend.calls)
        }
    }

    @Test fun `reads and app listing skip approval while app opening requires approval`() = runBlocking {
        val set = tools()
        assertFalse(set.single { it.name == "phone_screen" }.needsApproval(buildJsonObject {}))
        val apps = set.single { it.name == "phone_apps" }
        assertFalse(apps.needsApproval(buildJsonObject { put("action", "list") }))
        val open = buildJsonObject {
            put("action", "open")
            put("package_name", "example.app")
        }
        assertTrue(apps.needsApproval(open))
        assertEquals(
            AgentFinishReason.AwaitingApproval(setOf("phone-call")),
            throughAgent("phone_apps", open, ToolApprovalState.Auto).reason
        )
        assertEquals(0, backend.calls)
        assertEquals(AgentFinishReason.Completed, throughAgent("phone_apps", open, ToolApprovalState.Approved).reason)
        assertEquals(1, backend.calls)
    }

    @Test fun `existing tools use the latest approval setting`() = runBlocking {
        val action = tools().single { it.name == "phone_action" }
        assertTrue(action.needsApproval(click))
        config = config.copy(approvalRequired = false)
        assertFalse(action.needsApproval(click))
        assertEquals(AgentFinishReason.Completed, throughAgent("phone_action", click, ToolApprovalState.Auto).reason)
        assertEquals(1, backend.calls)
        config = config.copy(approvalRequired = true)
        assertTrue(action.needsApproval(click))
    }

    @Test fun `screen images remain tool output and conversation owner reaches the backend`() = runBlocking {
        val parts = tools(
            "chat-b"
        ).single { it.name == "phone_screen" }.execute(buildJsonObject { put("action", "screenshot") })
        assertEquals("chat-b", backend.lastOwner)
        assertEquals(2, parts.size)
        assertTrue(parts.last() is UIMessagePart.Image)
        assertTrue(backend.screenshot)
    }

    @Test fun `invalid actions never reach Android backend`() = runBlocking {
        val action = tools().single { it.name == "phone_action" }
        listOf(buildJsonObject {}, buildJsonObject {
            put("action", "input")
            put("snapshot_id", "x")
        }).forEach {
            assertTrue(runCatching { action.execute(it) }.isFailure)
        }
        val apps = tools().single { it.name == "phone_apps" }
        assertTrue(runCatching { apps.execute(buildJsonObject { put("action", "open") }) }.isFailure)
        assertTrue(runCatching { apps.execute(buildJsonObject { put("action", "arbitrary_intent") }) }.isFailure)
        assertEquals(0, backend.calls)
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
        var statusCalls = 0
        var calls = 0
        var lastOwner = ""
        var screenshot = false
        override suspend fun status(): JsonObject {
            statusCalls++
            return buildJsonObject { put("service_connected", false) }
        }
        override suspend fun prepareAction(owner: String, arguments: JsonObject): JsonObject = buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("target_package", "example.app")
        }
        override suspend fun screen(owner: String, screenshot: Boolean): List<UIMessagePart> {
            calls++
            lastOwner = owner
            this.screenshot = screenshot
            return listOf(UIMessagePart.Text("{\"snapshotId\":\"snapshot\"}")) +
                if (screenshot) listOf(UIMessagePart.Image("file:///phone-test.png")) else emptyList()
        }
        override suspend fun action(owner: String, action: PhoneAction): JsonObject {
            calls++
            lastOwner = owner
            return buildJsonObject { put("success", true) }
        }
        override suspend fun apps(owner: String, action: String, packageName: String?, query: String?): JsonObject {
            calls++
            lastOwner = owner
            return buildJsonObject { put("success", true) }
        }
    }
}
