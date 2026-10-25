package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_GENERATE_TOOL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolResultTransformerTest {
    private val tool = UIMessagePart.Tool(
        "call",
        MEDIA_GENERATE_TOOL,
        """{"kind":"video","prompt":"hello","_target":{"endpoint":"private"},"_references":["private-uri"],"_record_id":"id"}""",
        output = listOf(
            UIMessagePart.Text("""{"status":"succeeded"}"""),
            UIMessagePart.Image("file:///image.png"),
            UIMessagePart.Video("file:///video.mp4")
        )
    )

    @Test fun `text models receive status without private snapshots or unsupported media`() {
        val messages = listOf(UIMessage.user("test").copy(parts = listOf(tool)))
        val result = mediaToolRequestMessages(messages, false).single().parts.single() as UIMessagePart.Tool
        assertFalse(
            result.input.contains("_target") || result.input.contains("private") || result.input.contains("_record_id")
        )
        assertTrue(result.output.all { it is UIMessagePart.Text })
        assertEquals(tool, messages.single().parts.single())
    }

    @Test fun `vision models keep actual images and videos remain available to UI only`() {
        val result = mediaToolRequestMessages(
            listOf(UIMessage.user("test").copy(parts = listOf(tool))),
            true
        ).single().parts.single() as UIMessagePart.Tool
        assertEquals(1, result.output.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(result.output.none { it is UIMessagePart.Video })
    }

    @Test fun `unrelated tools are unchanged`() {
        val original = tool.copy(toolName = "other")
        val messages = listOf(UIMessage.user("test").copy(parts = listOf(original)))
        assertEquals(messages, mediaToolRequestMessages(messages, false))
    }
}
