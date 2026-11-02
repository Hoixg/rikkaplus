package me.rerere.workspace

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files

/** Android/Linux permissions are essential here: Windows JVM tests cannot reproduce PRoot's mode-000 directories. */
@RunWith(AndroidJUnit4::class)
class UnusedEnvironmentCleanerInstrumentedTest {
    @get:Rule val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private val restricted = mutableListOf<File>()

    @After fun restoreTestPermissions() {
        restricted.forEach { if (it.exists()) Os.chmod(it.absolutePath, 0b111_000_000) }
    }

    private fun manager() = WorkspaceManager(temp.newFolder(), environmentDir = temp.newFolder()).also {
        File(it.sharedLinuxDir, "bin/sh").apply { parentFile!!.mkdirs(); writeText("shared") }
    }

    private fun old(manager: WorkspaceManager, root: String): File {
        manager.ensureWorkspace(root)
        return File(manager.workspaceDir(root), "linux").apply { mkdirs() }
    }

    private fun restrict(directory: File, mode: Int = 0) {
        restricted += directory
        Os.chmod(directory.absolutePath, mode)
    }

    @Test fun scanAndDeleteModeZeroBindPlaceholdersAndNestedDirectories() {
        val manager = manager()
        val linux = old(manager, "old")
        val placeholder = File(linux, "workspace").apply { mkdirs() }
        val nested = File(linux, "root/cache").apply { mkdirs() }
        File(nested, "old-file").writeText("old")
        manager.writeText("old", "project.txt", "keep project")
        restrict(placeholder)
        // Verify the test really reproduces the error before using the cleaner.
        assertThrows(AccessDeniedException::class.java) { Files.newDirectoryStream(placeholder.toPath()).use {} }
        restrict(linux)
        restrict(nested)

        val preview = manager.unusedEnvironments(emptySet()).single()
        assertEquals(3L, preview.sizeBytes)
        assertEquals(1L, preview.fileCount)
        // A second scan must have the same identity after access has been repaired.
        assertEquals(preview, manager.unusedEnvironments(emptySet()).single())
        assertEquals(3L, manager.deleteUnusedEnvironment(preview, emptySet()))
        assertFalse(linux.exists())
        assertEquals("keep project", manager.readText("old", "project.txt"))
        assertEquals("shared", File(manager.sharedLinuxDir, "bin/sh").readText())
    }

    @Test fun scanRepairsExecutePermissionBeforeReadingChildAttributes() {
        val manager = manager()
        val linux = old(manager, "old")
        val readOnly = File(linux, "root").apply { mkdirs() }
        File(readOnly, "old-file").writeText("old")
        restrict(readOnly, 0b100_000_000) // 0400: readable, but cannot stat children or delete them.

        val preview = manager.unusedEnvironments(emptySet()).single()
        assertEquals(3L, preview.sizeBytes)
        manager.deleteUnusedEnvironment(preview, emptySet())
        assertFalse(linux.exists())
    }

    @Test fun protectedDirectoriesAndSymlinkTargetsAreNotChmoddedOrDeleted() {
        val manager = manager()
        val privateLinux = old(manager, "private")
        val sshLinux = old(manager, "ssh")
        manager.registerWorkspace("private", true)
        val linux = old(manager, "old")
        val outside = temp.newFolder()
        File(outside, "important").writeText("keep")
        Files.createSymbolicLink(File(linux, "outside").toPath(), outside.toPath())
        restrict(privateLinux)
        restrict(sshLinux)
        restrict(outside)

        val preview = manager.unusedEnvironments(setOf("ssh")).single()
        manager.deleteUnusedEnvironment(preview, setOf("ssh"))
        listOf(privateLinux, sshLinux, outside).forEach {
            assertEquals(0, Os.lstat(it.absolutePath).st_mode and 0b111_111_111)
        }
        Os.chmod(outside.absolutePath, 0b111_000_000)
        assertEquals("keep", File(outside, "important").readText())
    }
}
