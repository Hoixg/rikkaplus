package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.contextCompactionPolicy
import me.rerere.rikkahub.utils.estimateTokenCount
import me.rerere.rikkahub.utils.estimateWindowTokens
import me.rerere.rikkahub.utils.selectNodesForAutomaticCompaction
import me.rerere.rikkahub.utils.estimateTextTokenCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryTest {
    @Test fun requestBudgetReservesTheTemplateAndOutputInsideTheModelWindow() {
        for (window in listOf(4_096, 8_192, 128_000, 200_000, 1_000_000)) {
            val budget = compactionRequestBudget(window, promptTokens = 700)
            assertTrue(budget.contentTokens >= 128)
            assertTrue(budget.contentTokens + budget.outputTokens + 700 + 256 <= window)
            assertTrue(budget.contentTokens <= 12_000)
        }
        assertTrue(compactionRequestBudget(8_192, 2_000).contentTokens < compactionRequestBudget(8_192, 700).contentTokens)
        assertThrows(IllegalArgumentException::class.java) { compactionRequestBudget(1_024, 700) }
    }

    @Test fun aSummaryAlreadyWithinBudgetIsNotExpandedByAnotherRequest() = runTest {
        val summary = "keep all conclusions"
        val result = reduceCompactionSummaries(listOf(summary), 512, 128) { _, _ ->
            error("An already compact summary must not be regenerated")
        }
        assertEquals(summary, result)
    }

    @Test fun preferredLengthDoesNotRejectASummaryThatFitsTheActualAvailableSpace() = runTest {
        val summary = "a".repeat(800) // 200 estimated tokens, longer than the preferred 128.
        val result = reduceCompactionSummaries(listOf(summary), 512, 128, maxTokens = 300) { _, _ ->
            error("A usable summary must not be rejected for missing the advisory target")
        }
        assertEquals(summary, result)
    }

    @Test fun aReducedSummaryMayExceedThePreferredLengthWhileFittingTheActualBudget() = runTest {
        var requests = 0
        val result = reduceCompactionSummaries(listOf("a".repeat(2_000)), 1_024, 128, maxTokens = 300) { _, _ ->
            requests++
            "b".repeat(800)
        }
        assertEquals(1, requests)
        assertEquals(200, estimateTextTokenCount(result))
    }

    @Test fun summaryBudgetReservesPendingInputAndRetainedMessagesWithoutRequiringHalfTheSourceLength() {
        val budget = compactionSummaryBudget(128, 650, 100, 200, 500)!!
        assertEquals(128, budget.preferredTokens)
        assertEquals(334, budget.maxTokens)
        assertTrue(budget.maxTokens > 500 / 2)
        val shortSource = compactionSummaryBudget(128, 650, 0, 3, 100)!!
        assertEquals(99, shortSource.maxTokens)
        assertEquals(99, shortSource.preferredTokens)
        assertNull(compactionSummaryBudget(128, 650, 500, 200, 1_000))
        assertNull(compactionSummaryBudget(128, 650, 0, 3, 1))
    }

    @Test fun compactPromptFitsAOneKWindowWithoutMandatoryEmptySections() {
        val prompt = compactSummaryPrompt("", 128, "中文")
        assertFalse(prompt.contains("# Primary Request and Intent"))
        assertTrue(prompt.contains("Omit empty sections"))
        val promptTokens = estimateTextTokenCount(prompt)
        val budget = compactionRequestBudget(1_000, promptTokens)
        assertTrue(budget.contentTokens >= 128)
        assertTrue(budget.contentTokens + promptTokens + budget.outputTokens + 256 <= 1_000)
    }

    @Test fun aShortConversationCanBeRewrittenWithoutRecreatingTheCheckpointTemplate() = runTest {
        val structured = "# Primary Request and Intent\n- 用户问候\n# Key Technical Concepts\n- (none)\n" +
            "# Files and Code\n- (none)\n# Errors and Fixes\n- (none)\n# Pending Jobs\n- (none)"
        val result = reduceCompactionSummaries(listOf(structured), 512, 12, maxTokens = 24) { content, target ->
            val prompt = compactSummaryPrompt(content, target, "中文")
            assertTrue(prompt.contains("For a short conversation, use one short sentence"))
            "用户问候，助手回应。"
        }
        assertEquals("用户问候，助手回应。", result)
        assertTrue(estimateTextTokenCount(result) <= 24)
    }

    @Test fun modelWindowCompactionAcceptsAFittingSummaryWithBothGlobalSwitchesOff() = runTest {
        val model = Model(contextLength = 1_000)
        val settings = Settings(enableAutoCompaction = false, enableAutoCompactionTokenLimit = false,
            autoCompactionTokenLimit = 256_000)
        val policy = settings.contextCompactionPolicy(model)
        val nodes = listOf(
            UIMessage.user("old request ".repeat(200)).toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old reply ".repeat(200)))).toMessageNode(),
            UIMessage.user("recent request ".repeat(10)).toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("recent reply ".repeat(10)))).toMessageNode(),
        )
        val conversation = Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = nodes)
        assertEquals(1_000, policy.triggerTokens)
        assertTrue(policy.shouldCompact(conversation.estimateWindowTokens(model)))
        val source = selectNodesForAutomaticCompaction(nodes, 300, true)
        val emptyCheckpoint = insertContextCheckpoint(conversation, source.last().id, "")!!
        val pendingTokens = 32
        val budget = compactionSummaryBudget(128, policy.targetTokens, pendingTokens,
            emptyCheckpoint.estimateWindowTokens(model), source.sumOf { estimateTokenCount(listOf(it.currentMessage)) })!!
        val summary = "x".repeat(800) // Previously rejected solely for exceeding the 128-token target.
        assertTrue(estimateTextTokenCount(summary) > budget.preferredTokens)
        val result = reduceCompactionSummaries(listOf(summary), 512, budget.preferredTokens, budget.maxTokens) { _, _ ->
            error("The model-window budget already fits")
        }
        val updated = insertContextCheckpoint(conversation, source.last().id, result)!!
        assertTrue(updated.estimateWindowTokens(model) + pendingTokens <= policy.targetTokens)
        assertEquals(nodes, updated.messageNodes.filterNot { it.currentMessage.isContextCheckpoint })
    }

    @Test fun anOversizedFinalSummaryIsReducedAgainWithASmallerTarget() = runTest {
        val requested = mutableListOf<Int>()
        val result = reduceCompactionSummaries(listOf("a".repeat(2_000)), 1_024, 128) { content, target ->
            assertTrue(estimateTextTokenCount(content) <= 1_024)
            requested += target
            if (requested.size == 1) "b".repeat(800) else "all critical conclusions"
        }
        assertEquals(listOf(128, 64), requested)
        assertEquals("all critical conclusions", result)
    }

    @Test fun multipleOversizedSummariesAreChunkedBeforeTheFinalMerge() = runTest {
        val inputs = mutableListOf<String>()
        val result = reduceCompactionSummaries(List(4) { "原始摘要".repeat(150) }, 128, 64) { content, _ ->
            inputs += content
            assertTrue(estimateTextTokenCount(content) <= 128)
            "保留关键结论"
        }
        assertTrue(inputs.size > 1)
        assertEquals("保留关键结论", result)
    }

    @Test fun aModelIgnoringTheTargetFailsAfterABoundedNumberOfRequests() = runTest {
        var requests = 0
        var failed = false
        try {
            reduceCompactionSummaries(listOf("a".repeat(800)), 512, 64) { _, _ ->
                requests++
                "a".repeat(800)
            }
        } catch (error: CompactionSummaryTooLargeException) {
            failed = true
            assertEquals(200, error.actualTokens)
            assertEquals(64, error.maxTokens)
        }
        assertTrue("An oversized checkpoint must not be accepted", failed)
        assertEquals(4, requests)
    }

    @Test fun cancellationStopsReductionImmediately() = runTest {
        var requests = 0
        try {
            reduceCompactionSummaries(listOf("a".repeat(800)), 512, 64) { _, _ ->
                requests++
                throw CancellationException("cancelled")
            }
            error("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, requests) }
    }
}
