package me.avinas.tempo.ui.details

import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverArtPickerSelectionTest {

    @Test
    fun currentCoverIsNotAutoSelected() {
        val candidates = listOf(candidate(CoverArtProvider.CURRENT))

        assertNull(
            reconcileCoverPickerSelection(
                selectedProvider = null,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun explicitSelectionIsKeptWhileCandidateExists() {
        val candidates = listOf(
            candidate(CoverArtProvider.CURRENT),
            candidate(CoverArtProvider.DEEZER),
        )

        assertEquals(
            CoverArtProvider.DEEZER,
            reconcileCoverPickerSelection(
                selectedProvider = CoverArtProvider.DEEZER,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun missingSelectedCandidateClearsSelection() {
        assertNull(
            reconcileCoverPickerSelection(
                selectedProvider = CoverArtProvider.DEEZER,
                candidates = listOf(candidate(CoverArtProvider.CURRENT)),
            ),
        )
    }

    private fun candidate(provider: CoverArtProvider) =
        CoverArtCandidate(
            provider = provider,
            albumArtUrl = "https://example.test/" + provider.name.lowercase() + ".jpg",
        )
}
