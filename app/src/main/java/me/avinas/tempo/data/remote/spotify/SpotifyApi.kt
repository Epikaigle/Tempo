package me.avinas.tempo.data.remote.spotify

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Spotify Web API, base URL https://api.spotify.com/v1/.
 *
 * OAuth 2.0 authorization code + PKCE; every endpoint needs a Bearer token. Rate limiting is
 * dynamic, and a 429 carries a `Retry-After` header.
 *
 * Docs: https://developer.spotify.com/documentation/web-api
 */
interface SpotifyApi {

    companion object {
        const val BASE_URL = "https://api.spotify.com/v1/"
        const val AUTH_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        
        // Scopes required for Tempo features:
        // - user-read-private: Market-specific searches and user product info
        // - user-read-recently-played: Import recent listening history for onboarding
        // - user-top-read: Access user's top tracks/artists (4 weeks, 6 months, all time)
        // - user-library-read: Access user's saved/liked tracks with exact timestamps
        // - playlist-read-private: Access user's playlists (including "Your Top Songs" yearly wraps)
        const val SCOPES = "user-read-private user-read-recently-played user-top-read user-library-read playlist-read-private"
        
        const val REDIRECT_URI = "tempo://spotify-callback"
    }

    // Track Search

    /**
     * Search tracks. `query` supports field filters such as `artist:`, `track:` and `album:`;
     * `limit` caps at 50 and `market` is an ISO 3166-1 alpha-2 country code.
     */
    @GET("search")
    suspend fun searchTracks(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 5,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifySearchResponse>

    // Artist Search

    /**
     * Search artists. More reliable than track search for artist images, because it searches
     * artists directly instead of inferring them from tracks.
     *
     * `limit` caps at 50 and `market` is an ISO 3166-1 alpha-2 country code.
     */
    @GET("search")
    suspend fun searchArtists(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 5,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifyArtistSearchResponse>

    // Audio features endpoints removed: Spotify deprecated them for third-party apps in Nov 2024.

    // Track Details

    @GET("tracks/{id}")
    suspend fun getTrack(
        @Header("Authorization") authorization: String,
        @Path("id") trackId: String,
        @Query("market") market: String? = null
    ): Response<SpotifyTrack>

    /** Batch version of [getTrack]; `ids` is comma-separated, max 50. */
    @GET("tracks")
    suspend fun getMultipleTracks(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String,
        @Query("market") market: String? = null
    ): Response<SpotifyMultipleTracksResponse>

    // User Profile (for validating connection)

    /** Current user's profile; used to verify that authentication works. */
    @GET("me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String
    ): Response<SpotifyUser>

    // Artist Details

    @GET("artists/{id}")
    suspend fun getArtist(
        @Header("Authorization") authorization: String,
        @Path("id") artistId: String
    ): Response<SpotifyFullArtist>
    
    /** Batch version of [getArtist]; `ids` is comma-separated, max 50. */
    @GET("artists")
    suspend fun getMultipleArtists(
        @Header("Authorization") authorization: String,
        @Query("ids") ids: String
    ): Response<SpotifyMultipleArtistsResponse>
    
    /**
     * Up to 10 top tracks for an artist, used to approximate audio features from the user's
     * history now that Spotify's audio-features API is gone. `market` is required by the API.
     */
    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Header("Authorization") authorization: String,
        @Path("id") artistId: String,
        @Query("market") market: String = "US"
    ): Response<SpotifyArtistTopTracksResponse>

    // Recently Played (Import Feature)

    /**
     * Recently played, up to 50 tracks covering roughly the last 24 hours. Feeds the onboarding
     * import that bootstraps an empty Tempo library.
     *
     * `after` and `before` are Unix timestamps in milliseconds and are mutually exclusive: use
     * `after` for incremental polling, `before` to walk backwards. `limit` caps at 50.
     */
    @GET("me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("after") after: Long? = null,
        @Query("before") before: Long? = null
    ): Response<SpotifyRecentlyPlayedResponse>

    // User's Top Items (Stats.fm style!)

    /**
     * Top tracks by Spotify's affinity calculation rather than recency. `timeRange` is
     * `short_term` (~4 weeks), `medium_term` (~6 months) or `long_term` (several years, "all
     * time"). `offset` is 0-based and `limit` caps at 50.
     */
    @GET("me/top/tracks")
    suspend fun getUserTopTracks(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserTopTracksResponse>

    /**
     * Top artists, same `timeRange` values as [getUserTopTracks]. `offset` is 0-based and
     * `limit` caps at 50.
     */
    @GET("me/top/artists")
    suspend fun getUserTopArtists(
        @Header("Authorization") authorization: String,
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserTopArtistsResponse>

    // User's Saved Tracks (Time Machine - Exact Timestamps!)

    /**
     * Saved tracks with exact `added_at` timestamps, the Time Machine source for history
     * reconstruction: a like on 12 June 2023 means the user was listening to that track around
     * then, so events can be generated around those dates.
     *
     * `offset` pages up to roughly 1000 tracks; `limit` caps at 50.
     */
    @GET("me/tracks")
    suspend fun getUserSavedTracks(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null
    ): Response<SpotifySavedTracksResponse>

    // User's Playlists (Artifact Hunter)

    /**
     * Playlists, scanned for history artifacts: "Your Top Songs 2024"/"2023", "On Repeat",
     * "Repeat Rewind", "Daylist" and other seasonal wraps. `limit` caps at 50.
     */
    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SpotifyUserPlaylistsResponse>

    /**
     * Tracks from one playlist, used to pull the "Your Top Songs 20XX" wraps. `limit` caps at 100.
     */
    @GET("playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") authorization: String,
        @Path("playlist_id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("market") market: String? = null,
        @Query("fields") fields: String = "items(added_at,track(id,name,artists,album,duration_ms,popularity,explicit,external_ids,preview_url)),total,next"
    ): Response<SpotifyPlaylistTracksResponse>
}
