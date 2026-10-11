package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ScheduledTaskConversationWindowTest {
    @Test
    fun resetFollowUpDeletesPreviousHistoryBeforeSendingTheNextPrompt() {
        val oldUser = UIMessage.user("previous request")
        val oldReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("previous reply")))
        val currentUser = UIMessage.user("scheduled request")
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random())
            .updateCurrentMessages(listOf(oldUser, oldReply))
            .withoutHistory()
            .updateCurrentMessages(listOf(currentUser))

        val window = conversation.requestContextFromNode(0)

        assertEquals(listOf(currentUser), window.messages)
        assertEquals(listOf(0), window.sourceNodeIndexes)
        assertNull(window.checkpointContent)

        val currentReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("scheduled reply")))
        val updated = conversation.updateRequestWindowMessages(window, window.messages + currentReply)
        assertEquals(listOf(currentUser, currentReply), updated.currentMessages)
        assertEquals(2, updated.messageNodes.size)
    }

    @Test
    fun clearingHistoryRemovesBranchesCheckpointsAndSuggestionsButPreservesConfiguration() {
        val original = Conversation(
            assistantId = Uuid.random(),
            title = "Scheduled conversation",
            messageNodes = listOf(
                MessageNode(messages = listOf(UIMessage.user("old branch"), UIMessage.user("selected branch")), selectIndex = 1),
                UIMessage.user("summary").copy(isContextCheckpoint = true).toMessageNode(),
            ),
            chatSuggestions = listOf("old suggestion"),
            compressionSummaries = listOf(CompressionSummary(content = "legacy summary")),
            isPinned = true,
            customSystemPrompt = "system prompt",
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val cleared = original.withoutHistory()

        assertEquals(original.copy(messageNodes = emptyList(), chatSuggestions = emptyList(), compressionSummaries = emptyList()), cleared)
        assertTrue(cleared.currentMessages.isEmpty())
        assertTrue(cleared.requestContextFromNode(0).messages.isEmpty())
        assertNull(cleared.activeCompressionForRequest())
    }

    @Test
    fun repeatedResetsKeepOnlyTheLatestRound() {
        var conversation = Conversation.ofId(Uuid.random(), Uuid.random())
        repeat(3) { round ->
            val request = UIMessage.user("request $round")
            val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply $round")))
            conversation = conversation.withoutHistory().updateCurrentMessages(listOf(request))
            val window = conversation.requestContextFromNode(0)
            conversation = conversation.updateRequestWindowMessages(window, window.messages + reply)
            assertEquals(listOf(request, reply), conversation.currentMessages)
            assertEquals(2, conversation.messageNodes.size)
        }
    }
}
