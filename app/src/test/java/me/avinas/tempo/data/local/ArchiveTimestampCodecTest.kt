package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the delta-encoded timestamp blob codec used by
 * `scrobbles_archive.timestamps_blob`. The format is load-bearing: blobs
 * written by older app versions (and by backups) must decode identically.
 */
class ArchiveTimestampCodecTest {
    @Test
    fun `empty list compresses to empty blob`() {
        val blob = ArchiveTimestampCodec.compress(emptyList())
        assertEquals(0, blob.size)
        assertTrue(ArchiveTimestampCodec.decompress(blob).isEmpty())
    }

    @Test
    fun `single timestamp round trips`() {
        val timestamps = listOf(1_700_000_000_000L)
        val decoded = ArchiveTimestampCodec.decompress(ArchiveTimestampCodec.compress(timestamps))
        assertEquals(timestamps, decoded)
    }

    @Test
    fun `multiple timestamps round trip exactly`() {
        val timestamps =
            listOf(
                1_600_000_000_000L,
                1_600_000_060_000L, // +60s
                1_600_000_120_000L, // +60s
                1_600_003_720_000L, // +1h
            )
        val decoded = ArchiveTimestampCodec.decompress(ArchiveTimestampCodec.compress(timestamps))
        assertEquals(timestamps, decoded)
    }

    @Test
    fun `large gaps round trip`() {
        // Multi-year gaps exercise the Int delta range (seconds)
        val timestamps =
            listOf(
                1_000_000_000_000L,
                1_000_000_000_000L + 5L * 365 * 24 * 3600 * 1000,
            )
        val decoded = ArchiveTimestampCodec.decompress(ArchiveTimestampCodec.compress(timestamps))
        assertEquals(timestamps, decoded)
    }

    @Test
    fun `sub-second remainders are truncated to seconds`() {
        // The wire format stores deltas in whole seconds; sub-second precision
        // is intentionally lost. Document the behavior so a future "fix" does
        // not silently change the format.
        val timestamps = listOf(1_000_000_000_000L, 1_000_000_000_500L)
        val decoded = ArchiveTimestampCodec.decompress(ArchiveTimestampCodec.compress(timestamps))
        assertEquals(listOf(1_000_000_000_000L, 1_000_000_000_000L), decoded)
    }

    @Test
    fun `truncated blob degrades to empty list`() {
        val blob =
            ArchiveTimestampCodec.compress(
                listOf(1_000_000_000_000L, 1_000_000_060_000L, 1_000_000_120_000L),
            )
        val truncated = blob.copyOfRange(0, blob.size - 3)
        assertTrue(ArchiveTimestampCodec.decompress(truncated).isEmpty())
    }

    @Test
    fun `garbage blob degrades to empty list`() {
        assertTrue(ArchiveTimestampCodec.decompress(byteArrayOf(1, 2, 3)).isEmpty())
    }

    // Forged-blob hardening. Blobs also arrive inside user-supplied backup files, so a
    // hostile `count` / `delta` / base timestamp must be rejected, not trusted.

    private fun blob(
        baseTimestamp: Long,
        count: Int,
        vararg deltas: Int,
    ): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(12 + deltas.size * 4)
        buffer.putLong(baseTimestamp)
        buffer.putInt(count)
        deltas.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    @Test
    fun `count larger than the payload is rejected without allocating it`() {
        // A 12-byte blob claiming Int.MAX_VALUE timestamps used to size the result list from
        // the header alone. The guard must reject it before the first allocation.
        val forged = blob(1_700_000_000_000L, Int.MAX_VALUE)
        assertTrue(ArchiveTimestampCodec.decompress(forged).isEmpty())
    }

    @Test
    fun `count exceeding the available deltas is rejected`() {
        // 2 deltas present, header claims 4.
        val forged = blob(1_700_000_000_000L, count = 4, 60, 60)
        assertTrue(ArchiveTimestampCodec.decompress(forged).isEmpty())
    }

    @Test
    fun `negative delta is rejected`() {
        // compress() always sorts ascending, so a negative delta can only come from tampering.
        val forged = blob(1_700_000_000_000L, count = 2, -86_400)
        assertTrue(ArchiveTimestampCodec.decompress(forged).isEmpty())
    }

    @Test
    fun `implausible timestamps are preserved rather than rejected`() {
        // Deliberate: the codec does no magnitude checking. A clock-skew or date-range guard
        // would reject a valid archive written by a device whose clock was wrong, and callers
        // persist the empty result - so a bad guard destroys real history. It also buys nothing
        // against a forger, who can simply pick plausible timestamps.
        val farFuture = blob(1_700_000_000_000L, count = 2, 1_000_000_000)
        assertEquals(2, ArchiveTimestampCodec.decompress(farFuture).size)

        val ancient = blob(1L, count = 1)
        assertEquals(listOf(1L), ArchiveTimestampCodec.decompress(ancient))
    }
}
