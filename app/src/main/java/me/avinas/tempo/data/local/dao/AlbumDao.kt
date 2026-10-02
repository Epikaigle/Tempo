package me.avinas.tempo.data.local.dao

import androidx.room.*
import me.avinas.tempo.data.local.entities.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE id = :id")
    fun getById(id: Long): Flow<Album?>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: Long): Album?

    @Query("""
        SELECT a.* FROM albums a 
        INNER JOIN artists ar ON a.artist_id = ar.id 
        WHERE a.title = :title AND ar.name = :artistName 
        LIMIT 1
    """)
    suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): Album?
    
    @Query("SELECT * FROM albums WHERE musicbrainz_id = :mbid LIMIT 1")
    suspend fun getAlbumByMusicBrainzId(mbid: String): Album?

    @Query("SELECT * FROM albums WHERE artist_id = :artistId")
    fun albumsForArtist(artistId: Long): Flow<List<Album>>
    
    @Query("SELECT * FROM albums WHERE artist_id = :artistId ORDER BY release_year DESC")
    suspend fun getAlbumsForArtistSync(artistId: Long): List<Album>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: Album): Long

    @Update
    suspend fun update(album: Album)

    @Delete
    suspend fun delete(album: Album)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("""
        SELECT a.id, a.title, a.artist_id as artistId, ar.name as artistName,
               a.release_year as releaseYear,
               COALESCE(
                   NULLIF(a.artwork_url, ''),
                   (SELECT COALESCE(NULLIF(t.album_art_url, ''), NULLIF(em.album_art_url, ''))
                    FROM tracks t
                    LEFT JOIN enriched_metadata em ON t.id = em.track_id
                    WHERE t.album = a.title AND (t.artist = ar.name OR t.primary_artist_id = a.artist_id)
                      AND (t.album_art_url IS NOT NULL OR em.album_art_url IS NOT NULL)
                    LIMIT 1)
               ) as artworkUrl,
               a.release_type as releaseType
        FROM albums a
        INNER JOIN artists ar ON a.artist_id = ar.id
        WHERE (INSTR(LOWER(a.title), LOWER(:query)) > 0 OR INSTR(LOWER(ar.name), LOWER(:query)) > 0)
        AND (:excludeAlbumId IS NULL OR a.id != :excludeAlbumId)
        ORDER BY a.title ASC
        LIMIT :limit
    """)
    suspend fun searchAlbumsWithArtist(
        query: String,
        excludeAlbumId: Long? = null,
        limit: Int = 30
    ): List<AlbumSearchResult>

    @Query("""
        SELECT a.id, a.title, a.artist_id as artistId, ar.name as artistName,
               a.release_year as releaseYear,
               COALESCE(
                   NULLIF(a.artwork_url, ''),
                   (SELECT COALESCE(NULLIF(t.album_art_url, ''), NULLIF(em.album_art_url, ''))
                    FROM tracks t
                    LEFT JOIN enriched_metadata em ON t.id = em.track_id
                    WHERE t.album = a.title AND (t.artist = ar.name OR t.primary_artist_id = a.artist_id)
                      AND (t.album_art_url IS NOT NULL OR em.album_art_url IS NOT NULL)
                    LIMIT 1)
               ) as artworkUrl,
               a.release_type as releaseType
        FROM albums a
        INNER JOIN artists ar ON a.artist_id = ar.id
        WHERE a.artist_id = :artistId
        AND (:excludeAlbumId IS NULL OR a.id != :excludeAlbumId)
        ORDER BY a.release_year DESC, a.title ASC
    """)
    suspend fun getAlbumsByArtistWithArtist(
        artistId: Long,
        excludeAlbumId: Long? = null
    ): List<AlbumSearchResult>
    
    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumCount(): Int
    
    /**
     * Get count of actual albums (excluding singles).
     * Includes: Album, EP, Compilation, and items with unknown release_type.
     */
    @Query("SELECT COUNT(*) FROM albums WHERE release_type IS NULL OR LOWER(release_type) != 'single'")
    suspend fun getAlbumCountExcludingSingles(): Int
    
    /**
     * Get albums for an artist excluding singles.
     */
    @Query("SELECT * FROM albums WHERE artist_id = :artistId AND (release_type IS NULL OR LOWER(release_type) != 'single') ORDER BY release_year DESC")
    fun albumsForArtistExcludingSingles(artistId: Long): Flow<List<Album>>
    
    /**
     * Get all albums for export.
     */
    @Query("SELECT * FROM albums")
    suspend fun getAllSync(): List<Album>

    /**
     * Re-parent all albums from one artist to another.
     * Used during artist merge so the source artist's CASCADE delete
     * does not destroy album rows.
     */
    @Query("UPDATE albums SET artist_id = :targetArtistId WHERE artist_id = :sourceArtistId")
    suspend fun reassignArtist(sourceArtistId: Long, targetArtistId: Long): Int

    @Query("SELECT artwork_url FROM albums WHERE artwork_url LIKE 'file://%'")
    suspend fun getLocalImageUrls(): List<String>
}

data class AlbumSearchResult(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "artistId") val artistId: Long,
    @ColumnInfo(name = "artistName") val artistName: String,
    @ColumnInfo(name = "releaseYear") val releaseYear: Int?,
    @ColumnInfo(name = "artworkUrl") val artworkUrl: String?,
    @ColumnInfo(name = "releaseType") val releaseType: String? = null
)
