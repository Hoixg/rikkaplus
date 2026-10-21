package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.workspace.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WorkspaceFilesBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json
    private val record = WorkspaceFilesBackup.Record("workspace-id", "项目文件", "workspace-root", 100, 200)
    private val manifest = WorkspaceFilesBackup.Manifest(workspaces = listOf(record))

    private fun write(path: String, text: String) = File(temporary.root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test fun remoteWorkspacesHaveNoLocalFileBackupRecords() {
        val local = record.toEntity()
        val remote = local.copy(id = "ssh-id", root = "ssh-root", type = WorkspaceType.SSH.name)

        val backup = WorkspaceFilesBackup.records(listOf(local, remote))

        assertEquals(listOf(record), backup.workspaces)
    }

    @Test fun archiveContainsOnlyFilesAreaAndKeepsNestedAndEmptyFolders() = runBlocking {
        write("app/workspaces/workspace-root/files/project/main.py", "print('你好')")
        write("app/workspaces/workspace-root/files/.env", "configuration")
        write("app/workspaces/workspace-root/files/.l2s.internal", "proot metadata")
        write("app/workspaces/workspace-root/linux/bin/sh", "runtime")
        write("app/workspaces/workspace-root/tmp/cache", "cache")
        write("app/workspaces/unlisted/files/private.txt", "not a registered workspace")
        File(temporary.root, "app/workspaces/workspace-root/files/empty").mkdirs()
        val archive = File(temporary.root, "backup.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            WorkspaceFilesBackup.write(zip, File(temporary.root, "app"), manifest, json)
        }
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf(
                WorkspaceFilesBackup.MANIFEST,
                "workspaces/workspace-root/files/",
                "workspaces/workspace-root/files/project/",
                "workspaces/workspace-root/files/project/main.py",
                "workspaces/workspace-root/files/.env",
                "workspaces/workspace-root/files/empty/"
            ), names)
            assertEquals("print('你好')", zip.getInputStream(
                zip.getEntry("workspaces/workspace-root/files/project/main.py")
            )
                .readBytes().decodeToString())
        }
    }

    @Test fun fileArchiveRestoresThroughExistingJournalAndLeavesRuntimeUntouched() = runBlocking {
        write("source/workspaces/workspace-root/files/a.txt", "new file")
        File(temporary.root, "source/workspaces/workspace-root/files/empty").mkdirs()
        val archive = File(temporary.root, "backup.zip")
        ZipOutputStream(archive.outputStream()).use {
            WorkspaceFilesBackup.write(it, File(temporary.root, "source"), manifest, json)
        }
        val destination = File(temporary.root, "destination")
        write("destination/workspaces/workspace-root/linux/bin/sh", "existing runtime")
        write("destination/workspaces/workspace-root/tmp/session", "existing cache")
        write("destination/workspaces/workspace-root/files/keep.txt", "other file")
        val restore = PendingRestore(File(temporary.root, "restore"), File(temporary.root, "db"), destination)
        val staging = restore.createStagingDirectory()
        val payload = File(staging, "payload")
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val target = if (entry.name == WorkspaceFilesBackup.MANIFEST) {
                    File(staging, entry.name)
                } else {
                    PendingRestore.resolveInside(File(payload, "files"), entry.name.removeSuffix("/"))
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile!!.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        assertEquals(manifest, WorkspaceFilesBackup.validatePayload(payload, json))
        restore.publish(staging)
        restore.apply { error("No settings in this fixture") }
        assertEquals("new file", File(destination, "workspaces/workspace-root/files/a.txt").readText())
        assertEquals("other file", File(destination, "workspaces/workspace-root/files/keep.txt").readText())
        assertTrue(File(destination, "workspaces/workspace-root/files/empty").isDirectory)
        assertEquals("existing runtime", File(destination, "workspaces/workspace-root/linux/bin/sh").readText())
        assertEquals("existing cache", File(destination, "workspaces/workspace-root/tmp/session").readText())
    }

    @Test fun manifestOnlyRestoresAnEmptyFilesArea() {
        write("stage/${WorkspaceFilesBackup.MANIFEST}", json.encodeToString(manifest))
        WorkspaceFilesBackup.validatePayload(File(temporary.root, "stage/payload"), json)
        assertTrue(File(temporary.root, "stage/payload/files/workspaces/workspace-root/files").isDirectory)
    }

    @Test fun rejectsRuntimePathsTraversalAndUndeclaredWorkspaces() {
        for (path in listOf(
            "workspaces/root/linux/bin/sh",
            "workspaces/root/tmp/a",
            "workspaces/root/files/../linux/a",
            "workspaces/../files/a",
            "workspaces/./files/a",
            "workspaces/root/files/a\\b",
            "/workspaces/root/files/a",
            "workspaces/root/files//a"
        )) {
            assertFalse(path, WorkspaceFilesBackup.accepts(path, isDirectory = false))
        }
        write("stage/${WorkspaceFilesBackup.MANIFEST}", json.encodeToString(manifest))
        write("stage/payload/files/workspaces/undeclared/files/a", "data")
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesBackup.validatePayload(File(temporary.root, "stage/payload"), json)
        }
    }

    @Test fun olderBackupsNeedNoWorkspaceManifest() {
        assertEquals(null, WorkspaceFilesBackup.validatePayload(File(temporary.root, "payload"), json))
    }

    @Test fun workspaceFilesRequireMatchingMetadata() {
        write("stage/payload/files/workspaces/workspace-root/files/a", "data")
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesBackup.validatePayload(File(temporary.root, "stage/payload"), json)
        }
    }

    @Test fun duplicateIdsRootsAndFutureVersionsAreRejected() {
        for (invalid in listOf(
            WorkspaceFilesBackup.Manifest(2, listOf(record)),
            WorkspaceFilesBackup.Manifest(workspaces = listOf(record, record.copy(root = "another"))),
            WorkspaceFilesBackup.Manifest(workspaces = listOf(record, record.copy(id = "another"))),
            WorkspaceFilesBackup.Manifest(workspaces = listOf(record.copy(root = "..")))
        )) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }

    @Test fun symlinksCannotPullRuntimeIntoArchiveOrRedirectRestoreToIt() = runBlocking {
        val runtime = write("app/workspaces/workspace-root/linux/bin/sh", "runtime")
        val files = File(temporary.root, "app/workspaces/workspace-root/files").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(files, "runtime-link").toPath(), runtime.toPath())
        } catch (e: Exception) {
            assumeNoException("Filesystem does not support symbolic links", e)
        }
        val archive = File(temporary.root, "backup.zip")
        ZipOutputStream(archive.outputStream()).use {
            WorkspaceFilesBackup.write(it, File(temporary.root, "app"), manifest, json)
        }
        ZipFile(archive).use { assertEquals(null, it.getEntry("workspaces/workspace-root/files/runtime-link")) }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFilesBackup.resolveTarget(
                File(temporary.root, "app"),
                "workspaces/workspace-root/files/runtime-link"
            )
        }
        Unit
    }
}
