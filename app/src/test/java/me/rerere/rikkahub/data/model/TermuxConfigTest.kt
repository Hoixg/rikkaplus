package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxConfigTest {
    @Test
    fun `commands require ordinary tool approval by default`() {
        assertTrue(TermuxConfig().approvalRequired)
    }

    @Test
    fun `restored settings are kept within execution and output limits`() {
        val config = TermuxConfig(
            commandTimeoutMs = Long.MAX_VALUE,
            verifyTimeoutMs = 0,
            defaultWorkingDir = " ",
            maxStdoutBytes = -1,
            maxStderrBytes = Int.MAX_VALUE,
        ).normalized()
        assertEquals(600_000L, config.commandTimeoutMs)
        assertEquals(3_000L, config.verifyTimeoutMs)
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR, config.defaultWorkingDir)
        assertEquals(1_000, config.maxStdoutBytes)
        assertEquals(16_000, config.maxStderrBytes)
    }
}
