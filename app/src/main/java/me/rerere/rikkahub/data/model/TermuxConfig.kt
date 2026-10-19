package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

object TermuxDefaults {
    const val DEFAULT_COMMAND_TIMEOUT_MS = 60_000L
    const val DEFAULT_VERIFY_TIMEOUT_MS = 8_000L
    const val MAX_COMMAND_TIMEOUT_SECONDS = 600
    const val DEFAULT_WORKING_DIR = "/data/data/com.termux/files/home"
}

/** Stored with the other app settings; no separate preferences file or runtime singleton. */
@Serializable
data class TermuxConfig(
    val commandTimeoutMs: Long = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
    val verifyTimeoutMs: Long = TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
    val defaultWorkingDir: String = TermuxDefaults.DEFAULT_WORKING_DIR,
    val maxStdoutBytes: Int = 8_000,
    val maxStderrBytes: Int = 2_000,
    val aptWrapEnabled: Boolean = true,
    val approvalRequired: Boolean = true,
    val lastVerifiedAtMs: Long = 0,
) {
    fun normalized(): TermuxConfig = copy(
        commandTimeoutMs = commandTimeoutMs.coerceIn(5_000L, 600_000L),
        verifyTimeoutMs = verifyTimeoutMs.coerceIn(3_000L, 30_000L),
        defaultWorkingDir = defaultWorkingDir.trim().ifBlank { TermuxDefaults.DEFAULT_WORKING_DIR },
        maxStdoutBytes = maxStdoutBytes.coerceIn(1_000, 64_000),
        maxStderrBytes = maxStderrBytes.coerceIn(500, 16_000),
        lastVerifiedAtMs = lastVerifiedAtMs.coerceAtLeast(0),
    )
}
