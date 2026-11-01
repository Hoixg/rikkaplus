package me.rerere.rikkahub.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

/** The system binds this service only after the user enables it in accessibility settings. */
class PhoneAccessibilityService : AccessibilityService() {
    val snapshots = PhoneSnapshots()
    val operations = Mutex()
    private val screenTracker = PhoneScreenTracker()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        mutableConnected.value = true
        screenTracker.changed()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) screenTracker.changed()
    }

    suspend fun awaitQuiet(): Boolean = screenTracker.awaitQuiet()

    override fun onInterrupt() {
        snapshots.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        snapshots.clear()
        if (instance === this) {
            instance = null
            mutableConnected.value = false
        }
    }

    fun foregroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { recycle(root) }
    }

    fun capture(): PhoneScreen {
        val root = rootInActiveWindow ?: throw PhoneScreenUnavailable()
        try {
            val nodes = mutableListOf<PhoneNode>()
            var visited = 0
            var characters = 0
            var truncated = false
            fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?, passwordParent: Boolean) {
                if (visited++ >= 1_200 || nodes.size >= 250 || characters >= 24_000 || path.size > 40) {
                    truncated = true
                    return
                }
                val password = passwordParent || node.isPassword
                var parent = parentId
                if (node.isVisibleToUser && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true ||
                    node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable)) {
                    val copy = copyNode(node, (nodes.size + 1).toString(), parentId, path, password)
                    nodes.add(copy)
                    characters += copy.text.length + copy.description.length
                    parent = copy.id
                }
                for (index in 0 until node.childCount) {
                    if (visited >= 1_200 || nodes.size >= 250 || characters >= 24_000) {
                        truncated = true
                        break
                    }
                    val child = node.getChild(index) ?: continue
                    try { visit(child, path + index, parent, password) } finally { recycle(child) }
                }
            }
            visit(root, emptyList(), null, false)
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also {
                val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) display.getRealMetrics(it) else it.setTo(resources.displayMetrics)
            }
            val screen = PhoneScreen(Uuid.random().toString(), root.packageName?.toString().orEmpty(),
                metrics.widthPixels, metrics.heightPixels, nodes, truncated, root.windowId)
            check(screen.packageName.isNotBlank()) { "Active application could not be identified" }
            return screen
        } finally { recycle(root) }
    }

    fun performNodeAction(expected: PhoneNode, action: PhoneAction, screen: PhoneScreen): Boolean {
        val root = rootInActiveWindow ?: error("Active window is unavailable")
        val allocated = mutableListOf<AccessibilityNodeInfo>()
        try {
            check(root.packageName?.toString() == screen.packageName && root.windowId == screen.windowId) {
                "Foreground window changed; read the screen again"
            }
            var node = root
            var password = root.isPassword
            for (index in expected.path) {
                node = node.getChild(index)?.also { allocated.add(it) } ?: error("Target node changed; read the screen again")
                password = password || node.isPassword
            }
            val actual = copyNode(node, expected.id, expected.parentId, expected.path, password)
            check(actual == expected && node.isVisibleToUser && node.isEnabled) { "Target node changed; read the screen again" }
            return when (action.kind) {
                PhoneActionKind.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                PhoneActionKind.LONG_CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                PhoneActionKind.INPUT -> {
                    check(node.isEditable) { "Select an editable node" }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                    })
                }
                PhoneActionKind.SCROLL_FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                PhoneActionKind.SCROLL_BACKWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else -> error("Not a node action")
            }
        } finally {
            allocated.asReversed().forEach(::recycle)
            recycle(root)
        }
    }

    suspend fun gesture(action: PhoneAction): Boolean = withTimeout(5_000) {
        val path = Path().apply {
            moveTo(action.x!!.toFloat(), action.y!!.toFloat())
            if (action.kind == PhoneActionKind.SWIPE) lineTo(action.endX!!.toFloat(), action.endY!!.toFloat())
        }
        val duration = if (action.kind == PhoneActionKind.TAP) 60L else action.durationMillis
        val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun screenshot(): Bitmap {
        check(Build.VERSION.SDK_INT >= 30) { "Accessibility screenshots require Android 11 or later; screen reading still works" }
        return withTimeout(5_000) {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var copy: Bitmap? = null
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?: error("Screenshot could not be decoded")
                            try { copy = hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                            val result = copy ?: error("Screenshot could not be copied")
                            if (continuation.isActive) {
                                continuation.resume(result) { _, value, _ -> value.recycle() }
                            } else result.recycle()
                        } catch (error: Exception) {
                            copy?.takeUnless { it.isRecycled }?.recycle()
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally { screenshot.hardwareBuffer.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                            "Screenshot unavailable (Android error $errorCode); protected windows cannot be captured"))
                    }
                })
            }
        }
    }

    private fun copyNode(node: AccessibilityNodeInfo, id: String, parent: String?, path: List<Int>, password: Boolean): PhoneNode {
        val content = phoneNodeContent(node.text, node.contentDescription, password)
        val rect = Rect().also(node::getBoundsInScreen)
        return PhoneNode(id, parent, content.text, content.description, node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(), PhoneBounds(rect.left, rect.top, rect.right, rect.bottom),
            node.isClickable, node.isLongClickable, node.isEditable, node.isScrollable, node.isEnabled, password, path, content.identity)
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        private val mutableConnected = MutableStateFlow(false)
        val connected = mutableConnected.asStateFlow()
    }
}
