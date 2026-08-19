package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.ArtistAliasDao
import me.avinas.tempo.data.local.dao.ArtistDao
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistAlias
import me.avinas.tempo.data.local.entities.ArtistRole
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackArtist
import me.avinas.tempo.utils.ArtistParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for splitting an artist — the inverse of [ArtistMergeRepository].
 *
 * Needed when tracks from genuinely different artists ended up under a single
 * artist row. The historical cause was ASCII-only name normalization, which
 * collapsed every non-ASCII (e.g. Japanese) artist name into one shared row;
 * it also covers true homonyms and over-eager manual merges.
 *
 * Design notes:
 * - Tracks are grouped by their raw primary-artist string — the surviving
 *   ground truth of what was actually playing.
 * - Moving a track re-points its junction rows (preserving role/credit order,
 *   reusing the same conflict rules as merge), updates tracks.primary_artist_id,
 *   and rewrites the denormalized tracks.artist string when it still credits
 *   the source.
 * - An alias (original raw name -> target) is created for moved groups so
 *   future plays of that exact name land on the right artist. Aliases are
 *   NEVER created for names that still belong to the source artist.
 * - If the source artist is fully drained, it is deleted (aliases pointing to
 *   it cascade).
 */
@Singleton
class ArtistSplitRepository @Inject constructor(
    private val artistDao: ArtistDao,
    private val trackDao: TrackDao,
    private val trackArtistDao: TrackArtistDao,
    private val artistAliasDao: ArtistAliasDao,
    private val artistLinkingService: ArtistLinkingService,
    private val statsRepository: StatsRepository,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "ArtistSplitRepository"
    }

    /**
     * A group of tracks sharing the same raw primary-artist string.
     */
    data class ArtistSplitGroup(
        /** Distinct raw primary-artist name parsed from tracks.artist */
        val rawName: String,
        /** Tracks in this group, sorted by title */
        val tracks: List<Track>,
        /** True when this group's name resolves to the source artist itself */
        val isSourceName: Boolean
    )

    /**
     * One split operation: move [trackIds] to an artist named [targetName]
     * (created if it does not exist). [rawName] is the group's original raw
     * name, used to pin an alias for future plays (optional).
     */
    data class SplitMove(
        val trackIds: List<Long>,
        val targetName: String,
        val rawName: String? = null
    )

    /**
     * Summary of an executed split.
     */
    data class SplitResult(
        val movedTrackCount: Int,
        val targetArtistNames: List<String>,
        val sourceDeleted: Boolean
    )

    /**
     * Group all tracks currently linked to [sourceArtistId] by their raw
     * primary-artist string. The group matching the source artist's own name
     * comes first, the rest follow by size (largest first).
     */
    suspend fun getSplitGroups(sourceArtistId: Long): List<ArtistSplitGroup> {
        val source = artistDao.getArtistById(sourceArtistId) ?: return emptyList()
        val tracks = (trackArtistDao.getTracksForArtist(sourceArtistId) +
            trackDao.getTracksByPrimaryArtist(sourceArtistId))
            .distinctBy { it.id }

        return tracks
            .groupBy { track ->
                val parsed = ArtistParser.parse(track.artist)
                parsed.primaryArtist.trim().ifBlank { track.artist.trim() }
            }
            .map { (rawName, groupTracks) ->
                ArtistSplitGroup(
                    rawName = rawName,
                    tracks = groupTracks.sortedBy { it.title },
                    isSourceName = ArtistParser.isSameArtist(rawName, source.name) ||
                        Artist.normalizeName(rawName) == source.normalizedName
                )
            }
            .sortedWith(
                compareByDescending<ArtistSplitGroup> { it.isSourceName }
                    .thenByDescending { it.tracks.size }
            )
    }

    /**
     * Execute a split. All moves run in a single transaction.
     *
     * @return [SplitResult] on success, null on failure.
     */
    suspend fun splitArtist(sourceArtistId: Long, moves: List<SplitMove>): SplitResult? {
        val source = artistDao.getArtistById(sourceArtistId)
        if (source == null) {
            Log.w(TAG, "Source artist $sourceArtistId not found")
            return null
        }
        if (moves.isEmpty()) {
            Log.w(TAG, "No moves requested")
            return null
        }

        Log.i(TAG, "Splitting '${source.name}' (id=$sourceArtistId) with ${moves.size} moves")

        return try {
            var moved = 0
            val targetNames = mutableListOf<String>()

            database.withTransaction {
                for (move in moves) {
                    val targetName = move.targetName.trim()
                    if (targetName.isBlank() || move.trackIds.isEmpty()) continue

                    // Get-or-create respects aliases and normalized dedup
                    val target = artistLinkingService.getOrCreateArtist(targetName)
                    if (target.id == sourceArtistId) {
                        Log.d(TAG, "Skipping move to '$targetName' — resolves to the source artist")
                        continue
                    }

                    for (trackId in move.trackIds) {
                        val track = trackDao.getTrackById(trackId) ?: continue
                        moveTrack(track, source, target)
                        moved++
                    }
                    if (target.name !in targetNames) targetNames += target.name

                    maybeCreateSplitAlias(source, target, move.rawName)
                }
            }

            // Clean up a fully drained source artist
            val remainingLinked = trackArtistDao.getTrackCountForArtist(sourceArtistId)
            val remainingPrimary = trackDao.getTracksByPrimaryArtist(sourceArtistId).size
            val sourceDeleted = if (remainingLinked == 0 && remainingPrimary == 0) {
                artistDao.deleteById(sourceArtistId)
                Log.i(TAG, "Source artist '${source.name}' fully drained — deleted")
                true
            } else {
                false
            }

            statsRepository.invalidateCache()
            Log.i(TAG, "Split complete: $moved tracks moved to $targetNames, sourceDeleted=$sourceDeleted")
            SplitResult(moved, targetNames, sourceDeleted)
        } catch (e: Exception) {
            Log.e(TAG, "Split failed: ${e.message}", e)
            null
        }
    }

    /**
     * Move a single track's credit from [source] to [target]:
     * 1. Rewrite the raw artist string when it still credits the source
     *    (covers strings overwritten by the old buggy linker).
     * 2. Re-point tracks.primary_artist_id if it references the source.
     * 3. Re-point the junction row (role/credit-order preserving,
     *    conflict-safe against an existing target credit).
     */
    private suspend fun moveTrack(track: Track, source: Artist, target: Artist) {
        // 1. String fix — only when the string does not already name the target
        val namesTarget = ArtistParser.parse(track.artist).allArtists.any {
            ArtistParser.isSameArtist(it, target.name) ||
                Artist.normalizeName(it) == target.normalizedName
        }
        if (!namesTarget) {
            val newString = replaceCreditInString(track.artist, source.name, target.name)
            if (newString != track.artist) {
                trackDao.updateArtistString(track.id, newString)
            }
        }

        // 2. Primary artist FK
        if (track.primaryArtistId == source.id) {
            trackDao.updatePrimaryArtistId(track.id, target.id)
        }

        // 3. Junction rows
        val rels = trackArtistDao.getRelationshipsForTrack(track.id)
        val sourceRel = rels.find { it.artistId == source.id }
        if (sourceRel != null) {
            val targetRel = rels.find { it.artistId == target.id }
            trackArtistDao.delete(sourceRel)
            when {
                targetRel == null ->
                    trackArtistDao.insert(sourceRel.copy(artistId = target.id))
                // PRIMARY beats FEATURED — upgrade the existing target credit
                sourceRel.role == ArtistRole.PRIMARY && targetRel.role == ArtistRole.FEATURED -> {
                    trackArtistDao.delete(targetRel)
                    trackArtistDao.insert(
                        targetRel.copy(
                            role = ArtistRole.PRIMARY,
                            creditOrder = minOf(targetRel.creditOrder, sourceRel.creditOrder)
                        )
                    )
                }
                // Otherwise the existing target credit already covers this track
            }
        } else if (rels.none { it.artistId == target.id }) {
            // No junction rows at all (legacy track) — create the target credit
            trackArtistDao.insert(
                TrackArtist(
                    trackId = track.id,
                    artistId = target.id,
                    role = ArtistRole.PRIMARY,
                    creditOrder = 0
                )
            )
        }
    }

    /**
     * Pin future plays of a moved group's original raw name to the target
     * artist, so the split persists for incoming scrobbles.
     *
     * Safety rules — an alias is NOT created when:
     * - the raw name still resolves to the source artist's identity
     *   (would hijack the source's own future plays),
     * - an alias for that name already exists (user/system pin wins),
     * - the target already IS that exact name (lookup finds it directly).
     */
    private suspend fun maybeCreateSplitAlias(source: Artist, target: Artist, rawName: String?) {
        if (rawName.isNullOrBlank()) return
        val key = Artist.normalizeName(rawName)
        if (key.isBlank()) return
        if (key == source.normalizedName) return
        if (ArtistParser.isSameArtist(rawName, source.name)) return
        if (key == target.normalizedName) return
        if (artistAliasDao.findAlias(key) != null) return

        artistAliasDao.insertAlias(ArtistAlias.create(rawName, target.id))
        Log.d(TAG, "Created split alias: '$rawName' -> '${target.name}'")
    }

    /**
     * Replace one artist credit inside a raw artist string, conservatively.
     * Handles: whole-string match, ", "-separated segments in any position,
     * and a trailing "feat./ft./featuring/with" credit. Returns the input
     * unchanged when no segment matches.
     */
    private fun replaceCreditInString(raw: String, sourceName: String, targetName: String): String {
        if (raw.equals(sourceName, ignoreCase = true)) return targetName

        // Comma-separated segments (leading / middle / trailing)
        var result = raw.split(", ").joinToString(", ") { segment ->
            if (segment.equals(sourceName, ignoreCase = true)) targetName else segment
        }

        // Trailing featured credit: "X feat. Source" -> "X feat. Target"
        for (sep in listOf(" feat. ", " ft. ", " featuring ", " with ")) {
            val idx = result.indexOf(sep, ignoreCase = true)
            if (idx >= 0) {
                val after = result.substring(idx + sep.length)
                if (after.equals(sourceName, ignoreCase = true)) {
                    result = result.substring(0, idx + sep.length) + targetName
                }
            }
        }
        return result
    }
}
