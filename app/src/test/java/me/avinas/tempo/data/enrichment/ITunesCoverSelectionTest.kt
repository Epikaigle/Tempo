package me.avinas.tempo.data.enrichment

import me.avinas.tempo.data.remote.itunes.iTunesResult as AppleMusicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ITunesCoverSelectionTest {

    @Test
    fun exactAlbumBeatsEarlierCompilationForSameTrack() {
        val compilation = appleResult(
            collectionName = "Greatest Hits",
            artworkUrl = "https://example.test/compilation/100x100bb.jpg",
        )
        val exactAlbum = appleResult(
            collectionName = "Future Nostalgia",
            artworkUrl = "https://example.test/album/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(compilation, exactAlbum),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia", selected?.collectionName)
    }

    @Test
    fun recognizedAlbumEditionBeatsUnrelatedAlbumWhenExactIsMissing() {
        val unrelated = appleResult(
            collectionName = "Dance Hits",
            artworkUrl = "https://example.test/hits/100x100bb.jpg",
        )
        val deluxe = appleResult(
            collectionName = "Future Nostalgia Deluxe Edition",
            artworkUrl = "https://example.test/deluxe/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(unrelated, deluxe),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia Deluxe Edition", selected?.collectionName)
    }

    @Test
    fun unusableExactAlbumArtworkIsSkipped() {
        val noArtwork = appleResult(
            collectionName = "Future Nostalgia",
            artworkUrl = null,
        )
        val deluxe = appleResult(
            collectionName = "Future Nostalgia Deluxe Edition",
            artworkUrl = "https://example.test/deluxe/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(noArtwork, deluxe),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertEquals("Future Nostalgia Deluxe Edition", selected?.collectionName)
    }

    @Test
    fun wrongArtistIsRejectedEvenWithExactAlbum() {
        val wrongArtist = appleResult(
            artistName = "Dua Lipa Tribute",
            collectionName = "Future Nostalgia",
            artworkUrl = "https://example.test/wrong/100x100bb.jpg",
        )

        val selected = selectBestITunesCoverMatch(
            results = listOf(wrongArtist),
            expectedArtist = "Dua Lipa",
            expectedTrack = "Levitating",
            expectedAlbum = "Future Nostalgia",
        )

        assertNull(selected)
    }

    private fun appleResult(
        artistName: String = "Dua Lipa",
        trackName: String = "Levitating",
        collectionName: String,
        artworkUrl: String?,
    ) = AppleMusicResult(
        artistName = artistName,
        trackName = trackName,
        collectionName = collectionName,
        artworkUrl100 = artworkUrl,
    )
}
