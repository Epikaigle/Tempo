package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.ArchiveTimestampCodec
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackAlias
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import javax.inject.Singleton

/**
 * Repository for managing track alias and merge operations.
 *
 * When merging tracks:
 * 1. Creates an alias from source -> target for future lookups
 * 2. Re-points any existing aliases that pointed to source to now point to target
 *    (handles chained merges: A->B, B->C results in A->C)
 * 3. Merges useful metadata from source to target (album art, IDs, etc.)
 * 4. Moves all listening history from source to target
 * 5. Deletes the source track
 * 6. Invalidates stats cache to refresh UI
 */
@Singleton
open class TrackAliasRepository @Inject constructor(
    private val trackAliasDao: TrackAliasDao,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val scrobbleArchiveDao: ScrobbleArchiveDao,
    private val database: AppDatabase,
    private val statsRepository: StatsRepository,
    private val artistLinkingService: ArtistLinkingService,
    private val tracker: AnalyticsTracker
) {
    companion object {
        private const val TAG = "TrackAliasRepository"
    }

    /**
     * Transaction runner. Defaults to Room's [withTransaction], but can be overridden in tests.
     */
    internal var transactionRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        database.withTransaction { block() }
    }

    /**
     * Find an alias for the given title and artist.
     * Returns the alias if one exists, or null.
     */
    suspend fun findAlias(title: String, artist: String): TrackAlias? {
        return trackAliasDao.findAlias(title, artist)
    }

    /**
     * Create a new alias mapping (originalTitle, originalArtist) -> targetTrackId.
     */
    suspend fun createAlias(targetTrackId: Long, originalTitle: String, originalArtist: String) {
        createAliasInternal(targetTrackId, originalTitle, originalArtist)
        tracker.track(FeatureUsed(TempoFeature.ALIAS_RENAME))
    }

    private suspend fun createAliasInternal(
        targetTrackId: Long,
        originalTitle: String,
        originalArtist: String
    ) {
        val alias = TrackAlias(
            targetTrackId = targetTrackId,
            originalTitle = originalTitle,
            originalArtist = originalArtist
        )
        trackAliasDao.insertAlias(alias)
    }
    
    /**
     * Get all aliases that point to a specific track.
     */
    suspend fun getAliasesForTrack(trackId: Long): List<TrackAlias> {
        return trackAliasDao.getAliasesForTrack(trackId)
    }

    /**
     * Rename [trackId] to [newTitle] and remember [previousTitle] as an alias, so
     * plays and imports that still arrive under the old title resolve to this row
     * instead of re-creating it.
     *
     * The title write, the alias insert and the archive re-key share one transaction:
     * a rename that matched no row must not leave an alias behind pointing at a title
     * the track never had.
     *
     * Cache invalidation is part of the operation, not an afterthought. Per-track
     * details and every top-tracks list are memoized for the stats cache TTL, so
     * writing the row alone leaves every screen reloading the pre-rename title —
     * the edit looks like it never saved until the cache happens to expire.
     *
     * @return true only when a row was actually rewritten.
     */
    open suspend fun renameTrack(
        trackId: Long,
        previousTitle: String,
        newTitle: String,
        artist: String
    ): Boolean {
        val renamed = try {
            var rows = 0
            transactionRunner {
                rows = trackDao.updateTitle(trackId, newTitle)
                if (rows > 0) {
                    createAliasInternal(trackId, previousTitle, artist)
                    // The compressed long tail is keyed by (artist, title), so it does not
                    // follow a rename on its own. Left alone it keeps serving the old name
                    // to the All-History view and can never be recognised as already
                    // promoted — the same reason the merge path re-keys it.
                    rekeyArchiveRow(
                        previousArtist = artist,
                        previousTitle = previousTitle,
                        newArtist = artist,
                        newTitle = newTitle
                    )
                }
            }
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming track $trackId to '$newTitle'", e)
            false
        }

        if (!renamed) {
            Log.w(TAG, "Rename of track $trackId to '$newTitle' matched no row")
            return false
        }

        statsRepository.invalidateCache()
        tracker.track(FeatureUsed(TempoFeature.ALIAS_RENAME))
        Log.i(TAG, "Renamed track $trackId: '$previousTitle' -> '$newTitle'")
        return true
    }

    /**
     * Move the compressed archive row for one identity onto another.
     *
     * `scrobbles_archive` is the only play store keyed by title rather than by track id,
     * so a rename or merge that skips it leaves the old name behind: the All-History
     * view keeps listing the old title's archived plays beside the renamed track's
     * active ones, and `prunePromotedArchives` can never match the row to the track it
     * was promoted into. Timestamps are union-merged in case the new identity already
     * has an archive row of its own.
     */
    private suspend fun rekeyArchiveRow(
        previousArtist: String,
        previousTitle: String,
        newArtist: String,
        newTitle: String
    ): Boolean {
        val archiveRow = scrobbleArchiveDao.findByArtistAndTitle(previousArtist, previousTitle)
            ?: return false

        val newHash = ScrobbleArchive.generateTrackHash(newArtist, newTitle)
        if (newHash == archiveRow.trackHash) return false

        val retargeted = archiveRow.copy(
            id = 0,
            trackHash = newHash,
            trackTitle = newTitle,
            artistName = newArtist,
            artistNameNormalized = newArtist.lowercase().trim(),
            updatedAt = System.currentTimeMillis()
        )
        scrobbleArchiveDao.upsertWithMerge(
            retargeted,
            ArchiveTimestampCodec::decompress,
            ArchiveTimestampCodec::compress
        )
        scrobbleArchiveDao.delete(archiveRow)
        Log.d(TAG, "Re-keyed scrobble archive row: '$previousTitle' -> '$newTitle'")
        return true
    }

    
    /**
     * Merges source track into target track:
     * 1. Creates alias (Source -> Target)
     * 2. Re-points existing aliases that pointed to source to now point to target
     *    (handles chained merges so no aliases are lost when source is deleted)
     * 3. Merges metadata from source to target (fills in missing fields)
     * 4. Moves listening history from source to target
     * 5. Deletes source track
     * 6. Invalidates stats cache
     *
     * @param sourceTrackId The track to merge FROM (will be deleted)
     * @param targetTrackId The track to merge INTO (will remain)
     * @return true if merge succeeded, false otherwise
     */
    open suspend fun mergeTracks(sourceTrackId: Long, targetTrackId: Long): Boolean {
        val merged = mergeTracksInternal(sourceTrackId, targetTrackId)
        if (merged) tracker.track(FeatureUsed(TempoFeature.TRACK_MERGE))
        return merged
    }

    private suspend fun mergeTracksInternal(sourceTrackId: Long, targetTrackId: Long): Boolean {
        if (sourceTrackId == targetTrackId) {
            Log.w(TAG, "Cannot merge track into itself")
            return false
        }
        
        val sourceTrack = trackDao.getTrackById(sourceTrackId)
        val targetTrack = trackDao.getTrackById(targetTrackId)
        
        if (sourceTrack == null) {
            Log.w(TAG, "Source track $sourceTrackId not found")
            return false
        }
        if (targetTrack == null) {
            Log.w(TAG, "Target track $targetTrackId not found")
            return false
        }
        
        Log.i(TAG, "Merging track '${sourceTrack.title}' by '${sourceTrack.artist}' " +
                "into '${targetTrack.title}' by '${targetTrack.artist}'")
        
        return try {
            database.withTransaction {
                // 1. Create alias for future lookups
                val alias = TrackAlias(
                    targetTrackId = targetTrackId,
                    originalTitle = sourceTrack.title,
                    originalArtist = sourceTrack.artist
                )
                trackAliasDao.insertAlias(alias)
                Log.d(TAG, "Created alias: '${sourceTrack.title}' -> '${targetTrack.title}'")
                
                // 2. CRITICAL: Re-point existing aliases that pointed to source track
                // This handles chained merges: if "Song (Live)" was merged into "Song",
                // and now "Song" is merged into "Song (Remastered)", the alias for
                // "Song (Live)" must now point to "Song (Remastered)" to not be lost
                // when the CASCADE delete removes it.
                val existingAliases = trackAliasDao.getAliasesForTrack(sourceTrackId)
                for (existingAlias in existingAliases) {
                    // Re-create the alias pointing to the new target
                    // (delete old + insert new since we can't just update the FK)
                    trackAliasDao.insertAlias(
                        TrackAlias(
                            targetTrackId = targetTrackId,
                            originalTitle = existingAlias.originalTitle,
                            originalArtist = existingAlias.originalArtist
                        )
                    )
                }
                if (existingAliases.isNotEmpty()) {
                    Log.d(TAG, "Re-pointed ${existingAliases.size} existing aliases to target")
                }
                
                // 3. Merge metadata from source into target (fill missing fields)
                var updatedTarget = mergeTrackMetadata(sourceTrack, targetTrack)
                // A merge target whose artist is a structural placeholder label
                // (e.g. the Takeout "Release" artifact) must not keep it — the
                // surviving track would stay pooled under the bogus artist and
                // stats would never recognize the real one. Adopt the source's
                // real artist instead, then re-run the linking pipeline so
                // artist junctions / primary_artist_id follow the fix.
                val artistOverridden = ArtistParser.isPlaceholderArtistName(targetTrack.artist) &&
                    !ArtistParser.isPlaceholderArtistName(sourceTrack.artist)
                if (artistOverridden) {
                    updatedTarget = updatedTarget.copy(artist = sourceTrack.artist)
                }
                if (updatedTarget != targetTrack) {
                    trackDao.update(updatedTarget)
                    Log.d(TAG, "Updated target track metadata")
                }
                if (artistOverridden) {
                    try {
                        artistLinkingService.linkArtistsForTrack(updatedTarget)
                        Log.i(
                            TAG,
                            "Replaced placeholder artist '${targetTrack.artist}' with " +
                                "'${sourceTrack.artist}' on merged track '${updatedTarget.title}'"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-link merged track ${targetTrackId}", e)
                    }
                }
                
                // 4. Move listening history through the dedup pipeline.
                // A blind UPDATE (old reattributeEvents) kept stale content
                // fingerprints computed for the source track and double-counted
                // plays that already existed on the target. Re-inserting with
                // contentFingerprint=null forces recomputation for the target
                // track and reconciles same-play conflicts.
                val sourceEvents = listeningEventDao.getEventsForTrack(sourceTrackId)
                if (sourceEvents.isNotEmpty()) {
                    val moved = sourceEvents.map {
                        it.copy(id = 0, track_id = targetTrackId, contentFingerprint = null)
                    }
                    val moveResult = listeningEventDao.insertAllBatchedWithDedup(moved)
                    listeningEventDao.deleteEventsForTrack(sourceTrackId)
                    Log.d(TAG, "Moved listening history: ${moveResult.inserted} inserted, " +
                            "${moveResult.skipped} deduplicated against target")
                }

                // 5. Re-key the compressed scrobble archive row (Last.fm long-tail
                // history) so it follows the merged identity.
                rekeyArchiveRow(
                    previousArtist = sourceTrack.artist,
                    previousTitle = sourceTrack.title,
                    newArtist = targetTrack.artist,
                    newTitle = targetTrack.title
                )

                // 6. Delete source track (CASCADE will clean up old aliases and track_artists)
                trackDao.deleteById(sourceTrackId)
                Log.i(TAG, "Deleted source track '${sourceTrack.title}'")
            }
            
            // 6. Invalidate stats cache to refresh UI
            statsRepository.invalidateCache()
            Log.d(TAG, "Invalidated stats cache after merge")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging tracks: ${e.message}", e)
            false
        }
    }
    
    /**
     * Merge metadata from source track into target track.
     * Only copies fields that target is missing.
     */
    private fun mergeTrackMetadata(source: Track, target: Track): Track {
        var result = target
        
        // Copy album if target doesn't have one
        if (target.album.isNullOrBlank() && !source.album.isNullOrBlank()) {
            result = result.copy(album = source.album)
        }
        
        // Copy album art URL if target doesn't have one
        if (target.albumArtUrl.isNullOrBlank() && !source.albumArtUrl.isNullOrBlank()) {
            result = result.copy(albumArtUrl = source.albumArtUrl)
        }
        
        // Copy Spotify ID if target doesn't have one
        if (target.spotifyId.isNullOrBlank() && !source.spotifyId.isNullOrBlank()) {
            result = result.copy(spotifyId = source.spotifyId)
        }
        
        // Copy MusicBrainz ID if target doesn't have one
        if (target.musicbrainzId.isNullOrBlank() && !source.musicbrainzId.isNullOrBlank()) {
            result = result.copy(musicbrainzId = source.musicbrainzId)
        }
        
        // Copy duration if target doesn't have one
        if ((target.duration == null || target.duration == 0L) && source.duration != null && source.duration > 0) {
            result = result.copy(duration = source.duration)
        }
        
        return result
    }

    /**
     * Get all aliases for backup/export.
     */
    suspend fun getAllAliases(): List<TrackAlias> {
        return trackAliasDao.getAllSync()
    }
}
