package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.fork
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.model.withoutUnresumableToolCalls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.db.entity.ScheduledTaskRunStatus
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.applyToolApprovalDecision
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.imageToolChatModel
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findRequestProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.getConversationChatModel
import me.rerere.rikkahub.utils.contextCompactionPolicy
import me.rerere.rikkahub.utils.canCompactLatestCompletedTurn
import me.rerere.rikkahub.utils.estimateTokenCount
import me.rerere.rikkahub.utils.estimateTextTokenCount
import me.rerere.rikkahub.utils.effectiveContextLength
import me.rerere.rikkahub.utils.chunkCompactionTexts
import me.rerere.rikkahub.utils.toCompactionText
import me.rerere.rikkahub.utils.estimateWindowTokens
import me.rerere.rikkahub.utils.selectNodesForAutomaticCompaction

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

private const val AUTO_COMPRESS_TARGET_TOKENS = 2_000
private const val AUTO_COMPRESS_CONCURRENCY = 2

internal fun compactionTextGenerationParams(model: Model, conversationId: Uuid, outputTokens: Int): TextGenerationParams =
    backgroundTextGenerationParams(model.copy(tools = emptySet()), conversationId).copy(maxTokens = outputTokens)

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

class ChatService(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    val subagentManager: SubagentManager,
    private val workspaceRepository: WorkspaceRepository,
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val requestFactory: ChatRequestFactory,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val translationHandler: TranslationHandler,
    private val providerManager: ProviderManager,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
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
        SubagentRunner(context, requestFactory, sessionManager, conversationRepo, workspaceRepository, subagentManager)
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
    suspend fun deleteConversationTree(conversation: Conversation) = deleteConversation(conversation.id)



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
            if (persisted != null) session.initialize(load = { persisted })
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
            if (session.getJob() == null) finishInterruptedPendingTools(session)
        } finally {
            cancellingScheduled.remove(id)
            dispatchNextQueuedMessage(id)
        }
    }

    suspend fun startScheduledConversation(task: ScheduledTaskEntity, conversationId: Uuid): Deferred<String>? {
        val settings = settingsStore.awaitLoaded()
        val assistant = settings.getAssistantById(Uuid.parse(task.assistantId)) ?: error("任务所属助手已删除")
        val mode = me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.valueOf(task.mode)
        val resetHistory = mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.FOLLOW_UP && task.resetContextBeforeRun
        val sourceId = if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.NEW_CHAT) null
            else Uuid.parse(task.targetConversationId ?: error("目标会话未设置"))
        val source = sourceId?.let { id ->
            val session = sessionManager.getOrCreate(id)
            session.initialize(load = { loadConversation(id) ?: error("目标会话已删除") })
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
                me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.FOLLOW_UP ->
                    if (resetHistory) source!!.withoutHistory() else source!!
                me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE -> {
                    val messageId = Uuid.parse(task.targetUserMessageId ?: error("目标用户消息未设置"))
                    require(source!!.currentMessages.any { it.id == messageId && it.role == MessageRole.USER }) { "目标用户消息已删除或分支已改变" }
                    forkConversationAtMessage(source.id, messageId)
                }
            }
            destinationId = conversation.id
            val session = sessionManager.getOrCreate(conversation.id)
            if (sourceId != conversation.id) scheduledReservations.add(conversation.id)
            val contextStartIndex = if (resetHistory) 0 else null
            if (!scheduledTaskRepository.bindConversation(task, conversation.id.toString(), contextStartIndex)) return null
            val completion = CompletableDeferred<String>()
            scheduledRunOwners[conversation.id] = task.activeRunId!!
            scheduledCompletions[conversation.id] = completion
            scheduledWorkerConversations.add(conversation.id)
            session.initialize(load = { loadConversation(conversation.id) }, create = { conversation })
            if (resetHistory) {
                session.update { it.withoutHistory() }
                session.flush()
            } else {
                session.initialize(load = { loadConversation(conversation.id) }, create = { conversation })
                session.flush()
            }
            synchronized(session) {
                if (mode == me.rerere.rikkahub.data.db.entity.ScheduledTaskMode.REGENERATE) {
                    regenerateAtMessage(conversation.id, conversation.currentMessages.last { it.role == MessageRole.USER }.id)
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


    /** 一轮生成的结果 */
    private sealed interface TurnResult {
        data class Finished(val reason: AgentFinishReason) : TurnResult

        /** 没能完成：错误已经报告给用户，消息队列也已暂停 */
        data object Failed : TurnResult
    }

    private val conversationStore = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            // 会话落库即视为开始，此时把助手的配置固定到会话上
            val settings = settingsStore.awaitLoaded()
            return { it.bindConfig(settings).fillModelSnapshots(settings) }
        }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            if (previous == null && !conversationRepo.existsConversationById(current.id)) {
                conversationRepo.insertConversation(current)
            } else {
                conversationRepo.updateConversation(current)
            }
            if (previous != null && previous.messageNodes !== current.messageNodes) {
                withContext(Dispatchers.IO) { deleteUnreferencedFiles(previous, current) }
            }
        }
    }

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        store = conversationStore,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = memoryConsolidationScheduler::onConversationLeft,
    )

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

    fun cleanup() = runCatching { subagentManager.cleanup(); sessionManager.cleanup() }

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

    /** 会话的最新内容：已加载的以内存为准，否则读数据库 */
    suspend fun getConversation(conversationId: Uuid): Conversation? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?: conversationRepo.getConversationById(conversationId)

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
        if (!keepAliveInBackground || conversationId in scheduledWorkerConversations) return appScope.launch(start = CoroutineStart.LAZY) { block() }

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
        withLoadedSession(conversationId) { session ->
            settingsStore.selectAssistant(session.state.value.assistantId)
        }
    }

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize(
            load = { loadConversation(session.id) },
            create = { newConversation(session.id) },
        )
    }

    /** 持有会话引用并确保已加载后执行 [block]；[createIfMissing] 为 false 时数据库里没有就抛 NotFoundException */
    private suspend fun <T> withLoadedSession(
        conversationId: Uuid,
        createIfMissing: Boolean = true,
        block: suspend (ConversationSession) -> T,
    ): T = sessionManager.withSession(conversationId) { session ->
        session.initialize(
            load = { loadConversation(conversationId) },
            create = {
                if (createIfMissing) newConversation(conversationId) else throw NoSuchElementException("Conversation not found")
            },
        )
        block(session)
    }

    // 新建对话, 并添加预设消息
    private suspend fun newConversation(conversationId: Uuid): Conversation {
        val assistant = settingsStore.awaitLoaded().getCurrentAssistant()
        return Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    /**
     * 加载会话后在最新状态上修改并落库。
     * 删除消息或切换分支也可能解除工具审批阻塞，落库后重新检查队列。
     */
    private suspend fun mutate(conversationId: Uuid, transform: (Conversation) -> Conversation): Conversation =
        withLoadedSession(conversationId) { session ->
            session.update(transform)
            session.flush().also { dispatchNextQueuedMessage(conversationId) }
        }

    // 只删上次落库时引用、这次落库不再引用的文件；只在内存里出现过的文件宁可漏删也不误删
    private fun deleteUnreferencedFiles(previous: Conversation, current: Conversation) {
        val session = sessionManager.get(current.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val retainedFiles = (current.files + queuedFiles).toSet()
        val deletedFiles = previous.files.filter { it !in retainedFiles }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "deleteUnreferencedFiles: $deletedFiles")
        }
    }

    // ---- 发送消息 ----

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        sessionManager.getMessageQueueFlow(conversationId)

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
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

    fun moveQueuedMessage(conversationId: Uuid, messageId: Uuid, targetId: Uuid) {
        if (sessionManager.get(conversationId)?.messageQueue?.move(messageId, targetId) == true) {
            dispatchNextQueuedMessage(conversationId)
        }
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
                    finishInterruptedPendingTools(session, skipAskUserQuestion = true)
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
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(!session.state.value.isAwaitingToolApproval) {
                context.getString(R.string.chat_page_voice_tools_before_resume)
            }
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
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = queued.answer,
        ) {
            var inputPersisted = false
            var submittedInputId: Uuid? = null
            try {
                ensureInitialized(session)
                requireWritableConversation(session.state.value)
                finishInterruptedPendingTools(session)

                val assistant = settingsStore.awaitLoaded().getStoredAssistantOf(session.state.value)
                val processedContent = preprocessUserInputParts(queued.parts, assistant)

                val scheduledTask = scheduledTaskRepository.getActiveByConversation(conversationId.toString())
                if (queued.answer && !(scheduledTask?.mode == "FOLLOW_UP" && scheduledTask.resetContextBeforeRun)) {
                    autoCompressIfNeeded(conversationId, session, processedContent, session.processingStatus,
                        modelOverrideId = scheduledTask?.modelOverrideId)
                }

                // 添加消息到列表
                val previousIds = session.state.value.currentMessages.mapTo(HashSet()) { it.id }
                val submitted = UIMessage(role = MessageRole.USER, parts = processedContent)
                submittedInputId = submitted.id
                session.update {
                    it.copy(
                        messageNodes = it.messageNodes + submitted.toMessageNode(),
                    )
                }
                session.flush()
                inputPersisted = true
                session.submittingMessage = null

                // 开始补全
                val result = if (queued.answer) runTurn(session) else null

                queued.reply?.completeWith(runCatching {
                    check(result != TurnResult.Failed) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check((result as? TurnResult.Finished)?.reason !is AgentFinishReason.AwaitingApproval) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    session.state.value.currentMessages
                        .filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                val inputWasAdded = submittedInputId?.let { id ->
                    session.state.value.messageNodes.any { node -> node.messages.any { it.id == id } }
                } == true
                if (!inputPersisted && !inputWasAdded &&
                    (e is ContextCompactionException || e is CancellationException) &&
                    conversationId !in scheduledWorkerConversations && conversationId !in unavailableConversations
                ) {
                    session.messageQueue.requeueFront(queued)
                    session.messageQueue.pause()
                }
                reportFailure(session, e, if (e is ContextCompactionException) R.string.error_title_compress_context else R.string.error_title_send_message)
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

    // 发送、重新生成和审批失败时报告错误并暂停队列，排队的消息等用户处理完再继续
    private suspend fun reportFailure(session: ConversationSession, error: Exception, @StringRes title: Int) {
        if (error is CancellationException) throw error
        session.messageQueue.pause()
        if (scheduledTaskRepository.getActiveByConversation(session.id.toString()) != null) scheduledFailures[session.id] = error
        addError(error, session.id, title = context.getString(title))
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

    /** 重新生成用户消息时去掉它之后的节点再回复；重新生成助手消息时在它的节点上新开一个分支 */
    fun regenerateAtMessage(conversationId: Uuid, messageId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()
            val job = launchGenerationJob(conversationId = conversationId) {
                try {
                    previousJob?.join()
                    ensureInitialized(session)
                    val node = session.state.value.getMessageNodeByMessageId(messageId)
                        ?: return@launchGenerationJob

                    if (node.role == MessageRole.USER) {
                        session.tryUpdate { conversation ->
                            val index = conversation.messageNodes.indexOfFirst { it.id == node.id }
                            // 查找之后这条消息被删掉了
                            if (index == -1) return@tryUpdate null
                            conversation.copy(messageNodes = conversation.messageNodes.take(index + 1))
                        } ?: return@launchGenerationJob
                        session.flush()
                        runTurn(session)
                    } else {
                        runTurn(session, regenerateNodeId = node.id)
                    }

                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_regenerate_message)
                }
            }

            session.setJob(job)
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        editedPrompt: String? = null,
    ) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            val previousJob = session.getJob()

            val hasOtherPendingTools = session.state.value.currentMessages.any { message ->
                message.getTools().any { it.isPending && it.toolCallId != toolCallId }
            }

            val job = launchGenerationJob(
                conversationId = conversationId,
                keepAliveInBackground = !hasOtherPendingTools,
            ) {
                try {
                    afterPreviousGeneration(previousJob) {
                        ensureInitialized(session)
                        val updated = session.tryUpdate { conversation ->
                            // Ignore double taps and stale approvals for completed or inactive tools.
                            if (conversation.currentMessages.none { message ->
                                    message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                                }) return@tryUpdate null
                            conversation.copy(
                                messageNodes = conversation.messageNodes.map { node ->
                                    node.copy(messages = node.messages.map { message ->
                                        message.copy(parts = message.parts.map { part ->
                                            if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                                applyToolApprovalDecision(part, approved, reason, answer, editedPrompt)
                                            } else {
                                                part
                                            }
                                        })
                                    })
                                }
                            )
                        } ?: return@afterPreviousGeneration
                        session.flush()

                        // Only continue generation when all pending tools are handled
                        if (!updated.isAwaitingToolApproval) {
                            scheduledTaskRepository.resumeConversation(conversationId.toString())
                            runTurn(session)
                        }

                        _generationDoneFlow.emit(conversationId)
                    }
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_tool_approval)
                }
            }

            session.setJob(job, cancelPrevious = false)
        }
    }

    // ---- 生成回复 ----

    /**
     * 生成一轮回复：请求模型、执行工具，直到模型不再调用工具、有工具需要审批或达到步数上限。
     *
     * 默认接着当前分支的末尾回复；[regenerateNodeId] 不为空时只发送这个节点之前的消息，回复作为它的新分支。
     * 发送的最后一条是助手消息时从它继续（比如审批之后），回复写回那条消息。
     * 失败时报告错误、暂停消息队列并返回 [TurnResult.Failed]；取消时直接抛出。
     */
    private suspend fun runTurn(session: ConversationSession, regenerateNodeId: Uuid? = null): TurnResult {
        val task = scheduledTaskRepository.getActiveByConversation(session.id.toString())
        val result = if (task != null) {
            scheduledRunOwners[session.id] = task.activeRunId!!
            kotlinx.coroutines.withTimeout(scheduledTaskRepository.remainingGenerationMs(task)) {
                runTurnUnbounded(session, regenerateNodeId)
            }
        } else runTurnUnbounded(session, regenerateNodeId)
        if (result is TurnResult.Finished && result.reason == AgentFinishReason.Completed) autoCompressAfterTurnIfNeeded(session.id, task)
        return result
    }

    private suspend fun runTurnUnbounded(session: ConversationSession, regenerateNodeId: Uuid? = null): TurnResult {
        val conversationId = session.id
        // 生成开始前失败时通知只用来取消 Live Update，用不到发送者名称
        var senderName = ""
        try {
            requireWritableConversation(session.state.value)
            subagentManager.resumeParent(conversationId)
            val settings = settingsStore.awaitLoaded()
            // 清掉上一轮的建议；没法继续执行的工具调用发给模型会被拒绝，先去掉。
            // 重新生成时只清理要发给模型的节点，被重新生成的节点保持原样，旧回复留作分支
            val conversation = session.update { latest ->
                val untilNode = regenerateNodeId
                    ?.let { nodeId -> latest.messageNodes.indexOfFirst { it.id == nodeId } }
                    ?.takeIf { it != -1 }
                    ?: latest.messageNodes.size
                latest.copy(chatSuggestions = emptyList()).withoutUnresumableToolCalls(untilNode)
            }
            val scheduledTask = scheduledTaskRepository.getActiveByConversation(conversationId.toString())
            val model = scheduledTask?.modelOverrideId?.let { settings.findModelById(Uuid.parse(it)) }
                ?: settings.getConversationChatModel(conversation) ?: error("No chat model selected")
            val requestModel = imageToolChatModel(model, LocalToolOption.ImageGeneration in settings.getAssistantOf(conversation).localTools)
            val turn = requestFactory.createContext(settings, conversation, modelOverride = requestModel,
                getMessages = { session.state.value.currentMessages }, scheduledTask = scheduledTask,
                subagentTools = childAgentTools(settings, settings.getAssistantOf(conversation), model, conversation, scheduledTask != null))
            senderName = if (turn.assistant.useAssistantAvatar) {
                turn.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
            } else {
                turn.model.displayName
            }
            warnIfToolsUnavailable(turn, conversationId)

            val nodes = conversation.messageNodes
            val fullHistory = if (regenerateNodeId != null) {
                val index = nodes.indexOfFirst { it.id == regenerateNodeId }
                check(index != -1) { "Message node $regenerateNodeId not found" }
                conversation.currentMessages.take(index)
            } else {
                conversation.currentMessages
            }
            val requestSource = conversation.copy(messageNodes = nodes.take(fullHistory.size))
            val history = if (scheduledTask?.mode == "FOLLOW_UP" && scheduledTask.resetContextBeforeRun) {
                requestSource.requestContextFromNode(requireNotNull(scheduledTask.activeContextStartIndex)).messages
            } else requestSource.requestWindowMessages()
            val replyNodeId = when {
                history.lastOrNull()?.role == MessageRole.ASSISTANT -> nodes[fullHistory.lastIndex].id
                regenerateNodeId != null -> regenerateNodeId
                else -> Uuid.random()
            }

            val (request, hooks) = requestFactory.createAgent(turn, history, session.processingStatus,
                shouldYieldAfterToolResults = { session.messageQueue.state.value.priorityMessageId != null })
            var finishReason: AgentFinishReason? = null
            try {
                runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            // 生成过程中只有网络重试的提示会留着，收到新内容说明重试已经成功
                            session.processingStatus.value = null
                            session.update { it.upsertMessage(replyNodeId, event.message) }
                        }

                        is AgentEvent.Retrying -> {
                            logRetry(event.attempt)
                            session.processingStatus.value = retryStatus(event.attempt)
                        }

                        is AgentEvent.Finished -> {
                            Log.i(TAG, "runTurn: finished (${event.reason})")
                            finishReason = event.reason
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    session.processingStatus.value = null
                    // 可能被取消了，或者意外结束，兜底保存
                    val finished = session.finishGeneration()
                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            scheduledTask = scheduledTaskRepository.getActiveByConversation(conversationId.toString()) != null,
                            contentPreview = finished.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                        )
                    )
                }
            }

            val reason = checkNotNull(finishReason)
            // 等待审批时这一轮还没结束，标题、建议和记忆整理等审批之后再做
            if (reason !is AgentFinishReason.AwaitingApproval && reason !is AgentFinishReason.Yielded) {
                sessionManager.launchWithSession(conversationId) { generateTitle(conversationId) }
                sessionManager.launchWithSession(conversationId) { generateSuggestion(conversationId) }
                if (turn.assistant.enableMemory) memoryConsolidationScheduler.onTurnFinished(session.state.value)
            }
            return TurnResult.Finished(reason)
        } catch (e: CancellationException) {
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            throw e
        } catch (e: InvalidMcpServerNamesException) {
            session.messageQueue.pause()
            addError(
                error = IllegalStateException(
                    context.getString(R.string.error_mcp_invalid_server_name, e.names.joinToString(", "))
                ),
                conversationId = conversationId,
            )
            if (scheduledTaskRepository.getActiveByConversation(conversationId.toString()) != null) scheduledFailures[conversationId] = e
            return TurnResult.Failed
        } catch (e: Exception) {
            // 兜底取消 Live Update 通知（生成开始前失败时上面的 finally 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            session.messageQueue.pause()
            e.printStackTrace()
            addError(e, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "runTurn: $e")
            Logging.log(TAG, e.stackTraceToString())
            if (scheduledTaskRepository.getActiveByConversation(conversationId.toString()) != null) scheduledFailures[conversationId] = e
            return TurnResult.Failed
        }
    }

    // 模型不支持工具调用时，搜索和 MCP 工具都不会生效，提醒一下
    private fun warnIfToolsUnavailable(turn: ChatTurnContext, conversationId: Uuid) {
        if (ModelAbility.TOOL in turn.model.abilities) return
        if (shouldUseExternalWebSearch(turn.assistant, turn.model) ||
            mcpManager.getAllAvailableTools(turn.assistant).isNotEmpty()
        ) {
            addError(
                IllegalStateException(context.getString(R.string.tools_warning)),
                conversationId,
                title = context.getString(R.string.error_title_tool_unavailable)
            )
        }
    }

    private fun logRetry(attempt: RetryAttempt) {
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${attempt.delay} (${attempt.retry}/${attempt.maxRetries})",
            attempt.cause,
        )
    }

    private fun retryStatus(attempt: RetryAttempt): String {
        val reason = when (attempt.cause) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(
            R.string.chat_generation_network_retrying,
            context.getString(reason),
            attempt.retry,
            attempt.maxRetries,
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


    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            )
        )
    }

    // 生成被停止时还没执行的工具补上取消的结果，否则这条消息之后没法继续发给模型
    private suspend fun finishInterruptedPendingTools(session: ConversationSession, skipAskUserQuestion: Boolean = false) {
        session.tryUpdate { conversation ->
            val lastNode = conversation.messageNodes.lastOrNull() ?: return@tryUpdate null
            val updatedMessage = lastNode.currentMessage.finishPendingTools(if (skipAskUserQuestion) ::skipAskUserTool else ::cancelToolByUser)
            if (updatedMessage === lastNode.currentMessage) return@tryUpdate null
            conversation.upsertMessage(lastNode.id, updatedMessage)
        }
        session.flush()
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        force: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        withLoadedSession<Unit>(conversationId) { session ->
            val incarnation = session.incarnation
            val conversation = session.state.value
            if (!force && conversation.title.isNotBlank()) return@withLoadedSession

            val settings = settingsStore.awaitLoaded()
            // 关掉自动生成后只跳过对话结束时的自动调用，手动重新生成照常执行
            if (!force && !settings.enableAutoTitle) return@withLoadedSession

            runCatching {
                val model = settings.findModelById(settings.fastModelId)
                    ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
                val provider = model.findProvider(settings.providers)
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

                // 只写标题这一列：这时可能已经在生成下一轮，不能把还没生成完的内容一起落库。
                // 生成期间会话被删除的话不写入
                val title = result.message.toText().trim()
                session.updateMetadata(
                    transform = { it.copy(title = title) },
                    persist = { conversationRepo.updateTitle(conversationId, title) },
                    expectedIncarnation = incarnation,
                )
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
    }

    // ---- 生成建议 ----

    private suspend fun generateSuggestion(conversationId: Uuid): Unit = withContext(Dispatchers.IO) {
        sessionManager.withSession<Unit>(conversationId) { session ->
            runCatching {
                val settings = settingsStore.awaitLoaded()
                if (!settings.enableSuggestion) return@runCatching
                val model = settings.findModelById(settings.fastModelId)
                    ?: return@runCatching
                val provider = model.findProvider(settings.providers) ?: return@runCatching

                // 上一轮的建议在这一轮开始时已经清掉了
                ensureInitialized(session)
                val incarnation = session.incarnation
                val conversation = session.state.value

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
                val suggestions = result.message.toText().split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(10)

                // 只写建议这一列，理由同标题
                session.updateMetadata(
                    transform = { it.copy(chatSuggestions = suggestions) },
                    persist = { conversationRepo.updateChatSuggestions(conversationId, suggestions) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
    ): Result<Unit> = runCatching {
        withLoadedSession<Unit>(conversationId) { session ->
            // 生成中的回复还没写完，压缩不到它，检查点也不能插到它前面
            check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

            val conversation = session.state.value
            val nodes = conversation.messageNodes
            // 上一个检查点之后没有新消息，就没什么可压缩的
            val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
            if (checkpointIndex == nodes.lastIndex) {
                throw IllegalStateException(context.getString(R.string.chat_page_compress_nothing_to_compress))
            }
            // 未完成的工具调用没有结果，请求会被服务端拒绝；压到检查点之前也再不会执行。
            check(nodes.subList(checkpointIndex + 1, nodes.size).none { node ->
                node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
            }) { context.getString(R.string.chat_page_compress_handle_pending_tools) }

            // 必须和对话用同一个模型、同一套请求组装，才能命中提示词缓存
            val turn = requestFactory.createContext(settingsStore.awaitLoaded(), conversation)
            val sourceFingerprint = conversation.compressionSourceFingerprint(nodes.last().id)
            val summary = requestFactory.generateCompressionSummary(
                turn = turn,
                messages = conversation.currentMessages,
                compressPrompt = buildCompressPrompt(
                    additionalPrompt = additionalPrompt,
                    locale = Locale.getDefault().displayName,
                ),
            )

            // 原消息原样保留，摘要插在压缩时的最后一条消息之后。摘要生成期间对话可能已变化，
            // 因此按节点定位插入到最新状态。
            session.update { latest ->
                check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }
                check(latest.compressionSourceFingerprint(nodes.last().id) == sourceFingerprint) {
                    context.getString(R.string.chat_page_compress_conversation_changed)
                }
                val updated = latest.withContextCheckpoint(
                    afterNodeId = nodes.last().id,
                    summary = summary,
                ) ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                updated.copy(chatSuggestions = emptyList())
            }
            session.flush()
        }
    }

    private suspend fun autoCompressAfterTurnIfNeeded(
        conversationId: Uuid,
        scheduledTask: ScheduledTaskEntity?,
    ) {
        if (scheduledTask?.mode == "FOLLOW_UP" && scheduledTask.resetContextBeforeRun) return
        val session = sessionManager.get(conversationId) ?: return
        if (session.messageQueue.state.value.priorityMessageId != null) return
        val messages = session.state.value.currentMessages
        if (messages.lastOrNull()?.role != MessageRole.ASSISTANT) return
        if (messages.any { message ->
                message.getTools().any { it.isPending || it.canResumeExecution }
            }) return

        try {
            autoCompressIfNeeded(
                conversationId = conversationId,
                session = session,
                pendingParts = emptyList(),
                processingStatus = session.processingStatus,
                includeLatestCompletedTurn = true,
                modelOverrideId = scheduledTask?.modelOverrideId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            addError(error, conversationId, title = context.getString(R.string.error_title_compress_context))
        }
    }

    private suspend fun autoCompressIfNeeded(
        conversationId: Uuid,
        session: ConversationSession,
        pendingParts: List<UIMessagePart>,
        processingStatus: MutableStateFlow<String?>,
        includeLatestCompletedTurn: Boolean = false,
        modelOverrideId: String? = null,
    ) {
        val settings = settingsStore.awaitLoaded()
        val conversation = session.state.value
        val model = modelOverrideId?.let { settings.findModelById(Uuid.parse(it)) }
            ?: settings.getConversationChatModel(conversation) ?: return
        val policy = settings.contextCompactionPolicy(model)
        val pendingTokens = if (pendingParts.isEmpty()) 0 else estimateTokenCount(
            listOf(UIMessage(role = MessageRole.USER, parts = pendingParts)),
        )
        val usedTokens = conversation.estimateWindowTokens(model) + pendingTokens
        if (!policy.shouldCompact(usedTokens)) return
        val targetTokens = policy.targetTokens
        val summaryTargetTokens = minOf(AUTO_COMPRESS_TARGET_TOKENS, targetTokens / 4).coerceAtLeast(128)
        val nodesToCompress = autoCompressNodes(
            conversation, policy.capacityTokens, pendingTokens, targetTokens, summaryTargetTokens,
            includeLatestCompletedTurn || canCompactLatestCompletedTurn(conversation.windowNodes()),
        )
        if (nodesToCompress.isEmpty()) {
            throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))
        }
        val boundaryNodeId = nodesToCompress.last().id
        val sourceTokens = nodesToCompress.sumOf { estimateTokenCount(listOf(it.currentMessage)) }
        val emptyCheckpoint = insertContextCheckpoint(conversation, boundaryNodeId, "")
            ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
        val summaryBudget = compactionSummaryBudget(
            preferredTokens = summaryTargetTokens,
            targetTokens = targetTokens,
            pendingTokens = pendingTokens,
            retainedTokens = emptyCheckpoint.estimateWindowTokens(model),
            sourceTokens = sourceTokens,
        ) ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))

        val compactingStatus = context.getString(R.string.chat_page_compacting_context)
        processingStatus.value = compactingStatus
        try {
            val summary = compressNodesToSummary(
                conversationId = conversationId,
                nodesToCompress = nodesToCompress,
                settings = settings,
                model = model,
                processingStatus = processingStatus,
                summaryBudget = summaryBudget,
            )
            if (estimateTextTokenCount(summary) >= sourceTokens) {
                throw ContextCompactionException(context.getString(R.string.error_compress_context_no_reduction))
            }
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
                targetTokens = targetTokens,
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
        targetTokens: Int,
        summaryTargetTokens: Int,
        includeLatestCompletedTurn: Boolean,
    ): List<MessageNode> {
        val nodes = conversation.windowNodes()
        val summaryReserve = summaryTargetTokens
        val systemReserve = minOf(maxOf(512, (windowTokens * 0.08f).toInt()), targetTokens / 4)
        val keepBudget = (
            targetTokens - pendingTokens - summaryReserve - systemReserve
        ).coerceAtLeast(0)
        return selectNodesForAutomaticCompaction(
            nodes = nodes,
            keepBudgetTokens = keepBudget,
            includeLatestCompletedTurn = includeLatestCompletedTurn,
        )
    }

    private suspend fun persistContextCheckpoint(
        session: ConversationSession,
        boundaryNodeId: Uuid,
        summary: String,
        expectedFingerprint: String,
        model: Model,
        pendingTokens: Int,
        targetTokens: Int,
    ) {
        session.update { latest ->
            if (latest.compressionSourceFingerprint(boundaryNodeId) != expectedFingerprint) throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            val candidate = insertContextCheckpoint(latest, boundaryNodeId, summary)
                ?: throw ContextCompactionException(context.getString(R.string.error_compress_context_changed))
            if (candidate.estimateWindowTokens(model) + pendingTokens > targetTokens) throw ContextCompactionException(context.getString(R.string.error_compress_context_still_too_large))
            candidate
        }
        session.flush()
    }

    private suspend fun compressNodesToSummary(
        conversationId: Uuid,
        nodesToCompress: List<MessageNode>,
        settings: Settings,
        model: Model,
        processingStatus: MutableStateFlow<String?>,
        summaryBudget: CompactionSummaryBudget,
    ): String {
        if (nodesToCompress.isEmpty()) throw ContextCompactionException(
            context.getString(R.string.error_compress_context_failed),
        )
        return try {
            val provider = model.findRequestProvider(settings.providers)
                ?: throw IllegalStateException("Provider not found")
            val providerHandler = providerManager.getProviderByType(provider)
            val locale = Locale.getDefault().displayName
            // Reuse the normal request prefix when it fits. Small windows still use the
            // bounded chunk/reduce path, including the existing summary-budget checks.
            val source = sessionManager.get(conversationId)?.state?.value
            val boundaryIndex = source?.messageNodes?.indexOfFirst { it.id == nodesToCompress.last().id } ?: -1
            if (source != null && boundaryIndex >= 0 && settings.compressPrompt == DEFAULT_COMPRESS_PROMPT) {
                val prefix = source.copy(messageNodes = source.messageNodes.take(boundaryIndex + 1))
                val instruction = buildCompressPrompt(
                    "Keep the summary within ${summaryBudget.preferredTokens} tokens.", locale,
                )
                val reserve = estimateTextTokenCount(instruction).toLong() + summaryBudget.maxTokens + 1_024
                if (prefix.estimateWindowTokens(model).toLong() + reserve < model.effectiveContextLength()) {
                    try {
                        val task = scheduledTaskRepository.getActiveByConversation(conversationId.toString())
                        val turn = requestFactory.createContext(settings, prefix, modelOverride = model,
                            getMessages = { source.currentMessages }, scheduledTask = task,
                            subagentTools = childAgentTools(settings, settings.getAssistantOf(source), model, source, task != null))
                        val cachedSummary = requestFactory.generateCompressionSummary(
                            turn, prefix.requestWindowMessages(), instruction, summaryBudget.maxTokens,
                        )
                        if (estimateTextTokenCount(cachedSummary) <= summaryBudget.maxTokens) return cachedSummary
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Prefix-preserving compression failed; using bounded chunks", error)
                    }
                }
            }
            val useCompactTemplate = settings.compressPrompt == DEFAULT_COMPRESS_PROMPT &&
                (summaryBudget.preferredTokens < 256 || model.effectiveContextLength() < 4_096)
            fun renderPrompt(content: String, targetTokens: Int): String {
                if (useCompactTemplate) return compactSummaryPrompt(content, targetTokens, locale)
                val template = settings.compressPrompt
                val rendered = template.applyPlaceholders(
                    "content" to content,
                    "target_tokens" to targetTokens.toString(),
                    "additional_context" to "",
                    "locale" to locale,
                )
                return if (template.contains("{content}")) rendered else
                    "$rendered\n\nConversation content to summarize:\n$content"
            }

            val budget = compactionRequestBudget(
                windowTokens = model.effectiveContextLength(),
                promptTokens = maxOf(
                    estimateTextTokenCount(renderPrompt("", summaryBudget.preferredTokens)),
                    estimateTextTokenCount(compactSummaryPrompt("", summaryBudget.preferredTokens, locale)),
                ),
            )
            val reduceTarget = minOf(budget.contentTokens, summaryBudget.preferredTokens)
            val mapTarget = minOf(budget.contentTokens / 6, 1_000, reduceTarget).coerceAtLeast(1)
            val sourceChunks = chunkCompactionTexts(
                nodesToCompress.map { it.currentMessage.toCompactionText() },
                budget.contentTokens,
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

            suspend fun generateSummary(prompt: String): String =
                semaphore.withPermit {
                    providerHandler.generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.user(prompt)),
                        params = compactionTextGenerationParams(model, conversationId, budget.outputTokens),
                    ).message.toText().trim().takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Empty compression result")
                }

            val summaries = coroutineScope {
                sourceChunks.map { chunk ->
                    async {
                        generateSummary(renderPrompt(chunk, if (sourceChunks.size == 1) reduceTarget else mapTarget))
                            .also { reportMapChunkComplete() }
                    }
                }.awaitAll()
            }
            reduceCompactionSummaries(
                summaries = summaries,
                contentBudget = budget.contentTokens,
                targetTokens = reduceTarget,
                maxTokens = summaryBudget.maxTokens,
                onProgress = { pass, completed, total ->
                    processingStatus.value = if (total == 1) {
                        context.getString(R.string.chat_page_compacting_context_finalize)
                    } else context.getString(
                        R.string.chat_page_compacting_context_reduce_progress, pass, completed, total,
                    )
                },
                summarize = { content, target -> generateSummary(compactSummaryPrompt(content, target, locale)) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ContextCompactionException) {
            throw error
        } catch (error: CompactionSummaryTooLargeException) {
            Log.w(TAG, "Summary exceeds available context space", error)
            throw ContextCompactionException(context.getString(
                R.string.error_compress_context_summary_too_large, error.actualTokens, error.maxTokens,
            ))
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
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                transform = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
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
        withLoadedSession(conversationId) { session ->
            val conversation = session.state.value
            if (conversation.config != null) {
                session.updateMetadata(
                    transform = {
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
                return@withLoadedSession
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
                    },
                )
            }
        }
    }

    // 工作目录和配置一起按列落库；还没落过库的新会话先留在内存里，发出第一条消息时一起写入
    suspend fun updateWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(
                transform = { it.copy(workspaceCwd = workspaceCwd) },
                persist = conversationRepo::updateConversationConfig,
            )
        }
    }

    suspend fun updateConversationSystemPrompt(conversationId: Uuid, systemPrompt: String?) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(customSystemPrompt = systemPrompt) },
            persist = { conversationRepo.updateCustomSystemPrompt(conversationId, systemPrompt) },
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(title = title) },
            persist = { conversationRepo.updateTitle(conversationId, title) },
        )
    }

    /**
     * 用户在聊天页改的单列字段：已落库的会话只写这一列，生成中也不会顺带写入还没生成完的回复；
     * 还没落过库的新会话整份落库，留下记录（没有内容的新会话仍然不写）。
     */
    private suspend fun updateColumn(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend () -> Unit,
    ) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(transform, persist = { persist() })
            if (!session.existsInDatabase) session.flush()
        }
    }

    // ---- 会话元数据 ----

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            session.updateMetadata(transform, persist)
        }
    }

    suspend fun updateConversationChatModel(conversationId: Uuid, modelId: Uuid) {
        withLoadedSession(conversationId) { session ->
            val selectedModel = settingsStore.awaitLoaded().findModelById(modelId)
                ?: error("Selected model is no longer available")
            session.updateMetadata(
                transform = { conversation ->
                    requireWritableConversation(conversation)
                    check(conversation.parentConversationId != null) { "Only child chats can change their inherited model" }
                    conversation.copy(
                        modelOverrideId = modelId,
                        config = conversation.config?.copy(chatModelId = modelId, builtInSearch = BuiltInTools.Search in selectedModel.tools),
                    )
                },
                persist = {
                    conversationRepo.updateConversationModelOverride(conversationId, modelId)
                    conversationRepo.updateConversationConfig(it)
                },
            )
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        val conversation = getConversation(conversationId) ?: return
        requireWritableConversation(conversation)
        unavailableConversations.add(conversationId)
        try {
            stopGeneration(conversationId)
            subagentManager.stopParent(conversationId, forget = true)
            conversationRepo.getSubconversationsOfParentOnce(conversationId).forEach { child ->
                sessionManager.get(child.id)?.takeIf { it.isInitialized }?.updateMetadata(
                    transform = { it.copy(assistantId = assistantId, folderId = null) }, persist = {},
                )
            }
            updateConversationMetadata(
                conversationId = conversationId,
                transform = { it.copy(assistantId = assistantId, folderId = null) },
                persist = { conversationRepo.moveConversationTreeToAssistant(conversationId, it.assistantId) },
            )
        } finally {
            unavailableConversations.remove(conversationId)
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     * 有 session 的会话先改内存，免得之后整份落库时写回旧的文件夹；没有的只改这一列，不必加载整个会话。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            return
        }
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(folderId = folderId) },
            persist = { conversationRepo.updateConversationFolderId(conversationId, folderId) },
        )
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
     * 已加载的会话先改内存再删库，之后落库不会写回已删除的文件夹。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.folderId == folderId }
            .forEach { session ->
                // 这一列由下面删文件夹时统一清空，这里只改内存，不让会话因此变成有未落库的修改
                session.updateMetadata(
                    transform = { if (it.folderId == folderId) it.copy(folderId = null) else it },
                    persist = {},
                )
            }
        folderRepository.deleteFolder(folderId)
    }

    // ---- 删除会话 ----

    /**
     * 删除会话：停掉生成后在会话的写锁内删库，内存换成一个还没落过库的新会话。
     * 还停在这个会话上的页面看到的是空会话，之后再发消息会新建记录，不会写回已删除的那条。
     */
    suspend fun deleteConversation(conversationId: Uuid) {
        unavailableConversations.add(conversationId)
        try {
        subagentManager.stopParent(conversationId, forget = true)
        subagentManager.forgetChild(conversationId)
        conversationRepo.getSubconversationsOfParentOnce(conversationId).forEach { deleteConversation(it.id) }
        val session = sessionManager.getOrCreate(conversationId)
        discardQueuedMessages(session)
        session.replace(newConversation(conversationId), existsInDatabase = false) { previous ->
            conversationRepo.getConversationById(conversationId)?.let { conversationRepo.deleteConversation(it) }
            // 数据库那份不知道只在内存里出现过的附件
            previous?.let { filesManager.deleteChatFiles(it.files) }
        }
        sessionManager.removeIfIdle(session)
        } finally { unavailableConversations.remove(conversationId) }
    }

    /** 撤销删除：写回删除前的会话，还停在这个会话上的页面也一并换回 */
    suspend fun restoreConversation(conversation: Conversation) {
        val session = sessionManager.getOrCreate(conversation.id)
        session.replace(conversation, existsInDatabase = true) {
            conversationStore.write(previous = null, current = conversation)
        }
        sessionManager.removeIfIdle(session)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        val persisted = conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id }
        val loaded = sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.assistantId == assistantId }
            .map { it.id }
        (persisted + loaded).distinct().forEach { deleteConversation(it) }
    }

    private fun discardQueuedMessages(session: ConversationSession) {
        session.messageQueue.pause()
        session.messageQueue.state.value.messages.forEach { queued ->
            session.messageQueue.remove(queued.id)?.let(::cleanupQueuedAttachments)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            sessionManager.withSession<Unit>(conversationId) { session ->
                try {
                    ensureInitialized(session)
                    val settings = settingsStore.awaitLoaded()

                    val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                    if (messageText.isBlank()) return@withSession

                    // Set loading state for translation
                    val loadingText = context.getString(R.string.translating)
                    session.update { it.withTranslation(message.id, loadingText) }

                    translationHandler.translateText(
                        settings = settings,
                        sourceText = messageText,
                        targetLanguage = targetLanguage
                    ) { translatedText ->
                        // Update translation field in real-time
                        session.update { it.withTranslation(message.id, translatedText) }
                    }.collect { /* Final translation already handled in onStreamUpdate */ }

                    session.flush()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Clear translation field on error
                    if (session.isInitialized) session.update { it.withTranslation(message.id, null) }
                    addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
                }
            }
        }
    }

    suspend fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutate(conversationId) { it.withTranslation(messageId, null) }
    }

    private fun Conversation.withTranslation(messageId: Uuid, translation: String?): Conversation {
        val node = getMessageNodeByMessageId(messageId) ?: return this
        return upsertMessage(node.id, node.messages.first { it.id == messageId }.copy(translation = translation))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val settings = settingsStore.awaitLoaded()

        mutate(conversationId) { conversation ->
            val node = conversation.getMessageNodeByMessageId(messageId) ?: return@mutate conversation
            val original = node.messages.first { it.id == messageId }
            val processedParts = preprocessUserInputParts(parts, settings.getStoredAssistantOf(conversation))
            val edited = if (original.isContextCheckpoint) {
                // 摘要原地改写：新建分支会丢掉检查点标记，删除摘要时还会露出旧版本。
                original.copy(parts = processedParts)
            } else {
                UIMessage(
                    role = node.role,
                    parts = processedParts,
                    // 编辑的是原消息的内容，来源仍是生成它的模型
                    modelId = original.modelId,
                    modelSnapshot = original.modelSnapshot,
                )
            }
            conversation.upsertMessage(node.id, edited)
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val source = withLoadedSession(conversationId) { it.state.value }
        val existingTitles = conversationRepo
            .getConversationsOfAssistant(source.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val fork = source.fork(messageId, existingTitles, copyFile = ::copyChatFile)
            ?: throw NoSuchElementException("Message not found")
        // 新会话直接落库，打开时再从数据库加载
        val forkConversation = conversationStore.prepareWrite()(fork)
        conversationRepo.insertConversation(forkConversation)
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    private fun copyChatFile(url: String): String =
        filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        mutate(conversationId) { conversation ->
            val targetNode = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NoSuchElementException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw IllegalArgumentException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@mutate conversation

            conversation.copy(
                messageNodes = conversation.messageNodes.map { node ->
                    if (node.id == nodeId) node.copy(selectIndex = selectIndex) else node
                }
            )
        }
    }

    // 收藏记录在单独的表里，节点上的标记只用于显示，不需要落库
    suspend fun setNodeFavorite(conversationId: Uuid, nodeId: Uuid, favorite: Boolean) {
        val session = sessionManager.get(conversationId)?.takeIf { it.isInitialized } ?: return
        session.updateMetadata(
            transform = { conversation ->
                conversation.copy(
                    messageNodes = conversation.messageNodes.map { node ->
                        if (node.id == nodeId) node.copy(isFavorite = favorite) else node
                    }
                )
            },
            persist = {},
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        mutate(conversationId) { conversation ->
            buildConversationAfterMessageDelete(conversation, messageId)
                ?: if (failIfMissing) throw NoSuchElementException("Message not found") else conversation
        }
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
            if (session != null) finishInterruptedPendingTools(session)
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
