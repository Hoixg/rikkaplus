package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant

internal class PhoneActionRejected(val code: String, message: String) : IllegalStateException(message)

internal data class PhoneNodeTarget(val node: PhoneNode, val relocated: Boolean)

internal fun resolvePhoneTarget(before: PhoneScreen, current: PhoneScreen, action: PhoneAction): PhoneNodeTarget? {
    if (before.packageName != current.packageName) {
        throw PhoneActionRejected("foreground_changed", "Foreground app changed; inspect the new screen before acting.")
    }
    if (before.width != current.width || before.height != current.height || before.windowId != current.windowId) {
        throw PhoneActionRejected("window_changed", "Window or display changed; inspect the new screen before acting.")
    }
    if (action.kind == PhoneActionKind.TAP || action.kind == PhoneActionKind.SWIPE) {
        if (!before.sameLayout(current)) {
            throw PhoneActionRejected(
                "layout_changed",
                "Screen layout changed; choose coordinates from the new screen."
            )
        }
    }
    if (!action.kind.needsNode) return null
    val expected = before.nodes.find { it.id == action.nodeId }
        ?: throw PhoneActionRejected("node_unavailable", "Unknown node_id; choose a node from the new screen.")
    fun PhoneNode.matches(other: PhoneNode) = copy(
        id = other.id,
        parentId = other.parentId,
        path = other.path,
        bounds = other.bounds,
    ) == other
    val matches = current.nodes.filter { expected.matches(it) }
    val exact = matches.singleOrNull { it.path == expected.path && it.bounds == expected.bounds }
    val target = exact ?: run {
        val identifiable =
            expected.viewId.isNotBlank() || expected.text.isNotBlank() || expected.description.isNotBlank()
        val wasUnique = before.nodes.count { expected.matches(it) } == 1
        if (!identifiable || !wasUnique || before.truncated || current.truncated || matches.isEmpty()) {
            throw PhoneActionRejected(
                "node_changed",
                "The target cannot be safely located; choose a node from the new screen."
            )
        }
        if (matches.size != 1) {
            throw PhoneActionRejected(
                "node_ambiguous",
                "Several nodes match the old target; choose the intended node from the new screen."
            )
        }
        matches.single()
    }
    val supported = target.enabled && when (action.kind) {
        PhoneActionKind.CLICK -> target.clickable
        PhoneActionKind.LONG_CLICK -> target.longClickable
        PhoneActionKind.INPUT -> target.editable
        PhoneActionKind.SCROLL_FORWARD, PhoneActionKind.SCROLL_BACKWARD -> target.scrollable
        else -> false
    }
    if (!supported) {
        throw PhoneActionRejected(
            "node_not_actionable",
            "Choose an enabled node that supports this action."
        )
    }
    return PhoneNodeTarget(target, exact == null)
}

/** All calls run under the native service's operation mutex; implementations recheck permission and lock state. */
internal interface PhoneActionDevice {
    suspend fun prepare(packageName: String)
    suspend fun awaitQuiet(): Boolean
    suspend fun capture(): PhoneScreen
    suspend fun perform(action: PhoneAction, target: PhoneNode?, screen: PhoneScreen): Boolean
}

internal class PhoneActionRunner(private val snapshots: PhoneSnapshots, private val device: PhoneActionDevice) {
    suspend fun run(owner: String, action: PhoneAction): JsonObject {
        var before: PhoneScreen? = null
        var attempted = false
        var success = false
        var relocated = false
        var failure: Exception? = null
        try {
            val expected = snapshots.get(owner, action.snapshotId)
            action.validateBounds(expected)
            device.prepare(expected.packageName)
            device.awaitQuiet()
            val current = device.capture()
            before = current
            val target = resolvePhoneTarget(expected, current, action)
            relocated = target?.relocated == true
            snapshots.consume(owner, action.snapshotId)
            // Consume before dispatch even if Android throws or reports failure: never replay a possible write.
            attempted = true
            success = device.perform(action, target?.node, current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        val observation = observe(owner, before)
        return buildJsonObject {
            put("success", success)
            put("action", action.kind.key)
            put("action_attempted", attempted)
            if (relocated) put("target_relocated", true)
            if (failure != null) {
                put(
                    "error",
                    (failure as? PhoneActionRejected)?.code
                        ?: if (attempted) "action_outcome_unknown" else "action_unavailable"
                )
                put("reason", failure.message ?: "Phone action failed")
                if (attempted) put("outcome_unknown", true)
            } else if (!success) {
                put(
                    "reason",
                    "Android did not report success; inspect the new screen before deciding what to do. Do not blindly repeat the action."
                )
            }
            observation.forEach { (key, value) -> put(key, value) }
        }
    }

    suspend fun observe(owner: String, before: PhoneScreen? = null): JsonObject {
        var settled: Boolean? = null
        return try {
            settled = device.awaitQuiet()
            val after = device.capture()
            snapshots.put(owner, after)
            buildJsonObject {
                put("after", JsonInstant.encodeToJsonElement(after))
                put("settled", settled)
                if (before != null) put("screen_changed", !before.sameLayout(after))
                put("read_screen_again", false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed observation must not turn a successful action into a failure and trigger a repeated write.
            buildJsonObject {
                if (settled != null) put("settled", settled)
                put("read_screen_again", true)
                put("observation_error", error.message ?: "Current screen is unavailable")
            }
        }
    }
}
