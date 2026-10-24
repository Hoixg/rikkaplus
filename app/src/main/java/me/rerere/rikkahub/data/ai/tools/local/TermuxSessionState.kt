@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.data.ai.tools.local

import androidx.collection.LruCache
import java.util.UUID

internal data class TermuxExecutionState(
    val status: String,
    val exitCode: Int? = null,
    val signal: String? = null,
) {
    val finished: Boolean get() = status == "completed" || status == "exited"
}

internal const val TERMUX_STATE_MARKER = "__RIKKAHUB_PANE_STATE__"

/** A quiet screen is never evidence of completion. Only the shell hook / dead pane is. */
internal fun parseTermuxState(line: String): TermuxExecutionState {
    val fields = line.removePrefix(TERMUX_STATE_MARKER).trimEnd('\r', '\n').split('\t')
    if (fields.size < 5) return TermuxExecutionState("unknown")
    val dead = fields[0] == "1"
    val signal = fields[2].takeIf { it.isNotBlank() }
    return when {
        dead -> TermuxExecutionState("exited", fields[1].toIntOrNull(), signal)
        fields[3] == "idle" && fields[4].toIntOrNull() != null -> TermuxExecutionState(
            "completed",
            fields[4].toIntOrNull()
        )
        fields[3] == "pending" -> TermuxExecutionState("input_pending")
        fields[3] == "running" -> TermuxExecutionState("running")
        else -> TermuxExecutionState("unknown")
    }
}

/** Runs in the managed bash only; SSH/REPL input is passed through unchanged. No log files. */
internal fun managedTermuxShell(
    tmuxPath: String = "$TERMUX_BIN/tmux",
    bashPath: String = "$TERMUX_BIN/bash",
): String {
    val tmux = shellSingleQuote(tmuxPath)
    val beforeCommand = """${'$'}(command $tmux set-option -p -t "${'$'}TMUX_PANE" @rk_state running \; set-option -pu -t "${'$'}TMUX_PANE" @rk_exit >/dev/null)"""
    val rc = """
        [[ ! -f ~/.bashrc ]] || source ~/.bashrc
        __rk_prompt() {
            local __rk_ec=${'$'}?
            command $tmux set-option -p -t "${'$'}TMUX_PANE" @rk_exit "${'$'}__rk_ec" \; set-option -p -t "${'$'}TMUX_PANE" @rk_state idle >/dev/null
            return "${'$'}__rk_ec"
        }
        PROMPT_COMMAND=(__rk_prompt "${'$'}{PROMPT_COMMAND[@]}")
        PS0=${shellSingleQuote(beforeCommand)}"${'$'}{PS0-}"
    """.trimIndent()
    return """exec ${shellSingleQuote(bashPath)} --noprofile --rcfile <(printf '%s\n' ${shellSingleQuote(rc)}) -i"""
}

internal data class TermuxOutputDelta(
    val output: String,
    val cursor: String,
    val reset: Boolean,
    val truncated: Boolean,
)

/** Ephemeral terminal snapshots, bounded to 2 MiB. Tool results remain in their own chat. */
internal class TermuxOutputCache(maxSnapshots: Int = 32) {
    private data class Snapshot(val session: String, val text: String)

    private val snapshots = LruCache<String, Snapshot>(maxSnapshots)

    @Synchronized
    fun read(session: String, cursor: String?, screen: String, limit: Int): TermuxOutputDelta {
        val previous = cursor?.let { snapshots.get(it) }?.takeIf { it.session == session }
        val normalized = screen.trimEnd('\n', ' ', '\t')
        val current = takeLastUtf8Bytes(normalized, 64 * 1024)
        val delta = previous?.let { terminalDelta(it.text, current) }
        val reset = cursor != null && (previous == null || delta == null)
        val text = delta ?: current
        val output = takeLastUtf8Bytes(text, limit)
        val token = UUID.randomUUID().toString()
        snapshots.put(token, Snapshot(session, current))
        return TermuxOutputDelta(output, token, reset, output != text || current != normalized)
    }

    @Synchronized
    fun remove(session: String) {
        snapshots.snapshot().filterValues { it.session == session }.keys.forEach { snapshots.remove(it) }
    }
}

/** Screen deltas, not a lossless byte stream: rewrites/scrollback loss explicitly reset. */
internal fun terminalDelta(previous: String, current: String): String? {
    if (current.startsWith(previous)) return current.substring(previous.length)
    // Align whole lines when older scrollback has rolled out of the capture window.
    var start = previous.indexOf('\n') + 1
    while (start > 0 && start < previous.length) {
        val length = previous.length - start
        if (current.regionMatches(0, previous, start, length)) return current.substring(length)
        start = previous.indexOf('\n', start).let { if (it < 0) 0 else it + 1 }
    }
    return null
}
