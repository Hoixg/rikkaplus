package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class UnusedEnvironment(
    val root: String,
    val sizeBytes: Long,
    val fileCount: Long,
    internal val identity: String,
)

/** Only retired per-workspace Linux directories are eligible, never an active environment or project directory. */
internal class UnusedEnvironmentCleaner(
    private val baseDir: File,
    private val sharedLinuxDir: File,
    private val protectedRoots: Set<String>,
    private val validateRoot: (String) -> Unit,
) {
    fun scan(): List<UnusedEnvironment> = baseDir.listFiles().orEmpty().mapNotNull { directory ->
        if (runCatching { validateRoot(directory.name) }.isFailure) null else inspect(directory.name)
    }.sortedBy { it.root }

    fun delete(expected: UnusedEnvironment): Long {
        check(
            WorkspaceManager.isUsableRootfs(sharedLinuxDir)
        ) { "Install and verify the shared environment before cleaning old environments" }
        val current = inspect(expected.root) ?: error("This environment is no longer eligible for cleanup; scan again")
        check(
            current.identity == expected.identity
        ) { "The old environment changed after scanning; scan again before deleting it" }
        val directory = requireNotNull(candidate(expected.root))
        walk(directory, deleting = true) { _, _ -> }
        return current.sizeBytes
    }

    private fun candidate(root: String): File? {
        validateRoot(root)
        if (root in protectedRoots || Files.isSymbolicLink(baseDir.toPath())) return null
        val base = baseDir.canonicalFile
        val workspace = File(base, root)
        if (Files.isSymbolicLink(workspace.toPath()) || workspace.canonicalFile.parentFile != base) return null
        val linux = File(workspace, "linux")
        if (!Files.isDirectory(linux.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(linux.toPath())) return null
        if (linux.canonicalFile.parentFile != workspace.canonicalFile ||
            linux.canonicalFile == sharedLinuxDir.canonicalFile) {
            return null
        }
        return linux.canonicalFile
    }

    private fun inspect(root: String): UnusedEnvironment? {
        val directory = candidate(root) ?: return null
        var bytes = 0L
        var count = 0L
        val metadata = mutableListOf<String>()
        walk(directory) { path, attrs ->
            metadata += listOf(
                directory.toPath().relativize(path).toString(),
                attrs.fileKey()?.toString().orEmpty(),
                attrs.creationTime().toString(),
                attrs.lastModifiedTime().toString(),
                attrs.size().toString(),
                attrs.isDirectory.toString(),
                attrs.isSymbolicLink.toString(),
            ).joinToString("\u0000")
            if (!attrs.isDirectory) {
                bytes += attrs.size()
                count++
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        metadata.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update(0.toByte())
        }
        return UnusedEnvironment(root, bytes, count, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun walk(directory: File, deleting: Boolean = false, visit: (Path, BasicFileAttributes) -> Unit) {
        val boundary = directory.toPath()
        data class Entry(val path: Path, val directoryEnd: Boolean = false)
        val pending = ArrayDeque<Entry>()
        pending.addLast(Entry(boundary))
        while (pending.isNotEmpty()) {
            interrupted()
            val (path, directoryEnd) = pending.removeLast()
            val parent = path.parent.toFile().canonicalFile.toPath()
            check(path == boundary || parent == boundary || parent.startsWith(boundary)) {
                "Cleanup path escaped the old environment"
            }
            var attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attrs.isDirectory) {
                check(!attrs.isSymbolicLink && path.toFile().canonicalFile.toPath().startsWith(boundary)) {
                    "Cleanup directory escaped the old environment"
                }
                if (directoryEnd) {
                    Files.delete(path)
                    continue
                }
                val dir = path.toFile()
                // PRoot can leave mode-000 bind placeholders (e.g. /workspace). Repair owner access
                // BEFORE opening a directory: walkFileTree opens it before preVisitDirectory runs.
                // Only verified retired directories reach here; never chmod a symbolic link.
                if (!dir.canRead() && !dir.setReadable(true, true) ||
                    !dir.canExecute() && !dir.setExecutable(true, true) ||
                    deleting && !dir.canWrite() && !dir.setWritable(true, true)) {
                    throw IOException("Cannot access the old environment directory: $path")
                }
                // Android may derive creationTime from ctime, which chmod changes.
                attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(attrs.isDirectory && !attrs.isSymbolicLink) { "Old environment directory changed during cleanup" }
                visit(path, attrs)
                if (deleting) pending.addLast(Entry(path, directoryEnd = true))
                Files.newDirectoryStream(path).use { children ->
                    children.forEach { pending.addLast(Entry(it)) }
                }
            } else {
                check(!directoryEnd) { "Old environment directory changed during cleanup" }
                visit(path, attrs)
                if (deleting) Files.delete(path)
            }
        }
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Environment cleanup interrupted")
    }
}
