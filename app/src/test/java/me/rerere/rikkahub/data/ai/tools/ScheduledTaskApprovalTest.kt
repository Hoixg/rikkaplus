package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ScheduledTaskApprovalTest {
    private var now = 1_000_000L
    private var executions = 0
    private val definition = Tool(SCHEDULED_TASK_TOOL_NAME, "", needsApproval = { true },
        prepareArguments = { input -> buildJsonObject {
            put("action", input.jsonObject.getValue("action"))
            put(SCHEDULED_PREPARED_ARGUMENT, buildJsonObject {
                put("request_id", "stable-request")
                put("expected_revision", "revision-one")
                put("after", buildJsonObject { put("name", "Check weather"); put("prompt", "Report today's weather") })
            })
        } }, execute = { executions++; listOf(UIMessagePart.Text("done")) })
    private fun call(action: String = "create", id: String = "call") =
        UIMessagePart.Tool(id, SCHEDULED_TASK_TOOL_NAME, """{"action":"$action"}""")
    private suspend fun pending(action: String = "create", id: String = "call") =
        prepareScheduledTaskApproval(call(action, id), definition) { now }

    @Test fun preparationDoesNotExecuteAndSnapshotSurvivesSerialization() = runBlocking {
        val pending = pending()
        assertTrue(pending.isPending)
        assertEquals(0, executions)
        assertEquals(now + 30_000L, scheduledApprovalDeadline(pending))
        val restored = Json.decodeFromString(UIMessagePart.Tool.serializer(), Json.encodeToString(UIMessagePart.Tool.serializer(), pending))
        assertEquals(pending, restored)
        now += 10_000
        assertEquals(pending, prepareScheduledTaskApproval(restored, definition) { now })
        assertTrue(runCatching { executeToolWithApproval(pending, definition, pending.inputAsJson()) }.isFailure)
    }

    @Test fun approvalAt29SecondsCanExecuteAfterDeadline() = runBlocking {
        val pending = pending("run_now")
        now += 29_000
        val approved = decideScheduledTaskApproval(pending, true, now = now)
        assertEquals(ToolApprovalState.Approved, approved.approvalState)
        now += 2_000
        executeToolWithApproval(approved, definition, approved.inputAsJson())
        assertEquals(1, executions)
        assertEquals(approved, decideScheduledTaskApproval(approved, false, now = now))
    }

    @Test fun at30SecondsLateApprovalIsDeniedAndCannotExecute() = runBlocking {
        val pending = pending("update")
        now += 30_000
        val denied = decideScheduledTaskApproval(pending, true, now = now)
        assertEquals(ToolApprovalState.Denied(SCHEDULED_APPROVAL_TIMEOUT_REASON), denied.approvalState)
        assertEquals(denied, decideScheduledTaskApproval(denied, true, now = now))
        assertTrue(runCatching { executeToolWithApproval(denied, definition, denied.inputAsJson()) }.isFailure)
        assertEquals(0, executions)
    }

    @Test fun legacyPendingWithoutDeadlineAndBackwardClockFailClosed() = runBlocking {
        val legacy = call().copy(approvalState = ToolApprovalState.Pending)
        assertTrue(decideScheduledTaskApproval(legacy, true, now = now).approvalState is ToolApprovalState.Denied)
        assertEquals(legacy, prepareScheduledTaskApproval(legacy, definition) { now })
        val pending = pending()
        assertTrue(scheduledApprovalExpired(pending, now - 1))
    }

    @Test fun otherToolsAndNonTimedScheduledActionsKeepGenericApproval() = runBlocking {
        for (name in listOf("generate_image", "workspace_shell", "ask_user")) {
            val definition = Tool(name, "", needsApproval = { true }, execute = { emptyList() })
            val pending = prepareToolApproval(UIMessagePart.Tool("other", name, "{}"), definition)
            assertNull(scheduledApprovalDeadline(pending))
            assertFalse(isScheduledApproval(pending))
            assertEquals(pending, decideScheduledTaskApproval(pending, false, now = now + 99_000))
            assertEquals(ToolApprovalState.Approved, applyToolApprovalDecision(pending, true).approvalState)
        }
        for (action in listOf("delete", "set_enabled", "cancel_run", "list", "get", "options", "history")) {
            assertFalse(isScheduledApproval(call(action)))
        }
    }

    @Test fun coordinatorKeepsIndependentTimersAndDoesNotRestartOnPageUpdates() = runBlocking {
        val waits = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        val expired = mutableListOf<String>()
        var references = 0
        val coordinator = ScheduledTaskApprovalCoordinator(this, expire = { _, id -> expired += id },
            retain = { references++ }, release = { references-- }, clock = { now },
            wait = { duration -> CompletableDeferred<Unit>().also { waits.send(duration to it) }.await() })
        val first = pending(id = "first")
        coordinator.observe("chat", listOf(first))
        val firstWait = waits.receive()
        assertEquals(30_000L, firstWait.first)
        now += 10_000
        val second = pending(id = "second")
        coordinator.observe("chat", listOf(first.copy(), second))
        val secondWait = waits.receive()
        assertEquals(30_000L, secondWait.first)
        assertTrue(waits.tryReceive().isFailure)
        assertEquals(2, references)
        now += 20_000
        firstWait.second.complete(Unit)
        yield()
        assertEquals(listOf("first"), expired)
        coordinator.observe("chat", listOf(decideScheduledTaskApproval(second, true, now = now)))
        yield()
        assertEquals(0, references)
        assertEquals(listOf("first"), expired)
    }

    @Test fun restoredExpiredRequestTimesOutImmediatelyWhileOtherApprovalIsIgnored() = runBlocking {
        var waits = 0
        val expired = mutableListOf<String>()
        val pending = pending()
        now += 31_000
        val coordinator = ScheduledTaskApprovalCoordinator(this, { _, id -> expired += id }, clock = { now }, wait = { waits++ })
        coordinator.observe("chat", listOf(pending,
            UIMessagePart.Tool("other", "workspace_shell", "{}", approvalState = ToolApprovalState.Pending)))
        yield()
        assertEquals(listOf("call"), expired)
        assertEquals(0, waits)
    }

    @Test fun cancellingBeforeTimerStartsReleasesItsSessionReference() = runBlocking {
        var references = 0
        var expired = 0
        val pending = pending()
        val coordinator = ScheduledTaskApprovalCoordinator(this, { _, _ -> expired++ },
            retain = { references++ }, release = { references-- }, clock = { now })
        coordinator.observe("chat", listOf(pending))
        coordinator.observe("chat", listOf(decideScheduledTaskApproval(pending, true, now = now)))
        yield()
        assertEquals(0, references)
        assertEquals(0, expired)
    }

    @Test fun cleanupCancelsOnlyOwnedTimersAndReleasesReferences() = runBlocking {
        var references = 0
        var expired = 0
        val coordinator = ScheduledTaskApprovalCoordinator(this, { _, _ -> expired++ },
            retain = { references++ }, release = { references-- }, clock = { now })
        coordinator.observe("first-chat", listOf(pending(id = "first")))
        coordinator.observe("second-chat", listOf(pending(id = "second")))
        assertEquals(2, references)
        coordinator.cancelAll()
        yield()
        assertEquals(0, references)
        assertEquals(0, expired)
    }

    @Test fun newHumanMessageIsRequiredAfterDenialBeforeAnotherWriteRequest() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Schedule a check")))
        val denied = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(call().copy(approvalState = ToolApprovalState.Denied("No"))))
        assertTrue(hasDeniedScheduledRequestInTurn(listOf(user, denied)))
        assertFalse(hasDeniedScheduledRequestInTurn(listOf(user, denied, user.copy())))
    }
}
