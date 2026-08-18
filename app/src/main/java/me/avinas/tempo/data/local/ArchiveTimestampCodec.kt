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

            // Read deltas and reconstruct timestamps
            val timestamps = mutableListOf(baseTimestamp)
            var current = baseTimestamp

            repeat(count - 1) {
                val deltaSec = input.readInt()
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
