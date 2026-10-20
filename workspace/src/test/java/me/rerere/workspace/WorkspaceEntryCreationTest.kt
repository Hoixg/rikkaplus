package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceEntryCreationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manager() = WorkspaceManager(
        baseDir = temp.root,
        shellRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                error("File creation must not invoke a shell")
        },
    )

    @Test
    fun `creates an empty file and folder without a rootfs or shell`() {
        val manager = manager()
        val root = "project"
        manager.ensureWorkspace(root)
        val folder = manager.createDirectory(root, "资料目录")
        val file = manager.writeText(root, "资料目录/notes.md", "", overwrite = false)
        assertTrue(folder.isDirectory)
        assertEquals("资料目录", folder.path)
        assertFalse(file.isDirectory)
        assertEquals("资料目录/notes.md", file.path)
        assertEquals(0L, file.sizeBytes)
        assertTrue(File(manager.filesDir(root), file.path).isFile)
        assertFalse(manager.hasRootfs(root))
        assertTrue(manager.linuxDir(root).listFiles().orEmpty().isEmpty())
        assertTrue(manager.tempDir(root).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `existing files and folders are preserved when names collide`() {
        val manager = manager()
        val root = "project"
        manager.ensureWorkspace(root)
        manager.writeText(root, "keep.txt", "original")
        manager.createDirectory(root, "folder")
        manager.writeText(root, "folder/keep.txt", "child")
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeText(root, "keep.txt", "", overwrite = false)
        }
        assertThrows(IllegalArgumentException::class.java) { manager.createDirectory(root, "keep.txt") }
        assertThrows(IllegalArgumentException::class.java) { manager.createDirectory(root, "folder") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeText(root, "folder", "", overwrite = false)
        }
        assertEquals("original", manager.readText(root, "keep.txt"))
        assertEquals("child", manager.readText(root, "folder/keep.txt"))
    }

    @Test
    fun `creation cannot escape the workspace files area`() {
        val manager = manager()
        val root = "project"
        manager.ensureWorkspace(root)
        assertThrows(IllegalArgumentException::class.java) { manager.createDirectory(root, "../outside") }
        assertThrows(IllegalArgumentException::class.java) {
            manager.writeText(root, "../outside", "", overwrite = false)
        }
        assertFalse(File(manager.workspaceDir(root), "outside").exists())
    }
}
