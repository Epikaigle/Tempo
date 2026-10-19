package me.avinas.tempo.data.remote.reccobeats

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * ReccoBeats API, base URL https://api.reccobeats.com/. Free and unauthenticated, and it covers
 * the audio features Spotify deprecated.
 *
 * Rate limit: HTTP 429 once exceeded, so retry with backoff.
 *
 * IDs come in two shapes: ReccoBeats UUIDs ("878dadea-33c5-4c08-bdb9-e2b117475a99") and Spotify
 * base-62 IDs ("06HL4z0CvFAxyc27GXpf02"). `/audio-features` accepts both; `/track/{id}` details
 * only take the UUID.
 *
 * Docs: https://reccobeats.com/docs/documentation/introduction
 */
interface ReccoBeatsApi {

    companion object {
        const val BASE_URL = "https://api.reccobeats.com/"
    }

    // Track Search

    @GET("v1/track/search")
    suspend fun searchTracks(
        @Query("q") query: String
    ): Response<ReccoBeatsSearchResponse>

    // Track Details

    /**
     * Track details. This one takes only a ReccoBeats UUID, not a Spotify ID, so look the ID up
     * with [searchTracks] first.
     */
    @GET("v1/track/{id}")
    suspend fun getTrack(
        @Path("id") id: String
    ): Response<ReccoBeatsTrack>

    // Audio Features

    /**
     * The endpoint mood/energy analysis is built on. Pass a Spotify ID straight from the track if
     * you have one, no ReccoBeats search needed.
     */
    @GET("v1/track/{id}/audio-features")
    suspend fun getAudioFeatures(
        @Path("id") id: String
    ): Response<ReccoBeatsAudioFeatures>

    // Multiple Tracks

    /** Batch lookup; `ids` accepts either ID format. */
    @GET("v1/track")
    suspend fun getMultipleTracks(
        @Query("ids") ids: List<String>
    ): Response<ReccoBeatsMultipleTracksResponse>

    // Recommendations

    /**
     * Recommendations seeded from `seeds`, a comma-separated list of track IDs (either format).
     */
    @GET("v1/track/recommendation")
    suspend fun getRecommendations(
        @Query("seeds") seeds: String,
        @Query("size") size: Int = 10
    ): Response<ReccoBeatsRecommendationResponse>

    // Artist Search

    @GET("v1/artist/search")
    suspend fun searchArtists(
        @Query("q") query: String
    ): Response<ReccoBeatsArtistSearchResponse>

    // Album Search

    @GET("v1/album/search")
    suspend fun searchAlbums(
        @Query("q") query: String
    ): Response<ReccoBeatsAlbumSearchResponse>

    // Audio Analysis (File Upload)

    /**
     * Last resort when the track isn't in ReccoBeats and there's no Spotify ID, typically fed a
     * 30s Spotify preview.
     *
     * Max 5MB and 30 seconds, MP3/WAV and similar.
     */
    @Multipart
    @POST("v1/analysis/audio-features")
    suspend fun analyzeAudioFile(
        @Part audioFile: MultipartBody.Part
    ): Response<ReccoBeatsAnalysisResponse>
}
