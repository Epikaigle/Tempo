package me.avinas.tempo.utils

import java.text.Normalizer

/**
 * Unicode-aware text normalization helpers shared by [ArtistParser] and [TrackMatcher].
 *
 * Music metadata regularly contains names written in any writing system
 * (Hangul, Kanji/kana, Devanagari, Thai, Arabic, Cyrillic, ...) plus stylized
 * special characters (Ke$ha, P!nk, full-width "ＹＯＡＳＯＢＩ", zero-width junk
 * pasted from web players). These helpers make comparison robust without ever
 * destroying a script's meaningful characters.
 */
object UnicodeUtils {

    // Zero-width and bidi/format control characters that are invisible but
    // break string equality (ZWSP, ZWNJ, ZWJ, LRM/RLM, BOM, soft hyphen, ...).
    // Commonly injected by web players, share sheets and copy/paste.
    private val INVISIBLE_CHARS_PATTERN = Regex(
        "[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u206A-\\u206F\\uFEFF]"
    )

    /**
     * Remove invisible zero-width / format characters that would otherwise
     * make two visually identical strings compare as different.
     */
    fun stripInvisibleChars(input: String): String =
        if (input.isEmpty()) input else INVISIBLE_CHARS_PATTERN.replace(input, "")

    /**
     * True if the string contains at least one non-ASCII letter (CJK, Hangul,
     * kana, Thai, Arabic, Devanagari, Cyrillic, ...). Used to decide whether a
     * short token is meaningful: a single character is a complete word in
     * many scripts, unlike in Latin.
     */
    fun hasNonAsciiLetter(s: String): Boolean {
        for (ch in s) {
            if (ch.code > 0x7F && Character.isLetter(ch.code)) return true
        }
        return false
    }

    /**
     * Normalize a string for fuzzy matching across scripts.
     *
     * Pipeline:
     * 1. NFKC — folds compatibility variants: full-width "ＹＯ" -> "YO",
     *    half-width katakana "ﾖﾙｼｶ" -> "ヨルシカ", ligatures "ﬁ" -> "fi".
     * 2. NFD decomposition, then selectively strip combining marks — but ONLY
     *    when they are attached to a Latin/Greek/Cyrillic base letter, where
     *    they are pure diacritics ("é" -> "e", "ą" -> "a").
     * 3. NFC recomposition.
     *
     * Marks of every other script are PRESERVED because they are
     * semantically load-bearing:
     * - Hangul jamo vowels/finals (NFD of "한" = ᄒ + ᅡ + ᆫ — stripping them
     *   would collapse distinct Korean names)
     * - Devanagari/Indic matras and virama (कृष्णा vs कषण are different words)
     * - Thai vowels and tone marks (distinct letters, not diacritics)
     * - Arabic harakat and Hebrew niqqud
     */
    fun foldForMatching(input: String): String {
        if (input.isEmpty()) return input
        // Fast path: pure ASCII contains no compatibility variants or marks.
        var isAscii = true
        for (ch in input) {
            if (ch.code >= 0x80) { isAscii = false; break }
        }
        if (isAscii) return input

        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val decomposed = Normalizer.normalize(nfkc, Normalizer.Form.NFD)

        val sb = StringBuilder(decomposed.length)
        var latinLikeBase = false
        var index = 0
        while (index < decomposed.length) {
            val cp = decomposed.codePointAt(index)
            index += Character.charCount(cp)
            if (isCombiningMark(cp) && latinLikeBase) {
                // Diacritic on a Latin/Greek/Cyrillic base: drop it (é -> e).
                continue
            }
            sb.appendCodePoint(cp)
            if (!isCombiningMark(cp)) {
                latinLikeBase = isLatinLikeBase(cp)
            }
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    /** Mn / Mc — combining marks that follow a base character. */
    private fun isCombiningMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    /**
     * Scripts whose combining marks are decorative diacritics that can be
     * safely folded for fuzzy matching: Latin (all extensions), Greek,
     * Cyrillic (all extensions).
     */
    private fun isLatinLikeBase(cp: Int): Boolean = when (cp) {
        in 0x41..0x5A, in 0x61..0x7A -> true            // Basic Latin
        in 0xC0..0x24F -> true                           // Latin-1 Suppl. + Ext-A/B
        in 0x1E00..0x1EFF -> true                        // Latin Ext Additional
        in 0x2C60..0x2C7F -> true                        // Latin Ext-C
        in 0xA720..0xA7FF -> true                        // Latin Ext-D
        in 0x0370..0x03FF, in 0x1F00..0x1FFF -> true     // Greek
        in 0x0400..0x052F -> true                        // Cyrillic
        in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> true     // Cyrillic Ext
        else -> false
    }
}
