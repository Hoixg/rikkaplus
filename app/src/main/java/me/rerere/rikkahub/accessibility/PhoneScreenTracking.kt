package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.delay

/** Records event times only. Receiving an accessibility event never reads or saves the screen. */
internal class PhoneScreenTracker(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var lastChange = now()

    fun changed() {
        lastChange = now()
    }

    suspend fun awaitQuiet(quietMillis: Long = 300, timeoutMillis: Long = 2_000): Boolean {
        val start = now()
        while (true) {
            val current = now()
            if (current - maxOf(start, lastChange) >= quietMillis) return true
            if (current - start >= timeoutMillis) return false
            delay(25)
        }
    }
}

internal class PhoneScreenUnavailable : IllegalStateException(
    "No active window is available; unlock the phone and open the target app"
)

/** Retry observations only; a failed click, input or gesture is never dispatched again here. */
internal suspend fun capturePhoneScreen(capture: () -> PhoneScreen): PhoneScreen {
    repeat(2) {
        try {
            return capture()
        } catch (_: PhoneScreenUnavailable) {
            delay(300)
        }
    }
    return capture()
}
