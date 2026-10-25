// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs under the shell UID, in the separate process Shizuku spawns for it (bound via
 * [ShizukuManager.exec] / `Shizuku.bindUserService`). `Shizuku.newProcess` is private in
 * dev.rikka.shizuku:api 13.1.5 (verified with `javap -p` against the published artifact), so
 * this AIDL user service is the supported way to run a command with Shizuku's privileges.
 *
 * The no-arg constructor is required by Shizuku's server: it instantiates this class by
 * reflection in the spawned process. Actual process launch + bounded capture + timeout
 * enforcement lives in the Android-independent [ShizukuTaskManager] so that logic can be
 * unit-tested on the host JVM without Shizuku or a device.
 */
@Keep
class ShizukuUserService : IShizukuUserService.Stub() {
    private val tasks = ShizukuTaskManager(launch = ShizukuProcessControl::launch)

    init {
        Log.i(TAG, "constructor")
    }

    override fun exec(command: String?, timeoutMs: Int): String {
        val result = ShizukuCommandRunner.run(
            command = command.orEmpty(),
            timeoutMs = if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS,
            maxStdoutBytes = MAX_STDOUT_BYTES,
            maxStderrBytes = MAX_STDERR_BYTES,
            launch = ShizukuProcessControl::launch,
        )
        return result.toString()
    }

    private fun response(action: () -> kotlinx.serialization.json.JsonObject): String = try {
        action().toString()
    } catch (e: IllegalArgumentException) {
        buildJsonObject {
            put("error", "invalid_arguments")
            put("reason", e.message)
        }.toString()
    } catch (e: Exception) {
        buildJsonObject {
            put("error", "shizuku_operation_failed")
            put("reason", e.message)
        }.toString()
    }

    override fun start(taskId: String?, requestJson: String?): String = response {
        requireNotNull(requestJson)
        require(requestJson.toByteArray(Charsets.UTF_8).size <= 192 * 1024) { "Request is too large." }
        tasks.start(requireNotNull(taskId), shizukuJson.decodeFromString<ShizukuCommandRequest>(requestJson))
    }

    override fun read(taskId: String?): String = response { tasks.read(requireNotNull(taskId)) }
    override fun list(): String = response { tasks.list() }
    override fun cancel(taskId: String?): String = response { tasks.cancel(requireNotNull(taskId)) }
    override fun forget(taskId: String?): String = response { tasks.forget(requireNotNull(taskId)) }
    override fun output(taskId: String?, stream: String?, offset: Int, limit: Int, query: String?): String = response {
        tasks.output(requireNotNull(taskId), requireNotNull(stream), offset, limit, query)
    }

    override fun diagnostics(): String = response {
        buildJsonObject {
            put("user_service_connected", true)
            put("user_service_uid", Process.myUid())
            put("android_api", Build.VERSION.SDK_INT)
            put("process_group_supported", ShizukuProcessControl.supported())
            put("protocol_version", 2)
            put("working_directory", java.io.File(".").canonicalPath)
            put("max_running_tasks", 4)
            put("max_tasks", 16)
            put("output_limit_per_stream", 1024 * 1024)
            put("retention_ms", 60 * 60 * 1000)
        }
    }

    /** Reserved method Shizuku's server calls to tear this service down. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        tasks.close()
        System.exit(0)
    }

    companion object {
        private const val TAG = "ShizukuUserService"
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val MAX_STDOUT_BYTES = 8_000
        private const val MAX_STDERR_BYTES = 2_000
    }
}
