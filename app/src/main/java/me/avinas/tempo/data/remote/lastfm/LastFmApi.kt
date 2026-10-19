package me.avinas.tempo.data.remote.lastfm

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Last.fm API (https://www.last.fm/api), base URL https://ws.audioscrobbler.com/2.0/.
 *
 * Free for non-commercial use with reasonable rate limits. Every call carries the API key as
 * `api_key`, asks for JSON, and passes `autocorrect=1` so Last.fm fixes misspelled names.
 */
interface LastFmApi {

    companion object {
        const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

        // Roughly 5 requests/second.
        const val RATE_LIMIT_MS = 200L
    }

    /** `track.getInfo`: tags and genres for one track. */
    @GET(".")
    suspend fun getTrackInfo(
        @Query("method") method: String = "track.getInfo",
        @Query("track") track: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmScrobbleResponse>

    /** `artist.getInfo`: tags and similar artists. */
    @GET(".")
    suspend fun getArtistInfo(
        @Query("method") method: String = "artist.getInfo",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmArtistResponse>

    /** `track.getTopTags`. */
    @GET(".")
    suspend fun getTrackTopTags(
        @Query("method") method: String = "track.getTopTags",
        @Query("track") track: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmTopTagsResponse>

    /** `artist.getTopTags`. */
    @GET(".")
    suspend fun getArtistTopTags(
        @Query("method") method: String = "artist.getTopTags",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("autocorrect") autocorrect: Int = 1
    ): Response<LastFmTopTagsResponse>

    // History import. Public profiles only, so an API key is enough; no user auth.

    /** `user.getInfo`: total scrobble count and registration date. */
    @GET(".")
    suspend fun getUserInfo(
        @Query("method") method: String = "user.getInfo",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json"
    ): Response<LastFmUserInfoResponse>

    /**
     * `user.getRecentTracks`, paginated. Despite the name this returns the complete lifetime
     * scrobble history if you keep paging.
     *
     * `limit` caps at 200, `page` is 1-based, and `from`/`to` are Unix timestamps.
     */
    @GET(".")
    suspend fun getRecentTracks(
        @Query("method") method: String = "user.getRecentTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 200,
        @Query("page") page: Int = 1,
        @Query("extended") extended: Int = 1,
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null
    ): Response<LastFmRecentTracksResponse>

    /**
     * `user.getTopTracks`. `period` is one of `overall`, `7day`, `1month`, `3month`, `6month`,
     * `12month`; `limit` caps at 1000.
     */
    @GET(".")
    suspend fun getTopTracks(
        @Query("method") method: String = "user.getTopTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmTopTracksResponse>

    /** `user.getLovedTracks`. `limit` caps at 1000, `page` is 1-based. */
    @GET(".")
    suspend fun getLovedTracks(
        @Query("method") method: String = "user.getLovedTracks",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmLovedTracksResponse>

    /**
     * `user.getTopArtists`. Same `period` values as [getTopTracks]; `limit` caps at 1000.
     */
    @GET(".")
    suspend fun getTopArtists(
        @Query("method") method: String = "user.getTopArtists",
        @Query("user") user: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("period") period: String = "overall",
        @Query("limit") limit: Int = 1000,
        @Query("page") page: Int = 1
    ): Response<LastFmTopArtistsResponse>
}
