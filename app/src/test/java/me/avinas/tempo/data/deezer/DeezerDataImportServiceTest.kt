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
        val retainedSkipIsrc = "NLABC2600005"

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
                entry(retainedSkipIsrc, "Stable Skip Artist"),
                entry(retainedSkipIsrc, "Conflicting Skip Credit", msPlayed = 27_000L),
            )

        assertEquals(
            setOf(conflictingIsrc, substringConflictIsrc, retainedSkipIsrc),
            DeezerDataImportService.findIncomingAmbiguousIsrcs(entries),
        )
    }

    @Test
    fun preservesDeezerArtistEntitiesThatContainCollaborationCharacters() {
        assertEquals(
            listOf("Alpha & Beta"),
            DeezerDataImportService.deezerArtistCredits("Alpha & Beta"),
        )
        assertEquals(
            listOf("Group/Name", "Guest One", "Guest Two"),
            DeezerDataImportService.deezerArtistCredits("Group/Name, Guest One, Guest Two"),
        )
        assertEquals(
            listOf("Duo & Partner", "Initials&Initials", "Guest Three"),
            DeezerDataImportService.deezerArtistCredits(
                "Duo & Partner, Initials&Initials, Guest Three",
            ),
        )
        assertEquals(
            listOf("Tyler, the Creator"),
            DeezerDataImportService.deezerArtistCredits("Tyler, the Creator"),
        )
        assertEquals(
            listOf("Tyler, the Creator", "A$AP Rocky"),
            DeezerDataImportService.deezerArtistCredits("Tyler, the Creator, A$AP Rocky"),
        )
        assertEquals(
            listOf("Earth, Wind & Fire", "Chic"),
            DeezerDataImportService.deezerArtistCredits("Earth, Wind & Fire, Chic"),
        )
    }

    @Test
    fun rejectsConflictingKnownAlbumsForIsrcCandidate() {
        assertTrue(
            DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = "Same Album",
                incomingAlbum = "same album",
            ),
        )
        assertTrue(
            DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = null,
                incomingAlbum = "Incoming Album",
            ),
        )
        assertTrue(
            !DeezerDataImportService.albumsCompatibleForIsrcCandidate(
                existingAlbum = "Live Album",
                incomingAlbum = "Studio Album",
            ),
        )
    }

    @Test
    fun appliesTempoMinimumAndSkipClassificationToDeezerPlays() {
        assertTrue(!DeezerDataImportService.shouldImportDeezerPlay(24_999L))
        assertTrue(DeezerDataImportService.shouldImportDeezerPlay(25_000L))
        assertTrue(DeezerDataImportService.shouldImportDeezerPlay(10_000L, minimumPlayDurationMs = 5_000L))

        assertTrue(DeezerDataImportService.isDeezerSkip(29_999L, completionPercentage = 80))
        assertTrue(!DeezerDataImportService.isDeezerSkip(30_000L, completionPercentage = 80))
        assertTrue(DeezerDataImportService.isDeezerSkip(120_000L, completionPercentage = 20))
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
