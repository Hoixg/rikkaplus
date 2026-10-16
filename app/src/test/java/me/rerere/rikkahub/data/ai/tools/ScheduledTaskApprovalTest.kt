package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledTaskApprovalTest {
    private var executions = 0
    private val definition = Tool(SCHEDULED_TASK_TOOL_NAME, "",
        needsApproval = { scheduledAction(it) in scheduledApprovalActions },
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

    private suspend fun pending(action: String = "create") = prepareToolApproval(call(action), definition)

    @Test fun ordinaryPreparationPreservesSnapshotWithoutCreatingADeadline() = runTest {
        val pending = pending()
        assertTrue(pending.isPending)
        assertEquals(0, executions)
        assertNull(pending.metadata)
        val snapshot = scheduledApprovalSnapshot(pending)!!
        assertEquals("stable-request", snapshot.getValue("request_id").jsonPrimitive.content)
        assertNull(snapshot["expires_at"])
        assertNull(snapshot["timeout_ms"])
        val restored = Json.decodeFromString(UIMessagePart.Tool.serializer(), Json.encodeToString(UIMessagePart.Tool.serializer(), pending))
        assertEquals(pending, restored)
        assertEquals(snapshot, scheduledApprovalSnapshot(restored))
        assertEquals(restored, prepareToolApproval(restored, definition))
    }

    @Test fun allThreeActionsCanBeApprovedAfterMoreThanSixtySeconds() = runTest {
        for (action in listOf("create", "update", "run_now")) {
            val pending = pending(action)
            advanceTimeBy(86_400_000)
            val approved = applyToolApprovalDecision(pending, true)
            assertEquals(ToolApprovalState.Approved, approved.approvalState)
            assertEquals(pending.input, approved.input)
            executeToolWithApproval(approved, definition, approved.inputAsJson())
        }
        assertEquals(3, executions)
    }

    @Test fun waitingNeverApprovesOrExecutesTheRequest() = runTest {
        val pending = pending()
        advanceTimeBy(7 * 86_400_000L)
        assertTrue(pending.isPending)
        assertTrue(runCatching { executeToolWithApproval(pending, definition, pending.inputAsJson()) }.isFailure)
        assertEquals(0, executions)
    }

    @Test fun rejectedAndApprovedDecisionsCannotBeReversedByStaleClicks() = runTest {
        val pending = pending("update")
        val denied = applyToolApprovalDecision(pending, false, "用户拒绝")
        assertEquals(ToolApprovalState.Denied("用户拒绝"), denied.approvalState)
        assertEquals(denied, applyToolApprovalDecision(denied, true))
        assertTrue(runCatching { executeToolWithApproval(denied, definition, denied.inputAsJson()) }.isFailure)
        val approved = applyToolApprovalDecision(pending, true)
        assertEquals(approved, applyToolApprovalDecision(approved, false))
        assertEquals(0, executions)
    }

    @Test fun restoredPendingRequestIgnoresItsOldExpiredDeadline() = runTest {
        val pending = pending().let { call -> call.copy(metadata = buildJsonObject {
            put(SCHEDULED_APPROVAL_METADATA, buildJsonObject {
                scheduledApprovalSnapshot(call)!!.forEach { (key, value) -> put(key, value) }
                put("expires_at", 1L)
                put("timeout_ms", 60_000L)
            })
        }) }
        val restored = Json.decodeFromString(UIMessagePart.Tool.serializer(), Json.encodeToString(UIMessagePart.Tool.serializer(), pending))
        assertEquals(restored, prepareToolApproval(restored, definition))
        advanceTimeBy(86_400_000)
        val approved = applyToolApprovalDecision(restored, true)
        assertEquals(ToolApprovalState.Approved, approved.approvalState)
        executeToolWithApproval(approved, definition, approved.inputAsJson())
        assertEquals(1, executions)
    }

    @Test fun olderCardsCanStillReadTheirConfigurationFromMetadata() {
        val snapshot = buildJsonObject { put("after", buildJsonObject { put("name", "旧版任务") }) }
        val legacy = call().copy(approvalState = ToolApprovalState.Pending,
            metadata = buildJsonObject { put(SCHEDULED_APPROVAL_METADATA, snapshot) })
        assertEquals(snapshot, scheduledApprovalSnapshot(legacy))
    }

    @Test fun preparedArgumentsTakePriorityOverDuplicatedOlderCardMetadata() = runTest {
        val pending = pending()
        val stale = pending.copy(metadata = buildJsonObject {
            put(SCHEDULED_APPROVAL_METADATA, buildJsonObject { put("after", buildJsonObject { put("name", "Stale") }) })
        })
        assertEquals(scheduledApprovalSnapshot(pending), scheduledApprovalSnapshot(stale))
    }

    @Test fun otherToolsKeepOrdinaryApprovalAndNonWriteActionsDoNotUseTheTaskCard() = runTest {
        for (name in listOf("generate_image", "workspace_shell", "ask_user")) {
            val definition = Tool(name, "", needsApproval = { true }, execute = { emptyList() })
            val pending = prepareToolApproval(UIMessagePart.Tool("other", name, "{}"), definition)
            assertFalse(isScheduledApproval(pending))
            assertNull(scheduledApprovalSnapshot(pending))
            assertEquals(ToolApprovalState.Approved, applyToolApprovalDecision(pending, true).approvalState)
        }
        for (action in listOf("delete", "set_enabled", "cancel_run", "list", "get", "options", "history")) {
            assertFalse(isScheduledApproval(call(action)))
        }
    }

    @Test fun readActionsDoNotAcquireAnApprovalRequirement() = runTest {
        for (action in scheduledReadActions) {
            val prepared = prepareToolApproval(call(action), definition)
            assertEquals(ToolApprovalState.Auto, prepared.approvalState)
        }
    }

    @Test fun newHumanMessageIsRequiredAfterDenialBeforeAnotherWriteRequest() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Schedule a check")))
        val denied = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(call().copy(approvalState = ToolApprovalState.Denied("No"))))
        assertTrue(hasDeniedScheduledRequestInTurn(listOf(user, denied)))
        assertFalse(hasDeniedScheduledRequestInTurn(listOf(user, denied, user.copy())))
    }
}
