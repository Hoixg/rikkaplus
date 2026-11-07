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
import android.view.accessibility.AccessibilityWindowInfo
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
    val changeVersion: Long get() = screenTracker.version
    suspend fun awaitAfter(since: Long): Boolean = screenTracker.awaitAfter(since)

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
        val root = try { contentRoot().node } catch (_: PhoneScreenUnavailable) { return null }
        return try { root.packageName?.toString() } finally { recycle(root) }
    }

    private data class ContentRoot(val node: AccessibilityNodeInfo, val bounds: PhoneBounds?, val keyboardVisible: Boolean?)

    /** IME focus must not turn the keyboard into the app being controlled. */
    private fun contentRoot(): ContentRoot {
        val (width, height) = displaySize()
        val windows = runCatching { this.windows }.getOrNull()
        try {
            val candidates = windows.orEmpty().mapIndexed { index, window ->
                val rect = Rect().also(window::getBoundsInScreen)
                PhoneWindowCandidate(window.id, index, when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> PhoneWindowKind.APPLICATION
                    AccessibilityWindowInfo.TYPE_SYSTEM -> PhoneWindowKind.SYSTEM
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> PhoneWindowKind.KEYBOARD
                    else -> PhoneWindowKind.OTHER
                }, window.layer, window.isActive, window.isFocused, PhoneBounds(rect.left, rect.top, rect.right, rect.bottom))
            }
            val selected = selectPhoneWindow(candidates, width, height)
            val root = if (selected != null) {
                windows?.get(selected.index)?.root ?: rootInActiveWindow?.let {
                    if (it.windowId == selected.id) it else { recycle(it); null }
                }
            } else if (windows.isNullOrEmpty()) rootInActiveWindow else null
            // Never fall through an unreadable foreground dialog to an underlying application.
            return ContentRoot(root ?: throw PhoneScreenUnavailable(), selected?.bounds,
                windows?.takeIf { it.isNotEmpty() }?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) windows.orEmpty().forEach { it.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Pair<Int, Int> {
        val metrics = DisplayMetrics().also {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) display.getRealMetrics(it) else it.setTo(resources.displayMetrics)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    fun capture(): PhoneScreen {
        val content = contentRoot()
        val root = content.node
        try {
            val nodes = mutableListOf<PhoneNode>()
            var visited = 0
            var characters = 0
            var truncated = false
            val deadline = System.nanoTime() + 350_000_000L
            fun visit(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?, passwordParent: Boolean) {
                if (visited++ >= 1_200 || nodes.size >= 250 || characters >= 24_000 || path.size > 40 || System.nanoTime() >= deadline) {
                    truncated = true
                    return
                }
                val password = passwordParent || node.isPassword
                var parent = parentId
                if (node.isVisibleToUser && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true ||
                    node.hintText?.isNotBlank() == true || node.isCheckable ||
                    node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable)) {
                    val copy = copyNode(node, (nodes.size + 1).toString(), parentId, path, password)
                    nodes.add(copy)
                    characters += copy.text.length + copy.description.length + copy.hint.length + copy.stateDescription.length
                    parent = copy.id
                }
                for (index in 0 until node.childCount) {
                    if (visited >= 1_200 || nodes.size >= 250 || characters >= 24_000 || System.nanoTime() >= deadline) {
                        truncated = true
                        break
                    }
                    val child = node.getChild(index) ?: continue
                    try { visit(child, path + index, parent, password) } finally { recycle(child) }
                }
            }
            visit(root, emptyList(), null, false)
            val (width, height) = displaySize()
            val screen = PhoneScreen(Uuid.random().toString(), root.packageName?.toString().orEmpty(),
                width, height, nodes, truncated, root.windowId, content.bounds, content.keyboardVisible)
            check(screen.packageName.isNotBlank()) { "Active application could not be identified" }
            return screen
        } finally { recycle(root) }
    }

    fun performNodeAction(expected: PhoneNode, action: PhoneAction, screen: PhoneScreen): Boolean {
        val root = contentRoot().node
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
                    if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                    })
                }
                PhoneActionKind.SCROLL_FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                PhoneActionKind.SCROLL_BACKWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                PhoneActionKind.SCROLL_UP, PhoneActionKind.SCROLL_DOWN, PhoneActionKind.SCROLL_LEFT,
                PhoneActionKind.SCROLL_RIGHT, PhoneActionKind.IME_ENTER -> {
                    val id = nodeActions.entries.firstOrNull { it.value == action.kind.key }?.key
                        ?: error("This Android version does not support the action")
                    check(node.actionList.any { it.id == id }) { "The node no longer offers this action" }
                    node.performAction(id)
                }
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
        val content = phoneNodeContent(node.text, node.contentDescription, password,
            textIsHint = node.isEditable && node.isShowingHintText)
        val rect = Rect().also(node::getBoundsInScreen)
        return PhoneNode(id, parent, content.text, content.description, node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(), PhoneBounds(rect.left, rect.top, rect.right, rect.bottom),
            node.isClickable, node.isLongClickable, node.isEditable, node.isScrollable, node.isEnabled, password, path, content.identity,
            hint = if (password) "" else node.hintText?.toString().orEmpty().take(400),
            stateDescription = if (password || Build.VERSION.SDK_INT < 30) "" else node.stateDescription?.toString().orEmpty().take(400),
            checkable = node.isCheckable, checked = @Suppress("DEPRECATION") node.isChecked,
            selected = node.isSelected, focused = node.isFocused,
            actions = node.actionList.mapNotNull { nodeActions[it.id] }.distinct().sorted(), textIdentity = content.textIdentity)
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }

    companion object {
        private val nodeActions by lazy {
            buildMap {
                put(AccessibilityNodeInfo.ACTION_CLICK, "click")
                put(AccessibilityNodeInfo.ACTION_LONG_CLICK, "long_click")
                put(AccessibilityNodeInfo.ACTION_SET_TEXT, "input")
                put(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_forward")
                put(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "scroll_backward")
                put(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id, "scroll_up")
                put(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id, "scroll_down")
                put(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id, "scroll_left")
                put(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id, "scroll_right")
                if (Build.VERSION.SDK_INT >= 30) put(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, "ime_enter")
            }
        }
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        private val mutableConnected = MutableStateFlow(false)
        val connected = mutableConnected.asStateFlow()
    }
}
