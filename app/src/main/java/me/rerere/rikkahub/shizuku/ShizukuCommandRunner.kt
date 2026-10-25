// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only

@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/** Compatibility for the original exec RPC, using the same lifecycle as tracked tasks. */
internal object ShizukuCommandRunner {
    fun run(
        command: String,
        timeoutMs: Int,
        maxStdoutBytes: Int,
        maxStderrBytes: Int,
        launch: (
            ShizukuCommandRequest
        ) -> ShizukuManagedProcess = { ShizukuManagedProcess(it.processBuilder().start()) },
    ): JsonObject = ShizukuTaskManager(launch = launch).use { tasks ->
        val id = "sz_" + UUID.randomUUID().toString().replace("-", "")
        tasks.start(id, ShizukuCommandRequest(command = command, timeoutMs = timeoutMs.coerceIn(1000, 300_000)))
        var state = tasks.read(id, 20_000)
        while (state["finished"]?.jsonPrimitive?.boolean == false) state = tasks.read(id, 20_000)
        fun preview(stream: String, limit: Int): String {
            val page = tasks.output(id, stream, 0, limit.coerceIn(4, 65_536), null)
            val omitted = page["total_bytes"]!!.jsonPrimitive.long - page["next_offset"]!!.jsonPrimitive.int
            return page["output"]!!.jsonPrimitive.content +
                if (omitted > 0) "\n…[truncated; $omitted bytes more]" else ""
        }
        val stdout = preview("stdout", maxStdoutBytes)
        val stderr = preview("stderr", maxStderrBytes)
        JsonObject(state + buildJsonObject {
            if (state["execution_status"]?.jsonPrimitive?.content == "timed_out") {
                put("error", "command_timeout")
                put("partial_stdout", stdout)
                put("partial_stderr", stderr)
            } else {
                put("stdout", stdout)
                put("stderr", stderr)
            }
        })
    }
}
