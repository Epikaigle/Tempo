package me.avinas.tempo.data.musicolet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The join between `DB_SONGS_LOG` (titles, totals, last-played) and the `PCs_*`
 * play-count files (per-period counts keyed by audio file path) is the part most likely
 * to break against a real backup, because a path can appear in either of two forms.
 */
class MusicoletBackupParserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val parser = MusicoletBackupParser(JdbcMusicoletTableOpener())

    private fun backup(
        songDb: File,
        vararg periods: Pair<MusicoletPeriodKind, File>,
    ): MusicoletArchive =
        MusicoletArchive(
            songLog = songDb,
            periodFiles =
                periods.mapIndexed { index, (kind, file) ->
                    MusicoletPeriodFile(file, kind, 2026, index + 1)
                },
            entriesSeen = periods.size + 1,
            periodFilesSkipped = 0,
        )

    @Test
    fun `period plays join to their song by path`() {
        val songs = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songs,
            listOf(
                MusicoletFixtures.SongRow(
                    title = "Song A",
                    artist = "Artist A",
                    album = "Album A",
                    totalPlays = 5,
                    logPath = "Storage/primary/Music/a.mp3",
                ),
                MusicoletFixtures.SongRow(
                    title = "Song B",
                    artist = "Artist B",
                    totalPlays = 2,
                    logPath = "Storage/primary/Music/b.mp3",
                ),
            ),
        )

        val month = temp.newFile("pcs.db")
        MusicoletFixtures.writePeriodDatabase(
            month,
            // Same file, written the way the period database writes it.
            listOf("/storage/emulated/0/Music/a.mp3" to 4L, "/storage/emulated/0/Music/b.mp3" to 2L),
        )

        val parsed = parser.parse(backup(songs, MusicoletPeriodKind.MONTH to month))

        assertEquals(2, parsed.songs.size)
        assertEquals("Song A", parsed.songs[0].title)
        assertEquals("Artist A", parsed.songs[0].artist)
        assertEquals(5L, parsed.songs[0].totalPlays)
        assertEquals(2, parsed.periodPlays.size)
        assertEquals(4L, parsed.periodPlays.first { it.songIndex == 0 }.plays)
        assertEquals(1, parsed.periodFilesRead)
        assertEquals(0L, parsed.unmatchedPeriodPlays)
        assertTrue(parsed.warnings.isEmpty())
    }

    /** A play whose song was deleted from the device has no title to import under. */
    @Test
    fun `plays for unknown paths are counted but not attributed`() {
        val songs = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songs,
            listOf(MusicoletFixtures.SongRow(title = "Song A", artist = "A", logPath = "/storage/emulated/0/a.mp3")),
        )
        val month = temp.newFile("pcs.db")
        MusicoletFixtures.writePeriodDatabase(
            month,
            listOf("/storage/emulated/0/a.mp3" to 1L, "/storage/emulated/0/deleted.mp3" to 7L),
        )

        val parsed = parser.parse(backup(songs, MusicoletPeriodKind.MONTH to month))

        assertEquals(1, parsed.periodPlays.size)
        assertEquals(7L, parsed.unmatchedPeriodPlays)
    }

    /** `COL_ARTIST` holds several artists slash-separated; a joined artist splits plays in two. */
    @Test
    fun `multiple artists are reduced to the primary one`() {
        assertEquals("Artist A", MusicoletBackupParser.primaryArtist("Artist A/Artist B"))
        assertEquals("Artist A", MusicoletBackupParser.primaryArtist("  Artist A / Artist B "))
        assertEquals("Artist A", MusicoletBackupParser.primaryArtist("Artist A"))
        assertEquals("Unknown Artist", MusicoletBackupParser.primaryArtist(null))
        assertEquals("Unknown Artist", MusicoletBackupParser.primaryArtist("   "))
    }

    /** A last-played value in seconds must not be read as 1970. */
    @Test
    fun `second precision timestamps are scaled to milliseconds`() {
        assertEquals(1_780_000_000_000L, MusicoletBackupParser.normalizeTimestamp(1_780_000_000L))
        assertEquals(1_780_000_000_000L, MusicoletBackupParser.normalizeTimestamp(1_780_000_000_000L))
        assertNull(MusicoletBackupParser.normalizeTimestamp(0L))
        assertNull(MusicoletBackupParser.normalizeTimestamp(-5L))
        assertNull(MusicoletBackupParser.normalizeTimestamp(null))
        // Far in the past or the future: a broken clock, not listening.
        assertNull(MusicoletBackupParser.normalizeTimestamp(1_000L))
        assertNull(MusicoletBackupParser.normalizeTimestamp(9_999_999_999_999L))
    }

    /** Old backups have no lifetime column; the periods then have to supply the total. */
    @Test
    fun `total falls back to the sum of period plays when the column is absent`() {
        val songs = temp.newFile("songs-old.db")
        MusicoletFixtures.writeDatabase(
            songs,
            "CREATE TABLE TABLE_SONGS (COL_TITLE TEXT, COL_ARTIST TEXT, COL_LOGPATH TEXT)",
            "INSERT INTO TABLE_SONGS VALUES (?,?,?)",
            listOf(listOf("Song A", "Artist A", "/storage/emulated/0/a.mp3")),
        )
        val month = temp.newFile("pcs.db")
        MusicoletFixtures.writePeriodDatabase(month, listOf("/storage/emulated/0/a.mp3" to 9L))

        val parsed = parser.parse(backup(songs, MusicoletPeriodKind.MONTH to month))

        assertEquals(9L, parsed.songs.single().totalPlays)
    }

    /** Period plays for the same song and period in two members must be summed, not overwritten. */
    @Test
    fun `duplicate period members are summed`() {
        val songs = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songs,
            listOf(MusicoletFixtures.SongRow(title = "Song A", artist = "A", logPath = "/storage/emulated/0/a.mp3")),
        )
        val first = temp.newFile("pcs1.db")
        MusicoletFixtures.writePeriodDatabase(first, listOf("/storage/emulated/0/a.mp3" to 3L))
        val second = temp.newFile("pcs2.db")
        MusicoletFixtures.writePeriodDatabase(second, listOf("/storage/emulated/0/a.mp3" to 4L))

        val parsed =
            parser.parse(
                MusicoletArchive(
                    songLog = songs,
                    periodFiles =
                        listOf(
                            MusicoletPeriodFile(first, MusicoletPeriodKind.MONTH, 2026, 6),
                            MusicoletPeriodFile(second, MusicoletPeriodKind.MONTH, 2026, 6),
                        ),
                    entriesSeen = 3,
                    periodFilesSkipped = 0,
                ),
            )

        assertEquals(7L, parsed.periodPlays.single().plays)
    }

    /** A bare database with no period files must still be importable, with a warning. */
    @Test
    fun `missing period files are reported rather than failing`() {
        val songs = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songs,
            listOf(MusicoletFixtures.SongRow(title = "Song A", artist = "A", totalPlays = 4, lastPlayedMs = 1_780_000_000_000L)),
        )

        val parsed = parser.parse(MusicoletArchive(songs, emptyList(), 1, 0))

        assertEquals(1, parsed.songs.size)
        assertTrue(parsed.periodPlays.isEmpty())
        // No warning here: the importer adds the "no per-month counts" note, because only
        // it knows whether the user picked a bare file or a backup that lacked the files.
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `a database with no songs is reported instead of parsed`() {
        val songs = temp.newFile("empty.db")
        MusicoletFixtures.writeSongDatabase(songs, emptyList())

        val parsed = parser.parse(MusicoletArchive(songs, emptyList(), 1, 0))

        assertTrue(parsed.songs.isEmpty())
        assertTrue(parsed.warnings.single().contains("no songs"))
    }

    @Test
    fun `a missing song database explains how to make a backup`() {
        val parsed = parser.parse(MusicoletArchive(null, emptyList(), 0, 0))

        assertTrue(parsed.songs.isEmpty())
        assertTrue(parsed.warnings.single().contains("Backup and restore"))
    }
}
