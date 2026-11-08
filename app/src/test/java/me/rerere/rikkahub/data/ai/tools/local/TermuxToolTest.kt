package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure marker-wrapping / terminal-decision logic behind [runCommandCapture]
 * (#83: a Termux start-ACK broadcast, byte-identical to a successful empty run, used to be
 * mistaken for the final result). Fixtures must use the Termux-delivered shape - StreamGobbler
 * appends a trailing newline after every line including the marker line (#100). The Android
 * BroadcastReceiver/PendingIntent plumbing is verified on-device.
 */
class TermuxToolTest {

    @Test
    fun stoppedTimeoutRetainsBothOutputStreamsWithoutMarkers() {
        val result = parseCapturedResult("partial\n\nM1\n", "partial stderr", 137, "M1") as CaptureResult.Timeout
        assertTrue(result.commandStopped)
        assertEquals("partial\n", result.stdout)
        assertEquals("partial stderr", result.stderr)
    }

    @Test
    fun completionFooterPreservesActualExitCodesAndWhitespace() {
        listOf(0, 17, 124, 137).forEach { code ->
            val result = parseCapturedResult(
                "hi  \n\nM1_exit:$code\nM1\n",
                "stderr\n",
                code,
                "M1"
            ) as CaptureResult.Success
            assertEquals(code, result.exitCode)
            assertEquals("hi  \n", result.stdout)
            assertEquals("stderr\n", result.stderr)
        }
    }

    @Test
    fun missingCompletionDoesNotClaimThatRemoteProcessesHaveStopped() {
        assertFalse(CaptureResult.Timeout().commandStopped)
        val result = parseCapturedResult("partial", "", 137, "M1")
        assertTrue(result is CaptureResult.Success)
    }

    @Test
    fun isTerminalResult_populatedAckWithoutMarker_isNotTerminal() {
        // err=-1, exitCode=0, empty stdout: exactly Termux's start-ACK shape.
        assertFalse(isTerminalResult(err = -1, exitCode = 0, stdout = "", marker = "M1"))
    }

    @Test
    fun isTerminalResult_markerBearingStdout_isTerminal() {
        assertTrue(isTerminalResult(err = -1, exitCode = 0, stdout = "hello\nM1", marker = "M1"))
    }

    @Test
    fun isTerminalResult_nonZeroErr_isTerminalWithoutMarker() {
        assertTrue(isTerminalResult(err = 1, exitCode = 0, stdout = "", marker = "M1"))
    }

    @Test
    fun isTerminalResult_nonZeroExitCode_isTerminalWithoutMarker() {
        assertTrue(isTerminalResult(err = -1, exitCode = 127, stdout = "", marker = "M1"))
    }

    @Test
    fun isTerminalResult_termuxDeliveredStdoutWithTrailingNewline_isTerminal() {
        assertTrue(isTerminalResult(err = -1, exitCode = 0, stdout = "hello\n\nM1\n", marker = "M1"))
    }

    @Test
    fun isTerminalResult_noOutputCommandTermuxShape_isTerminal() {
        // What `true` produces: no stdout, just the marker line.
        assertTrue(isTerminalResult(err = -1, exitCode = 0, stdout = "\nM1\n", marker = "M1"))
    }

    @Test
    fun isTerminalResult_outputWithoutMarkerButTrailingNewline_isNotTerminal() {
        // A partial/ack bundle must still wait even though stdout ends with a newline.
        assertFalse(isTerminalResult(err = -1, exitCode = 0, stdout = "hello\n", marker = "M1"))
    }

    @Test
    fun stripTerminationMarker_removesMarkerAndPrecedingNewline() {
        assertEquals("hello", stripTerminationMarker("hello\nM1", "M1"))
    }

    @Test
    fun stripTerminationMarker_leavesStdoutUntouchedWhenMarkerAbsent() {
        assertEquals("hello", stripTerminationMarker("hello", "M1"))
    }

    @Test
    fun stripTerminationMarker_termuxDeliveredShape_restoresCommandStdout() {
        assertEquals("hello\n", stripTerminationMarker("hello\n\nM1\n", "M1"))
    }

    @Test
    fun stripTerminationMarker_noOutputCommandTermuxShape_returnsEmpty() {
        assertEquals("", stripTerminationMarker("\nM1\n", "M1"))
    }

    @Test
    fun stripTerminationMarker_preservesCommandTrailingSpaces() {
        assertEquals("hi  ", stripTerminationMarker("hi  \nM1\n", "M1"))
    }

    @Test
    fun stripTerminationMarker_failedCommandTermuxShape_doesNotLeakMarker() {
        assertEquals("this_will_fail\n", stripTerminationMarker("this_will_fail\n\nM1\n", "M1"))
    }

    @Test
    fun buildMarkerWrappedArgv_bindsOriginalExecutableToDollarZero_noDashDash() {
        val (exe, args) = buildMarkerWrappedArgv(
            bashPath = "/data/data/com.termux/files/usr/bin/bash",
            executable = "/data/data/com.termux/files/usr/bin/echo",
            arguments = arrayOf("hi"),
            marker = "M1",
        )
        assertEquals("/data/data/com.termux/files/usr/bin/bash", exe)
        // args[0] = "-c", args[1] = script, args[2] = original executable ($0), rest = original args ($@).
        assertEquals("-c", args[0])
        assertTrue(args[1].contains("\"\$0\" \"\$@\""))
        assertFalse(args[2] == "--")
        assertEquals("/data/data/com.termux/files/usr/bin/echo", args[2])
        assertEquals("hi", args[3])
    }

    @Test
    fun buildMarkerWrappedArgv_argumentBytesSurviveUnchanged() {
        val trickyArgs = arrayOf("a b", "\$(rm -rf /)", "; echo pwned")
        val (_, args) = buildMarkerWrappedArgv(
            bashPath = "/bin/bash",
            executable = "/bin/echo",
            arguments = trickyArgs,
            marker = "M1",
        )
        // Original args are literal argv entries after $0, never interpolated into the script.
        assertEquals(listOf("a b", "\$(rm -rf /)", "; echo pwned"), args.drop(3))
    }
}
