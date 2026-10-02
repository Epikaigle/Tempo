package me.avinas.tempo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for the Google Takeout watch-history layouts.
 *
 * Classic layout: subtitles[0] = artist channel ("Artist - Topic"),
 * subtitles[1] = album (playlist link).
 *
 * Newer layouts changed the roles: the first subtitle can be the release link
 * literally named "Release" while the artist channel moved to another subtitle
 * or into the `details` array. The old positional parser stored "Release" as
 * the artist for every such entry (bogus top-3 artist, 500+ tracks), lost the
 * album, and split play counts across duplicate track rows.
 */
class YouTubeMusicImportSubtitleRolesTest {
    // The parsing paths under test never touch the DAOs/repos or the import
    // state, so we allocate the service without running its constructor.
    private val unsafe: sun.misc.Unsafe by lazy {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null) as sun.misc.Unsafe
    }

    @Suppress("UNCHECKED_CAST")
    private fun newService(): YouTubeMusicImportService =
        unsafe.allocateInstance(YouTubeMusicImportService::class.java) as YouTubeMusicImportService

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return ByteArrayInputStream(bos.toByteArray())
    }

    private fun import(historyJson: String) =
        newService().parseZipStream(
            zip("Takeout/YouTube and YouTube Music/history/watch-history.json" to historyJson),
            "takeout.zip",
        )

    @Test
    fun `classic layout keeps artist and album`() {
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Song A",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2024-05-01T15:30:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Artist A - Topic",
                 "url": "https://www.youtube.com/channel/UC111111111111111111111"},
                {"name": "Album A",
                 "url": "https://www.youtube.com/playlist?list=OLAK5uy_test"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Song A", result.parsed[0].trackName)
        assertEquals("Artist A", result.parsed[0].artistName)
        assertEquals("Album A", result.parsed[0].albumName)
    }

    @Test
    fun `release label first is not the artist when artist channel follows`() {
        // Newer layout: the release link (literally named "Release") is the
        // first subtitle; the artist channel is the second one.
        val history =
            """
            [{"header": "Watched on YouTube Music",
              "title": "Song B",
              "titleUrl": "https://www.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:00:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Release",
                 "url": "https://music.youtube.com/playlist?list=OLAK5uy_release"},
                {"name": "Artist B - Topic",
                 "url": "https://www.youtube.com/channel/UC222222222222222222222"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Artist B", result.parsed[0].artistName)
        // The real album name is not present in this layout — must stay null,
        // not "Release" and not the artist's channel name.
        assertNull(result.parsed[0].albumName)
    }

    @Test
    fun `artist is recovered from details when subtitles have no channel`() {
        // Newer layout: the only subtitle is the release link; the artist
        // channel lives in the details array.
        val history =
            """
            [{"header": "Watched on YouTube Music",
              "title": "Song C",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:05:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Release",
                 "url": "https://music.youtube.com/watch?v=abcdefghijk"}],
              "details": [
                {"name": "Artist C",
                 "url": "https://music.youtube.com/channel/UC333333333333333333333"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Artist C", result.parsed[0].artistName)
        assertNull(result.parsed[0].albumName)
    }

    @Test
    fun `named release link is still recognized as the album`() {
        // Newer layout where the release link carries the actual album title.
        val history =
            """
            [{"header": "Watched on YouTube Music",
              "title": "Song D",
              "titleUrl": "https://www.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:10:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Album D",
                 "url": "https://music.youtube.com/playlist?list=OLAK5uy_albumd"},
                {"name": "Artist D - Topic",
                 "url": "https://www.youtube.com/channel/UC444444444444444444444"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Artist D", result.parsed[0].artistName)
        assertEquals("Album D", result.parsed[0].albumName)
    }

    @Test
    fun `entry with only placeholder labels is skipped instead of pooled`() {
        // No real artist anywhere: the entry must be skipped rather than
        // imported under a bogus "Release" artist.
        val history =
            """
            [{"header": "Watched on YouTube Music",
              "title": "Song E",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:15:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Release",
                 "url": "https://music.youtube.com/playlist?list=OLAK5uy_x"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(0, result.parsed.size)
    }

    @Test
    fun `title artist fallback still works without subtitles`() {
        // App convention for embedded credits is "Title - Artist".
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Song F - Artist F",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2024-05-01T15:40:00Z",
              "products": ["YouTube Music"]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Song F", result.parsed[0].trackName)
        assertEquals("Artist F", result.parsed[0].artistName)
    }

    @Test
    fun `featured artist channel is never used as the album`() {
        // Classic layout with a second channel link (featured artist): the old
        // positional subtitles[1] logic stored the channel name as the album.
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Song G",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2024-05-01T15:45:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Artist G - Topic",
                 "url": "https://www.youtube.com/channel/UC555555555555555555555"},
                {"name": "Featured G",
                 "url": "https://www.youtube.com/channel/UC666666666666666666666"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Artist G", result.parsed[0].artistName)
        assertNull(result.parsed[0].albumName)
    }

    @Test
    fun `mixed classic and new layouts parse correctly together`() {
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Song A",
              "titleUrl": "https://music.youtube.com/watch?v=aaaaaaaaaaa",
              "time": "2024-05-01T15:30:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Artist A - Topic",
                 "url": "https://www.youtube.com/channel/UC111111111111111111111"},
                {"name": "Album A",
                 "url": "https://www.youtube.com/playlist?list=OLAK5uy_test"}]},
             {"header": "Watched on YouTube Music",
              "title": "Song B",
              "titleUrl": "https://www.youtube.com/watch?v=bbbbbbbbbbb",
              "time": "2025-01-15T10:00:00Z",
              "products": ["YouTube Music"],
              "subtitles": [
                {"name": "Release",
                 "url": "https://music.youtube.com/playlist?list=OLAK5uy_release"},
                {"name": "Artist B - Topic",
                 "url": "https://www.youtube.com/channel/UC222222222222222222222"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(2, result.parsed.size)
        assertEquals("Artist A", result.parsed[0].artistName)
        assertEquals("Album A", result.parsed[0].albumName)
        assertEquals("Artist B", result.parsed[1].artistName)
        assertNull(result.parsed[1].albumName)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `search history file in zip is never treated as watch history`() {
        // Real Takeout zips ship search-history.json in the same "history/"
        // folder. Its name matches the "history" keyword, but its entries are
        // searches ("Searched for ..."), never plays. A query containing
        // " - " would otherwise be imported as a fabricated play.
        val searches =
            """
            [{"header": "YouTube Music",
              "title": "Searched for Seedhe Maut - Nanchaku",
              "titleUrl": "https://www.youtube.com/results?search_query=Seedhe+Maut+-+Nanchaku",
              "time": "2025-01-15T10:00:00Z",
              "products": ["YouTube"],
              "activityControls": ["YouTube search history"]},
             {"header": "YouTube Music",
              "title": "Searched for Karma",
              "titleUrl": "https://www.youtube.com/results?search_query=Karma",
              "time": "2025-01-15T10:01:00Z",
              "products": ["YouTube"]}]
            """.trimIndent()

        val result =
            newService().parseZipStream(
                zip("Takeout/YouTube and YouTube Music/history/search-history.json" to searches),
                "takeout.zip",
            )

        assertEquals(0, result.parsed.size)
        // The user picked the wrong file — the error must say so, listing what
        // was found, not fail silently.
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `search result links inside a watch history file are skipped`() {
        // Defense in depth: even when search entries reach the JSON parser
        // (e.g. the user selects the file directly, or a future export renames
        // it), the /results? URL marks them as searches and they must never be
        // imported — a "Searched for Artist - Song" query would otherwise
        // fabricate a play with artist "Song".
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Searched for Seedhe Maut - Nanchaku",
              "titleUrl": "https://www.youtube.com/results?search_query=Seedhe+Maut+-+Nanchaku",
              "time": "2025-01-15T10:00:00Z",
              "products": ["YouTube"]},
             {"header": "YouTube Music",
              "title": "Real Song",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:05:00Z",
              "products": ["YouTube"],
              "subtitles": [
                {"name": "Real Artist - Topic",
                 "url": "https://www.youtube.com/channel/UC777777777777777777777"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Real Song", result.parsed[0].trackName)
        assertEquals("Real Artist", result.parsed[0].artistName)
        // The search entry is counted as skipped, not silently vanished.
        assertEquals(1, result.nonMusicSkipped)
    }

    @Test
    fun `non-music skipped count is reported per file`() {
        // YouTube-app watches (header "YouTube") and artist-less entries must
        // be visible in the skip count so the user can reconcile the numbers
        // against their export instead of guessing where entries went.
        val history =
            """
            [{"header": "YouTube Music",
              "title": "Song H",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2025-01-15T10:20:00Z",
              "products": ["YouTube"],
              "subtitles": [
                {"name": "Artist H - Topic",
                 "url": "https://www.youtube.com/channel/UC888888888888888888888"}]},
             {"header": "YouTube",
              "title": "Watched Some Vlog",
              "titleUrl": "https://www.youtube.com/watch?v=aaaaaaaaaaa",
              "time": "2025-01-15T10:25:00Z",
              "products": ["YouTube"]},
             {"header": "YouTube Music",
              "title": "Song I",
              "titleUrl": "https://music.youtube.com/watch?v=bbcdefghijk",
              "time": "2025-01-15T10:30:00Z",
              "products": ["YouTube"],
              "subtitles": [
                {"name": "Release - Topic",
                 "url": "https://www.youtube.com/channel/UC999999999999999999999"}]}]
            """.trimIndent()

        val result = import(history)

        assertEquals(1, result.parsed.size)
        assertEquals("Song H", result.parsed[0].trackName)
        // One non-YTM watch + one artist-less entry: both skipped, both counted.
        assertEquals(2, result.nonMusicSkipped)
    }
}
