package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.data.model.ScheduledTaskFile
import me.rerere.rikkahub.data.model.isValidScheduledTaskCreatedFileName
import me.rerere.rikkahub.data.model.isScheduledTaskTextFile
import me.rerere.rikkahub.data.model.parseScheduledTaskFiles
import me.rerere.rikkahub.data.model.scheduledTaskFiles
import me.rerere.rikkahub.data.repository.ScheduledTaskRepository
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val MAX_TEXT_BYTES = 256 * 1024

internal fun scheduledTaskFileId(taskId: String, uri: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$taskId\u0000$uri".toByteArray(StandardCharsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

internal fun allowedScheduledTaskFileActions(task: ScheduledTaskEntity): List<String> =
    if (!task.filesEnabled) emptyList() else buildList {
        val hasFiles = scheduledTaskFiles(task).isNotEmpty()
        val canCreate = task.allowFileCreate && task.creationFolderUri != null
        if (!hasFiles && !canCreate) return@buildList
        add("list")
        if (canCreate) add("create")
        if (task.allowFileRead) add("read")
        if (task.allowFileWrite) add("write")
        if (task.allowFileDelete) add("delete")
    }.takeIf { it.size > 1 } ?: emptyList()

fun createScheduledTaskFileTools(
    context: Context,
    repository: ScheduledTaskRepository,
    task: ScheduledTaskEntity,
): List<Tool> {
    val actions = allowedScheduledTaskFileActions(task)
    if (actions.isEmpty()) return emptyList()
    return listOf(Tool(
        name = "scheduled_task_file",
        description = "Access only this scheduled task's text files. Call list for file IDs. " +
            "create makes a new text file in this task's configured folder and adds it to this task's file list. " +
            "read returns UTF-8 text; write replaces the entire file with UTF-8 text; delete removes the selected file. " +
            "Available actions: ${actions.joinToString()}.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { actions.forEach(::add) })
                    })
                    put("file_id", buildJsonObject { put("type", "string"); put("description", "Stable ID returned by list") })
                    put("name", buildJsonObject { put("type", "string"); put("description", "New text file name for create") })
                    put("content", buildJsonObject { put("type", "string"); put("description", "Replacement UTF-8 text for write") })
                },
                required = listOf("action"),
            )
        },
        execute = { input ->
            withContext(Dispatchers.IO) {
                fun result(value: String) = listOf(UIMessagePart.Text(value))
                fun failure(message: String) = result(buildJsonObject { put("error", message) }.toString())
                try {
                    val obj = input.jsonObject
                    val action = obj["action"]?.jsonPrimitive?.contentOrNull
                    val runId = task.activeRunId ?: return@withContext failure("Scheduled task is no longer running")
                    val current = repository.getById(task.id)
                        ?.takeIf { it.activeRunId == runId }
                        ?: return@withContext failure("Scheduled task is no longer running")
                    val currentActions = allowedScheduledTaskFileActions(current)
                    if (action !in currentActions) return@withContext failure("Action is not enabled for this task")
                    val files = scheduledTaskFiles(current)
                    if (action == "list") return@withContext result(buildJsonObject {
                        put("files", buildJsonArray {
                            files.forEach { file ->
                                addJsonObject { put("file_id", scheduledTaskFileId(task.id, file.uri)); put("name", file.name) }
                            }
                        })
                        put("allowed_actions", buildJsonArray { currentActions.filterNot { it == "list" }.forEach(::add) })
                    }.toString())

                    if (action == "create") {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext failure("name is required")
                        if (!isValidScheduledTaskCreatedFileName(name)) {
                            return@withContext failure("Use a plain text file name with a supported extension")
                        }
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val bytes = content.toByteArray(StandardCharsets.UTF_8)
                        if (bytes.size > MAX_TEXT_BYTES) return@withContext failure("Text exceeds 256 KiB limit")
                        val folderUri = current.creationFolderUri?.let(Uri::parse)
                            ?: return@withContext failure("Creation folder is not configured")
                        val folderGrant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == folderUri }
                        if (folderGrant?.isReadPermission != true || !folderGrant.isWritePermission) {
                            return@withContext failure("Creation folder permission is no longer available")
                        }
                        val folder = DocumentFile.fromTreeUri(context, folderUri)
                            ?: return@withContext failure("Creation folder is unavailable")
                        if (!folder.exists() || !folder.isDirectory || !folder.canWrite()) {
                            return@withContext failure("Creation folder is not writable")
                        }
                        if (folder.findFile(name) != null) return@withContext failure("File already exists")
                        val created = folder.createFile("text/plain", name)
                            ?: return@withContext failure("Unable to create text file")
                        var recorded = false
                        try {
                            val createdName = created.name.orEmpty()
                            if (createdName != name) {
                                return@withContext failure("Document provider changed the file name")
                            }
                            context.contentResolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
                                ?: return@withContext failure("Unable to write new file")
                            val file = ScheduledTaskFile(created.uri.toString(), createdName, folderUri.toString())
                            if (!repository.recordCreatedFile(task.id, runId, file)) {
                                return@withContext failure("Unable to add new file to this task")
                            }
                            recorded = true
                            return@withContext result(buildJsonObject {
                                put("created", true); put("file_id", scheduledTaskFileId(task.id, file.uri)); put("name", createdName)
                            }.toString())
                        } finally {
                            if (!recorded) runCatching { created.delete() }
                        }
                    }

                    val fileId = obj["file_id"]?.jsonPrimitive?.contentOrNull
                    val selected = files.firstOrNull { scheduledTaskFileId(task.id, it.uri) == fileId }
                        ?: return@withContext failure("Unknown file_id")
                    if (!isScheduledTaskTextFile(selected.name)) return@withContext failure("Only text files are supported")
                    val uri = Uri.parse(selected.uri)
                    val generated = parseScheduledTaskFiles(current.createdFilesJson).any { it.uri == selected.uri }
                    val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                        ?: selected.parentTreeUri?.takeIf { generated }?.let(Uri::parse)?.let { folderUri ->
                            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == folderUri }
                        }
                        ?: return@withContext failure("File permission is no longer available")
                    val needsWrite = action == "write" || action == "delete"
                    if (needsWrite && !grant.isWritePermission || !needsWrite && !grant.isReadPermission) {
                        return@withContext failure("Required Android file permission is missing")
                    }
                    val document = DocumentFile.fromSingleUri(context, uri)
                        ?: return@withContext failure("Selected file is unavailable")
                    if (!document.exists() || !document.isFile) return@withContext failure("Selected file no longer exists")
                    if (!isScheduledTaskTextFile(document.name ?: selected.name)) {
                        return@withContext failure("Selected file is no longer a supported text file")
                    }
                    when (action) {
                        "read" -> {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (output.size() <= MAX_TEXT_BYTES) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: return@withContext failure("Unable to read selected file")
                            if (bytes.size > MAX_TEXT_BYTES) return@withContext failure("Text file exceeds 256 KiB limit")
                            val content = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString()
                            result(buildJsonObject { put("name", selected.name); put("content", content) }.toString())
                        }
                        "write" -> {
                            val content = obj["content"]?.jsonPrimitive?.contentOrNull
                                ?: return@withContext failure("content is required")
                            val bytes = content.toByteArray(StandardCharsets.UTF_8)
                            if (bytes.size > MAX_TEXT_BYTES) return@withContext failure("Text exceeds 256 KiB limit")
                            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                                ?: return@withContext failure("Unable to write selected file")
                            result(buildJsonObject { put("written", true); put("name", selected.name) }.toString())
                        }
                        "delete" -> {
                            if (!document.delete()) return@withContext failure("Unable to delete selected file")
                            if (generated) runCatching { repository.forgetCreatedFile(task.id, selected.uri, runId) }
                            result(buildJsonObject { put("deleted", true); put("name", selected.name) }.toString())
                        }
                        else -> failure("Unknown action")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure(error.message ?: "File operation failed")
                }
            }
        },
    ))
}
