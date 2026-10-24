package me.rerere.rikkahub.data.automation

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import me.rerere.rikkahub.utils.JsonInstant

/** Configuration and run state are separate; changing a task never changes a run's snapshot. */
@Entity(tableName = "automation_task", indices = [Index("assistantId")])
data class AutomationTask(
    @PrimaryKey val id: String,
    val assistantId: String,
    val definition: TaskDefinition,
    val enabled: Boolean,
    val revision: String,
    val nextRunAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Editing changes future execution only; the active run keeps its own immutable definition. */
    internal fun reconfigure(definition: TaskDefinition, requestedEnabled: Boolean?, now: Long, revision: String): AutomationTask {
        val enabled = requestedEnabled ?: this.enabled
        val next = when {
            !enabled -> null
            this.enabled && this.definition.schedule == definition.schedule -> nextRunAt
            else -> definition.schedule.next(now, createdAt)
        }
        require(!enabled || next != null) { "排程时间已经结束，请选择未来的时间" }
        return copy(assistantId = definition.assistantId, definition = definition, enabled = enabled,
            revision = revision, nextRunAt = next, updatedAt = now)
    }
}

enum class RunState(val label: String) {
    QUEUED("等待聊天空闲"), RUNNING("执行中"), APPROVAL("等待审批"),
    SUCCESS("已完成"), FAILED("执行失败"), CANCELLED("已取消"), SKIPPED("已跳过"),
}

@Entity(
    tableName = "automation_run",
    foreignKeys = [ForeignKey(entity = AutomationTask::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index("activeTaskId", unique = true), Index("activeConversationId", unique = true)],
)
data class AutomationRun(
    @PrimaryKey val id: String,
    val taskId: String,
    val definition: TaskDefinition,
    val dueAt: Long,
    val manual: Boolean,
    val state: RunState = RunState.QUEUED,
    val activeTaskId: String? = taskId,
    val activeConversationId: String? = null,
    val conversationId: String? = null,
    val executionModelId: String? = null,
    val executionModelName: String = "",
    val generationMs: Long = 0,
    val endedAt: Long? = null,
    val detail: String = "",
)

object AutomationConverters {
    @TypeConverter fun encodeDefinition(value: TaskDefinition): String = JsonInstant.encodeToString(value)
    @TypeConverter fun decodeDefinition(value: String): TaskDefinition = JsonInstant.decodeFromString(value)
}
