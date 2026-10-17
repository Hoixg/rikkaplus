package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.runAgent
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.tools.SUBAGENT_RESULT_INLINE_CHARS
import me.rerere.rikkahub.data.ai.tools.restrictChildTools
import me.rerere.rikkahub.data.ai.tools.childAssistant
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.getConversationChatModel
import java.io.File
import kotlin.uuid.Uuid

/** Uses the same request transformers, approval gates and session persistence as ordinary chat. */
internal class SubagentRunner(
    private val context: Application,
    private val requestFactory: ChatRequestFactory,
    private val sessions: ConversationSessionManager,
    private val repository: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val manager: SubagentManager,
) {
    suspend fun run(id: Uuid, config: SubagentExecutionConfig, prompt: String, followup: Boolean): String =
        sessions.withSession(id) { session ->
            if (!session.tryStartSubagent(currentCoroutineContext().job)) {
                return@withSession SubagentManager.errorJson("AGENT_SESSION_BUSY", "Child session is already in use", id)
            }
            val startedAt = System.currentTimeMillis()
            val parent = config.parent
            if (followup) session.initialize(load = { repository.getConversationById(id) ?: error("AGENT_SESSION_NOT_FOUND") })
            if (followup && session.hasPendingToolApprovals()) {
                return@withSession SubagentManager.errorJson("AGENT_SESSION_BUSY", "Child session has a pending tool approval", id)
            }
            val previous = if (followup) session.state.value else null
            check(!followup || previous?.parentConversationId == parent.id) { "AGENT_SESSION_NOT_FOUND" }
            val base = (previous ?: Conversation(
                id = id,
                assistantId = parent.assistantId,
                title = manager.tasks.value[id]?.description.orEmpty(),
                messageNodes = emptyList(),
                customSystemPrompt = parent.customSystemPrompt,
                config = parent.config,
                modeInjectionIds = parent.modeInjectionIds,
                lorebookIds = parent.lorebookIds,
                workspaceCwd = parent.workspaceCwd,
                modelOverrideId = config.model.id,
                parentConversationId = parent.id,
            ))
            session.initialize(load = { if (followup) repository.getConversationById(id) else null }, create = { base })
            session.update { latest -> latest.copy(messageNodes = latest.messageNodes + UIMessage.user(prompt).toMessageNode()) }
            val foregroundId = Uuid.random()
            val foreground = !config.scheduledExecution && ChatGenerationForegroundService.acquire(context, foregroundId, parent.id)
            var failure: Throwable? = null
            var finishReason: AgentFinishReason? = null
            var before: Map<String, Pair<Long, Long>>? = null
            var after: Map<String, Pair<Long, Long>> = emptyMap()
            try {
                session.flush()
                before = snapshotWorkspace(config)
                val assistant = childAssistant(config.assistant, parent)
                val model = (config.settings.getConversationChatModel(session.state.value) ?: config.model).let {
                    if (config.maxToolCalls == 0) it.copy(tools = emptySet()) else it
                }
                val turn = requestFactory.createContext(
                    config.settings, session.state.value,
                    modelOverride = model,
                    assistantOverride = assistant,
                    getMessages = { session.state.value.currentMessages },
                    scheduledExecution = config.scheduledExecution,
                    toolFilter = { if (config.maxToolCalls == 0) emptyList() else restrictChildTools(it) },
                )
                val replyNodeId = Uuid.random()
                val (request, hooks) = requestFactory.createAgent(
                    turn, session.state.value.requestWindowMessages(), session.processingStatus,
                    maxSteps = config.maxSteps,
                    maxToolCalls = config.maxToolCalls,
                    resetTurnTracker = false,
                )
                runAgent(request, hooks).collect { event ->
                    if (event is AgentEvent.MessageUpdated) {
                        session.update { it.upsertMessage(replyNodeId, event.message) }
                        manager.updateMessages(id, session.state.value.currentMessages)
                    }
                    if (event is AgentEvent.Finished) finishReason = event.reason
                }
            } catch (e: Throwable) {
                failure = e
            } finally {
                withContext(NonCancellable) {
                    try {
                        session.finishGeneration()
                        after = snapshotWorkspace(config)
                    } catch (e: Exception) {
                        if (failure == null) failure = e else failure?.addSuppressed(e)
                    } finally {
                        if (foreground) ChatGenerationForegroundService.release(context, foregroundId)
                    }
                }
            }
            val messages = session.state.value.currentMessages
            val latest = messages.drop(base.currentMessages.size).lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
            if (failure == null && finishReason != AgentFinishReason.Completed) {
                failure = IllegalStateException("Child reached its tool step or turn budget before completing")
            }
            val answer = latest?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty()
            val tools = messages.drop(base.currentMessages.size).flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            val status = when (failure) {
                is TimeoutCancellationException -> "timeout"
                is CancellationException -> "cancelled"
                null -> if (answer.isBlank()) "empty_output" else "ok"
                else -> "error"
            }
            val result = withContext(NonCancellable + Dispatchers.IO) {
                val endedAt = System.currentTimeMillis()
                buildJsonObject {
                    put("status", status)
                    put("description", manager.tasks.value[id]?.description.orEmpty())
                    put("sessionId", id.toString())
                    put("result", inlineResult(id, answer))
                    if (tools.isNotEmpty()) {
                        val grouped = tools.groupBy { it.toolName }
                        if (status == "empty_output") put("toolCalls", grouped.entries.joinToString(", ") { (name, calls) -> "$name x${calls.size}" })
                        put("toolCallsDetail", buildJsonArray { grouped.forEach { (name, calls) -> add(buildJsonObject {
                            put("name", name); put("count", calls.size)
                        }) } })
                    }
                    put("toolCallCount", tools.size)
                    put("startedAt", startedAt)
                    put("endedAt", endedAt)
                    put("durationMs", endedAt - startedAt)
                    put("files", buildJsonArray { before?.let { baseline -> after.filter { (path, stat) -> baseline[path] != stat }.keys.forEach { add("/workspace/$it") } } })
                    failure?.let { put("error", it.message ?: it.javaClass.simpleName) }
                }.toString()
            }
            manager.recordPartial(id, result)
            (failure as? CancellationException)?.let { throw it }
            result
        }

    private fun inlineResult(id: Uuid, answer: String): String {
        if (answer.length <= SUBAGENT_RESULT_INLINE_CHARS) return answer
        val output = File(context.filesDir, "${FileFolders.TOOL_OUTPUTS}/$id.md")
        output.parentFile?.mkdirs()
        output.writeText(answer)
        val suffix = "\n[Truncated; full result: /tool_outputs/$id.md]"
        return answer.take(SUBAGENT_RESULT_INLINE_CHARS - suffix.length) + suffix
    }

    private suspend fun snapshotWorkspace(config: SubagentExecutionConfig): Map<String, Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val workspace = config.assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) } ?: return@withContext emptyMap()
        val root = File(context.filesDir, "workspaces/${workspace.root}/files").canonicalFile
        if (!root.isDirectory) return@withContext emptyMap()
        buildMap {
            root.walkTopDown().maxDepth(6)
                .onEnter { it.canonicalFile.toPath().startsWith(root.toPath()) && it.name != ".git" }
                .take(5_000).forEach { file ->
                currentCoroutineContext().ensureActive()
                if (file.isFile && file.canonicalFile.toPath().startsWith(root.toPath())) {
                    put(file.relativeTo(root).invariantSeparatorsPath, file.length() to file.lastModified())
                }
            }
        }
    }
}
