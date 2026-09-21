package me.avinas.tempo.data.deezer

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DeezerDataImportServiceTest {

    @Test
    fun detectsConflictingIncomingIsrcWithoutBreakingCollaborationExpansion() {
        val compatibleIsrc = "USABC2600001"
        val conflictingIsrc = "GBXYZ2600002"
        val substringConflictIsrc = "FRABC2600003"

        val entries =
            listOf(
                entry(compatibleIsrc, "Primary Artist"),
                entry(compatibleIsrc, "Primary Artist, Guest Artist"),
                entry(conflictingIsrc, "Artist Alpha"),
                entry(conflictingIsrc, "Artist Beta"),
                entry(substringConflictIsrc, "Queen"),
                entry(substringConflictIsrc, "Queen Latifah"),
            )

        assertEquals(
            setOf(conflictingIsrc, substringConflictIsrc),
            DeezerDataImportService.findIncomingAmbiguousIsrcs(entries),
        )
    }

    private fun entry(
        isrc: String,
        artist: String,
    ) = DeezerXlsxParser.Entry(
        trackName = "Synthetic Track",
        artistName = artist,
        albumName = "Synthetic Album",
        isrc = isrc,
        listenedAtMillis = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli(),
        msPlayed = 120_000L,
    )
}
