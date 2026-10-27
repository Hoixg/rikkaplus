package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
    val reordering: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
            ),
        )
    }

    @Synchronized
    fun takeNext(expectedId: Uuid? = null): QueuedMessage? {
        val current = state.value
        if (current.paused || current.reordering) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        if (expectedId != null && next.id != expectedId) return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    /** An explicit interjection may bypass the queue order and its paused state. */
    @Synchronized
    fun take(id: Uuid): QueuedMessage? {
        val current = state.value
        val message = current.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.filterNot { it.id == id })
        return message
    }

    @Synchronized
    fun setReordering(reordering: Boolean) {
        mutableState.value = state.value.copy(reordering = reordering && state.value.messages.size > 1)
    }

    /** Move to another pending item's position; both identities are resolved against the latest order. */
    @Synchronized
    fun move(id: Uuid, targetId: Uuid): Boolean {
        val current = state.value
        val index = current.messages.indexOfFirst { it.id == id }
        val target = current.messages.indexOfFirst { it.id == targetId }
        if (index < 0 || target < 0 || index == target) return false
        if ((minOf(index, target)..maxOf(index, target)).any { current.messages[it].isEditing }) return false
        val reordered = current.messages.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        mutableState.value = current.copy(messages = reordered)
        return true
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) {
                    it.copy(
                        parts = parts?.toList() ?: it.parts,
                        isEditing = false
                    )
                } else {
                    it
                }
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }
}
