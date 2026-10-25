@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuCommandRequestTest {
    @Test
    fun shellAndLiteralArgvModesAreExclusive() {
        ShizukuCommandRequest(command = "echo hello").validate()
        val request = ShizukuCommandRequest(executable = "/system/bin/echo", arguments = listOf("$(literal); 'quoted'"))
        request.validate()
        assertEquals(listOf("/system/bin/echo", "$(literal); 'quoted'"), request.processBuilder().command())
        assertThrows(IllegalArgumentException::class.java) { request.copy(command = "echo duplicate").validate() }
        assertThrows(IllegalArgumentException::class.java) { ShizukuCommandRequest().validate() }
        assertThrows(IllegalArgumentException::class.java) { request.copy(executable = "echo").validate() }
        assertThrows(
            IllegalArgumentException::class.java
        ) { ShizukuCommandRequest(command = "echo", arguments = listOf("a")).validate() }
    }

    @Test
    fun environmentAndDirectoryArePerProcessAndNeverShellInterpolated() {
        val request =
            ShizukuCommandRequest(
                command = "echo",
                workingDir = "/data/local/tmp/path with spaces",
                environment = mapOf("RK_VALUE" to "$(literal)")
            )
        request.validate()
        assertEquals("/data/local/tmp/path with spaces", request.processBuilder().directory()!!.path.replace('\\', '/'))
        assertEquals("$(literal)", request.processBuilder().environment()["RK_VALUE"])
        assertThrows(
            IllegalArgumentException::class.java
        ) { request.copy(environment = mapOf("BAD;NAME" to "x")).validate() }
        assertThrows(IllegalArgumentException::class.java) { request.copy(workingDir = "relative").validate() }
    }

    @Test
    fun boundsAndNulAreValidatedBeforeBinderDispatch() {
        val request = ShizukuCommandRequest(command = "cat", stdin = "你好\nsecond line")
        assertEquals(request, shizukuJson.decodeFromString<ShizukuCommandRequest>(shizukuJson.encodeToString(request)))
        assertThrows(IllegalArgumentException::class.java) { request.copy(stdin = "x".repeat(128 * 1024)).validate() }
        assertThrows(IllegalArgumentException::class.java) { request.copy(stdin = "a\u0000b").validate() }
        assertThrows(IllegalArgumentException::class.java) { request.copy(timeoutMs = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { request.copy(timeoutMs = 300_001).validate() }
    }

    @Test
    fun taskIdsCannotTargetArbitraryProcessesOrShellSyntax() {
        assertTrue(isShizukuTaskId("sz_" + "a".repeat(32)))
        listOf(
            "123",
            "sz_../../etc",
            "sz_x;kill 1",
            "sz_" + "a".repeat(33)
        ).forEach { assertFalse(isShizukuTaskId(it)) }
    }
}
