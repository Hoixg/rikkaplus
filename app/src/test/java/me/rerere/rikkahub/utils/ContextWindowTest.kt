package me.rerere.rikkahub.utils

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.prompts.isCompactionCheckpoint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.CompressionSummary
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDateTime
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

class ContextWindowTest {
    private fun message(text: String, role: MessageRole = MessageRole.USER): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun node(vararg messages: UIMessage): MessageNode =
        MessageNode(messages = messages.toList())

    @Test
    fun contextGaugeUsesLatestRequestInsteadOfAccumulatedToolUsage() {
        val model = Model(modelId = "tool-model", contextLength = 1_000)
        val reply = message("done", MessageRole.ASSISTANT).copy(
            modelId = model.id,
            contextTokens = 700,
            usage = TokenUsage(promptTokens = 2_000, completionTokens = 300),
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node(reply)))
        assertEquals(700, conversation.estimateWindowTokens(model))
        assertFalse(Settings(enableAutoCompaction = false).contextCompactionPolicy(model).shouldCompact(conversation.estimateWindowTokens(model)))
    }

    @Test
    fun contextLengthInputParsing() {
        assertEquals(4096, parseContextLengthInput("4096"))
        assertEquals(256_000, parseContextLengthInput("256k"))
        assertEquals(1_000_000, parseContextLengthInput(" 1M "))
        assertEquals(1_500_000, parseContextLengthInput("1.5m"))
        assertNull(parseContextLengthInput("abc"))
        assertNull(parseContextLengthInput("0"))
        assertNull(parseContextLengthInput("100000001"))
    }

    @Test
    fun contextLengthFormatting() {
        assertEquals("", formatContextLength(null))
        assertEquals("4.1K", formatContextLength(4096))
        assertEquals("256K", formatContextLength(256_000))
        assertEquals("1M", formatContextLength(1_000_000))
        assertEquals("1.5M", formatContextLength(1_500_000))
    }

    @Test
    fun effectiveWindowPrefersUserConfiguredThenModelDefaultThenRegistryFallback() {
        val modelId = "context-indicator-model"
        assertEquals(8_192, Model(modelId = modelId, contextLength = 8_192).effectiveContextLength())
        assertEquals(1_000_000, Model(modelId = modelId).effectiveContextLength())
        assertEquals(262_144, Model(modelId = modelId, contextLength = null).effectiveContextLength())
        assertEquals(262_144, null.effectiveContextLength())
    }

    @Test
    fun usageRingAndCompactionUseTheModelWindowWhenTheGlobalCapIsOff() {
        val settings = Settings(autoCompactionTokenLimit = 10_000, enableAutoCompactionTokenLimit = false)
        for (window in listOf(32_000, 128_000, 200_000, 1_000_000)) {
            val policy = settings.contextCompactionPolicy(Model(contextLength = window))
            assertEquals(window, policy.capacityTokens)
            assertEquals(window * 80 / 100, policy.triggerTokens)
            assertFalse(policy.usesGlobalLimit)
        }
        assertEquals(262_144, settings.contextCompactionPolicy(null).capacityTokens)
    }

    @Test
    fun contextUsageProgressUsesDisplayCapacityAndClampsOverflow() {
        val capacity = Settings(enableAutoCompactionTokenLimit = true, autoCompactionTokenLimit = 100_000)
            .contextCompactionPolicy(Model(contextLength = 200_000)).capacityTokens
        assertEquals(0f, contextUsageFraction(0, capacity), 0f)
        assertEquals(0.5f, contextUsageFraction(50_000, capacity), 0f)
        assertEquals(1f, contextUsageFraction(100_000, capacity), 0f)
        assertEquals(1f, contextUsageFraction(120_000, capacity), 0f)
        assertEquals("120K", formatContextLength(120_000))
        assertEquals(0f, contextUsageFraction(-1, capacity), 0f)
        assertEquals(0f, contextUsageFraction(1, 0), 0f)
    }

    @Test
    fun enabledGlobalCapOverridesBothSmallerAndLargerModelWindows() {
        val settings = Settings(enableAutoCompactionTokenLimit = true, autoCompactionTokenLimit = 100_000,
            enableAutoCompaction = false)
        for (window in listOf(32_000, 1_000_000)) {
            val model = Model(contextLength = window)
            val policy = settings.contextCompactionPolicy(model)
            assertEquals(100_000, policy.capacityTokens)
            assertEquals(100_000, policy.triggerTokens)
            assertFalse(policy.shouldCompact(99_999))
            assertTrue(policy.shouldCompact(100_000))
            assertTrue(policy.usesGlobalLimit)
            assertEquals(window, settings.copy(enableAutoCompactionTokenLimit = false)
                .contextCompactionPolicy(model).capacityTokens)
        }
    }

    @Test
    fun modelWindowCompactionStillRunsWhenGlobalAutomaticCompactionIsDisabled() {
        val settings = Settings(enableAutoCompaction = false, autoCompactionThresholdPercent = 50,
            autoCompactionTokenLimit = 1_000)
        val smaller = settings.contextCompactionPolicy(Model(contextLength = 128_000))
        assertFalse(smaller.shouldCompact(127_999))
        assertTrue(smaller.shouldCompact(128_000))
        assertEquals(83_200, smaller.targetTokens)
        assertFalse(settings.contextCompactionPolicy(Model(contextLength = 200_000)).shouldCompact(128_000))
    }

    @Test
    fun globalAutomaticCompactionAppliesThePercentageToTheActiveCapacity() {
        val model = Model(contextLength = 200_000)
        val settings = Settings(autoCompactionThresholdPercent = 90,
            autoCompactionTokenLimit = 100_000, enableAutoCompactionTokenLimit = true)
        val global = settings.contextCompactionPolicy(model)
        assertEquals(90_000, global.triggerTokens)
        assertFalse(global.shouldCompact(89_999))
        assertTrue(global.shouldCompact(90_000))
        val perModel = settings.copy(enableAutoCompactionTokenLimit = false).contextCompactionPolicy(model)
        assertEquals(180_000, perModel.triggerTokens)
        assertFalse(perModel.shouldCompact(90_000))
    }

    @Test
    fun autoCompactionTargetStaysBelowCustomThreshold() {
        val model = Model(contextLength = 1_000)
        assertEquals(640, Settings().contextCompactionPolicy(model).targetTokens)
        assertEquals(64, Settings(enableAutoCompactionTokenLimit = true, autoCompactionTokenLimit = 100)
            .contextCompactionPolicy(model).targetTokens)
        assertEquals(400, Settings(autoCompactionThresholdPercent = 50).contextCompactionPolicy(model).targetTokens)
    }

    @Test
    fun defaultAutoCompactionThresholdPreservesTheExistingContextWindowBehavior() {
        assertEquals(DEFAULT_AUTO_COMPACTION_THRESHOLD_PERCENT, Settings().autoCompactionThresholdPercent)
        assertNull(Settings().autoCompactionTokenLimit)
        val unknownModel: Model? = null
        assertEquals(262_144, unknownModel.effectiveContextLength())
        assertFalse(Settings().enableAutoCompactionTokenLimit)
        assertEquals(209_715, Settings().contextCompactionPolicy(unknownModel).triggerTokens)
    }

    @Test
    fun anEnabledButInvalidGlobalCapFallsBackToTheModelWindow() {
        for (cap in listOf(null, 0, MAX_AUTO_COMPACTION_TOKEN_LIMIT + 1)) {
            val policy = Settings(enableAutoCompactionTokenLimit = true, autoCompactionTokenLimit = cap)
                .contextCompactionPolicy(Model(contextLength = 128_000))
            assertEquals(128_000, policy.capacityTokens)
            assertFalse(policy.usesGlobalLimit)
        }
    }

    @Test
    fun autoCompactionSettingsAreNormalizedToSupportedRanges() {
        assertEquals(MIN_AUTO_COMPACTION_THRESHOLD_PERCENT, normalizeAutoCompactionThresholdPercent(0))
        assertEquals(85, normalizeAutoCompactionThresholdPercent(83))
        assertEquals(MAX_AUTO_COMPACTION_THRESHOLD_PERCENT, normalizeAutoCompactionThresholdPercent(100))
        assertNull(normalizeAutoCompactionTokenLimit(0))
        assertNull(normalizeAutoCompactionTokenLimit(MAX_AUTO_COMPACTION_TOKEN_LIMIT + 1))
    }

    @Test
    fun autoCompactionIsEnabledByDefaultButCanBeDisabled() {
        assertTrue(Settings().enableAutoCompaction)
        assertEquals(80, Settings().autoCompactionThresholdPercent)
        assertNull(Settings().autoCompactionTokenLimit)
        assertFalse(Settings(enableAutoCompaction = false).enableAutoCompaction)
    }

    @Test
    fun requestWindowUsesCheckpointBoundary() {
        val first = node(message("old"), message("old-selected"))
        val second = node(message("new", MessageRole.ASSISTANT))
        val initial = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(first, second),
        )
        val conversation = initial.copy(
            compressionSummaries = listOf(CompressionSummary(
                content = "summary",
                boundaryNodeId = first.id,
                sourceFingerprint = initial.compressionSourceFingerprint(first.id),
            )),
        )

        val requestContext = conversation.requestContextForGeneration()
        val window = requestContext.messages

        assertEquals("summary", requestContext.checkpointContent)
        assertEquals(listOf(second.currentMessage), window)
        assertFalse(window.any { it.isSynthetic || it.isCompactionCheckpoint() })
        assertEquals("new", (window.last().parts.single() as UIMessagePart.Text).text)
        assertEquals(second.currentMessage, window.last())
    }

    @Test
    fun streamedRequestWindowUpdatePreservesCheckpointSourceAndAppendsReplyAtHistoryTail() {
        val first = node(message("old user"))
        val boundary = node(message("old assistant", MessageRole.ASSISTANT))
        val recent = node(message("recent user"))
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, boundary, recent))
        val checkpoint = CompressionSummary(
            content = "compressed prefix",
            boundaryNodeId = boundary.id,
            sourceFingerprint = initial.compressionSourceFingerprint(boundary.id),
        )
        val compacted = initial.copy(compressionSummaries = listOf(checkpoint))
        val requestWindow = compacted.requestWindowForGeneration()
        val reply = message("new assistant reply", MessageRole.ASSISTANT)

        val updated = compacted.updateRequestWindowMessages(
            requestWindow = requestWindow,
            messages = requestWindow.messages + reply,
        )

        assertEquals(listOf(first, boundary), updated.messageNodes.take(2))
        assertEquals(recent.currentMessage, updated.messageNodes[2].currentMessage)
        assertEquals(reply, updated.messageNodes[3].currentMessage)
        assertEquals(checkpoint, updated.activeCompression())
        val updatedRequestContext = updated.requestContextForGeneration()
        assertEquals("compressed prefix", updatedRequestContext.checkpointContent)
        assertEquals(listOf(recent.currentMessage, reply), updatedRequestContext.messages)
        assertFalse(updatedRequestContext.messages.any { it.isSynthetic || it.isCompactionCheckpoint() })
        assertEquals(listOf(recent, updated.messageNodes[3]), updated.windowNodes())
        assertEquals(listOf(recent), selectNodesForCompaction(updated.windowNodes(), keepBudgetTokens = 0))
    }

    @Test
    fun uncompressedRequestWindowUpdateKeepsExistingNodeAlignment() {
        val first = node(message("first"))
        val second = node(message("second", MessageRole.ASSISTANT))
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, second))
        val requestWindow = initial.requestWindowForGeneration()
        val reply = message("new reply", MessageRole.ASSISTANT)

        val updated = initial.updateRequestWindowMessages(requestWindow, requestWindow.messages + reply)

        assertEquals(listOf(first, second), updated.messageNodes.take(2))
        assertEquals(reply, updated.messageNodes[2].currentMessage)
    }

    @Test
    fun checkpointAwareAssistantRegenerationMapsReplyToOriginalNode() {
        val first = node(message("old user"))
        val boundary = node(message("old assistant", MessageRole.ASSISTANT))
        val recentUser = node(message("recent user"))
        val recentAssistant = node(message("recent assistant", MessageRole.ASSISTANT))
        val initial = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(first, boundary, recentUser, recentAssistant),
        )
        val checkpoint = CompressionSummary(
            content = "compressed prefix",
            boundaryNodeId = boundary.id,
            sourceFingerprint = initial.compressionSourceFingerprint(boundary.id),
        )
        val compacted = initial.copy(compressionSummaries = listOf(checkpoint))
        val requestWindow = compacted.requestWindowForGeneration(messageRange = 0..<3)
        val regenerated = message("regenerated assistant", MessageRole.ASSISTANT)

        val updated = compacted.updateRequestWindowMessages(
            requestWindow = requestWindow,
            messages = requestWindow.messages + regenerated,
        )

        assertEquals(listOf(first, boundary), updated.messageNodes.take(2))
        assertEquals(2, updated.messageNodes[3].messages.size)
        assertEquals(regenerated, updated.messageNodes[3].currentMessage)
        assertEquals(checkpoint, updated.activeCompression())
    }

    @Test
    fun regenerationBeforeCheckpointUsesRawPrefixAndInvalidatesStaleCheckpointAfterEdit() {
        val first = node(message("old user"))
        val boundary = node(message("old assistant", MessageRole.ASSISTANT))
        val recent = node(message("recent user"))
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, boundary, recent))
        val checkpoint = CompressionSummary(
            content = "compressed prefix",
            boundaryNodeId = boundary.id,
            sourceFingerprint = initial.compressionSourceFingerprint(boundary.id),
        )
        val compacted = initial.copy(compressionSummaries = listOf(checkpoint))
        val requestWindow = compacted.requestWindowForGeneration(messageRange = 0..<1)
        val regenerated = message("regenerated boundary", MessageRole.ASSISTANT)

        val updated = compacted.updateRequestWindowMessages(
            requestWindow = requestWindow,
            messages = requestWindow.messages + regenerated,
        )

        assertEquals(listOf(first.currentMessage), requestWindow.messages)
        assertFalse(requestWindow.messages.any { it.isCompactionCheckpoint() })
        assertEquals(regenerated, updated.messageNodes[1].currentMessage)
        assertNull(updated.activeCompression())
    }

    @Test
    fun requestWindowFallsBackWhenBoundaryIsMissing() {
        val first = node(message("old"))
        val second = node(message("new", MessageRole.ASSISTANT))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(first, second),
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = Uuid.random()),
            ),
        )

        assertTrue(conversation.activeCompression() == null)
        assertEquals(conversation.currentMessages, conversation.requestWindowMessages())
    }

    @Test
    fun laterAssistantUsageIsUsableAfterCheckpoint() {
        val checkpointAt = LocalDateTime(2026, 1, 1, 10, 0)
            .toInstant(TimeZone.currentSystemDefault())
            .toJavaInstant()
        val later = LocalDateTime(2026, 1, 1, 11, 0)
        val model = Model(modelId = "context-test-model")
        val boundary = node(message("old"))
        val initial = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(boundary),
        )
        val checkpoint = CompressionSummary(
            content = "summary",
            boundaryNodeId = boundary.id,
            createdAt = checkpointAt,
            sourceFingerprint = initial.compressionSourceFingerprint(boundary.id),
        )
        val usageMessage = message("new", MessageRole.ASSISTANT).copy(
            modelId = model.id,
            createdAt = later,
            finishedAt = later,
            usage = TokenUsage(promptTokens = 123, completionTokens = 7),
        )
        val usageConversation = initial.copy(
            messageNodes = listOf(boundary, node(usageMessage)),
            compressionSummaries = listOf(checkpoint),
        )

        assertEquals(checkpoint, usageConversation.activeCompressionForRequest())
        assertEquals(130, usageConversation.estimateWindowTokens(model))
    }

    @Test
    fun staleAssistantUsageFallsBackToLocalEstimate() {
        val checkpointAt = LocalDateTime(2026, 1, 1, 11, 0)
            .toInstant(TimeZone.currentSystemDefault())
            .toJavaInstant()
        val earlier = LocalDateTime(2026, 1, 1, 10, 0)
        val usageMessage = message("new", MessageRole.ASSISTANT).copy(
            createdAt = earlier,
            finishedAt = earlier,
            usage = TokenUsage(promptTokens = 10_000),
        )
        val staleNode = node(usageMessage)
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(staleNode),
            compressionSummaries = listOf(
                CompressionSummary(content = "summary", boundaryNodeId = staleNode.id, createdAt = checkpointAt),
            ),
        )

        assertEquals(estimateTokenCount(conversation.requestWindowMessages()), conversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }

    @Test
    fun assistantUsageFromAnotherModelFallsBackToLocalEstimate() {
        val usageMessage = message("new", MessageRole.ASSISTANT).copy(
            usage = TokenUsage(promptTokens = 10_000),
            modelId = Uuid.random(),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(node(usageMessage)),
        )

        assertEquals(estimateTokenCount(conversation.requestWindowMessages()), conversation.estimateWindowTokens(Model(modelId = "context-test-model")))
    }

    @Test
    fun pendingInputIsIncludedInThresholdCalculation() {
        val currentTokens = 75
        val pendingTokens = estimateTokenCount(listOf(message("x".repeat(100))))

        val policy = Settings().contextCompactionPolicy(Model(contextLength = 100))
        assertFalse(policy.shouldCompact(currentTokens))
        assertTrue(policy.shouldCompact(currentTokens + pendingTokens))
        assertTrue(
            estimateTokenCount(listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///image.png"))))) >= 1_024,
        )
    }

    @Test
    fun shortConversationCanCompactAtLeastOneNode() {
        val nodes = listOf(node(message("first")), node(message("last")))

        assertEquals(listOf(nodes.first()), selectNodesForCompaction(nodes, keepBudgetTokens = 0))
        assertEquals(listOf(nodes.first()), selectNodesForCompaction(nodes, keepBudgetTokens = Int.MAX_VALUE))
        assertEquals(1, selectNodesForCompaction(nodes, keepBudgetTokens = Int.MAX_VALUE).size)
        assertTrue(selectNodesForCompaction(listOf(nodes.first()), keepBudgetTokens = 0).isNotEmpty())
    }

    @Test
    fun completedTurnCompactsImmediatelyWithoutDiscardingRecentContextWhenItFits() {
        val old = listOf(node(message("old request")), node(message("old reply", MessageRole.ASSISTANT)))
        val recent = listOf(node(message("latest request")), node(message("latest reply", MessageRole.ASSISTANT)))
        val nodes = old + recent
        val recentTokens = recent.sumOf { estimateTokenCount(listOf(it.currentMessage)) }

        assertEquals(old, selectNodesForAutomaticCompaction(nodes, recentTokens, true))
        assertEquals(nodes, selectNodesForAutomaticCompaction(nodes, 0, true))
        assertEquals(emptyList<MessageNode>(), selectNodesForAutomaticCompaction(recent, 0, false))
        assertEquals(recent, selectNodesForAutomaticCompaction(recent, 0, true))
    }

    @Test
    fun preSendCompactionIncludesAnOversizedCompletedTurnButKeepsPendingToolsAndUnansweredInput() {
        val recent = listOf(node(message("request ".repeat(500))), node(message("reply ".repeat(500), MessageRole.ASSISTANT)))
        assertTrue(canCompactLatestCompletedTurn(recent))
        assertEquals(recent, selectNodesForAutomaticCompaction(recent, 0, canCompactLatestCompletedTurn(recent)))
        assertFalse(canCompactLatestCompletedTurn(recent + node(message("unanswered input"))))
        val pending = UIMessagePart.Tool("call", "tool", "{}", approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)
        for (tool in listOf(pending, pending.copy(approvalState = me.rerere.ai.ui.ToolApprovalState.Approved))) {
            val nodes = recent + node(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            assertFalse(canCompactLatestCompletedTurn(nodes))
        }
    }

    @Test
    fun checkpointAfterLatestCompletedTurnKeepsHistoryVisible() {
        val user = node(message("long request"))
        val assistant = node(message("long reply", MessageRole.ASSISTANT))
        val original = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user, assistant))

        val compacted = original.withContextCheckpoint(assistant.id, "concise summary")!!

        assertEquals(listOf(user, assistant), compacted.messageNodes.take(2))
        assertTrue(compacted.messageNodes.last().currentMessage.isContextCheckpoint)
        assertEquals(listOf(compacted.messageNodes.last().currentMessage), compacted.requestWindowMessages())
    }

    @Test
    fun compactionInputKeepsToolsAndAttachmentMetadataButNotLocalPaths() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Document("file:///private/report.pdf", "report.pdf", "application/pdf"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    input = "{\"path\":\"notes.txt\"}",
                    output = listOf(UIMessagePart.Text("file contents")),
                ),
            ),
        )

        val formatted = message.toCompactionText()

        assertTrue(formatted.contains("report.pdf"))
        assertTrue(formatted.contains("application/pdf"))
        assertTrue(formatted.contains("read_file"))
        assertTrue(formatted.contains("notes.txt"))
        assertTrue(formatted.contains("file contents"))
        assertFalse(formatted.contains("file:///private"))
    }

    @Test
    fun compactionChunksRespectEstimatedTokenBudgetAndPreserveContent() {
        val text = "中文内容和 latin text ".repeat(800)
        val chunks = chunkCompactionTexts(listOf(text), tokenBudget = 128)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { estimateTextTokenCount(it) <= 128 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun priorCheckpointIsAddedToOnlyTheFinalMergeContext() {
        val prior = "previous checkpoint"
        val context = priorCheckpointMergeContext(prior)
        val sourceChunks = chunkCompactionTexts(listOf("new message 1", "new message 2"), tokenBudget = 32)

        assertTrue(sourceChunks.none { it.contains(prior) })
        assertEquals(1, context.windowed(prior.length).count { it == prior })
        assertEquals("", priorCheckpointMergeContext("  "))
    }

    @Test
    fun oldCheckpointJsonWithoutFingerprintRemainsReadable() {
        val checkpoint = CompressionSummary(content = "legacy summary", boundaryNodeId = Uuid.random())
        val oldJson = JsonInstant.encodeToString(checkpoint)
            .replace(Regex(",\\\"sourceFingerprint\\\":null"), "")

        val decoded = JsonInstant.decodeFromString<CompressionSummary>(oldJson)

        assertEquals("legacy summary", decoded.content)
        assertNull(decoded.sourceFingerprint)
    }

    @Test
    fun sourceFingerprintDetectsBranchAndAttachmentChangesButAcceptsLegacyCheckpoint() {
        val first = node(
            message("selected").copy(parts = listOf(UIMessagePart.Text("selected"), UIMessagePart.Image("file:///one.png"))),
            message("alternate").copy(parts = listOf(UIMessagePart.Text("alternate"), UIMessagePart.Image("file:///two.png"))),
        )
        val later = node(message("new"))
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, later))
        val fingerprint = initial.compressionSourceFingerprint(first.id)!!
        val checkpoint = CompressionSummary(
            content = "summary",
            boundaryNodeId = first.id,
            sourceFingerprint = fingerprint,
        )
        val withCheckpoint = initial.copy(compressionSummaries = listOf(checkpoint))

        assertEquals(checkpoint, withCheckpoint.activeCompression())
        assertEquals(
            checkpoint,
            withCheckpoint.copy(messageNodes = listOf(first, later, node(message("appended")))).activeCompression(),
        )
        assertTrue(withCheckpoint.copy(messageNodes = listOf(first.copy(selectIndex = 1), later)).activeCompression() == null)
        val changedAlternate = first.copy(messages = first.messages + message("new branch"))
        assertTrue(withCheckpoint.copy(messageNodes = listOf(changedAlternate, later)).activeCompression() == null)
        val changedAttachment = first.copy(messages = first.messages.mapIndexed { index, item ->
            if (index == 0) item.copy(parts = listOf(UIMessagePart.Image("file:///changed.png"))) else item
        })
        assertTrue(withCheckpoint.copy(messageNodes = listOf(changedAttachment, later)).activeCompression() == null)

        val legacy = withCheckpoint.copy(compressionSummaries = listOf(checkpoint.copy(sourceFingerprint = null)))
        assertEquals("summary", legacy.activeCompression()?.content)
    }
}
