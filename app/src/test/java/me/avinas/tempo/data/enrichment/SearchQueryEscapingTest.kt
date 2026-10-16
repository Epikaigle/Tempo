package me.avinas.tempo.data.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query builders interpolate untrusted track metadata (MediaSession metadata from
 * other apps, imported tags, the edit-title dialog) into provider search syntax.
 * These tests pin the escaping rules that stop crafted text from closing a field
 * or phrase early and appending its own clauses.
 */
class SearchQueryEscapingTest {

    // ---------------------------------------------------------------------
    // MusicBrainz (Lucene)
    // ---------------------------------------------------------------------

    /**
     * Models how Lucene reads an escaped string: a backslash escapes the character
     * after it. Any quote still "live" by that reading would terminate the phrase
     * the query builder wraps the value in.
     */
    private fun liveQuotes(value: String): List<Char> {
        val live = mutableListOf<Char>()
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '\\' -> index += 2
                '"' -> {
                    live += '"'
                    index++
                }
                else -> index++
            }
        }
        return live
    }

    @Test
    fun ordinaryTitleIsLeftAlone() {
        assertEquals("Normal Song", escapeLuceneQuery("Normal Song"))
    }

    @Test
    fun quoteIsEscapedWithASingleBackslash() {
        // Regression: escaping backslashes last turned this into a\\"b, which Lucene
        // reads as a literal backslash followed by a live, phrase-closing quote.
        assertEquals("""a\"b""", escapeLuceneQuery("""a"b"""))
    }

    @Test
    fun backslashIsEscapedExactlyOnce() {
        assertEquals("""a\\b""", escapeLuceneQuery("""a\b"""))
    }

    @Test
    fun backslashCannotSwallowTheEscapeOfAFollowingQuote() {
        // Input: a\"b  ->  a\\\"b, which Lucene reads as literal backslash + literal quote.
        val escaped = escapeLuceneQuery("""a\"b""")
        assertEquals("""a\\\"b""", escaped)
        assertTrue(liveQuotes(escaped).isEmpty())
    }

    @Test
    fun booleanAndFieldOperatorsAreEscaped() {
        assertEquals("""a\:b""", escapeLuceneQuery("a:b"))
        assertEquals("""a\&&b""", escapeLuceneQuery("a&&b"))
        assertEquals("""a\||b""", escapeLuceneQuery("a||b"))
        assertEquals("""a\+b""", escapeLuceneQuery("a+b"))
        assertEquals("""a\~b""", escapeLuceneQuery("a~b"))
    }

    @Test
    fun craftedTitleCannotEscapeTheRecordingPhrase() {
        val payload = """x" OR artist:"Drake"""

        val escaped = escapeLuceneQuery(payload)

        assertEquals("""x\" OR artist\:\"Drake""", escaped)
        assertTrue(
            "no live quote may survive escaping: $escaped",
            liveQuotes(escaped).isEmpty(),
        )
    }

    @Test
    fun builtQueryKeepsThePayloadInsideItsPhrase() {
        // Mirrors buildSearchQuery(): recording:"<title>" AND artist:"<artist>"
        val query = "recording:\"${escapeLuceneQuery("""x" OR artist:"Drake""")}\" AND artist:\"Drake\""

        assertEquals(
            """recording:"x\" OR artist\:\"Drake" AND artist:"Drake"""",
            query,
        )
        // Only the four quotes the builder itself contributes stay live: two around
        // the recording phrase and two around the artist phrase. The payload adds none.
        assertEquals(4, liveQuotes(query).size)
    }

    @Test
    fun payloadWithOnlyQuotesCannotBreakOut() {
        for (payload in listOf("\"", "\"\"", "\" OR \"", "a\"\"b", "\\\"", "a\" OR recording:\"b")) {
            val escaped = escapeLuceneQuery(payload)
            assertTrue(
                "payload <$payload> escaped to <$escaped> still contains a live quote",
                liveQuotes(escaped).isEmpty(),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Deezer (artist:"..." track:"...")
    // ---------------------------------------------------------------------

    @Test
    fun deezerKeepsOrdinaryValuesIntact() {
        assertEquals("Lose Yourself", sanitizeDeezerSearchValue("Lose Yourself"))
        assertEquals("AC/DC", sanitizeDeezerSearchValue("AC/DC"))
    }

    @Test
    fun deezerNeutralisesFieldBreakingQuotes() {
        val sanitized = sanitizeDeezerSearchValue("""X" artist:"Y""")

        assertEquals("X artist: Y", sanitized)
        assertTrue("quotes must not survive", !sanitized.contains('"'))
    }

    @Test
    fun deezerNeutralisesBackslashes() {
        val sanitized = sanitizeDeezerSearchValue("""a\b""")

        assertTrue(!sanitized.contains('\\'))
        assertEquals("a b", sanitized)
    }

    @Test
    fun deezerStripsQuotesFromLegitimateTitlesWithoutLeavingDoubleSpaces() {
        assertEquals("Say Hello", sanitizeDeezerSearchValue("""Say "Hello""""))
        assertEquals("Say Hello", sanitizeDeezerSearchValue("""Say  ""  Hello"""))
    }

    @Test
    fun deezerBuiltQueryCannotGainExtraFieldsFromThePayload() {
        val title = sanitizeDeezerSearchValue("""X" track:"Other" artist:"Someone""")
        val artist = sanitizeDeezerSearchValue("""Real" artist:"Fake""")

        val query = "artist:\"$artist\" track:\"$title\""

        // Only the four quotes that delimit the two intended fields remain.
        assertEquals(4, query.count { it == '"' })
        assertEquals("""artist:"Real artist: Fake" track:"X track: Other artist: Someone"""", query)
    }
}
