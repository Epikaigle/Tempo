package me.avinas.tempo.data.remote.musicbrainz

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MusicBrainz API, base URL https://musicbrainz.org/ws/2/.
 *
 * Max 1 request per second unauthenticated, and every request needs a User-Agent identifying
 * the app. `inc` selects related data to include; responses are XML unless `fmt=json` is passed.
 *
 * Docs: https://musicbrainz.org/doc/MusicBrainz_API
 */
interface MusicBrainzApi {

    companion object {
        const val BASE_URL = "https://musicbrainz.org/ws/2/"
        const val COVER_ART_BASE_URL = "https://coverartarchive.org/"
        
        // MusicBrainz requires a User-Agent with contact info
        // See: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
        val USER_AGENT: String = me.avinas.tempo.BuildConfig.MUSICBRAINZ_USER_AGENT
        
        // Rate limit: 1 request per second
        val RATE_LIMIT_MS: Long = me.avinas.tempo.BuildConfig.MUSICBRAINZ_RATE_LIMIT_MS
    }

    // Recording (Track) Search & Lookup

    /**
     * Search recordings. `query` uses Lucene syntax, e.g.
     * `recording:"song title" AND artist:"artist name"` or the same with `artistname`.
     *
     * `limit` caps at 100.
     */
    @GET("recording")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("fmt") format: String = "json"
    ): Response<RecordingSearchResponse>

    @GET("recording/{mbid}")
    suspend fun lookupRecording(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "artists+releases+tags+genres+isrcs+url-rels+release-groups",
        @Query("fmt") format: String = "json"
    ): Response<RecordingLookupResponse>

    // Artist Search & Lookup

    @GET("artist/{mbid}")
    suspend fun lookupArtist(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "tags+genres+aliases+url-rels+release-groups",
        @Query("fmt") format: String = "json"
    ): Response<ArtistLookupResponse>

    // Release (Album) Search & Lookup

    @GET("release/{mbid}")
    suspend fun lookupRelease(
        @Path("mbid") mbid: String,
        @Query("inc") inc: String = "artists+labels+recordings+release-groups+tags+genres+url-rels",
        @Query("fmt") format: String = "json"
    ): Response<ReleaseLookupResponse>
}

/**
 * Cover Art Archive, a separate API from MusicBrainz that serves album artwork.
 * No rate limit, but be respectful.
 *
 * Base URL: https://coverartarchive.org/
 */
interface CoverArtArchiveApi {

    @GET("release/{mbid}")
    suspend fun getReleaseCoverArt(
        @Path("mbid") mbid: String
    ): Response<CoverArtResponse>
    
    @GET("release-group/{mbid}")
    suspend fun getReleaseGroupCoverArt(
        @Path("mbid") mbid: String
    ): Response<CoverArtResponse>

    /**
     * Redirects to the image. Direct image URLs add a size suffix to the same path:
     * https://coverartarchive.org/release/{mbid}/front-250
     * https://coverartarchive.org/release/{mbid}/front-500
     * https://coverartarchive.org/release/{mbid}/front-1200
     */
    @GET("release/{mbid}/front")
    suspend fun getReleaseFrontCover(
        @Path("mbid") mbid: String
    ): Response<Unit>

    companion object {
        const val BASE_URL = "https://coverartarchive.org/"
        
        fun getFrontCoverUrl(releaseMbid: String, size: Int = 500): String {
            return "${BASE_URL}release/$releaseMbid/front-$size"
        }
        
        fun getReleaseGroupFrontCoverUrl(releaseGroupMbid: String, size: Int = 500): String {
            return "${BASE_URL}release-group/$releaseGroupMbid/front-$size"
        }
    }
}
