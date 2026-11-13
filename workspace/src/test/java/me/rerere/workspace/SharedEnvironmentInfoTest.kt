package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files

class SharedEnvironmentInfoTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(base: File, path: String, text: String): File = File(base, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test fun `an absent shared environment is reported without creating directories`() {
        val directory = File(temp.root, "not-installed")
        val info = inspectSharedEnvironment(directory)
        assertFalse(directory.exists())
        assertFalse(info.installed)
        assertEquals(0L, info.sizeBytes)
        assertTrue(info.packages.isEmpty())
        assertTrue(info.commands.isEmpty())
    }

    @Test fun `reads actual installed Debian packages and local commands without changing files`() {
        val environment = temp.newFolder()
        val linux = File(environment, "linux")
        write(linux, "bin/sh", "shell")
        write(linux, "etc/os-release", "NAME=Ubuntu\nPRETTY_NAME=\"Ubuntu 24.04.3 LTS\"\n")
        val status =
            write(linux, "var/lib/dpkg/status", """
            Package: python3
            Status: install ok installed
            Version: 3.12.3
            Description: Python
             continued: description

            Package: removed
            Status: deinstall ok config-files
            Version: 1.0

            Package: git
            Status: install ok installed
            Version: 2.43.0

            Package: nodejs
            Status: hold ok installed
            Version: 20.19.0
            """.trimIndent())
        write(linux, "usr/local/bin/uv", "binary")
        write(linux, "root/.local/bin/custom-tool", "script")
        File(linux, "usr/local/bin/not-a-command").mkdirs()
        val before = status.readBytes()
        val info = inspectSharedEnvironment(environment)
        assertTrue(info.installed)
        assertEquals("Ubuntu 24.04.3 LTS", info.distribution)
        assertEquals(
            listOf(
                EnvironmentPackage("git", "2.43.0"),
                EnvironmentPackage("nodejs", "20.19.0"),
                EnvironmentPackage("python3", "3.12.3")
            ),
            info.packages
        )
        assertEquals(listOf("/root/.local/bin/custom-tool", "/usr/local/bin/uv"), info.commands)
        assertTrue(before.contentEquals(status.readBytes()))
        assertFalse(info.incomplete)
    }

    @Test fun `Alpine package records are read without requiring a Debian environment`() {
        val environment = temp.newFolder()
        write(environment, "linux/bin/sh", "shell")
        write(environment, "linux/lib/apk/db/installed", "P:git\nV:2.47.0-r0\n\nP:python3\nV:3.12.7-r0\n")
        val info = inspectSharedEnvironment(environment)
        assertEquals(
            listOf(EnvironmentPackage("git", "2.47.0-r0"), EnvironmentPackage("python3", "3.12.7-r0")),
            info.packages
        )
    }

    @Test fun `storage excludes workspace files and does not follow directory symlinks`() {
        val base = temp.newFolder()
        val environment = File(base, "environment").apply { mkdirs() }
        val project = File(base, "workspaces/project").apply { mkdirs() }
        write(project, "large-file", "x".repeat(100_000))
        write(environment, "linux/bin/sh", "shell")
        val archive = write(environment, "tmp/rootfs.tar", "archive")
        Files.createSymbolicLink(File(environment, "linux/workspace").toPath(), project.toPath())
        val info = inspectSharedEnvironment(environment)
        assertEquals(archive.length(), info.sizeBytes - info.rootfsSizeBytes)
        assertTrue(info.rootfsSizeBytes < 100_000)
        assertTrue(File(project, "large-file").exists())
    }

    @Test fun `a cancelled scan exits without modifying the environment`() {
        val environment = temp.newFolder()
        val shell = write(environment, "linux/bin/sh", "shell")
        try {
            Thread.currentThread().interrupt()
            assertTrue(
                runCatching { inspectSharedEnvironment(environment) }.exceptionOrNull() is InterruptedIOException
            )
        } finally {
            Thread.interrupted()
        }
        assertEquals("shell", shell.readText())
    }
}
