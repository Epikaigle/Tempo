package me.avinas.tempo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for localized Google Takeout exports: file names inside the
 * ZIP are named in the account's language (e.g. Portuguese
 * "histórico de músicas ouvidas.json") and must still be detected.
 */
class YouTubeMusicImportZipDetectionTest {

    // The ZIP-parsing paths under test never touch the DAOs/repos or the
    // import state, so we allocate the service without running its constructor
    // (Kotlin forbids null for its non-null constructor parameters).
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

    @Test
    fun `english watch-history names are recognized`() {
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube and YouTube Music/history/watch-history.json"
            )
        )
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube and YouTube Music/history/my-activity.html"
            )
        )
    }

    @Test
    fun `localized history names are recognized`() {
        // Portuguese (with and without diacritics)
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube e YouTube Music/histórico/histórico de músicas ouvidas.json"
            )
        )
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube e YouTube Music/historico/historico de musicas ouvidas.json"
            )
        )
        // French / German / Spanish
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube et YouTube Music/historique/historique des vidéos regardées.json"
            )
        )
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube und YouTube Music/Verlauf/Watch-History.json"
            )
        )
        assertTrue(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube e YouTube Music/historial/historial de reproducción.json"
            )
        )
    }

    @Test
    fun `non-history takeout files are not recognized by name`() {
        assertFalse(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube and YouTube Music/playlists/playlists.json"
            )
        )
        assertFalse(
            YouTubeMusicImportService.isWatchHistoryEntryName(
                "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.json"
            )
        )
    }

    @Test
    fun `watch-history json content is sniffed correctly`() {
        val json = """[{"header": "YouTube Music", "title": "Assisti \"Song A\"", """ +
            """"titleUrl": "https://www.youtube.com/watch?v=abcdefghijk", """ +
            """"time": "2024-05-01T15:30:00Z"}]"""
        assertTrue(YouTubeMusicImportService.looksLikeWatchHistoryJson(json))

        // Playlists / metadata objects must NOT be sniffed in.
        assertFalse(YouTubeMusicImportService.looksLikeWatchHistoryJson("""[{"snippet": {"title": "Mix"}}]"""))
        assertFalse(YouTubeMusicImportService.looksLikeWatchHistoryJson("""{"ok": false}"""))
    }

    @Test
    fun `watch-history html content is sniffed correctly`() {
        val html = """<html><body><div class="outer-cell">YouTube Music""" +
            """<a href="https://www.youtube.com/watch?v=abcdefghijk">Song</a></div></body></html>"""
        assertTrue(YouTubeMusicImportService.looksLikeWatchHistoryHtml(html))

        assertFalse(YouTubeMusicImportService.looksLikeWatchHistoryHtml("""<html><body>nothing here</body></html>"""))
    }

    // End-to-end ZIP parsing

    private val portugueseHistoryJson = """
        [{"header": "YouTube Music",
          "title": "Assisti \"Song A\"",
          "titleUrl": "https://www.youtube.com/watch?v=abcdefghijk",
          "time": "2024-05-01T15:30:00Z",
          "products": ["YouTube", "YouTube Music"],
          "subtitles": [{"name": "Artist A - Topic",
                         "url": "https://www.youtube.com/channel/UC123"}]}]
    """.trimIndent()

    @Test
    fun `portuguese named zip is parsed via localized filename`() {
        val input = zip(
            "Takeout/YouTube e YouTube Music/histórico/histórico de músicas ouvidas.json" to portugueseHistoryJson
        )

        val result = newService().parseZipStream(input, "takeout-20240501T000000Z-001.zip")

        assertEquals(1, result.parsed.size)
        assertEquals("Song A", result.parsed[0].trackName)
        assertEquals("Artist A", result.parsed[0].artistName)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `zip with unknown localized filename is parsed via content sniffing`() {
        // A language none of the filename keywords cover: the file must be
        // found by inspecting its content instead of its name.
        val historyJson = """
            [{"header": "YouTube Music",
              "title": "Song X",
              "titleUrl": "https://music.youtube.com/watch?v=abcdefghijk",
              "time": "2024-05-01T15:30:00Z",
              "subtitles": [{"name": "Artist X - Topic",
                             "url": "https://www.youtube.com/channel/UC123"}]}]
        """.trimIndent()
        val playlists = """[{"snippet": {"title": "My YouTube Music Mix"}, "contentDetails": {"itemCount": 10}}]"""

        val input = zip(
            "Takeout/YouTube/неизвестное имя файла.json" to historyJson,
            "Takeout/YouTube/playlists/playlists.json" to playlists
        )

        val result = newService().parseZipStream(input, "takeout.zip")

        assertEquals(1, result.parsed.size)
        assertEquals("Song X", result.parsed[0].trackName)
        assertEquals("Artist X", result.parsed[0].artistName)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `localized html watch-history in zip is parsed`() {
        val html = """
            <html><body>
            <div class="outer-cell mdl-cell mdl-cell--12-col mdl-grid">
            <div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">YouTube Music<br>
            <a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">Assisti "Song B"</a><br>
            <a href="https://www.youtube.com/channel/UC456">Artist B - Topic</a><br>
            May 1, 2024, 15:30:00 BRT
            </div>
            </div>
            </body></html>
        """.trimIndent()

        val input = zip(
            "Takeout/YouTube e YouTube Music/histórico/histórico de exibição.html" to html
        )

        val result = newService().parseZipStream(input, "takeout.zip")

        assertEquals(1, result.parsed.size)
        assertEquals("Song B", result.parsed[0].trackName)
        assertEquals("Artist B", result.parsed[0].artistName)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `zip without any history file still reports a helpful error`() {
        val playlists = """[{"snippet": {"title": "My Mix"}}]"""
        val input = zip("Takeout/YouTube/playlists/playlists.json" to playlists)

        val result = newService().parseZipStream(input, "takeout.zip")

        assertEquals(0, result.parsed.size)
        assertNotNull(result.errors.firstOrNull())
        assertTrue(result.errors.first().contains("No watch-history file found"))
    }
}
