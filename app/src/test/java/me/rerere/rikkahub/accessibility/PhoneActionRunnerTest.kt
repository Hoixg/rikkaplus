package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PhoneActionRunnerTest {
    private val original = PhoneScreen("old", "example.app", 1080, 2400, listOf(
        PhoneNode("1", null, "Send", "", "example.app:id/send", "Button", PhoneBounds(10, 100, 200, 180),
            true, false, false, false, true, false, listOf(0), "send-fingerprint"),
    ), false, windowId = 10)
    private val click = PhoneAction(PhoneActionKind.CLICK, "old", nodeId = "1")
    private val snapshots = PhoneSnapshots().apply { put("chat", original) }

    @Test fun `post action observation waits from the event version captured before dispatch`() = runTest {
        val device = Device().apply { changeMarker = 7L }
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertTrue(result.flag("success"))
        assertEquals(listOf("prepare", "settle", "capture", "perform", "settle-after:7", "capture"), device.calls)
        assertEquals(1, device.actions)
    }

    @Test fun `input checks the resulting full text and never retries when Android accepted the wrong value`() = runTest {
        val requested = "a".repeat(400) + "wanted"
        val wrong = "a".repeat(400) + "different"
        val node = original.nodes.single().copy(editable = true, className = "EditText", viewId = "app:id/input", text = "old")
        val inputScreen = original.copy(nodes = listOf(node))
        val input = PhoneAction(PhoneActionKind.INPUT, "old", nodeId = "1", text = requested)
        listOf(requested, wrong).forEach { value ->
            snapshots.put("chat", inputScreen)
            val device = Device(inputScreen).apply {
                afterScreen = inputScreen.copy(nodes = listOf(node.copy(text = value.take(400), textIdentity = phoneTextIdentity(value))))
            }
            val result = PhoneActionRunner(snapshots, device).run("chat", input)
            assertTrue(result.flag("native_success"))
            assertEquals(value == requested, result.flag("input_verified"))
            assertEquals(value == requested, result.flag("success"))
            if (value != requested) assertEquals("input_mismatch", result.getValue("error").jsonPrimitive.content)
            assertEquals(1, device.actions)
            assertFalse(result.flag("read_screen_again"))
        }
    }

    @Test fun `passwords and a replaced input window are not falsely reported as verified`() = runTest {
        val node = original.nodes.single().copy(editable = true, password = true, text = "[password]")
        val passwordScreen = original.copy(nodes = listOf(node))
        snapshots.put("chat", passwordScreen)
        val device = Device(passwordScreen)
        val result = PhoneActionRunner(snapshots, device).run("chat", PhoneAction(PhoneActionKind.INPUT, "old", nodeId = "1", text = "private-value"))
        assertEquals("unavailable", result.getValue("input_verification").jsonPrimitive.content)
        assertFalse(result.toString().contains("private-value"))
        assertNull(verifyPhoneInput(node.copy(password = false), passwordScreen, passwordScreen.copy(windowId = 99), "text"))
    }

    @Test fun `directional scroll and keyboard submit require the corresponding offered action`() = runTest {
        listOf(PhoneActionKind.SCROLL_LEFT, PhoneActionKind.SCROLL_RIGHT, PhoneActionKind.SCROLL_UP, PhoneActionKind.SCROLL_DOWN, PhoneActionKind.IME_ENTER).forEach { kind ->
            val node = original.nodes.single().copy(editable = true, actions = listOf(kind.key))
            val screen = original.copy(nodes = listOf(node))
            snapshots.put("chat", screen)
            val device = Device(screen)
            assertTrue(PhoneActionRunner(snapshots, device).run("chat", click.copy(kind = kind)).flag("success"))
            assertEquals(1, device.actions)
            snapshots.put("chat", screen.copy(nodes = listOf(node.copy(actions = emptyList()))))
            val unavailable = Device(screen.copy(nodes = listOf(node.copy(actions = emptyList()))))
            assertFalse(PhoneActionRunner(snapshots, unavailable).run("chat", click.copy(kind = kind)).flag("action_attempted"))
            assertEquals(0, unavailable.actions)
        }
    }

    @Test fun `keyboard visibility and window viewport changes invalidate old gesture coordinates`() = runTest {
        listOf(original.copy(keyboardVisible = true), original.copy(windowBounds = PhoneBounds(0, 0, 1080, 1500))).forEach { screen ->
            snapshots.put("chat", original)
            val device = Device(screen)
            val result = PhoneActionRunner(snapshots, device).run("chat", click.copy(kind = PhoneActionKind.TAP, x = 20.0, y = 120.0))
            assertEquals("layout_changed", result.getValue("error").jsonPrimitive.content)
            assertEquals(0, device.actions)
        }
    }

    @Test fun `successful actions return a usable next snapshot without a separate read`() = runTest {
        val device = Device()
        val runner = PhoneActionRunner(snapshots, device)
        val first = runner.run("chat", click)
        assertTrue(first.flag("success"))
        assertTrue(first.flag("action_attempted"))
        assertFalse(first.flag("read_screen_again"))
        val next = first.getValue("after").jsonObject.getValue("snapshotId").jsonPrimitive.content
        assertEquals(next, snapshots.get("chat", next).snapshotId)
        val second = runner.run("chat", click.copy(snapshotId = next))
        assertTrue(second.flag("success"))
        assertEquals(2, device.actions)
        assertEquals(listOf("prepare", "settle", "capture", "perform", "settle", "capture"), device.calls.take(6))
    }

    @Test fun `a moved unique node uses its new path and bounds`() = runTest {
        val moved = original.nodes.single().copy(id = "7", path = listOf(2, 0), bounds = PhoneBounds(20, 400, 210, 480))
        val device = Device(original.copy(nodes = listOf(moved)))
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertTrue(result.flag("success"))
        assertTrue(result.flag("target_relocated"))
        assertEquals(moved, device.target)
        assertEquals(1, device.actions)
    }

    @Test fun `unrelated content changes do not invalidate an unchanged node`() = runTest {
        val unrelated = original.nodes.single().copy(id = "2", text = "Loading", identity = "loading", path = listOf(1))
        val device = Device(original.copy(nodes = original.nodes + unrelated))
        assertTrue(PhoneActionRunner(snapshots, device).run("chat", click).flag("success"))
        assertEquals(original.nodes.single(), device.target)
    }

    @Test fun `ambiguous moved nodes are rejected and return the current screen`() = runTest {
        val moved = original.nodes.single().copy(path = listOf(2), bounds = PhoneBounds(0, 400, 100, 480))
        val device = Device(original.copy(nodes = listOf(moved, moved.copy(id = "2", path = listOf(3)))))
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertEquals("node_ambiguous", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.flag("action_attempted"))
        assertFalse(result.flag("read_screen_again"))
        assertEquals(0, device.actions)
    }

    @Test fun `duplicates in the original screen cannot become a unique relocated target`() = runTest {
        val duplicate = original.nodes.single().copy(id = "2", path = listOf(1))
        snapshots.put("chat", original.copy(nodes = original.nodes + duplicate))
        val moved = original.nodes.single().copy(path = listOf(2), bounds = PhoneBounds(0, 400, 100, 480))
        val device = Device(original.copy(nodes = listOf(moved)))
        assertFalse(PhoneActionRunner(snapshots, device).run("chat", click).flag("action_attempted"))
        assertEquals(0, device.actions)
    }

    @Test fun `changed text changed windows disabled controls and truncated relocation never dispatch`() = runTest {
        val node = original.nodes.single()
        listOf(
            original.copy(nodes = listOf(node.copy(text = "Delete", identity = "delete"))),
            original.copy(windowId = 11),
            original.copy(packageName = "other.app"),
            original.copy(width = 2400, height = 1080),
            original.copy(nodes = listOf(node.copy(enabled = false))),
            original.copy(nodes = listOf(node.copy(path = listOf(2))), truncated = true),
        ).forEach { screen ->
            snapshots.put("chat", original)
            val device = Device(screen)
            assertFalse(PhoneActionRunner(snapshots, device).run("chat", click).flag("action_attempted"))
            assertEquals(0, device.actions)
        }
    }

    @Test fun `coordinate actions still reject any changed layout`() = runTest {
        val moved = original.nodes.single().copy(bounds = PhoneBounds(50, 100, 200, 180))
        val device = Device(original.copy(nodes = listOf(moved)))
        val result = PhoneActionRunner(snapshots, device).run("chat", click.copy(kind = PhoneActionKind.TAP, x = 20.0, y = 120.0))
        assertEquals("layout_changed", result.getValue("error").jsonPrimitive.content)
        assertEquals(0, device.actions)
    }

    @Test fun `a used snapshot is never replayed even when the screen did not change`() = runTest {
        val device = Device().apply { failAfterActionRead = true }
        val runner = PhoneActionRunner(snapshots, device)
        assertTrue(runner.run("chat", click).flag("success"))
        val replay = runner.run("chat", click)
        assertEquals("snapshot_consumed", replay.getValue("error").jsonPrimitive.content)
        assertFalse(replay.flag("action_attempted"))
        assertEquals(1, device.actions)
    }

    @Test fun `successful native action remains successful when observing its result fails`() = runTest {
        val device = Device().apply { failAfterActionRead = true }
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertTrue(result.flag("success"))
        assertTrue(result.flag("read_screen_again"))
        assertTrue("observation_error" in result)
        assertFalse("error" in result)
        assertEquals(1, device.actions)
    }

    @Test fun `a native exception reports an unknown outcome and never retries the action`() = runTest {
        val device = Device().apply { actionError = IllegalStateException("Binder failed after dispatch") }
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertTrue(result.flag("outcome_unknown"))
        assertTrue(result.flag("action_attempted"))
        assertEquals("action_outcome_unknown", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.flag("read_screen_again"))
        assertEquals(1, device.actions)
    }

    @Test fun `a native false result does not repeat the action`() = runTest {
        val device = Device().apply { actionResult = false }
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertFalse(result.flag("success"))
        assertTrue(result.flag("action_attempted"))
        assertFalse(result.flag("read_screen_again"))
        assertEquals(1, device.actions)
    }

    @Test fun `an animation timeout does not discard the new snapshot or mark an action failed`() = runTest {
        val device = Device().apply { quiet = false }
        val result = PhoneActionRunner(snapshots, device).run("chat", click)
        assertTrue(result.flag("success"))
        assertFalse(result.flag("settled"))
        assertFalse(result.flag("read_screen_again"))
        assertEquals(1, device.actions)
    }

    @Test fun `cancelling during dispatch propagates without observing or redispatching`() = runTest {
        val device = Device().apply { actionError = CancellationException("User stopped") }
        try {
            PhoneActionRunner(snapshots, device).run("chat", click)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, device.actions)
        assertEquals(1, device.reads)
        assertTrue(runCatching { snapshots.get("chat", "old") }.isFailure)
    }

    @Test fun `app opening observations create a snapshot owned only by the requesting conversation`() = runTest {
        val result = PhoneActionRunner(snapshots, Device()).observe("other-chat")
        val id = result.getValue("after").jsonObject.getValue("snapshotId").jsonPrimitive.content
        assertEquals(id, snapshots.get("other-chat", id).snapshotId)
        assertTrue(runCatching { snapshots.get("chat", id) }.isFailure)
        assertEquals("old", snapshots.get("chat", "old").snapshotId)
    }

    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean

    private inner class Device(private val current: PhoneScreen = original) : PhoneActionDevice {
        var actions = 0
        var reads = 0
        var quiet = true
        var actionResult = true
        var failAfterActionRead = false
        var actionError: Exception? = null
        var target: PhoneNode? = null
        var afterScreen: PhoneScreen? = null
        var changeMarker: Long? = null
        val calls = mutableListOf<String>()
        override suspend fun prepare(packageName: String) { calls += "prepare" }
        override suspend fun awaitQuiet(): Boolean { calls += "settle"; return quiet }
        override fun changeVersion(): Long? = changeMarker
        override suspend fun awaitAfter(since: Long): Boolean { calls += "settle-after:$since"; return quiet }
        override suspend fun capture(): PhoneScreen {
            calls += "capture"
            reads++
            if (failAfterActionRead && actions > 0) error("Window temporarily unavailable")
            return (afterScreen?.takeIf { actions > 0 } ?: current).copy(snapshotId = "fresh-$reads")
        }
        override suspend fun perform(action: PhoneAction, target: PhoneNode?, screen: PhoneScreen): Boolean {
            calls += "perform"
            actions++
            changeMarker = changeMarker?.plus(1)
            this.target = target
            actionError?.let { throw it }
            return actionResult
        }
    }
}
