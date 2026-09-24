package me.avinas.tempo.data.enrichment

import me.avinas.tempo.utils.ArtistParser

private val COVER_VERSION_MARKERS = setOf(
    "remaster",
    "remastered",
    "remix",
    "mix",
    "edit",
    "version",
    "live",
    "acoustic",
    "instrumental",
    "mono",
    "stereo",
    "deluxe",
    "edition",
)

private val COVER_VERSION_ALLOWED_TOKENS = COVER_VERSION_MARKERS + setOf(
    "radio",
    "single",
    "album",
    "extended",
    "club",
    "original",
    "anniversary",
    "bonus",
    "digital",
)

/**
 * Conservative title matching for user-facing cover candidates.
 *
 * Exact normalized titles always match. A prefix match is accepted only when the
 * extra suffix clearly describes a version of the same recording (for example
 * "2011 remaster", "live", or "radio edit"). Ordinary longer titles such as
 * "Stay High" or "Homecoming" are deliberately rejected.
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

    fun isRecognizedVersionSuffix(base: String, longer: String): Boolean {
        if (!longer.startsWith("$base ")) return false
        val suffixTokens = longer
            .removePrefix(base)
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }

        val hasVersionMarker = suffixTokens.any { token -> token in COVER_VERSION_MARKERS }
        val containsOnlyVersionTokens = suffixTokens.all { token ->
            token in COVER_VERSION_ALLOWED_TOKENS ||
                token.all(Char::isDigit)
        }

        return hasVersionMarker && containsOnlyVersionTokens
    }

    return isRecognizedVersionSuffix(expected, candidate) ||
        isRecognizedVersionSuffix(candidate, expected)
}

/**
 * Strict artist validation for artwork providers.
 *
 * Provider candidates must contain at least one parsed artist that is an exact
 * normalized match (allowing only the existing "The X" vs "X" normalization).
 * This intentionally avoids the broader fuzzy/Jaccard matcher used elsewhere in
 * Tempo, so "Drake" cannot validate "Drake Bell".
 */
internal fun isSafeCoverArtistMatch(
    expectedArtist: String,
    candidateArtist: String,
): Boolean {
    if (ArtistParser.isUnknownArtist(expectedArtist) ||
        ArtistParser.isUnknownArtist(candidateArtist)
    ) {
        return false
    }

    val expectedArtists = ArtistParser.getAllArtists(expectedArtist)
    val candidateArtists = ArtistParser.getAllArtists(candidateArtist)

    return expectedArtists.any { expected ->
        candidateArtists.any { candidate ->
            ArtistParser.isStrictSameArtist(expected, candidate)
        }
    }
}
