package me.avinas.tempo.utils

/**
 * Replaces one artist name inside a multi-artist string, segment by segment.
 *
 * The old SQL implementation (LIKE + CASE + SUBSTR) only understood the ", "
 * separator and treated '%' / '_' in artist names as wildcards, so names like
 * "100%" or "_test_" corrupted unrelated rows. This implementation splits the
 * string on every separator the app's parser understands, compares whole
 * segments case-insensitively, and rebuilds the string with the original
 * separators preserved.
 *
 * Supported separators: , & | / + and the words x, feat., ft., featuring, with
 * (including parenthesized "(feat. X)" / "[ft. X]" forms).
 */
object ArtistNameReplacer {

    /**
     * Separator pattern. Alternatives are ordered longest-first so the
     * parenthesized featuring forms win over the bare word forms.
     * Punctuation separators consume surrounding whitespace; word separators
     * require whitespace on both sides so they never match inside a name.
     */
    private val SEPARATOR_REGEX = Regex(
        "\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+" +
            "|\\s+(?:feat\\.?|ft\\.?|featuring|with|x)\\s+" +
            "|\\s*(?:,|&|\\||/|\\+)\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * Replace every segment of [artistString] that equals [oldName]
     * (case-insensitive, ignoring surrounding brackets) with [newName].
     *
     * Returns the rebuilt string, or null when no segment matched.
     */
    fun replaceSegment(artistString: String, oldName: String, newName: String): String? {
        if (oldName.isBlank() || artistString.isBlank()) return null

        val parts = splitPreservingSeparators(artistString)
        var changed = false
        val rebuilt = StringBuilder(artistString.length)

        for (part in parts) {
            if (part.isSeparator) {
                rebuilt.append(part.text)
                continue
            }
            val replaced = replaceIfSegmentMatches(part.text, oldName, newName)
            if (replaced != null) {
                rebuilt.append(replaced)
                changed = true
            } else {
                rebuilt.append(part.text)
            }
        }

        return if (changed) rebuilt.toString() else null
    }

    /**
     * Split [artistString] into alternating segment/separator parts,
     * preserving every character of the original string.
     */
    private fun splitPreservingSeparators(artistString: String): List<Part> {
        val parts = mutableListOf<Part>()
        var cursor = 0
        for (match in SEPARATOR_REGEX.findAll(artistString)) {
            if (match.range.first > cursor) {
                parts.add(Part(artistString.substring(cursor, match.range.first), isSeparator = false))
            }
            parts.add(Part(match.value, isSeparator = true))
            cursor = match.range.last + 1
        }
        if (cursor < artistString.length) {
            parts.add(Part(artistString.substring(cursor), isSeparator = false))
        }
        return parts
    }

    /**
     * Return the replacement for one segment, or null when it does not match.
     * Leading/trailing whitespace and bracket decorations ("(X)", "[X]") are
     * preserved around the new name.
     */
    private fun replaceIfSegmentMatches(segmentText: String, oldName: String, newName: String): String? {
        val leading = segmentText.takeWhile { it.isWhitespace() }
        val trailing = segmentText.takeLastWhile { it.isWhitespace() }
        val core = segmentText.substring(leading.length, segmentText.length - trailing.length)
        if (core.isEmpty()) return null

        val open = core.takeWhile { it == '(' || it == '[' }
        val close = core.takeLastWhile { it == ')' || it == ']' }
        val inner = core.substring(open.length, core.length - close.length).trim()
        if (inner.isEmpty()) return null

        if (!inner.equals(oldName, ignoreCase = true)) return null

        return buildString {
            append(leading)
            append(open)
            append(newName)
            append(close)
            append(trailing)
        }
    }

    private data class Part(val text: String, val isSeparator: Boolean)
}
