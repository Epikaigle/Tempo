package me.avinas.tempo.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.dao.isLocalBackupArtwork
import me.avinas.tempo.data.local.dao.isManagedLocalArtworkFile
import me.avinas.tempo.data.local.dao.managedArtworkFileOrNull
import me.avinas.tempo.data.local.entities.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTrackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrackDao,
    private val trackArtistDao: TrackArtistDao,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val enrichedMetadataDao: EnrichedMetadataDao
) : TrackRepository {

    companion object {
        private const val TAG = "TrackRepository"

        /** Mirrors ALBUM_ART_DIR in MusicTrackingService and ImportExportManager. */
        private const val ALBUM_ART_DIR = "album_art"

        private val albumArtBackupMutex = Mutex()
    }

    private suspend fun <T> withAlbumArtBackupLock(block: suspend () -> T): T {
        albumArtBackupMutex.lock()
        return try {
            block()
        } finally {
            albumArtBackupMutex.unlock()
        }
    }

    /**
     * Directory holding every artwork file Tempo downloads, extracts or saves.
     * Also written by MusicTrackingService and ImportExportManager.
     */
    private fun managedArtworkRoot(): File = File(context.filesDir, ALBUM_ART_DIR)

    override fun getById(id: Long): Flow<Track?> = dao.getById(id)
    override suspend fun findBySpotifyId(spotifyId: String): Track? = dao.findBySpotifyId(spotifyId)
    override suspend fun findByYoutubeId(youtubeId: String): Track? = dao.findByYoutubeId(youtubeId)
    override suspend fun findByMusicBrainzId(musicbrainzId: String): Track? = dao.findByMusicBrainzId(musicbrainzId)
    override suspend fun findByTitleAndArtist(title: String, artist: String): Track? =
        dao.findByTitleAndArtist(title, artist)
    override suspend fun findByTitleAndArtistFuzzy(title: String, artist: String): Track? = 
        dao.findByTitleAndArtistFuzzy(title, artist)
    override suspend fun findCandidatesByTitle(title: String): List<Track> =
        dao.findCandidatesByTitle(title)
    override suspend fun findCandidatesByArtist(artist: String): List<Track> =
        dao.findCandidatesByArtist(artist)
    override suspend fun findFuzzyCandidates(title: String, artist: String): List<Track> =
        dao.findFuzzyCandidates(title, artist)
    override suspend fun insert(track: Track): Long = dao.insert(track)
    override suspend fun insertAll(tracks: List<Track>): List<Long> = dao.insertAll(tracks)
    override suspend fun update(track: Track) = dao.updatePreservingManualArtwork(track)
    override suspend fun updateTitle(trackId: Long, title: String): Int =
        dao.updateTitle(trackId, title)
    override suspend fun updateAutomaticAlbumArtUrl(
        trackId: Long,
        albumArtUrl: String?,
    ): String? {
        // content:// is local artwork too, but Tempo does not own its backing
        // file. Only managed file:// backups need the stale-file existence guard.
        if (!isManagedLocalArtworkFile(albumArtUrl)) {
            return dao.updateAutomaticAlbumArtUrl(trackId, albumArtUrl)
        }
        // Non-null: isManagedLocalArtworkFile() only accepts file:// strings.
        val localUrl = albumArtUrl!!

        return withContext(Dispatchers.IO) {
            withAlbumArtBackupLock {
                // The scheme alone is not enough to adopt a backup. MediaSession art
                // URIs are supplied by whichever app is playing audio, so a path
                // outside Tempo's own artwork directory must never be stored as a
                // managed local cover (it would later be deleted as "obsolete").
                val localFile = managedArtworkFileOrNull(localUrl, managedArtworkRoot())
                if (localFile != null && localFile.exists()) {
                    dao.updateAutomaticAlbumArtUrl(trackId, localUrl)
                } else {
                    // Either a remote-success cleanup retired this exact path while a
                    // delayed tracking write was in flight, or the URL is not a
                    // Tempo-managed artwork file at all. Never persist it: prefer the
                    // current canonical remote mirror when one exists, otherwise
                    // leave the current protected Track value unchanged.
                    if (localFile == null) {
                        Log.w(TAG, "Rejected local artwork outside the managed directory")
                    }
                    dao.promoteLocalAlbumArtToCanonicalIfMatches(
                        trackId = trackId,
                        expectedLocalUrl = localUrl,
                    ) ?: dao.getCurrentTrackAlbumArtUrl(trackId)
                }
            }
        }
    }

    private suspend fun deleteLocalAlbumArtIfUnreferenced(localUrl: String) {
        // Only ever delete files inside Tempo's own artwork directory. Untrusted
        // input reaches this path (MediaSession metadata, restored backups, imported
        // exports), so a crafted file:// value must not be able to name an arbitrary
        // file such as the app's own database.
        val localFile = managedArtworkFileOrNull(localUrl, managedArtworkRoot())
        if (localFile == null) {
            if (isManagedLocalArtworkFile(localUrl)) {
                Log.w(TAG, "Refusing to delete local artwork outside the managed directory")
            }
            return
        }

        val stillReferenced =
            dao.countAlbumArtUrlReferences(localUrl) > 0 ||
                enrichedMetadataDao.countImageUrlReferences(localUrl) > 0
        if (stillReferenced) {
            Log.d(TAG, "Keeping shared local album art still referenced by another row: $localUrl")
            return
        }

        if (localFile.exists() && !localFile.delete()) {
            Log.w(TAG, "Failed to delete obsolete local album art: " + localFile.absolutePath)
        }
    }

    override suspend fun consumeLocalAlbumArtBackup(
        trackId: Long,
        expectedLocalUrl: String,
    ): String? =
        withContext(Dispatchers.IO) {
            withAlbumArtBackupLock {
                val canonicalRemote =
                    dao.promoteLocalAlbumArtToCanonicalIfMatches(
                        trackId = trackId,
                        expectedLocalUrl = expectedLocalUrl,
                    )

                if (canonicalRemote != null) {
                    deleteLocalAlbumArtIfUnreferenced(expectedLocalUrl)
                }

                canonicalRemote
            }
        }

    override suspend fun discardLocalAlbumArtBackup(expectedLocalUrl: String) =
        withContext(Dispatchers.IO) {
            withAlbumArtBackupLock {
                if (!isLocalBackupArtwork(expectedLocalUrl)) return@withAlbumArtBackupLock

                deleteLocalAlbumArtIfUnreferenced(expectedLocalUrl)
            }
        }

    override suspend fun updateYoutubeIdIfMissing(trackId: Long, youtubeId: String): Int =
        dao.updateYoutubeIdIfMissing(trackId, youtubeId)
    override fun all(): Flow<List<Track>> = dao.all()
    
    override suspend fun searchTracks(query: String): List<Track> {
        val byTitle = dao.searchByTitle(query)
        val byArtist = dao.searchByArtist(query)
        return (byTitle + byArtist).distinctBy { it.id }
    }
    
    override suspend fun deleteById(id: Long): Int = dao.deleteById(id)
    
    override suspend fun updateContentTypeByArtist(artistName: String, contentType: String): Int =
        dao.updateContentTypeByArtist(artistName, contentType)
    
    override suspend fun getTrackIdsByArtist(artistName: String): List<Long> =
        dao.getTrackIdsByArtist(artistName)
    
    override suspend fun deleteByArtist(artistName: String): Int =
        dao.deleteByArtist(artistName)

    override suspend fun deleteTrackWithAllData(trackId: Long): DeleteResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Deleting track $trackId and all associated data")
            
            // 1. Delete track_artists junction entries
            trackArtistDao.deleteAllForTrack(trackId)
            
            // 2. Delete manual content marks
            manualContentMarkDao.deleteMarksByTrackId(trackId)
            
            // 3. Delete enriched metadata
            enrichedMetadataDao.deleteByTrackId(trackId)
            
            // 4. Delete the track itself
            // (listening_events will be cascade-deleted by FK constraint)
            val deleted = dao.deleteById(trackId)
            
            if (deleted > 0) {
                Log.d(TAG, "Successfully deleted track $trackId and all associated data")
                DeleteResult(success = true)
            } else {
                Log.w(TAG, "Track $trackId not found for deletion")
                DeleteResult(success = false, error = "Track not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete track $trackId", e)
            DeleteResult(success = false, error = e.message ?: "Failed to delete track")
        }
    }
}
