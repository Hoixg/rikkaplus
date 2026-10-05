package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.SUBAGENT_RESULT_INLINE_CHARS
import me.rerere.rikkahub.data.ai.tools.restrictChildTools
import me.rerere.rikkahub.data.ai.tools.childAssistant
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File
import kotlin.uuid.Uuid

/** Uses the same request transformers, approval gates and session persistence as ordinary chat. */
internal class SubagentRunner(
    private val context: Application,
    private val generationLoop: GenerationLoop,
    private val toolFactory: ChatToolFactory,
    private val templateTransformer: TemplateTransformer,
    private val sessions: ConversationSessionManager,
    private val repository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val manager: SubagentManager,
    private val save: suspend (Uuid, Conversation) -> Unit,
) {
    suspend fun run(id: Uuid, config: SubagentExecutionConfig, prompt: String, followup: Boolean): String =
        sessions.withSession(id) { session ->
            if (!session.tryStartSubagent(currentCoroutineContext().job)) {
                return@withSession SubagentManager.errorJson("AGENT_SESSION_BUSY", "Child session is already in use", id)
            }
            val startedAt = System.currentTimeMillis()
            val parent = config.parent
            if (followup) session.initialize { repository.getConversationById(id) ?: error("AGENT_SESSION_NOT_FOUND") }
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
                modeInjectionIds = parent.modeInjectionIds,
                lorebookIds = parent.lorebookIds,
                workspaceCwd = parent.workspaceCwd,
                modelOverrideId = config.model.id,
                parentConversationId = parent.id,
            )).let { it.copy(messageNodes = it.messageNodes + UIMessage.user(prompt).toMessageNode()) }
            session.updateFromGeneration(base)
            val foregroundId = Uuid.random()
            val foreground = !config.scheduledExecution && ChatGenerationForegroundService.acquire(context, foregroundId, parent.id)
            var failure: Throwable? = null
            var before: Map<String, Pair<Long, Long>>? = null
            var after: Map<String, Pair<Long, Long>> = emptyMap()
            try {
                save(id, base)
                before = snapshotWorkspace(config)
                val assistant = childAssistant(config.assistant, parent)
                val model = config.model.let {
                    if (config.maxToolCalls == 0) it.copy(tools = emptySet()) else it
                }
                val tools = restrictChildTools(toolFactory.createTools(
                    settings = config.settings,
                    assistant = assistant,
                    model = model,
                    workspaceCwd = base.workspaceCwd,
                    getMessages = { session.state.value.currentMessages },
                    scheduledExecution = config.scheduledExecution,
                ))
                val window = base.requestContextForGeneration()
                generationLoop.generateText(
                    settings = config.settings,
                    model = model,
                    messages = window.messages,
                    assistant = assistant,
                    conversationId = id,
                    conversationSystemPrompt = base.customSystemPrompt,
                    compactionContext = window.checkpointContent,
                    conversationModeInjectionIds = base.modeInjectionIds,
                    conversationLorebookIds = base.lorebookIds,
                    workspaceCwd = base.workspaceCwd,
                    processingStatus = session.processingStatus,
                    memories = if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories() else memoryRepository.getMemoriesOfAssistant(assistant.id.toString()),
                    inputTransformers = listOf(TimeReminderTransformer, PromptInjectionTransformer, PlaceholderTransformer, DocumentAsPromptTransformer, OcrTransformer, templateTransformer, WorkspaceReminderTransformer(workspaceRepository), ImageToolResultTransformer),
                    outputTransformers = listOf(ThinkTagTransformer, Base64ImageToLocalFileTransformer, RegexOutputTransformer),
                    tools = tools,
                    maxSteps = config.maxSteps,
                    maxToolCalls = config.maxToolCalls,
                    resetTurnTracker = false,
                ).collect { chunk ->
                    if (chunk is GenerationChunk.Messages) {
                        session.updateFromGeneration(session.state.value.updateRequestWindowMessages(window, chunk.messages))
                        manager.updateMessages(id, chunk.messages)
                    }
                }
            } catch (e: Throwable) {
                failure = e
            } finally {
                withContext(NonCancellable) {
                    try {
                        session.finishGeneration { save(id, it) }
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
            if (failure == null && latest?.parts?.any { it is UIMessagePart.Tool } == true) {
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
