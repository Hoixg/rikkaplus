package me.rerere.rikkahub.data.automation

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.uuid.Uuid

@Serializable
enum class TaskMode { NEW_CHAT, FOLLOW_UP, REGENERATE }

@Serializable
enum class ScheduleKind { ONCE, DAILY, WEEKLY, INTERVAL }

@Serializable
data class TaskSchedule(
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val at: Long = 0,
    val timeMinutes: Int = 540,
    val intervalMinutes: Int = 1440,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startDate: String? = null,
    val endDate: String? = null,
) {
    fun validate() {
        require(kind !in setOf(ScheduleKind.DAILY, ScheduleKind.WEEKLY) || timeMinutes in 0..1439) { "执行时间无效" }
        require(kind != ScheduleKind.INTERVAL || intervalMinutes >= 15) { "执行间隔至少为 15 分钟" }
        require(kind != ScheduleKind.WEEKLY || (weekdays.all { it in 1..7 } && weekdays.isNotEmpty())) {
            "请至少选择一个有效的星期" }
        val start = startDate?.let(LocalDate::parse)
        val end = endDate?.let(LocalDate::parse)
        require(start == null || end == null || start <= end) { "结束日期不能早于开始日期" }
        require(kind != ScheduleKind.ONCE || at > 0) { "请选择执行日期和时间" }
    }

    /** Local calendar schedules follow time-zone changes; intervals retain their original anchor. */
    fun next(now: Long, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (kind) {
        ScheduleKind.ONCE -> at.takeIf { it > now }
        ScheduleKind.INTERVAL -> {
            val period = intervalMinutes.toLong() * 60_000
            anchor + ((now - anchor).coerceAtLeast(0) / period + 1) * period
        }
        ScheduleKind.DAILY, ScheduleKind.WEEKLY -> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val first = maxOf(today, startDate?.let(LocalDate::parse) ?: today)
            val end = endDate?.let(LocalDate::parse)
            (0L..7L).firstNotNullOfOrNull { offset ->
                val day = first.plusDays(offset)
                if ((end != null && day > end) || (kind == ScheduleKind.WEEKLY && day.dayOfWeek.value !in weekdays)) null
                else day.atTime(LocalTime.of(timeMinutes / 60, timeMinutes % 60)).atZone(zone)
                    .withEarlierOffsetAtOverlap().toInstant().toEpochMilli().takeIf { it > now }
            }
        }
    }

    fun describe(): String {
        val time = "%02d:%02d".format(java.util.Locale.ROOT, timeMinutes / 60, timeMinutes % 60)
        val range = listOfNotNull(startDate?.let { "从 $it 起" }, endDate?.let { "至 $it 止" }).joinToString("，")
        val text = when (kind) {
            ScheduleKind.ONCE -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            ScheduleKind.DAILY -> "每天 $time"
            ScheduleKind.WEEKLY -> weekdays.filter { it in 1..7 }.sorted().joinToString("、") { "周${"一二三四五六日"[it - 1]}" } + " $time"
            ScheduleKind.INTERVAL -> "每 $intervalMinutes 分钟"
        }
        return if (range.isBlank() || kind !in setOf(ScheduleKind.DAILY, ScheduleKind.WEEKLY)) text else "$text · $range"
    }
}

@Serializable
data class TaskDefinition(
    val name: String = "",
    val prompt: String = "",
    val assistantId: String = "",
    val mode: TaskMode = TaskMode.NEW_CHAT,
    val conversationId: String? = null,
    val userMessageId: String? = null,
    val modelId: String? = null,
    val schedule: TaskSchedule = TaskSchedule(),
    val notify: Boolean = true,
    val resetContextBeforeRun: Boolean = false,
) {
    fun validate() {
        require(name.isNotBlank()) { "请输入任务名称" }
        Uuid.parse(assistantId)
        modelId?.let(Uuid::parse)
        require(mode == TaskMode.REGENERATE || prompt.isNotBlank()) { "请输入任务提示词" }
        require(mode == TaskMode.NEW_CHAT || conversationId != null) { "请选择目标聊天" }
        require(mode != TaskMode.REGENERATE || userMessageId != null) { "请选择要重新生成的用户消息" }
        require(!resetContextBeforeRun || mode == TaskMode.FOLLOW_UP) { "只有继续聊天任务支持执行前清空上下文和记录" }
        conversationId?.let(Uuid::parse)
        userMessageId?.let(Uuid::parse)
        schedule.validate()
    }

    /** Called once after reserving a follow-up run; approval continuations keep this run's messages. */
    internal fun prepareConversation(conversation: Conversation): Conversation =
        if (mode == TaskMode.FOLLOW_UP && resetContextBeforeRun) {
            // Compression checkpoints and alternative branches live in messageNodes.
            conversation.copy(messageNodes = emptyList(), chatSuggestions = emptyList())
        } else conversation

    fun validateTarget(target: Conversation?) {
        if (mode == TaskMode.NEW_CHAT) return
        require(target != null && target.id.toString() == conversationId && target.assistantId.toString() == assistantId) {
            "目标聊天已删除或不属于所选助手" }
        if (mode == TaskMode.REGENERATE) require(target.currentMessages.any {
            it.role == MessageRole.USER && it.id.toString() == userMessageId
        }) { "所选用户消息已删除或当前分支已改变" }
    }
}
