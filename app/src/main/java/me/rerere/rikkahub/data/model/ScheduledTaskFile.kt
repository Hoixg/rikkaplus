package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity

@Serializable
data class ScheduledTaskFile(val uri: String, val name: String, val parentTreeUri: String? = null)

private val supportedTextExtensions = setOf(
    "md", "markdown", "txt", "text", "csv", "json", "jsonl", "yaml", "yml",
    "xml", "html", "css", "js", "ts", "py", "kt", "java", "sh", "log", "ini", "toml",
)

fun isScheduledTaskTextFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in supportedTextExtensions

fun isValidScheduledTaskCreatedFileName(name: String): Boolean =
    name == name.trim() && name.length in 1..128 &&
        name.none { it == '/' || it == '\\' || it.isISOControl() } &&
        isScheduledTaskTextFile(name)

fun parseScheduledTaskFiles(value: String): List<ScheduledTaskFile> =
    Json.decodeFromString(value)

fun encodeScheduledTaskFiles(files: List<ScheduledTaskFile>): String = Json.encodeToString(files)

fun scheduledTaskFiles(task: ScheduledTaskEntity): List<ScheduledTaskFile> =
    (parseScheduledTaskFiles(task.filesJson) + parseScheduledTaskFiles(task.createdFilesJson)).distinctBy { it.uri }
