package me.rerere.rikkahub.data.automation

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

class TaskScheduleTest {
    private val utc = ZoneId.of("UTC")
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun `once is consumed at its instant`() {
        val schedule = TaskSchedule(ScheduleKind.ONCE, at = 1000)
        assertEquals(1000L, schedule.next(999, 0, utc))
        assertNull(schedule.next(1000, 0, utc))
    }

    @Test fun `weekly schedule respects inclusive bounds and skips unselected days`() {
        val schedule = TaskSchedule(ScheduleKind.WEEKLY, timeMinutes = 9 * 60, weekdays = setOf(1, 5), startDate = "2026-10-23", endDate = "2026-10-26")
        val friday = time("2026-10-23T09:00:00Z")
        assertEquals(friday, schedule.next(friday - 1, 0, utc))
        assertEquals(time("2026-10-26T09:00:00Z"), schedule.next(friday, 0, utc))
        assertNull(schedule.next(time("2026-10-26T09:00:00Z"), 0, utc))
    }

    @Test fun `future date bound is not restricted to the next calendar week`() {
        val schedule = TaskSchedule(startDate = "2027-01-01", endDate = "2027-01-01")
        assertEquals(time("2027-01-01T09:00:00Z"), schedule.next(time("2026-10-23T00:00:00Z"), 0, utc))
    }

    @Test fun `interval skips missed slots and stays on its original anchor`() {
        val schedule = TaskSchedule(ScheduleKind.INTERVAL, intervalMinutes = 15)
        assertEquals(2_701_000L, schedule.next(2_000_000, 1000, utc))
        assertEquals(3_601_000L, schedule.next(2_701_000, 1000, utc))
    }

    @Test fun `spring daylight saving gap moves the nonexistent time forward`() {
        val schedule = TaskSchedule(timeMinutes = 150)
        assertEquals(time("2026-03-08T07:30:00Z"), schedule.next(time("2026-03-08T05:00:00Z"), 0, ZoneId.of("America/New_York")))
    }

    @Test fun `fall daylight saving overlap does not execute the same local slot twice`() {
        val schedule = TaskSchedule(timeMinutes = 90)
        val first = time("2026-11-01T05:30:00Z")
        assertEquals(first, schedule.next(first - 1, 0, ZoneId.of("America/New_York")))
        assertEquals(time("2026-11-02T06:30:00Z"), schedule.next(first, 0, ZoneId.of("America/New_York")))
    }

    @Test fun `invalid ranges empty weeks and short intervals are rejected`() {
        listOf(TaskSchedule(startDate = "2026-10-24", endDate = "2026-10-23"),
            TaskSchedule(ScheduleKind.WEEKLY, weekdays = emptySet()),
            TaskSchedule(ScheduleKind.INTERVAL, intervalMinutes = 14), TaskSchedule(timeMinutes = 1440)).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
    }

    @Test fun `a task cannot address another assistant or a removed message branch`() {
        val assistantId = Uuid.random()
        val target = Conversation.ofId(Uuid.random(), assistantId).updateCurrentMessages(listOf(UIMessage.user("original")))
        val definition = TaskDefinition("task", "prompt", assistantId.toString(), TaskMode.REGENERATE,
            target.id.toString(), target.currentMessages.single().id.toString())
        definition.validateTarget(target)
        assertTrue(runCatching { definition.validateTarget(target.copy(assistantId = Uuid.random())) }.isFailure)
        assertTrue(runCatching { definition.validateTarget(target.copy(messageNodes = emptyList())) }.isFailure)
        assertTrue(runCatching { definition.validateTarget(null) }.isFailure)
    }

    @Test fun `definition snapshot round trips without runtime or compatibility fields`() {
        val definition = TaskDefinition("task", "new\n".repeat(4000), Uuid.random().toString(), schedule = TaskSchedule(ScheduleKind.WEEKLY))
        val encoded = JsonInstant.encodeToString(definition)
        assertEquals(definition, JsonInstant.decodeFromString<TaskDefinition>(encoded))
        assertFalse(encoded.contains("before"))
        assertFalse(encoded.contains("deadline"))
    }
}
