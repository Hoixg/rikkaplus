package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A safe path reference into the workspace files area. */
data class WorkspacePathReference(val path: String)

private const val WORKSPACE_PREFIX = "/workspace"

fun parseWorkspacePathReference(source: String): WorkspacePathReference? {
    val value = source.trim().removeSurrounding("<", ">")
        .substringBefore('#')
        .substringBefore('?')
    val filePath = when {
        value.startsWith("sandbox:", ignoreCase = true) -> {
            val sandboxPath = value.substringAfter(':')
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .replace('\\', '/')
            val pathWithoutLeadingSlashes = sandboxPath.trimStart('/')
            if (pathWithoutLeadingSlashes.startsWith("workspace/", ignoreCase = true) ||
                pathWithoutLeadingSlashes.equals("workspace", ignoreCase = true)
            ) "/$pathWithoutLeadingSlashes" else sandboxPath
        }
        value.startsWith("file:///", ignoreCase = true) -> value.substring(7)
        else -> value
    }
    val decoded = runCatching {
        URLDecoder.decode(filePath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrNull() ?: return null
    val normalized = decoded.replace('\\', '/')
    if (!normalized.equals(WORKSPACE_PREFIX, ignoreCase = true) &&
        !normalized.startsWith("$WORKSPACE_PREFIX/", ignoreCase = true)
    ) return null

    val relative = normalized.substring(WORKSPACE_PREFIX.length).trimStart('/')
    val segments = mutableListOf<String>()
    for (segment in relative.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> return null
            else -> segments += segment
        }
    }
    return WorkspacePathReference(segments.joinToString("/"))
}

fun WorkspacePathReference.parentPath(): String = path.substringBeforeLast('/', "")

private val PLAIN_WORKSPACE_PATH = Regex(
    "(?<![\\w\"'(/\\[])(?:file://)?/workspace(?:/[^\\s<>()\\]]+)?",
    RegexOption.IGNORE_CASE,
)
private val SANDBOX_WORKSPACE_PATH = Regex(
    "(?<![\\w\"'(/\\[])sandbox:(?:\\[)?(?:/{1,3})workspace(?:/[^\\s<>()\\]]+)?(?:\\])?",
    RegexOption.IGNORE_CASE,
)
private val INLINE_CODE_SPAN = Regex("`+[^`\\n]*`+")
private val MARKDOWN_LINK = Regex("!?\\[[^\\]]*]\\([^)]*\\)")

/** Turns bare workspace paths into markdown links without touching fenced code blocks. */
fun linkifyWorkspacePaths(text: String): String = buildString {
    var inFence = false
    text.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            append(line)
        } else if (inFence) {
            append(line)
        } else {
            append(linkifyWorkspacePathsInLine(line))
        }
    }
}

private fun linkifyWorkspacePathsInLine(line: String): String {
    val protectedRanges = (INLINE_CODE_SPAN.findAll(line) + MARKDOWN_LINK.findAll(line))
        .map { it.range }
        .toList()
    val matches = (SANDBOX_WORKSPACE_PATH.findAll(line) + PLAIN_WORKSPACE_PATH.findAll(line))
        .sortedBy { it.range.first }
    return buildString {
        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start < cursor) continue
            append(line.substring(cursor, start))
            val rawPath = match.value
            val isProtected = protectedRanges.any { range -> start in range || match.range.last in range }
            if (isProtected) {
                append(rawPath)
                cursor = endExclusive
                continue
            }

            val path = rawPath.trimEnd('.', ',', ';', ':')
            val trailing = rawPath.substring(path.length)
            val reference = parseWorkspacePathReference(path)
            if (reference == null) {
                append(rawPath)
            } else if (path.startsWith("sandbox:", ignoreCase = true)) {
                val workspacePath = reference.path.takeIf(String::isNotEmpty)
                    ?.let { "/workspace/$it" } ?: "/workspace"
                append("[$workspacePath]($workspacePath)$trailing")
            } else {
                append("[$path]($path)$trailing")
            }
            cursor = endExclusive
        }
        append(line.substring(cursor))
    }
}
