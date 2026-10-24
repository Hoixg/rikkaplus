package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AutomationReservationTest {
    @Test fun `background work never cancels a user job or jumps a paused message queue`() = runTest {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, FakeConversationStore(), {})
        session.initialize(load = { null }, create = { Conversation.ofId(id) })
        val user = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(user)
        val automation = launch(start = CoroutineStart.LAZY) { error("Must not start") }
        assertFalse(session.tryStartAutomation(automation))
        assertSame(user, session.getJob())
        automation.cancel()
        user.cancel(); runCurrent()
        session.messageQueue.enqueue(listOf(UIMessagePart.Text("user first")))
        session.messageQueue.pause()
        val next = launch(start = CoroutineStart.LAZY) { error("Must not start") }
        assertFalse(session.tryStartAutomation(next))
        assertEquals("user first", (session.messageQueue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
        next.cancel(); session.cleanup()
    }

    @Test fun `unresolved approval holds the conversation and idle reservation is exclusive`() = runTest {
        val id = Uuid.random()
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "approval", toolName = "write_file", input = "{}", approvalState = ToolApprovalState.Pending)))
        val initial = Conversation.ofId(id).updateCurrentMessages(listOf(pending))
        val session = ConversationSession(id, initial, this, FakeConversationStore(), {})
        session.initialize(load = { initial })
        val blocked = launch(start = CoroutineStart.LAZY) { error("Must not start") }
        assertFalse(session.tryStartAutomation(blocked)); blocked.cancel()
        session.update { it.copy(messageNodes = emptyList()) }
        val first = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        assertTrue(session.tryStartAutomation(first))
        val second = launch(start = CoroutineStart.LAZY) { error("Must not start") }
        assertFalse(session.tryStartAutomation(second))
        assertSame(first, session.getJob())
        second.cancel(); session.cleanup()
    }
}
