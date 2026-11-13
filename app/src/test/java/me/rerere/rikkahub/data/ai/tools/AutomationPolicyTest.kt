package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPolicyTest {
    @Test fun `draft edits do not request approval but final persistence does`() {
        assertTrue(setOf("create", "update", "set_enabled").none { it in automationApprovalActions })
        assertTrue("save" in automationApprovalActions)
        assertTrue(automationApprovalActions.containsAll(listOf("delete", "run", "cancel")))
    }

    @Test fun `scheduled execution cannot mutate or trigger schedules`() {
        automationReadActions.forEach { requireAutomationAction(it, scheduled = true) }
        automationWriteActions.forEach {
            assertTrue(runCatching { requireAutomationAction(it, scheduled = true) }.isFailure)
            requireAutomationAction(it, scheduled = false)
        }
        assertTrue(runCatching { requireAutomationAction("unknown", false) }.isFailure)
    }
}
