package me.avinas.tempo.data.remote.reccobeats

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * ReccoBeats API response models.
 * 
 * Documentation: https://reccobeats.com/docs/documentation/introduction
 */

// Track Search

/**
 * Response from /v1/track/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsSearchResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList(),
    val total: Int = 0
)

/**
 * Track object from search results
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsTrack(
    val id: String, // ReccoBeats UUID
    val name: String,
    val artists: List<ReccoBeatsArtist> = emptyList(),
    val album: ReccoBeatsAlbum? = null,
    @field:Json(name = "duration_ms") val durationMs: Long? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null,
    val isrc: String? = null,
    @field:Json(name = "preview_url") val previewUrl: String? = null,
    val popularity: Int? = null
)

/**
 * Artist reference in track
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsArtist(
    val id: String,
    val name: String,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
)

/**
 * Album reference in track
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAlbum(
    val id: String,
    val name: String,
    @field:Json(name = "release_date") val releaseDate: String? = null,
    val images: List<ReccoBeatsImage>? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
) {
    val smallImageUrl: String?
        get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
    
    val mediumImageUrl: String?
        get() = images?.sortedBy { it.width ?: 0 }?.getOrNull(images.size / 2)?.url
    
    val largeImageUrl: String?
        get() = images?.maxByOrNull { it.width ?: 0 }?.url
}

/**
 * Image object
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

// Audio Features

/**
 * Audio features from /v1/track/:id/audio-features.
 * 
 * Values match Spotify's format: most run 0.0-1.0, `loudness` is dB (roughly -60 to 0), `tempo`
 * is BPM, `key` is 0=C through 11=B with -1 for unknown, `mode` is 0=minor or 1=major, and
 * `instrumentalness` above 0.5 means instrumental.
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAudioFeatures(
    val acousticness: Float = 0f,
    val danceability: Float = 0f,
    val energy: Float = 0f,
    val instrumentalness: Float = 0f,
    val liveness: Float = 0f,
    val speechiness: Float = 0f,
    val valence: Float = 0f,
    val tempo: Float = 0f,
    val loudness: Float = 0f,
    val key: Int = -1,
    val mode: Int = 0,
    @field:Json(name = "time_signature") val timeSignature: Int = 4,
    @field:Json(name = "duration_ms") val durationMs: Long? = null
) {
    fun deriveMood(): String {
        return when {
            valence >= 0.7 && energy >= 0.7 -> "Energetic"
            valence >= 0.7 && energy >= 0.5 -> "Happy"
            valence >= 0.7 && energy < 0.5 -> "Peaceful"
            valence >= 0.5 && energy >= 0.7 -> "Upbeat"
            valence >= 0.5 && energy >= 0.5 -> "Neutral"
            valence >= 0.5 && energy < 0.5 -> "Calm"
            valence >= 0.3 && energy >= 0.7 -> "Intense"
            valence >= 0.3 && energy >= 0.5 -> "Moody"
            valence >= 0.3 && energy < 0.5 -> "Contemplative"
            energy >= 0.7 -> "Aggressive"
            energy >= 0.5 -> "Dark"
            else -> "Melancholic"
        }
    }
    
    fun deriveEnergyLevel(): String {
        return when {
            energy >= 0.8 -> "Very High"
            energy >= 0.6 -> "High"
            energy >= 0.4 -> "Medium"
            energy >= 0.2 -> "Low"
            else -> "Very Low"
        }
    }
    
    /**
     * Rough hints only; real genres come from the metadata APIs.
     */
    fun deriveGenreHints(): List<String> {
        val hints = mutableListOf<String>()
        
        if (speechiness > 0.33) {
            hints.add("Hip-Hop")
            if (speechiness > 0.66) hints.add("Spoken Word")
        }
        
        if (acousticness > 0.7) {
            hints.add("Acoustic")
            if (energy < 0.4) hints.add("Folk")
        }
        
        if (instrumentalness > 0.5) {
            hints.add("Instrumental")
            if (energy > 0.7) hints.add("Electronic")
        }
        
        if (danceability > 0.7 && energy > 0.7) {
            hints.add("Dance")
            if (tempo > 120) hints.add("EDM")
        }
        
        if (energy > 0.8 && acousticness < 0.3 && speechiness < 0.3) {
            hints.add("Rock")
            if (loudness > -5) hints.add("Metal")
        }
        
        if (energy < 0.3 && acousticness > 0.5) {
            hints.add("Ballad")
            if (instrumentalness > 0.5) hints.add("Ambient")
        }
        
        return hints.distinct()
    }
    
    fun getKeyName(): String? {
        if (key < 0 || key > 11) return null
        val keyNames = listOf("C", "C♯/D♭", "D", "D♯/E♭", "E", "F", "F♯/G♭", "G", "G♯/A♭", "A", "A♯/B♭", "B")
        val modeName = if (mode == 1) "Major" else "Minor"
        return "${keyNames[key]} $modeName"
    }
}

// Artist Search

/**
 * Response from /v1/artist/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsArtistSearchResponse(
    val artists: List<ReccoBeatsFullArtist> = emptyList(),
    val total: Int = 0
)

/**
 * Full artist object from artist search
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsFullArtist(
    val id: String,
    val name: String,
    @field:Json(name = "spotify_id") val spotifyId: String? = null,
    val genres: List<String>? = null,
    val images: List<ReccoBeatsImage>? = null,
    val popularity: Int? = null
) {
    val smallImageUrl: String?
        get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url
    
    val largeImageUrl: String?
        get() = images?.maxByOrNull { it.width ?: 0 }?.url
}

// Album Search

/**
 * Response from /v1/album/search endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsAlbumSearchResponse(
    val albums: List<ReccoBeatsFullAlbum> = emptyList(),
    val total: Int = 0
)

/**
 * Full album object from album search
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsFullAlbum(
    val id: String,
    val name: String,
    val artists: List<ReccoBeatsArtist>? = null,
    @field:Json(name = "release_date") val releaseDate: String? = null,
    @field:Json(name = "total_tracks") val totalTracks: Int? = null,
    val images: List<ReccoBeatsImage>? = null,
    @field:Json(name = "spotify_id") val spotifyId: String? = null
)

// Multiple Tracks

/**
 * Response from /v1/track?ids=... endpoint (multiple tracks)
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsMultipleTracksResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList()
)

// Recommendations

/**
 * Response from /v1/track/recommendation endpoint
 */
@JsonClass(generateAdapter = true)
data class ReccoBeatsRecommendationResponse(
    val tracks: List<ReccoBeatsTrack> = emptyList()
)

// Audio Analysis (File Upload)

/**
 * /v1/analysis/audio-features (file upload) returns the same shape as [ReccoBeatsAudioFeatures].
 */
typealias ReccoBeatsAnalysisResponse = ReccoBeatsAudioFeatures
