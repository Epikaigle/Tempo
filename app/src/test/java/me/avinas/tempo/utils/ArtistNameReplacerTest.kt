package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ArtistNameReplacer], the Kotlin-side replacement used during
 * artist merge to rewrite multi-artist track strings. It replaced a fragile
 * LIKE-based SQL UPDATE that only understood ", " and treated '%' / '_' in
 * artist names as wildcards.
 */
class ArtistNameReplacerTest {

    @Test
    fun `replaces first segment`() {
        assertEquals(
            "NewArtist, OtherArtist",
            ArtistNameReplacer.replaceSegment("OldArtist, OtherArtist", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `replaces last segment`() {
        assertEquals(
            "OtherArtist, NewArtist",
            ArtistNameReplacer.replaceSegment("OtherArtist, OldArtist", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `replaces middle segment`() {
        assertEquals(
            "A, NewArtist, C",
            ArtistNameReplacer.replaceSegment("A, OldArtist, C", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `replacement is case insensitive`() {
        assertEquals(
            "NewArtist, B",
            ArtistNameReplacer.replaceSegment("OLDARTIST, B", "oldartist", "NewArtist")
        )
    }

    @Test
    fun `does not replace partial names`() {
        // "Art" must not match inside "Artie Shaw"
        assertNull(ArtistNameReplacer.replaceSegment("Artie Shaw, B", "Art", "NewArt"))
    }

    @Test
    fun `handles ampersand separator`() {
        assertEquals(
            "NewArtist & B",
            ArtistNameReplacer.replaceSegment("OldArtist & B", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `handles pipe separator`() {
        assertEquals(
            "A | NewArtist",
            ArtistNameReplacer.replaceSegment("A | OldArtist", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `handles slash separator`() {
        assertEquals(
            "NewArtist / B",
            ArtistNameReplacer.replaceSegment("OldArtist / B", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `handles featuring separator`() {
        assertEquals(
            "A feat. NewArtist",
            ArtistNameReplacer.replaceSegment("A feat. OldArtist", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `handles parenthesized featuring`() {
        assertEquals(
            "A (feat. NewArtist)",
            ArtistNameReplacer.replaceSegment("A (feat. OldArtist)", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `handles bracketed featuring`() {
        assertEquals(
            "A [ft. NewArtist]",
            ArtistNameReplacer.replaceSegment("A [ft. OldArtist]", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `names with percent and underscore are matched literally`() {
        // The old SQL LIKE treated these as wildcards and corrupted rows
        assertEquals(
            "New100%, B",
            ArtistNameReplacer.replaceSegment("100%, B", "100%", "New100%")
        )
        assertEquals(
            "A, _new",
            ArtistNameReplacer.replaceSegment("A, _old", "_old", "_new")
        )
    }

    @Test
    fun `replaces all matching segments`() {
        assertEquals(
            "NewArtist, B, NewArtist",
            ArtistNameReplacer.replaceSegment("OldArtist, B, OldArtist", "OldArtist", "NewArtist")
        )
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(ArtistNameReplacer.replaceSegment("A, B", "C", "D"))
    }

    @Test
    fun `returns null for blank inputs`() {
        assertNull(ArtistNameReplacer.replaceSegment("", "A", "B"))
        assertNull(ArtistNameReplacer.replaceSegment("A, B", "", "B"))
    }

    @Test
    fun `word separator does not match inside a name`() {
        // "x" as a letter inside a name must not act as a separator
        assertNull(ArtistNameReplacer.replaceSegment("Maxwell, B", "well", "New"))
    }
}
