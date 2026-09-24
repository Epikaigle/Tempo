package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzManualArtworkTest {

    @Test
    fun manualArtworkSurvivesCrossTrackMetadataReuse() {
        val current = EnrichedMetadata(
            id = 10L,
            trackId = 1L,
            albumArtUrl = "https://manual.example/cover.jpg",
            albumArtUrlSmall = "https://manual.example/cover-small.jpg",
            albumArtUrlLarge = "https://manual.example/cover-large.jpg",
            albumArtSource = AlbumArtSource.USER_SELECTED,
        )
        val replacement = EnrichedMetadata(
            id = 10L,
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/cover.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
            genres = listOf("Rock"),
        )

        val merged = preserveUserSelectedArtwork(current, replacement)

        assertEquals(AlbumArtSource.USER_SELECTED, merged.albumArtSource)
        assertEquals("https://manual.example/cover.jpg", merged.albumArtUrl)
        assertEquals("https://manual.example/cover-small.jpg", merged.albumArtUrlSmall)
        assertEquals("https://manual.example/cover-large.jpg", merged.albumArtUrlLarge)
        assertEquals(listOf("Rock"), merged.genres)
    }

    @Test
    fun automaticArtworkCanBeReplacedNormally() {
        val current = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://itunes.example/cover.jpg",
            albumArtSource = AlbumArtSource.ITUNES,
        )
        val replacement = EnrichedMetadata(
            trackId = 1L,
            albumArtUrl = "https://musicbrainz.example/cover.jpg",
            albumArtSource = AlbumArtSource.MUSICBRAINZ,
        )

        assertEquals(replacement, preserveUserSelectedArtwork(current, replacement))
    }
}
