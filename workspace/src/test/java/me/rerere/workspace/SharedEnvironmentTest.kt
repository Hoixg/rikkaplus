package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

class SharedEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()

    private fun manager(runner: WorkspaceShellRunner = HostShellRunner()) = WorkspaceManager(
        baseDir = temp.newFolder(), environmentDir = temp.newFolder(), shellRunner = runner,
    )

    @Test
    fun `workspaces share system files but keep projects and temporary files separate`() {
        val manager = manager()
        manager.ensureWorkspace("first")
        manager.ensureWorkspace("second")
        File(manager.sharedLinuxDir, "bin").mkdirs()
        File(manager.sharedLinuxDir, "bin/sh").writeText("shell")
        manager.writeText("first", "project.txt", "first")
        manager.writeText("second", "project.txt", "second")
        File(manager.tempDir("first"), "result").writeText("private temp")

        assertEquals(manager.linuxDir("first"), manager.linuxDir("second"))
        assertTrue(manager.hasRootfs("second"))
        assertEquals("first", manager.readText("first", "project.txt"))
        assertEquals("second", manager.readText("second", "project.txt"))
        assertFalse(manager.resolveRootfsFile("second", "/tmp/result").exists())
        assertEquals("private temp", manager.resolveRootfsFile("first", "/tmp/result").readText())

        assertTrue(manager.deleteWorkspace("first"))
        assertTrue(manager.deleteWorkspace("second"))
        manager.ensureWorkspace("third")
        assertTrue(manager.hasRootfs("third"))
        assertFalse(File(manager.workspaceDir("third"), "linux").exists())
    }

    @Test
    fun `second workspace reuses installed environment without another download`() = server { url, requests ->
        val manager = manager()
        val installer = RootfsInstaller(manager)
        installer.install("first", url)
        File(manager.sharedLinuxDir, "installed-python").writeText("keep")
        installer.install("second", url)

        assertEquals(1, requests.get())
        assertTrue(manager.hasRootfs("second"))
        assertEquals("keep", File(manager.linuxDir("second"), "installed-python").readText())
        assertTrue(manager.filesDir("second").isDirectory)
    }

    @Test
    fun `parallel workspace installs download only once`() = server { url, requests ->
        val manager = manager()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val tasks = listOf("first", "second").map { root ->
                pool.submit { start.await(); RootfsInstaller(manager).install(root, url) }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, requests.get())
            assertTrue(manager.hasRootfs("first"))
            assertTrue(manager.hasRootfs("second"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `failed reinstall preserves the installed system and every project`() = server(archive(valid = false)) { url, _ ->
        val manager = installedManager()
        manager.writeText("first", "project.txt", "project")
        val failure = runCatching { RootfsInstaller(manager).install("first", url, replaceExisting = true) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("old system", File(manager.sharedLinuxDir, "bin/sh").readText())
        assertEquals("project", manager.readText("first", "project.txt"))
        assertTrue(manager.hasRootfs("second"))
    }

    @Test
    fun `fresh invalid archive never becomes a ready environment`() = server(archive(valid = false)) { url, _ ->
        val manager = manager()
        assertTrue(runCatching { RootfsInstaller(manager).install("first", url) }.isFailure)
        assertFalse(manager.hasRootfs("first"))
        assertFalse(manager.previousLinuxDir.exists())
    }

    @Test
    fun `explicit reinstall replaces the shared system and retains project files`() = server { url, _ ->
        val manager = installedManager()
        manager.ensureWorkspace("second")
        manager.writeText("first", "project.txt", "first")
        manager.writeText("second", "project.txt", "second")
        RootfsInstaller(manager).install("first", url, replaceExisting = true)

        assertEquals("new system", File(manager.linuxDir("second"), "bin/sh").readText())
        assertEquals("first", manager.readText("first", "project.txt"))
        assertEquals("second", manager.readText("second", "project.txt"))
        assertFalse(manager.previousLinuxDir.exists())
    }

    @Test
    fun `install waits for an executing command before replacing its environment`() = server { url, requests ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return WorkspaceCommandResult(0, "done", "")
            }
        })
        manager.ensureWorkspace("first")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = pool.submit { manager.executeCommand("first", "work") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val replacing = CountDownLatch(1)
            val install = pool.submit { replacing.countDown(); RootfsInstaller(manager).install("second", url) }
            assertTrue(replacing.await(5, TimeUnit.SECONDS))
            assertFalse(install.isDone)
            assertEquals(0, requests.get())
            release.countDown()
            command.get(10, TimeUnit.SECONDS)
            install.get(10, TimeUnit.SECONDS)
            assertTrue(manager.hasRootfs("first"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `activation recovery restores the last published environment`() {
        val manager = installedManager()
        assertTrue(manager.sharedLinuxDir.renameTo(manager.previousLinuxDir))
        manager.recoverEnvironment()
        assertEquals("old system", File(manager.sharedLinuxDir, "bin/sh").readText())
        assertFalse(manager.previousLinuxDir.exists())
    }

    @Test
    fun `workspace roots cannot point at the environment or parent directory`() {
        val manager = manager()
        for (root in listOf(".", "..", ".environment")) {
            assertThrows(IllegalArgumentException::class.java) { manager.deleteWorkspace(root) }
        }
    }

    private fun installedManager() = manager().apply {
        ensureWorkspace("first")
        File(sharedLinuxDir, "bin").mkdirs()
        File(sharedLinuxDir, "bin/sh").writeText("old system")
    }

    @Test
    fun `independent workspace installs separately and deletion cannot remove shared packages`() = server { url, requests ->
        val manager = manager()
        manager.ensureWorkspace("shared")
        manager.ensureWorkspace("private", independentEnvironment = true)
        RootfsInstaller(manager).install("shared", url)
        File(manager.sharedLinuxDir, "shared-package").writeText("shared")
        assertFalse(manager.hasRootfs("private"))
        RootfsInstaller(manager).install("private", url)
        assertEquals(2, requests.get())
        assertNotEquals(manager.linuxDir("shared"), manager.linuxDir("private"))
        assertFalse(File(manager.linuxDir("private"), "shared-package").exists())
        File(manager.linuxDir("private"), "private-package").writeText("private")
        assertFalse(File(manager.sharedLinuxDir, "private-package").exists())
        assertTrue(manager.deleteWorkspace("private"))
        assertTrue(manager.hasRootfs("shared"))
        assertEquals("shared", File(manager.sharedLinuxDir, "shared-package").readText())
    }

    @Test
    fun `independent installation does not wait for commands in the shared environment`() = server { url, _ ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return WorkspaceCommandResult(0, "", "")
            }
        })
        manager.ensureWorkspace("shared")
        manager.ensureWorkspace("private", independentEnvironment = true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = pool.submit { manager.executeCommand("shared", "work") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            pool.submit { RootfsInstaller(manager).install("private", url) }.get(5, TimeUnit.SECONDS)
            assertFalse(command.isDone)
            assertTrue(manager.hasRootfs("private"))
            assertFalse(manager.hasRootfs("shared"))
            release.countDown()
            command.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    private fun server(bytes: ByteArray = archive(), block: (String, AtomicInteger) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        server.createContext("/rootfs.tar.gz") { response ->
            requests.incrementAndGet()
            response.sendResponseHeaders(200, bytes.size.toLong())
            response.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/rootfs.tar.gz", requests) }
        finally { server.stop(0) }
    }

    private fun archive(valid: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            val content = "new system".toByteArray()
            val header = ByteArray(512)
            (if (valid) "bin/sh" else "README").toByteArray().copyInto(header)
            "0000755".toByteArray().copyInto(header, 100)
            content.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            gzip.write(header)
            gzip.write(content)
            gzip.write(ByteArray(512 - content.size))
            gzip.write(ByteArray(1024))
        }
        return bytes.toByteArray()
    }
}
