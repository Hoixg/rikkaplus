package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInboxInterjectionTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `reordering user inputs preserves pending background notices and cancellation restores only user content`() {
        val inbox = ConversationInbox()
        val parts = text("first") + UIMessagePart.Image("file:///reference.png")
        inbox.enqueue(parts)
        val first = inbox.state.value.messages.single()
        val notice = UIMessagePart.Notice(title = "finished", text = "command result")
        inbox.post("command", notice)
        inbox.enqueue(text("second"))
        val second = inbox.state.value.messages.last()
        val originalNotice = inbox.state.value.notices.single()

        inbox.setReordering(true)
        assertNull(inbox.takeNext())
        assertTrue(inbox.move(second.id, first.id))
        assertEquals(listOf(second, first), inbox.state.value.messages)
        assertEquals(originalNotice, inbox.state.value.items[1])
        var restored = emptyList<UIMessagePart>()
        assertTrue(inbox.cancel(first.id) {
            restored = it
            true
        })
        assertEquals(parts, restored)
        inbox.setReordering(false)
        assertNull(inbox.takeMessage(first.id))
        assertEquals(second, inbox.takeMessage(second.id))
        assertEquals(listOf(notice), inbox.takeNotices())
        assertTrue(inbox.state.value.items.isEmpty())
    }

    @Test
    fun `a background notice cannot be restored as a user draft or sent as an interjection`() {
        val inbox = ConversationInbox()
        val notice = UIMessagePart.Notice(title = "finished", text = "command result")
        inbox.post("command", notice)
        val id = inbox.state.value.notices.single().id
        assertFalse(inbox.cancel(id) { error("Not a user draft") })
        assertNull(inbox.take(id))
        assertNull(inbox.beginEdit(id))
        assertEquals(listOf(notice), inbox.takeNotices())
    }

    @Test
    fun `notices still reach an active turn while user messages are being reordered`() {
        val inbox = ConversationInbox()
        inbox.enqueue(text("first"))
        inbox.enqueue(text("second"))
        val notice = UIMessagePart.Notice(title = "finished", text = "command result")
        inbox.post("command", notice)
        inbox.setReordering(true)
        assertEquals(listOf(notice), inbox.takeNotices())
        assertNull(inbox.takeMessage())
        assertEquals(2, inbox.state.value.messages.size)
    }

    @Test
    fun `cancelling paused input returns text and attachments without resuming the remaining queue`() {
        val queue = ConversationInbox()
        val parts = text("restore me") + UIMessagePart.Image("file:///reference.png")
        queue.enqueue(parts)
        queue.enqueue(text("keep waiting"))
        queue.pause()
        val original = queue.state.value.messages
        var restored = emptyList<UIMessagePart>()

        assertTrue(queue.cancel(original.first().id) {
            restored = it
            true
        })

        assertEquals(parts, restored)
        assertEquals(listOf(original.last()), queue.state.value.messages)
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeMessage())
        assertFalse(queue.cancel(original.first().id) { error("Cannot restore twice") })
    }

    @Test
    fun `cancelling queued voice input releases its observer without sending it`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("voice"), reply = reply)
        assertTrue(queue.cancel(queue.state.value.messages.single().id) { true })
        assertNull(reply.await())
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `a failed draft transfer or active edit leaves the pending item untouched`() {
        val queue = ConversationInbox()
        queue.enqueue(text("pending"))
        val original = queue.state.value.messages.single()
        assertFalse(queue.cancel(original.id) { false })
        assertEquals(listOf(original), queue.state.value.messages)
        queue.beginEdit(original.id)
        assertFalse(queue.cancel(original.id) { error("Editing input is reserved") })
        assertTrue(queue.state.value.messages.single().isEditing)
    }

    @Test
    fun `cancelling a scheduled head cannot dispatch its successor with the wrong answer mode`() {
        val queue = ConversationInbox()
        queue.enqueue(text("scheduled"), answer = false)
        queue.enqueue(text("next"))
        val scheduled = queue.state.value.messages.first()
        assertTrue(queue.cancel(scheduled.id) { true })
        assertNull(queue.takeMessage(scheduled.id))
        val next = queue.takeMessage()!!
        assertTrue(next.answer)
        assertEquals(text("next"), next.parts)
        assertFalse(queue.cancel(next.id) { error("Already sent") })
    }

    @Test
    fun `cancelling while an interjection waits for the previous job prevents its later dispatch`() = runBlocking {
        val queue = ConversationInbox()
        queue.enqueue(text("waiting interjection"))
        val id = queue.state.value.messages.single().id
        val release = CompletableDeferred<Unit>()
        val previous = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        var sent: QueuedMessage? = null
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            afterPreviousGenerations(listOf(previous)) { sent = queue.take(id) }
        }
        previous.cancel()
        assertTrue(queue.cancel(id) { true })
        release.complete(Unit)
        waiting.join()
        assertNull(sent)
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `moving messages changes send order while preserving attachments and voice observers`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("first"))
        queue.enqueue(text("second") + UIMessagePart.Image("file:///reference.png"), answer = false, reply = reply)
        queue.enqueue(text("third"))
        val original = queue.state.value.messages

        assertTrue(queue.move(original[1].id, original[0].id))
        assertTrue(queue.move(original[0].id, original[2].id))

        assertEquals(listOf(original[1], original[2], original[0]), List(3) { queue.takeMessage()!! })
        assertFalse(reply.isCompleted)
    }

    @Test
    fun `sorting holds automatic dispatch without pausing or failing voice messages`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("first"), reply = reply)
        queue.enqueue(text("second"))
        val scheduled = queue.state.value.messages.first()
        val next = queue.state.value.messages.last()

        queue.setReordering(true)
        assertNull(queue.takeMessage(scheduled.id))
        assertTrue(queue.move(next.id, scheduled.id))
        assertNull(queue.takeMessage())
        assertFalse(queue.state.value.paused)
        assertFalse(reply.isCompleted)
        queue.setReordering(false)

        assertNull(queue.takeMessage(scheduled.id))
        assertEquals(next, queue.takeMessage())
        assertEquals(scheduled, queue.takeMessage())
    }

    @Test
    fun `finishing sorting cannot resume a queue paused by the user`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val previous = queue.state.value.messages.first()
        val next = queue.state.value.messages.last()
        queue.pause()

        queue.setReordering(true)
        assertTrue(queue.move(next.id, previous.id))
        queue.setReordering(false)

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeMessage())
        queue.resume()
        assertEquals(next, queue.takeMessage())
    }

    @Test
    fun `stale source target and same item moves cannot lose or recreate messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("removed"))
        val removed = queue.takeMessage()!!
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
        val queue = ConversationInbox()
        queue.enqueue(text("editing"))
        queue.enqueue(text("second"))
        val original = queue.state.value.messages
        queue.beginEdit(original.first().id)

        assertFalse(queue.move(original.first().id, original.last().id))
        assertFalse(queue.move(original.last().id, original.first().id))
        assertNull(queue.takeMessage())
        queue.finishEdit(original.first().id, text("edited"))
        assertTrue(queue.move(original.last().id, original.first().id))

        assertEquals(original.last(), queue.takeMessage())
        assertEquals(text("edited"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `sorting an empty or single message queue cannot hold future input`() {
        val queue = ConversationInbox()
        queue.setReordering(true)
        queue.enqueue(text("only"))
        queue.setReordering(true)

        assertFalse(queue.state.value.reordering)
        assertEquals(text("only"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `dragging across multiple rows preserves the relative order of other messages`() {
        val queue = ConversationInbox()
        listOf("first", "second", "third", "fourth").forEach { queue.enqueue(text(it)) }
        val original = queue.state.value.messages

        assertTrue(queue.move(original[0].id, original[3].id))
        assertEquals(listOf(original[1], original[2], original[3], original[0]), queue.state.value.messages)
        assertTrue(queue.move(original[0].id, original[1].id))
        assertEquals(original, queue.state.value.messages)
    }

    @Test
    fun `a long drag cannot cross an editing placeholder`() {
        val queue = ConversationInbox()
        listOf("first", "editing", "third").forEach { queue.enqueue(text(it)) }
        val original = queue.state.value.messages
        queue.beginEdit(original[1].id)

        assertFalse(queue.move(original[0].id, original[2].id))
        assertFalse(queue.move(original[2].id, original[0].id))
        assertEquals(original.map { it.id }, queue.state.value.messages.map { it.id })
    }

    @Test
    fun `taking an interjection preserves attachments answer mode and voice observer`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        val parts = text("interject") + UIMessagePart.Image("file:///reference.png")
        queue.enqueue(text("first"))
        queue.enqueue(parts, answer = false, reply = reply)
        queue.enqueue(text("last"))
        val selected = queue.state.value.messages[1]

        assertEquals(selected, queue.take(selected.id))
        assertFalse(reply.isCompleted)
        assertEquals(text("first"), queue.takeMessage()!!.parts)
        assertEquals(text("last"), queue.takeMessage()!!.parts)
        assertNull(queue.take(selected.id))
    }

    @Test
    fun `explicit interjection does not resume other paused messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("interject"))
        queue.pause()
        val selected = queue.state.value.messages.last()

        assertEquals(selected, queue.take(selected.id))
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeMessage())
        queue.resume()
        assertEquals(text("first"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `interjection cannot take a message being edited`() {
        val queue = ConversationInbox()
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
        val queue = ConversationInbox()
        queue.enqueue(text("without answer"), answer = false)
        queue.enqueue(text("with answer"))
        val scheduled = queue.state.value.messages.first()
        queue.remove(scheduled.id)

        assertNull(queue.takeMessage(scheduled.id))
        val next = queue.takeMessage()!!
        assertEquals(text("with answer"), next.parts)
        assertTrue(next.answer)
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeMessage())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeMessage()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeMessage()!!.parts)
        val second = queue.takeMessage()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeMessage())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeMessage())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeMessage()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeMessage()!!.parts)
        assertNull(queue.takeMessage())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = ConversationInbox()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeMessage()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeMessage()!!.parts)
        assertNull(queue.takeMessage())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeMessage())
        queue.resume()
        assertEquals(text("edited"), queue.takeMessage()!!.parts)
        assertEquals(text("second"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        val id = queue.takeMessage()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeMessage())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeMessage()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = ConversationInbox()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeMessage()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = ConversationInbox()
        val second = ConversationInbox()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeMessage())
        assertEquals(text("second"), second.takeMessage()!!.parts)
    }
}
