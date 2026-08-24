package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Unicode / multi-script support in track matching.
 *
 * Background: [TrackMatcher] used to NFD-decompose strings and strip ALL
 * combining marks. That destroyed scripts where marks are semantic:
 * - Hangul syllables decompose into jamo, and the vowel/final jamo are
 *   combining marks — "아이유" collapsed to "ㅇㅇ" and distinct Korean
 *   artists/titles falsely merged.
 * - Devanagari matras, Thai vowels/tone marks and Arabic harakat were also
 *   stripped, merging distinct words.
 *
 * The fix folds diacritics only for Latin/Greek/Cyrillic bases and preserves
 * every other script's marks (see [UnicodeUtils.foldForMatching]).
 */
class TrackMatcherUnicodeTest {

    // normalizeTitle / normalizeArtist

    @Test
    fun `korean artist names survive normalization`() {
        val iu = TrackMatcher.normalizeArtist("아이유")
        val bts = TrackMatcher.normalizeArtist("방탄소년단")
        assertTrue(iu.isNotBlank())
        assertTrue(bts.isNotBlank())
        assertNotEquals(iu, bts)
        assertEquals(iu, TrackMatcher.normalizeArtist("아이유"))
    }

    @Test
    fun `hangul syllables do not collapse to leading consonants`() {
        // Old bug: NFD + strip-all-marks reduced both of these to "ㅈ",
        // falsely matching two different artists.
        assertNotEquals(
            TrackMatcher.normalizeArtist("지드래곤"),
            TrackMatcher.normalizeArtist("지코")
        )
    }

    @Test
    fun `devanagari matras are preserved`() {
        val krishna = TrackMatcher.normalizeArtist("कृष्णा")
        val rishabh = TrackMatcher.normalizeArtist("ऋषभ")
        assertTrue(krishna.isNotBlank())
        assertNotEquals(krishna, rishabh)
        assertEquals(krishna, TrackMatcher.normalizeArtist("कृष्णा"))
    }

    @Test
    fun `thai vowel and tone marks are preserved`() {
        val tha = TrackMatcher.normalizeTitle("ที่ไหน")
        val thi = TrackMatcher.normalizeTitle("ทีไหน")
        assertNotEquals(tha, thi)
        assertTrue(tha.isNotBlank())
    }

    @Test
    fun `arabic names normalize distinctly`() {
        val amr = TrackMatcher.normalizeArtist("عمرو دياب")
        val kadim = TrackMatcher.normalizeArtist("كاظم الساهر")
        assertNotEquals(amr, kadim)
        assertEquals(amr, TrackMatcher.normalizeArtist("عمرو دياب"))
    }

    @Test
    fun `latin diacritics still fold for matching`() {
        assertEquals(
            TrackMatcher.normalizeTitle("Beyoncé"),
            TrackMatcher.normalizeTitle("Beyonce")
        )
        assertEquals(
            TrackMatcher.normalizeArtist("Stéphane Grappelli"),
            TrackMatcher.normalizeArtist("Stephane Grappelli")
        )
    }

    @Test
    fun `full-width latin folds to ascii`() {
        assertEquals(
            TrackMatcher.normalizeArtist("ＹＯＡＳＯＢＩ"),
            TrackMatcher.normalizeArtist("YOASOBI")
        )
    }

    @Test
    fun `half-width katakana folds to full-width`() {
        assertEquals(
            TrackMatcher.normalizeTitle("ヨルシカ"),
            TrackMatcher.normalizeTitle("ﾖﾙｼｶ")
        )
    }

    @Test
    fun `invisible zero-width characters are stripped`() {
        assertEquals(
            TrackMatcher.normalizeArtist("아이유​"), // ZWSP injected after 아이유
            TrackMatcher.normalizeArtist("아이유")
        )
        assertEquals(
            TrackMatcher.normalizeTitle("Lem\u200Bon"),
            TrackMatcher.normalizeTitle("Lemon")
        )
    }

    // matchTracks

    @Test
    fun `identical japanese tracks match exactly`() {
        val result = TrackMatcher.matchTracks("Lemon", "米津玄師", "Lemon", "米津玄師")
        assertTrue(result.isMatch)
        assertEquals(MatchType.EXACT, result.matchType)
    }

    @Test
    fun `different japanese artists do not match`() {
        val result = TrackMatcher.matchTracks("Lemon", "米津玄師", "Lemon", "宇多田ヒカル")
        assertFalse(result.isMatch)
    }

    @Test
    fun `different korean titles by same artist do not match`() {
        val result = TrackMatcher.matchTracks("봄날", "방탄소년단", "피 땀 눈물", "방탄소년단")
        assertFalse(result.isMatch)
    }

    @Test
    fun `hindi tracks match themselves`() {
        val result = TrackMatcher.matchTracks("कैसा है तू", "अरिजीत सिंह", "कैसा है तू", "अरिजीत सिंह")
        assertTrue(result.isMatch)
    }

    // findBestMatch

    @Test
    fun `findBestMatch finds korean candidate`() {
        val candidates = listOf(
            TrackCandidate(1, "봄날", "방탄소년단"),
            TrackCandidate(2, "피 땀 눈물", "방탄소년단")
        )
        val result = TrackMatcher.findBestMatch("봄날", "방탄소년단", candidates)
        assertNotNull(result)
        assertEquals(1L, result!!.first.id)
    }

    @Test
    fun `findBestMatch finds japanese candidate among latin noise`() {
        val candidates = listOf(
            TrackCandidate(1, "Shape of You", "Ed Sheeran"),
            TrackCandidate(2, "マリーゴールド", "あいみょん"),
            TrackCandidate(3, "Lemon", "米津玄師")
        )
        val result = TrackMatcher.findBestMatch("Lemon", "米津玄師", candidates)
        assertNotNull(result)
        assertEquals(3L, result!!.first.id)
    }

    @Test
    fun `findBestMatch matches title with percent sign`() {
        // '%' must be treated literally, never as a wildcard
        val candidates = listOf(
            TrackCandidate(1, "100% Love", "Sunidhi Chauhan"),
            TrackCandidate(2, "1000 Miles", "Vanessa Carlton")
        )
        val result = TrackMatcher.findBestMatch("100% Love", "Sunidhi Chauhan", candidates)
        assertNotNull(result)
        assertEquals(1L, result!!.first.id)
    }

    @Test
    fun `findBestMatch matches single-character cjk title`() {
        // Single CJK characters are complete words and must not be dropped
        // by the word pre-filter.
        val candidates = listOf(
            TrackCandidate(1, "光", "米津玄師"),
            TrackCandidate(2, "闇", "米津玄師")
        )
        val result = TrackMatcher.findBestMatch("光", "米津玄師", candidates)
        assertNotNull(result)
        assertEquals(1L, result!!.first.id)
    }

    // UnicodeUtils

    @Test
    fun `foldForMatching keeps korean intact`() {
        assertEquals("한글", UnicodeUtils.foldForMatching("한글"))
    }

    @Test
    fun `foldForMatching folds latin diacritics only`() {
        assertEquals("e", UnicodeUtils.foldForMatching("é"))
        assertEquals("bjork", UnicodeUtils.foldForMatching("Björk").lowercase())
        // Multiple stacked diacritics (Vietnamese) must all fold away
        assertEquals("e", UnicodeUtils.foldForMatching("ế"))
        // Cyrillic diacritics fold too, letters preserved
        assertEquals("Кот", UnicodeUtils.foldForMatching("Кот"))
    }

    @Test
    fun `foldForMatching ascii fast path is identity`() {
        assertEquals("plain ascii 123", UnicodeUtils.foldForMatching("plain ascii 123"))
    }

    @Test
    fun `stripInvisibleChars removes zero-width junk`() {
        assertEquals("아이유", UnicodeUtils.stripInvisibleChars("아\u200B이\u200C유\uFEFF"))
        assertEquals("ab", UnicodeUtils.stripInvisibleChars("a\u200Db"))
    }
}
