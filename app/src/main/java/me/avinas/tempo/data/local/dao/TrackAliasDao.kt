package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackAlias

@Dao
interface TrackAliasDao {
    /**
     * Find the alias recorded for [title]/[artist].
     *
     * Matched case-insensitively on purpose: the stored strings are whatever the track
     * held when the user renamed or merged it, while the play can arrive from another
     * source with its own casing ("Song" from Spotify, "song" from Last.fm). Matching on
     * case would miss the alias and re-create the row the user asked to get rid of.
     *
     * The (original_title, original_artist) index is BINARY, so this comparison scans
     * rather than seeks — affordable because the table holds one row per rename or merge
     * a person has performed, and each caller reaches it once per unique name per run.
     *
     * [id] ordering keeps the result deterministic should two rows differ only by case.
     */
    @Query(
        """
        SELECT * FROM track_aliases
        WHERE original_title = :title COLLATE NOCASE
        AND original_artist = :artist COLLATE NOCASE
        ORDER BY id DESC
        LIMIT 1
    """,
    )
    suspend fun findAlias(title: String, artist: String): TrackAlias?

    /**
     * Resolve [title]/[artist] to the track that absorbed it, or null when no alias was
     * ever recorded for that name.
     *
     * This is the identity step importers use: a re-import of a title the user renamed
     * away (or merged into another row) belongs to the surviving track, not to a new
     * row. Joining rather than returning the alias row keeps the foreign key's CASCADE
     * semantics honest — an alias whose target is gone resolves to null instead of to a
     * dangling id, so the caller falls through to its normal matching.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN track_aliases a ON a.target_track_id = t.id
        WHERE a.original_title = :title COLLATE NOCASE
        AND a.original_artist = :artist COLLATE NOCASE
        ORDER BY a.id DESC
        LIMIT 1
    """,
    )
    suspend fun findAliasTargetTrack(title: String, artist: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: TrackAlias): Long
    
    @Query("SELECT * FROM track_aliases WHERE target_track_id = :trackId")
    suspend fun getAliasesForTrack(trackId: Long): List<TrackAlias>
    
    @Query("DELETE FROM track_aliases WHERE target_track_id = :trackId")
    suspend fun clearAliasesForTrack(trackId: Long)
    
    /**
     * Get all track aliases for export.
     */
    @Query("SELECT * FROM track_aliases")
    suspend fun getAllSync(): List<TrackAlias>
}
