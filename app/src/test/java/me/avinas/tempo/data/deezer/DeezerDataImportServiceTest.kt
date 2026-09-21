package me.avinas.tempo.data.deezer

import java.time.Instant
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerDataImportServiceTest {

    @Test
    fun detectsConflictingIncomingIsrcWithoutBreakingCollaborationExpansion() {
        val compatibleIsrc = "USABC2600001"
        val conflictingIsrc = "GBXYZ2600002"
        val substringConflictIsrc = "FRABC2600003"
        val shortNoiseIsrc = "DEABC2600004"

        val entries =
            listOf(
                entry(compatibleIsrc, "Primary Artist"),
                entry(compatibleIsrc, "Primary Artist, Guest Artist"),
                entry(compatibleIsrc, "Guest Artist"),
                entry(conflictingIsrc, "Artist Alpha"),
                entry(conflictingIsrc, "Artist Beta"),
                entry(substringConflictIsrc, "Queen"),
                entry(substringConflictIsrc, "Queen Latifah"),
                entry(shortNoiseIsrc, "Stable Artist"),
                entry(shortNoiseIsrc, "Wrong Short Credit", msPlayed = 5_000L),
            )

        assertEquals(
            setOf(conflictingIsrc, substringConflictIsrc),
            DeezerDataImportService.findIncomingAmbiguousIsrcs(entries),
        )
    }

    @Test
    fun propagatesCancellationDuringIncomingIsrcScan() {
        val entries =
            List(300) { index ->
                entry(
                    isrc = "USABC26" + index.toString().padStart(5, '0'),
                    artist = "Artist $index",
                )
            }

        val error =
            runCatching {
                DeezerDataImportService.findIncomingAmbiguousIsrcs(entries) {
                    throw CancellationException("cancelled")
                }
            }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    private fun entry(
        isrc: String,
        artist: String,
        msPlayed: Long = 120_000L,
    ) = DeezerXlsxParser.Entry(
        trackName = "Synthetic Track",
        artistName = artist,
        albumName = "Synthetic Album",
        isrc = isrc,
        listenedAtMillis = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(),
        msPlayed = msPlayed,
    )
}
