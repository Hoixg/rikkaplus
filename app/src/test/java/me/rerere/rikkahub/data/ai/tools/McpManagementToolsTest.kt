package me.rerere.rikkahub.data.ai.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.toSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagementToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Exercise the actual SettingsStore and preference serializers without platform-specific file renames.
    private val disk = object : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
    private val store by lazy { SettingsStore(disk, scope) }
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "Test"),
        url = "https://example.com/mcp",
    )

    @After
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    private suspend fun enable() {
        store.awaitLoaded()
        store.update { it.copy(allowAiManageMcp = true, mcpServers = listOf(base)) }
    }

    private suspend fun call(name: String, arguments: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val tool = createMcpManagementTools(store).single { it.name == name }
        val text = tool.execute(buildJsonObject(arguments)).single() as UIMessagePart.Text
        return me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(text.text).jsonObject
    }

    @Test
    fun `management is off by default and revoking access blocks previously created tools`() = runBlocking {
        store.awaitLoaded()
        assertTrue(createMcpManagementTools(store).isEmpty())
        enable()
        val staleTools = createMcpManagementTools(store)
        store.update { it.copy(allowAiManageMcp = false) }
        staleTools.forEach { tool ->
            val attempt = runCatching {
                tool.execute(buildJsonObject {
                    put("server_id", base.id.toString())
                    put("name", "Test")
                    put("url", "https://new.example/mcp")
                })
            }
            assertTrue(attempt.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(listOf(base), store.settingsFlow.value.mcpServers)
    }

    @Test
    fun `writing updates stored preferences used by the existing settings UI`() = runBlocking {
        enable()
        val result = call(McpManagementToolNames.WRITE) {
            put("name", "NewServer")
            put("url", "https://new.example/mcp")
            put("headers", buildJsonObject { put("X-Key", "secret") })
        }
        val saved = disk.data.first().toSettings().mcpServers.single { it.commonOptions.name == "NewServer" }
        assertEquals(result["server_id"]!!.jsonPrimitive.content, saved.id.toString())
        assertEquals(listOf("X-Key" to "secret"), saved.commonOptions.headers)
        assertFalse(result.toString().contains("secret"))
        assertFalse(call(McpManagementToolNames.LIST).toString().contains("secret"))
    }

    @Test
    fun `edits preserve latest OAuth headers and tool approvals`() = runBlocking {
        enable()
        val write = createMcpManagementTools(store).single { it.name == McpManagementToolNames.WRITE }
        val latestOptions = base.commonOptions.copy(
            headers = listOf("Authorization" to "header-secret"),
            tools = listOf(McpTool(name = "remove", needsApproval = true)),
            oauth = McpOAuthState(enabled = true, accessToken = "oauth-secret"),
        )
        store.update { it.copy(titlePrompt = "keep", mcpServers = listOf(base.copy(commonOptions = latestOptions))) }
        write.execute(buildJsonObject {
            put("server_id", base.id.toString())
            put("name", "Renamed")
        })
        val saved = store.settingsFlow.value.mcpServers.single()
        assertEquals(latestOptions.copy(name = "Renamed"), saved.commonOptions)
        assertEquals("keep", store.settingsFlow.value.titlePrompt)
        assertFalse(call(McpManagementToolNames.LIST).toString().contains("oauth-secret"))
    }

    @Test
    fun `write cannot delete another server and invalid edits leave stored data intact`() = runBlocking {
        enable()
        call(McpManagementToolNames.WRITE) {
            put("name", "Other")
            put("url", "https://other.example/mcp")
        }
        val before = store.settingsFlow.value.mcpServers
        assertTrue(runCatching {
            call(McpManagementToolNames.WRITE) {
                put("server_id", base.id.toString())
                put("url", "file:///invalid")
            }
        }.isFailure)
        assertEquals(before, store.settingsFlow.value.mcpServers)
        call(McpManagementToolNames.WRITE) {
            put("server_id", base.id.toString())
            put("enabled", false)
        }
        assertEquals(before.map { it.id }, store.settingsFlow.value.mcpServers.map { it.id })
    }

    @Test
    fun `delete waits for ordinary agent approval and denial keeps the connection`() = runBlocking {
        enable()
        val pending = deleteThroughAgent(ToolApprovalState.Auto)
        assertEquals(AgentFinishReason.AwaitingApproval(setOf("delete-call")), pending.reason)
        assertEquals(ToolApprovalState.Pending, pending.message.getTools().single().approvalState)
        assertEquals(listOf(base), store.settingsFlow.value.mcpServers)
        deleteThroughAgent(ToolApprovalState.Denied("keep"))
        assertEquals(listOf(base), store.settingsFlow.value.mcpServers)
        val approved = deleteThroughAgent(ToolApprovalState.Approved)
        assertEquals(AgentFinishReason.Completed, approved.reason)
        val output = approved.message.getTools().single().output.single() as UIMessagePart.Text
        assertTrue(output.text.contains("\"deleted\":true"))
        assertTrue(store.settingsFlow.value.mcpServers.isEmpty())
    }

    @Test
    fun `renaming a pending deletion target requires a new approval`() = runBlocking {
        enable()
        val delete = createMcpManagementTools(store).single { it.name == McpManagementToolNames.DELETE }
        store.update {
            it.copy(mcpServers = listOf(base.copy(commonOptions = base.commonOptions.copy(name = "NewName"))))
        }
        assertTrue(runCatching {
            delete.execute(buildJsonObject {
                put("server_id", base.id.toString())
                put("name", "Test")
            })
        }.isFailure)
        assertEquals("NewName", store.settingsFlow.value.mcpServers.single().commonOptions.name)
    }

    private suspend fun deleteThroughAgent(state: ToolApprovalState): AgentEvent.Finished {
        val part = UIMessagePart.Tool(
            toolCallId = "delete-call",
            toolName = McpManagementToolNames.DELETE,
            input = buildJsonObject {
                put("server_id", base.id.toString())
                put("name", "Test")
            }.toString(),
            approvalState = state,
        )
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ) = TextGenerationResult("done", "test", UIMessage.assistant("done"))
            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<StreamChunk> = error("No network or streaming in this test")
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) =
                StepRequest(messages, TextGenerationParams(Model(modelId = "test")))
        }
        return runAgent(AgentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))),
            tools = createMcpManagementTools(store),
            stream = false,
        ), hooks).toList().last() as AgentEvent.Finished
    }
}
