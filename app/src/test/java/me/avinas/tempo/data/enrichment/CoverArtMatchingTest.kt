package me.avinas.tempo.data.enrichment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtMatchingTest {

    @Test
    fun exactShortTitleIsAllowed() {
        assertTrue(isSafeCoverTrackTitleMatch("XO", "XO"))
    }

    @Test
    fun shortTitleCannotMatchBySubstring() {
        assertFalse(isSafeCoverTrackTitleMatch("XO", "XOXO"))
    }

    @Test
    fun remasterSuffixDoesNotBreakARealMatch() {
        assertTrue(
            isSafeCoverTrackTitleMatch(
                "Dreams",
                "Dreams (Remastered)",
            )
        )
    }

    @Test
    fun featuredArtistSuffixDoesNotBreakARealMatch() {
        assertTrue(
            isSafeCoverTrackTitleMatch(
                "Stay",
                "Stay (feat. Guest Artist)",
            )
        )
    }

    @Test
    fun unrelatedTitlesAreRejected() {
        assertFalse(isSafeCoverTrackTitleMatch("Paracetamol", "Paradise"))
    }
}
