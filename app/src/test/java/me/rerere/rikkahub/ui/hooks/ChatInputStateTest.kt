package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    private fun editing(parts: List<UIMessagePart>) = ChatInputState().apply {
        editingMessage = Uuid.random()
        setContents(parts)
    }

    @Test
    fun `cancelled queue input restores every text part and all attachment types`() {
        val state = ChatInputState()
        val attachments = listOf(
            UIMessagePart.Image("file:///image.png"),
            UIMessagePart.Document("file:///notes.md", "notes.md"),
            UIMessagePart.Audio("file:///audio.mp3"),
            UIMessagePart.Video("file:///video.mp4"),
        )
        assertTrue(
            state.restoreQueuedMessage(listOf(UIMessagePart.Text("first"), UIMessagePart.Text("second")) + attachments)
        )
        assertEquals(listOf(UIMessagePart.Text("first\nsecond")) + attachments, state.getContents())
        assertFalse(state.isEditing())
    }

    @Test
    fun `restoring queue input replaces the existing text draft and its attachments`() {
        val state = ChatInputState()
        val oldImage = UIMessagePart.Image("file:///draft.png")
        val restoredImage = UIMessagePart.Image("file:///queued.png")
        state.setContents(listOf(UIMessagePart.Text("unfinished draft"), oldImage))
        assertTrue(state.restoreQueuedMessage(listOf(UIMessagePart.Text("cancelled"), restoredImage)))
        assertEquals(
            listOf(UIMessagePart.Text("cancelled"), restoredImage),
            state.getContents(),
        )
    }

    @Test
    fun `attachment only cancellations remain attachment only and preserve borrowed files`() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///queued.png")
        state.setContents(listOf(UIMessagePart.Text("old draft"), UIMessagePart.Image("file:///draft.png")))
        assertTrue(state.restoreQueuedMessage(listOf(image)))
        assertEquals(listOf(image), state.getContents())
        assertFalse(state.shouldDeleteFileOnRemove(image))
        state.clearInput()
        state.setContents(listOf(image))
        assertTrue(state.shouldDeleteFileOnRemove(image))
    }

    @Test
    fun `successive cancellations replace the draft and protect only the latest restored files`() {
        val state = ChatInputState()
        val first = UIMessagePart.Image("file:///first.png")
        val second = UIMessagePart.Image("file:///second.png")
        state.restoreQueuedMessage(listOf(UIMessagePart.Text("first"), first))
        state.restoreQueuedMessage(listOf(UIMessagePart.Text("second"), second))
        assertEquals(listOf(UIMessagePart.Text("second"), second), state.getContents())
        assertTrue(state.shouldDeleteFileOnRemove(first))
        assertFalse(state.shouldDeleteFileOnRemove(second))
    }

    @Test
    fun `history editing cannot be overwritten by a queue cancellation`() {
        val parts = listOf(UIMessagePart.Text("editing history"))
        val state = editing(parts)
        val id = state.editingMessage
        assertFalse(state.restoreQueuedMessage(listOf(UIMessagePart.Text("pending"))))
        assertEquals(id, state.editingMessage)
        assertEquals(parts, state.getContents())
    }

    @Test
    fun `reasoning and text message allows editing reasoning in place`() {
        val reasoning = UIMessagePart.Reasoning("old thought")
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNotNull(state.editingReasoning)
        assertEquals(emptyList<UIMessagePart>(), state.messageContent)

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("new thought")
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(
            listOf(reasoning.copy(reasoning = "new thought"), UIMessagePart.Text("new answer")),
            state.getContents(),
        )
    }

    @Test
    fun `blank reasoning is dropped`() {
        val state = editing(listOf(UIMessagePart.Reasoning("old thought"), UIMessagePart.Text("answer")))

        state.editingReasoning!!.setTextAndPlaceCursorAtEnd("  ")

        assertEquals(listOf(UIMessagePart.Text("answer")), state.getContents())
    }

    @Test
    fun `signed reasoning is not editable and kept as is`() {
        val reasoning = UIMessagePart.Reasoning(
            reasoning = "thought",
            metadata = buildJsonObject { put("signature", JsonPrimitive("sig")) },
        )
        val state = editing(listOf(reasoning, UIMessagePart.Text("answer")))

        assertNull(state.editingReasoning)
        state.textContent.setTextAndPlaceCursorAtEnd("new answer")

        assertEquals(listOf(reasoning, UIMessagePart.Text("new answer")), state.getContents())
    }

    @Test
    fun `message with other parts is not editable`() {
        val state = editing(
            listOf(
                UIMessagePart.Reasoning("thought"),
                UIMessagePart.Text("answer"),
                UIMessagePart.Image("file:///image.png"),
            )
        )

        assertNull(state.editingReasoning)
    }

    @Test
    fun `clear input resets reasoning editing`() {
        val state = editing(listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("answer")))

        state.clearInput()

        assertNull(state.editingReasoning)
    }
}
