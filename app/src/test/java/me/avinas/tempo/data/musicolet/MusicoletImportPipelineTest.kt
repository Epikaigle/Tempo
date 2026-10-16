package me.avinas.tempo.data.musicolet

import me.avinas.tempo.data.local.SourceAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The whole read path against one synthetic backup: an encrypted ZIP, written the way
 * Musicolet writes one, through decryption, the path join, the period layer choice and
 * the timeline. This is the test that would catch a change to any one link.
 */
class MusicoletImportPipelineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.UTC

    private fun instant(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `an encrypted backup becomes dated plays in the right months`() {
        // Two songs; one has its path stored in Musicolet's internal form.
        val songDb = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songDb,
            listOf(
                MusicoletFixtures.SongRow(
                    title = "Replay",
                    artist = "Artist One/Artist Two",
                    album = "Album",
                    durationMs = 200_000L,
                    totalPlays = 5,
                    lastPlayedMs = instant("2026-07-18T20:00:00Z"),
                    logPath = "Storage/primary/Music/replay.mp3",
                ),
                MusicoletFixtures.SongRow(
                    title = "Old Song",
                    artist = "Artist Three",
                    totalPlays = 4,
                    logPath = "/storage/emulated/0/Music/old.mp3",
                ),
            ),
        )

        val june = temp.newFile("june.db")
        MusicoletFixtures.writePeriodDatabase(june, listOf("/storage/emulated/0/Music/replay.mp3" to 3L))
        val july = temp.newFile("july.db")
        MusicoletFixtures.writePeriodDatabase(july, listOf("/storage/emulated/0/Music/replay.mp3" to 2L))
        val year2019 = temp.newFile("2019.db")
        MusicoletFixtures.writePeriodDatabase(year2019, listOf("/storage/emulated/0/Music/old.mp3" to 4L))

        val backup = temp.newFile("musicolet_backup.zip")
        MusicoletFixtures.zip(
            backup,
            mapOf(
                "DB_SONGS_LOG" to MusicoletFixtures.encrypt(songDb.readBytes()),
                "PCs_M_2026.6" to MusicoletFixtures.encrypt(june.readBytes()),
                "PCs_M_2026.7" to MusicoletFixtures.encrypt(july.readBytes()),
                "PCs_Y_2019" to MusicoletFixtures.encrypt(year2019.readBytes()),
                "0.musicolet.backup" to MusicoletFixtures.encrypt("{\"note\":\"manifest\"}".toByteArray()),
            ),
        )

        val workDir = temp.newFolder("work")
        val archive = backup.inputStream().use { MusicoletArchiveReader.read(it, backup.name, workDir) }
        val parsed = MusicoletBackupParser(JdbcMusicoletTableOpener()).parse(archive)
        val plan =
            MusicoletReconstruction.plan(
                backup = parsed,
                options = MusicoletReconstruction.Options(includeUndatedPlays = false),
                nowMs = instant("2026-10-15T12:00:00Z"),
                zone = zone,
            )

        assertEquals(2, parsed.songs.size)
        assertEquals(3, parsed.periodFilesRead)
        assertEquals("Artist One", parsed.songs[0].artist)

        val replay = plan.songPlays.first { it.songIndex == 0 }.timestamps
        val old = plan.songPlays.first { it.songIndex == 1 }.timestamps

        assertEquals(5, replay.size)
        assertEquals(3, replay.count { it in instant("2026-06-01T00:00:00Z") until instant("2026-07-01T00:00:00Z") })
        assertEquals(2, replay.count { it in instant("2026-07-01T00:00:00Z") until instant("2026-08-01T00:00:00Z") })
        // The exact last-played time is preserved.
        assertEquals(instant("2026-07-18T20:00:00Z"), replay.last())

        // The year-only song is placed across 2019.
        assertEquals(4, old.size)
        assertTrue(old.all { it in instant("2019-01-01T00:00:00Z") until instant("2020-01-01T00:00:00Z") })

        assertEquals(0L, plan.undatedPlays)
        assertEquals(0L, plan.droppedPlays)
    }

    /** Re-importing the same backup must produce identical timestamps, or dedup cannot work. */
    @Test
    fun `the same backup reconstructs to identical plays twice`() {
        val songDb = temp.newFile("songs.db")
        MusicoletFixtures.writeSongDatabase(
            songDb,
            listOf(
                MusicoletFixtures.SongRow(
                    title = "Replay",
                    artist = "Artist",
                    totalPlays = 7,
                    logPath = "/storage/emulated/0/Music/replay.mp3",
                ),
            ),
        )
        val month = temp.newFile("month.db")
        MusicoletFixtures.writePeriodDatabase(month, listOf("/storage/emulated/0/Music/replay.mp3" to 7L))

        val backup = temp.newFile("backup.zip")
        MusicoletFixtures.zip(
            backup,
            mapOf(
                "DB_SONGS_LOG" to MusicoletFixtures.encrypt(songDb.readBytes()),
                "PCs_M_2025.11" to MusicoletFixtures.encrypt(month.readBytes()),
            ),
        )

        fun run(): List<Long> {
            val workDir = temp.newFolder()
            val archive = backup.inputStream().use { MusicoletArchiveReader.read(it, backup.name, workDir) }
            val parsed = MusicoletBackupParser(JdbcMusicoletTableOpener()).parse(archive)
            return MusicoletReconstruction
                .plan(parsed, MusicoletReconstruction.Options(), instant("2026-10-15T12:00:00Z"), zone)
                .songPlays
                .single()
                .timestamps
        }

        assertEquals(7, run().size)
        assertEquals(run(), run())
    }

    /**
     * Reconstructed history must rank below every measured source, so an import can never
     * delete or overwrite a play Tempo watched happen.
     */
    @Test
    fun `the import source ranks as reconstructed history`() {
        val rank = SourceAuthority.rank(MusicoletImportService.IMPORT_SOURCE)

        assertEquals(40, rank)
        assertTrue(rank < SourceAuthority.rank("com.spotify.music"))
        assertTrue(rank < SourceAuthority.rank("com.google.android.apps.youtube.music.import.json"))
        assertTrue(rank < SourceAuthority.rank("fm.last.import"))
        assertTrue(rank < SourceAuthority.rank("desktop:abc"))
    }
}
