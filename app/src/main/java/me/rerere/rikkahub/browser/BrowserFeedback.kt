package me.rerere.rikkahub.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

data class BrowserActivity(val conversationId: String, val tabId: String, val action: String)

enum class BrowserGestureKind { TAP, SCROLL }

/** Coordinates use the same CSS viewport as DOM actions, independently of device screen metrics. */
data class BrowserGesture(
    val id: Long,
    val tabId: String,
    val kind: BrowserGestureKind,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    val motionMillis get() = if (kind == BrowserGestureKind.TAP) 220 else 360
    fun normalizedX(progress: Float) = (fromX + (toX - fromX) * progress) / viewportWidth
    fun normalizedY(progress: Float) = (fromY + (toY - fromY) * progress) / viewportHeight
}

/** Invalid visual feedback is ignored; it must never reject or retry a browser action. */
internal fun decodeBrowserGesture(id: Long, tabId: String, value: JsonObject): BrowserGesture? = runCatching {
    fun number(key: String) =
        value[key]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() } ?: error("invalid coordinate")
    val width = number("width")
    val height = number("height")
    require(width > 0 && height > 0)
    val kind = when (value["kind"]?.jsonPrimitive?.content) {
        "tap" -> BrowserGestureKind.TAP
        "scroll" -> BrowserGestureKind.SCROLL
        else -> error("unknown gesture")
    }
    val fromX = number("from_x")
    val fromY = number("from_y")
    val toX = number("to_x")
    val toY = number("to_y")
    require(fromX in 0f..width && toX in 0f..width && fromY in 0f..height && toY in 0f..height)
    BrowserGesture(id, tabId, kind, fromX, fromY, toX, toY, width, height)
}.getOrNull()
