package me.avinas.tempo.data.youtube

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@JsonClass(generateAdapter = true)
data class YouTubeWatchHistoryEntry(
    @Json(name = "header") val header: String?,
    @Json(name = "title") val title: String?,
    @Json(name = "titleUrl") val titleUrl: String?,
    @Json(name = "subtitles") val subtitles: List<YouTubeSubtitle>?,
    @Json(name = "time") val time: String?,
    @Json(name = "products") val products: List<String>?,
    @Json(name = "activityControls") val activityControls: List<String>?,
    @Json(name = "description") val description: String?
) {
    val isYouTubeMusic: Boolean
        get() = header == "YouTube Music" ||
            products?.any { it.equals("YouTube Music", ignoreCase = true) } == true ||
            titleUrl?.contains("music.youtube.com") == true

    val timestampMillis: Long
        get() = parseYouTubeTimestamp(time)
}

@JsonClass(generateAdapter = true)
data class YouTubeSubtitle(
    @Json(name = "name") val name: String?,
    @Json(name = "url") val url: String?
)

internal fun parseYouTubeTimestamp(value: String?): Long {
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

    try {
        val cleaned = value.replace("Z", "+00:00")
        return Instant.parse(cleaned).toEpochMilli()
    } catch (_: Exception) {}

    return 0L
}
