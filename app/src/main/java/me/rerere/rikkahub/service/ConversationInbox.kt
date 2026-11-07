package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

/** 收件箱里等待发给模型的一项 */
sealed interface InboxItem {
    val id: Uuid
}

/** 用户输入：等当前这一轮结束后各自开一轮 */
data class QueuedMessage(
    override val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
) : InboxItem

/**
 * 运行时事件，例如后台命令结束：正在生成时在下一步请求模型前插进这一轮，空闲时单独开一轮。
 * [key] 标识事件来源，来源可以据此撤回已经不需要发送的通知。
 */
data class QueuedNotice(
    override val id: Uuid = Uuid.random(),
    val key: String,
    val notice: UIMessagePart.Notice,
) : InboxItem

data class InboxState(
    val items: List<InboxItem> = emptyList(),
    val paused: Boolean = false,
    val reordering: Boolean = false,
) {
    val messages: List<QueuedMessage> get() = items.filterIsInstance<QueuedMessage>()
    val notices: List<QueuedNotice> get() = items.filterIsInstance<QueuedNotice>()
}

/** 收件箱决定的下一轮 */
sealed interface InboxTurn {
    /** 发送这条用户输入；要回复时等待中的通知存成它之前的节点 */
    data class Message(val message: QueuedMessage) : InboxTurn

    /** 只有通知在等，单独开一轮；通知在开始时用 [ConversationInbox.takeNotices] 取 */
    data object Notices : InboxTurn
}

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
    draftAttachmentUrls: Set<String> = emptySet(),
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls() - draftAttachmentUrls
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

/**
 * 会话收件箱：等待发给模型的用户输入和运行时事件，进入会话历史之前都放在这里。
 *
 * 暂停只挡住开始新的一轮；正在进行的一轮仍然会在步骤之间取走通知。
 */
class ConversationInbox {
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            items = state.value.items + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
            ),
        )
    }

    /** 放入一条通知；同一来源已有没发出的通知时原地替换 */
    @Synchronized
    fun post(key: String, notice: UIMessagePart.Notice) {
        val items = state.value.items
        val index = items.indexOfFirst { it is QueuedNotice && it.key == key }
        mutableState.value = state.value.copy(
            items = if (index == -1) {
                items + QueuedNotice(key = key, notice = notice)
            } else {
                items.toMutableList().apply { set(index, (get(index) as QueuedNotice).copy(notice = notice)) }
            },
        )
    }

    /** 撤回还没发出的通知；已经发出的不受影响 */
    @Synchronized
    fun withdraw(key: String) {
        mutableState.value = state.value.copy(
            items = state.value.items.filterNot { it is QueuedNotice && it.key == key },
        )
    }

    /**
     * 取出下一轮：先发用户输入，只剩通知时单独开一轮。
     * 正在编辑的队首挡住后面的用户输入，但不挡通知。
     */
    @Synchronized
    fun takeNext(): InboxTurn? {
        val current = state.value
        if (current.paused || current.reordering) return null
        val head = current.messages.firstOrNull()
        if (head != null && !head.isEditing) {
            mutableState.value = current.copy(items = current.items.filterNot { it.id == head.id })
            return InboxTurn.Message(head)
        }
        return InboxTurn.Notices.takeIf { current.notices.isNotEmpty() }
    }

    /** Claim only the expected pending input after interrupted generations finish saving. */
    @Synchronized
    fun takeMessage(expectedId: Uuid? = null): QueuedMessage? {
        val current = state.value
        if (current.paused || current.reordering) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        if (expectedId != null && next.id != expectedId) return null
        mutableState.value = current.copy(items = current.items.filterNot { it.id == next.id })
        return next
    }

    /** An explicit interjection may bypass the pending order and paused state. */
    @Synchronized
    fun take(id: Uuid): QueuedMessage? {
        val current = state.value
        val message = current.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = current.copy(items = current.items.filterNot { it.id == id })
        return message
    }

    @Synchronized
    fun setReordering(reordering: Boolean) {
        mutableState.value = state.value.copy(reordering = reordering && state.value.messages.size > 1)
    }

    /** Reorder user inputs while retaining notice slots and excluding messages being edited. */
    @Synchronized
    fun move(id: Uuid, targetId: Uuid): Boolean {
        val current = state.value
        val messages = current.messages
        val index = messages.indexOfFirst { it.id == id }
        val target = messages.indexOfFirst { it.id == targetId }
        if (index < 0 || target < 0 || index == target) return false
        if ((minOf(index, target)..maxOf(index, target)).any { messages[it].isEditing }) return false
        val reordered = messages.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        val iterator = reordered.iterator()
        mutableState.value =
            current.copy(items = current.items.map { if (it is QueuedMessage) iterator.next() else it })
        return true
    }

    /** Restore a draft before releasing ownership of its text and attachments. */
    @Synchronized
    fun cancel(id: Uuid, restoreDraft: (List<UIMessagePart>) -> Boolean): Boolean {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return false
        if (!restoreDraft(message.parts)) return false
        remove(id)
        return true
    }

    /** 取出全部通知，按放入顺序；不受暂停影响 */
    @Synchronized
    fun takeNotices(): List<UIMessagePart.Notice> {
        val current = state.value
        val notices = current.notices
        if (notices.isEmpty()) return emptyList()
        mutableState.value = current.copy(items = current.items.filterNot { it is QueuedNotice })
        return notices.map { it.notice }
    }

    @Synchronized
    fun remove(id: Uuid): InboxItem? {
        val removed = state.value.items.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(items = state.value.items.filterNot { it.id == id })
        (removed as? QueuedMessage)?.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            items = state.value.items.map { if (it.id == id) message.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            items = state.value.items.map {
                if (it.id == id) {
                    previous.copy(parts = parts?.toList() ?: previous.parts, isEditing = false)
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
