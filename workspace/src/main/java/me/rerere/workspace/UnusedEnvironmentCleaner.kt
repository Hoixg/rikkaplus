package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
        val boundary = directory.canonicalFile.toPath()
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            private fun checkParent(path: Path) {
                interrupted()
                val parent = path.parent.toFile().canonicalFile.toPath()
                check(
                    path == boundary || parent == boundary || parent.startsWith(boundary)
                ) { "Cleanup path escaped the old environment" }
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkParent(dir)
                check(
                    dir.toFile().canonicalFile.toPath().startsWith(boundary)
                ) { "Cleanup directory escaped the old environment" }
                // Some installed packages leave read-only directories. Never chmod or follow symbolic links.
                dir.toFile().setWritable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkParent(file)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                checkParent(dir)
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
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
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            private fun record(path: Path, attrs: BasicFileAttributes) {
                interrupted()
                metadata += listOf(
                    directory.toPath().relativize(path).toString(),
                    attrs.fileKey()?.toString().orEmpty(),
                    attrs.creationTime().toString(),
                    attrs.lastModifiedTime().toString(),
                    attrs.size().toString(),
                    attrs.isDirectory.toString(),
                    attrs.isSymbolicLink.toString(),
                ).joinToString("\u0000")
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(
                    dir.toFile().canonicalFile.toPath().startsWith(directory.toPath())
                ) { "Scan directory escaped the old environment" }
                record(dir, attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(file, attrs)
                bytes += attrs.size()
                count++
                return FileVisitResult.CONTINUE
            }
        })
        val digest = MessageDigest.getInstance("SHA-256")
        metadata.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update(0.toByte())
        }
        return UnusedEnvironment(root, bytes, count, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Environment cleanup interrupted")
    }
}
