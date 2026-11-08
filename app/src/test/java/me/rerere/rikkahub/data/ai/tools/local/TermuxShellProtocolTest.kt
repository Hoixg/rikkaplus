package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Execute the generated protocol in real bash; Android intent delivery still needs a phone. */
class TermuxShellProtocolTest {
    private fun bash(): File {
        val file = listOf(
            File("/bin/bash"),
            File("C:/Program Files/Git/usr/bin/bash.exe"),
        ).firstOrNull { it.isFile }
        assumeTrue("Bash is required for the shell protocol tests", file != null)
        return file!!
    }

    private fun runShell(
        bash: File,
        arguments: List<String>,
        input: String,
        environment: Map<String, String> = emptyMap(),
        closeInput: Boolean = true,
    ): Triple<Int, String, String> {
        // A script file avoids Windows command-line reparsing of embedded quotes in bash -c.
        val script = File.createTempFile("termux-shell-test", ".sh")
        script.writeText(
            "export PATH=/usr/bin:/bin:\"\$PATH\"\n" +
                (listOf(bash.invariantSeparatorsPath) + arguments).joinToString(" ", transform = ::shellSingleQuote)
        )
        val process = ProcessBuilder(bash.absolutePath, script.absolutePath)
            .apply { environment().putAll(environment) }
            .start()
        try {
            process.outputStream.write(input.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            if (closeInput) process.outputStream.close()
            assertTrue("Shell protocol hung", process.waitFor(10, TimeUnit.SECONDS))
            return Triple(
                process.exitValue(),
                process.inputStream.bufferedReader(Charsets.UTF_8).readText(),
                process.errorStream.bufferedReader(Charsets.UTF_8).readText(),
            )
        } finally {
            runCatching { process.outputStream.close() }
            process.destroyForcibly()
            script.delete()
        }
    }

    @Test
    fun captureWrapperPreservesStdinAndRealExitStatus() {
        val bash = bash()
        val (executable, arguments) = buildMarkerWrappedArgv(
            bash.invariantSeparatorsPath,
            bash.invariantSeparatorsPath,
            arrayOf("-c", "IFS= read -r text; printf '%s' \"\$text\"; exit 17"),
            "TEST_DONE",
            stdin = "literal \$(echo should-not-execute)\n",
        )
        val input =
            Base64.getEncoder().encodeToString("literal \$(echo should-not-execute)\n".toByteArray(Charsets.UTF_8)) +
                "\n"
        val result = runShell(File(executable), arguments.toList(), input)
        assertEquals(17, result.first)
        val captured = parseCapturedResult(
            result.second,
            result.third,
            result.first,
            "TEST_DONE"
        ) as CaptureResult.Success
        assertEquals("literal \$(echo should-not-execute)", captured.stdout)
    }

    private fun capture(command: String, stdin: String? = null, timeoutMs: Long = 2_000): CaptureResult {
        val bash = bash()
        val (executable, arguments) = buildMarkerWrappedArgv(
            bash.invariantSeparatorsPath,
            bash.invariantSeparatorsPath,
            arrayOf("-c", command),
            "TEST_DONE",
            timeoutMs = timeoutMs,
            stdin = stdin,
        )
        // Reproduce beta.3: nonempty transport input gets a newline and EOF; empty input stays open.
        val input = if (stdin.isNullOrEmpty()) {
            ""
        } else {
            Base64.getEncoder().encodeToString(stdin.toByteArray(Charsets.UTF_8)) +
                "\n"
        }
        val result = runShell(File(executable), arguments.toList(), input, closeInput = !stdin.isNullOrEmpty())
        return parseCapturedResult(result.second, result.third, result.first, "TEST_DONE")
    }

    @Test
    fun captureStdinIsByteExactAndEmptyInputClosesEvenWhenTermuxKeepsItsPipeOpen() {
        listOf(null, "", "ABC", "ABC\n", "XYZ", "中文\n", "A\u0000B").forEach { input ->
            val result = capture("od -An -v -tx1", input)
            assertTrue("$result", result is CaptureResult.Success)
            result as CaptureResult.Success
            assertEquals(0, result.exitCode)
            val actual = result.stdout.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val expected = input.orEmpty().toByteArray(Charsets.UTF_8).map { "%02x".format(it.toInt() and 0xff) }
            assertEquals("stdin=$input", expected, actual)
        }
    }

    @Test
    fun captureLargeStdinDoesNotExceedTheSingleArgumentLimit() {
        val bash = bash()
        val input = "x".repeat(128 * 1024)
        val (_, arguments) = buildMarkerWrappedArgv(
            bash.invariantSeparatorsPath,
            bash.invariantSeparatorsPath,
            arrayOf("-c", "wc -c"),
            "TEST_DONE",
            stdin = input,
        )
        assertTrue("stdin must not be embedded in a shell argument", arguments[1].length < 4096)
        val result = capture("wc -c", input) as CaptureResult.Success
        assertEquals(0, result.exitCode)
        assertEquals(input.length.toString(), result.stdout.trim())
    }

    @Test
    fun captureTimeoutStopsTheChildProcessGroupAndReturnsPartialOutput() {
        val directory = Files.createTempDirectory("termux-timeout").toFile()
        try {
            val leaked = File(directory, "leaked")
            val command = "printf 'PARTIAL_TIMEOUT_TEST\\n'; printf 'PARTIAL_STDERR\\n' >&2; " +
                "(sleep 2; printf 'leaked' > ${shellSingleQuote(leaked.invariantSeparatorsPath)}) & wait"
            val result = capture(command, timeoutMs = 500)
            assertTrue("$result", result is CaptureResult.Timeout)
            result as CaptureResult.Timeout
            assertTrue(result.commandStopped)
            assertEquals("PARTIAL_TIMEOUT_TEST\n", result.stdout)
            assertTrue(result.stderr, result.stderr.contains("PARTIAL_STDERR"))
            Thread.sleep(2100)
            assertFalse("A child continued after timeout", leaked.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun timeoutIsNotConfusedWithARealExit124Or137() {
        listOf(124, 137).forEach { code ->
            val result = capture("printf 'normal'; printf 'error-text' >&2; exit $code")
            assertTrue("$result", result is CaptureResult.Success)
            result as CaptureResult.Success
            assertEquals(code, result.exitCode)
            assertEquals("normal", result.stdout)
            assertEquals("error-text", result.stderr)
        }
    }

    @Test
    fun managedShellReportsRunningBeforeCommandAndExactFailureAfterIt() {
        val bash = bash()
        val directory = Files.createTempDirectory("termux-protocol").toFile()
        try {
            val events = File(directory, "events")
            val tmux = File(directory, "fake-tmux")
            tmux.writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> \"\$RK_TEST_EVENTS\"\n")
            tmux.setExecutable(true)
            val command = managedTermuxShell(tmux.invariantSeparatorsPath, bash.invariantSeparatorsPath)
            val input = """
                printf 'COMMAND\n' >> "${'$'}RK_TEST_EVENTS"; sleep 0.1; (exit 17)
                read -r response; printf '%s\n' "${'$'}response"
                literal ${'$'}(echo must-stay-literal)
                exit
            """.trimIndent() + "\n"
            val result = runShell(
                bash,
                listOf("-c", command),
                input,
                mapOf(
                    "HOME" to directory.invariantSeparatorsPath,
                    "TMUX_PANE" to "%0",
                    "RK_TEST_EVENTS" to events.invariantSeparatorsPath
                )
            )
            assertTrue(result.second, events.isFile)
            val log = events.readText()
            val before = log.indexOf("@rk_state running")
            val executed = log.indexOf("COMMAND")
            val completed = log.indexOf("@rk_exit 17")
            assertTrue("$log\n${result.second}", before >= 0 && before < executed && executed < completed)
            assertTrue(log, log.contains("-t %0"))
            assertFalse(log, log.contains("-t \"%0\""))
            assertTrue(result.second, result.second.contains("literal \$(echo must-stay-literal)"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun backgroundArgvIsLiteralAndStdinPipelineKeepsExitCode() {
        val bash = bash()
        val directory = Files.createTempDirectory("termux-background").toFile()
        try {
            val source = File(directory, "stdin")
            source.writeText("first line\nsecond 'quoted' line\n")
            val events = File(directory, "events")
            val fakeTmux = File(directory, "fake-tmux")
            fakeTmux.writeText(
                "#!/bin/sh\nif [ \"\$1\" = save-buffer ]; then cat \"\$RK_TEST_INPUT\"; " +
                    "else printf 'deleted' >> \"\$RK_TEST_EVENTS\"; fi\n"
            )
            fakeTmux.setExecutable(true)
            val script = termuxBackgroundScript(
                bash.invariantSeparatorsPath,
                arrayOf("-c", "cat; printf '%s' \"\$1\"; exit 23", "test", "literal \$(echo no) ; 'quoted'"),
                "rk_test"
            ).replace("$TERMUX_BIN/tmux", fakeTmux.invariantSeparatorsPath)
            val result = runShell(
                bash,
                listOf("-c", script),
                "",
                mapOf(
                    "RK_TEST_INPUT" to source.invariantSeparatorsPath,
                    "RK_TEST_EVENTS" to events.invariantSeparatorsPath
                )
            )
            assertEquals(23, result.first)
            assertEquals(source.readText() + "literal \$(echo no) ; 'quoted'", result.second)
            assertEquals("deleted", events.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
