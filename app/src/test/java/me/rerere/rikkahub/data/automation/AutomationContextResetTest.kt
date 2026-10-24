package me.rerere.rikkahub.data.automation

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AutomationContextResetTest {
    private val conversation = Conversation(
        assistantId = Uuid.random(),
        title = "Scheduled conversation",
        messageNodes = listOf(
            MessageNode(messages = listOf(UIMessage.user("old branch"), UIMessage.user("selected branch")), selectIndex = 1),
            UIMessage.user("old summary").copy(isContextCheckpoint = true).toMessageNode(),
        ),
        chatSuggestions = listOf("old suggestion"),
        isPinned = true,
        customSystemPrompt = "system prompt",
        config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
        workspaceCwd = "/workspace/project",
        folderId = Uuid.random(),
        modeInjectionIds = setOf(Uuid.random()),
        lorebookIds = setOf(Uuid.random()),
    )
    private val definition = TaskDefinition(
        name = "task",
        prompt = "scheduled request",
        assistantId = conversation.assistantId.toString(),
        mode = TaskMode.FOLLOW_UP,
        conversationId = conversation.id.toString(),
        resetContextBeforeRun = true,
    )

    @Test
    fun `reset deletes every branch checkpoint and suggestion while preserving chat configuration`() {
        val cleared = definition.prepareConversation(conversation)

        assertEquals(conversation.copy(messageNodes = emptyList(), chatSuggestions = emptyList()), cleared)
        assertTrue(cleared.currentMessages.limitContext(0).isEmpty())
        assertEquals(2, conversation.messageNodes.size)
    }

    @Test
    fun `disabled reset keeps the complete chat unchanged`() {
        assertSame(conversation, definition.copy(resetContextBeforeRun = false).prepareConversation(conversation))
    }

    @Test
    fun `other execution modes cannot enable reset and never clear the source chat`() {
        listOf(TaskMode.NEW_CHAT, TaskMode.REGENERATE).forEach { mode ->
            val invalid = definition.copy(mode = mode, userMessageId = Uuid.random().toString())
            assertTrue(runCatching { invalid.validate() }.isFailure)
            assertSame(conversation, invalid.prepareConversation(conversation))
        }
        definition.validate()
    }

    @Test
    fun `each subsequent execution keeps only its latest prompt and reply`() {
        var current = conversation
        repeat(3) { round ->
            val request = UIMessage.user("request $round")
            val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply $round")))
            current = definition.prepareConversation(current).updateCurrentMessages(listOf(request, reply))
            assertEquals(listOf(request, reply), current.currentMessages.limitContext(0))
            assertEquals(2, current.messageNodes.size)
            assertTrue(current.messageNodes.all { it.messages.size == 1 })
        }
    }

    @Test
    fun `task and execution snapshot persist the reset setting through existing converters`() {
        assertFalse(TaskDefinition().resetContextBeforeRun)
        val restored = AutomationConverters.decodeDefinition(AutomationConverters.encodeDefinition(definition))
        assertEquals(definition, restored)

        val task = AutomationTask("task", definition.assistantId, definition, true, "revision", 1000, 0, 0)
        val run = AutomationRun("run", task.id, restored, 1000, false)
        val edited = task.reconfigure(definition.copy(resetContextBeforeRun = false), null, 500, "edited")
        assertFalse(edited.definition.resetContextBeforeRun)
        assertTrue(run.definition.resetContextBeforeRun)
    }
}
