package me.avinas.tempo.data.local.dao

import kotlin.math.abs

/**
 * Choose the closest matching physical playback among the candidates allowed
 * by source and producer-origin constraints. Looking up the first matching row
 * can attach a producer identity to the wrong rapid replay, because SQLite
 * does not guarantee row order without an ORDER BY.
 *
 * Equal-distance ties retain the first eligible index, so callers can provide
 * candidates in a stable timestamp-and-id ordering.
 */
internal inline fun <T> nearestEligiblePlaybackIndex(
    slots: List<T>,
    incomingTimestamp: Long,
    timestampOf: (T) -> Long,
    eligible: (T) -> Boolean,
): Int {
    var best = -1
    var bestDistance = Long.MAX_VALUE
    for ((index, slot) in slots.withIndex()) {
        if (!eligible(slot)) continue
        val distance = abs(timestampOf(slot) - incomingTimestamp)
        if (best == -1 || distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}
