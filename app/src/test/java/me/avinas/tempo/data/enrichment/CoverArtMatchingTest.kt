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
    fun ordinaryLongerTitleIsRejected() {
        assertFalse(isSafeCoverTrackTitleMatch("Stay", "Stay High"))
        assertFalse(isSafeCoverTrackTitleMatch("Home", "Homecoming"))
    }

    @Test
    fun recognizedVersionSuffixCanMatch() {
        assertTrue(isSafeCoverTrackTitleMatch("Dreams", "Dreams 2011 Remaster"))
        assertTrue(isSafeCoverTrackTitleMatch("Song", "Song Live"))
        assertTrue(isSafeCoverTrackTitleMatch("Song", "Song Radio Edit"))
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

    @Test
    fun strictArtistMatchAllowsSameArtistAndThePrefix() {
        assertTrue(isSafeCoverArtistMatch("The Weeknd", "Weeknd"))
        assertTrue(isSafeCoverArtistMatch("Dua Lipa feat. DaBaby", "Dua Lipa"))
    }

    @Test
    fun strictArtistMatchRejectsContainedDifferentArtist() {
        assertFalse(isSafeCoverArtistMatch("Drake", "Drake Bell"))
        assertFalse(isSafeCoverArtistMatch("Queen", "Queen Latifah"))
    }

    @Test
    fun strictArtistMatchRejectsUnknownArtist() {
        assertFalse(isSafeCoverArtistMatch("Unknown Artist", "Drake"))
    }
}
