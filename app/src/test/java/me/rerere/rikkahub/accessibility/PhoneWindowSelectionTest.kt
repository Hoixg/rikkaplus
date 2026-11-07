package me.rerere.rikkahub.accessibility

import org.junit.Assert.*
import org.junit.Test

class PhoneWindowSelectionTest {
    private val app = PhoneWindowCandidate(10, 0, PhoneWindowKind.APPLICATION, 1, true, true, PhoneBounds(0, 0, 1080, 2400))
    private fun select(vararg windows: PhoneWindowCandidate) = selectPhoneWindow(windows.toList(), 1080, 2400)

    @Test fun `keyboard focus and overlays do not replace the application content window`() {
        val keyboard = app.copy(id = 20, kind = PhoneWindowKind.KEYBOARD, layer = 10, bounds = PhoneBounds(0, 1500, 1080, 2400))
        val overlay = app.copy(id = 30, kind = PhoneWindowKind.OTHER, layer = 20)
        assertEquals(app, select(keyboard, overlay, app))
        assertNull(select(keyboard, overlay))
    }

    @Test fun `system bars are skipped while focused dialogs and notification panels remain in front`() {
        val bar = app.copy(id = 20, kind = PhoneWindowKind.SYSTEM, layer = 10, active = false, focused = false, bounds = PhoneBounds(0, 0, 1080, 80))
        assertEquals(app, select(bar, app))
        val dialog = bar.copy(active = true, bounds = PhoneBounds(100, 500, 900, 1000))
        assertEquals(dialog, select(dialog, app))
        val shade = bar.copy(bounds = PhoneBounds(0, 0, 1080, 1800))
        assertEquals(shade, select(shade, app))
    }

    @Test fun `top application is selected even when a lower window remains active`() {
        val dialog = app.copy(id = 20, layer = 10, active = false, focused = false, bounds = PhoneBounds(50, 500, 900, 1200))
        assertEquals(dialog, select(app, dialog))
        // The native capture must try this window, rather than clicking through it if its root is unavailable.
        assertEquals(20, select(app, dialog)!!.id)
    }

    @Test fun `offscreen and empty windows are excluded and split screen uses focus for equal layers`() {
        assertEquals(app, select(app.copy(id = 20, layer = 10, bounds = PhoneBounds(2000, 0, 3000, 1000)), app))
        assertNull(select(app.copy(bounds = PhoneBounds(0, 0, 0, 0))))
        val other = app.copy(id = 20, active = false, focused = false)
        assertEquals(app, select(other, app))
    }
}
