package me.avinas.tempo.data.analytics

import kotlin.random.Random

/**
 * Mints the anonymous session id.
 *
 * The format is `epochSeconds` + 8 random digits, as required by the Aptabase ingest
 * contract. Critically, the id exists **in memory only** and rotates after
 * [IDLE_TIMEOUT_MS] of inactivity: a new process always starts a new session, and the id is
 * never written to disk. That is what stops it from silently becoming a persistent device
 * identifier, which would break the "no identifiers" promise.
 */
internal class AnalyticsSession(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) {

    private var currentId: String? = null
    private var lastActivityMillis = 0L

    fun id(): String {
        val now = clockMillis()
        val existing = currentId
        if (existing == null || now - lastActivityMillis > IDLE_TIMEOUT_MS) {
            val fresh = format(now, random)
            currentId = fresh
            lastActivityMillis = now
            return fresh
        }
        lastActivityMillis = now
        return existing
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 60L * 60L * 1000L

        /** Epoch seconds padded to 10 digits, then 8 random digits — always 18 characters. */
        private const val FORMAT = "%010d%08d"
        private const val RANDOM_BOUND = 100_000_000

        fun format(nowMillis: Long, random: Random): String =
            FORMAT.format(nowMillis / 1000L, random.nextInt(0, RANDOM_BOUND))
    }
}
