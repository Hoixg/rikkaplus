package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPolicyTest {
    @Test fun `scheduled execution cannot mutate or trigger schedules`() {
        automationReadActions.forEach { requireAutomationAction(it, scheduled = true) }
        automationWriteActions.forEach {
            assertTrue(runCatching { requireAutomationAction(it, scheduled = true) }.isFailure)
            requireAutomationAction(it, scheduled = false)
        }
        assertTrue(runCatching { requireAutomationAction("unknown", false) }.isFailure)
    }
}
