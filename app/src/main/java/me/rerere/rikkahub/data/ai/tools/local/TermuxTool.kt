@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
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
import me.rerere.rikkahub.data.model.TermuxDefaults
import java.util.UUID

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"

internal fun shellSingleQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

// Termux delivers stdout / stderr / exitCode via a result Bundle attached to the
// RUN_COMMAND_PENDING_INTENT we register. Documented at:
// https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val EXTRA_RESULT_BUNDLE = "result"
private const val RESULT_KEY_STDOUT = "stdout"
private const val RESULT_KEY_STDERR = "stderr"
private const val RESULT_KEY_EXIT_CODE = "exitCode"
private const val RESULT_KEY_ERR = "err"
private const val RESULT_KEY_ERRMSG = "errmsg"

/** The existing Android RUN_COMMAND interface, shared by settings and AI tools. */
internal object TermuxIntegration {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    enum class State { NOT_INSTALLED, NO_PERMISSION, READY }

    fun state(ctx: Context): State {
        try {
            ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }
        return if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            State.READY
        } else {
            State.NO_PERMISSION
        }
    }

    suspend fun verify(ctx: Context, timeoutMs: Long = TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS): VerifyResult {
        when (state(ctx)) {
            State.NOT_INSTALLED -> return VerifyResult.NotInstalled
            State.NO_PERMISSION -> return VerifyResult.NoPermission
            State.READY -> Unit
        }
        return when (val result = runCommandCapture(
            ctx,
            "$TERMUX_BIN_DIR/bash",
            arrayOf("-c", "echo RIKKAHUB_OK"),
            TERMUX_HOME_DIR,
            timeoutMs,
        )) {
            is CaptureResult.Success -> if (result.exitCode == 0 && result.stdout.contains("RIKKAHUB_OK")) {
                VerifyResult.Ok
            } else {
                VerifyResult.UnexpectedOutput(result.stderr.ifBlank { result.stdout })
            }
            CaptureResult.Timeout -> VerifyResult.TimedOut
            CaptureResult.Denied -> VerifyResult.NoPermission
            is CaptureResult.OtherError -> VerifyResult.OtherError(result.message)
        }
    }

    sealed class VerifyResult {
        data object NotInstalled : VerifyResult()
        data object NoPermission : VerifyResult()
        data object TimedOut : VerifyResult()
        data object Ok : VerifyResult()
        data class UnexpectedOutput(val stdout: String) : VerifyResult()
        data class OtherError(val message: String) : VerifyResult()
    }
}

internal sealed class CaptureResult {
    data class Success(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val stdoutTruncated: Boolean = false,
        val stderrTruncated: Boolean = false,
    ) : CaptureResult()
    data object Timeout : CaptureResult()
    data object Denied : CaptureResult()
    data class OtherError(val message: String) : CaptureResult()
}

/**
 * Wrap [executable]/[arguments] so the real command runs through `bash -c` and prints a
 * per-call [marker] right after it exits, whatever its exit status. `bash -c <script> <name>
 * <args...>` binds `$0` to `<name>` and `$@` to the rest, so the original executable goes in
 * the `$0` slot with no `--` separator (a `--` there would land the executable in `$1` and run
 * the wrong thing). Arguments stay literal argv entries, never interpolated into the script
 * text, so bytes like spaces, `$(...)` and `;` survive unchanged. Pure.
 */
internal fun buildMarkerWrappedArgv(
    bashPath: String,
    executable: String,
    arguments: Array<String>,
    marker: String,
): Pair<String, Array<String>> {
    val script = "\"\$0\" \"\$@\"; ec=\$?; printf '\\n%s' '$marker'; exit \$ec"
    return bashPath to (arrayOf("-c", script, executable, *arguments))
}

/**
 * Decide whether a Termux result bundle is the real completion, as opposed to the early
 * start-ACK broadcast Termux fires immediately (`err=-1, exitCode=0`, empty stdout/stderr -
 * byte-identical to a successful empty run). An actual internal error ([err] != -1) or a
 * nonzero [exitCode] is always terminal - a denial or a real failure never reaches the marker
 * print. Otherwise (err=-1, exitCode=0, the ack shape) it is terminal only once [stdout] carries
 * our per-call [marker], which only the real completion broadcast can print. Termux's
 * StreamGobbler appends a `"\n"` after every line it reassembles, including the marker line, so
 * the marker is never the literal last character - trim trailing whitespace before comparing.
 * Pure.
 */
internal fun isTerminalResult(err: Int, exitCode: Int, stdout: String, marker: String): Boolean {
    if (err != -1 || exitCode != 0) return true
    return stdout.trimEnd().endsWith(marker)
}

/**
 * Remove the trailing `"\n" + marker` appended by [buildMarkerWrappedArgv]'s script, restoring
 * the command's real stdout. Leaves [stdout] untouched when the marker isn't present (e.g. an
 * error bundle whose script never reached the `printf`). Termux's StreamGobbler appends a
 * trailing `"\n"` after the marker line that the script itself never printed, so trim it before
 * matching the suffix - everything before `"\n" + marker` is the command's real stdout, trailing
 * whitespace included. Pure.
 */
internal fun stripTerminationMarker(stdout: String, marker: String): String {
    val trimmed = stdout.trimEnd()
    val suffix = "\n$marker"
    return if (trimmed.endsWith(suffix)) trimmed.removeSuffix(suffix) else stdout
}

/**
 * Dispatch a Termux command and suspend until it completes (or times out), returning the
 * captured output. Implementation registers a one-shot BroadcastReceiver, hands a
 * PendingIntent for it to Termux, and waits on a CompletableDeferred until Termux fires
 * the broadcast back. Always uses background mode internally because the result-bundle
 * delivery path only fires for background commands.
 */
internal suspend fun runCommandCapture(
    ctx: Context,
    executable: String,
    arguments: Array<String>,
    workingDir: String,
    // Default reads from the runtime holder so callers that don't pass a timeout get the
    // user-configured value, not a stale compile-time constant.
    timeoutMs: Long = TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
    stdin: String? = null,
): CaptureResult {
    val resultDeferred = CompletableDeferred<Bundle>()
    val resultAction = "${ctx.packageName}.TERMUX_RESULT_${UUID.randomUUID()}"
    // Per-call completion marker (see buildMarkerWrappedArgv / isTerminalResult). Termux's
    // start-ACK broadcast is byte-identical to a successful empty run (err=-1, exitCode=0,
    // empty output), so without this the tool used to return early on the ACK and report
    // success while the command was still running (#83).
    val marker = UUID.randomUUID().toString()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Termux's RunCommandService fires the PendingIntent *twice* in 0.118.x:
            // - once almost immediately as a "started" / acknowledgement broadcast with
            //   intent.extras == null
            // - again when the command actually completes, this time with a "result" Bundle
            //   containing stdout / stderr / exitCode.
            // FLAG_ONE_SHOT used to consume the first empty fire and the real one was
            // never delivered. Now we ignore empty broadcasts and only complete the
            // deferred when we see a usable payload.
            val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
            if (bundle == null && intent.extras == null) return
            // Some Termux variants put the keys directly on the broadcast intent rather than
            // nested under "result". Support both shapes by falling back to flat extras.
            val effective = bundle ?: intent.extras ?: Bundle()
            // A populated but non-terminal bundle is the start-ACK: ignore it and keep waiting
            // for the marker-bearing completion broadcast; the overall timeout still bounds it.
            if (!isTerminalResult(
                    err = effective.getInt(RESULT_KEY_ERR, -1),
                    exitCode = effective.getInt(RESULT_KEY_EXIT_CODE, 0),
                    stdout = effective.getString(RESULT_KEY_STDOUT).orEmpty(),
                    marker = marker,
                )
            ) {
                return
            }
            if (resultDeferred.isActive) resultDeferred.complete(effective)
        }
    }
    val filter = IntentFilter(resultAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        ctx.registerReceiver(receiver, filter)
    }

    val pi = try {
        val resultIntent = Intent(resultAction).setPackage(ctx.packageName)
        PendingIntent.getBroadcast(
            ctx,
            resultAction.hashCode(),
            resultIntent,
            // Termux fires this PendingIntent twice (started ack + final result). Using
            // FLAG_ONE_SHOT used to consume the ack and lose the real result; FLAG_MUTABLE
            // lets Termux append its own extras to the intent it sends.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    } catch (t: Throwable) {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        return CaptureResult.OtherError("PendingIntent creation failed: ${t.message}")
    }

    // Always dispatch through bash so the marker print above is reachable, regardless of what
    // executable the caller asked for. Known behaviour change, accepted: the raw
    // executable/arguments tool path (termuxRunCommandTool) used to bypass bash and now
    // requires it - verify() already hard-requires $TERMUX_BIN_DIR/bash and TermuxSessionTool
    // already assumes bash, so this does not add a new class of dependency.
    val (wrappedExecutable, wrappedArguments) = buildMarkerWrappedArgv(
        bashPath = "$TERMUX_BIN_DIR/bash",
        executable = executable,
        arguments = arguments,
        marker = marker,
    )
    val intent = Intent().apply {
        setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        action = TERMUX_RUN_COMMAND_ACTION
        putExtra("com.termux.RUN_COMMAND_PATH", wrappedExecutable)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", wrappedArguments)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        stdin?.let { putExtra("com.termux.RUN_COMMAND_STDIN", it) }
        putExtra(EXTRA_PENDING_INTENT, pi)
    }

    return try {
        ctx.startService(intent)
        val bundle = withTimeoutOrNull(timeoutMs) { resultDeferred.await() }
        if (bundle == null) {
            CaptureResult.Timeout
        } else {
            // Per the Termux RUN_COMMAND wiki: `err = -1` (= Activity.RESULT_OK) means
            // "no internal error" — i.e. the success sentinel. Any value other than -1
            // is an actual Termux internal failure (service start failed, manual exit,
            // OS killed it, etc). Earlier revisions inverted this and rejected the
            // success path because err=-1 != 0.
            val errCode = bundle.getInt(RESULT_KEY_ERR, -1)
            if (errCode != -1) {
                val errMsg = bundle.getString(RESULT_KEY_ERRMSG).orEmpty()
                if (errMsg.contains("PermissionDenied", ignoreCase = true) ||
                    errMsg.contains("not allowed", ignoreCase = true)
                ) {
                    CaptureResult.Denied
                } else {
                    CaptureResult.OtherError("err=$errCode: $errMsg")
                }
            } else {
                CaptureResult.Success(
                    stdout = stripTerminationMarker(bundle.getString(RESULT_KEY_STDOUT).orEmpty(), marker),
                    stderr = bundle.getString(RESULT_KEY_STDERR).orEmpty(),
                    exitCode = bundle.getInt(RESULT_KEY_EXIT_CODE, -1),
                    stdoutTruncated = (bundle.getString("stdout_original_length")?.toLongOrNull() ?: 0) >
                        bundle.getString(RESULT_KEY_STDOUT).orEmpty().length,
                    stderrTruncated = (bundle.getString("stderr_original_length")?.toLongOrNull() ?: 0) >
                        bundle.getString(RESULT_KEY_STDERR).orEmpty().length,
                )
            }
        }
    } catch (t: SecurityException) {
        CaptureResult.Denied
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        CaptureResult.OtherError(t.message ?: t::class.java.simpleName)
    } finally {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        try {
            pi.cancel()
        } catch (_: Throwable) {}
    }
}

/**
 * LLM-callable termux command tool. Defaults to capture mode (background command, output
 * returned in the JSON envelope so the model can reason about it). Pass `interactive=true`
 * for the legacy "open visible Termux session" mode where the user sees output live but
 * the bot cannot read it.
 */
internal fun termuxRunCommandTool(
    context: Context,
    outputCache: TermuxOutputCache,
    getConfig: () -> TermuxConfig = { TermuxConfig() },
): Tool = Tool(
    name = "termux_run_command",
    description = """
        Execute a shell command in Termux. By default the command runs in the background and
        its stdout / stderr / exit_code are returned to you so you can reason on the output
        (e.g. check if a package is installed, read a file, run a script). Pass
        interactive=true to instead open a visible Termux session - useful when the user
        explicitly wants to watch output live or when the command needs an interactive prompt;
        in that mode no output is returned. Termux must have allow-external-apps=true set in
        ~/.termux/termux.properties (one-time setup). In command mode, apt/apt-get are
        automatically wrapped with DEBIAN_FRONTEND=noninteractive and safe dpkg defaults;
        do not add extra -y flags unless the user specifically asked for unattended upgrades.
        Use stdin for multiline scripts or input data. background=true creates a tracked tmux
        task shared across chats: use session_read/list/kill to read output, check status or stop it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Shell command line, e.g. 'pkg update && pkg upgrade -y'. Mutually exclusive with executable+arguments."
                    )
                })
                put("executable", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path to executable, e.g. /data/data/com.termux/files/usr/bin/bash. Pairs with arguments[]."
                    )
                })
                put("arguments", buildJsonObject {
                    put("type", "array")
                    put("description", "Argument list when using executable mode")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("working_dir", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory. Defaults to Termux home (/data/data/com.termux/files/home).")
                })
                put("interactive", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "If true, opens a visible Termux session and does NOT capture output. Default false (background + capture)."
                    )
                })
                put("stdin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Standard input passed literally via Termux RUN_COMMAND_STDIN, e.g. a script for bash -s or data for python. Capture/background modes only."
                    )
                })
                put("background", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Run as a tracked tmux background task and return session_id immediately. Output and exit status remain available through termux_session_read/list; stop with termux_session_kill. Supports command or executable+arguments. Default false."
                    )
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Capture-mode timeout in seconds. Omit or pass 0 to use the user-configured default (Extensions > Termux). Max ${TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS} s."
                    )
                })
            }
        )
    },
    needsApproval = { getConfig().approvalRequired },
    execute = { input ->
        val rawCommand = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        val executable = input.jsonObject["executable"]?.jsonPrimitive?.contentOrNull
        val argumentsArr = input.jsonObject["arguments"]?.jsonArray
        val stdin = input.jsonObject["stdin"]?.jsonPrimitive?.contentOrNull
        val workingDir = input.jsonObject["working_dir"]?.jsonPrimitive?.contentOrNull
            ?: getConfig().defaultWorkingDir
        val interactive = input.jsonObject["interactive"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        val background = input.jsonObject["background"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        // Read the user-configured default command timeout at call time. The LLM can override
        // per-call with timeout_seconds (0 = use runtime default, otherwise capped at
        // MAX_COMMAND_TIMEOUT_SECONDS = 600 s, raised from the old 300 s ceiling).
        val configuredTimeoutMs = getConfig().commandTimeoutMs
        val rawTimeout = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
        val timeoutMs = when {
            rawTimeout == null || rawTimeout == 0 -> configuredTimeoutMs
            else -> rawTimeout.coerceIn(1, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS).toLong() * 1000
        }

        if (rawCommand.isNullOrBlank() && executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "either 'command' or 'executable' is required") }.toString()
                )
            )
        }
        if (interactive && (background || stdin != null)) {
            return@Tool listOf(
                UIMessagePart.Text("""{"error":"interactive cannot be combined with background or stdin"}""")
            )
        }
        val inputBytes = listOfNotNull(rawCommand, executable, argumentsArr?.toString(), stdin)
            .sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (inputBytes > 128 * 1024) {
            return@Tool listOf(UIMessagePart.Text("""{"error":"command and stdin exceed the 128 KiB input budget"}"""))
        }
        if (!rawCommand.isNullOrBlank() && !executable.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command and executable are mutually exclusive") }.toString()
                )
            )
        }

        // Pre-flight: Termux installed?
        when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_not_installed")
                            put(
                                "recovery",
                                "Install Termux from the official GitHub releases page: https://github.com/termux/termux-app/releases . "
                            )
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.NO_PERMISSION -> {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_not_granted")
                            put(
                                "recovery",
                                "Toggle Termux on in Assistant -> Local tools so the runtime permission dialog appears, OR run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND"
                            )
                        }.toString()
                    )
                )
            }
            TermuxIntegration.State.READY -> Unit // proceed
        }

        // Prepend a noninteractive preamble for `command` mode so apt/pkg upgrades don't
        // hang waiting for "keep your existing config?" debconf prompts. Only applies to
        // the bash -c path; raw executable+arguments callers get no wrapping.
        // Gated on getConfig().aptWrapEnabled — user can disable from Settings → Termux.
        val (resolvedExe, resolvedArgs) = if (rawCommand != null) {
            // apt/dpkg noninteractive wrapping, gated on getConfig().aptWrapEnabled (user can
            // disable from Extensions > Termux).
            val preamble = if (getConfig().aptWrapEnabled) {
                "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a; " +
                    "apt(){ command apt -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                    "apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' -o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                    "export -f apt apt-get; "
            } else {
                ""
            }
            "$TERMUX_BIN_DIR/bash" to arrayOf("-c", preamble + rawCommand)
        } else {
            val args = argumentsArr?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toTypedArray()
                ?: emptyArray()
            executable!! to args
        }

        if (background) {
            return@Tool startTermuxBackgroundTask(
                context,
                outputCache,
                getConfig(),
                resolvedExe,
                resolvedArgs,
                workingDir,
                stdin
            )
        }

        if (interactive) {
            // Legacy fire-and-forget interactive session. Cannot read output back.
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", resolvedExe)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", resolvedArgs)
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            return@Tool try {
                context.startService(intent)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("mode", "interactive")
                            put(
                                "note",
                                "Opened a visible Termux session. Output is NOT captured by this tool. The user can see it directly."
                            )
                        }.toString()
                    )
                )
            } catch (t: SecurityException) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "termux_permission_denied")
                            put(
                                "recovery",
                                "In Termux, run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux and reopen, then retry."
                            )
                        }.toString()
                    )
                )
            } catch (t: Throwable) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "dispatch_failed")
                            put("reason", t.message ?: t::class.java.simpleName)
                        }.toString()
                    )
                )
            }
        }

        // Capture mode (default).
        val payload = when (val res = runCommandCapture(
            ctx = context,
            executable = resolvedExe,
            arguments = resolvedArgs,
            workingDir = workingDir,
            timeoutMs = timeoutMs,
            stdin = stdin,
        )) {
            is CaptureResult.Success -> buildJsonObject {
                put("success", res.exitCode == 0)
                put("mode", "capture")
                put("execution_status", "completed")
                put(
                    "stdout_truncated",
                    res.stdoutTruncated || res.stdout.toByteArray(Charsets.UTF_8).size > getConfig().maxStdoutBytes
                )
                put(
                    "stderr_truncated",
                    res.stderrTruncated || res.stderr.toByteArray(Charsets.UTF_8).size > getConfig().maxStderrBytes
                )
                put("exit_code", res.exitCode)
                val maxOut = getConfig().maxStdoutBytes
                val maxErr = getConfig().maxStderrBytes
                // maxStdoutBytes/maxStderrBytes are UTF-8 byte budgets. Measure and cut on bytes
                // (boundary-aligned via takeFirstUtf8Bytes) so multibyte output isn't mis-sized
                // and the "bytes more" count is honest rather than a char-count delta.
                put(
                    "stdout",
                    res.stdout.let {
                        val outBytes = it.toByteArray(Charsets.UTF_8).size
                        if (outBytes >
                            maxOut) {
                            takeFirstUtf8Bytes(it, maxOut) + "\n…[truncated; ${outBytes - maxOut} bytes more]"
                        } else {
                            it
                        }
                    }
                )
                if (res.stderr.isNotBlank()) {
                    put(
                        "stderr",
                        res.stderr.let {
                            if (it.toByteArray(Charsets.UTF_8).size >
                                maxErr) {
                                takeFirstUtf8Bytes(it, maxErr) + "\n…[truncated]"
                            } else {
                                it
                            }
                        }
                    )
                }
                if (res.exitCode != 0) {
                    put("note", "Non-zero exit code; check stderr.")
                }
            }
            is CaptureResult.Timeout -> buildJsonObject {
                put("error", "timeout")
                put(
                    "recovery",
                    "Command did not return within ${timeoutMs / 1000}s. Either bump timeout_seconds or, if Termux gave no result at all, the user likely has not set allow-external-apps=true in ~/.termux/termux.properties (or did not restart Termux after editing it)."
                )
            }
            is CaptureResult.Denied -> buildJsonObject {
                put("error", "termux_permission_denied")
                put(
                    "recovery",
                    "Open Termux, then run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties. Force-stop Termux from app info and reopen it. Then retry."
                )
            }
            is CaptureResult.OtherError -> buildJsonObject {
                put("error", "termux_run_failed")
                put("reason", res.message)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
