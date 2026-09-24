package me.avinas.tempo.data.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtProviderStatusTest {

    @Test
    fun iTunesAllFailuresRemainErrors() {
        val result = resolveITunesCoverSearchTerminalResult(
            hadSuccessfulResponse = false,
            lastProviderError = "network down",
        )

        assertTrue(result is ITunesEnrichmentService.iTunesResult.Error)
        assertEquals(
            "network down",
            (result as ITunesEnrichmentService.iTunesResult.Error).message,
        )
    }

    @Test
    fun iTunesSuccessfulLookupWithoutMatchIsNotFound() {
        val result = resolveITunesCoverSearchTerminalResult(
            hadSuccessfulResponse = true,
            lastProviderError = "later transient error",
        )

        assertSame(ITunesEnrichmentService.iTunesResult.NotFound, result)
    }

    @Test
    fun musicBrainzProviderErrorMapsToErrorState() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.Error("provider unavailable")
        )

        assertEquals(CoverArtProvider.MUSICBRAINZ, result.provider)
        assertEquals(CoverArtLookupStatus.ERROR, result.status)
        assertEquals("provider unavailable", result.message)
    }

    @Test
    fun musicBrainzNoArtworkMapsToNotFoundState() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.NotFound
        )

        assertEquals(CoverArtLookupStatus.NOT_FOUND, result.status)
        assertEquals(null, result.candidate)
    }

    @Test
    fun musicBrainzSuccessMapsArtworkCandidate() {
        val result = mapMusicBrainzCoverSearchResult(
            MusicBrainzEnrichmentService.CoverArtSearchResult.Success(
                MusicBrainzEnrichmentService.CoverArtLookupResult(
                    albumArtUrl = "https://example.test/base.jpg",
                    albumArtUrlSmall = "https://example.test/small.jpg",
                    albumArtUrlLarge = "https://example.test/large.jpg",
                    albumTitle = "Album",
                )
            )
        )

        assertEquals(CoverArtLookupStatus.FOUND, result.status)
        assertNotNull(result.candidate)
        assertEquals("https://example.test/large.jpg", result.candidate?.albumArtUrl)
        assertEquals("Album", result.candidate?.albumTitle)
    }
}
