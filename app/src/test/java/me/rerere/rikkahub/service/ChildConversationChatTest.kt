package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChildConversationChatTest {
    @Test fun `stale generation snapshots cannot overwrite the latest child model selection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random(), modelOverrideId = Uuid.random())
        val session = ConversationSession(child.id, child, scope, {})
        val stale = child.updateCurrentMessages(listOf(UIMessage.user("saved before model selection")))
        val chosen = Uuid.random()
        try {
            session.withPersistenceLock { session.updateMetadata({ it.copy(modelOverrideId = chosen) }, {}) }
            session.updateFromGeneration(stale)
            assertEquals(chosen, session.state.value.modelOverrideId)
            session.withPersistenceLock {
                val persisted = session.preserveChildModelOverride(stale)
                assertEquals(chosen, persisted.modelOverrideId)
                assertEquals(stale.messageNodes, persisted.messageNodes)
                assertEquals(child.parentConversationId, persisted.parentConversationId)
            }
        } finally { session.cleanup(); scope.cancel() }
    }

    @Test fun `reloaded pending approval stays blocked after child job reservation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
            .updateCurrentMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("approval", "generate_image", "{}", approvalState = ToolApprovalState.Pending)))))
        val session = ConversationSession(child.id, Conversation.ofId(child.id), scope, {})
        val job = Job()
        try {
            assertTrue(session.tryStartSubagent(job))
            session.initialize { child }
            assertTrue(session.hasPendingToolApprovals())
            assertEquals(child, session.state.value)
        } finally { session.cleanup(); scope.cancel() }
    }

    @Test fun `changing a child model preserves streamed messages and parent association`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random(), modelOverrideId = Uuid.random())
        val session = ConversationSession(child.id, child, scope, {})
        var persisted = child
        val chosen = Uuid.random()
        try {
            session.updateConversation(child.updateCurrentMessages(listOf(UIMessage.user("existing child history"))))
            session.updateMetadata({ it.copy(modelOverrideId = chosen) }, {
                persisted = persisted.copy(modelOverrideId = it.modelOverrideId)
            })
            assertTrue(persisted.messageNodes.isEmpty())
            session.finishGeneration { persisted = it }
            assertEquals(chosen, persisted.modelOverrideId)
            assertEquals(child.parentConversationId, persisted.parentConversationId)
            assertEquals("existing child history", persisted.currentMessages.single().toText())
        } finally { session.cleanup(); scope.cancel() }
    }

    @Test fun `direct user generation rejects a tool followup without cancelling or replacing history`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
            .updateCurrentMessages(listOf(UIMessage.user("direct user turn")))
        val session = ConversationSession(child.id, child, scope, {})
        val userJob = Job()
        val toolJob = Job()
        try {
            session.setJob(userJob)
            assertFalse(session.tryStartSubagent(toolJob))
            assertSame(userJob, session.getJob())
            assertTrue(userJob.isActive)
            assertEquals(child, session.state.value)
        } finally { session.cleanup(); toolJob.cancel(); scope.cancel() }
    }

    @Test fun `user messages wait for tool completion then retain order and child ownership`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
        val dispatched = mutableListOf<String>()
        lateinit var session: ConversationSession
        session = ConversationSession(child.id, child, scope, {}, { _, cause ->
            assertNull(cause)
            assertNull(session.getJob())
            while (true) {
                val next = session.messageQueue.takeNext() ?: break
                dispatched += next.parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
                session.updateConversation(session.state.value.updateCurrentMessages(
                    session.state.value.currentMessages + UIMessage.user(dispatched.last())))
            }
        })
        val toolJob = Job()
        val competing = Job()
        try {
            assertTrue(session.tryStartSubagent(toolJob))
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("first user followup")))
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("second user followup")))
            assertFalse(session.tryStartSubagent(competing))
            assertTrue(dispatched.isEmpty())
            toolJob.complete()
            assertEquals(listOf("first user followup", "second user followup"), dispatched)
            assertEquals(child.parentConversationId, session.state.value.parentConversationId)
            assertEquals(dispatched, session.state.value.currentMessages.map { it.toText() })
        } finally { session.cleanup(); competing.cancel(); scope.cancel() }
    }

    @Test fun `paused user queue retains priority over tool followups after a stopped turn`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
        val session = ConversationSession(child.id, child, scope, {})
        val toolJob = Job()
        try {
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("retained input")))
            session.messageQueue.pause()
            assertFalse(session.tryStartSubagent(toolJob))
            assertNull(session.getJob())
            assertEquals("retained input", (session.messageQueue.state.value.messages.single().parts.single() as UIMessagePart.Text).text)
        } finally { session.cleanup(); toolJob.cancel(); scope.cancel() }
    }

    @Test fun `concurrent tool reservations cannot both own a child session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val child = Conversation.ofId(Uuid.random()).copy(parentConversationId = Uuid.random())
        val session = ConversationSession(child.id, child, scope, {})
        val jobs = List(32) { Job() }
        try {
            val gate = CompletableDeferred<Unit>()
            val attempts = jobs.map { job -> async(Dispatchers.Default) { gate.await(); session.tryStartSubagent(job) } }
            gate.complete(Unit)
            assertEquals(1, attempts.awaitAll().count { it })
            assertTrue(jobs.all { it.isActive })
        } finally { session.cleanup(); jobs.forEach { it.cancel() }; scope.cancel() }
    }
}
