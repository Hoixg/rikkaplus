package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ScheduledSubagentTargetTest {
    @Test fun `a child trace cannot become a scheduled followup target`() {
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
        val task = ScheduledTaskEntity(id = "task", name = "Task", prompt = "prompt", assistantId = child.assistantId.toString(),
            scheduleType = "INTERVAL", mode = "FOLLOW_UP", targetConversationId = child.id.toString(), createdAt = 1, updatedAt = 1, revision = "rev")
        assertThrows(IllegalArgumentException::class.java) { validateTaskTargetSelection(task, child, Settings()) }
        validateTaskTargetSelection(task, child.copy(parentConversationId = null), Settings())
    }
}
