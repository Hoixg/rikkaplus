package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunStatus
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.ai.tools.applyToolApprovalDecision
import me.rerere.rikkahub.data.ai.tools.ScheduledTaskApprovalCoordinator
import me.rerere.rikkahub.data.ai.tools.isScheduledApproval
import me.rerere.rikkahub.data.ai.tools.decideScheduledTaskApproval
import me.rerere.rikkahub.data.ai.tools.SCHEDULED_APPROVAL_TIMEOUT_REASON
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.imageToolChatModel
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.ImageToolResultTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findRequestProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.getConversationChatModel
import me.rerere.rikkahub.utils.shouldAutoCompact
import me.rerere.rikkahub.utils.estimateTokenCount
import me.rerere.rikkahub.utils.estimateTextTokenCount
import me.rerere.rikkahub.utils.effectiveContextLength
import me.rerere.rikkahub.utils.chunkCompactionTexts
import me.rerere.rikkahub.utils.toCompactionText
import me.rerere.rikkahub.utils.estimateWindowTokens
import me.rerere.rikkahub.utils.selectCompactionPrefixKeepingLatestTurn
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    conversationId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = conversationId.toString(),
)

private const val AUTO_COMPRESS_TARGET_RATIO = 0.65f
private const val AUTO_COMPRESS_TARGET_TOKENS = 2_000
private const val AUTO_COMPRESS_INPUT_BUDGET_MAX = 12_000
private const val AUTO_COMPRESS_CONCURRENCY = 2
private const val AUTO_COMPRESS_REDUCE_LIMIT = 4

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 源标题已带 (N) 后缀时递增序号，避免多次 fork 后叠加成 xxx(1)(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source.title, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    config = source.config,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
    modelOverrideId = source.modelOverrideId,
)

internal fun insertContextCheckpoint(
    conversation: Conversation,
    afterNodeId: Uuid,
    summary: String,
): Conversation? = conversation.withContextCheckpoint(afterNodeId, summary)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    val subagentManager: SubagentManager,
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationLoop: GenerationLoop,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    private val unavailableConversations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()
    private val deletedConversations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()
    private val stoppingConversations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

    private fun requireWritableConversation(conversation: Conversation) {
        check(conversation.id !in unavailableConversations && conversation.id !in deletedConversations) { "Conversation is being removed or moved" }
        conversation.parentConversationId?.let { parentId ->
            check(parentId !in unavailableConversations && parentId !in deletedConversations && parentId !in stoppingConversations) {
                "Parent conversation is stopping, being removed or moved"
            }
        }
    }

    private val subagentRunner by lazy {
        SubagentRunner(context, generationLoop, chatToolFactory, templateTransformer, sessionManager,
            conversationRepo, memoryRepository, workspaceRepository, subagentManager, ::saveConversation)
    }

    private fun childAgentTools(settings: Settings, assistant: Assistant, model: Model, conversation: Conversation, scheduled: Boolean): List<me.rerere.ai.core.Tool> {
        if (!assistant.enableSubagents || ModelAbility.TOOL !in model.abilities || conversation.parentConversationId != null) return emptyList()
        fun parseId(raw: String) = runCatching { Uuid.parse(raw) }.getOrNull()
        return listOf(
            createSubagentTool { description, prompt, async, timeoutMs, maxToolCalls ->
                val config = SubagentExecutionConfig(
                    settings, assistant, model, conversation,
                    timeoutMs = minOf(timeoutMs ?: Long.MAX_VALUE, ToolRuntimeLimits.turnBudgetMs).coerceAtLeast(1),
                    maxSteps = minOf(32L, (maxToolCalls?.toLong()?.plus(1) ?: 32L)).toInt(),
                    maxToolCalls = maxToolCalls,
                    scheduledExecution = scheduled,
                )
                subagentManager.spawn(config, description, prompt, async, subagentRunner::run)
            },
            createFollowupAgentTool { raw, message ->
                parseId(raw)?.let { subagentManager.followup(conversation.id, it, message) }
                    ?: SubagentManager.errorJson(AGENT_SESSION_NOT_FOUND, "Invalid child session ID")
            },
            createPollAgentTool { raw -> parseId(raw)?.let { subagentManager.poll(conversation.id, it) }
                ?: SubagentManager.errorJson("AGENT_TASK_NOT_FOUND", "Invalid task ID") },
            createCancelAgentTool { raw -> parseId(raw)?.let { subagentManager.cancel(conversation.id, it) }
                ?: SubagentManager.errorJson("AGENT_TASK_NOT_FOUND", "Invalid task ID") },
            createListAgentsTool { subagentManager.list(conversation.id) },
        )
    }

    /** Stop writers before deleting rows; child finalizers cannot resurrect deleted conversations. */
    suspend fun deleteConversationTree(conversation: Conversation) = withContext(NonCancellable) {
        unavailableConversations.add(conversation.id)
        if (conversation.parentConversationId != null) subagentManager.forgetChild(conversation.id)
        stopGeneration(conversation.id)
        subagentManager.stopParent(conversation.id, forget = true)
        conversationRepo.getSubconversationsOfParentOnce(conversation.id).forEach { child ->
            unavailableConversations.add(child.id)
            subagentManager.forgetChild(child.id)
            stopGeneration(child.id)
            deleteStoppedConversation(child)
            java.io.File(context.filesDir, "tool_outputs/${child.id}.md").delete()
        }
        deleteStoppedConversation(conversation)
        if (conversation.parentConversationId != null) java.io.File(context.filesDir, "tool_outputs/${conversation.id}.md").delete()
    }

    private suspend fun deleteStoppedConversation(conversation: Conversation) {
        // Serialize with any late title/translation save that was already in flight.
        sessionManager.withSession(conversation.id) { session ->
            session.withPersistenceLock {
                deletedConversations.add(conversation.id)
                conversationRepo.deleteConversation(conversation)
            }
        }
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        conversationRepo.getConversationsOfAssistant(assistantId).first().forEach { deleteConversationTree(it) }
        // Older/restored backups may contain orphaned children.
        conversationRepo.deleteConversationOfAssistant(assistantId)
    }

    private val scheduledCompletions = java.util.concurrent.ConcurrentHashMap<Uuid, CompletableDeferred<String>>()
    private val scheduledFailures = java.util.concurrent.ConcurrentHashMap<Uuid, Throwable>()
    private val scheduledWorkerConversations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

    private val scheduledReservations = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()
    private val scheduledRunOwners = java.util.concurrent.ConcurrentHashMap<Uuid, String>()
    private val cancellingScheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()
    private val finishingScheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

    init {
        scheduledTaskRepository.stopConversation = { id, runId -> cancelScheduledConversation(Uuid.parse(id), runId) }

    }

    private suspend fun cancelScheduledConversation(id: Uuid, runId: String) {
        cancellingScheduled.add(id)
        try {
            val persisted = conversationRepo.getConversationById(id)
            val session = sessionManager.getOrCreate(id)
            if (persisted != null) session.initialize { persisted }
            val owner = scheduledTaskRepository.getActiveByConversation(id.toString())
            if (owner?.activeRunId != runId) return
            // Restored pending approvals do not have an in-memory owner yet.
            if (session.getJob() == null && owner.lastRunStatus == "WAITING_APPROVAL") scheduledRunOwners.putIfAbsent(id, runId)
            val jobs = synchronized(session) {
                if (scheduledRunOwners[id] != runId) emptyList() else {
                    session.messageQueue.pause()
                    session.cancelJobs()
                }
            }
            subagentManager.stopParent(id)
            jobs.forEach { it.join() }
            if (session.getJob() == null) finishInterruptedPendingTools(id)
        } finally {
            cancellingScheduled.remove(id)
            dispatchNextQueuedMessage(id)
        }
    }

    suspend fun startScheduledConversation(task: ScheduledTaskEntity, conversationId: Uuid): Deferred<String>? {
        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.getAssistantById(Uuid.parse(task.assistantId)) ?: error("任务所属助手已删除")
        val mode = me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.valueOf(task.mode)
        val sourceId = if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.NEW_CHAT) null
            else Uuid.parse(task.targetConversationId ?: error("目标会话未设置"))
        val source = sourceId?.let { id ->
            val session = sessionManager.getOrCreate(id)
            session.initialize { conversationRepo.getConversationById(id) ?: error("目标会话已删除") }
            require(session.state.value.assistantId == assistant.id) { "目标会话不属于任务助手" }
            requireWritableConversation(session.state.value)
            require(session.state.value.parentConversationId == null) { "定时任务不能使用子会话" }
            if (id in stoppingConversations) return null
            if (scheduledTaskRepository.getActiveByConversation(id.toString()) != null) return null
            if (!session.reserveForScheduledTask(scheduledReservations, finishingScheduled + cancellingScheduled)) return null
            session.state.value
        }
        var destinationId: Uuid? = null
        var started = false
        try {
            val modelId = task.modelOverrideId?.let(Uuid::parse) ?: source?.modelOverrideId ?: assistant.chatModelId ?: settings.chatModelId
            require(settings.findModelById(modelId) != null) { "任务模型已删除或未配置" }
            assistant.workspaceId?.let { workspaceId ->
                val workspace = workspaceRepository.getById(workspaceId.toString()) ?: error("任务工作区已删除")
                require(workspace.shellStatus != "BROKEN") { "任务工作区不可用" }
            }
            val conversation = when (mode) {
                me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.NEW_CHAT ->
                    Conversation.ofId(conversationId, assistant.id, newConversation = true)
                        .copy(title = task.name).updateCurrentMessages(assistant.presetMessages)
                me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.FOLLOW_UP -> source!!
                me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE -> {
                    val messageId = Uuid.parse(task.targetUserMessageId ?: error("目标用户消息未设置"))
                    require(source!!.currentMessages.any { it.id == messageId && it.role == MessageRole.USER }) { "目标用户消息已删除或分支已改变" }
                    forkConversationAtMessage(source.id, messageId)
                }
            }
            destinationId = conversation.id
            val session = sessionManager.getOrCreate(conversation.id)
            if (sourceId != conversation.id) scheduledReservations.add(conversation.id)
            if (!scheduledTaskRepository.bindConversation(task, conversation.id.toString())) return null
            val completion = CompletableDeferred<String>()
            scheduledRunOwners[conversation.id] = task.activeRunId!!
            scheduledCompletions[conversation.id] = completion
            scheduledWorkerConversations.add(conversation.id)
            session.updateConversation(conversation)
            if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.NEW_CHAT) conversationRepo.insertConversation(conversation)
            synchronized(session) {
                if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE) {
                    regenerateAtMessage(conversation.id, conversation.currentMessages.last { it.role == MessageRole.USER })
                } else {
                    sendQueuedMessage(session, QueuedMessage(parts = listOf(UIMessagePart.Text(task.prompt))))
                }
            }
            started = true
            return completion
        } finally {
            if (!started) destinationId?.let { id ->
                if (scheduledRunOwners.remove(id, task.activeRunId)) {
                    scheduledWorkerConversations.remove(id)
                    scheduledCompletions.remove(id)
                }
            }
            sourceId?.let { scheduledReservations.remove(it); dispatchNextQueuedMessage(it) }
            destinationId?.let { scheduledReservations.remove(it) }
        }
    }

    fun releaseScheduledWorker(conversationId: Uuid, completion: Deferred<String>? = null) {
        if (completion == null || scheduledCompletions[conversationId] === completion) {
            scheduledWorkerConversations.remove(conversationId)
            scheduledCompletions.remove(conversationId)
        }
    }

    private suspend fun finishScheduledSession(session: ConversationSession, failure: Throwable?, conversation: Conversation) {
        val task = scheduledTaskRepository.getActiveByConversation(session.id.toString()) ?: return
        val status = when {
            failure is kotlinx.coroutines.TimeoutCancellationException -> ScheduledTaskRunStatus.FAILED
            failure is CancellationException -> ScheduledTaskRunStatus.CANCELLED
            failure != null -> ScheduledTaskRunStatus.FAILED
            conversation.currentMessages.any { it.parts.any { part -> part is UIMessagePart.Tool && part.isPending } } ->
                ScheduledTaskRunStatus.WAITING_APPROVAL
            else -> ScheduledTaskRunStatus.SUCCESS
        }
        val error = failure?.message.orEmpty()
        if (status == ScheduledTaskRunStatus.FAILED) Log.e(TAG, error.ifBlank { "任务执行失败" }, failure)
        if (scheduledTaskRepository.finish(task, status, error, conversation.currentMessages.lastOrNull()?.toText().orEmpty())) {
            if (status.name != task.lastRunStatus || status.name != "WAITING_APPROVAL") {
                appEventBus.emit(AppEvent.ScheduledTaskEnded(session.id, task.name, status.name,
                    error.ifBlank { conversation.currentMessages.lastOrNull()?.toText().orEmpty().take(150) },
                    task.id, task.activeRunId.orEmpty(), task.notify, task.showPreview))
            }
        }
        if (status == ScheduledTaskRunStatus.WAITING_APPROVAL) scheduledWorkerConversations.remove(session.id)
        else scheduledRunOwners.remove(session.id, task.activeRunId)
        scheduledCompletions[session.id]?.complete(status.name)
    }

    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
    )

    private val scheduledApprovals = ScheduledTaskApprovalCoordinator(
        scope = appScope,
        expire = { conversationId, toolId -> handleToolApproval(Uuid.parse(conversationId), toolId, false, SCHEDULED_APPROVAL_TIMEOUT_REASON) },
        retain = { sessionManager.acquire(Uuid.parse(it)) },
        release = { sessionManager.release(Uuid.parse(it)) },
    )

    private fun observeScheduledApprovals(conversation: Conversation) {
        scheduledApprovals.observe(conversation.id.toString(), conversation.currentMessages.flatMap { it.getTools() })
    }

    init {
        appScope.launch {
            runCatching {
                conversationRepo.getScheduledApprovalConversationIds().forEach { id ->
                    sessionManager.withSession(id) { session ->
                        session.initialize { conversationRepo.getConversationById(id) ?: session.state.value }
                        observeScheduledApprovals(session.state.value)
                    }
                }
            }.onFailure { Log.e(TAG, "Unable to restore scheduled task approvals", it) }
        }
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        subagentManager.cleanup()
        scheduledApprovals.cancelAll()
        sessionManager.cleanup()
    }

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        if (session.state.value.parentConversationId != null) {
            if (cause != null) session.messageQueue.pause()
            if (session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) {
                session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
            }
            dispatchNextQueuedMessage(session.id)
            appScope.launch { appEventBus.emit(AppEvent.ChatTurnFinished(session.id)) }
            return
        }
        val completedConversation = session.state.value
        val scheduledFailure = scheduledFailures.remove(session.id) ?: cause
        scheduledWorkerConversations.remove(session.id)
        if (cause != null) session.messageQueue.pause()
        if (session.state.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) {
            session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        finishingScheduled.add(session.id)
        appScope.launch {
            try { finishScheduledSession(session, scheduledFailure, completedConversation) }
            catch (e: Exception) {
                scheduledCompletions[session.id]?.completeExceptionally(e)
                Log.e(TAG, "Unable to persist scheduled result", e)
            }
            finishingScheduled.remove(session.id)
            dispatchNextQueuedMessage(session.id)
            scheduledTaskRepository.requestDispatch()
            appEventBus.emit(AppEvent.ChatTurnFinished(session.id))
        }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground || conversationId in scheduledWorkerConversations) {
            return appScope.launch(start = CoroutineStart.LAZY) {
                block()
            }
        }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        sessionManager.withSession(conversationId) { session ->
            ensureInitialized(session)
            settingsStore.updateAssistant(session.state.value.assistantId)
            observeScheduledApprovals(session.state.value)
        }
    }

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize {
            loadConversation(session.id) ?: run {
                // 新建对话, 并添加预设消息
                // 当前助手要读已落盘的值：updateAssistant 只写盘，settingsFlow 要等解码完才跟上
                val assistant = settingsStore.settingsFlowRaw.first().getCurrentAssistant()
                Conversation.ofId(
                    id = session.id,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(assistant.presetMessages)
            }
        }
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        val bound = conversation.bindConfig(loadedSettings())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置。
    // 不读 settingsFlowRaw：它落后于还没写完盘的修改，刚在新会话里切的模型会被漏掉。
    private suspend fun loadedSettings(): Settings = settingsStore.settingsFlow.first { !it.init }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun moveQueuedMessage(conversationId: Uuid, messageId: Uuid, targetId: Uuid) {
        if (sessionManager.get(conversationId)?.messageQueue?.move(messageId, targetId) == true) {
            dispatchNextQueuedMessage(conversationId)
        }
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendQueuedMessageImmediately(conversationId: Uuid, messageId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val (generationJob, pendingAskUser) = synchronized(session) {
            if (session.messageQueue.prioritize(messageId) == null) return
            session.messageQueue.resume()
            val pendingAskUser = session.state.value.currentMessages.any { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool && part.toolName == "ask_user" && part.isPending
                }
            }
            session.getJob() to pendingAskUser
        }

        if (generationJob != null || pendingAskUser) {
            // The user is replacing the answer to ask_user with a new message. Wait until the
            // current turn has released the session, then close any ask_user question as skipped.
            appScope.launch {
                generationJob?.join()
                val stillWaitingForAskUser = session.state.value.currentMessages.any { message ->
                    message.parts.any { part ->
                        part is UIMessagePart.Tool && part.toolName == "ask_user" && part.isPending
                    }
                }
                if (session.messageQueue.state.value.priorityMessageId == messageId && stillWaitingForAskUser) {
                    finishInterruptedPendingTools(conversationId, skipAskUserQuestion = true)
                }
                dispatchNextQueuedMessage(conversationId)
            }
        } else {
            dispatchNextQueuedMessage(conversationId)
        }
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            requireWritableConversation(session.state.value)
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            requireWritableConversation(session.state.value)
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            if (conversationId in unavailableConversations || conversationId in stoppingConversations ||
                session.state.value.parentConversationId?.let { it in unavailableConversations || it in stoppingConversations || it in deletedConversations } == true) return null
            // A pending tool approval is still part of the current turn.
            if (conversationId in cancellingScheduled || conversationId in scheduledReservations || conversationId in finishingScheduled || session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: run {
                if (session.messageQueue.state.value.messages.isEmpty()) appScope.launch { scheduledTaskRepository.requestDispatch() }
                return null
            }
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = answer,
        ) {
            var submittedInputId: Uuid? = null
            var inputPersisted = false
            try {
                session.initialize { conversationRepo.getConversationById(conversationId) ?: session.state.value }
                requireWritableConversation(session.state.value)
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: if (scheduledTaskRepository.getActiveByConversation(conversationId.toString()) != null) error("任务所属助手已删除") else settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 对齐 YuiHub：普通发送前压缩旧上下文，新输入始终保留为近期消息。
                if (answer && settings.enableAutoCompaction) {
                    autoCompressIfNeeded(
                        conversationId = conversationId,
                        session = session,
                        pendingParts = processedContent,
                        processingStatus = session.processingStatus,
                    )
                }

                // 添加消息到列表
                val conversationBeforeInput = session.state.value
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                )
                submittedInputId = userMessage.id
                val newConversation = conversationBeforeInput.copy(
                    messageNodes = conversationBeforeInput.messageNodes + userMessage.toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                inputPersisted = true
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (conversationId in scheduledWorkerConversations) scheduledFailures[conversationId] = e
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) {
                    val inputWasSaved = submittedInputId?.let { messageId ->
                        session.state.value.messageNodes.any { node ->
                            node.messages.any { message -> message.id == messageId }
                        }
                    } ?: false
                    // Compaction runs before history insertion; stopping here must not discard typed input.
                    if (!inputPersisted && !inputWasSaved && conversationId !in scheduledWorkerConversations &&
                        scheduledTaskRepository.getActiveByConversation(conversationId.toString()) == null) {
                        session.messageQueue.requeueFront(queued)
                        session.messageQueue.pause()
                    }
                    throw e
                }
                if (e is ContextCompactionException) {
                    if (conversationId !in scheduledWorkerConversations && scheduledTaskRepository.getActiveByConversation(conversationId.toString()) == null) session.messageQueue.requeueFront(queued)
                    session.messageQueue.pause()
                    addError(
                        e,
                        conversationId,
                        title = context.getString(R.string.error_title_compress_context),
                    )
                    return@launchGenerationJob
                }
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        requireWritableConversation(session.state.value)
        val previousJob = session.getJob()

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                previousJob?.join()
                session.initialize { conversationRepo.getConversationById(conversationId) ?: session.state.value }
                requireWritableConversation(session.state.value)
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                if (conversationId in scheduledWorkerConversations) scheduledFailures[conversationId] = e
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        editedPrompt: String? = null,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        requireWritableConversation(session.state.value)
        val previousJob = session.getJob()
        val decisionAt = System.currentTimeMillis()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                afterPreviousGeneration(previousJob) {
                    session.initialize { conversationRepo.getConversationById(conversationId) ?: session.state.value }
                    requireWritableConversation(session.state.value)
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                if (isScheduledApproval(part)) {
                                                    decideScheduledTaskApproval(part, approved && answer == null && editedPrompt == null,
                                                        reason, decisionAt)
                                                } else applyToolApprovalDecision(part, approved, reason, answer, editedPrompt)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        scheduledTaskRepository.resumeConversation(conversationId.toString())
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                if (scheduledTaskRepository.getActiveByConversation(conversationId.toString()) != null) scheduledFailures[conversationId] = e
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(conversationId: Uuid, messageRange: ClosedRange<Int>? = null) {
        val task = scheduledTaskRepository.getActiveByConversation(conversationId.toString())
        task?.activeRunId?.let { scheduledRunOwners[conversationId] = it }
        if (task == null) handleMessageCompleteUnbounded(conversationId, messageRange)
        else try {
            kotlinx.coroutines.withTimeout(scheduledTaskRepository.remainingGenerationMs(task)) {
                handleMessageCompleteUnbounded(conversationId, messageRange)
                subagentManager.awaitChildren(conversationId)
            }
        } finally {
            withContext(NonCancellable) { subagentManager.stopParent(conversationId) }
        }
    }

    private suspend fun handleMessageCompleteUnbounded(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val scheduledTask = scheduledTaskRepository.getActiveByConversation(conversationId.toString())
        val initialConversation = getConversationFlow(conversationId).value
        currentCoroutineContext().ensureActive()
        requireWritableConversation(initialConversation)
        subagentManager.resumeParent(conversationId)
        val settings = settingsStore.settingsFlow.first()
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val storedAssistant = settings.getAssistantById(initialConversation.assistantId)
        val assistant = if (storedAssistant == null && scheduledTask != null) {
            error("任务所属助手已删除")
        } else {
            settings.getAssistantOf(initialConversation)
        }
        val model = scheduledTask?.modelOverrideId?.let { settings.findModelById(Uuid.parse(it)) }
            ?: settings.getConversationChatModel(initialConversation)
            ?: throw IllegalStateException("No chat model selected")
        val requestModel = imageToolChatModel(model, LocalToolOption.ImageGeneration in assistant.localTools)

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools(assistant).isNotEmpty() ||
                    LocalToolOption.ImageGeneration in assistant.localTools) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            val requestConversation = getConversationFlow(conversationId).value
            val requestWindow = requestConversation.requestContextForGeneration(messageRange)

            val tools = try {
                chatToolFactory.createTools(
                    settings = settings,
                    assistant = assistant,
                    model = requestModel,
                    workspaceCwd = conversation.workspaceCwd,
                    getMessages = { getConversationFlow(conversationId).value.currentMessages },
                    scheduledExecution = scheduledTask != null,
                    subagentTools = childAgentTools(settings, assistant, requestModel, conversation, scheduledTask != null),
                )
            } catch (error: InvalidMcpServerNamesException) {
                if (scheduledTask != null) scheduledFailures[conversationId] = error
                sessionManager.get(conversationId)?.messageQueue?.pause()
                addError(
                    error = IllegalStateException(
                        context.getString(
                            R.string.error_mcp_invalid_server_name,
                            error.names.joinToString(", "),
                        )
                    ),
                    conversationId = conversationId,
                )
                return
            }

            // start generating
            val session = sessionManager.getOrCreate(conversationId)
            generationLoop.generateText(
                settings = settings,
                model = requestModel,
                processingStatus = session.processingStatus,
                messages = requestWindow.messages,
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                compactionContext = requestWindow.checkpointContent,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(ImageToolResultTransformer)
                },
                outputTransformers = outputTransformers,
                tools = tools,
                shouldYieldAfterToolResults = {
                    session.messageQueue.state.value.priorityMessageId != null
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = session.finishGeneration { conversation ->
                    saveConversation(conversationId, conversation)
                }

                // 生成结束：后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        scheduledTask = scheduledTask != null,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateRequestWindowMessages(requestWindow, chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                    }
                }
            }
        }.onFailure {
            if (scheduledTask != null) scheduledFailures[conversationId] = it
            if (it is CancellationException) throw it
            sessionManager.get(conversationId)?.messageQueue?.pause()

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value

            if (sessionManager.get(conversationId)?.messageQueue?.state?.value?.priorityMessageId != null) {
                return@onSuccess
            }

            sessionManager.launchWithSession(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            sessionManager.launchWithSession(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    private fun skipAskUserTool(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (tool.toolName != "ask_user") return cancelToolByUser(tool)
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"skipped","reason":"A new queued message was sent instead of answering this question."}"""
                )
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        skipAskUserQuestion: Boolean = false,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val transform: (UIMessagePart.Tool) -> UIMessagePart.Tool =
            if (skipAskUserQuestion) ::skipAskUserTool else ::cancelToolByUser
        val updatedMessage = lastMessage.finishPendingTools(transform)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
            val provider = model.findRequestProvider(settings.providers)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.fastModelId)
                ?: return@runCatching
            val provider = model.findRequestProvider(settings.providers) ?: return@runCatching

            sessionManager.get(conversationId)?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, conversationId, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessionManager.get(conversationId)?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
    ): Result<Unit> = runCatching {
        val session = sessionManager.getOrCreate(conversationId)
        check(!session.isGenerating) {
            context.getString(R.string.chat_page_compress_blocked_generating)
        }

        val settings = settingsStore.settingsFlow.first()
        val model = settings.getConversationChatModel(conversation)
            ?: settings.getChatModelOf(conversation)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findRequestProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        val nodes = conversation.messageNodes
        val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val cutIndex = (nodes.size - keepRecentMessages.coerceAtLeast(0)).coerceAtLeast(0)
        if (cutIndex <= checkpointIndex + 1) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        // The current checkpoint carries the compressed prefix, so include it when creating
        // its replacement and retain only the messages that follow it.
        val messagesToCompress = nodes.subList(checkpointIndex.coerceAtLeast(0), cutIndex)
            .map { it.currentMessage }
        check(messagesToCompress.none { message ->
            message.getTools().any { it.isPending || it.canResumeExecution }
        }) {
            context.getString(R.string.chat_page_compress_pending_tools)
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= 256) return listOf(messages)
            val mid = messages.size / 2
            return splitMessages(messages.subList(0, mid)) + splitMessages(messages.subList(mid, messages.size))
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { message ->
                message.summaryAsText(maxLength = if (message.isContextCheckpoint) Int.MAX_VALUE else 2_000)
            }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName,
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, conversationId),
            )
            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress).map { chunk -> async { compressMessages(chunk) } }.awaitAll()
        }
        val boundaryNodeId = nodes[cutIndex - 1].id
        val sourceFingerprint = conversation.compressionSourceFingerprint(boundaryNodeId)
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
        val summary = compressedSummaries.joinToString("\n\n")

        val updatedConversation = synchronized(session) {
            check(!session.isGenerating) {
                context.getString(R.string.chat_page_compress_blocked_generating)
            }
            val latest = session.state.value
            if (latest.compressionSourceFingerprint(boundaryNodeId) != sourceFingerprint) {
                throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
            }
            val updated = insertContextCheckpoint(latest, boundaryNodeId, summary)
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
            updated.copy(chatSuggestions = emptyList()).also { updateConversation(conversationId, it) }
        }
        saveConversation(conversationId, updatedConversation)
    }

    private suspend fun autoCompressIfNeeded(
        conversationId: Uuid,
        session: ConversationSession,
        pendingParts: List<UIMessagePart>,
        processingStatus: MutableStateFlow<String?>,
    ) {
        val settings = settingsStore.settingsFlow.first()
        if (!settings.enableAutoCompaction) return
        val conversation = session.state.value
        val model = settings.getConversationChatModel(conversation) ?: return
        val windowTokens = model.effectiveContextLength()
        val pendingTokens = estimateTokenCount(
            listOf(UIMessage(role = MessageRole.USER, parts = pendingParts)),
        )
        val usedTokens = conversation.estimateWindowTokens(model) + pendingTokens
        if (!shouldAutoCompact(
                enabled = settings.enableAutoCompaction,
                usedTokens = usedTokens,
                windowTokens = windowTokens,
                thresholdPercent = settings.autoCompactionThresholdPercent,
                tokenLimit = settings.autoCompactionTokenLimit,
            )
        ) return

        val targetTokens = (windowTokens * AUTO_COMPRESS_TARGET_RATIO).toInt()
        val nodesToCompress = autoCompressNodes(conversation, windowTokens, pendingTokens)
        if (nodesToCompress.isEmpty()) {
            throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))
        }

        val compactingStatus = context.getString(R.string.chat_page_compacting_context)
        processingStatus.value = compactingStatus
        try {
            val summary = compressNodesToSummary(
                conversationId = conversationId,
                conversation = conversation,
                nodesToCompress = nodesToCompress,
                settings = settings,
                model = model,
                processingStatus = processingStatus,
            )
            val sourceTokens = nodesToCompress.sumOf { estimateTokenCount(listOf(it.currentMessage)) }
            if (estimateTextTokenCount(summary) >= sourceTokens) {
                throw ContextCompactionException(context.getString(R.string.error_compress_context_failed))
            }
            val boundaryNodeId = nodesToCompress.last().id
            val sourceFingerprint = conversation.compressionSourceFingerprint(boundaryNodeId)
                ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            val candidate = insertContextCheckpoint(conversation, boundaryNodeId, summary)
                ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            if (candidate.estimateWindowTokens(model) + pendingTokens > targetTokens) {
                throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))
            }
            persistContextCheckpoint(
                session = session,
                boundaryNodeId = boundaryNodeId,
                summary = summary,
                expectedFingerprint = sourceFingerprint,
                model = model,
                pendingTokens = pendingTokens,
                windowTokens = windowTokens,
            )
        } catch (error: ContextCompactionException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ContextCompactionException(
                context.getString(R.string.error_compress_context_failed),
                error,
            )
        } finally {
            processingStatus.value = null
        }
    }

    private fun autoCompressNodes(
        conversation: Conversation,
        windowTokens: Int,
        pendingTokens: Int,
    ): List<MessageNode> {
        val nodes = conversation.windowNodes()
        val summaryReserve = minOf(AUTO_COMPRESS_TARGET_TOKENS, (windowTokens * 0.08f).toInt())
        val systemReserve = maxOf(512, (windowTokens * 0.08f).toInt())
        val keepBudget = (
            windowTokens * AUTO_COMPRESS_TARGET_RATIO - pendingTokens - summaryReserve - systemReserve
        ).toInt().coerceAtLeast(0)
        return selectCompactionPrefixKeepingLatestTurn(
            nodes = nodes,
            keepBudgetTokens = keepBudget,
        )
    }

    private suspend fun persistContextCheckpoint(
        session: ConversationSession,
        boundaryNodeId: Uuid,
        summary: String,
        expectedFingerprint: String,
        model: Model,
        pendingTokens: Int,
        windowTokens: Int,
    ) {
        fun makeUpdatedConversation(conversation: Conversation): Conversation {
            if (conversation.compressionSourceFingerprint(boundaryNodeId) != expectedFingerprint) {
                throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            }
            val candidate = insertContextCheckpoint(conversation, boundaryNodeId, summary)
                ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            if (candidate.estimateWindowTokens(model) + pendingTokens >
                (windowTokens * AUTO_COMPRESS_TARGET_RATIO).toInt()
            ) {
                throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))
            }
            return candidate
        }

        session.withPersistenceLock {
            val (previous, updated) = synchronized(session) {
                val latest = session.state.value
                val candidate = makeUpdatedConversation(latest)
                session.updateConversation(candidate)
                latest to candidate
            }
            try {
                conversationRepo.updateConversation(updated)
            } catch (error: Exception) {
                synchronized(session) {
                    val latest = session.state.value
                    if (latest == updated) session.updateConversation(previous)
                }
                throw error
            }
        }
    }

    private suspend fun compressNodesToSummary(
        conversationId: Uuid,
        conversation: Conversation,
        nodesToCompress: List<MessageNode>,
        settings: Settings,
        model: Model,
        processingStatus: MutableStateFlow<String?>,
    ): String {
        if (nodesToCompress.isEmpty()) throw ContextCompactionException(
            context.getString(R.string.error_compress_context_failed),
        )
        return try {
            val provider = model.findRequestProvider(settings.providers)
                ?: throw IllegalStateException("Provider not found")
            val providerHandler = providerManager.getProviderByType(provider)
            val inputBudget = (model.effectiveContextLength() - 1_024)
                .coerceIn(512, AUTO_COMPRESS_INPUT_BUDGET_MAX)
            val contentBudget = inputBudget - 512
            if (contentBudget < 128) throw IllegalStateException("No room for a safe context summary")

            val mapTarget = (inputBudget / 6).coerceIn(128, 1_000)
            val reduceTarget = contentBudget.coerceAtMost(AUTO_COMPRESS_TARGET_TOKENS).coerceAtLeast(128)
            val sourceChunks = chunkCompactionTexts(
                nodesToCompress.map { it.currentMessage.toCompactionText() },
                (inputBudget - 512).coerceAtLeast(1),
            )
            val semaphore = Semaphore(AUTO_COMPRESS_CONCURRENCY)
            val progressMutex = Mutex()
            var completedMapChunks = 0

            processingStatus.value = context.getString(
                R.string.chat_page_compacting_context_map_progress,
                0,
                sourceChunks.size,
            )

            suspend fun reportMapChunkComplete() = progressMutex.withLock {
                completedMapChunks++
                processingStatus.value = context.getString(
                    R.string.chat_page_compacting_context_map_progress,
                    completedMapChunks,
                    sourceChunks.size,
                )
            }

            suspend fun summarize(content: String, targetTokens: Int, additionalContext: String = ""): String =
                semaphore.withPermit {
                    val promptTemplate = settings.compressPrompt
                    val renderedPrompt = promptTemplate.applyPlaceholders(
                        "content" to content,
                        "target_tokens" to targetTokens.toString(),
                        "additional_context" to additionalContext,
                        "locale" to Locale.getDefault().displayName,
                    )
                    val missingInputs = buildString {
                        if (!promptTemplate.contains("{content}")) {
                            appendLine()
                            appendLine("Conversation content to summarize:")
                            appendLine(content)
                        }
                        if (additionalContext.isNotBlank() &&
                            !promptTemplate.contains("{additional_context}")
                        ) {
                            appendLine()
                            appendLine(additionalContext)
                        }
                    }
                    val prompt = renderedPrompt + missingInputs
                    providerHandler.generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.user(prompt)),
                        params = backgroundTextGenerationParams(model, conversationId),
                    ).message.toText().trim().takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Empty compression result")
                }

            var summaries = coroutineScope {
                sourceChunks.map { chunk ->
                    async { summarize(chunk, mapTarget).also { reportMapChunkComplete() } }
                }.awaitAll()
            }
            var reducePasses = 0
            while (summaries.sumOf(::estimateTextTokenCount) > contentBudget) {
                if (reducePasses++ >= AUTO_COMPRESS_REDUCE_LIMIT) {
                    throw IllegalStateException("Could not reduce checkpoint to the model input budget")
                }
                val groups = chunkCompactionTexts(summaries, (inputBudget - 512).coerceAtLeast(1))
                val previousSize = summaries.sumOf(::estimateTextTokenCount)
                var completedReduceGroups = 0
                processingStatus.value = context.getString(
                    R.string.chat_page_compacting_context_reduce_progress,
                    reducePasses,
                    completedReduceGroups,
                    groups.size,
                )
                summaries = coroutineScope {
                    groups.map { group ->
                        async {
                            summarize(group, reduceTarget).also {
                                progressMutex.withLock {
                                    completedReduceGroups++
                                    processingStatus.value = context.getString(
                                        R.string.chat_page_compacting_context_reduce_progress,
                                        reducePasses,
                                        completedReduceGroups,
                                        groups.size,
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
                val reducedSize = summaries.sumOf(::estimateTextTokenCount)
                if (reducedSize >= previousSize && groups.size >= summaries.size) {
                    throw IllegalStateException("Checkpoint reduction did not converge")
                }
            }

            processingStatus.value = context.getString(R.string.chat_page_compacting_context_finalize)
            summarize(
                content = summaries.joinToString("\n\n"),
                targetTokens = reduceTarget,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ContextCompactionException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "compressNodesToSummary failed", error)
            throw ContextCompactionException(
                context.getString(R.string.error_compress_context_failed),
                error,
            )
        }
    }

    // ---- 聊天页配置 ----

    /**
     * 聊天页对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        sessionManager.withSession(conversationId) { session ->
            session.withPersistenceLock {
                ensureInitialized(session)
                val settings = settingsStore.settingsFlow.first()
                val conversation = session.state.value
                val stored = settings.getStoredAssistantOf(conversation)
                val updated = update(settings.getAssistantOf(conversation))
                val assistant = updated.withoutConversationFields(conversation, stored)
                session.updateMetadata(
                    update = { it.withAssistantUpdate(updated, settings) },
                    persist = conversationRepo::updateConversationConfig,
                )
                if (assistant != stored) {
                    settingsStore.update { latest ->
                        latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                    }
                }
            }
        }
    }

    /**
     * 切换搜索方式，传 null 的一项保持不变。
     * 模型内置搜索在会话开始前是模型自身的开关，开始后固定在会话上。
     */
    suspend fun updateChatSearch(
        conversationId: Uuid,
        enableWebSearch: Boolean? = null,
        builtInSearch: Boolean? = null,
    ) {
        sessionManager.withSession(conversationId) { session ->
            session.withPersistenceLock {
                ensureInitialized(session)
                val conversation = session.state.value
                if (conversation.config != null) {
                    session.updateMetadata(
                        update = {
                            it.copy(
                                config = it.config?.let { config ->
                                    config.copy(
                                        enableWebSearch = enableWebSearch ?: config.enableWebSearch,
                                        builtInSearch = builtInSearch ?: config.builtInSearch,
                                    )
                                }
                            )
                        },
                        persist = conversationRepo::updateConversationConfig,
                    )
                    return@withPersistenceLock
                }
                settingsStore.update { settings ->
                    val assistant = settings.getAssistantOf(conversation)
                    val model = settings.getChatModelOf(conversation)
                    settings.copy(
                        assistants = if (enableWebSearch == null) {
                            settings.assistants
                        } else {
                            settings.assistants.map {
                                if (it.id == assistant.id) it.copy(enableWebSearch = enableWebSearch) else it
                            }
                        },
                        providers = if (builtInSearch == null || model == null) {
                            settings.providers
                        } else {
                            settings.providers.map { provider ->
                                provider.editModel(
                                    model.copy(
                                        tools = if (builtInSearch) {
                                            model.tools + BuiltInTools.Search
                                        } else {
                                            model.tools - BuiltInTools.Search
                                        }
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = sessionManager.getOrCreate(conversationId)
        checkFilesDelete(conversation, session.state.value)
        val updated = session.updateFromGeneration(conversation)
        observeScheduledApprovals(updated)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        sessionManager.withSession(conversationId) { session ->
            session.initialize {
                conversationRepo.getConversationById(conversationId)
                    ?: throw IllegalStateException("Conversation not found")
            }
            session.withPersistenceLock {
                session.updateMetadata(update, persist)
            }
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun updateConversationChatModel(conversationId: Uuid, modelId: Uuid) {
        sessionManager.withSession(conversationId) { session ->
            session.withPersistenceLock {
                ensureInitialized(session)
                val settings = settingsStore.settingsFlow.first()
                val selectedModel = settings.findModelById(modelId)
                    ?: throw IllegalStateException("Selected model is no longer available")
                session.updateMetadata(
                    update = { conversation ->
                        requireWritableConversation(conversation)
                        check(conversation.parentConversationId != null) { "Only child chats can change their inherited model" }
                        conversation.copy(
                            modelOverrideId = modelId,
                            config = conversation.config?.copy(
                                chatModelId = modelId,
                                builtInSearch = BuiltInTools.Search in selectedModel.tools,
                            ),
                        )
                    },
                    persist = {
                        conversationRepo.updateConversationModelOverride(conversationId, modelId)
                        conversationRepo.updateConversationConfig(it)
                    },
                )
            }
        }
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        val persisted = conversationRepo.getConversationById(conversationId) ?: return
        requireWritableConversation(persisted)
        unavailableConversations.add(conversationId)
        try {
            stopGeneration(conversationId)
            subagentManager.stopParent(conversationId, forget = true)
            conversationRepo.getSubconversationsOfParentOnce(conversationId).forEach { child ->
                sessionManager.get(child.id)?.let { it.updateConversation(it.state.value.copy(assistantId = assistantId, folderId = null)) }
            }
            updateConversationMetadata(
                conversationId = conversationId,
                // 文件夹属于助手，移动后清除原助手的文件夹归属。
                update = { it.copy(assistantId = assistantId, folderId = null) },
                persist = { conversationRepo.moveConversationTreeToAssistant(conversationId, it.assistantId) },
            )
        } finally {
            unavailableConversations.remove(conversationId)
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) != null) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val session = sessionManager.get(newConversation.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val session = sessionManager.getOrCreate(conversationId)
        session.withPersistenceLock {
            if (conversationId in deletedConversations) return@withPersistenceLock
            val exists = conversationRepo.existsConversationById(conversation.id)
            if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
                return@withPersistenceLock // 新会话且为空时不保存
            }

            // A persisted conversation keeps the assistant/model configuration it started with.
            val preservedConversation = session.preserveChildModelOverride(conversation)
            val settings = loadedSettings()
            val updatedConversation = preservedConversation.bindConfig(settings).fillModelSnapshots(settings)
            updateConversation(conversationId, updatedConversation)

            if (!exists) {
                conversationRepo.insertConversation(updatedConversation)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }

            // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
            // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
            dispatchNextQueuedMessage(conversationId)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        requireWritableConversation(currentConversation)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            val original = node.messages.first { it.id == messageId }
            if (original.isContextCheckpoint) {
                // 摘要原地改写：新建分支会丢掉检查点标记，删除摘要时还会露出旧版本。
                return@map node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == messageId) message.copy(parts = processedParts) else message
                    },
                )
            }

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                    // 编辑的是原消息的内容，来源仍是生成它的模型
                    modelId = original.modelId,
                    modelSnapshot = original.modelSnapshot,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw IllegalStateException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        requireWritableConversation(currentConversation)
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw IllegalStateException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw IllegalArgumentException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        requireWritableConversation(currentConversation)
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw IllegalStateException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid): Unit = withContext(NonCancellable) {
        stoppingConversations.add(conversationId)
        val session = sessionManager.get(conversationId)
        try {
            val jobs = session?.let { synchronized(it) {
                session.messageQueue.pause()
                session.cancelJobs()
            } }.orEmpty()
            subagentManager.stopParent(conversationId)
            subagentManager.stopChild(conversationId)
            jobs.forEach { it.join() }
            if (session != null) finishInterruptedPendingTools(conversationId)
            // User turns in child chats are ordinary session jobs, separate from tool handles.
            val children = conversationRepo.getSubconversationsOfParentOnce(conversationId).map { it.id } +
                sessionManager.snapshot().filter { it.state.value.parentConversationId == conversationId }.map { it.id }
            children.distinct().forEach { childId -> stopGeneration(childId) }
            scheduledTaskRepository.requestDispatch()
        } finally {
            session?.messageQueue?.pause()
            stoppingConversations.remove(conversationId)
        }
    }
}
