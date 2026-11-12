package me.rerere.rikkahub.browser

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserFeedbackTest {
    private fun feedback() = buildJsonObject {
        put("kind", "tap")
        put("width", 1080); put("height", 2388)
        put("from_x", 1070); put("from_y", 2300)
        put("to_x", 1070); put("to_y", 2300)
    }

    @Test fun `pointer mapping uses reported CSS viewport including its bottom region`() {
        val gesture = requireNotNull(decodeBrowserGesture(1, "tab", feedback()))
        assertEquals(535f, gesture.normalizedX(1f) * 540, 0.001f)
        assertEquals(1150f, gesture.normalizedY(1f) * 1194, 0.001f)
        assertEquals("tab", gesture.tabId)
    }

    @Test fun `scroll interpolation follows the actual finger direction`() {
        val data = JsonObject(feedback() + mapOf(
            "kind" to JsonPrimitive("scroll"), "from_y" to JsonPrimitive(1800), "to_y" to JsonPrimitive(600),
        ))
        val gesture = requireNotNull(decodeBrowserGesture(1, "tab", data))
        assertEquals(BrowserGestureKind.SCROLL, gesture.kind)
        assertEquals(1200f, gesture.normalizedY(0.5f) * gesture.viewportHeight, 0.001f)
        assertTrue(gesture.normalizedY(1f) < gesture.normalizedY(0f))
    }

    @Test fun `malformed optional visual feedback is ignored`() {
        listOf(
            JsonObject(feedback() + ("width" to JsonPrimitive(0))),
            JsonObject(feedback() + ("height" to JsonPrimitive(-1))),
            JsonObject(feedback() + ("from_x" to JsonPrimitive("NaN"))),
            JsonObject(feedback() + ("to_x" to JsonPrimitive(1e30))),
            JsonObject(feedback() + ("to_y" to JsonArray(emptyList()))),
            JsonObject(feedback() + ("kind" to JsonPrimitive("unknown"))),
            JsonObject(emptyMap()),
        ).forEach { assertNull(decodeBrowserGesture(1, "tab", it)) }
    }

    @Test fun `repeated clicks at the same point remain distinct animation events`() {
        assertNotEquals(decodeBrowserGesture(1, "tab", feedback()), decodeBrowserGesture(2, "tab", feedback()))
    }
}
