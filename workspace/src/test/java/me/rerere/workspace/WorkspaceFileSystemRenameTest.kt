package me.rerere.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileSystemRenameTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun `renaming a folder preserves its children`() {
        val root = tempFolder.newFolder("workspace")
        val source = File(root, "src/sub").apply { mkdirs() }
        File(source, "note.txt").writeText("content")

        val renamed = WorkspaceFileSystem().renameEntry(root, "src", "docs")

        assertEquals("docs", renamed.path)
        assertTrue(renamed.isDirectory)
        assertTrue(File(root, "docs/sub/note.txt").isFile)
        assertFalse(File(root, "src").exists())
    }

    @Test
    fun `renaming a folder refuses an existing target`() {
        val root = tempFolder.newFolder("workspace")
        File(root, "src").mkdir()
        File(root, "docs").mkdir()

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSystem().renameEntry(root, "src", "docs")
        }
        assertTrue(File(root, "src").isDirectory)
        assertTrue(File(root, "docs").isDirectory)
    }
}
