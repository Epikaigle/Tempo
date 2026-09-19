package me.avinas.tempo.data.local

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Delta-encoded timestamp blob codec for `scrobbles_archive.timestamps_blob`.
 *
 * Wire format (big-endian):
 *  - base timestamp: Long (milliseconds)
 *  - count: Int
 *  - count-1 deltas: Int each, in **seconds**, relative to the previous timestamp
 *
 * This object is the single source of truth for the format. It was extracted
 * from LastFmImportService so that merge operations (track/artist merge rewrite
 * archived scrobbles) and backup restore can decode and re-encode blobs without
 * the layout ever diverging between call sites.
 */
object ArchiveTimestampCodec {
    /** Long base timestamp + Int count, i.e. the bytes before the first delta. */
    private const val HEADER_BYTES = 12

    /** Each delta is one Int. */
    private const val DELTA_BYTES = 4

    /**
     * Compress timestamps using delta encoding.
     * Timestamps are assumed to be sorted ascending.
     */
    fun compress(timestamps: List<Long>): ByteArray {
        if (timestamps.isEmpty()) return ByteArray(0)

        val output = ByteArrayOutputStream()
        val dataOut = DataOutputStream(output)

        // Write first timestamp as base
        dataOut.writeLong(timestamps.first())

        // Write count
        dataOut.writeInt(timestamps.size)

        // Write deltas (in seconds to save space)
        var prev = timestamps.first()
        for (i in 1 until timestamps.size) {
            val delta = ((timestamps[i] - prev) / 1000).toInt()
            dataOut.writeInt(delta)
            prev = timestamps[i]
        }

        dataOut.flush()
        return output.toByteArray()
    }

    /**
     * Decompress timestamps from an archive blob.
     * Returns an empty list for empty or malformed blobs (never throws).
     *
     * Blobs are not necessarily ours: they also arrive inside user-supplied backup files
     * (`ImportExportManager.replayStagedArchive`), so the header is not taken on faith.
     * A 12-byte blob claiming `Int.MAX_VALUE` timestamps used to size the result list from
     * the header alone, so the count is now checked against what the payload can hold.
     *
     * Only structural checks live here. Anything that second-guesses the *values* (clock
     * skew, plausible date range) would reject a valid archive written by a device whose
     * clock was wrong, and the callers persist the empty result - so a bad guard loses real
     * history. Magnitude checks are also worthless against a deliberate forger, who can
     * always pick plausible timestamps.
     */
    fun decompress(blob: ByteArray): List<Long> {
        if (blob.isEmpty()) return emptyList()
        return try {
            val input = DataInputStream(ByteArrayInputStream(blob))

            // Read base timestamp
            val baseTimestamp = input.readLong()

            // Read count
            val count = input.readInt()

            if (count <= 1) return listOf(baseTimestamp)

            // A valid blob carries DELTA_BYTES per delta, so `count` cannot exceed what the
            // payload can hold.
            if (count - 1 > (blob.size - HEADER_BYTES) / DELTA_BYTES) return emptyList()

            // Read deltas and reconstruct timestamps
            val timestamps = mutableListOf(baseTimestamp)
            var current = baseTimestamp

            repeat(count - 1) {
                val deltaSec = input.readInt()
                // Every producer sorts ascending before compressing, so a negative delta can
                // only come from corruption or tampering - and it would put the reconstructed
                // instants out of order for consumers that rely on the ascending invariant.
                if (deltaSec < 0) return emptyList()
                current += deltaSec * 1000L
                timestamps.add(current)
            }

            timestamps
        } catch (e: IOException) {
            // Truncated or malformed blob — degrade to empty rather than throw.
            emptyList()
        }
    }
}
