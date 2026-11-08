// Adapted from ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// Source and adaptation notes: docs/phone-control-sources.md
package me.rerere.rikkahub.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "RikkaAccService"
private const val GESTURE_TIMEOUT_MS = 5_000L
class PhoneAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RikkaAcc-Screenshot").apply { isDaemon = true }
    }
    private val gestureHandlerThread = HandlerThread("RikkaAcc-Callback").apply { start() }
    private val gestureHandler = Handler(gestureHandlerThread.looper)

    // Uptime of the last window state/content change event. ScreenState.awaitQuiet polls
    // this to detect UI settle after an action.
    @Volatile
    var lastWindowEventUptime: Long = 0L
        private set

    // Serialises overlapping gesture-dispatch callers (the OS rejects overlapping
    // dispatchGesture calls). Mutex.withLock releases correctly when a waiter is
    // cancelled mid-wait, which the previous ticket counter did not.
    private val gestureMutex = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mutableConnected.value = true
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        mutableConnected.value = false
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        mutableConnected.value = false
        gestureHandlerThread.quitSafely()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window, content, text and scroll events all restart the post-action quiet period.
        if (event != null) {
            lastWindowEventUptime = android.os.SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {
        // Required override; no-op.
    }

    /**
     * Dispatches a gesture and suspends until it completes / cancels / times out (5s).
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        gestureMutex.withLock {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(d: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val dispatched = dispatchGesture(gesture, callback, gestureHandler)
                    if (!dispatched && cont.isActive) cont.resume(false)
                }
            } ?: false
        }

    fun buildTapPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    fun buildSwipePath(sx: Float, sy: Float, ex: Float, ey: Float): Path = Path().apply {
        moveTo(sx, sy)
        lineTo(ex, ey)
    }

    /**
     * Walk the active window's node tree depth-first (preorder, children in order).
     * `filter` decides whether to emit a node; traversal stops once `cap` nodes have been
     * emitted. traversalIndex counts every node seen (1-based), independent of the filter.
     * Depth is capped at 60 to survive pathological trees (deep WebView/Compose nesting).
     *
     * recycle=true recycles every visited non-root node on API < 33 after its subtree is
     * done (a no-op on 33+ where nodes are not pooled). Callers passing recycle=true MUST
     * NOT retain nodes past the emit callback; serialise or copy inside emit.
     *
     * Returns (emitted, totalSeen, truncated).
     */
    fun traverseTree(
        root: AccessibilityNodeInfo,
        filter: (AccessibilityNodeInfo, depth: Int) -> Boolean,
        cap: Int,
        emit: (AccessibilityNodeInfo, depth: Int, traversalIndex: Int) -> Unit,
        recycle: Boolean = false,
    ): Triple<Int, Int, Boolean> {
        val maxDepth = 60
        var emitted = 0
        var seen = 0
        var truncated = false
        val canRecycle = recycle && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (n, depth) = stack.removeLast()
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) truncated = true
            }
            if (!truncated && depth < maxDepth) {
                for (i in n.childCount - 1 downTo 0) {
                    val child = n.getChild(i) ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
            if (canRecycle && n !== root) {
                @Suppress("DEPRECATION")
                n.recycle()
            }
            if (truncated) break
        }
        if (canRecycle) {
            // Drain children queued but never visited (truncation / depth-cap leftovers).
            while (stack.isNotEmpty()) {
                val (n, _) = stack.removeLast()
                if (n !== root) {
                    @Suppress("DEPRECATION")
                    n.recycle()
                }
            }
        }
        return Triple(emitted, seen, truncated)
    }

    /**
     * Walks up the parent chain looking for a clickable node. Returns the original if it's
     * already clickable, or the nearest clickable ancestor, or null if none exists.
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /**
     * Captures a screenshot of the given display. Suspends until callback fires; returns
     * ScreenshotOutcome.Success(softwareBitmap) or Failure(reason). The success bitmap is a
     * software bitmap (ARGB_8888) — caller MUST call bitmap.recycle() when done to free
     * native memory. Returns Failure("api_too_low") on Android < 11 (API 30).
     */
    suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome =
        withTimeoutOrNull(GESTURE_TIMEOUT_MS) { captureScreenshotOnce(displayId) }
            ?: ScreenshotOutcome.Failure("timeout")

    private suspend fun captureScreenshotOnce(displayId: Int): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotOutcome.Failure("api_too_low")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.let { hardware ->
                                    try {
                                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                                    } finally {
                                        hardware.recycle()
                                    }
                                }
                            } finally {
                                result.hardwareBuffer.close()
                            }
                            if (cont.isActive) {
                                cont.resume(
                                    if (bmp != null) {
                                        ScreenshotOutcome.Success(bmp)
                                    } else {
                                        ScreenshotOutcome.Failure("bitmap_decode_failed")
                                    }
                                ) { _, value, _ ->
                                    (value as? ScreenshotOutcome.Success)?.bitmap?.recycle()
                                }
                            } else {
                                bmp?.recycle()
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(ScreenshotOutcome.Failure("exception:${t.message}"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
                            else -> "error_code_$errorCode"
                        }
                        if (cont.isActive) cont.resume(ScreenshotOutcome.Failure(reason))
                    }
                }
            )
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(val bitmap: Bitmap) : ScreenshotOutcome()
        data class Failure(val reason: String) : ScreenshotOutcome()
    }

    companion object {
        private val mutableConnected = MutableStateFlow(false)
        val connected = mutableConnected.asStateFlow()

        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }
}
