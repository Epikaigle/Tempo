package me.avinas.tempo.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.AlbumArtSource
import me.avinas.tempo.data.local.entities.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkPersistenceIntegrationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualSelectionResetAndAutomaticReplacementStayConsistentAcrossTables() = runBlocking {
        val trackDao = database.trackDao()
        val metadataDao = database.enrichedMetadataDao()
        val trackId =
            trackDao.insert(
                Track(
                    title = "Song",
                    artist = "Artist",
                    album = null,
                    duration = null,
                    albumArtUrl = null,
                    spotifyId = null,
                    musicbrainzId = null,
                )
            )

        val manualUrl = "https://manual.example/cover.jpg"
        val automaticUrl = "https://automatic.example/cover.jpg"

        metadataDao.setUserSelectedArtwork(
            trackId = trackId,
            albumArtUrl = manualUrl,
            albumArtUrlSmall = manualUrl,
            albumArtUrlLarge = manualUrl,
            timestamp = 1L,
        )

        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_SELECTED,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )

        val rejectedAutomatic =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = automaticUrl,
            )

        assertEquals(manualUrl, rejectedAutomatic)
        assertEquals(manualUrl, trackDao.getTrackById(trackId)?.albumArtUrl)

        metadataDao.resetArtworkToAutomatic(trackId = trackId, timestamp = 2L)

        assertNull(trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.USER_RESET,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )

        // Simulate an inconsistent/stale Track mirror from an old snapshot or restore.
        // Any generic row update must honor USER_RESET and clear it again.
        trackDao.updateAlbumArtUrl(trackId, manualUrl)
        val staleTrack = requireNotNull(trackDao.getTrackById(trackId))
        trackDao.updatePreservingManualArtwork(
            staleTrack.copy(
                album = "Updated Album",
                albumArtUrl = "https://stale-snapshot.example/cover.jpg",
            )
        )

        val afterGenericUpdate = requireNotNull(trackDao.getTrackById(trackId))
        assertEquals("Updated Album", afterGenericUpdate.album)
        assertNull(afterGenericUpdate.albumArtUrl)

        val resetMetadata = requireNotNull(metadataDao.forTrackSync(trackId))
        metadataDao.upsertFromAutomaticEnrichment(
            resetMetadata.copy(
                albumArtUrl = automaticUrl,
                albumArtUrlSmall = automaticUrl,
                albumArtUrlLarge = automaticUrl,
                albumArtSource = AlbumArtSource.ITUNES,
            )
        )
        val acceptedAutomatic =
            trackDao.updateAutomaticAlbumArtUrl(
                trackId = trackId,
                albumArtUrl = automaticUrl,
            )

        assertEquals(automaticUrl, acceptedAutomatic)
        assertEquals(automaticUrl, trackDao.getTrackById(trackId)?.albumArtUrl)
        assertEquals(
            AlbumArtSource.ITUNES,
            metadataDao.forTrackSync(trackId)?.albumArtSource,
        )
    }
}
