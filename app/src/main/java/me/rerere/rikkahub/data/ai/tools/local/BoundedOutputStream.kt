// Adapted from ExTV/rikkahub-agent, commit a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.
// SPDX-License-Identifier: AGPL-3.0-only
// 1.0 adaptations: local tool approval, current UI APIs and extension navigation.

package me.rerere.rikkahub.data.ai.tools.local

import java.io.ByteArrayOutputStream

internal class BoundedOutputStream(private val cap: Int) : java.io.OutputStream() {
    private val buf = ByteArrayOutputStream(minOf(cap + SLACK, 16_384))
    /** Total bytes the remote process wrote, including the ones we discarded. */
    private var total: Long = 0L

    override fun write(b: Int) {
        total += 1
        if (buf.size() < cap + SLACK) buf.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        total += len
        val remaining = (cap + SLACK) - buf.size()
        if (remaining > 0) buf.write(b, off, minOf(len, remaining))
    }

    /**
     * Render the captured bytes with a "[truncated; N bytes more]" tail derived from the true
     * byte count we observed — the discarded bytes never hit the heap, so a post-hoc length
     * check on the buffer alone couldn't know about them.
     *
     * Everything here is byte-accurate: we keep at most [cap] bytes (snapped down to a UTF-8
     * code-point boundary so a trailing multi-byte sequence is never split) and report the
     * remainder as (total bytes written minus bytes kept). When nothing exceeded [cap] there is no
     * marker, even for multibyte text whose byte count outruns its char count.
     */
    fun snapshot(): String {
        val bytes = buf.toByteArray()
        if (total <= cap.toLong()) return String(bytes, Charsets.UTF_8)
        // Keep up to [cap] bytes, backing off to a code-point boundary so we don't slice a
        // multi-byte UTF-8 sequence in half. The buffer holds cap + SLACK bytes, so there is
        // always at least one whole code point's worth of headroom past the cut.
        var keep = minOf(cap, bytes.size)
        while (keep in 1 until bytes.size && (bytes[keep].toInt() and 0xC0) == 0x80) keep--
        val kept = String(bytes, 0, keep, Charsets.UTF_8)
        return kept + "\n…[truncated; ${total - keep.toLong()} bytes more]"
    }

    private companion object {
        /** Headroom past [cap] so a final multi-byte UTF-8 sequence isn't split. */
        const val SLACK = 4_096
    }
}
