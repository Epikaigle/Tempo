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

/**
 * A fuzzy Drive match is safe only when it identifies one eligible playback.
 * Multiple nearby plays can represent independent replays: preserve incoming
 * provenance instead of binding it to an arbitrary historical row.
 */
internal inline fun <T> uniqueEligiblePlaybackIndex(
    slots: List<T>,
    eligible: (T) -> Boolean,
): Int {
    var result = -1
    for ((index, slot) in slots.withIndex()) {
        if (!eligible(slot)) continue
        if (result != -1) return -1
        result = index
    }
    return result
}
