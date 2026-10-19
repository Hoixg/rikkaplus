package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSessionStateTest {
    @Test
    fun scopeSurvivesRecreationButRejectsOtherChatsAndLegacyIds() {
        val first = TermuxScope("chat-a")
        val id = first.sessionName("long label with quotes ' \$ and punctuation!")
        assertTrue(first.owns(id))
        assertTrue(TermuxScope("chat-a").owns(id))
        assertFalse(TermuxScope("chat-b").owns(id))
        assertFalse(first.owns("rk_legacy_1234"))
        assertFalse(first.owns("$id:1"))
        assertFalse(first.owns("${first.prefix}-t"))
    }

    @Test
    fun listingFiltersToCurrentChat() {
        val scope = TermuxScope("chat-a")
        val own = scope.sessionName(null)
        val other = TermuxScope("chat-b").sessionName(null)
        val output = "$own\t100\t200\n$other\t100\t200\nrk_legacy\t100\t200\n"
        assertEquals(listOf(own), parseSessions(output, scope.prefix).map { it.name })
    }

    @Test
    fun runningAndPendingNeverExposeStaleExitCodes() {
        val running = parseTermuxState("${TERMUX_STATE_MARKER}0\t\t\trunning\t17")
        assertEquals("running", running.status)
        assertFalse(running.finished)
        assertNull(running.exitCode)
        assertEquals("input_pending", parseTermuxState("${TERMUX_STATE_MARKER}0\t\t\tpending\t").status)
    }

    @Test
    fun completedShellAndExitedTaskPreserveFailureStatus() {
        val shell = parseTermuxState("${TERMUX_STATE_MARKER}0\t\t\tidle\t17")
        assertTrue(shell.finished)
        assertEquals(17, shell.exitCode)
        val task = parseTermuxState("${TERMUX_STATE_MARKER}1\t23\t\trunning\t")
        assertEquals("exited", task.status)
        assertEquals(23, task.exitCode)
        val signalled = parseTermuxState("${TERMUX_STATE_MARKER}1\t\t15\trunning\t")
        assertNull(signalled.exitCode)
        assertEquals("15", signalled.signal)
    }

    @Test
    fun malformedMetadataIsUnknown() {
        assertEquals("unknown", parseTermuxState("invalid").status)
        assertEquals("unknown", parseTermuxState("${TERMUX_STATE_MARKER}0\t\t\tidle\t").status)
        assertEquals("unknown", parseTermuxPane("ordinary output").state.status)
    }

    @Test
    fun metadataIsNotIncludedInOutput() {
        val pane = parseTermuxPane("hello\n${TERMUX_STATE_MARKER}1\t7\t\trunning\t\ttask\n")
        assertEquals("hello", pane.screen)
        assertEquals("task", pane.kind)
        assertEquals(7, pane.state.exitCode)
    }

    @Test
    fun cursorReturnsOnlyNewTextAndEmptyWhenUnchanged() {
        val cache = TermuxOutputCache()
        val first = cache.read("a", null, "hello\n", 8000)
        val next = cache.read("a", first.cursor, "hello\nworld\n", 8000)
        assertEquals("\nworld", next.output)
        assertFalse(next.reset)
        assertEquals("", cache.read("a", next.cursor, "hello\nworld\n", 8000).output)
    }

    @Test
    fun cursorCannotReadAnotherSessionOrChat() {
        val cache = TermuxOutputCache()
        val secret = cache.read("chat-a-session", null, "private", 8000)
        val other = cache.read("chat-b-session", secret.cursor, "other", 8000)
        assertEquals("other", other.output)
        assertTrue(other.reset)
    }

    @Test
    fun rewriteOrExpiredCursorExplicitlyResets() {
        val cache = TermuxOutputCache()
        val first = cache.read("a", null, "old screen", 8000)
        assertTrue(cache.read("a", first.cursor, "new screen", 8000).reset)
        cache.remove("a")
        assertTrue(cache.read("a", first.cursor, "current", 8000).reset)
    }

    @Test
    fun scrollbackOverlapUsesWholeLinesAndPreservesRepeatedLines() {
        assertEquals("\nfour", terminalDelta("one\ntwo\nthree", "two\nthree\nfour"))
        assertEquals("\nsame", terminalDelta("same\nsame", "same\nsame\nsame"))
        assertNull(terminalDelta("different", "screen"))
    }

    @Test
    fun cacheEvictsOldSnapshotsAndKeepsUtf8Intact() {
        val cache = TermuxOutputCache(maxSnapshots = 1)
        val first = cache.read("a", null, "12345678", 8)
        val next = cache.read("b", null, "你好😀", 4)
        assertEquals("😀", next.output)
        assertTrue(next.truncated)
        assertTrue(cache.read("a", first.cursor, "a", 8).reset)
    }

    @Test
    fun waitingForTextDoesNotSettleBeforeMatchOrTimeout() {
        val samples = listOf(PaneSample(0, "quiet"), PaneSample(1000, "quiet"))
        assertEquals(PollResult.Continue, evaluatePoll(samples, 600, 2000, "password:"))
        val timed = evaluatePoll(samples + PaneSample(2000, "quiet"), 600, 2000, "password:")
        assertEquals(PollResult.Reason.TIMEOUT, (timed as PollResult.Done).reason)
    }

    @Test
    fun fastTaskHasRemainOnExitConfiguredBeforeExecution() {
        val prepared = TmuxOps.prepareArgv("rk_test", 80, 24, "/tmp/path with spaces", "task").toList()
        assertTrue(prepared.contains("remain-on-exit"))
        assertTrue(prepared.contains("/tmp/path with spaces"))
        assertFalse(prepared.contains("respawn-pane"))
        assertEquals("=rk_test:", TmuxOps.respawnArgv("rk_test", "exit 17")[3])
    }
}
