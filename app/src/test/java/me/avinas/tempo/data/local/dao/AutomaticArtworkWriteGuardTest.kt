package me.avinas.tempo.data.local.dao

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticArtworkWriteGuardTest {

    @Test
    fun automaticWriteCannotOverwriteCurrentManualArtwork() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://manual.example/new.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val staleAutomatic = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/old.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
            genres = listOf("Pop"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(current, staleAutomatic)

        assertEquals(AlbumArtSource.USER_SELECTED, merged.albumArtSource)
        assertEquals("https://manual.example/new.jpg", merged.albumArtUrl)
        assertEquals(listOf("Pop"), merged.genres)
    }

    @Test
    fun staleManualSnapshotCannotResurrectAfterAutomaticReset() {
        val currentAfterReset = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = null,
            albumArtSource = AlbumArtSource.NONE,
        )
        val staleManual = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://manual.example/old.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
            genres = listOf("Rock"),
        )

        val merged = mergeAutomaticEnrichmentArtwork(currentAfterReset, staleManual)

        assertEquals(AlbumArtSource.NONE, merged.albumArtSource)
        assertEquals(null, merged.albumArtUrl)
        assertEquals(listOf("Rock"), merged.genres)
    }

    @Test
    fun automaticArtworkStillUpdatesWhenNoManualChoiceExists() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://deezer.example/old.jpg",
            albumArtSource = AlbumArtSource.DEEZER,
        )
        val incoming = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/new.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        assertEquals(incoming, mergeAutomaticEnrichmentArtwork(current, incoming))
    }
}
