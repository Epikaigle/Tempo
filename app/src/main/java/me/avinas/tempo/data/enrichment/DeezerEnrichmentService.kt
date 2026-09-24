package me.avinas.tempo.data.enrichment

import android.util.Log
import me.avinas.tempo.data.remote.deezer.DeezerApi
import me.avinas.tempo.data.remote.deezer.DeezerTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Fetches 30-second audio previews from Deezer's search API
 * as fallback when Spotify and iTunes have no sample.
 */
@Singleton
class DeezerEnrichmentService @Inject constructor(
    private val deezerApi: DeezerApi
) {
    companion object {
        private const val TAG = "DeezerEnrichment"
        private const val RATE_LIMIT_DELAY_MS = 500L // Be generous with rate limits on public API
    }
    
    sealed class DeezerResult {
        data class Success(
            val previewUrl: String,
            val deezerId: Long,
            val link: String,
            val coverMd5: String?,
            // Album cover art URLs (Deezer provides multiple sizes)
            val albumArtUrl: String? = null,      // Best quality (XL or Big)
            val albumArtUrlSmall: String? = null, // Small
            val albumArtUrlLarge: String? = null, // XL
            // Artist image URL
            val artistImageUrl: String? = null,   // Best quality (XL or Big)
            // Album title for fallback
            val albumTitle: String? = null
        ) : DeezerResult()
        
        object NotFound : DeezerResult()
        data class Error(val message: String) : DeezerResult()
    }
    
    data class CoverArtResult(
        val albumArtUrl: String,
        val albumArtUrlSmall: String? = null,
        val albumArtUrlLarge: String? = null,
        val albumTitle: String? = null
    )

    /**
     * Search Deezer specifically for album artwork. This lookup is independent of
     * preview availability, so a valid cover can still be offered when Deezer does
     * not expose a 30-second sample for the track.
     */
    suspend fun searchAlbumArt(
        artist: String,
        track: String,
        album: String? = null
    ): CoverArtResult? {
        return try {
            val cleanArtist = me.avinas.tempo.utils.ArtistParser.getPrimaryArtist(artist)
            val cleanTrack = me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(track)
            val structuredQuery = "artist:\"$cleanArtist\" track:\"$cleanTrack\""
            val structured = deezerApi.searchTracks(structuredQuery)
            var results = if (structured.isSuccessful) structured.body()?.data.orEmpty() else emptyList()

            if (results.isEmpty()) {
                delay(RATE_LIMIT_DELAY_MS)
                val loose = deezerApi.searchTracks("$cleanTrack $cleanArtist")
                results = if (loose.isSuccessful) loose.body()?.data.orEmpty() else emptyList()
            }

            val bestMatch = results.firstOrNull { result ->
                val hasArt = !result.album.coverXl.isNullOrBlank() ||
                    !result.album.coverBig.isNullOrBlank() ||
                    !result.album.coverMedium.isNullOrBlank()
                hasArt && me.avinas.tempo.utils.ArtistParser.hasAnyMatchingArtist(
                    result.artist.name,
                    artist
                )
            } ?: results.firstOrNull { result ->
                !result.album.coverXl.isNullOrBlank() ||
                    !result.album.coverBig.isNullOrBlank() ||
                    !result.album.coverMedium.isNullOrBlank()
            } ?: return null

            val bestUrl = bestMatch.album.coverXl
                ?: bestMatch.album.coverBig
                ?: bestMatch.album.coverMedium
                ?: return null

            CoverArtResult(
                albumArtUrl = bestUrl,
                albumArtUrlSmall = bestMatch.album.coverSmall,
                albumArtUrlLarge = bestMatch.album.coverXl ?: bestMatch.album.coverBig,
                albumTitle = bestMatch.album.title
            )
        } catch (e: Exception) {
            Log.e(TAG, "Deezer cover-art search error", e)
            null
        }
    }

    /**
     * Search for a track and get its preview URL.
     */
    suspend fun getAudioPreview(
        artist: String,
        track: String,
        album: String? = null
    ): DeezerResult {
        return try {
            // Deezer advanced search syntax: artist:"..." track:"..."
            // Strip featured artists and clean title for Deezer search syntax
            val cleanArtist = me.avinas.tempo.utils.ArtistParser.getPrimaryArtist(artist)
            val cleanTrack = me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(track)
            
            // Construct query: artist:'X' track:'Y'
            // We use standard quotes for Deezer syntax
            val query = "artist:\"$cleanArtist\" track:\"$cleanTrack\""
            
            Log.d(TAG, "Searching Deezer: $query")
            
            val response = deezerApi.searchTracks(query)
            
            if (!response.isSuccessful) {
                return DeezerResult.Error("API Error: ${response.code()}")
            }
            
            val results = response.body()?.data
            if (results.isNullOrEmpty()) {
                // Try relaxed search (just text, no filters) if structured search failed
                Log.d(TAG, "Structured search failed, trying loose search for '$cleanTrack $cleanArtist'")
                val looseQuery = "$cleanTrack $cleanArtist"
                return searchLoose(looseQuery)
            }
            
            // Find best match (prefer one with preview)
            val bestMatch = results.firstOrNull { 
                !it.preview.isNullOrBlank() 
            } ?: results.first()
            
            if (bestMatch.preview.isNullOrBlank()) {
                return DeezerResult.Error("Track found but no preview available")
            }
            
            Log.i(TAG, "Found Deezer match: '${bestMatch.title}' by '${bestMatch.artist.name}' with cover art and artist image")
            
            DeezerResult.Success(
                previewUrl = bestMatch.preview,
                deezerId = bestMatch.id,
                link = bestMatch.link,
                coverMd5 = bestMatch.album.md5Image,
                // Include album cover art URLs
                albumArtUrl = bestMatch.album.coverBig ?: bestMatch.album.coverMedium,
                albumArtUrlSmall = bestMatch.album.coverSmall,
                albumArtUrlLarge = bestMatch.album.coverXl ?: bestMatch.album.coverBig,
                // Include artist image URL
                artistImageUrl = bestMatch.artist.pictureXl ?: bestMatch.artist.pictureBig ?: bestMatch.artist.pictureMedium,
                // Album title for fallback
                albumTitle = bestMatch.album.title
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Deezer search error", e)
            DeezerResult.Error(e.message ?: "Unknown error")
        }
    }
    
    private suspend fun searchLoose(query: String): DeezerResult {
        try {
            delay(RATE_LIMIT_DELAY_MS)
            val response = deezerApi.searchTracks(query)
            
            if (!response.isSuccessful) return DeezerResult.NotFound
            
            val results = response.body()?.data
            if (results.isNullOrEmpty()) return DeezerResult.NotFound
            
            val bestMatch = results.firstOrNull { !it.preview.isNullOrBlank() } 
                ?: return DeezerResult.NotFound
                
            Log.i(TAG, "Found Deezer match (loose search): '${bestMatch.title}' with cover art and artist image")
            
            return DeezerResult.Success(
                previewUrl = bestMatch.preview!!,
                deezerId = bestMatch.id,
                link = bestMatch.link,
                coverMd5 = bestMatch.album.md5Image,
                // Include album cover art URLs
                albumArtUrl = bestMatch.album.coverBig ?: bestMatch.album.coverMedium,
                albumArtUrlSmall = bestMatch.album.coverSmall,
                albumArtUrlLarge = bestMatch.album.coverXl ?: bestMatch.album.coverBig,
                // Include artist image URL
                artistImageUrl = bestMatch.artist.pictureXl ?: bestMatch.artist.pictureBig ?: bestMatch.artist.pictureMedium,
                // Album title for fallback
                albumTitle = bestMatch.album.title
            )
        } catch (e: Exception) {
            return DeezerResult.Error(e.message ?: "Unknown error")
        }
    }
}
