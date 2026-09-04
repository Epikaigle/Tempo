package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.AlbumDao
import me.avinas.tempo.data.local.dao.AlbumSearchResult
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Album
import me.avinas.tempo.data.local.entities.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing album merging operations.
 *
 * When merging albums:
 * 1. Matches tracks between source and target albums:
 *    - Duplicate tracks (same title) are merged via [TrackAliasRepository.mergeTracks],
 *      consolidating listening history, deduplicating scrobbles, and re-pointing aliases.
 *    - Unique tracks on the source album are reassigned to the target album title.
 * 2. Re-points any remaining tracks on the source album to the target album.
 * 3. Rewrites compressed scrobble archive rows so long-tail history reflects the new album.
 * 4. Copies useful metadata from source album to target album (artwork, year, MBID, release type).
 * 5. Deletes the source album.
 * 6. Invalidates stats cache to refresh UI across the app.
 */
@Singleton
open class AlbumMergeRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val trackDao: TrackDao,
    private val trackAliasRepository: TrackAliasRepository,
    private val scrobbleArchiveDao: ScrobbleArchiveDao,
    private val database: AppDatabase,
    private val statsRepository: StatsRepository
) {
    companion object {
        private const val TAG = "AlbumMergeRepository"
    }
    /**
     * Transaction runner. Defaults to Room's [withTransaction], but can be overridden in tests.
     */
    internal var transactionRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        database.withTransaction { block() }
    }


    /**
     * Search for albums matching the query by title or artist name.
     * Excludes the source album.
     */
    open suspend fun searchAlbums(
        query: String,
        excludeAlbumId: Long? = null,
        limit: Int = 30
    ): List<AlbumSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return albumDao.searchAlbumsWithArtist(trimmed, excludeAlbumId, limit)
    }

    /**
     * Get all albums by a specific artist (for initial suggestions before user types).
     * Excludes the source album.
     */
    open suspend fun getAlbumsForArtist(
        artistId: Long,
        excludeAlbumId: Long? = null
    ): List<AlbumSearchResult> {
        return albumDao.getAlbumsByArtistWithArtist(artistId, excludeAlbumId)
    }

    /**
     * Merges source album into target album:
     * 1. Merges matching tracks (duplicate titles) via TrackAliasRepository.mergeTracks
     * 2. Reassigns unique tracks from source album to target album
     * 3. Updates scrobble archive rows
     * 4. Copies missing metadata from source album to target album
     * 5. Deletes source album
     * 6. Invalidates stats cache
     *
     * @param sourceAlbumId The album to merge FROM (will be deleted)
     * @param targetAlbumId The album to merge INTO (will remain)
     * @return true if merge succeeded, false otherwise
     */
    open suspend fun mergeAlbums(sourceAlbumId: Long, targetAlbumId: Long): Boolean {
        if (sourceAlbumId == targetAlbumId) {
            Log.w(TAG, "Cannot merge album into itself (id=$sourceAlbumId)")
            return false
        }

        val sourceAlbum = albumDao.getAlbumById(sourceAlbumId)
        val targetAlbum = albumDao.getAlbumById(targetAlbumId)

        if (sourceAlbum == null) {
            Log.w(TAG, "Source album $sourceAlbumId not found")
            return false
        }
        if (targetAlbum == null) {
            Log.w(TAG, "Target album $targetAlbumId not found")
            return false
        }

        val sourceArtist = artistDao.getArtistById(sourceAlbum.artistId)
        val targetArtist = artistDao.getArtistById(targetAlbum.artistId)

        if (sourceArtist == null) {
            Log.w(TAG, "Source artist ${sourceAlbum.artistId} not found")
            return false
        }
        if (targetArtist == null) {
            Log.w(TAG, "Target artist ${targetAlbum.artistId} not found")
            return false
        }

        Log.i(
            TAG,
            "Merging album '${sourceAlbum.title}' by '${sourceArtist.name}' " +
                "into '${targetAlbum.title}' by '${targetArtist.name}'"
        )

        return try {
            transactionRunner {
                // 1. Fetch tracks for both albums
                val sourceTracks = trackDao.getTracksForAlbumByArtist(
                    sourceAlbum.title,
                    sourceArtist.id,
                    sourceArtist.name
                )
                val targetTracks = trackDao.getTracksForAlbumByArtist(
                    targetAlbum.title,
                    targetArtist.id,
                    targetArtist.name
                )

                var mergedTracksCount = 0
                var movedTracksCount = 0

                // 2. Track deduplication and reassignment
                for (sTrack in sourceTracks) {
                    val matchingTarget = targetTracks.find {
                        it.title.trim().equals(sTrack.title.trim(), ignoreCase = true)
                    }

                    if (matchingTarget != null) {
                        // Consolidate identical track into target
                        val trackMerged = trackAliasRepository.mergeTracks(sTrack.id, matchingTarget.id)
                        if (trackMerged) {
                            mergedTracksCount++
                        } else {
                            // Fallback if track merge failed: reassign album
                            trackDao.setTrackAlbum(sTrack.id, targetAlbum.title)
                            movedTracksCount++
                        }
                    } else {
                        // Unique track on source album: move to target album
                        trackDao.setTrackAlbum(sTrack.id, targetAlbum.title)
                        if (targetArtist.id != sourceArtist.id) {
                            trackDao.updatePrimaryArtistId(sTrack.id, targetArtist.id)
                            trackDao.updateArtistString(sTrack.id, targetArtist.name)
                        }
                        movedTracksCount++
                    }
                }
                Log.d(
                    TAG,
                    "Processed tracks: $mergedTracksCount duplicate tracks merged, " +
                        "$movedTracksCount unique tracks moved to target album"
                )

                // 3. Catch-all: update any remaining tracks with sourceAlbum.title
                val remainingReassigned = trackDao.reassignAlbumTracks(
                    sourceAlbum.title,
                    targetAlbum.title,
                    sourceArtist.id,
                    sourceArtist.name
                )
                if (remainingReassigned > 0) {
                    Log.d(TAG, "Reassigned $remainingReassigned additional track(s) by title match")
                }

                // 4. Update scrobble archive
                val normalizedSourceArtist = sourceArtist.name.lowercase().trim()
                val archiveUpdated = scrobbleArchiveDao.updateAlbumName(
                    sourceAlbum.title,
                    targetAlbum.title,
                    normalizedSourceArtist
                )
                if (archiveUpdated > 0) {
                    Log.d(TAG, "Updated $archiveUpdated scrobble archive rows to '${targetAlbum.title}'")
                }

                // 5. Merge album metadata
                val updatedTarget = mergeAlbumMetadata(sourceAlbum, targetAlbum)
                if (updatedTarget != targetAlbum) {
                    if (targetAlbum.musicbrainzId.isNullOrBlank() && !sourceAlbum.musicbrainzId.isNullOrBlank()) {
                        // Clear musicbrainzId on source first to avoid UNIQUE constraint violation
                        albumDao.update(sourceAlbum.copy(musicbrainzId = null))
                    }
                    albumDao.update(updatedTarget)
                    Log.d(TAG, "Updated target album metadata")
                }

                // 6. Delete source album
                albumDao.deleteById(sourceAlbum.id)
                Log.i(TAG, "Deleted source album '${sourceAlbum.title}' (id=${sourceAlbum.id})")
            }

            // 7. Invalidate stats cache so UI refreshes immediately
            statsRepository.invalidateCache()
            Log.d(TAG, "Invalidated stats cache after album merge")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging albums: ${e.message}", e)
            false
        }
    }

    /**
     * Merge metadata from source album into target album.
     * Only copies fields that target is missing.
     */
    private fun mergeAlbumMetadata(source: Album, target: Album): Album {
        var result = target

        // Copy artwork URL if target lacks it
        if (target.artworkUrl.isNullOrBlank() && !source.artworkUrl.isNullOrBlank()) {
            result = result.copy(artworkUrl = source.artworkUrl)
        }

        // Copy release year if target lacks it
        if (target.releaseYear == null && source.releaseYear != null) {
            result = result.copy(releaseYear = source.releaseYear)
        }

        // Copy release type if target lacks it
        if (target.releaseType.isNullOrBlank() && !source.releaseType.isNullOrBlank()) {
            result = result.copy(releaseType = source.releaseType)
        }

        // Copy MusicBrainz ID if target lacks it
        if (target.musicbrainzId.isNullOrBlank() && !source.musicbrainzId.isNullOrBlank()) {
            result = result.copy(musicbrainzId = source.musicbrainzId)
        }

        return result
    }
}
