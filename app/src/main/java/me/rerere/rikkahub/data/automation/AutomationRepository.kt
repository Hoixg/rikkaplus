package me.rerere.rikkahub.data.automation

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.AutomationService
import kotlin.uuid.Uuid

class AutomationRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val conversations: ConversationRepository,
) {
    private val dao get() = database.automationDao()
    private val lock = Mutex()
    private val startup = Mutex()
    private var recovered = false
    val tasks = dao.tasksFlow()
    val activeRuns = dao.activeFlow()
    fun historyFlow(id: String) = dao.historyFlow(id)
    suspend fun task(id: String) = dao.task(id)
    suspend fun tasks(assistantId: String) = dao.tasks().filter { it.assistantId == assistantId }
    suspend fun active() = dao.active()
    suspend fun run(id: String) = dao.run(id)
    suspend fun history(id: String) = dao.history(id)
    suspend fun forConversation(id: String) = dao.forConversation(id)

    /** Only this branch's own schema is read. A killed generation is never sent again automatically. */
    suspend fun recover() = startup.withLock {
        if (recovered) return@withLock
        lock.withLock {
            database.withTransaction {
                dao.active().forEach { run ->
                    val pending = if (run.state == RunState.APPROVAL) run.conversationId?.let { id ->
                        conversations.getConversationById(Uuid.parse(id))?.currentMessages?.any { message -> message.getTools().any { it.isPending } }
                    } == true else false
                    if (run.state == RunState.RUNNING || (run.state == RunState.APPROVAL && !pending)) {
                        dao.put(run.ended(RunState.FAILED, "应用进程中断，请查看聊天内容后决定是否重新执行"))
                        dao.prune(run.taskId)
                    }
                }
            }
        }
        recovered = true
    }

    suspend fun validate(definition: TaskDefinition) {
        definition.validate()
        val settings = settingsStore.awaitLoaded()
        require(settings.assistants.any { it.id.toString() == definition.assistantId }) { "所选助手已删除" }
        definition.modelId?.let {
            require(settings.findModelById(Uuid.parse(it))?.type == ModelType.CHAT) { "所选聊天模型已删除" }
        }
        definition.validateTarget(definition.conversationId?.let { conversations.getConversationById(Uuid.parse(it)) })
    }

    suspend fun save(id: String, definition: TaskDefinition, enabled: Boolean?, revision: String? = null) {
        recover()
        Uuid.parse(id)
        validate(definition)
        lock.withLock {
            val now = System.currentTimeMillis()
            val before = dao.task(id)
            if (revision == null && before != null) {
                require(before.definition == definition && before.enabled == (enabled ?: before.enabled)) { "任务 ID 已存在" }
                return@withLock
            }
            require(before?.revision == revision) { "任务已变化，请重新读取后修改" }
            val shouldEnable = enabled ?: before?.enabled ?: false
            require(!shouldEnable || before?.enabled == true || AutomationAlarms.allowed(context)) { "请先授权精确闹钟，或保存为停用任务" }
            require(dao.tasks().none { it.id != id && it.assistantId == definition.assistantId && it.definition.name == definition.name }) {
                "该助手已有同名任务" }
            val base = before ?: AutomationTask(id, definition.assistantId, definition, false, "", null, now, now)
            val task = base.reconfigure(definition, shouldEnable, now, Uuid.random().toString())
            dao.put(task)
            AutomationAlarms.schedule(context, task)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean, revision: String) {
        recover()
        lock.withLock {
            val old = dao.task(id) ?: error("任务已删除")
            require(old.revision == revision) { "任务已变化，请重新读取" }
            require(!enabled || AutomationAlarms.allowed(context)) { "请先授权精确闹钟" }
            val next = if (enabled) old.definition.schedule.next(System.currentTimeMillis(), old.createdAt) else null
            require(!enabled || next != null) { "排程时间已经结束" }
            val updated = old.copy(enabled = enabled, nextRunAt = next, revision = Uuid.random().toString(), updatedAt = System.currentTimeMillis())
            dao.put(updated)
            AutomationAlarms.schedule(context, updated)
        }
    }

    suspend fun delete(id: String, revision: String) {
        recover()
        lock.withLock {
            val task = dao.task(id) ?: return@withLock
            require(task.revision == revision) { "任务已变化，请重新读取" }
            require(dao.active().none { it.taskId == id }) { "请先取消正在执行的任务" }
            dao.delete(id)
            AutomationAlarms.cancel(context, id)
        }
    }

    suspend fun queue(id: String, revision: String, requestId: String = Uuid.random().toString(), dueAt: Long? = null): AutomationRun? {
        recover()
        val result = lock.withLock {
            database.withTransaction {
                dao.run(requestId)?.let { require(it.taskId == id); return@withTransaction it }
                val task = dao.task(id) ?: error("任务已删除")
                if (dueAt != null && (!task.enabled || task.nextRunAt != dueAt || task.revision != revision || dueAt > System.currentTimeMillis())) {
                    return@withTransaction null
                }
                require(task.revision == revision) { "任务已变化，请重新读取" }
                val active = dao.active().firstOrNull { it.taskId == id }
                if (dueAt != null) {
                    val next = task.definition.schedule.next(System.currentTimeMillis(), task.createdAt)
                    dao.put(task.copy(nextRunAt = next, enabled = next != null))
                }
                val run = AutomationRun(requestId, id, task.definition, dueAt ?: System.currentTimeMillis(), dueAt == null)
                val recorded = if (active == null) run else run.ended(RunState.SKIPPED, "上一次执行尚未结束")
                dao.put(recorded)
                dao.prune(id)
                recorded
            }.also { dao.task(id)?.let { AutomationAlarms.schedule(context, it) } }
        }
        refreshRetry()
        return result
    }

    suspend fun bind(runId: String, conversationId: String, modelId: String, modelName: String): Boolean = lock.withLock {
        database.withTransaction {
            val run = dao.run(runId) ?: return@withTransaction false
            if (run.state != RunState.QUEUED || run.activeTaskId == null || dao.forConversation(conversationId) != null) return@withTransaction false
            dao.put(run.copy(state = RunState.RUNNING, activeConversationId = conversationId, conversationId = conversationId,
                executionModelId = modelId, executionModelName = modelName))
            true
        }
    }

    suspend fun beginSegment(id: String): AutomationRun? = lock.withLock {
        val run = dao.run(id)?.takeIf { it.activeTaskId != null } ?: return@withLock null
        run.copy(state = RunState.RUNNING).also { dao.put(it) }
    }

    suspend fun finish(id: String, state: RunState, detail: String = "", generationMs: Long = 0): AutomationRun? = lock.withLock {
        database.withTransaction {
            val run = dao.run(id)?.takeIf { it.activeTaskId != null } ?: return@withTransaction null
            val updated = if (state == RunState.APPROVAL) run.copy(state = state, generationMs = run.generationMs + generationMs)
                else run.ended(state, detail).copy(generationMs = run.generationMs + generationMs)
            dao.put(updated)
            dao.prune(run.taskId)
            updated
        }
    }

    suspend fun reconcile(clockChanged: Boolean = false) {
        recover()
        lock.withLock {
            dao.tasks().forEach { old ->
                val next = if (!old.enabled) null else if (clockChanged || old.nextRunAt == null) {
                    old.definition.schedule.next(System.currentTimeMillis(), old.createdAt)
                } else old.nextRunAt
                val updated = old.copy(nextRunAt = next, enabled = old.enabled && next != null,
                    revision = if (clockChanged) Uuid.random().toString() else old.revision)
                if (updated != old) dao.put(updated)
                AutomationAlarms.schedule(context, updated)
            }
        }
        refreshRetry()
        kick()
    }

    suspend fun refreshRetry() = AutomationAlarms.retry(context, dao.active().any { it.state == RunState.QUEUED })

    suspend fun kick() {
        if (dao.active().none { it.state == RunState.QUEUED }) return
        runCatching { AutomationService.start(context) }.onFailure {
            android.util.Log.w("Automation", "Cannot start pending tasks in background; retry on next foreground/alarm", it)
        }
    }

    private fun AutomationRun.ended(state: RunState, detail: String) = copy(
        state = state, activeTaskId = null, activeConversationId = null, endedAt = System.currentTimeMillis(), detail = detail.take(1000))
}
