// Adapted from ExTV/rikkahub-agent, a88f5854. SPDX-License-Identifier: AGPL-3.0-only
package me.rerere.rikkahub.accessibility.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeToolTest {

    @Test
    fun `swipe rejects missing end_y`() {
        val tool = swipeTool()
        val result = execTool(tool, """{"start_x":0,"start_y":0,"end_x":100}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `swipe rejects negative coordinate`() {
        val tool = swipeTool()
        val result = execTool(tool, """{"start_x":0,"start_y":-1,"end_x":100,"end_y":100}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `swipe rejects duration below floor`() {
        val tool = swipeTool()
        val result = execTool(
            tool,
            """{"start_x":0,"start_y":0,"end_x":100,"end_y":100,"duration_ms":10}"""
        )
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `swipe returns service-not-active when service offline`() {
        val tool = swipeTool()
        val result = execTool(tool, """{"start_x":0,"start_y":0,"end_x":100,"end_y":100}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }
}
