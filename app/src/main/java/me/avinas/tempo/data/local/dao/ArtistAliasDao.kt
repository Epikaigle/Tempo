package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.ArtistAlias
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing artist aliases.
 * 
 * Artist aliases are used to redirect plays from merged artist names
 * to their canonical artist.
 */
@Dao
interface ArtistAliasDao {

    // Insert Operations
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: ArtistAlias): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<ArtistAlias>)
    
    @Query("UPDATE artist_aliases SET target_artist_id = :newTargetId WHERE id = :aliasId")
    suspend fun updateTargetArtist(aliasId: Long, newTargetId: Long)

    // Query Operations
    @Query("SELECT * FROM artist_aliases WHERE original_name_normalized = :normalizedName LIMIT 1")
    suspend fun findAlias(normalizedName: String): ArtistAlias?

    @Query("SELECT * FROM artist_aliases WHERE original_name_normalized IN (:normalizedNames)")
    suspend fun findAliasesByNormalizedNames(normalizedNames: List<String>): List<ArtistAlias>

    @Query("UPDATE artist_aliases SET original_name_normalized = :newKey WHERE id = :id")
    suspend fun updateNormalizedName(id: Long, newKey: String)

    @Query("SELECT * FROM artist_aliases WHERE target_artist_id = :artistId ORDER BY created_at DESC")
    suspend fun getAliasesForArtist(artistId: Long): List<ArtistAlias>
    
    @Query("SELECT * FROM artist_aliases WHERE target_artist_id = :artistId ORDER BY created_at DESC")
    fun observeAliasesForArtist(artistId: Long): Flow<List<ArtistAlias>>
    
    @Query("SELECT * FROM artist_aliases ORDER BY created_at DESC")
    suspend fun getAllSync(): List<ArtistAlias>
    
    @Query("SELECT EXISTS(SELECT 1 FROM artist_aliases WHERE original_name_normalized = :normalizedName)")
    suspend fun hasAlias(normalizedName: String): Boolean

    // Delete Operations
    @Query("DELETE FROM artist_aliases WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM artist_aliases WHERE target_artist_id = :artistId")
    suspend fun deleteAllForArtist(artistId: Long)
    
    @Delete
    suspend fun delete(alias: ArtistAlias)
    
    @Query("DELETE FROM artist_aliases")
    suspend fun deleteAll()

    // Count Operations

    /**
     * Count total aliases.
     */
    @Query("SELECT COUNT(*) FROM artist_aliases")
    suspend fun countAll(): Int
    
    /**
     * Count aliases for a specific artist.
     */
    @Query("SELECT COUNT(*) FROM artist_aliases WHERE target_artist_id = :artistId")
    suspend fun countForArtist(artistId: Long): Int
}
