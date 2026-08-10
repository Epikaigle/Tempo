package me.avinas.tempo.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SpotifyJsonModelsTest {

    private fun expectedEpochMillis(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second)
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli()

    @Test
    fun `parses ISO-8601 instant from extended export ts field`() {
        // Extended streaming history ("Streaming_History_Audio_*.json" / endsong)
        assertEquals(
            expectedEpochMillis(2024, 1, 15, 10, 30, 45),
            parseSpotifyTimestamp("2024-01-15T10:30:45Z")
        )
    }

    @Test
    fun `parses ISO local date-time without offset`() {
        assertEquals(
            expectedEpochMillis(2024, 1, 15, 10, 30, 45),
            parseSpotifyTimestamp("2024-01-15T10:30:45")
        )
    }

    @Test
    fun `parses legacy format with seconds`() {
        assertEquals(
            expectedEpochMillis(2024, 1, 15, 10, 30, 45),
            parseSpotifyTimestamp("2024-01-15 10:30:45")
        )
    }

    @Test
    fun `parses legacy StreamingHistory format without seconds`() {
        // Regression test: Spotify's basic account-data export
        // (StreamingHistory0.json / StreamingHistory_music_0.json) writes
        // minute-precision endTime values. These used to parse as 0, which made
        // the import report every entry as a skipped duplicate.
        assertEquals(
            expectedEpochMillis(2024, 1, 15, 10, 30, 0),
            parseSpotifyTimestamp("2024-01-15 10:30")
        )
    }

    @Test
    fun `legacy and extended formats of the same play are close in time`() {
        // Same wall-clock play recorded by both export types must land within
        // the duplicate-tolerance window so cross-file dedup still works.
        val legacy = parseSpotifyTimestamp("2024-01-15 10:30")
        val extended = parseSpotifyTimestamp("2024-01-15T10:30:45Z")
        assertEquals(45_000L, extended - legacy)
    }

    @Test
    fun `returns 0 for null blank or unparseable values`() {
        assertEquals(0L, parseSpotifyTimestamp(null))
        assertEquals(0L, parseSpotifyTimestamp(""))
        assertEquals(0L, parseSpotifyTimestamp("   "))
        assertEquals(0L, parseSpotifyTimestamp("not a timestamp"))
        assertEquals(0L, parseSpotifyTimestamp("15/01/2024 10:30"))
    }
}
