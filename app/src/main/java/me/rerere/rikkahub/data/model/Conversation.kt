package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    val parentConversationId: Uuid? = null,
    // Legacy sidecar checkpoints retained for migration from older app versions.
    val compressionSummaries: List<CompressionSummary> = emptyList(),
    val modelOverrideId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    internal fun withoutHistory(): Conversation = copy(
        messageNodes = emptyList(),
        chatSuggestions = emptyList(),
        compressionSummaries = emptyList(),
    )

    /** Reads a checkpoint sidecar written by older app versions. */
    fun activeCompression(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryNodeId = checkpoint.boundaryNodeId ?: return null
        if (messageNodes.none { it.id == boundaryNodeId }) return null
        val expectedFingerprint = checkpoint.sourceFingerprint ?: return checkpoint
        return checkpoint.takeIf { compressionSourceFingerprint(boundaryNodeId) == expectedFingerprint }
    }

    /** A request may use only legacy sidecar checkpoints whose source can still be verified. */
    fun activeCompressionForRequest(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryNodeId = checkpoint.boundaryNodeId ?: return null
        val expectedFingerprint = checkpoint.sourceFingerprint ?: return null
        val boundaryIndex = messageNodes.indexOfFirst { it.id == boundaryNodeId }
        val latestMessageCheckpointIndex = messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        return checkpoint.takeIf {
            boundaryIndex >= latestMessageCheckpointIndex && boundaryIndex >= 0 &&
                compressionSourceFingerprint(boundaryNodeId) == expectedFingerprint
        }
    }

    /** Hashes the source history, including branch selection, alternates, and attachment references. */
    fun compressionSourceFingerprint(boundaryNodeId: Uuid): String? {
        val boundaryIndex = messageNodes.indexOfFirst { it.id == boundaryNodeId }
        if (boundaryIndex < 0) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val delimiter = byteArrayOf(0)
        fun update(value: String) {
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(delimiter)
        }
        for (index in 0..boundaryIndex) {
            val node = messageNodes[index]
            update(node.id.toString())
            update(node.selectIndex.toString())
            node.messages.forEach { message -> update(message.toString()) }
        }
        val hash = digest.digest()
        val hex = "0123456789abcdef"
        return buildString(hash.size * 2) {
            hash.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]).append(hex[value and 0x0f])
            }
        }
    }

    /** Replaces older summaries with one canonical, visible checkpoint while retaining source messages. */
    internal fun withContextCheckpoint(afterNodeId: Uuid, summary: String): Conversation? {
        val boundaryIndex = messageNodes.indexOfFirst { it.id == afterNodeId }
        if (boundaryIndex < 0) return null

        val retainedNodes = messageNodes.filterNot { it.currentMessage.isContextCheckpoint }
        val retainedBoundaryIndex = retainedNodes.indexOfFirst { it.id == afterNodeId }
        val insertIndex = if (retainedBoundaryIndex >= 0) {
            retainedBoundaryIndex + 1
        } else {
            messageNodes.take(boundaryIndex).count { !it.currentMessage.isContextCheckpoint }
        }
        val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
        val updatedNodes = retainedNodes.toMutableList().apply { add(insertIndex, checkpoint) }
        return copy(
            messageNodes = updatedNodes,
            compressionSummaries = emptyList(),
        )
    }

    /** Converts a verified checkpoint from the legacy sidecar column to the canonical message form. */
    internal fun migrateLegacyCompressionCheckpoint(): Conversation {
        val legacyCheckpoint = activeCompressionForRequest()
        val boundaryNodeId = legacyCheckpoint?.boundaryNodeId
        return if (legacyCheckpoint != null && boundaryNodeId != null) {
            withContextCheckpoint(boundaryNodeId, legacyCheckpoint.content)
                ?: withOnlyLatestContextCheckpoint()
        } else {
            withOnlyLatestContextCheckpoint()
        }
    }

    private fun withOnlyLatestContextCheckpoint(): Conversation {
        val latestCheckpointIndex = messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        return copy(
            messageNodes = if (latestCheckpointIndex < 0) {
                messageNodes
            } else {
                messageNodes.filterIndexed { index, node ->
                    index >= latestCheckpointIndex || !node.currentMessage.isContextCheckpoint
                }
            },
            compressionSummaries = emptyList(),
        )
    }

    fun requestWindowMessages(): List<UIMessage> = requestContextForGeneration().messages

    /** Compatibility alias for callers that also need streamed-message source mapping. */
    internal fun requestWindowForGeneration(messageRange: ClosedRange<Int>? = null): ConversationRequestWindow =
        requestContextForGeneration(messageRange)

    /** Keep older messages visible in the chat while excluding them from this model request. */
    internal fun requestContextFromNode(startIndex: Int): ConversationRequestWindow {
        require(startIndex in 0..messageNodes.size)
        val indexes = (startIndex until messageNodes.size).toList()
        return ConversationRequestWindow(
            messages = indexes.map { messageNodes[it].currentMessage },
            sourceNodeIndexes = indexes,
            appendNodeIndex = messageNodes.size,
        )
    }

    /** Builds a model request window from the latest checkpoint while keeping older history persisted. */
    internal fun requestContextForGeneration(messageRange: ClosedRange<Int>? = null): ConversationRequestWindow {
        val checkpoint = activeCompressionForRequest()
        val compressionBoundaryIndex = checkpoint?.boundaryNodeId?.let { boundaryId ->
            messageNodes.indexOfFirst { it.id == boundaryId }.takeIf { it >= 0 }
        }
        val messageCheckpointIndex = messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        val rangeEnd = messageRange?.endInclusive
        val applicableCompressionBoundary = compressionBoundaryIndex?.takeIf { boundaryIndex ->
            rangeEnd == null || rangeEnd >= boundaryIndex
        }
        val shouldUseMessageCheckpoint = messageCheckpointIndex >= 0 &&
            (rangeEnd == null || rangeEnd >= messageCheckpointIndex) &&
            (applicableCompressionBoundary == null || messageCheckpointIndex > applicableCompressionBoundary)
        val firstWindowIndex = when {
            applicableCompressionBoundary != null -> applicableCompressionBoundary + 1
            shouldUseMessageCheckpoint -> messageCheckpointIndex
            else -> 0
        }
        val messages = mutableListOf<UIMessage>()
        val nodeIndexes = mutableListOf<Int?>()

        messageNodes.forEachIndexed { index, node ->
            val belongsToWindow = index >= firstWindowIndex
            val checkpointMessage = shouldUseMessageCheckpoint && index == messageCheckpointIndex
            if (belongsToWindow && (messageRange == null || index in messageRange || checkpointMessage)) {
                messages += node.messages[node.selectIndex]
                nodeIndexes += index
            }
        }

        val appendNodeIndex = if (messageRange == null) {
            messageNodes.size
        } else {
            (messageRange.endInclusive.toLong() + 1L)
                .coerceIn(0L, messageNodes.size.toLong())
                .toInt()
        }
        return ConversationRequestWindow(
            messages = messages,
            sourceNodeIndexes = nodeIndexes,
            appendNodeIndex = appendNodeIndex,
            checkpointContent = checkpoint?.content?.takeIf { applicableCompressionBoundary != null },
        )
    }

    /** Applies streamed request-window messages without persisting synthetic checkpoint messages. */
    internal fun updateRequestWindowMessages(
        requestWindow: ConversationRequestWindow,
        messages: List<UIMessage>,
    ): Conversation {
        val newNodes = messageNodes.toMutableList()
        messages.forEachIndexed { responseIndex, message ->
            val nodeIndex = if (responseIndex < requestWindow.sourceNodeIndexes.size) {
                requestWindow.sourceNodeIndexes[responseIndex] ?: return@forEachIndexed
            } else {
                requestWindow.appendNodeIndex + responseIndex - requestWindow.sourceNodeIndexes.size
            }

            val node = newNodes.getOrNull(nodeIndex)
            if (node == null) {
                if (nodeIndex == newNodes.size) newNodes += message.toMessageNode()
                return@forEachIndexed
            }

            val updatedMessages = node.messages.toMutableList()
            val existingIndex = updatedMessages.indexOfFirst { it.id == message.id }
            val selectedIndex = if (existingIndex >= 0) {
                updatedMessages[existingIndex] = message
                node.selectIndex
            } else {
                updatedMessages += message
                updatedMessages.lastIndex
            }
            newNodes[nodeIndex] = node.copy(messages = updatedMessages, selectIndex = selectedIndex)
        }
        return copy(messageNodes = newNodes)
    }

    /** Returns the active context window, including its single canonical checkpoint message. */
    fun windowNodes(): List<MessageNode> {
        val checkpoint = activeCompressionForRequest()
        if (checkpoint != null) {
            val boundaryIndex = messageNodes.indexOfFirst { it.id == checkpoint.boundaryNodeId }
            if (boundaryIndex >= 0) return messageNodes.drop(boundaryIndex + 1)
        }
        val messageCheckpointIndex = messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        return if (messageCheckpointIndex >= 0) messageNodes.drop(messageCheckpointIndex) else messageNodes
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    /**
     * 写入一条消息：[nodeId] 节点里已有同 id 的消息就原地替换（不改变选中的分支），否则作为新分支加入并选中；
     * 节点不存在时追加到末尾。按节点 id 而不是下标定位，生成期间别的节点被增删、分支被切换，回复也不会写错位置。
     */
    fun upsertMessage(nodeId: Uuid, message: UIMessage): Conversation {
        // 生成中的回复几乎总在最后一个节点
        val index = messageNodes.indexOfLast { it.id == nodeId }
        if (index == -1) {
            return copy(messageNodes = messageNodes + MessageNode(id = nodeId, messages = listOf(message)))
        }
        val node = messageNodes[index]
        val existing = node.messages.indexOfFirst { it.id == message.id }
        val updated = if (existing == -1) {
            node.copy(messages = node.messages + message, selectIndex = node.messages.size)
        } else {
            node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
        }
        return copy(messageNodes = messageNodes.toMutableList().also { it[index] = updated })
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

internal class ConversationRequestWindow(
    val messages: List<UIMessage>,
    internal val sourceNodeIndexes: List<Int?>,
    internal val appendNodeIndex: Int,
    val checkpointContent: String? = null,
)

@Serializable
data class CompressionSummary(
    val id: Uuid = Uuid.random(),
    val content: String = "",
    val messageCount: Int = 0,
    val boundaryNodeId: Uuid? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    /** Null identifies a legacy checkpoint whose source cannot be verified for requests. */
    val sourceFingerprint: String? = null,
)

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

/**
 * 最近一次回复结束时的上下文占用（token），取不到时返回 null。
 * 之后又插入了压缩检查点的话，记录的占用已经过时，同样返回 null。
 */
fun Conversation.lastContextTokens(): Int? {
    val messages = currentMessages
    val message = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    val checkpoint = messages.lastOrNull { it.isContextCheckpoint }
    if (checkpoint != null && checkpoint.createdAt > message.createdAt) return null
    // 旧消息没有 contextTokens，那时的 usage 只保留最后一步，可以直接用
    return message.contextTokens ?: message.usage?.let { it.promptTokens + it.completionTokens }
}

/** 在 [afterNodeId] 节点之后插入压缩检查点；该节点已不存在时返回 null。 */
internal fun Conversation.insertContextCheckpoint(afterNodeId: Uuid, summary: String): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == afterNodeId }
    if (index == -1) return null
    val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
    return copy(
        messageNodes = messageNodes.subList(0, index + 1) + checkpoint + messageNodes.subList(index + 1, messageNodes.size),
    )
}

/** 有工具调用在等待用户审批：这一轮还没结束，排队的消息要等审批完成后再发 */
internal val Conversation.isAwaitingToolApproval: Boolean
    get() = currentMessages.any { message -> message.getTools().any { it.isPending } }

/**
 * 去掉还有未执行、又没法继续执行的工具调用的消息（比如还在等待审批），这样的消息发给模型会被拒绝。
 * 已经批准、拒绝或回答过的工具调用之后会继续执行，保留。只处理下标小于 [untilNode] 的节点。
 */
internal fun Conversation.withoutUnresumableToolCalls(untilNode: Int = messageNodes.size): Conversation {
    val nodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index >= untilNode) return@mapIndexedNotNull node
        val unexecuted = node.currentMessage.getTools().filter { !it.isExecuted }
        if (unexecuted.isEmpty() || unexecuted.any { it.canResumeExecution }) return@mapIndexedNotNull node
        val messages = node.messages.filter { it.id != node.currentMessage.id }
        if (messages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = messages, selectIndex = (node.selectIndex - 1).coerceIn(messages.indices))
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

/** 替换本地附件的地址，覆盖范围与 [localFileUrls] 一致，包含工具结果中的嵌套附件。 */
internal fun UIMessagePart.mapLocalFileUrls(transform: (String) -> String): UIMessagePart {
    fun String.mapped() = if (startsWith("file://")) transform(this) else this
    return when (this) {
        is UIMessagePart.Image -> copy(url = url.mapped())
        is UIMessagePart.Document -> copy(url = url.mapped())
        is UIMessagePart.Video -> copy(url = url.mapped())
        is UIMessagePart.Audio -> copy(url = url.mapped())
        is UIMessagePart.Tool -> copy(output = output.map { it.mapLocalFileUrls(transform) })
        else -> this
    }
}
