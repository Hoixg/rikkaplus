// Adapted from ExTV/rikkahub-agent, a88f5854. SPDX-License-Identifier: AGPL-3.0-only
package me.rerere.rikkahub.accessibility.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToolTest {

    @Test
    fun `scroll rejects missing direction`() {
        val tool = scrollTool()
        val result = execTool(tool, """{}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `scroll rejects unknown direction`() {
        val tool = scrollTool()
        val result = execTool(tool, """{"direction":"diagonal"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `scroll returns service-not-active when offline`() {
        val tool = scrollTool()
        val result = execTool(tool, """{"direction":"down"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }
}
