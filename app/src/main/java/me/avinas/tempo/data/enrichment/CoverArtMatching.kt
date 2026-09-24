package me.avinas.tempo.data.enrichment

import me.avinas.tempo.utils.ArtistParser

/**
 * Strict-enough title matching for user-facing cover candidates.
 *
 * Exact normalized titles always match, including very short song names. Partial
 * containment is allowed only for titles of at least four characters so a short
 * title such as "XO" cannot accidentally match an unrelated longer song.
 */
internal fun isSafeCoverTrackTitleMatch(
    expectedTitle: String,
    candidateTitle: String,
): Boolean {
    val expected = ArtistParser.normalizeForSearch(
        ArtistParser.cleanTrackTitle(expectedTitle)
    )
    val candidate = ArtistParser.normalizeForSearch(
        ArtistParser.cleanTrackTitle(candidateTitle)
    )

    if (expected.isBlank() || candidate.isBlank()) return false
    if (expected == candidate) return true

    val shorterLength = minOf(expected.length, candidate.length)
    if (shorterLength < 4) return false

    return expected.contains(candidate) || candidate.contains(expected)
}
