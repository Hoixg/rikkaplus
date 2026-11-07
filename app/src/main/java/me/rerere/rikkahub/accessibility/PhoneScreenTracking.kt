package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.delay

/** Records event times only. Receiving an accessibility event never reads or saves the screen. */
internal class PhoneScreenTracker(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile private var lastChange = now()

    @Volatile var version = 0L
        private set

    fun changed() {
        lastChange = now()
        version++
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

    /** Allow a delayed transition to begin before treating a quiet old screen as the result. */
    suspend fun awaitAfter(
        since: Long,
        firstEventMillis: Long = 600,
        quietMillis: Long = 300,
        timeoutMillis: Long = 2_000
    ): Boolean {
        val start = now()
        while (version == since && now() - start < firstEventMillis.coerceAtMost(timeoutMillis)) delay(25)
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
    repeat(3) { attempt ->
        try {
            val screen = capture()
            if (screen.nodes.isNotEmpty() || attempt == 2) return screen
        } catch (error: PhoneScreenUnavailable) {
            if (attempt == 2) throw error
        }
        delay(300)
    }
    error("Screen capture did not finish")
}
