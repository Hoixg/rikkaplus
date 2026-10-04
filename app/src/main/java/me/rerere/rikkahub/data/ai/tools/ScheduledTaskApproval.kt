package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.MessageRole

internal const val SCHEDULED_APPROVAL_METADATA = "scheduled_task_approval"
internal const val SCHEDULED_PREPARED_ARGUMENT = "_scheduled_task_prepared"
internal const val SCHEDULED_APPROVAL_TIMEOUT_MS = 30_000L
internal const val SCHEDULED_APPROVAL_TIMEOUT_REASON = "审批超时，未批准"
internal val scheduledApprovalActions = setOf("create", "update", "run_now")
internal val scheduledReadActions = setOf("list", "get", "options", "history")

internal fun scheduledAction(input: JsonElement): String =
    (input as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

internal fun isScheduledApproval(call: UIMessagePart.Tool): Boolean =
    call.toolName == SCHEDULED_TASK_TOOL_NAME && runCatching {
        scheduledAction(call.inputAsJson()) in scheduledApprovalActions
    }.getOrDefault(false)

internal fun scheduledApprovalMetadata(call: UIMessagePart.Tool): JsonObject? =
    call.metadata?.get(SCHEDULED_APPROVAL_METADATA) as? JsonObject

internal fun scheduledApprovalDeadline(call: UIMessagePart.Tool): Long? =
    scheduledApprovalMetadata(call)?.get("expires_at")?.jsonPrimitive?.longOrNull

internal fun scheduledApprovalExpired(call: UIMessagePart.Tool, now: Long): Boolean =
    isScheduledApproval(call) && call.isPending && (scheduledApprovalDeadline(call)?.let {
        now >= it || now < it - SCHEDULED_APPROVAL_TIMEOUT_MS
    } ?: true)

internal fun hasDeniedScheduledRequestInTurn(messages: List<UIMessage>): Boolean =
    messages.drop(messages.indexOfLast { it.role == MessageRole.USER } + 1).any { message ->
        message.getTools().any { isScheduledApproval(it) && it.approvalState is ToolApprovalState.Denied }
    }

/** Separate from generic approval: only scheduled task requests receive a deadline and snapshot. */
internal suspend fun prepareScheduledTaskApproval(
    call: UIMessagePart.Tool,
    definition: Tool?,
    now: () -> Long = System::currentTimeMillis,
): UIMessagePart.Tool {
    val prepared = prepareToolApproval(call, definition)
    if (!isScheduledApproval(prepared) || !prepared.isPending) return prepared
    if (call.approvalState != ToolApprovalState.Auto) return prepared // Never restart a restored deadline.
    val snapshot = prepared.inputAsJson().jsonObject[SCHEDULED_PREPARED_ARGUMENT] as? JsonObject
        ?: return prepared.copy(approvalState = ToolApprovalState.Denied("审批配置缺失，请重新请求"))
    return prepared.copy(metadata = buildJsonObject {
        prepared.metadata?.forEach { (key, value) -> put(key, value) }
        put(SCHEDULED_APPROVAL_METADATA, buildJsonObject {
            snapshot.forEach { (key, value) -> put(key, value) }
            put("expires_at", now() + SCHEDULED_APPROVAL_TIMEOUT_MS)
        })
    })
}

internal fun decideScheduledTaskApproval(
    call: UIMessagePart.Tool,
    approved: Boolean,
    reason: String = "",
    now: Long = System.currentTimeMillis(),
): UIMessagePart.Tool {
    if (!isScheduledApproval(call) || !call.isPending) return call
    return applyToolApprovalDecision(call, approved && !scheduledApprovalExpired(call, now),
        if (scheduledApprovalExpired(call, now)) SCHEDULED_APPROVAL_TIMEOUT_REASON else reason)
}

/** Owns timers independently of Compose and retains no timers for any other kind of approval. */
internal class ScheduledTaskApprovalCoordinator(
    private val scope: CoroutineScope,
    private val expire: suspend (String, String) -> Unit,
    private val retain: (String) -> Unit = {},
    private val release: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val timers = mutableMapOf<Pair<String, String>, Job>()

    @Synchronized
    fun cancelAll() {
        timers.values.toList().forEach { it.cancel() }
    }

    @Synchronized
    fun observe(conversationId: String, calls: List<UIMessagePart.Tool>) {
        val pending = calls.filter { it.isPending && isScheduledApproval(it) }.associateBy { it.toolCallId }
        timers.keys.filter { it.first == conversationId && it.second !in pending }.toList().forEach {
            timers.remove(it)?.cancel()
        }
        pending.values.forEach { call ->
            val key = conversationId to call.toolCallId
            if (key in timers) return@forEach
            retain(conversationId)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val remaining = if (scheduledApprovalExpired(call, clock())) 0L else
                    ((scheduledApprovalDeadline(call) ?: clock()) - clock()).coerceIn(0L, SCHEDULED_APPROVAL_TIMEOUT_MS)
                if (remaining > 0) wait(remaining)
                expire(conversationId, call.toolCallId)
            }
            timers[key] = job
            job.invokeOnCompletion {
                synchronized(this) { if (timers[key] === job) timers.remove(key) }
                release(conversationId) // Also runs when cancelled before the lazy coroutine starts.
            }
            job.start()
        }
    }
}
