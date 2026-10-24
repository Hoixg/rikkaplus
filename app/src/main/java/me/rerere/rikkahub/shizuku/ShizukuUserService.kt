// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

package me.rerere.rikkahub.shizuku

import android.util.Log
import androidx.annotation.Keep

/**
 * Runs under the shell UID, in the separate process Shizuku spawns for it (bound via
 * [ShizukuManager.exec] / `Shizuku.bindUserService`). `Shizuku.newProcess` is private in
 * dev.rikka.shizuku:api 13.1.5 (verified with `javap -p` against the published artifact), so
 * this AIDL user service is the supported way to run a command with Shizuku's privileges.
 *
 * The no-arg constructor is required by Shizuku's server: it instantiates this class by
 * reflection in the spawned process. Actual process launch + bounded capture + timeout
 * enforcement lives in the Android-independent [ShizukuCommandRunner] so that logic can be
 * unit-tested on the host JVM without Shizuku or a device.
 */
@Keep
class ShizukuUserService : IShizukuUserService.Stub() {

    init {
        Log.i(TAG, "constructor")
    }

    override fun exec(command: String?, timeoutMs: Int): String {
        val result = ShizukuCommandRunner.run(
            command = command.orEmpty(),
            timeoutMs = if (timeoutMs > 0) timeoutMs else DEFAULT_TIMEOUT_MS,
            maxStdoutBytes = MAX_STDOUT_BYTES,
            maxStderrBytes = MAX_STDERR_BYTES,
        )
        return result.toString()
    }

    /** Reserved method Shizuku's server calls to tear this service down. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    companion object {
        private const val TAG = "ShizukuUserService"
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val MAX_STDOUT_BYTES = 8_000
        private const val MAX_STDERR_BYTES = 2_000
    }
}
