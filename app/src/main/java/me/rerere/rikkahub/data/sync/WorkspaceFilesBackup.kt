package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only the FILES storage area; linux, PRoot state and temporary directories are excluded. */
internal object WorkspaceFilesBackup {
    const val DIRECTORY = "workspaces"
    const val MANIFEST = "workspace_files.json"
    private val rootName = Regex("[A-Za-z0-9._-]+")

    @Serializable
    data class Manifest(val version: Int = 1, val workspaces: List<Record>) {
        fun validate() {
            require(version == 1) { "Unsupported workspace file backup version: $version" }
            require(workspaces.map { it.id }.distinct().size == workspaces.size) { "Duplicate workspace id" }
            require(workspaces.map { it.root }.distinct().size == workspaces.size) { "Duplicate workspace root" }
            workspaces.forEach {
                require(it.id.isNotBlank() && it.name.isNotBlank() && validRoot(it.root)) {
                    "Invalid workspace metadata"
                }
            }
        }
    }

    @Serializable
    data class Record(
        val id: String,
        val name: String,
        val root: String,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toEntity() = WorkspaceEntity(
            id = id,
            name = name,
            root = root,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    fun records(workspaces: List<WorkspaceEntity>) = Manifest(workspaces = workspaces.map {
        Record(it.id, it.name, it.root, it.createdAt, it.updatedAt)
    })

    suspend fun write(zip: ZipOutputStream, filesDir: File, manifest: Manifest, json: Json) {
        manifest.validate()
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        val base = File(filesDir, DIRECTORY)
        require(!Files.isSymbolicLink(base.toPath())) { "Workspace directory must not be a symbolic link" }
        for (workspace in manifest.workspaces) {
            currentCoroutineContext().ensureActive()
            val root = PendingRestore.resolveInside(base, workspace.root)
            val directory = File(root, "files")
            require(
                !Files.isSymbolicLink(File(base, workspace.root).toPath()) &&
                    !Files.isSymbolicLink(directory.toPath())
            ) { "Workspace files must not be a symbolic link" }
            val prefix = "$DIRECTORY/${workspace.root}/files"
            // An empty file area still needs a directory on restore, including a workspace with no files yet.
            zip.putNextEntry(ZipEntry("$prefix/"))
            zip.closeEntry()
            if (!directory.exists()) continue
            require(directory.isDirectory) { "Workspace files area is not a directory" }
            val contents = directory.walkTopDown().onEnter {
                !Files.isSymbolicLink(it.toPath()) && !it.name.startsWith(".l2s.")
            }.onFail { _, error -> throw error }
            for (file in contents) {
                currentCoroutineContext().ensureActive()
                if (file == directory || file.name.startsWith(".l2s.") || Files.isSymbolicLink(file.toPath())) continue
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                PendingRestore.resolveInside(directory, relative)
                val name = "$prefix/$relative"
                if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                } else if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Accept only paths that can belong to the Files page, including directory entries. */
    fun accepts(name: String, isDirectory: Boolean): Boolean {
        val path = name.removeSuffix("/")
        val parts = path.split('/')
        return parts.size >= 3 && parts[0] == DIRECTORY && validRoot(parts[1]) && parts[2] == "files" &&
            (parts.size > 3 || isDirectory) && parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it }
    }

    fun resolveTarget(filesDir: File, path: String): File {
        require(accepts(path, isDirectory = true)) { "Invalid workspace file restore path" }
        val parts = path.split('/')
        val base = File(filesDir, DIRECTORY)
        val root = File(base, parts[1])
        val directory = File(root, "files")
        require(listOf(base, root, directory).none { Files.isSymbolicLink(it.toPath()) }) {
            "Workspace restore target must not be a symbolic link"
        }
        PendingRestore.resolveInside(filesDir, "$DIRECTORY/${parts[1]}/files")
        var target = directory
        for (component in parts.drop(3)) {
            target = File(target, component)
            require(!Files.isSymbolicLink(target.toPath())) { "Workspace file restore cannot follow symbolic links" }
        }
        return if (parts.size ==
            3) {
            directory
        } else {
            PendingRestore.resolveInside(directory, parts.drop(3).joinToString("/"))
        }
    }

    fun validatePayload(payload: File, json: Json): Manifest? {
        val file = File(payload.parentFile, MANIFEST)
        val directory = File(payload, "files/$DIRECTORY")
        require(file.isFile || !directory.exists()) { "Workspace file backup is missing its metadata" }
        if (!file.isFile) return null // Existing backups have neither the manifest nor workspace files.
        val manifest = json.decodeFromString<Manifest>(file.readText()).also { it.validate() }
        val roots = manifest.workspaces.map { it.root }.toSet()
        require(directory.listFiles().orEmpty().all { it.name in roots }) { "Unknown workspace in file backup" }
        // Empty workspaces may have metadata only; initialize their file area without a runtime.
        for (workspace in manifest.workspaces) {
            val target = PendingRestore.resolveInside(directory, "${workspace.root}/files")
            check(target.isDirectory || target.mkdirs()) { "Cannot stage workspace files" }
        }
        return manifest
    }

    private fun validRoot(root: String) = rootName.matches(root) && root != "." && root != ".."
}
