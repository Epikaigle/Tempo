package me.avinas.tempo.data.musicolet

import java.time.Instant
import java.time.ZoneId

/**
 * Places a period's play count onto a timeline.
 *
 * Musicolet counts plays per period and never records when a play happened, so every
 * timestamp this produces is an estimate. Two properties make that acceptable:
 *
 *  * **Deterministic.** The same backup always yields the same timestamps, so the
 *    content fingerprint of each event is stable and re-importing is a no-op rather
 *    than a second copy of the listener's history.
 *  * **Bounded and ordered.** Plays are spread evenly across the period, kept at
 *    least [MIN_SPACING_MS] apart, and never placed after [Bucket]'s end or the
 *    "not after" instant an import supplies. A play that will not fit is reported as
 *    dropped instead of being stacked on top of another or pushed into the future.
 *
 * Plays land between 07:00 and midnight local time. Musicolet cannot know the hour
 * either, and a uniform spread over the whole day would invent overnight listening
 * that never happened, inflating night-owl statistics and the hour histogram. The
 * cost is that hour-of-day and night-owl figures for imported plays are neutral
 * rather than measured, which is the honest reading of data that has no hour in it.
 */
internal object MusicoletPlaySynthesizer {
    /** Floor on the gap between two plays, matching the DAO's same-source tolerance. */
    const val MIN_SPACING_MS = 6_000L

    /** A period's window, half-open: `[startMs, endMs)`. */
    data class Bucket(
        val startMs: Long,
        val endMs: Long,
    )

    /** Placed timestamps in ascending order, and how many could not be placed. */
    data class Placement(
        val timestamps: List<Long>,
        val dropped: Long,
    )

    /** Upper bound on plays for one song in one period; far beyond real listening. */
    const val MAX_PLAYS_PER_BUCKET = 20_000

    private const val WAKING_START_MS = 7L * 3_600_000L
    private const val WAKING_LENGTH_MS = 17L * 3_600_000L

    /**
     * Spreads [requested] plays across [bucket], stopping at [notAfterMs].
     *
     * [notAfterMs] is normally "now", so a period still in progress (the current month)
     * never receives plays dated in its own future.
     */
    fun spread(
        bucket: Bucket,
        requested: Int,
        notAfterMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Placement {
        val windowEnd = minOf(bucket.endMs, notAfterMs)
        val windowStart = bucket.startMs
        val windowMillis = windowEnd - windowStart
        if (requested <= 0 || windowMillis < MIN_SPACING_MS) {
            return Placement(emptyList(), requested.coerceAtLeast(0).toLong())
        }

        // Never mint more plays than the window can hold distinctly.
        val capacity = (windowMillis / MIN_SPACING_MS).toInt()
        val count = minOf(requested, capacity, MAX_PLAYS_PER_BUCKET)
        if (count <= 0) return Placement(emptyList(), requested.toLong())

        val placed = ArrayList<Long>(count)
        var previous = Long.MIN_VALUE
        for (index in 0 until count) {
            // Centre of this play's slot across the window: even, ordered, and free of
            // any randomness that would make the result differ between imports.
            val slotCentre = windowStart + ((2L * index + 1L) * windowMillis) / (2L * count)
            val snapped = snapIntoWakingHours(slotCentre, zone).coerceIn(windowStart, windowEnd - 1L)
            val timestamp = maxOf(snapped, previous + MIN_SPACING_MS)
            if (timestamp > windowEnd - 1L) break
            placed.add(timestamp)
            previous = timestamp
        }

        return Placement(placed, (requested - placed.size).toLong())
    }

    /**
     * Moves an instant to the same fraction of its own local day, but inside the
     * waking window, so a period's plays stay ordered while their hour becomes
     * 07:00–23:59. The day length is read from the zone so DST days stay correct.
     */
    private fun snapIntoWakingHours(
        epochMillis: Long,
        zone: ZoneId,
    ): Long {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val dayStart = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayLength = (dayEnd - dayStart).coerceAtLeast(1L)
        val fraction = ((epochMillis - dayStart).toDouble() / dayLength).coerceIn(0.0, 1.0)
        return dayStart + WAKING_START_MS + (fraction * WAKING_LENGTH_MS).toLong()
    }
}
