package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ManualContentMark
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualContentMarkDao {
    /**
     * Get all manual content marks, newest first so equal-specificity rules resolve
     * deterministically to the latest user correction.
     */
    @Query("SELECT * FROM manual_content_marks ORDER BY marked_at DESC")
    fun getAllMarks(): Flow<List<ManualContentMark>>

    /**
     * Get all marks for a specific content type.
     */
    @Query("SELECT * FROM manual_content_marks WHERE content_type = :contentType")
    fun getMarksByType(contentType: String): Flow<List<ManualContentMark>>

    /**
     * Resolve the effective matching rule using the same precedence as the service:
     * TITLE_ARTIST > TITLE > ARTIST, newest rule first for equal specificity.
     */
    @Query("""
        SELECT * FROM manual_content_marks
        WHERE (pattern_type = 'TITLE_ARTIST' AND LOWER(original_title) = LOWER(:title) AND LOWER(original_artist) = LOWER(:artist))
           OR (pattern_type = 'TITLE_ARTIST' AND LOWER(pattern_value) = LOWER(:title) AND LOWER(original_artist) = LOWER(:artist))
           OR (pattern_type = 'TITLE' AND LOWER(original_title) = LOWER(:title))
           OR (pattern_type = 'ARTIST' AND LOWER(original_artist) = LOWER(:artist))
        ORDER BY
            CASE pattern_type
                WHEN 'TITLE_ARTIST' THEN 3
                WHEN 'TITLE' THEN 2
                WHEN 'ARTIST' THEN 1
                ELSE 0
            END DESC,
            marked_at DESC
        LIMIT 1
    """)
    suspend fun findMatchingMark(title: String, artist: String): ManualContentMark?

    /**
     * Insert a new manual mark.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMark(mark: ManualContentMark): Long

    /**
     * Delete a specific mark.
     */
    @Delete
    suspend fun deleteMark(mark: ManualContentMark)

    /**
     * Delete all marks for a specific track.
     */
    @Query("DELETE FROM manual_content_marks WHERE target_track_id = :trackId")
    suspend fun deleteMarksByTrackId(trackId: Long)

    /**
     * Delete all marks.
     */
    @Query("DELETE FROM manual_content_marks")
    suspend fun deleteAll()

    /**
     * Get all manual content marks for synchronous rule evaluation, newest first.
     */
    @Query("SELECT * FROM manual_content_marks ORDER BY marked_at DESC")
    suspend fun getAllSync(): List<ManualContentMark>
}
