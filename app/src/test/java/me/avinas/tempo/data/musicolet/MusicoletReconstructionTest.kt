package me.avinas.tempo.data.musicolet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Musicolet's files overlap: a month's plays are inside its year and inside its weeks.
 * Using more than one layer would multiply the listener's history, so these tests pin
 * which layer wins and what happens to the plays no layer accounts for.
 */
class MusicoletReconstructionTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun instant(text: String): Long = Instant.parse(text).toEpochMilli()

    private val now = instant("2026-10-15T12:00:00Z")

    private fun song(
        totalPlays: Long,
        lastPlayedMs: Long? = null,
    ) = MusicoletSong(
        title = "Song",
        artist = "Artist",
        album = null,
        durationMs = 180_000L,
        totalPlays = totalPlays,
        lastPlayedMs = lastPlayedMs,
    )

    private fun month(
        songIndex: Int = 0,
        year: Int,
        month: Int,
        plays: Long,
    ) = MusicoletPeriodPlays(songIndex, MusicoletPeriodKind.MONTH, year, month, plays)

    private fun year(
        songIndex: Int = 0,
        year: Int,
        plays: Long,
    ) = MusicoletPeriodPlays(songIndex, MusicoletPeriodKind.YEAR, year, null, plays)

    private fun week(
        songIndex: Int = 0,
        year: Int,
        week: Int,
        plays: Long,
    ) = MusicoletPeriodPlays(songIndex, MusicoletPeriodKind.WEEK, year, week, plays)

    private fun plan(
        songs: List<MusicoletSong>,
        periods: List<MusicoletPeriodPlays>,
        includeUndated: Boolean = false,
    ): MusicoletReconstruction.Plan =
        MusicoletReconstruction.plan(
            backup =
                MusicoletParsedBackup(
                    songs = songs,
                    periodPlays = periods,
                    periodFilesRead = periods.size,
                    unmatchedPeriodPlays = 0,
                    warnings = emptyList(),
                ),
            options = MusicoletReconstruction.Options(includeUndatedPlays = includeUndated),
            nowMs = now,
            zone = zone,
        )

    @Test
    fun `each month's plays land inside that month`() {
        val result =
            plan(
                listOf(song(totalPlays = 5)),
                listOf(month(year = 2026, month = 6, plays = 3), month(year = 2026, month = 7, plays = 2)),
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(5, plays.size)
        assertEquals(3, plays.count { it in instant("2026-06-01T00:00:00Z") until instant("2026-07-01T00:00:00Z") })
        assertEquals(2, plays.count { it in instant("2026-07-01T00:00:00Z") until instant("2026-08-01T00:00:00Z") })
        assertEquals(0L, result.undatedPlays)
        assertFalse(result.truncated)
    }

    /** A year file describes the same plays its months do. Counting both would double them. */
    @Test
    fun `a year file is ignored for a year that has monthly files`() {
        val result =
            plan(
                listOf(song(totalPlays = 3)),
                listOf(month(year = 2026, month = 6, plays = 3), year(year = 2026, plays = 40)),
            )

        assertEquals(3, result.songPlays.single().timestamps.size)
        assertEquals(0L, result.undatedPlays)
    }

    /** But a year with no monthly file is the only coverage there is. */
    @Test
    fun `a year file is used for a year with no monthly files`() {
        val result = plan(listOf(song(totalPlays = 4)), listOf(year(year = 2019, plays = 4)))

        val plays = result.songPlays.single().timestamps
        assertEquals(4, plays.size)
        assertTrue(plays.all { it in instant("2019-01-01T00:00:00Z") until instant("2020-01-01T00:00:00Z") })
    }

    @Test
    fun `a month file with no month in its name is treated as a year`() {
        val result =
            plan(
                listOf(song(totalPlays = 3)),
                listOf(MusicoletPeriodPlays(0, MusicoletPeriodKind.MONTH, 2019, null, 3)),
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(3, plays.size)
        assertTrue(plays.all { it in instant("2019-01-01T00:00:00Z") until instant("2020-01-01T00:00:00Z") })
    }

    /** A backup holding nothing but weekly files still imports. */
    @Test
    fun `weekly files are used when there is nothing coarser`() {
        val result = plan(listOf(song(totalPlays = 2)), listOf(week(year = 2026, week = 10, plays = 2)))

        val plays = result.songPlays.single().timestamps
        assertEquals(2, plays.size)
        // ISO week 10 of 2026 is 2–8 March.
        assertTrue(plays.all { it in instant("2026-03-01T00:00:00Z") until instant("2026-03-09T00:00:00Z") })
    }

    /** Weeks are redundant once months exist, for the same reason years are. */
    @Test
    fun `weekly files are ignored when months exist`() {
        val result =
            plan(
                listOf(song(totalPlays = 3)),
                listOf(month(year = 2026, month = 3, plays = 3), week(year = 2026, week = 10, plays = 9)),
            )

        assertEquals(3, result.songPlays.single().timestamps.size)
    }

    /**
     * The one exact timestamp Musicolet keeps: when its period already covers that
     * moment, the estimate for the final play becomes the real time and no play is added.
     */
    @Test
    fun `last played inside a covered month pins the final play without adding one`() {
        val lastPlayed = instant("2026-06-20T21:30:00Z")
        val result =
            plan(
                listOf(song(totalPlays = 3, lastPlayedMs = lastPlayed)),
                listOf(month(year = 2026, month = 6, plays = 3)),
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(3, plays.size)
        assertEquals(lastPlayed, plays.last())
        assertEquals(0L, result.lastPlayedAnchors)
    }

    /** A play since the last period file was written is a play of its own. */
    @Test
    fun `last played outside every period is added as an extra play`() {
        val lastPlayed = instant("2026-09-02T08:15:00Z")
        val result =
            plan(
                listOf(song(totalPlays = 2, lastPlayedMs = lastPlayed)),
                listOf(month(year = 2026, month = 6, plays = 2)),
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(3, plays.size)
        assertEquals(lastPlayed, plays.last())
        assertEquals(1L, result.lastPlayedAnchors)
    }

    /** A song with no period files at all still contributes its exact last-played time. */
    @Test
    fun `a song with only a last-played time still imports`() {
        val lastPlayed = instant("2026-09-02T08:15:00Z")
        val result = plan(listOf(song(totalPlays = 9, lastPlayedMs = lastPlayed)), emptyList())

        assertEquals(listOf(lastPlayed), result.songPlays.single().timestamps)
        assertEquals(1L, result.lastPlayedAnchors)
        // Nothing could place the rest, and that is reported rather than hidden.
        assertEquals(9L, result.undatedPlays)
    }

    /**
     * Plays from before Musicolet kept period files have no date. By default they are
     * reported and left out; the timeline stays truthful.
     */
    @Test
    fun `undated plays are reported and left out by default`() {
        val result =
            plan(
                listOf(song(totalPlays = 8)),
                listOf(month(year = 2026, month = 6, plays = 5)),
            )

        assertEquals(5, result.songPlays.single().timestamps.size)
        assertEquals(3L, result.undatedPlays)
        assertEquals(0L, result.anchoredUndatedPlays)
    }

    /** Opting in places them at the start of the song's history so totals match Musicolet. */
    @Test
    fun `undated plays are anchored to the earliest period when opted in`() {
        val result =
            plan(
                listOf(song(totalPlays = 8)),
                listOf(month(year = 2026, month = 6, plays = 5), month(year = 2026, month = 7, plays = 1)),
                includeUndated = true,
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(8, plays.size)
        // 8 counted minus the 6 the two month files account for.
        assertEquals(2L, result.anchoredUndatedPlays)
        assertEquals(2L, result.undatedPlays)
        // Anchored to June, not spread into July.
        assertEquals(7, plays.count { it in instant("2026-06-01T00:00:00Z") until instant("2026-07-01T00:00:00Z") })
        assertEquals(1, plays.count { it in instant("2026-07-01T00:00:00Z") until instant("2026-08-01T00:00:00Z") })
    }

    /** Period files can be stale relative to the lifetime count; a negative residual is not a thing. */
    @Test
    fun `a total below the period sum is not treated as undated plays`() {
        val result = plan(listOf(song(totalPlays = 1)), listOf(month(year = 2026, month = 6, plays = 5)))

        assertEquals(5, result.songPlays.single().timestamps.size)
        assertEquals(0L, result.undatedPlays)
    }

    @Test
    fun `no play is ever dated after now`() {
        val result =
            plan(
                listOf(song(totalPlays = 6)),
                listOf(
                    month(year = 2026, month = 6, plays = 2),
                    // The current, incomplete month.
                    month(year = 2026, month = 10, plays = 4),
                ),
            )

        for (timestamp in result.songPlays.single().timestamps) {
            assertTrue("$timestamp is in the future", timestamp <= now)
        }
    }

    /** A stamp ahead of this device's clock is not a play that happened. */
    @Test
    fun `a last played time in the future is ignored`() {
        val result =
            plan(
                listOf(song(totalPlays = 2, lastPlayedMs = instant("2027-01-01T00:00:00Z"))),
                listOf(month(year = 2026, month = 6, plays = 2)),
            )

        val plays = result.songPlays.single().timestamps
        assertEquals(2, plays.size)
        assertEquals(0L, result.lastPlayedAnchors)
        assertTrue(plays.all { it <= now })
    }

    @Test
    fun `songs are independent and ordered`() {
        val result =
            plan(
                listOf(song(totalPlays = 1), song(totalPlays = 2)),
                listOf(
                    MusicoletPeriodPlays(0, MusicoletPeriodKind.MONTH, 2026, 6, 1),
                    MusicoletPeriodPlays(1, MusicoletPeriodKind.MONTH, 2019, 2, 2),
                ),
            )

        assertEquals(2, result.songPlays.size)
        assertEquals(1, result.songPlays[0].timestamps.size)
        assertEquals(2, result.songPlays[1].timestamps.size)
        assertTrue(
            result.songPlays.all { songPlays ->
                songPlays.timestamps.zipWithNext().all { (a, b) -> a < b }
            },
        )
    }

    /** Nothing to import is a valid outcome, and must not fabricate a play. */
    @Test
    fun `a song with no plays and no last-played time produces nothing`() {
        val result = plan(listOf(song(totalPlays = 0)), emptyList())

        assertTrue(result.songPlays.isEmpty())
        assertEquals(0L, result.totalPlays)
    }
}
