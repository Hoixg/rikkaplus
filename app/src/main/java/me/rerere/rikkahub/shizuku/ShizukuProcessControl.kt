@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Each command owns a fresh session/process group, so cancellation also stops pipelines/children. */
internal object ShizukuProcessControl {
    fun supported(): Boolean = File("/system/bin/setsid").canExecute()

    fun launch(request: ShizukuCommandRequest): ShizukuManagedProcess {
        check(supported()) { "This ROM has no /system/bin/setsid; managed process cancellation is unavailable." }
        val process = request.processBuilder(listOf("/system/bin/setsid")).start()
        val pid = try {
            // UserService is exempt from hidden API restrictions. Process.pid() is not available
            // on every supported Android version; libcore's process has a stable integer pid field.
            val publicPid = runCatching {
                (Process::class.java.getMethod(
                    "pid"
                ).invoke(process) as Number).toInt()
            }.getOrNull()
            (publicPid ?: generateSequence<Class<*>>(process.javaClass) { it.superclass }.mapNotNull { type ->
                runCatching {
                    (type.getDeclaredField(
                        "pid"
                    ).apply { isAccessible = true }.get(process) as Number).toInt()
                }.getOrNull()
            }.firstOrNull())?.takeIf { it > 1 } ?: error("Could not determine the command PID.")
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
        return ShizukuManagedProcess(process) {
            // Negative PID addresses only this newly-created group, never the UserService's group.
            runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            process.destroyForcibly()
            // Cover cancellation racing with setsid establishing its group.
            runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        }
    }
}
