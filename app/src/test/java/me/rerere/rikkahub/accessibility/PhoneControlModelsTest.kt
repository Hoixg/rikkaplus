package me.rerere.rikkahub.accessibility

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class PhoneControlModelsTest {
    private fun screen(id: String = "screen") = PhoneScreen(id, "example.app", 1080, 2400, listOf(
        PhoneNode("1", null, "Continue", "", "example.app:id/continue", "Button", PhoneBounds(0, 0, 100, 100),
            true, false, false, false, true, false, listOf(0), "internal-identity"),
    ), false)

    @Test fun `snapshots cannot cross conversations or be replayed`() {
        val snapshots = PhoneSnapshots()
        snapshots.put("chat-a", screen())
        assertTrue(runCatching { snapshots.get("chat-b", "screen") }.isFailure)
        assertTrue(runCatching { snapshots.consume("chat-b", "screen") }.isFailure)
        assertEquals("screen", snapshots.get("chat-a", "screen").snapshotId)
        snapshots.consume("chat-a", "screen")
        assertTrue(runCatching { snapshots.get("chat-a", "screen") }.isFailure)
    }

    @Test fun `expiry replacement and capacity retire old snapshots`() {
        var time = 0L
        val snapshots = PhoneSnapshots(now = { time }, ttlMillis = 100, capacity = 2)
        snapshots.put("chat-a", screen("old"))
        snapshots.put("chat-a", screen("new"))
        assertTrue(runCatching { snapshots.get("chat-a", "old") }.isFailure)
        snapshots.put("chat-b", screen("b"))
        snapshots.put("chat-c", screen("c"))
        assertTrue(runCatching { snapshots.get("chat-a", "new") }.isFailure)
        assertEquals("b", snapshots.get("chat-b", "b").snapshotId)
        time = 100
        assertTrue(runCatching { snapshots.get("chat-b", "b") }.isFailure)
        snapshots.put("chat-c", screen("after-expiry"))
        snapshots.clear()
        assertTrue(runCatching { snapshots.get("chat-c", "after-expiry") }.isFailure)
    }

    @Test fun `snapshot retirement explains replacement consumption and expiry without leaking another owner`() {
        var time = 0L
        val snapshots = PhoneSnapshots(now = { time }, ttlMillis = 100)
        fun reason(owner: String, id: String) = (runCatching { snapshots.get(owner, id) }.exceptionOrNull() as PhoneActionRejected).code
        snapshots.put("chat", screen("read"))
        snapshots.put("chat", screen("latest"))
        assertEquals("snapshot_replaced", reason("chat", "read"))
        snapshots.consume("chat", "latest")
        assertEquals("snapshot_consumed", reason("chat", "latest"))
        assertEquals("snapshot_unavailable", reason("other", "latest"))
        snapshots.put("chat", screen("expires"))
        time = 100
        assertEquals("snapshot_expired", reason("chat", "expires"))
    }

    @Test fun `coordinate validation detects layout app text and size changes`() {
        val before = screen()
        assertTrue(before.sameLayout(screen("another-id")))
        assertFalse(before.sameLayout(before.copy(packageName = "other.app")))
        assertFalse(before.sameLayout(before.copy(width = 2400, height = 1080)))
        assertFalse(before.sameLayout(before.copy(nodes = before.nodes.map { it.copy(text = "Delete") })))
        assertFalse(before.sameLayout(before.copy(nodes = before.nodes.map { it.copy(identity = "changed") })))
    }

    @Test fun `password data is redacted before hashing and long text remains distinguishable`() {
        val first = phoneNodeContent("password-one", "password-one", true)
        val second = phoneNodeContent("password-two", "password-two", true)
        assertEquals("[password]", first.text)
        assertEquals("", first.description)
        assertEquals(first, second)
        val common = "a".repeat(400)
        val a = phoneNodeContent(common + "one", "", false)
        val b = phoneNodeContent(common + "two", "", false)
        assertEquals(a.text, b.text)
        assertNotEquals(a.identity, b.identity)
    }

    @Test fun `screen output omits internal node paths and fingerprints`() {
        val encoded = JsonInstant.encodeToString(screen())
        assertTrue(encoded.contains("Continue"))
        assertFalse(encoded.contains("internal-identity"))
        assertFalse(encoded.contains("\"path\""))
        assertFalse(encoded.contains("\"identity\""))
    }

    @Test fun `input and gesture arguments reject malformed or oversized values`() {
        fun args(action: String) = buildJsonObject { put("action", action); put("snapshot_id", "screen") }
        assertTrue(runCatching { PhoneAction.parse(args("unknown")) }.isFailure)
        assertTrue(runCatching { PhoneAction.parse(args("input")) }.isFailure)
        assertTrue(runCatching { PhoneAction.parse(buildJsonObject {
            put("action", "input"); put("snapshot_id", "screen"); put("node_id", "1"); put("text", "x".repeat(16_001))
        }) }.isFailure)
        val clear = PhoneAction.parse(buildJsonObject {
            put("action", "input"); put("snapshot_id", "screen"); put("node_id", "1"); put("text", "")
        })
        assertEquals("", clear.text)
        val tap = PhoneAction(PhoneActionKind.TAP, "screen", x = 10.0, y = 10.0)
        tap.validateBounds(screen())
        listOf(tap.copy(x = -1.0), tap.copy(x = 1080.0), tap.copy(y = 2400.0), tap.copy(x = Double.NaN)).forEach {
            assertTrue(runCatching { it.validateBounds(screen()) }.isFailure)
        }
        assertTrue(runCatching { PhoneAction.parse(buildJsonObject {
            put("action", "swipe"); put("snapshot_id", "screen"); put("duration_ms", 10_000)
        }) }.isFailure)
    }
}
