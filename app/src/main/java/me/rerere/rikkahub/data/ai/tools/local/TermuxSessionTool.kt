@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TermuxConfig
import java.util.UUID

internal const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"

private const val DEFAULT_COLS = 200
private const val DEFAULT_ROWS = 50
private const val DEFAULT_READ_LINES = 200
private const val DEFAULT_TIMEOUT_S = 20
private const val MAX_TIMEOUT_S = 600
private const val SETTLE_MS = 600L
private const val POLL_INTERVAL_MS = 200L
private const val MAX_SESSIONS = 8
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val INSTALL_TIMEOUT_MS = 180_000L

// Idle sessions are never explicitly killed by the model, so they would otherwise pin the
// MAX_SESSIONS budget forever. Reap any rk_ session whose tmux session_activity is older than
// this before enforcing the slot cap. 6h is long enough to leave a genuinely in-use shell
// (ssh, a REPL, a watched build) alone while clearing forgotten ones.
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000

/** Builds the argv passed to the tmux executable for each session operation. Pure. */
internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "rk_$id" else "rk_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int, rows: Int): Array<String> =
        arrayOf("new-session", "-d", "-s", session, "-x", cols.toString(), "-y", rows.toString())

    // -l sends the text literally (no tmux key-name interpretation); -- ends option parsing.
    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", session, "-l", "--", text)

    // Each element is a tmux key name (e.g. "C-c", "Enter", "Up", "Tab").
    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", session) + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", session, "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", session, "-p", "-S", "-${lines.coerceAtLeast(0)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", session)

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")

    fun snapshotArgv(session: String, lines: Int): Array<String> =
        capturePaneArgv("=$session:", lines) + arrayOf(
            ";",
            "display-message",
            "-p",
            "-t",
            "=$session:",
            "$TERMUX_STATE_MARKER#{pane_dead}\t#{pane_dead_status}\t#{pane_dead_signal}\t#{@rk_state}\t#{@rk_exit}\t#{@rk_kind}"
        )

    fun prepareArgv(session: String, cols: Int, rows: Int, workingDir: String, kind: String): Array<String> =
        startArgv(session, cols, rows) + arrayOf(
            "-c", workingDir, "$TERMUX_BIN/sleep", "86400",
            ";", "set-option", "-w", "-t", "=$session:", "remain-on-exit", "on",
            ";", "set-option", "-p", "-t", "=$session:", "@rk_kind", kind,
            ";", "set-option", "-p", "-t", "=$session:", "@rk_state", "running",
        )

    fun respawnArgv(session: String, command: String): Array<String> =
        arrayOf("respawn-pane", "-k", "-t", "=$session:", "$TERMUX_BIN/bash", "-c", command)

    fun pendingArgv(session: String): Array<String> = arrayOf(
        "set-option", "-p", "-t", "=$session:", "@rk_state", "pending",
        ";", "set-option", "-pu", "-t", "=$session:", "@rk_exit"
    )
}

internal data class PaneSample(val elapsedMs: Long, val content: String)

internal sealed interface PollResult {
    data object Continue : PollResult
    data class Done(val reason: Reason, val content: String) : PollResult
    enum class Reason { SETTLED, MATCHED, TIMEOUT }
}

/** Regex match with substring fallback when the pattern is not a valid regex. */
internal fun waitForMatches(pane: String, pattern: String): Boolean {
    if (pattern.isEmpty()) return false
    val rx = runCatching { Regex(pattern) }.getOrNull()
    return if (rx != null) rx.containsMatchIn(pane) else pane.contains(pattern)
}

/**
 * Decide whether the polling loop should stop, given every pane snapshot taken so far
 * (chronological, each with its elapsed time since the send). Order of precedence:
 * wait_for match, then settle (screen unchanged for >= settleMs), then timeout.
 */
internal fun evaluatePoll(
    samples: List<PaneSample>,
    settleMs: Long,
    timeoutMs: Long,
    waitFor: String?,
): PollResult {
    val cur = samples.lastOrNull() ?: return PollResult.Continue
    if (!waitFor.isNullOrEmpty() && waitForMatches(cur.content, waitFor)) {
        return PollResult.Done(PollResult.Reason.MATCHED, cur.content)
    }
    var stableSince = cur.elapsedMs
    for (i in samples.indices.reversed()) {
        if (samples[i].content == cur.content) stableSince = samples[i].elapsedMs else break
    }
    if (waitFor.isNullOrEmpty() && samples.size >= 2 && cur.elapsedMs - stableSince >= settleMs) {
        return PollResult.Done(PollResult.Reason.SETTLED, cur.content)
    }
    if (cur.elapsedMs >= timeoutMs) {
        return PollResult.Done(PollResult.Reason.TIMEOUT, cur.content)
    }
    return PollResult.Continue
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "rk_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

/** Sessions whose last tmux activity is older than [ttlMs] relative to [nowEpochSecs]. Pure. */
internal fun staleSessionsToReap(
    sessions: List<TmuxSessionInfo>,
    nowEpochSecs: Long,
    ttlMs: Long,
): List<TmuxSessionInfo> {
    val cutoffSecs = nowEpochSecs - ttlMs / 1000
    // session_activity is epoch seconds; treat a 0/unparsed activity as not-stale so a session
    // with a malformed timestamp is never reaped out from under an active user.
    return sessions.filter { it.lastActivity in 1 until cutoffSecs }
}

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

private suspend fun tmux(context: Context, argv: Array<String>, timeoutMs: Long = TMUX_OP_TIMEOUT_MS): CaptureResult =
    when (val result = runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs)) {
        is CaptureResult.Success -> if (result.exitCode == 0) {
            result
        } else {
            CaptureResult.OtherError(result.stderr.ifBlank { "tmux exited with ${result.exitCode}: ${result.stdout}" })
        }
        else -> result
    }

/** Ensure tmux is installed; auto-install on first use. Returns null on success, an error string otherwise. */
private suspend fun ensureTmux(context: Context): String? {
    val check =
        runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.stdout.isNotBlank()) return null
    // The install can run for the full INSTALL_TIMEOUT_MS (~180s). Keep the bound but surface
    // each outcome distinctly instead of silently blocking ~3 min and then reporting a generic
    // failure: a Denied means the permission path, a Timeout means the install is still going
    // (network / large download) so the caller can tell the user to retry shortly.
    val install =
        runCommandCapture(
            context,
            "$TERMUX_BIN/bash",
            arrayOf("-c", "pkg install -y tmux"),
            TERMUX_HOME,
            INSTALL_TIMEOUT_MS
        )
    if (install is CaptureResult.Denied) return "termux_permission_denied"
    if (install is CaptureResult.Timeout) return "tmux_installing"
    val recheck =
        runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

private fun resolveTimeoutMs(input: JsonElement): Long {
    val raw = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
    val secs = when {
        raw == null || raw == 0 -> DEFAULT_TIMEOUT_S
        else -> raw.coerceIn(1, MAX_TIMEOUT_S)
    }
    return secs.toLong() * 1000
}

/**
 * UTF-8 byte width of the Unicode code point [cp]. Used to budget truncation by bytes while
 * iterating code points (NOT chars): an astral char (emoji, some CJK) is a surrogate PAIR of
 * two Java chars but a single 4-byte UTF-8 sequence. Measuring per-char would count each
 * surrogate half separately — overshooting the budget ~2x and letting a cut fall between the
 * two halves, corrupting the very emoji this boundary-snapping is meant to protect.
 */
private fun utf8Width(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

/**
 * Keep at most [maxBytes] of UTF-8 from the end of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split, which
 * would otherwise corrupt CJK / emoji output. Returns the whole string when it already fits.
 * Pure.
 */
internal fun takeLastUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    // Walk back from the end one code point at a time, counting its true UTF-8 width, until
    // adding one more would exceed the budget; the surviving slice is byte-bounded and aligned.
    var bytes = 0
    var i = s.length
    while (i > 0) {
        val cp = s.codePointBefore(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i -= Character.charCount(cp)
    }
    return s.substring(i)
}

/**
 * Keep at most [maxBytes] of UTF-8 from the start of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split. Returns
 * the whole string when it already fits. Pure. Counterpart to [takeLastUtf8Bytes] for
 * head-keeping truncation.
 */
internal fun takeFirstUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

internal data class TermuxPane(val screen: String, val state: TermuxExecutionState, val kind: String)

internal fun parseTermuxPane(stdout: String): TermuxPane {
    val index = stdout.lastIndexOf(TERMUX_STATE_MARKER)
    if (index < 0) return TermuxPane(stdout, TermuxExecutionState("unknown"), "unknown")
    val metadata = stdout.substring(index).trimEnd('\r', '\n')
    return TermuxPane(
        stdout.substring(0, index).trimEnd('\r', '\n'),
        parseTermuxState(metadata),
        metadata.split('\t').getOrNull(5) ?: "unknown",
    )
}

private suspend fun readUntilDone(
    context: Context,
    session: String,
    lines: Int,
    waitFor: String?,
    timeoutMs: Long,
    waitForCompletion: Boolean = false,
): CaptureResult {
    val start = android.os.SystemClock.elapsedRealtime()
    val samples = ArrayList<PaneSample>()
    while (true) {
        val cap = tmux(context, TmuxOps.snapshotArgv(session, lines))
        if (cap !is CaptureResult.Success) return cap
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        // Include state changes in the stability check; the command may finish without output.
        if (samples.lastOrNull()?.content != cap.stdout) samples.clear()
        samples.add(PaneSample(elapsed, cap.stdout))
        if (samples.size > 2) samples.removeAt(1)
        val pane = parseTermuxPane(cap.stdout)
        val reason = when {
            !waitFor.isNullOrEmpty() && waitForMatches(pane.screen, waitFor) -> "MATCHED"
            pane.state.finished && samples.size == 2 && elapsed - samples.first().elapsedMs >= SETTLE_MS -> "COMPLETED"
            else -> when (val result = evaluatePoll(samples, SETTLE_MS, timeoutMs, null)) {
                is PollResult.Done -> when (result.reason) {
                    PollResult.Reason.TIMEOUT -> "TIMEOUT"
                    // Waiting for specific text must not stop on a quiet screen.
                    PollResult.Reason.SETTLED -> if (waitFor.isNullOrEmpty() && !waitForCompletion) "SETTLED" else null
                    PollResult.Reason.MATCHED -> "MATCHED"
                }
                PollResult.Continue -> null
            }
        }
        if (reason != null) return cap.copy(stderr = reason)
        if (elapsed >= timeoutMs) return cap.copy(stderr = "TIMEOUT")
        delay(POLL_INTERVAL_MS)
    }
}

private fun sessionErrorEnvelope(error: String, recovery: String) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", error)
        put("recovery", recovery)
    }.toString())
)

private fun preflight(context: Context): List<UIMessagePart>? = when (TermuxIntegration.state(context)) {
    TermuxIntegration.State.NOT_INSTALLED -> sessionErrorEnvelope(
        "termux_not_installed",
        "Install Termux from https://github.com/termux/termux-app/releases ."
    )
    TermuxIntegration.State.NO_PERMISSION -> sessionErrorEnvelope(
        "termux_permission_not_granted",
        "Grant the Termux RUN_COMMAND permission in Extensions > Termux."
    )
    TermuxIntegration.State.READY -> null
}

private fun sessionReadEnvelope(
    session: String,
    result: CaptureResult.Success,
    outputCache: TermuxOutputCache,
    config: TermuxConfig,
    cursor: String? = null,
    mode: String? = null,
): List<UIMessagePart> {
    val pane = parseTermuxPane(result.stdout)
    val delta = outputCache.read(session, cursor, pane.screen, config.maxStdoutBytes)
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("success", true)
        put("session_id", session)
        mode?.let { put("mode", it) }
        put("kind", pane.kind)
        put("execution_status", pane.state.status)
        pane.state.exitCode?.let { put("exit_code", it) }
        pane.state.signal?.let { put("exit_signal", it) }
        put("output", delta.output)
        put("cursor", delta.cursor)
        put("cursor_reset", delta.reset)
        put("output_truncated", delta.truncated || result.stdoutTruncated)
        // Keep the screen field for existing consumers on a full read only.
        if (cursor == null) put("screen", delta.output)
        put("matched_wait_for", result.stderr == "MATCHED")
        put("timed_out", result.stderr == "TIMEOUT")
        put("read_reason", result.stderr.ifBlank { "SNAPSHOT" })
    }.toString()))
}

private fun readFailure(result: CaptureResult): List<UIMessagePart> =
    if (result is CaptureResult.OtherError && isSessionNotFound(result.message)) {
        sessionErrorEnvelope("session_not_found", "This chat's session is gone. Start a new session.")
    } else {
        sessionErrorEnvelope(
            "termux_operation_failed",
            when (result) {
                CaptureResult.Timeout -> "Termux did not respond; the operation may still be running. Retry reading the same session."
                CaptureResult.Denied -> "Grant Termux access and enable allow-external-apps=true."
                is CaptureResult.OtherError -> result.message
                else -> "Check the Termux connection."
            }
        )
    }

private val sessionCreationMutex = Mutex()

private data class SessionCreation(val id: String, val read: CaptureResult, val dispatched: Boolean = false)

/** Create in two phases so remain-on-exit is enabled even for an instantly exiting job. */
private suspend fun createManagedSession(
    context: Context,
    scope: TermuxScope,
    label: String?,
    cols: Int,
    rows: Int,
    workingDir: String,
    command: (String) -> String,
    kind: String,
    stdin: String? = null,
): SessionCreation = sessionCreationMutex.withLock {
    val name = scope.sessionName(label)
    ensureTmux(context)?.let { return@withLock SessionCreation(name, CaptureResult.OtherError(it)) }
    val response = tmux(context, TmuxOps.listArgv())
    var live = when (response) {
        is CaptureResult.Success -> parseSessions(response.stdout, scope.prefix).filter { scope.owns(it.name) }
        is CaptureResult.OtherError -> if (isSessionNotFound(response.message)) {
            emptyList()
        } else {
            return@withLock SessionCreation(name, response)
        }
        else -> return@withLock SessionCreation(name, response)
    }
    // A long silent command is not an idle session. Reap only known finished shells/jobs.
    val stale = staleSessionsToReap(live, System.currentTimeMillis() / 1000, SESSION_TTL_MS)
    for (session in stale) {
        val snapshot = tmux(context, TmuxOps.snapshotArgv(session.name, 0)) as? CaptureResult.Success ?: continue
        if (!parseTermuxPane(snapshot.stdout).state.finished) continue
        if (tmux(context, TmuxOps.killArgv("=${session.name}")) is CaptureResult.Success) {
            live = live.filterNot { it.name == session.name }
        }
    }
    if (live.size >= MAX_SESSIONS) {
        return@withLock SessionCreation(
            name,
            CaptureResult.OtherError("Max $MAX_SESSIONS sessions in this chat. Close an old session first.")
        )
    }
    var launched = false
    try {
        val prepared = tmux(context, TmuxOps.prepareArgv(name, cols, rows, workingDir, kind))
        if (prepared !is CaptureResult.Success) return@withLock SessionCreation(name, prepared)
        if (stdin != null) {
            val loaded = runCommandCapture(
                context,
                "$TERMUX_BIN/tmux",
                arrayOf("load-buffer", "-b", name, "-"),
                TERMUX_HOME,
                TMUX_OP_TIMEOUT_MS,
                stdin
            )
            if (loaded !is CaptureResult.Success || loaded.exitCode != 0) {
                return@withLock SessionCreation(name, CaptureResult.OtherError("Could not supply stdin to the task."))
            }
        }
        // Once dispatched, cancellation/timeout must keep the task discoverable by its id.
        launched = true
        val started = tmux(context, TmuxOps.respawnArgv(name, command(name)))
        SessionCreation(name, started, dispatched = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } finally {
        if (!launched) {
            withContext(NonCancellable) {
                tmux(context, TmuxOps.killArgv("=$name"))
                tmux(context, arrayOf("delete-buffer", "-b", name))
            }
        }
    }
}

internal fun termuxBackgroundScript(executable: String, arguments: Array<String>, buffer: String?): String {
    val argv = (listOf(executable) + arguments).joinToString(" ", transform = ::shellSingleQuote)
    if (buffer == null) return "exec $argv </dev/null"
    val tmux = shellSingleQuote("$TERMUX_BIN/tmux")
    val name = shellSingleQuote(buffer)
    return "$tmux save-buffer -b $name - | $argv; ec=\$?; $tmux delete-buffer -b $name; exit \$ec"
}

internal suspend fun startTermuxBackgroundTask(
    context: Context,
    scope: TermuxScope,
    outputCache: TermuxOutputCache,
    config: TermuxConfig,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    stdin: String?,
): List<UIMessagePart> {
    val created = createManagedSession(
        context, scope, null, DEFAULT_COLS, DEFAULT_ROWS, workingDir,
        command = { id -> termuxBackgroundScript(executable, arguments, id.takeIf { stdin != null }) },
        kind = "task", stdin = stdin,
    )
    return if (created.read is CaptureResult.Success) {
        val snapshot = tmux(context, TmuxOps.snapshotArgv(created.id, DEFAULT_READ_LINES))
        if (snapshot is CaptureResult.Success) {
            sessionReadEnvelope(created.id, snapshot, outputCache, config, mode = "background")
        } else {
            createdSessionEnvelope(created.id, "background")
        }
    } else if (created.dispatched) {
        createdSessionEnvelope(created.id, "background")
    } else {
        readFailure(created.read)
    }
}

private fun createdSessionEnvelope(
    session: String,
    mode: String,
    note: String = "Session created; use termux_session_read/list to check status. Do not start a duplicate task.",
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", true)
        put("session_id", session)
        put("mode", mode)
        put("execution_status", "unknown")
        put("note", note)
    }.toString())
)

private fun sessionIdSchema() = buildJsonObject {
    put("type", "string")
    put("description", "Session/task id returned in this chat. Other chats' sessions are inaccessible.")
}

private fun cursorSchema() = buildJsonObject {
    put("type", "string")
    put(
        "description",
        "Previous cursor for this session. Returns only new terminal text in output; cursor_reset indicates a rewritten screen/expired cursor. Omit for a full screen."
    )
}

private fun waitForSchema() = buildJsonObject {
    put("type", "string")
    put(
        "description",
        "Wait for this substring/regex on screen, completion, or timeout. A quiet screen does not end this wait."
    )
}

private fun timeoutSchema() = buildJsonObject {
    put("type", "integer")
    put(
        "description",
        "Read timeout: default $DEFAULT_TIMEOUT_S, max $MAX_TIMEOUT_S seconds. A read timeout does not stop the task."
    )
}

internal fun termuxSessionStartTool(
    context: Context,
    scope: TermuxScope,
    outputCache: TermuxOutputCache,
    getConfig: () -> TermuxConfig = { TermuxConfig() },
): Tool = Tool(
    name = "termux_session_start",
    description = "Open a persistent interactive Termux bash terminal (tmux). Supports SSH, prompts and REPLs. Scoped to this chat. Returns session_id, execution_status, exit_code when available and output cursor. Shell commands complete only when the managed local shell returns; a quiet screen may still be running. Auto-installs tmux if needed.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string") })
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "Optional initial shell command, e.g. ssh myhost.")
            })
            put("cols", buildJsonObject { put("type", "integer") })
            put("rows", buildJsonObject { put("type", "integer") })
        })
    },
    needsApproval = { getConfig().approvalRequired },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val config = getConfig()
        val created = createManagedSession(
            context,
            scope,
            input.jsonObject["name"]?.jsonPrimitive?.contentOrNull,
            (input.jsonObject["cols"]?.jsonPrimitive?.intOrNull ?: DEFAULT_COLS).coerceIn(20, 500),
            (input.jsonObject["rows"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROWS).coerceIn(5, 200),
            config.defaultWorkingDir,
            { managedTermuxShell() },
            "terminal"
        )
        if (created.read !is CaptureResult.Success) {
            return@Tool if (created.dispatched) {
                createdSessionEnvelope(
                    created.id,
                    "terminal"
                )
            } else {
                readFailure(created.read)
            }
        }
        // Let bash finish loading its rc and install the hooks before typing the initial input.
        val ready = readUntilDone(context, created.id, DEFAULT_READ_LINES, null, DEFAULT_TIMEOUT_S * 1000L, true)
        val initial = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (!initial.isNullOrBlank()) {
            if (ready !is CaptureResult.Success || parseTermuxPane(ready.stdout).state.status != "completed") {
                return@Tool createdSessionEnvelope(
                    created.id,
                    "terminal",
                    "Initial command was not sent because the shell is not ready. Read this session, then send the command once the shell is ready."
                )
            }
            val sent = sendSessionInput(context, created.id, initial, true, emptyList())
            if (sent !is CaptureResult.Success) return@Tool readFailure(sent)
        }
        val read = if (initial.isNullOrBlank()) {
            ready
        } else {
            readUntilDone(context, created.id, DEFAULT_READ_LINES, null, DEFAULT_TIMEOUT_S * 1000L)
        }
        if (read is CaptureResult.Success) {
            sessionReadEnvelope(created.id, read, outputCache, config)
        } else {
            createdSessionEnvelope(created.id, "terminal")
        }
    }
)

private suspend fun sendSessionInput(
    context: Context,
    session: String,
    text: String?,
    enter: Boolean,
    keys: List<String>,
): CaptureResult {
    if (enter || "Enter" in keys || text?.contains('\n') == true) {
        val snapshot = tmux(context, TmuxOps.snapshotArgv(session, 0))
        if (snapshot !is CaptureResult.Success) return snapshot
        val pane = parseTermuxPane(snapshot.stdout)
        if (pane.state.status == "completed" && pane.kind == "terminal") {
            val pending = tmux(context, TmuxOps.pendingArgv(session))
            if (pending !is CaptureResult.Success) return pending
        }
    }
    val operations = buildList {
        if (!text.isNullOrEmpty()) add(TmuxOps.sendTextArgv("=$session:", text))
        if (keys.isNotEmpty()) add(TmuxOps.sendKeysArgv("=$session:", keys))
        if (enter) add(TmuxOps.enterArgv("=$session:"))
    }
    for (argv in operations) {
        val sent = tmux(context, argv)
        if (sent !is CaptureResult.Success) return sent
    }
    return CaptureResult.Success("", "", 0)
}

internal fun termuxSessionSendTool(
    context: Context,
    scope: TermuxScope,
    outputCache: TermuxOutputCache,
    getConfig: () -> TermuxConfig = { TermuxConfig() },
): Tool = Tool(
    name = "termux_session_send",
    description = "Type literal input or control keys into this chat's terminal/task. Input is not wrapped as a command, so password/SSH/REPL prompts work. enter defaults true. Returns actual execution_status independently of read_reason; SETTLED means output paused, not command completion. Supply cursor to read incremental output.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", sessionIdSchema())
            put("input", buildJsonObject { put("type", "string") })
            put("enter", buildJsonObject { put("type", "boolean") })
            put("keys", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "tmux key names, e.g. C-c, Enter, Up, Tab.")
            })
            put("cursor", cursorSchema())
            put("wait_for", waitForSchema())
            put("timeout_seconds", timeoutSchema())
        })
    },
    needsApproval = { getConfig().approvalRequired },
    execute = { input ->
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(scope::owns)
            ?: return@Tool sessionErrorEnvelope("session_not_in_current_chat", "Use a session_id created in this chat.")
        preflight(context)?.let { return@Tool it }
        val keys = input.jsonObject["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        require(keys.none { it.startsWith("-") || it.contains(';') }) { "Invalid tmux key name" }
        val sent = sendSessionInput(
            context,
            session,
            input.jsonObject["input"]?.jsonPrimitive?.contentOrNull,
            input.jsonObject["enter"]?.jsonPrimitive?.booleanOrNull ?: true,
            keys
        )
        if (sent !is CaptureResult.Success) return@Tool readFailure(sent)
        val read = readUntilDone(
            context,
            session,
            DEFAULT_READ_LINES,
            input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull,
            resolveTimeoutMs(input)
        )
        if (read is CaptureResult.Success) {
            sessionReadEnvelope(
                session,
                read,
                outputCache,
                getConfig(),
                input.jsonObject["cursor"]?.jsonPrimitive?.contentOrNull
            )
        } else {
            readFailure(read)
        }
    }
)

internal fun termuxSessionReadTool(
    context: Context,
    scope: TermuxScope,
    outputCache: TermuxOutputCache,
    getConfig: () -> TermuxConfig = { TermuxConfig() },
): Tool = Tool(
    name = "termux_session_read",
    description = "Read this chat's terminal or background task and its execution_status/exit_code. Pass the previous cursor to receive only new terminal text; omit for a full screen. Rewrites/expired cursors set cursor_reset; bounded scrollback/output may be truncated. Optional wait_for waits for text/completion/timeout. A read timeout leaves the task running.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", sessionIdSchema())
            put("cursor", cursorSchema())
            put("wait_for", waitForSchema())
            put("timeout_seconds", timeoutSchema())
            put("wait_for_completion", buildJsonObject {
                put("type", "boolean")
                put(
                    "description",
                    "Wait until the managed local command exits instead of returning when output pauses. Default false."
                )
            })
            put("lines", buildJsonObject {
                put("type", "integer")
                put("description", "Scrollback depth, default $DEFAULT_READ_LINES, max 2000.")
            })
        })
    },
    execute = { input ->
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(scope::owns)
            ?: return@Tool sessionErrorEnvelope("session_not_in_current_chat", "Use a session_id created in this chat.")
        preflight(context)?.let { return@Tool it }
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val waitForCompletion = input.jsonObject["wait_for_completion"]?.jsonPrimitive?.booleanOrNull ?: false
        val lines = (input.jsonObject["lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_READ_LINES).coerceIn(1, 2000)
        val read = if (waitFor.isNullOrEmpty() &&
            !waitForCompletion) {
            tmux(context, TmuxOps.snapshotArgv(session, lines))
        } else {
            readUntilDone(context, session, lines, waitFor, resolveTimeoutMs(input), waitForCompletion)
        }
        if (read is CaptureResult.Success) {
            sessionReadEnvelope(
                session,
                read,
                outputCache,
                getConfig(),
                input.jsonObject["cursor"]?.jsonPrimitive?.contentOrNull
            )
        } else {
            readFailure(read)
        }
    }
)

internal fun termuxSessionKillTool(
    context: Context,
    scope: TermuxScope,
    outputCache: TermuxOutputCache,
    getConfig: () -> TermuxConfig = { TermuxConfig() },
): Tool = Tool(
    name = "termux_session_kill",
    description = "Stop and close this chat's Termux terminal/background task, freeing its scrollback and output cursors.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("session_id", sessionIdSchema()) }) },
    needsApproval = { getConfig().approvalRequired },
    execute = { input ->
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(scope::owns)
            ?: return@Tool sessionErrorEnvelope("session_not_in_current_chat", "Use a session_id created in this chat.")
        preflight(context)?.let { return@Tool it }
        val killed = tmux(context, TmuxOps.killArgv("=$session"))
        if (killed !is CaptureResult.Success &&
            !(killed is CaptureResult.OtherError && isSessionNotFound(killed.message))) {
            return@Tool readFailure(killed)
        }
        tmux(context, arrayOf("delete-buffer", "-b", session))
        outputCache.remove(session)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("killed", session)
            put("execution_status", "stopped")
        }.toString()))
    }
)

internal fun termuxSessionListTool(context: Context, scope: TermuxScope): Tool = Tool(
    name = "termux_session_list",
    description = "List this chat's Termux terminals and background tasks, including execution status and exit codes. Finished tasks remain readable until closed. Other chats' sessions are excluded.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        preflight(context)?.let { return@Tool it }
        val response = tmux(context, TmuxOps.listArgv())
        val sessions = when (response) {
            is CaptureResult.Success -> parseSessions(response.stdout, scope.prefix).filter { scope.owns(it.name) }
            is CaptureResult.OtherError -> if (isSessionNotFound(
                    response.message
                )) {
                emptyList()
            } else {
                return@Tool readFailure(response)
            }
            else -> return@Tool readFailure(response)
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("sessions", buildJsonArray {
                for (session in sessions) {
                    val snapshot = tmux(context, TmuxOps.snapshotArgv(session.name, 0)) as? CaptureResult.Success
                    val pane = snapshot?.let { parseTermuxPane(it.stdout) }
                    add(buildJsonObject {
                        put("session_id", session.name)
                        put("created", session.created)
                        put("last_activity", session.lastActivity)
                        put("kind", pane?.kind ?: "unknown")
                        put("execution_status", pane?.state?.status ?: "unknown")
                        pane?.state?.exitCode?.let { put("exit_code", it) }
                        pane?.state?.signal?.let { put("exit_signal", it) }
                    })
                }
            })
        }.toString()))
    }
)

internal fun isManagedTermuxSession(id: String): Boolean = Regex("rk_[A-Za-z0-9_]{1,64}").matches(id)
