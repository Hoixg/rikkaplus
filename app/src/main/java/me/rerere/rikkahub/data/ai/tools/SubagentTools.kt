package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

const val SPAWN_AGENT_TOOL_NAME = "spawn_agent"
const val FOLLOWUP_AGENT_TOOL_NAME = "followup_agent"
const val POLL_AGENT_TOOL_NAME = "poll_agent"
const val CANCEL_AGENT_TOOL_NAME = "cancel_agent"
const val LIST_AGENTS_TOOL_NAME = "list_agents"

/** 子代理会话失效（已删除/不存在/已过期回收）时返回的错误码 */
const val AGENT_SESSION_NOT_FOUND = "AGENT_SESSION_NOT_FOUND"

/**
 * 子代理最终回复的注入预算：超过即截断（显式标记）并把全文落盘 /tool_outputs，
 * 由父代理按需读取。老值 4K 会把父代理上下文顶爆（P0-3 根因），下调到 2K。
 */
const val SUBAGENT_RESULT_INLINE_CHARS = 2_000

/**
 * async=true 的 spawn 返回体（P0-2 b）：父代理立即拿到 taskId 继续其它工作，
 * 结果经 poll_agent 取回；完成事件随 poll 结果注入下一轮父对话。
 */
fun createSubagentTool(
    onSpawn: suspend (description: String, prompt: String, async: Boolean, timeoutMs: Long?, maxToolCalls: Int?) -> String,
): Tool = Tool(
    name = SPAWN_AGENT_TOOL_NAME,
    description = """
        Delegate a self-contained subtask to a fresh child agent (empty history; shares workspace, model and tools).
        Assign independent analysis, implementation, research or review work. Handle simple questions and single-step lookups directly.
        The prompt defines the task: include all relevant files, context, constraints and expected deliverables. Children cannot spawn children.
        By default this call BLOCKS until the child finishes and returns structured JSON: status (ok | empty_output | error | timeout), result, toolCalls, toolCallsDetail, startedAt/endedAt/durationMs, files, sessionId.
        With async=true it returns {status:"running", taskId} immediately; fetch the result later with poll_agent, and optionally cancel with cancel_agent. In async mode taskId equals the child's sessionId (they are the same id).
        All spawned agents (sync and async) stay listed by list_agents for about 30 minutes after they finish, so a lost result can be re-discovered there; pass its sessionId/taskId to followup_agent to continue.
        File hand-off: children write produced files under /workspace/subagents/ (they create it if missing) and return the paths; when you instruct a child where to put files, use /workspace/subagents/ (or a subpath of it) so parent and child agree on the location. If the child returns a path, use it as-is rather than assuming a directory.
        Optional timeoutMs cancels a runaway child (status=timeout, partial result preserved); maxToolCalls caps its tool round-trips.
        To ask a finished child agent follow-up questions, pass its sessionId to followup_agent instead of spawning again.
        Multiple spawn_agent calls in one turn run in parallel (up to 4 concurrently).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Short label for the subtask (shown in the UI)")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Self-contained instructions for the child agent")
                })
                put("async", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, return {taskId} immediately and poll later with poll_agent. Default false (block until done).")
                })
                put("timeoutMs", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional hard timeout; on expiry the child is cancelled and status=timeout with partial result.")
                })
                put("maxToolCalls", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional cap on the child's tool call round-trips; exceeding it ends the child with status=error.")
                })
            },
            required = listOf("description", "prompt"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "subtask" }
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (prompt.isBlank()) {
            error("spawn_agent requires a non-empty prompt")
        }
        val async = obj["async"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val maxToolCalls = obj["maxToolCalls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive" }
        require(maxToolCalls == null || maxToolCalls >= 0) { "maxToolCalls must be non-negative" }
        // Old calls may still contain `agent`; unknown fields are intentionally ignored.
        val result = onSpawn(description, prompt, async, timeoutMs, maxToolCalls)
        listOf(
            UIMessagePart.Text(result)
        )
    },
)

fun createFollowupAgentTool(
    onFollowUp: suspend (sessionId: String, message: String) -> String,
): Tool = Tool(
    name = FOLLOWUP_AGENT_TOOL_NAME,
    description = """
        Continue a conversation with a previously spawned child agent (multi-turn follow-up).
        Pass the `sessionId` returned by spawn_agent and your follow-up message; the child keeps its full context.
        Sessions stay resumable for about 30 minutes after the child finishes; afterwards you get AGENT_SESSION_NOT_FOUND (expired).
        Fails with AGENT_SESSION_NOT_FOUND if the session no longer exists. Has the same per-child step limit as spawn_agent.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("sessionId", buildJsonObject {
                    put("type", "string")
                    put("description", "sessionId from a previous spawn_agent result")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Follow-up message for the child agent")
                })
            },
            required = listOf("sessionId", "message"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        listOf(
            UIMessagePart.Text(
                if (sessionId.isBlank() || message.isBlank()) {
                    buildJsonObject {
                        put("status", "error")
                        put("error", "followup_agent requires non-empty sessionId and message")
                    }.toString()
                } else {
                    onFollowUp(sessionId, message)
                }
            )
        )
    },
)

fun createPollAgentTool(
    onPoll: suspend (taskId: String) -> String,
): Tool = Tool(
    name = POLL_AGENT_TOOL_NAME,
    description = """
        Check an async-spawned child agent: {taskId, status (running|completed|failed|cancelled|timeout), durationMs, toolCalls (so far), result (once finished)}.
        Poll returns the full structured result when the task is finished, or progress (tool call count, latest action) while still running.
        Unknown taskId returns AGENT_TASK_NOT_FOUND.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "taskId returned by spawn_agent(async=true)")
                })
            },
            required = listOf("taskId"),
        )
    },
    execute = { args ->
        val taskId = args.jsonObject["taskId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        listOf(
            UIMessagePart.Text(
                if (taskId.isBlank()) {
                    buildJsonObject {
                        put("status", "error")
                        put("error", "poll_agent requires a non-empty taskId")
                    }.toString()
                } else {
                    onPoll(taskId)
                }
            )
        )
    },
)

fun createCancelAgentTool(
    onCancel: suspend (taskId: String) -> String,
): Tool = Tool(
    name = CANCEL_AGENT_TOOL_NAME,
    description = """
        Cancel a running async child agent. The child stops producing new side effects; results produced so far are preserved
        and the task ends with status=cancelled. Already finished tasks cannot be cancelled.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "string")
                    put("description", "taskId returned by spawn_agent(async=true)")
                })
            },
            required = listOf("taskId"),
        )
    },
    execute = { args ->
        val taskId = args.jsonObject["taskId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        listOf(
            UIMessagePart.Text(
                if (taskId.isBlank()) {
                    buildJsonObject {
                        put("status", "error")
                        put("error", "cancel_agent requires a non-empty taskId")
                    }.toString()
                } else {
                    onCancel(taskId)
                }
            )
        )
    },
)

fun createListAgentsTool(
    onList: suspend () -> String,
): Tool = Tool(
    name = LIST_AGENTS_TOOL_NAME,
    description = """
        List child agents spawned in this conversation (running and finished, both sync and async): taskId, description, status, mode, durationMs.
        Use it to re-discover taskIds/sessionIds for poll_agent / cancel_agent / followup_agent.
    """.trimIndent(),
    parameters = { null },
    execute = {
        listOf(UIMessagePart.Text(onList()))
    },
)
