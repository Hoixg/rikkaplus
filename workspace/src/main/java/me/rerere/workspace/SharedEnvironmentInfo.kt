package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class EnvironmentPackage(val name: String, val version: String)

data class SharedEnvironmentInfo(
    val directory: String,
    val installed: Boolean,
    val distribution: String?,
    val sizeBytes: Long,
    val rootfsSizeBytes: Long,
    val packages: List<EnvironmentPackage>,
    val commands: List<String>,
    val incomplete: Boolean,
)

/** Read existing Rootfs metadata without starting a shell, changing permissions or following mount placeholders. */
internal fun inspectSharedEnvironment(directory: File): SharedEnvironmentInfo {
    val linux = File(directory, "linux")
    var total = 0L
    var rootfs = 0L
    var incomplete = false
    fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Environment scan cancelled")
    }
    if (directory.exists()) {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                interrupted()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                interrupted()
                total += attrs.size()
                if (file.startsWith(linux.toPath())) rootfs += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                interrupted()
                incomplete = true
                return FileVisitResult.CONTINUE
            }
        })
    }
    fun read(path: String): String? {
        interrupted()
        val file = File(linux, path)
        if (!file.canonicalFile.toPath().startsWith(linux.canonicalFile.toPath()) || !file.isFile) return null
        return try {
            file.readText()
        } catch (_: IOException) {
            incomplete = true
            null
        }
    }
    fun packages(text: String?, apk: Boolean): List<EnvironmentPackage> = text.orEmpty()
        .split(Regex("""\r?\n\s*\r?\n"""))
        .mapNotNull { record ->
            val fields = record.lineSequence().filter { it.isNotBlank() && !it.first().isWhitespace() && ':' in it }
                .associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            if (!apk && fields["Status"]?.substringAfterLast(' ') != "installed") return@mapNotNull null
            val name = fields[if (apk) "P" else "Package"] ?: return@mapNotNull null
            val version = fields[if (apk) "V" else "Version"] ?: return@mapNotNull null
            EnvironmentPackage(name, version)
        }
    val installed =
        packages(read("var/lib/dpkg/status"), apk = false) + packages(read("lib/apk/db/installed"), apk = true)
    val os = read("etc/os-release") ?: read("usr/lib/os-release")
    val distribution = os?.lineSequence()?.firstOrNull { it.startsWith("PRETTY_NAME=") }
        ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    val commands = listOf("usr/local/bin", "usr/local/sbin", "root/.local/bin", "root/.cargo/bin", "root/.bun/bin")
        .flatMap { path ->
            interrupted()
            val bin = File(linux, path)
            if (!bin.canonicalFile.toPath().startsWith(linux.canonicalFile.toPath())) return@flatMap emptyList()
            bin.listFiles().orEmpty().filter {
                !it.name.startsWith('.') &&
                    (it.isFile || Files.isSymbolicLink(it.toPath()))
            }
                .map { "/$path/${it.name}" }
        }.distinct().sorted()
    return SharedEnvironmentInfo(
        directory = directory.absolutePath,
        installed = WorkspaceManager.isUsableRootfs(linux),
        distribution = distribution,
        sizeBytes = total,
        rootfsSizeBytes = rootfs,
        packages = installed.distinctBy { it.name }.sortedBy { it.name },
        commands = commands,
        incomplete = incomplete,
    )
}
