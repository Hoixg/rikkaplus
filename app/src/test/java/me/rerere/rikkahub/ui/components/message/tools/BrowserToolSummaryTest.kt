package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class BrowserToolSummaryTest {
    private fun context(action: String, owned: Boolean = true): ToolUIContext {
        val arguments = buildJsonObject { put("action", action) }
        return ToolUIContext(
            tool = UIMessagePart.Tool("id", "browser", arguments.toString()),
            arguments = arguments,
            content = buildJsonObject { if (owned) put("conversation_id", "chat"); put("url", "https://example.com/page") },
            loading = false,
        )
    }

    @Test fun `navigation exposes the compact page entry`() {
        listOf("open", "new_tab", "render", "switch_tab").forEach { assertTrue(BrowserToolUI.hasSummary(context(it))) }
    }

    @Test fun `repeated operations do not repeat a URL and open entry`() {
        listOf("click", "type", "select_option", "press", "scroll", "snapshot", "extract", "wait", "reload", "evaluate", "screenshot")
            .forEach { assertFalse(BrowserToolUI.hasSummary(context(it))) }
    }

    @Test fun `an error without an owning conversation never creates a page entry`() {
        assertFalse(BrowserToolUI.hasSummary(context("open", owned = false)))
    }
}
