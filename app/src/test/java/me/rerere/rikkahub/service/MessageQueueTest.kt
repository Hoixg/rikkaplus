package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `moving messages changes send order while preserving attachments and voice observers`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("first"))
        queue.enqueue(text("second") + UIMessagePart.Image("file:///reference.png"), answer = false, reply = reply)
        queue.enqueue(text("third"))
        val original = queue.state.value.messages

        assertTrue(queue.move(original[1].id, original[0].id))
        assertTrue(queue.move(original[0].id, original[2].id))

        assertEquals(listOf(original[1], original[2], original[0]), List(3) { queue.takeNext()!! })
        assertFalse(reply.isCompleted)
    }

    @Test
    fun `sorting holds automatic dispatch without pausing or failing voice messages`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("first"), reply = reply)
        queue.enqueue(text("second"))
        val scheduled = queue.state.value.messages.first()
        val next = queue.state.value.messages.last()

        queue.setReordering(true)
        assertNull(queue.takeNext(scheduled.id))
        assertTrue(queue.move(next.id, scheduled.id))
        assertNull(queue.takeNext())
        assertFalse(queue.state.value.paused)
        assertFalse(reply.isCompleted)
        queue.setReordering(false)

        assertNull(queue.takeNext(scheduled.id))
        assertEquals(next, queue.takeNext())
        assertEquals(scheduled, queue.takeNext())
    }

    @Test
    fun `finishing sorting cannot resume a queue paused by the user`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val previous = queue.state.value.messages.first()
        val next = queue.state.value.messages.last()
        queue.pause()

        queue.setReordering(true)
        assertTrue(queue.move(next.id, previous.id))
        queue.setReordering(false)

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(next, queue.takeNext())
    }

    @Test
    fun `stale source target and same item moves cannot lose or recreate messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("removed"))
        val removed = queue.takeNext()!!
        assertFalse(queue.move(removed.id, removed.id))
        queue.enqueue(text("first"))
        queue.enqueue(text("last"))
        val original = queue.state.value.messages

        assertFalse(queue.move(removed.id, original.first().id))
        assertFalse(queue.move(original.first().id, removed.id))
        assertFalse(queue.move(original.first().id, original.first().id))

        assertEquals(original, queue.state.value.messages)
    }

    @Test
    fun `sorting cannot move an editing placeholder or move another message past it`() {
        val queue = MessageQueue()
        queue.enqueue(text("editing"))
        queue.enqueue(text("second"))
        val original = queue.state.value.messages
        queue.beginEdit(original.first().id)

        assertFalse(queue.move(original.first().id, original.last().id))
        assertFalse(queue.move(original.last().id, original.first().id))
        assertNull(queue.takeNext())
        queue.finishEdit(original.first().id, text("edited"))
        assertTrue(queue.move(original.last().id, original.first().id))

        assertEquals(original.last(), queue.takeNext())
        assertEquals(text("edited"), queue.takeNext()!!.parts)
    }

    @Test
    fun `sorting an empty or single message queue cannot hold future input`() {
        val queue = MessageQueue()
        queue.setReordering(true)
        queue.enqueue(text("only"))
        queue.setReordering(true)

        assertFalse(queue.state.value.reordering)
        assertEquals(text("only"), queue.takeNext()!!.parts)
    }

    @Test
    fun `dragging across multiple rows preserves the relative order of other messages`() {
        val queue = MessageQueue()
        listOf("first", "second", "third", "fourth").forEach { queue.enqueue(text(it)) }
        val original = queue.state.value.messages

        assertTrue(queue.move(original[0].id, original[3].id))
        assertEquals(listOf(original[1], original[2], original[3], original[0]), queue.state.value.messages)
        assertTrue(queue.move(original[0].id, original[1].id))
        assertEquals(original, queue.state.value.messages)
    }

    @Test
    fun `a long drag cannot cross an editing placeholder`() {
        val queue = MessageQueue()
        listOf("first", "editing", "third").forEach { queue.enqueue(text(it)) }
        val original = queue.state.value.messages
        queue.beginEdit(original[1].id)

        assertFalse(queue.move(original[0].id, original[2].id))
        assertFalse(queue.move(original[2].id, original[0].id))
        assertEquals(original.map { it.id }, queue.state.value.messages.map { it.id })
    }

    @Test
    fun `taking an interjection preserves attachments answer mode and voice observer`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        val parts = text("interject") + UIMessagePart.Image("file:///reference.png")
        queue.enqueue(text("first"))
        queue.enqueue(parts, answer = false, reply = reply)
        queue.enqueue(text("last"))
        val selected = queue.state.value.messages[1]

        assertEquals(selected, queue.take(selected.id))
        assertFalse(reply.isCompleted)
        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertEquals(text("last"), queue.takeNext()!!.parts)
        assertNull(queue.take(selected.id))
    }

    @Test
    fun `explicit interjection does not resume other paused messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("interject"))
        queue.pause()
        val selected = queue.state.value.messages.last()

        assertEquals(selected, queue.take(selected.id))
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("first"), queue.takeNext()!!.parts)
    }

    @Test
    fun `interjection cannot take a message being edited`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"))
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)

        assertNull(queue.take(id))
        queue.finishEdit(id, text("edited"))
        assertEquals(text("edited"), queue.take(id)!!.parts)
        queue.finishEdit(id, text("late edit"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `removing a scheduled head does not dispatch another message with different answer mode`() {
        val queue = MessageQueue()
        queue.enqueue(text("without answer"), answer = false)
        queue.enqueue(text("with answer"))
        val scheduled = queue.state.value.messages.first()
        queue.remove(scheduled.id)

        assertNull(queue.takeNext(scheduled.id))
        val next = queue.takeNext()!!
        assertEquals(text("with answer"), next.parts)
        assertTrue(next.answer)
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }
}
