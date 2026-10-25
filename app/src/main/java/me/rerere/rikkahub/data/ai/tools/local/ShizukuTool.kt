// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuCommandRequest
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.shizuku.isShizukuTaskId
import me.rerere.rikkahub.shizuku.shizukuJson

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MIN_TIMEOUT_MS = 1_000

/** Matches ssh_exec's ceiling (300s / 5 min). */
private const val MAX_TIMEOUT_MS = 300_000

/**
 * Run a shell command with Shizuku's privileges (the UID of the running Shizuku service). Requires the Shizuku app installed, its service running, and
 * permission granted from Extensions -> Shizuku; the permission is never requested from this
 * tool or automatically, only from an explicit tap on that settings screen.
 *
 * Uses this branch's existing per-call tool approval, controlled by the Shizuku settings.
 */
fun shizukuExecTool(context: Context, approvalRequired: () -> Boolean = { true }): Tool = Tool(
    name = "shizuku_exec",
    description = """
        Run a shell command with Shizuku's privileges (ADB shell or root, depending on how Shizuku was started). Returns stdout, stderr, and exit code. Requires the Shizuku app
        installed, its service running, and permission granted from Extensions -> Shizuku.
        Supply command OR an absolute executable with literal arguments. Supports stdin, working_dir and environment.
        background=true returns a tracked task immediately. Otherwise waits for completion and cancels if chat generation stops.
        All modes return task_id/output_id; use shizuku_output_read for full paginated output and shizuku_task_read/list/cancel/forget to manage tasks.
        Tasks are shared across chats and expire one hour after finishing. Tracking and retained output are lost on a UserService restart.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run, e.g. 'pm list packages -3'")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds. Default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.")
                })
                put("executable", stringSchema("Absolute executable path; mutually exclusive with command."))
                put("arguments", buildJsonObject {
                    put("type", "array")
                    put("items", stringSchema("Literal argument."))
                })
                put("stdin", stringSchema("UTF-8 input, closed after writing. Omit to supply immediate EOF."))
                put("working_dir", stringSchema("Absolute working directory. Omit to inherit the service directory."))
                put("environment", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                    put("description", "Environment overrides for this task only.")
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Return immediately with task_id; default false.")
                })
            },
        )
    },
    needsApproval = { approvalRequired() },
    execute = { input ->
        shizukuResult {
            val request = shizukuJson.decodeFromJsonElement<ShizukuCommandRequest>(input).let {
                it.copy(timeoutMs = it.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
            }
            ShizukuManager.execute(context, request)
        }
    }
)

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}
private fun integerSchema(
    description: String
) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}
private fun taskSchema() = stringSchema(
    "Managed task_id/output_id returned by shizuku_exec or shizuku_task_list; shared across chats."
)

private fun JsonElement.taskId(): String = jsonObject["task_id"]?.jsonPrimitive?.contentOrNull
    ?.takeIf(::isShizukuTaskId) ?: throw IllegalArgumentException("A valid task_id is required.")

private suspend fun shizukuResult(action: suspend () -> JsonObject): List<UIMessagePart> {
    val result = try {
        action()
    } catch (e: IllegalArgumentException) {
        buildJsonObject {
            put("error", "invalid_arguments")
            put("reason", e.message)
        }
    }
    return listOf(UIMessagePart.Text(result.toString()))
}

internal fun shizukuTaskTools(context: Context, approvalRequired: () -> Boolean): List<Tool> = listOf(
    Tool(
        name = "shizuku_status",
        description = "Diagnose Shizuku installation, binder, permission, server version/UID (root or shell), Android version and actual UserService connectivity/capabilities. Never requests permission or starts Shizuku.",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = { shizukuResult { ShizukuManager.diagnostics(context) } }
    ),
    Tool(
        name = "shizuku_task_read",
        description = "Read a managed Shizuku task's execution_status/exit_code. Optional wait_ms (0..20000) waits for completion without cancelling the task. Use shizuku_output_read to read its stdout/stderr.",
        parameters = {
            InputSchema.Obj(buildJsonObject {
                put("task_id", taskSchema())
                put(
                    "wait_ms",
                    integerSchema(
                        "Wait for completion for up to 20000 ms; default 0. A read timeout leaves the task running."
                    )
                )
            }, required = listOf("task_id"))
        },
        execute = { input ->
            shizukuResult {
                val id = input.taskId()
                val waitMs = (input.jsonObject["wait_ms"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 20_000)
                val deadline = android.os.SystemClock.elapsedRealtime() + waitMs
                var result = ShizukuManager.taskRead(context, id)
                while (result["finished"]?.jsonPrimitive?.booleanOrNull == false &&
                    android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(minOf(100, (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)))
                    result = ShizukuManager.taskRead(context, id)
                }
                result
            }
        }
    ),
    Tool(
        name = "shizuku_task_list",
        description = "List shared Shizuku tasks with IDs, status, exit codes and retention limits. Does not expose commands, environment or stdin. Check this after an unknown execution result before retrying a command.",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = { shizukuResult { ShizukuManager.taskList(context) } }
    ),
    Tool(
        name = "shizuku_task_cancel",
        description = "Cancel one managed Shizuku task and its owned process group, including normal child processes. Output remains readable. May return stopping; read until finished before assuming cancellation completed.",
        parameters = {
            InputSchema.Obj(
                buildJsonObject { put("task_id", taskSchema()) },
                required = listOf("task_id")
            )
        },
        needsApproval = { approvalRequired() },
        execute = { input -> shizukuResult { ShizukuManager.taskCancel(context, input.taskId()) } }
    ),
    Tool(
        name = "shizuku_task_forget",
        description = "Remove a finished task and its retained output from memory. Running tasks must be cancelled and finished first.",
        parameters = {
            InputSchema.Obj(
                buildJsonObject { put("task_id", taskSchema()) },
                required = listOf("task_id")
            )
        },
        needsApproval = { approvalRequired() },
        execute = { input -> shizukuResult { ShizukuManager.taskForget(context, input.taskId()) } }
    ),
    Tool(
        name = "shizuku_output_read",
        description = "Page or search a task's retained UTF-8 stdout/stderr without rerunning it. Start offset=0, then pass next_offset. Each stream retains up to 1 MiB; output_truncated/dropped_bytes explicitly report overflow. No task logs are written to disk. For running tasks, retry the same next_offset when no new output is available.",
        parameters = {
            InputSchema.Obj(buildJsonObject {
                put("task_id", taskSchema())
                put("stream", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("stdout")
                        add("stderr")
                    })
                    put("description", "stdout (default) or stderr.")
                })
                put("offset", integerSchema("Byte offset, default 0. Use next_offset from the previous page."))
                put("limit", integerSchema("Page size in bytes, 4..65536; default 8000."))
                put(
                    "query",
                    stringSchema("Optional literal text to search from offset; not a regex. Maximum 1024 characters.")
                )
            }, required = listOf("task_id"))
        },
        execute = { input ->
            shizukuResult {
                ShizukuManager.output(
                    context,
                    input.taskId(),
                    input.jsonObject["stream"]?.jsonPrimitive?.contentOrNull ?: "stdout",
                    input.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0,
                    input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 8000,
                    input.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                )
            }
        }
    )
)
