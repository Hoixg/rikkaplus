package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_GENERATE_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_CANCEL_TOOL
import me.rerere.rikkahub.data.ai.tools.local.MEDIA_TASK_READ_TOOL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    private val recordId = "aabbccdd-1234-5678-90ab-1234567890ab"
    private val otherRecordId = "aabbccdd-1234-5678-90ab-1234567890ac"
    private val image = UIMessagePart.Image("file:///media/result.png")
    private val video = UIMessagePart.Video("file:///media/result.mp4")

    private fun mediaTool(
        callId: String,
        id: String = recordId,
        name: String = MEDIA_TASK_READ_TOOL,
        status: String = "succeeded",
        error: String? = null,
    ) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = name,
        input = buildJsonObject {
            put(if (name == MEDIA_GENERATE_TOOL) "_record_id" else "record_id", id)
        }.toString(),
        output = listOf(UIMessagePart.Text(buildJsonObject {
            put("record_id", id)
            put("status", status)
            error?.let { put("error", it) }
        }.toString()), image),
    )

    private fun List<UIMessagePart>.toolSteps() = groupMessageParts()
        .filterIsInstance<MessagePartBlock.ThinkingBlock>()
        .flatMap { it.steps }
        .filterIsInstance<ThinkingStep.ToolStep>()

    @Test
    fun `repeated progress reads show one card without changing tool history or outputs`() {
        val first = mediaTool("read-1", status = "running")
        val second = mediaTool("read-2")
        val steps = listOf(first, second).toolSteps()

        assertEquals(listOf(false, true), steps.map { it.showSummary })
        assertEquals(listOf("read-1", "read-2"), steps.map { it.tool.toolCallId })
        assertSame(first, steps[0].tool)
        assertSame(second, steps[1].tool)
        assertSame(image, steps[0].tool.output[1])
        assertSame(image, steps[1].tool.output[1])
    }

    @Test
    fun `generate read and stop share one live card for images and videos`() {
        for (media in listOf(image, video)) {
            val calls = listOf(
                mediaTool("generate", name = MEDIA_GENERATE_TOOL),
                mediaTool("read-1"),
                mediaTool("read-2"),
                mediaTool("stop", name = MEDIA_TASK_CANCEL_TOOL, status = "cancelled"),
            ).map { it.copy(output = listOf(it.output.first(), media)) }

            val steps = calls.toolSteps()
            assertEquals(listOf(false, false, false, true), steps.map { it.showSummary })
            assertEquals(calls, steps.map { it.tool })
        }
    }

    @Test
    fun `separate tasks with identical outputs each keep a card`() {
        val calls = listOf(
            mediaTool("first-task-1"),
            mediaTool("other-task", id = otherRecordId),
            mediaTool("first-task-2"),
        )

        assertEquals(listOf(false, true, true), calls.toolSteps().map { it.showSummary })
    }

    @Test
    fun `text between polls preserves original content order and indices`() {
        val first = mediaTool("read-1")
        val text = UIMessagePart.Text("正在查看结果")
        val second = mediaTool("read-2")
        val blocks = listOf(first, text, second).groupMessageParts()

        assertEquals(3, blocks.size)
        assertEquals(MessagePartBlock.ContentBlock(text, 1), blocks[1])
        assertEquals(listOf(false, true), listOf(first, text, second).toolSteps().map { it.showSummary })
    }

    @Test
    fun `approval and denial remain visible and do not take over a successful card`() {
        val pending = mediaTool("pending-stop", name = MEDIA_TASK_CANCEL_TOOL).copy(
            output = emptyList(),
            approvalState = ToolApprovalState.Pending,
        )
        val denied = mediaTool("denied-stop", name = MEDIA_TASK_CANCEL_TOOL).copy(
            approvalState = ToolApprovalState.Denied("继续生成"),
        )
        val calls = listOf(mediaTool("read-1"), pending, denied, mediaTool("read-2"))

        val steps = calls.toolSteps()
        assertEquals(listOf(false, true, true, true), steps.map { it.showSummary })
        assertSame(pending, steps[1].tool)
        assertSame(denied, steps[2].tool)
    }

    @Test
    fun `failed calls preserve error summaries even when a later query succeeds`() {
        val error = mediaTool("failed-read", status = "failed", error = "文件已删除")
        val calls = listOf(mediaTool("read-1"), error, mediaTool("read-2"))

        val steps = calls.toolSteps()
        assertEquals(listOf(false, true, true), steps.map { it.showSummary })
        assertSame(error, steps[1].tool)
    }

    @Test
    fun `in flight read uses the live card and a query error restores the earlier card`() {
        val first = mediaTool("read-1")
        val running = mediaTool("read-2").copy(output = emptyList())
        assertEquals(listOf(false, true), listOf(first, running).toolSteps().map { it.showSummary })

        val failed = running.copy(output = listOf(UIMessagePart.Text("""{"error":"查询失败"}""")))
        assertEquals(listOf(true, true), listOf(first, failed).toolSteps().map { it.showSummary })

        val completed = mediaTool("read-2")
        assertEquals(listOf(false, true), listOf(first, completed).toolSteps().map { it.showSummary })
    }

    @Test
    fun `partial arguments unknown ids and malformed results never hide another card`() {
        val first = mediaTool("read-1")
        val incomplete = first.copy(toolCallId = "partial", input = """{"record_id":""", output = emptyList())
        val malformed = first.copy(toolCallId = "malformed", output = listOf(UIMessagePart.Text("truncated {")))
        val unknown = first.copy(toolCallId = "unknown", output = listOf(UIMessagePart.Text("{}")))
        val invalidId = mediaTool("invalid", id = "not-a-uuid")
        val calls = listOf(first, incomplete, malformed, unknown, invalidId)

        assertTrue(calls.toolSteps().all { it.showSummary })
    }

    @Test
    fun `uuid case is normalized but conflicting task ids remain visible`() {
        val first = mediaTool("read-1")
        val upperCase = mediaTool("read-2", id = recordId.uppercase())
        val conflicting = mediaTool("conflicting", id = otherRecordId).copy(output = first.output)

        assertEquals(
            listOf(false, true, true),
            listOf(first, upperCase, conflicting).toolSteps().map { it.showSummary }
        )
    }

    @Test
    fun `deduplication is scoped to one reply and leaves other tools and charts unchanged`() {
        val first = mediaTool("read-1")
        val second = mediaTool("read-2")
        assertTrue(listOf(first).toolSteps().single().showSummary)
        assertTrue(listOf(second).toolSteps().single().showSummary)

        val ordinary = first.copy(toolCallId = "other-tool", toolName = "search_web")
        val chart = UIMessagePart.Tool(
            toolCallId = "chart",
            toolName = CHART_DISPLAY_TOOL_NAME,
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"success":true}""")),
        )
        val parts = listOf(first, ordinary, chart, second)
        val blocks = parts.groupMessageParts()
        assertEquals(MessagePartBlock.ChartBlock(chart, 2), blocks[1])
        assertEquals(listOf(false, true, true), parts.toolSteps().map { it.showSummary })
    }
}
