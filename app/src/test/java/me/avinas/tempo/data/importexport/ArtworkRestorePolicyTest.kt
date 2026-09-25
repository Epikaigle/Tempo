package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.AlbumArtSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRestorePolicyTest {

    @Test
    fun userResetAlwaysClearsRestoredTrackMirror() {
        assertNull(
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.USER_RESET,
                metadataArtUrl = null,
                trackArtUrl = "https://backup.example/stale.jpg",
            ),
        )
    }

    @Test
    fun userSelectedMetadataWinsOverStaleTrackMirror() {
        assertEquals(
            "https://manual.example/current.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.USER_SELECTED,
                metadataArtUrl = "https://manual.example/current.jpg",
                trackArtUrl = "https://backup.example/stale.jpg",
            ),
        )
    }

    @Test
    fun automaticArtworkBackfillsTrackMirrorWhenMissing() {
        assertEquals(
            "https://automatic.example/cover.jpg",
            resolveRestoredTrackArtwork(
                albumArtSource = AlbumArtSource.MUSICBRAINZ,
                metadataArtUrl = "https://automatic.example/cover.jpg",
                trackArtUrl = null,
            ),
        )
    }
}
