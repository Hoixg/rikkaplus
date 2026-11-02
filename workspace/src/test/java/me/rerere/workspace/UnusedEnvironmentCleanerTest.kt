package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class UnusedEnvironmentCleanerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun manager(installed: Boolean = true): WorkspaceManager = WorkspaceManager(
        temp.newFolder(), environmentDir = temp.newFolder(),
    ).also { if (installed) File(it.sharedLinuxDir, "bin/sh").apply { parentFile!!.mkdirs(); writeText("shared") } }

    private fun old(manager: WorkspaceManager, root: String): File {
        manager.ensureWorkspace(root)
        return File(manager.workspaceDir(root), "linux").apply {
            mkdirs()
            File(this, "root/private.txt").apply { parentFile!!.mkdirs(); writeText("old personal file") }
        }
    }

    @Test fun `cleaning a retired root preserves the shared system projects and workspace temp`() {
        val manager = manager()
        val directory = old(manager, "old")
        manager.writeText("old", "project.txt", "project")
        File(manager.tempDir("old"), "keep").writeText("temp")
        val preview = manager.unusedEnvironments(emptySet()).single()
        assertEquals("old", preview.root)
        assertEquals("old personal file".toByteArray().size.toLong(), preview.sizeBytes)
        assertEquals(preview.sizeBytes, manager.deleteUnusedEnvironment(preview, emptySet()))
        assertFalse(directory.exists())
        assertEquals("shared", File(manager.sharedLinuxDir, "bin/sh").readText())
        assertEquals("project", manager.readText("old", "project.txt"))
        assertEquals("temp", File(manager.tempDir("old"), "keep").readText())
        assertTrue(manager.unusedEnvironments(emptySet()).isEmpty())
    }

    @Test fun `private environments and protected SSH roots are neither listed nor deleted`() {
        val manager = manager()
        old(manager, "shared-old")
        old(manager, "private")
        old(manager, "ssh")
        val previews = manager.unusedEnvironments(emptySet()).associateBy { it.root }
        manager.registerWorkspace("private", true)
        assertEquals(listOf("shared-old"), manager.unusedEnvironments(setOf("ssh")).map { it.root })
        assertTrue(runCatching { manager.deleteUnusedEnvironment(previews.getValue("private"), emptySet()) }.isFailure)
        assertTrue(runCatching { manager.deleteUnusedEnvironment(previews.getValue("ssh"), setOf("ssh")) }.isFailure)
        assertTrue(File(manager.workspaceDir("private"), "linux/root/private.txt").exists())
        assertTrue(File(manager.workspaceDir("ssh"), "linux/root/private.txt").exists())
    }

    @Test fun `old directories without a workspace record can be inspected and removed`() {
        val manager = manager()
        val orphan = old(manager, "removed-workspace")
        val preview = manager.unusedEnvironments(emptySet()).single()
        manager.deleteUnusedEnvironment(preview, emptySet())
        assertFalse(orphan.exists())
        assertTrue(manager.workspaceDir("removed-workspace").exists())
    }

    @Test fun `a changed preview cannot authorize deletion of newly added files`() {
        val manager = manager()
        val directory = old(manager, "old")
        val preview = manager.unusedEnvironments(emptySet()).single()
        File(directory, "new-important-file").writeText("keep")
        assertTrue(runCatching { manager.deleteUnusedEnvironment(preview, emptySet()) }.isFailure)
        assertEquals("keep", File(directory, "new-important-file").readText())
        assertTrue(File(directory, "root/private.txt").exists())
    }

    @Test fun `cleanup requires a working shared environment and rejects stale repeated deletes`() {
        val manager = manager(installed = false)
        val directory = old(manager, "old")
        val preview = manager.unusedEnvironments(emptySet()).single()
        assertTrue(runCatching { manager.deleteUnusedEnvironment(preview, emptySet()) }.isFailure)
        assertTrue(directory.exists())
        File(manager.sharedLinuxDir, "bin/sh").apply { parentFile!!.mkdirs(); writeText("shared") }
        manager.deleteUnusedEnvironment(preview, emptySet())
        assertTrue(runCatching { manager.deleteUnusedEnvironment(preview, emptySet()) }.isFailure)
    }

    @Test fun `invalid root names cannot escape the workspace boundary`() {
        val manager = manager()
        listOf("..", ".", ".environment", "../outside", "root/linux", "/outside").forEach { root ->
            val forged = UnusedEnvironment(root, 0, 0, "invalid")
            assertTrue(runCatching { manager.deleteUnusedEnvironment(forged, emptySet()) }.isFailure)
        }
        assertEquals("shared", File(manager.sharedLinuxDir, "bin/sh").readText())
    }

    @Test fun `a shared environment located under the base directory is never a cleanup candidate`() {
        val base = temp.newFolder()
        val manager = WorkspaceManager(base, environmentDir = File(base, "shared-system"))
        File(manager.sharedLinuxDir, "bin/sh").apply { parentFile!!.mkdirs(); writeText("shared") }
        assertTrue(manager.unusedEnvironments(emptySet()).isEmpty())
    }

    @Test fun `symbolic links are never followed into another environment or outside the old tree`() {
        val manager = manager()
        val directory = old(manager, "old")
        val outside = temp.newFolder()
        File(outside, "keep").writeText("important")
        try {
            Files.createSymbolicLink(File(directory, "outside").toPath(), outside.toPath())
            manager.ensureWorkspace("alias")
            Files.createSymbolicLink(File(manager.workspaceDir("alias"), "linux").toPath(), manager.sharedLinuxDir.toPath())
        } catch (error: Exception) { assumeNoException("Host cannot create symbolic links", error) }
        val preview = manager.unusedEnvironments(emptySet()).single()
        assertEquals("old", preview.root)
        manager.deleteUnusedEnvironment(preview, emptySet())
        assertEquals("important", File(outside, "keep").readText())
        assertEquals("shared", File(manager.sharedLinuxDir, "bin/sh").readText())
    }

    @Test fun `dangling and looping Linux symlinks do not interrupt cleanup`() {
        val manager = manager()
        val directory = old(manager, "old")
        try {
            Files.createSymbolicLink(File(directory, "workspace").toPath(), temp.root.toPath().resolve("missing-project"))
            Files.createSymbolicLink(File(directory, "loop").toPath(), File(directory, "loop").toPath())
        } catch (error: Exception) { assumeNoException("Host cannot create symbolic links", error) }
        val preview = manager.unusedEnvironments(emptySet()).single()
        manager.deleteUnusedEnvironment(preview, emptySet())
        assertFalse(directory.exists())
        assertEquals("shared", File(manager.sharedLinuxDir, "bin/sh").readText())
    }
}
