@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

internal val shizukuJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class ShizukuCommandRequest(
    val command: String? = null,
    val executable: String? = null,
    val arguments: List<String> = emptyList(),
    val stdin: String? = null,
    @SerialName("working_dir")
    val workingDir: String? = null,
    val environment: Map<String, String> = emptyMap(),
    @SerialName("timeout_ms")
    val timeoutMs: Int = 30_000,
    val background: Boolean = false,
) {
    fun validate() {
        require((command != null) xor (executable != null)) { "Provide command OR executable, exclusively." }
        require(command == null || command.isNotBlank()) { "command must not be blank." }
        require(executable == null || executable.startsWith('/')) { "executable must be an absolute path." }
        require(command == null || arguments.isEmpty()) { "arguments requires executable." }
        require(
            workingDir == null || workingDir.startsWith('/') || File(workingDir).isAbsolute
        ) { "working_dir must be an absolute path." }
        require(
            environment.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }
        ) { "Invalid environment variable name." }
        val values =
            listOfNotNull(command, executable, workingDir, stdin) + arguments + environment.keys + environment.values
        require(values.none { '\u0000' in it }) { "NUL characters are not supported." }
        require(
            values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 128 * 1024
        ) { "Input exceeds 128 KiB." }
        require(
            shizukuJson.encodeToString(this).toByteArray(Charsets.UTF_8).size <= 192 * 1024
        ) { "Encoded request exceeds 192 KiB." }
        require(timeoutMs in 1_000..300_000) { "timeout_ms must be between 1000 and 300000." }
    }

    fun processBuilder(prefix: List<String> = emptyList()): ProcessBuilder = ProcessBuilder(
        prefix + (executable?.let { listOf(it) + arguments } ?: listOf("sh", "-c", command!!))
    ).apply {
        workingDir?.let { directory(File(it)) }
        environment().putAll(environment)
    }
}

internal fun isShizukuTaskId(id: String): Boolean = Regex("sz_[a-f0-9]{32}").matches(id)
