package me.avinas.tempo.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ArtistParser.isPlaceholderArtistName] — the guard that keeps
 * Google Takeout's structural subtitle labels ("Release", "Song", ...) from
 * ever being stored as an artist.
 */
class ArtistParserPlaceholderTest {

    @Test
    fun `structural takeout labels are placeholders`() {
        assertTrue(ArtistParser.isPlaceholderArtistName("Release"))
        assertTrue(ArtistParser.isPlaceholderArtistName("release"))
        assertTrue(ArtistParser.isPlaceholderArtistName("  Release  "))
        assertTrue(ArtistParser.isPlaceholderArtistName("Song"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Video"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Album"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Playlist"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Topic"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Single"))
        assertTrue(ArtistParser.isPlaceholderArtistName("Channel"))
    }

    @Test
    fun `blank names are placeholders`() {
        assertTrue(ArtistParser.isPlaceholderArtistName(""))
        assertTrue(ArtistParser.isPlaceholderArtistName("   "))
    }

    @Test
    fun `real artist names are never placeholders`() {
        assertFalse(ArtistParser.isPlaceholderArtistName("Radiohead"))
        assertFalse(ArtistParser.isPlaceholderArtistName("The Beatles"))
        assertFalse(ArtistParser.isPlaceholderArtistName("BTS"))
        assertFalse(ArtistParser.isPlaceholderArtistName("Release Theory"))
        assertFalse(ArtistParser.isPlaceholderArtistName("The Releases"))
        assertFalse(ArtistParser.isPlaceholderArtistName(" Kendrick Lamar "))
        // Names that merely *contain* a structural word must not match.
        assertFalse(ArtistParser.isPlaceholderArtistName("Playlist Club"))
        assertFalse(ArtistParser.isPlaceholderArtistName("Video Kids"))
    }
}
