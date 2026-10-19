package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
    ): Pair<Int, String> {
        // A script file avoids Windows command-line reparsing of embedded quotes in bash -c.
        val script = File.createTempFile("termux-shell-test", ".sh")
        script.writeText(
            "export PATH=/usr/bin:/bin:\"\$PATH\"\n" +
                (listOf(bash.invariantSeparatorsPath) + arguments).joinToString(" ", transform = ::shellSingleQuote)
        )
        val process = ProcessBuilder(bash.absolutePath, script.absolutePath)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
        try {
            assertTrue("Shell protocol hung", process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
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
            "TEST_DONE"
        )
        val result = runShell(File(executable), arguments.toList(), "literal \$(echo should-not-execute)\n")
        assertEquals(17, result.first)
        assertEquals("literal \$(echo should-not-execute)", stripTerminationMarker(result.second, "TEST_DONE"))
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
