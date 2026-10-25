@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Cancellable client polling, independent of Android/Binder for host verification. */
internal class ShizukuCommandClient(
    private val start: suspend (String, ShizukuCommandRequest) -> JsonObject,
    private val read: suspend (String) -> JsonObject,
    private val output: suspend (String, String, Int) -> JsonObject,
    private val cancel: suspend (String) -> JsonObject,
) {
    suspend fun execute(request: ShizukuCommandRequest): JsonObject {
        request.validate()
        val id = "sz_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val started = start(id, request)
            if (started["error"] != null || request.background) return started
            val result = withTimeoutOrNull(request.timeoutMs + 7000L) {
                var current = started
                while (current["finished"]?.jsonPrimitive?.booleanOrNull != true && current["error"] == null) {
                    delay(100)
                    current = read(id)
                }
                if (current["error"] != null) {
                    current
                } else {
                    val stdout = output(id, "stdout", 8000)
                    val stderr = output(id, "stderr", 2000)
                    JsonObject(current + buildJsonObject {
                        val status = current["execution_status"]?.jsonPrimitive?.content
                        put("success", status == "completed" && current["exit_code"]?.jsonPrimitive?.intOrNull == 0)
                        put("stdout", stdout["output"]?.jsonPrimitive?.content.orEmpty())
                        put("stderr", stderr["output"]?.jsonPrimitive?.content.orEmpty())
                        put(
                            "stdout_truncated",
                            stdout["has_more"]?.jsonPrimitive?.booleanOrNull == true ||
                                stdout["output_truncated"]?.jsonPrimitive?.booleanOrNull == true
                        )
                        put(
                            "stderr_truncated",
                            stderr["has_more"]?.jsonPrimitive?.booleanOrNull == true ||
                                stderr["output_truncated"]?.jsonPrimitive?.booleanOrNull == true
                        )
                        if (status == "timed_out") {
                            put("error", "command_timeout")
                            put("partial_stdout", stdout["output"]?.jsonPrimitive?.content.orEmpty())
                            put("partial_stderr", stderr["output"]?.jsonPrimitive?.content.orEmpty())
                        }
                        if (stdout["error"] != null || stderr["error"] != null) put("output_unavailable", true)
                    })
                }
            }
            return result ?: shizukuCallFailed(id)
        } catch (cancelled: CancellationException) {
            // Cancelling chat generation stops its synchronous command, not unrelated background jobs.
            if (!request.background) withContext(NonCancellable) { cancel(id) }
            throw cancelled
        }
    }
}

internal fun shizukuCallFailed(id: String? = null): JsonObject = buildJsonObject {
    put("error", "shizuku_call_failed")
    put("execution_status", "unknown")
    id?.let {
        put("task_id", it)
        put("output_id", it)
    }
    put(
        "recovery",
        "The operation outcome is unknown. Check shizuku_task_list/read before starting another command; do not replay a command automatically."
    )
}
