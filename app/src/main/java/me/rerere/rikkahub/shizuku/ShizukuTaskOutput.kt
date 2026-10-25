@file:Suppress("ktlint:standard:max-line-length")

package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Append-only, bounded raw bytes. Page offsets advance only over bytes actually returned. */
internal class ShizukuTaskOutput(private val capacity: Int = 1024 * 1024) {
    private val bytes = ByteArrayOutputStream(minOf(capacity, 8192))
    private var total = 0L

    @Synchronized
    fun write(buffer: ByteArray, size: Int) {
        total += size
        val remaining = capacity - bytes.size()
        if (remaining > 0) bytes.write(buffer, 0, minOf(size, remaining))
    }

    @Synchronized
    fun page(offset: Int, limit: Int, query: String? = null, finished: Boolean = false): JsonObject {
        require(offset >= 0) { "offset must be nonnegative." }
        require(limit in 4..65_536) { "limit must be between 4 and 65536 bytes." }
        require(
            query == null || (query.isNotEmpty() && query.length <= 1024)
        ) { "query must contain 1 to 1024 characters." }
        val raw = bytes.toByteArray()
        var retained = raw.size
        if (total > raw.size && raw.isNotEmpty()) {
            var lead = raw.lastIndex
            while (lead > 0 && raw[lead].toInt() and 0xc0 == 0x80) lead--
            val first = raw[lead].toInt() and 0xff
            val width = when {
                first < 0x80 -> 1
                first and 0xe0 == 0xc0 -> 2
                first and 0xf0 == 0xe0 -> 3
                first and
                    0xf8 ==
                    0xf0 -> 4
                else -> 1
            }
            if (lead + width > raw.size) retained = lead
        }
        val data = raw.copyOf(retained)
        var start = offset.coerceAtMost(data.size)
        var matched = true
        var retryOffset = start
        if (query != null) {
            val needle = query.toByteArray(Charsets.UTF_8)
            start = (start..data.size - needle.size).firstOrNull { index ->
                needle.indices.all { data[index + it] == needle[it] }
            } ?: data.size
            matched = start < data.size
            retryOffset = maxOf(offset.coerceAtMost(data.size), data.size - needle.size + 1)
        }
        // Arbitrary caller offsets may point inside a UTF-8 code point.
        while (start < data.size && data[start].toInt() and 0xc0 == 0x80) start++
        val end = minOf(data.size, start + limit)
        val input = ByteBuffer.wrap(data, start, end - start)
        val output = CharBuffer.allocate(end - start)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        // A running writer may have delivered only the first bytes of a code point.
        decoder.decode(input, output, finished && end == data.size)
        var next = input.position()
        if (query != null && !matched && !finished) {
            next = retryOffset
            while (next > 0 && next < data.size && data[next].toInt() and 0xc0 == 0x80) next--
        }
        output.flip()
        return buildJsonObject {
            put("output", output.toString())
            put("offset", start)
            put("next_offset", next)
            put("retained_bytes", data.size)
            put("total_bytes", total)
            put("dropped_bytes", total - data.size)
            put("has_more", next < data.size)
            put("output_truncated", total > data.size)
            put("eof", finished && next >= data.size)
            if (query != null) put("matched", matched)
        }
    }
}
