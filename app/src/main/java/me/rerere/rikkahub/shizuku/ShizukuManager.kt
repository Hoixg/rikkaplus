// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuManager"

/** Package name of the Shizuku manager app, the only reliable "installed" proxy without a
 *  live binder (the service itself can keep running after the app is force-stopped on some
 *  ROMs, but if the package is gone there is nothing to grant permission from).
 *  `moe.shizuku.manager` is only the Java namespace / permission prefix, never an installed
 *  package; the real application id is `moe.shizuku.privileged.api` (verified with
 *  `pm list packages`). */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Arbitrary request code: this app only ever makes one kind of Shizuku permission request. */
private const val PERMISSION_REQUEST_CODE = 8730

private const val BIND_TIMEOUT_MS = 10_000L

/**
 * Parse the raw JSON string [ShizukuUserService.exec] returns over the AIDL boundary back
 * into a [JsonObject]. A malformed response (crashed mid-write, wrong service version)
 * degrades to a structured `shizuku_bad_response` error carrying the raw text, rather than
 * throwing out of [ShizukuManager.exec]. Pure and Android-free, so it is unit-testable
 * without a device.
 */
internal fun parseExecResponse(raw: String): JsonObject =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
        buildJsonObject {
            put("error", "shizuku_bad_response")
            put("raw", raw)
        }
    }

/**
 * Outcome of a bind attempt against [IShizukuUserService], see [ShizukuManager.ensureBound].
 * Distinguishes *why* a bind failed instead of collapsing every case to `null` (#45: a thrown
 * exception, a died binding and a plain timeout used to be reported identically as
 * `shizuku_bind_failed`).
 */
internal sealed interface BindResult {
    data class Connected(val service: IShizukuUserService) : BindResult

    sealed interface Failure : BindResult {
        val phase: String

        /** `Shizuku.bindUserService` threw synchronously. */
        data class BindThrew(val throwable: Throwable) : Failure {
            override val phase = "bind_threw"
        }

        /** [ServiceConnection.onBindingDied] fired before [ServiceConnection.onServiceConnected],
         *  or the latter fired with a null/dead binder (the remote process died between connect
         *  and the first `pingBinder()`) -- either way there is no usable service. */
        data object BindingDied : Failure {
            override val phase = "binding_died"
        }

        /** No connection callback arrived within [BIND_TIMEOUT_MS]. */
        data object Timeout : Failure {
            override val phase = "bind_timeout"
        }
    }
}

/**
 * Build the `shizuku_bind_failed` envelope for a failed bind attempt, carrying `phase` (which
 * of the three failure modes it was) and, for [BindResult.Failure.BindThrew], a `reason` naming
 * the throwable's class and message. Never a stack trace: that goes to logcat only, see
 * [ShizukuManager.startBind]. Pure, so unit-testable without the Shizuku SDK.
 */
internal fun bindFailedResponse(failure: BindResult.Failure): JsonObject = buildJsonObject {
    put("error", "shizuku_bind_failed")
    put(
        "recovery",
        if (failure is BindResult.Failure.Timeout) {
            // The server accepted the bind (an unauthorised caller fails at bind_threw), so
            // permission is not the problem; a running server older than the Shizuku app is (#45).
            "The Shizuku server accepted the request but the user service never started. Open the Shizuku app: if the running server version is older than the app, or it offers to restart to upgrade, restart the Shizuku service, then retry."
        } else {
            "Could not bind the Shizuku user service. Retry; if it keeps failing, restart the Shizuku service and re-grant permission from Extensions -> Shizuku."
        },
    )
    put("phase", failure.phase)
    if (failure is BindResult.Failure.BindThrew) {
        put("reason", "${failure.throwable::class.java.simpleName}: ${failure.throwable.message}")
    }
}

/**
 * Thin wrapper around the static [Shizuku] SDK object (dev.rikka.shizuku:api 13.1.5) plus the
 * bind/unbind lifecycle for [IShizukuUserService]. `Shizuku.newProcess` is private in this
 * artifact (verified with `javap -p`), so [exec] is built on `Shizuku.bindUserService` against
 * our own AIDL service instead, see [ShizukuUserService].
 *
 * A stateless `object` wrapping a global SDK singleton, mirroring
 * [me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration] in shape: tool factories and the
 * settings page call it directly with a [Context], no DI needed.
 */
object ShizukuManager {

    // --- status --------------------------------------------------------------------------

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(context: Context): ShizukuStatus = ShizukuStatusMapper.compute(
        installed = isInstalled(context),
        binderAlive = isBinderAlive(),
        permissionGranted = isPermissionGranted(),
    )

    /**
     * Fires Shizuku's own consent flow. No-op if the binder isn't alive (there is nothing to
     * ask). The result arrives asynchronously via a registered
     * [Shizuku.OnRequestPermissionResultListener], not an Activity callback: callers must
     * register one (see [addRequestPermissionResultListener]) before calling this.
     *
     * Called from an explicit tap on the Extensions -> Shizuku screen, and from the assistant's
     * Shizuku local-tool toggle when it's switched on, never at app start or on first chat.
     */
    fun requestPermission() {
        if (!isBinderAlive()) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    // --- listeners: settings page wires these into a DisposableEffect --------------------

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    // --- shizuku_exec: bind the AIDL user service and run a command ----------------------

    private val bindLock = Mutex()
    private var service: IShizukuUserService? = null
    private var connection: ServiceConnection? = null

    @Volatile
    private var bindWaiter: CompletableDeferred<BindResult>? = null

    @Volatile
    private var cachedArgs: Shizuku.UserServiceArgs? = null

    private fun argsFor(context: Context): Shizuku.UserServiceArgs = cachedArgs ?: Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        // Protocol changes must replace an old UserService even without an app version bump.
        .version((BuildConfig.VERSION_CODE.toIntOrNull() ?: 1) * 100 + 2)
        .also { cachedArgs = it }

    /** Compatibility entry point; command execution uses short task RPCs and cancellable polling. */
    suspend fun exec(context: Context, command: String, timeoutMs: Int): JsonObject =
        execute(context, ShizukuCommandRequest(command = command, timeoutMs = timeoutMs.coerceIn(1000, 300_000)))

    internal suspend fun execute(context: Context, request: ShizukuCommandRequest): JsonObject = ShizukuCommandClient(
        start = { id, spec -> call(context, id) { it.start(id, shizukuJson.encodeToString(spec)) } },
        read = { id -> taskRead(context, id) },
        output = { id, stream, limit -> output(context, id, stream, 0, limit, null) },
        cancel = { id -> taskCancel(context, id) },
    ).execute(request)

    internal suspend fun taskRead(context: Context, id: String): JsonObject = call(context, id) { it.read(id) }
    internal suspend fun taskList(context: Context): JsonObject = call(context) { it.list() }
    internal suspend fun taskCancel(context: Context, id: String): JsonObject = call(context, id) { it.cancel(id) }
    internal suspend fun taskForget(context: Context, id: String): JsonObject = call(context, id) { it.forget(id) }
    internal suspend fun output(
        context: Context,
        id: String,
        stream: String,
        offset: Int,
        limit: Int,
        query: String?
    ): JsonObject =
        call(context, id) { it.output(id, stream, offset, limit, query) }

    suspend fun diagnostics(context: Context): JsonObject {
        val installed = isInstalled(context)
        val alive = isBinderAlive()
        val granted = isPermissionGranted()
        val base = buildJsonObject {
            put("installed", installed)
            put("binder_alive", alive)
            put("permission_granted", granted)
            put("android_api", Build.VERSION.SDK_INT)
            put("android_version", Build.VERSION.RELEASE)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            if (alive && granted) {
                runCatching { Shizuku.getVersion() }.getOrNull()?.let { put("server_version", it) }
                runCatching { Shizuku.getUid() }.getOrNull()?.let { uid ->
                    put("server_uid", uid)
                    put(
                        "identity",
                        when (uid) {
                            0 -> "root"
                            2000 -> "shell"
                            else -> "other"
                        }
                    )
                }
            }
        }
        val probe = call(context) { it.diagnostics() }
        return JsonObject(base + probe + buildJsonObject {
            put("success", probe["error"] == null)
            if (probe["error"] != null) put("user_service_connected", false)
        })
    }

    private suspend fun call(context: Context, id: String? = null, operation: (IShizukuUserService) -> String): JsonObject {
        ShizukuStatusMapper.errorFor(status(context))?.let { return it }
        val version = runCatching { Shizuku.getVersion() }.getOrNull()
            ?: return checkNotNull(ShizukuStatusMapper.errorFor(ShizukuStatus.NOT_RUNNING))
        if (version < 10) {
            return buildJsonObject {
                put("error", "shizuku_version_too_old")
                put(
                    "recovery",
                    "This Shizuku server is too old to support bindUserService (needs API 10+). Update the Shizuku app."
                )
            }
        }
        val api = when (val bindResult = ensureBound(context)) {
            is BindResult.Connected -> bindResult.service
            is BindResult.Failure -> return bindFailedResponse(bindResult)
        }
        return try {
            // These RPCs never wait for a command. Cancellation is an explicit per-task RPC.
            withContext(Dispatchers.IO) {
                val result = parseExecResponse(operation(api))
                if (id != null && result["error"]?.jsonPrimitive?.content == "shizuku_bad_response") {
                    JsonObject(result + shizukuCallFailed(id))
                } else {
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "user service operation failed", e)
            // A command failure must not tear down the shared service and other chats' tasks.
            if (!api.asBinder().pingBinder()) resetBinding(expectedService = api)
            shizukuCallFailed(id)
        }
    }

    private suspend fun ensureBound(context: Context): BindResult {
        bindLock.withLock { service?.takeIf { it.asBinder().pingBinder() }?.let { return BindResult.Connected(it) } }
        val deferred: CompletableDeferred<BindResult>
        val needBind: Boolean
        bindLock.withLock {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return BindResult.Connected(it) }
            if (bindWaiter == null) {
                bindWaiter = CompletableDeferred()
                needBind = true
            } else {
                needBind = false
            }
            deferred = bindWaiter!!
        }
        if (needBind) startBind(context, deferred)
        val result = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (result != null) return result
        // No connection callback arrived in time. Clear the waiter so the next call starts a
        // fresh bind instead of awaiting this same dead deferred forever (a bind that never
        // calls back would otherwise wedge every future call until process restart). Only
        // clear it if it is still ours. The stale connection is left alone: if its callback
        // arrives late it installs a live service, and completing the orphaned deferred is harmless.
        bindLock.withLock {
            if (bindWaiter === deferred) bindWaiter = null
        }
        return BindResult.Failure.Timeout
    }

    private fun startBind(context: Context, deferred: CompletableDeferred<BindResult>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val callback = this
                val api = if (binder != null && binder.pingBinder()) {
                    IShizukuUserService.Stub.asInterface(binder)
                } else {
                    null
                }
                var accepted = false
                runCatching {
                    runBlocking {
                        bindLock.withLock {
                            if (connection !== callback) return@withLock
                            service = api
                            if (bindWaiter === deferred) bindWaiter = null
                            accepted = true
                        }
                    }
                }
                if (!deferred.isCompleted) {
                    deferred.complete(
                        if (accepted && api != null) BindResult.Connected(api) else BindResult.Failure.BindingDied
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val callback = this
                Log.w(TAG, "shizuku user service disconnected")
                runCatching { runBlocking { resetBinding(callback) } }
            }

            override fun onBindingDied(name: ComponentName?) {
                val callback = this
                Log.w(TAG, "shizuku user service binding died")
                runCatching { runBlocking { resetBinding(callback) } }
                if (!deferred.isCompleted) deferred.complete(BindResult.Failure.BindingDied)
            }
        }
        val previous = runBlocking {
            bindLock.withLock {
                val old = connection
                connection = conn
                old
            }
        }
        previous?.let { runCatching { Shizuku.unbindUserService(argsFor(context), it, false) } }
        val result = runCatching { Shizuku.bindUserService(argsFor(context), conn) }
        if (result.isFailure) {
            val t = result.exceptionOrNull()!!
            Log.e(TAG, "bindUserService threw", t)
            runCatching {
                runBlocking {
                    bindLock.withLock {
                        if (connection === conn) connection = null
                        if (bindWaiter === deferred) bindWaiter = null
                    }
                }
            }
            if (!deferred.isCompleted) deferred.complete(BindResult.Failure.BindThrew(t))
        }
    }

    private suspend fun resetBinding(expected: ServiceConnection? = null, expectedService: IShizukuUserService? = null) {
        val previous = bindLock.withLock {
            if (expected != null && connection !== expected) return
            if (expectedService != null && service !== expectedService) return
            val old = connection to cachedArgs
            service = null
            connection = null
            bindWaiter?.complete(BindResult.Failure.BindingDied)
            bindWaiter = null
            old
        }
        val (conn, args) = previous
        if (conn != null && args != null) {
            // Remove only this callback. A reconnect must not destroy another caller's tasks.
            runCatching { Shizuku.unbindUserService(args, conn, false) }
        }
    }
}
