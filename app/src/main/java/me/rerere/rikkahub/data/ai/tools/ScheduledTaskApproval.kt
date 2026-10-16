package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole

// Read older saved cards without retaining their former approval deadlines.
internal const val SCHEDULED_APPROVAL_METADATA = "scheduled_task_approval"
internal const val SCHEDULED_PREPARED_ARGUMENT = "_scheduled_task_prepared"
internal val scheduledApprovalActions = setOf("create", "update", "run_now")
internal val scheduledReadActions = setOf("list", "get", "options", "history")

internal fun scheduledAction(input: JsonElement): String =
    (input as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

internal fun isScheduledApproval(call: UIMessagePart.Tool): Boolean =
    call.toolName == SCHEDULED_TASK_TOOL_NAME && runCatching {
        scheduledAction(call.inputAsJson()) in scheduledApprovalActions
    }.getOrDefault(false)

/** The normal approval preparation already stores the exact configuration to review and execute. */
internal fun scheduledApprovalSnapshot(call: UIMessagePart.Tool): JsonObject? {
    if (!isScheduledApproval(call)) return null
    return runCatching {
        call.inputAsJson().jsonObject[SCHEDULED_PREPARED_ARGUMENT] as? JsonObject
    }.getOrNull() ?: call.metadata?.get(SCHEDULED_APPROVAL_METADATA) as? JsonObject
}

internal fun hasDeniedScheduledRequestInTurn(messages: List<UIMessage>): Boolean =
    messages.drop(messages.indexOfLast { it.role == MessageRole.USER } + 1).any { message ->
        message.getTools().any { isScheduledApproval(it) && it.approvalState is ToolApprovalState.Denied }
    }
