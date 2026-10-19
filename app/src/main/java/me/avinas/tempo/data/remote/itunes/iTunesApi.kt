package me.avinas.tempo.data.remote.itunes

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search API. Free with no API key, but only around 20 requests per minute.
 *
 * Docs: https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
 * Base URL: https://itunes.apple.com/
 */
interface iTunesApi {

    companion object {
        const val BASE_URL = "https://itunes.apple.com/"
        
        const val RATE_LIMIT_MS = 3000L
    }

    /**
     * Search the iTunes Store. `term` works best as artist + album, `limit` caps at 200, and
     * `country` is a two-letter store code.
     */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("entity") entity: String = "album",
        @Query("limit") limit: Int = 5,
        @Query("country") country: String = "in", // India store for regional artist coverage
        @Query("media") media: String = "music"
    ): Response<iTunesSearchResponse>
    
    @GET("lookup")
    suspend fun lookupArtist(
        @Query("id") artistId: Long,
        @Query("entity") entity: String? = null
    ): Response<iTunesSearchResponse>
    
    /**
     * Lookup that also returns song/album results, which carry the `artworkUrl` fields.
     */
    @GET("lookup")
    suspend fun lookupArtistWithMedia(
        @Query("id") artistId: Long,
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 3
    ): Response<iTunesSearchResponse>
}
