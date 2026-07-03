package me.avinas.tempo.data.local

import me.avinas.tempo.data.local.entities.ListeningEvent
import java.security.MessageDigest

/**
 * Layer 1 of the import reconciliation pipeline.
 *
 * A content fingerprint is a deterministic SHA-256 hash of the fields that
 * uniquely identify a single play *from a given source*:
 *
 *   source | track_id | timestamp | playDuration | endTimestamp
 *
 * Because the fingerprint is built from the values as they are stored, importing
 * the same data twice (e.g. re-importing a Spotify JSON export) produces
 * identical fingerprints and is therefore a complete no-op — the count-inflation
 * loophole is closed at the source level, immune to timestamp drift.
 *
 * Cross-source duplicates (the same physical play recorded by both Spotify and
 * Last.fm, whose normalized timestamps can drift by ~60s) produce *different*
 * fingerprints and are handled by [SourceAuthority] + temporal reconciliation
 * (Layer 2) in the DAO.
 *
 * Legacy rows written before this column existed have a NULL fingerprint and
 * simply fall back to temporal dedup — they are never falsely matched.
 */
object EventFingerprint {

    private val HEX = "0123456789abcdef".toCharArray()

    fun compute(
        source: String,
        trackId: Long,
        timestamp: Long,
        playDuration: Long,
        endTimestamp: Long?
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$source|$trackId|$timestamp|$playDuration|${endTimestamp ?: 0L}"
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** Compute the fingerprint for an already-built [ListeningEvent]. */
    fun compute(event: ListeningEvent): String =
        compute(event.source, event.track_id, event.timestamp, event.playDuration, event.endTimestamp)
}
