package me.avinas.tempo.data.spotify

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Spotify data export JSON models.
 *
 * Spotify allows users to request their data export from account settings.
 * The export includes streaming history in two formats:
 *
 * 1. StreamingHistory*.json (older format, array of objects)
 * 2. endsong_*.json (newer format, one JSON object per line / ndjson)
 *
 * These models support both formats.
 */

@JsonClass(generateAdapter = true)
data class SpotifyStreamingHistoryEntry(
    @Json(name = "endTime") val endTime: String,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "trackName") val trackName: String?,
    @Json(name = "albumName") val albumName: String?,
    @Json(name = "trackUri") val trackUri: String?,
    @Json(name = "msPlayed") val msPlayed: Long?
) {
    val spotifyTrackId: String?
        get() = trackUri?.let {
            if (it.startsWith("spotify:track:")) it.removePrefix("spotify:track:") else null
        }

    val endTimeMillis: Long
        get() = parseSpotifyTimestamp(endTime)
}

@JsonClass(generateAdapter = true)
data class SpotifyEndsongEntry(
    @Json(name = "ts") val timestamp: String?,
    @Json(name = "platform") val platform: String?,
    @Json(name = "ms_played") val msPlayed: Long?,
    @Json(name = "conn_country") val connCountry: String?,
    @Json(name = "master_metadata_track_name") val trackName: String?,
    @Json(name = "master_metadata_album_artist_name") val artistName: String?,
    @Json(name = "master_metadata_album_album_name") val albumName: String?,
    @Json(name = "spotify_track_uri") val trackUri: String?,
    @Json(name = "episode_name") val episodeName: String?,
    @Json(name = "episode_show_name") val episodeShowName: String?,
    @Json(name = "spotify_episode_uri") val episodeUri: String?,
    @Json(name = "reason_start") val reasonStart: String?,
    @Json(name = "reason_end") val reasonEnd: String?,
    @Json(name = "shuffle") val shuffle: Boolean?,
    @Json(name = "skipped") val skipped: Boolean?,
    @Json(name = "offline") val offline: Boolean?,
    @Json(name = "incognito_mode") val incognitoMode: Boolean?
) {
    val spotifyTrackId: String?
        get() = trackUri?.let {
            if (it.startsWith("spotify:track:")) it.removePrefix("spotify:track:") else null
        }

    val timestampMillis: Long
        get() = parseSpotifyTimestamp(timestamp)

    val isPodcast: Boolean
        get() = episodeUri != null || episodeName != null

    val wasCompleted: Boolean
        get() = reasonEnd == "trackdone" || (msPlayed ?: 0) > 30000
}

@JsonClass(generateAdapter = true)
data class SpotifyPlaylistEntry(
    @Json(name = "name") val name: String?,
    @Json(name = "lastModifiedDate") val lastModifiedDate: String?,
    @Json(name = "items") val items: List<SpotifyPlaylistItem>?,
    @Json(name = "description") val description: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyPlaylistItem(
    @Json(name = "track") val track: SpotifyPlaylistTrack?,
    @Json(name = "episode") val episode: SpotifyPlaylistEpisode?,
    @Json(name = "addedDate") val addedDate: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyPlaylistTrack(
    @Json(name = "trackName") val trackName: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "albumName") val albumName: String?,
    @Json(name = "trackUri") val trackUri: String?
) {
    val spotifyTrackId: String?
        get() = trackUri?.let {
            if (it.startsWith("spotify:track:")) it.removePrefix("spotify:track:") else null
        }
}

@JsonClass(generateAdapter = true)
data class SpotifyPlaylistEpisode(
    @Json(name = "episodeName") val episodeName: String?,
    @Json(name = "episodeShowName") val episodeShowName: String?,
    @Json(name = "episodeUri") val episodeUri: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyYourLibrary(
    @Json(name = "tracks") val tracks: List<SpotifyLibraryTrack>?,
    @Json(name = "albums") val albums: List<SpotifyLibraryAlbum>?,
    @Json(name = "artists") val artists: List<SpotifyLibraryArtist>?,
    @Json(name = "episodes") val episodes: List<SpotifyLibraryEpisode>?,
    @Json(name = "shows") val shows: List<SpotifyLibraryShow>?
)

@JsonClass(generateAdapter = true)
data class SpotifyLibraryTrack(
    @Json(name = "trackName") val trackName: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "albumName") val albumName: String?,
    @Json(name = "trackUri") val trackUri: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyLibraryAlbum(
    @Json(name = "albumName") val albumName: String?,
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "albumUri") val albumUri: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyLibraryArtist(
    @Json(name = "artistName") val artistName: String?,
    @Json(name = "artistUri") val artistUri: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyLibraryEpisode(
    @Json(name = "episodeName") val episodeName: String?,
    @Json(name = "episodeShowName") val episodeShowName: String?,
    @Json(name = "episodeUri") val episodeUri: String?
)

@JsonClass(generateAdapter = true)
data class SpotifyLibraryShow(
    @Json(name = "showName") val showName: String?,
    @Json(name = "showUri") val showUri: String?
)

internal fun parseSpotifyTimestamp(value: String?): Long {
    if (value.isNullOrBlank()) return 0L

    try {
        return Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {}

    try {
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    } catch (_: DateTimeParseException) {}

    try {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    } catch (_: DateTimeParseException) {}

    return 0L
}
