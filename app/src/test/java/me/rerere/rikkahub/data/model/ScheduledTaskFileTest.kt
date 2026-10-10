package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskFileTest {
    @Test
    fun acceptsTextExtensionsOnly() {
        assertTrue(isScheduledTaskTextFile("notes.MD"))
        assertTrue(isScheduledTaskTextFile("plain.txt"))
        assertTrue(isScheduledTaskTextFile("data.json"))
        assertFalse(isScheduledTaskTextFile("photo.png"))
        assertFalse(isScheduledTaskTextFile("notes.md.exe"))
        assertTrue(isValidScheduledTaskCreatedFileName("new-notes.md"))
        assertFalse(isValidScheduledTaskCreatedFileName("../new-notes.md"))
        assertFalse(isValidScheduledTaskCreatedFileName("folder\\new-notes.md"))
        assertFalse(isValidScheduledTaskCreatedFileName(" photo.png"))
    }

    @Test
    fun fileListRoundTripsWithoutSharingState() {
        val first = listOf(ScheduledTaskFile("content://provider/a", "a.md"))
        val second = listOf(ScheduledTaskFile("content://provider/b", "b.txt", "content://provider/tree"))

        assertEquals(first, parseScheduledTaskFiles(encodeScheduledTaskFiles(first)))
        assertEquals(second, parseScheduledTaskFiles(encodeScheduledTaskFiles(second)))
    }
}
