package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

enum class SubagentTaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT }

data class SubagentTask(
    val taskId: Uuid,
    val parentConversationId: Uuid,
    val description: String,
    val status: SubagentTaskStatus = SubagentTaskStatus.RUNNING,
    val startedAt: Long,
    val endedAt: Long? = null,
    val expiresAt: Long? = null,
    val async: Boolean,
    val resultJson: String? = null,
    val messages: List<UIMessage> = emptyList(),
)

/** Immutable configuration captured from the actual parent request, including model overrides. */
data class SubagentExecutionConfig(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val parent: Conversation,
    val timeoutMs: Long,
    val maxSteps: Int = 32,
    val maxToolCalls: Int? = null,
    val scheduledExecution: Boolean = false,
)

/** Process-local resumable handles. Persistent child conversations never expire with these handles. */
class SubagentManager(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Handle(
        val config: SubagentExecutionConfig,
        val run: suspend (Uuid, SubagentExecutionConfig, String, Boolean) -> String,
        var job: Deferred<String>? = null,
    )
    private val lock = Any()
    private val permits = Semaphore(4)
    private val handles = mutableMapOf<Uuid, Handle>()
    private val stoppedParents = mutableSetOf<Uuid>()
    private val _tasks = MutableStateFlow<Map<Uuid, SubagentTask>>(emptyMap())
    val tasks = _tasks.asStateFlow()
    private val collectionJob = scope.launch {
        while (isActive) {
            delay(60_000)
            synchronized(lock) { collectExpiredLocked() }
        }
    }

    fun resumeParent(parentId: Uuid) = synchronized(lock) { stoppedParents.remove(parentId) }

    suspend fun spawn(
        config: SubagentExecutionConfig,
        description: String,
        prompt: String,
        async: Boolean,
        run: suspend (Uuid, SubagentExecutionConfig, String, Boolean) -> String,
    ): String {
        currentCoroutineContext().ensureActive()
        val id = Uuid.random()
        val job = synchronized(lock) {
            check(config.parent.id !in stoppedParents) { "Parent conversation has stopped" }
            val handle = Handle(config, run)
            handles[id] = handle
            _tasks.update { it + (id to SubagentTask(id, config.parent.id, description, startedAt = now(), async = async)) }
            launchRun(id, handle, prompt, false)
        }
        job.start()
        if (async) return buildJsonObject {
            put("status", "running")
            put("taskId", id.toString())
            put("description", description)
            put("hint", "Poll with poll_agent(taskId) to fetch progress/result; cancel with cancel_agent(taskId).")
        }.toString()
        return awaitSynchronous(id, job)
    }

    suspend fun followup(parentId: Uuid, id: Uuid, message: String): String {
        currentCoroutineContext().ensureActive()
        val job = synchronized(lock) {
            collectExpiredLocked()
            val task = _tasks.value[id]
            val handle = handles[id]
            if (task == null || handle == null || task.parentConversationId != parentId || parentId in stoppedParents) {
                return errorJson("AGENT_SESSION_NOT_FOUND", "Child session is missing or expired")
            }
            if (handle.job?.isCompleted == false) return errorJson("AGENT_SESSION_BUSY", "Child session is already running")
            _tasks.update { it + (id to task.copy(status = SubagentTaskStatus.RUNNING, startedAt = now(), endedAt = null, expiresAt = null, resultJson = null, async = false)) }
            launchRun(id, handle, message, true)
        }
        job.start()
        return awaitSynchronous(id, job)
    }

    private suspend fun awaitSynchronous(id: Uuid, job: Deferred<String>): String = try {
        job.await()
    } catch (e: CancellationException) {
        // A synchronous child belongs to this tool invocation even though its handle is app-scoped.
        withContext(NonCancellable) { job.cancelAndJoin() }
        if (currentCoroutineContext().isActive) {
            // Cancelling/deleting one child must not cancel the parent awaiting its result.
            _tasks.value[id]?.resultJson ?: errorJson("cancelled", "Child generation cancelled", id)
        } else throw e
    }

    /** Called under lock; LAZY registration closes the cancel-before-start race. */
    private fun launchRun(id: Uuid, handle: Handle, message: String, followup: Boolean): Deferred<String> {
        val job = scope.async(start = CoroutineStart.LAZY) {
            var status = SubagentTaskStatus.COMPLETED
            var result: String? = null
            try {
                result = withTimeout(handle.config.timeoutMs) {
                    permits.withPermit { handle.run(id, handle.config, message, followup) }
                }
                status = statusFromResult(Json.parseToJsonElement(result).jsonObject["status"]?.jsonPrimitive?.content.orEmpty())
            } catch (e: TimeoutCancellationException) {
                status = SubagentTaskStatus.TIMEOUT
            } catch (e: CancellationException) {
                status = SubagentTaskStatus.CANCELLED
            } catch (e: Exception) {
                status = SubagentTaskStatus.FAILED
                result = errorJson("error", e.message ?: "Child generation failed", id)
            } finally {
                synchronized(lock) {
                    val current = _tasks.value[id]
                    if (current != null) {
                        result = result ?: current.resultJson ?: errorJson(status.name.lowercase(), "Child generation ended", id)
                        if (status == SubagentTaskStatus.CANCELLED || status == SubagentTaskStatus.TIMEOUT) {
                            val partial = runCatching { Json.parseToJsonElement(result!!).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                            result = JsonObject(partial + ("status" to JsonPrimitive(status.name.lowercase()))).toString()
                        }
                        _tasks.update { it + (id to current.copy(status = status, resultJson = result, endedAt = now(), expiresAt = now() + SESSION_TTL_MS, messages = emptyList())) }
                    }
                }
            }
            result ?: errorJson("error", "Child session removed", id)
        }
        handle.job = job
        // Lazy coroutines cancelled before entry do not execute finally.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) synchronized(lock) {
                val task = _tasks.value[id]
                if (task?.status == SubagentTaskStatus.RUNNING) {
                    _tasks.update { it + (id to task.copy(status = SubagentTaskStatus.CANCELLED, resultJson = errorJson("cancelled", "Cancelled before execution", id), endedAt = now(), expiresAt = now() + SESSION_TTL_MS, messages = emptyList())) }
                }
            }
        }
        return job
    }

    fun updateMessages(id: Uuid, messages: List<UIMessage>) {
        _tasks.update { map -> map[id]?.let { map + (id to it.copy(messages = messages)) } ?: map }
    }

    fun recordPartial(id: Uuid, result: String) {
        _tasks.update { map -> map[id]?.let { map + (id to it.copy(resultJson = result)) } ?: map }
    }

    fun poll(parentId: Uuid, id: Uuid): String = synchronized(lock) {
        collectExpiredLocked()
        val task = _tasks.value[id]?.takeIf { it.parentConversationId == parentId }
            ?: return errorJson("AGENT_TASK_NOT_FOUND", "Unknown task")
        if (task.status != SubagentTaskStatus.RUNNING) return buildJsonObject {
            put("status", task.status.name.lowercase())
            put("taskId", id.toString())
            put("description", task.description)
            put("durationMs", (task.endedAt ?: now()) - task.startedAt)
            task.resultJson?.let { put("result", it) }
            put("sessionId", id.toString())
        }.toString()
        taskJson(task, progress = true).toString()
    }

    fun list(parentId: Uuid): String = synchronized(lock) {
        collectExpiredLocked()
        val children = _tasks.value.values.filter { it.parentConversationId == parentId }.sortedBy { it.startedAt }
        buildJsonObject {
            put("count", children.size)
            put("agents", buildJsonArray { children.forEach { add(taskJson(it)) } })
        }.toString()
    }

    fun cancel(parentId: Uuid, id: Uuid): String = synchronized(lock) {
        val task = _tasks.value[id]?.takeIf { it.parentConversationId == parentId }
        val job = handles[id]?.job
        if (task == null || task.status != SubagentTaskStatus.RUNNING || job?.isCompleted != false) {
            return errorJson("AGENT_TASK_NOT_FOUND", "Task is not running")
        }
        job.cancel()
        buildJsonObject {
            put("status", "cancelling"); put("taskId", id.toString())
            put("hint", "The child agent will stop at its next cancellation checkpoint; poll for final state.")
        }.toString()
    }

    suspend fun awaitChildren(parentId: Uuid) {
        while (true) {
            val jobs = synchronized(lock) { _tasks.value.values.filter { it.parentConversationId == parentId && it.status == SubagentTaskStatus.RUNNING }.mapNotNull { handles[it.taskId]?.job } }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    suspend fun forgetChild(id: Uuid) {
        val job = synchronized(lock) { handles[id]?.job.also { if (it?.isCompleted == false) it.cancel() } }
        withContext(NonCancellable) { job?.join() }
        synchronized(lock) {
            handles.remove(id)
            _tasks.update { it - id }
        }
    }

    /** The child chat's stop button also cancels a tool run waiting for a global permit. */
    suspend fun stopChild(id: Uuid) {
        val job = synchronized(lock) { handles[id]?.job.also { if (it?.isCompleted == false) it.cancel() } }
        withContext(NonCancellable) { job?.join() }
    }

    suspend fun stopParent(parentId: Uuid, forget: Boolean = false) {
        val jobs = synchronized(lock) {
            stoppedParents.add(parentId)
            _tasks.value.values.filter { it.parentConversationId == parentId }.mapNotNull { handles[it.taskId]?.job }.also { jobs -> jobs.forEach { if (!it.isCompleted) it.cancel() } }
        }
        withContext(NonCancellable) { jobs.joinAll() }
        if (forget) synchronized(lock) {
            val ids = _tasks.value.values.filter { it.parentConversationId == parentId }.map { it.taskId }.toSet()
            ids.forEach(handles::remove)
            _tasks.update { it - ids }
        }
    }

    fun cleanup() = synchronized(lock) {
        collectionJob.cancel()
        stoppedParents.addAll(_tasks.value.values.map { it.parentConversationId })
        handles.values.forEach { it.job?.cancel() }
    }

    private fun collectExpiredLocked() {
        val expired = _tasks.value.values.filter { it.expiresAt?.let { expiry -> expiry <= now() } == true }.map { it.taskId }.toSet()
        expired.forEach(handles::remove)
        _tasks.update { it - expired }
    }

    private fun taskJson(task: SubagentTask, progress: Boolean = false) = buildJsonObject {
        put("taskId", task.taskId.toString())
        put("sessionId", task.taskId.toString())
        put("description", task.description)
        put("status", task.status.name.lowercase())
        put("mode", if (task.async) "async" else "sync")
        put("durationMs", (task.endedAt ?: now()) - task.startedAt)
        task.expiresAt?.let { put("sessionExpiresAt", it) }
        if (progress) {
            put("lastProgressAt", now())
            val tools = task.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            put("toolCalls", tools.size)
            task.messages.lastOrNull()?.parts?.lastOrNull()?.let { part ->
                val preview = when (part) {
                    is UIMessagePart.Text -> part.text.take(120)
                    is UIMessagePart.Reasoning -> part.reasoning.take(120)
                    is UIMessagePart.Tool -> "→ ${part.toolName}"
                    else -> null
                }
                preview?.takeIf(String::isNotBlank)?.let { put("latestAction", it) }
            }
        }
    }

    companion object {
        const val SESSION_TTL_MS = 30L * 60 * 1000
        fun statusFromResult(status: String): SubagentTaskStatus = when (status) {
            "ok", "empty_output" -> SubagentTaskStatus.COMPLETED
            "timeout" -> SubagentTaskStatus.TIMEOUT
            "cancelled" -> SubagentTaskStatus.CANCELLED
            else -> SubagentTaskStatus.FAILED
        }
        fun errorJson(code: String, message: String, id: Uuid? = null): String = buildJsonObject {
            put("status", if (code in setOf("cancelled", "timeout")) code else "error")
            put("error", code)
            put("message", message)
            id?.let { put("sessionId", it.toString()) }
        }.toString()
    }
}
