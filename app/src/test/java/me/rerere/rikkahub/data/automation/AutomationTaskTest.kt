package me.rerere.rikkahub.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTaskTest {
    private val definition = TaskDefinition(
        name = "task",
        prompt = "old prompt",
        assistantId = "11111111-1111-4111-8111-111111111111",
        schedule = TaskSchedule(ScheduleKind.INTERVAL, intervalMinutes = 15),
    )
    private val task = AutomationTask("task-id", definition.assistantId, definition, true, "old-revision", 1_801_000, 1000, 1000)

    @Test
    fun `editing content keeps the unconsumed slot even when its alarm is late`() {
        val edited = task.reconfigure(definition.copy(prompt = "new prompt"), null, 2_000_000, "new-revision")
        assertEquals(task.nextRunAt, edited.nextRunAt)
        assertEquals(task.createdAt, edited.createdAt)
        assertEquals("new prompt", edited.definition.prompt)
        assertEquals("new-revision", edited.revision)
        assertTrue(edited.enabled)
    }

    @Test
    fun `editing a consumed one time task keeps the latest disabled state`() {
        val consumed = task.copy(definition = definition.copy(schedule = TaskSchedule(ScheduleKind.ONCE, at = 1000)), enabled = false, nextRunAt = null)
        val edited = consumed.reconfigure(consumed.definition.copy(prompt = "new prompt"), null, 2000, "new-revision")
        assertFalse(edited.enabled)
        assertNull(edited.nextRunAt)
        assertTrue(runCatching { consumed.reconfigure(edited.definition, true, 2000, "new-revision") }.isFailure)
    }

    @Test
    fun `changing an interval keeps the original schedule anchor`() {
        val edited = task.reconfigure(definition.copy(schedule = definition.schedule.copy(intervalMinutes = 30)), null, 2_000_000, "new-revision")
        assertEquals(3_601_000L, edited.nextRunAt)
        assertEquals(1000L, edited.createdAt)
    }

    @Test
    fun `disabling while editing cancels future scheduling and keeps the run snapshot`() {
        val running = AutomationRun("run-id", task.id, task.definition, 1000, false, RunState.RUNNING)
        val edited = task.reconfigure(definition.copy(prompt = "new prompt"), false, 2000, "new-revision")
        assertFalse(edited.enabled)
        assertNull(edited.nextRunAt)
        assertEquals("old prompt", running.definition.prompt)
        assertEquals(RunState.RUNNING, running.state)
    }
}
