@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuTaskOutputTest {
    private fun ShizukuTaskOutput.append(text: String) {
        val data = text.toByteArray()
        write(data, data.size)
    }

    @Test
    fun pagesPreserveEveryByteInsteadOfAdvancingPastUnreadOutput() {
        val output = ShizukuTaskOutput()
        val expected = "first line\n" + "你好😀 xyz\n".repeat(2500)
        output.append(expected)
        var offset = 0
        val actual = StringBuilder()
        do {
            val page = output.page(offset, 101, finished = true)
            actual.append(page["output"]!!.jsonPrimitive.content)
            val next = page["next_offset"]!!.jsonPrimitive.int
            assertTrue(next > offset)
            offset = next
        } while (!page["eof"]!!.jsonPrimitive.boolean)
        assertEquals(expected, actual.toString())
        assertEquals(expected.toByteArray().size, offset)
    }

    @Test
    fun incompleteLiveCodePointIsReturnedOnceItsRemainingBytesArrive() {
        val output = ShizukuTaskOutput()
        val emoji = "😀".toByteArray()
        output.write(emoji.copyOfRange(0, 2), 2)
        assertEquals(0, output.page(0, 4)["next_offset"]!!.jsonPrimitive.int)
        output.write(emoji.copyOfRange(2, 4), 2)
        assertEquals("😀", output.page(0, 4)["output"]!!.jsonPrimitive.content)
        assertEquals(4, output.page(0, 4)["next_offset"]!!.jsonPrimitive.int)
    }

    @Test
    fun capacityOverflowIsExplicitAndDoesNotSplitUtf8() {
        val output = ShizukuTaskOutput(capacity = 5)
        output.append("你好😀")
        val page = output.page(0, 100, finished = true)
        assertEquals("你", page["output"]!!.jsonPrimitive.content)
        assertEquals(3, page["retained_bytes"]!!.jsonPrimitive.int)
        assertEquals(7, page["dropped_bytes"]!!.jsonPrimitive.int)
        assertTrue(page["output_truncated"]!!.jsonPrimitive.boolean)
        assertTrue(page["eof"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun literalSearchAndContinuationCanFindMoreThanOneMatch() {
        val output = ShizukuTaskOutput()
        output.append("prefix [hit] first\nseparator\n[hit] second")
        val first = output.page(0, 12, query = "[hit]", finished = true)
        assertTrue(first["matched"]!!.jsonPrimitive.boolean)
        assertTrue(first["output"]!!.jsonPrimitive.content.startsWith("[hit]"))
        val next = output.page(first["next_offset"]!!.jsonPrimitive.int, 40, query = "[hit]", finished = true)
        assertEquals("[hit] second", next["output"]!!.jsonPrimitive.content)
        assertFalse(output.page(0, 40, query = "absent", finished = true)["matched"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun liveSearchPreservesOverlapAcrossNewWrites() {
        val output = ShizukuTaskOutput()
        output.append("waiting for pass")
        val before = output.page(0, 100, query = "password:")
        output.append("word:")
        val after = output.page(before["next_offset"]!!.jsonPrimitive.int, 100, query = "password:")
        assertTrue(after["matched"]!!.jsonPrimitive.boolean)
        assertEquals("password:", after["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidOffsetsAndLimitsAreRejected() {
        val output = ShizukuTaskOutput()
        assertThrows(IllegalArgumentException::class.java) { output.page(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { output.page(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { output.page(0, 65_537) }
        assertThrows(IllegalArgumentException::class.java) { output.page(0, 10, "") }
    }
}
