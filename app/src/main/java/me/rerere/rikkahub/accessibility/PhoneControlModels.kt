package me.rerere.rikkahub.accessibility

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

@Serializable
data class PhoneControlConfig(val enabled: Boolean = false, val approvalRequired: Boolean = true)

internal data class PhoneNodeContent(val text: String, val description: String, val identity: String)

internal fun phoneNodeContent(text: CharSequence?, description: CharSequence?, password: Boolean): PhoneNodeContent {
    val safeText = if (password) "[password]" else text?.toString().orEmpty()
    val safeDescription = if (password) "" else description?.toString().orEmpty()
    val identity = MessageDigest.getInstance("SHA-256").digest((safeText + "\u0000" + safeDescription).toByteArray())
        .joinToString("") { "%02x".format(it) }
    return PhoneNodeContent(safeText.take(400), safeDescription.take(400), identity)
}

@Serializable
data class PhoneBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class PhoneNode(
    val id: String,
    val parentId: String?,
    val text: String,
    val description: String,
    val viewId: String,
    val className: String,
    val bounds: PhoneBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    @Transient val path: List<Int> = emptyList(),
    @Transient val identity: String = "",
)

@Serializable
data class PhoneScreen(
    val snapshotId: String,
    val packageName: String,
    val width: Int,
    val height: Int,
    val nodes: List<PhoneNode>,
    val truncated: Boolean,
    val windowId: Int = 0,
) {
    fun sameLayout(other: PhoneScreen): Boolean = copy(snapshotId = "") == other.copy(snapshotId = "")
}

/** Only copied, password-redacted data is retained; no AccessibilityNodeInfo objects. */
class PhoneSnapshots(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 300_000,
    private val capacity: Int = 8,
) {
    private data class Entry(val owner: String, val screen: PhoneScreen, val createdAt: Long)
    private data class Retired(val owner: String, val reason: String)
    private val entries = linkedMapOf<String, Entry>()
    private val retired = linkedMapOf<String, Retired>()

    @Synchronized
    fun put(owner: String, screen: PhoneScreen) {
        prune()
        entries.values.filter { it.owner == owner }.forEach { retire(it, "snapshot_replaced") }
        entries[screen.snapshotId] = Entry(owner, screen, now())
        while (entries.size > capacity) retire(entries.values.first(), "snapshot_evicted")
    }

    @Synchronized
    fun get(owner: String, id: String): PhoneScreen {
        prune()
        val entry = entries[id]
        if (entry != null && entry.owner == owner) return entry.screen
        val reason = retired[id]?.takeIf { it.owner == owner }?.reason ?: "snapshot_unavailable"
        throw PhoneActionRejected(reason, when (reason) {
            "snapshot_consumed" -> "This snapshot was already used. Use after.snapshotId from the last action, or read the screen again; do not replay the previous action."
            "snapshot_replaced" -> "A newer screen read replaced this snapshot. Use the latest snapshotId and its node IDs."
            "snapshot_expired" -> "This screen snapshot exceeded its 5-minute lifetime; read the screen again."
            else -> "Screen snapshot is unavailable; read the screen again."
        })
    }

    @Synchronized
    fun consume(owner: String, id: String) {
        get(owner, id)
        retire(entries.getValue(id), "snapshot_consumed")
    }

    @Synchronized
    fun clear() {
        entries.clear()
        retired.clear()
    }

    private fun prune() {
        val current = now()
        entries.values.filter { current - it.createdAt >= ttlMillis }.forEach { retire(it, "snapshot_expired") }
    }

    private fun retire(entry: Entry, reason: String) {
        entries.remove(entry.screen.snapshotId)
        retired[entry.screen.snapshotId] = Retired(entry.owner, reason)
        while (retired.size > capacity * 4) retired.remove(retired.keys.first())
    }
}

enum class PhoneActionKind(val key: String, val needsNode: Boolean = false) {
    CLICK("click", true),
    LONG_CLICK("long_click", true),
    INPUT("input", true),
    SCROLL_FORWARD("scroll_forward", true),
    SCROLL_BACKWARD("scroll_backward", true),
    TAP("tap"),
    SWIPE("swipe"),
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"),
}

data class PhoneAction(
    val kind: PhoneActionKind,
    val snapshotId: String,
    val nodeId: String? = null,
    val text: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    val durationMillis: Long = 400,
) {
    fun validateBounds(screen: PhoneScreen) {
        fun checkPoint(x: Double?, y: Double?) {
            require(x != null && y != null && x.isFinite() && y.isFinite() &&
                x >= 0 && y >= 0 && x < screen.width && y < screen.height) { "Coordinates are outside the screen" }
        }
        if (kind == PhoneActionKind.TAP || kind == PhoneActionKind.SWIPE) checkPoint(x, y)
        if (kind == PhoneActionKind.SWIPE) checkPoint(endX, endY)
    }

    companion object {
        fun parse(args: JsonObject): PhoneAction {
            val kind = PhoneActionKind.entries.find { it.key == args.string("action") }
                ?: error("Unknown phone action")
            val snapshot = args.string("snapshot_id")?.takeIf { it.isNotBlank() } ?: error("snapshot_id is required")
            val node = args.string("node_id")
            require(!kind.needsNode || !node.isNullOrBlank()) { "node_id is required" }
            val text = args.string("text")
            require(
                kind != PhoneActionKind.INPUT || (text != null && text.length <= 16_000)
            ) { "text is required and must be at most 16000 characters" }
            val duration = (args["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 400L
            require(duration in 100..2_000) { "duration_ms must be between 100 and 2000" }
            fun coordinate(key: String): Double? {
                val value = (args[key] as? JsonPrimitive)?.doubleOrNull
                if (key in args) require(value != null && value.isFinite()) { "$key must be finite" }
                return value
            }
            return PhoneAction(kind, snapshot, node, text, coordinate("x"), coordinate("y"),
                coordinate("end_x"), coordinate("end_y"), duration)
        }
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
