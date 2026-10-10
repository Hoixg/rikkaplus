package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ScheduledTaskConversationWindowTest {
    @Test
    fun followUpCanKeepVisibleHistoryOutOfTheNextModelRequest() {
        val oldUser = UIMessage.user("previous request")
        val oldReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("previous reply")))
        val currentUser = UIMessage.user("scheduled request")
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random())
            .updateCurrentMessages(listOf(oldUser, oldReply, currentUser))

        val window = conversation.requestContextFromNode(2)

        assertEquals(listOf(currentUser), window.messages)
        assertEquals(listOf(2), window.sourceNodeIndexes)
        assertNull(window.checkpointContent)

        val currentReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("scheduled reply")))
        val updated = conversation.updateRequestWindowMessages(window, window.messages + currentReply)
        assertEquals(listOf(oldUser, oldReply, currentUser, currentReply), updated.currentMessages)
    }
}
