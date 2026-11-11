package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
import org.junit.Assert.*
import org.junit.Test

class BrowserToolTest {
    @Test fun `tool binds the owning conversation and workspace instead of trusting model fields`() = runTest {
        var seenOwner = ""
        var seenWorkspace: String? = null
        val controller = object : BrowserController {
            override suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject): List<UIMessagePart> {
                seenOwner = conversationId; seenWorkspace = workspaceId
                return listOf(UIMessagePart.Text("ok"))
            }
        }
        val tool = buildBrowserTool(controller, "owned-chat", "bound-workspace")
        tool.execute(buildJsonObject { put("action", "open"); put("conversation_id", "other-chat"); put("workspace_id", "other-workspace") })
        assertEquals("owned-chat", seenOwner)
        assertEquals("bound-workspace", seenWorkspace)
    }

    @Test fun `screenshots stay typed images for model continuation`() = runTest {
        val expected = listOf(UIMessagePart.Image("file:///screenshot.png"), UIMessagePart.Text("dimensions"))
        val tool = buildBrowserTool(object : BrowserController {
            override suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject) = expected
        }, "chat", null)
        assertEquals(expected, tool.execute(buildJsonObject { put("action", "screenshot") }))
    }

    @Test fun `cancellation propagates rather than becoming a retryable browser error`() = runTest {
        val tool = buildBrowserTool(object : BrowserController {
            override suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject): List<UIMessagePart> {
                throw CancellationException("stopped")
            }
        }, "chat", null)
        assertTrue(runCatching { tool.execute(buildJsonObject { put("action", "click") }) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `native failures report that a mutation may already have completed`() = runTest {
        val tool = buildBrowserTool(object : BrowserController {
            override suspend fun execute(conversationId: String, workspaceId: String?, arguments: JsonObject): List<UIMessagePart> {
                error("native timeout")
            }
        }, "chat", null)
        val text = tool.execute(buildJsonObject { put("action", "click") }).filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.contains("native timeout"))
        assertTrue(text.contains("may already have completed"))
    }
}
