package me.avinas.tempo.data.local.dao

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtworkWriteGuardTest {

    @Test
    fun manualMetadataWinsOverIncomingFullRowArtwork() {
        assertEquals(
            "https://manual.example/cover.jpg",
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.USER_SELECTED,
                manualArtUrl = "https://manual.example/cover.jpg",
                currentTrackArtUrl = "https://old.example/cover.jpg",
                incomingArtUrl = "https://automatic.example/cover.jpg",
            )
        )
    }

    @Test
    fun resetTombstoneClearsStaleTrackMirrorAndBlocksIncomingArtwork() {
        assertNull(
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.USER_RESET,
                manualArtUrl = null,
                currentTrackArtUrl = "https://stale-track.example/old-manual.jpg",
                incomingArtUrl = "https://automatic.example/new.jpg",
            )
        )
    }

    @Test
    fun normalAutomaticTrackUpdatesRemainAllowed() {
        assertEquals(
            "https://automatic.example/cover.jpg",
            resolveProtectedTrackArtwork(
                source = AlbumArtSource.ITUNES,
                manualArtUrl = null,
                currentTrackArtUrl = null,
                incomingArtUrl = "https://automatic.example/cover.jpg",
            )
        )
    }
}
