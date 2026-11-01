package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneScreenTrackingTest {
    @Test fun `quiet waiting restarts when the layout changes`() = runTest {
        val tracker = PhoneScreenTracker { testScheduler.currentTime }
        val waiting = async { tracker.awaitQuiet() }
        advanceTimeBy(250)
        tracker.changed()
        advanceTimeBy(299)
        runCurrent()
        assertFalse(waiting.isCompleted)
        advanceTimeBy(26)
        assertTrue(waiting.await())
        assertTrue(testScheduler.currentTime in 550..575)
    }

    @Test fun `a quiet old timestamp cannot skip the wait after a new action`() = runTest {
        val tracker = PhoneScreenTracker { testScheduler.currentTime }
        advanceTimeBy(10_000)
        assertTrue(tracker.awaitQuiet())
        assertEquals(10_300L, testScheduler.currentTime)
    }

    @Test fun `continuous animation stops waiting at the deadline`() = runTest {
        val tracker = PhoneScreenTracker { testScheduler.currentTime }
        val events = launch { while (true) { delay(100); tracker.changed() } }
        assertFalse(tracker.awaitQuiet())
        assertEquals(2_000L, testScheduler.currentTime)
        events.cancelAndJoin()
    }

    @Test fun `cancelling a wait stops it immediately`() = runTest {
        val tracker = PhoneScreenTracker { testScheduler.currentTime }
        val waiting = async { tracker.awaitQuiet() }
        advanceTimeBy(100)
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun `only a temporarily missing window is retried up to three reads`() = runTest {
        val screen = PhoneScreen("fresh", "app", 100, 200, emptyList(), false)
        var reads = 0
        assertEquals(screen, capturePhoneScreen {
            if (++reads < 3) throw PhoneScreenUnavailable()
            screen
        })
        assertEquals(3, reads)
        assertEquals(600L, testScheduler.currentTime)
        reads = 0
        try {
            capturePhoneScreen { reads++; throw PhoneScreenUnavailable() }
            fail("Expected unavailable window")
        } catch (_: PhoneScreenUnavailable) { }
        assertEquals(3, reads)
        reads = 0
        try {
            capturePhoneScreen { reads++; error("Disabled or locked") }
            fail("Expected precondition failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, reads)
    }
}
