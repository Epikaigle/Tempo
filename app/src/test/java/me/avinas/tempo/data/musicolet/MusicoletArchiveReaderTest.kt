package me.avinas.tempo.data.musicolet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `PCs_M_2026.6` / `PCs_W_2026.23` / `PCs_Y_2026` naming, including bucketless years. */
class MusicoletPeriodNameTest {
    @Test
    fun `monthly files parse to a month`() {
        val parsed = MusicoletArchiveReader.parsePeriodName("PCs_M_2026.6")
        assertNotNull(parsed)
        assertEquals(MusicoletPeriodKind.MONTH, parsed!!.kind)
        assertEquals(2026, parsed.year)
        assertEquals(6, parsed.bucket)
    }

    @Test
    fun `weekly files parse to a week`() {
        val parsed = MusicoletArchiveReader.parsePeriodName("PCs_W_2026.23")
        assertEquals(MusicoletPeriodKind.WEEK, parsed!!.kind)
        assertEquals(23, parsed.bucket)
    }

    @Test
    fun `yearly files parse with no bucket`() {
        val parsed = MusicoletArchiveReader.parsePeriodName("PCs_Y_2026")
        assertEquals(MusicoletPeriodKind.YEAR, parsed!!.kind)
        assertEquals(2026, parsed.year)
        assertNull(parsed.bucket)
    }

    /** Some backups name a monthly file for a whole year, with no month in the name. */
    @Test
    fun `monthly file with no bucket keeps its kind and no bucket`() {
        val parsed = MusicoletArchiveReader.parsePeriodName("PCs_M_2019")
        assertEquals(MusicoletPeriodKind.MONTH, parsed!!.kind)
        assertEquals(2019, parsed.year)
        assertNull(parsed.bucket)
    }

    @Test
    fun `lowercase codes are accepted`() {
        assertEquals(MusicoletPeriodKind.MONTH, MusicoletArchiveReader.parsePeriodName("pcs_m_2026.1")!!.kind)
    }

    @Test
    fun `unrelated names are rejected`() {
        for (name in listOf("DB_SONGS_LOG", "0.musicolet.backup", "PCs_", "PCs_X_2026", "PCs_M_20", "notes.txt")) {
            assertNull(name, MusicoletArchiveReader.parsePeriodName(name))
        }
    }
}

/**
 * The backup is a ZIP of encrypted databases, but a user may hand over a single member
 * they extracted themselves, so both shapes must be read and neither may be trusted for
 * its entry names.
 */
class MusicoletArchiveReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val opener = JdbcMusicoletTableOpener()

    @Test
    fun `zip yields the song database and every period database`() {
        val songDb = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songDb,
            listOf(MusicoletFixtures.SongRow(title = "A", artist = "B", totalPlays = 3)),
        )
        val monthDb = temp.newFile("pcs.db")
        MusicoletFixtures.writePeriodDatabase(monthDb, listOf("/storage/emulated/0/a.mp3" to 2L))

        val backup = temp.newFile("musicolet_backup.zip")
        MusicoletFixtures.zip(
            backup,
            mapOf(
                "DB_SONGS_LOG" to MusicoletFixtures.encrypt(songDb.readBytes()),
                "PCs_M_2026.6" to MusicoletFixtures.encrypt(monthDb.readBytes()),
                // Not a listening database: must be skipped without being read as one.
                "0.musicolet.backup" to MusicoletFixtures.encrypt("{}".toByteArray()),
            ),
        )

        val workDir = temp.newFolder("work")
        val archive = backup.inputStream().use { MusicoletArchiveReader.read(it, "musicolet_backup.zip", workDir) }

        assertNotNull(archive.songLog)
        assertEquals(1, archive.periodFiles.size)
        assertEquals(MusicoletPeriodKind.MONTH, archive.periodFiles.single().kind)
        assertEquals(2026, archive.periodFiles.single().year)
        assertEquals(6, archive.periodFiles.single().bucket)
        assertEquals(3, archive.entriesSeen)

        // The extracted database is a readable SQLite file, not ciphertext.
        val table = opener.openSingleTable(archive.songLog!!)
        assertEquals("TABLE_SONGS", table!!.name)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `a bare encrypted database is accepted without a zip`() {
        val songDb = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(songDb, listOf(MusicoletFixtures.SongRow(title = "Solo", artist = "A")))

        val bare = temp.newFile("DB_SONGS_LOG")
        bare.writeBytes(MusicoletFixtures.encrypt(songDb.readBytes()))

        val workDir = temp.newFolder("work-bare")
        val archive = bare.inputStream().use { MusicoletArchiveReader.read(it, "DB_SONGS_LOG", workDir) }

        assertNotNull(archive.songLog)
        assertTrue(archive.periodFiles.isEmpty())
        assertEquals("TABLE_SONGS", opener.openSingleTable(archive.songLog!!)!!.name)
    }

    /** A zip with nothing recognizable in it must not report a song database. */
    @Test
    fun `a foreign zip yields no song database`() {
        val backup = temp.newFile("holiday.zip")
        MusicoletFixtures.zip(backup, mapOf("photos/cat.jpg" to ByteArray(32) { 3 }))

        val workDir = temp.newFolder("work-foreign")
        val archive = backup.inputStream().use { MusicoletArchiveReader.read(it, "holiday.zip", workDir) }

        assertNull(archive.songLog)
        assertTrue(archive.periodFiles.isEmpty())
    }

    /**
     * Entry names are attacker-controlled. A member whose name escapes the directory is
     * not written at all, and the written name is composed from the parsed period.
     */
    @Test
    fun `path traversal entries never escape the working directory`() {
        val songDb = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(songDb, listOf(MusicoletFixtures.SongRow(title = "A", artist = "B")))

        val backup = temp.newFile("hostile.zip")
        MusicoletFixtures.zip(
            backup,
            mapOf(
                "../../../../etc/DB_SONGS_LOG" to MusicoletFixtures.encrypt(songDb.readBytes()),
                "nested/PCs_Y_2020" to MusicoletFixtures.encrypt(songDb.readBytes()),
                "..\\..\\PCs_M_2021.3" to MusicoletFixtures.encrypt(songDb.readBytes()),
            ),
        )

        val root = temp.newFolder("work-traversal")
        val workDir = root.resolve("inner")
        val archive = backup.inputStream().use { MusicoletArchiveReader.read(it, "hostile.zip", workDir) }

        assertNotNull(archive.songLog)
        assertTrue(
            "song database must land inside the working directory",
            archive.songLog!!.canonicalPath.startsWith(workDir.canonicalPath),
        )
        // Period files are found by basename and written under a composed name.
        assertEquals(2, archive.periodFiles.size)
        for (period in archive.periodFiles) {
            assertTrue(period.file.canonicalPath.startsWith(workDir.canonicalPath))
            assertTrue(period.file.name.startsWith("PCs_"))
        }
        // Nothing was created outside the working directory.
        assertEquals(0, root.listFiles()!!.count { it.isFile })
    }
}
