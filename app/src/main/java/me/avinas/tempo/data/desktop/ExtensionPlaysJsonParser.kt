package me.avinas.tempo.data.desktop

import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import java.io.EOFException
import java.io.InputStream

/**
 * Streaming reader for play export JSON files from the Tempo Stats browser extension.
 *
 * Supports both top-level arrays and objects wrapping plays under common keys
 * (`plays`, `items`, `entries`, `data`, `history`), in camelCase or snake_case.
 * Invokes [onRecord] incrementally to avoid loading large files entirely into memory.
 */
internal class ExtensionPlaysJsonParser(
    private val maxRecords: Int = MAX_RECORDS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Normalized play record from an export file.
     */
    data class Record(
        val title: String,
        val artist: String,
        val album: String?,
        val timestampUtc: Long,
        val durationMs: Long,
        val listenedMs: Long,
        val sourceApp: String?,
        val contentType: String?,
        val skipped: Boolean?,
        val isMuted: Boolean,
        val completionPercentage: Int?,
        val replayCount: Int,
        val pauseCount: Int,
        val seekCount: Int,
        val positionUpdatesCount: Int,
        val totalPauseDurationMs: Long,
        val sessionId: String?,
    )

    data class Summary(
        val parsed: Int,
        val malformed: Int,
        val truncated: Boolean,
    )

    /**
     * Reads [stream] to completion, invoking [onRecord] for each valid play.
     */
    suspend fun parse(stream: InputStream, onRecord: suspend (Record) -> Unit): Summary {
        var parsed = 0
        var malformed = 0
        var truncated = false

        JsonReader.of(stream.source().buffer()).use { reader ->
            // Only the first peek may return EOF without error (empty file).
            if (!hasContent(reader)) {
                return Summary(parsed = 0, malformed = 0, truncated = false)
            }

            // Process top-level array or wrapper object.
            while (reader.peek() != JsonReader.Token.END_DOCUMENT) {
                when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> {
                        reader.beginArray()
                        var cursor = RecordCursor(parsed, malformed)
                        while (reader.hasNext() && !cursor.full) {
                            cursor = readArrayElement(reader, cursor, onRecord)
                        }
                        parsed = cursor.parsed
                        malformed = cursor.malformed
                        if (cursor.full) {
                            truncated = true
                            break
                        }
                        reader.endArray()
                    }

                    JsonReader.Token.BEGIN_OBJECT -> {
                        // A wrapper object: consume it, feeding play-array values through the
                        // same per-record path and skipping every other key.
                        reader.beginObject()
                        var wrapperFull = false
                        while (reader.hasNext() && !wrapperFull && !parsedAtCapacity(parsed)) {
                            val name = reader.nextName()
                            val isPlayArray =
                                reader.peek() == JsonReader.Token.BEGIN_ARRAY &&
                                    name.lowercase() in WRAPPER_ARRAY_KEYS
                            if (!isPlayArray) {
                                reader.skipValue()
                                continue
                            }
                            reader.beginArray()
                            var cursor = RecordCursor(parsed, malformed)
                            while (reader.hasNext() && !cursor.full) {
                                cursor = readArrayElement(reader, cursor, onRecord)
                            }
                            parsed = cursor.parsed
                            malformed = cursor.malformed
                            if (cursor.full) {
                                wrapperFull = true
                                truncated = true
                                break
                            }
                            reader.endArray()
                        }
                        if (truncated) break
                        reader.endObject()
                    }

                    JsonReader.Token.NULL -> reader.nextNull<Any>()

                    // Skip unexpected top-level primitive tokens.
                    else -> reader.skipValue()
                }
            }
        }

        return Summary(parsed = parsed, malformed = malformed, truncated = truncated)
    }

    private val RecordCursor.full: Boolean get() = parsedAtCapacity(parsed)

    private fun parsedAtCapacity(parsed: Int): Boolean = parsed >= maxRecords

    // Consume one element of a play array.
    private suspend fun readArrayElement(
        reader: JsonReader,
        cursor: RecordCursor,
        onRecord: suspend (Record) -> Unit,
    ): RecordCursor {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return cursor.copy(malformed = cursor.malformed + 1)
        }
        val record = readRecord(reader)
        return if (record == null) {
            cursor.copy(malformed = cursor.malformed + 1)
        } else {
            onRecord(record)
            cursor.copy(parsed = cursor.parsed + 1)
        }
    }

    private data class RecordCursor(val parsed: Int, val malformed: Int)

    // Returns false if the stream is empty at the start.
    private fun hasContent(reader: JsonReader): Boolean = try {
        reader.peek() != JsonReader.Token.END_DOCUMENT
    } catch (e: EOFException) {
        false
    }

    // Reads one play object. Returns null if required fields are missing.
    private fun readRecord(reader: JsonReader): Record? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var rawTimestamp: Long? = null
        var durationMs: Long? = null
        var listenedMs: Long? = null
        var sourceApp: String? = null
        var contentType: String? = null
        var skipped: Boolean? = null
        var muted: Boolean? = null
        var volumeLevel: Double? = null
        var completion: Long? = null
        var replayCount = 0
        var pauseCount = 0
        var seekCount = 0
        var positionUpdatesCount = 0
        var totalPauseDurationMs = 0L
        var sessionId: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title", "track", "trackName", "track_name", "name" ->
                    title = reader.readText() ?: title

                "artist", "artistName", "artist_name" ->
                    artist = reader.readText() ?: artist

                "album", "albumName", "album_name" ->
                    album = reader.readText() ?: album

                "timestampUtc", "timestamp_utc", "timestamp", "playedAt", "played_at",
                "endTime", "end_time", "ts" ->
                    rawTimestamp = reader.readNumber()?.toLong() ?: rawTimestamp

                "durationMs", "duration_ms", "trackDurationMs", "track_duration_ms", "duration" ->
                    durationMs = reader.readNumber()?.toLong() ?: durationMs

                "listenedMs", "listened_ms", "msPlayed", "ms_played" ->
                    listenedMs = reader.readNumber()?.toLong() ?: listenedMs

                "sourceApp", "source_app", "source", "app" ->
                    sourceApp = reader.readText() ?: sourceApp

                "contentType", "content_type" ->
                    contentType = reader.readText()?.let(::normalizeContentType) ?: contentType

                "skipped", "wasSkipped", "was_skipped" ->
                    skipped = reader.readBool() ?: skipped

                "isMuted", "is_muted", "muted" ->
                    muted = reader.readBool() ?: muted

                "volumeLevel", "volume_level" ->
                    volumeLevel = reader.readNumber() ?: volumeLevel

                "completionPercentage", "completion_percentage" ->
                    completion = reader.readNumber()?.toLong() ?: completion

                "replayCount", "replay_count", "replays" ->
                    replayCount = reader.readNumber()?.toInt() ?: replayCount

                "pauseCount", "pause_count" ->
                    pauseCount = reader.readNumber()?.toInt() ?: pauseCount

                "seekCount", "seek_count" ->
                    seekCount = reader.readNumber()?.toInt() ?: seekCount

                "positionUpdatesCount", "position_updates_count" ->
                    positionUpdatesCount = reader.readNumber()?.toInt() ?: positionUpdatesCount

                "totalPauseDurationMs", "total_pause_duration_ms" ->
                    totalPauseDurationMs = reader.readNumber()?.toLong() ?: totalPauseDurationMs

                "sessionId", "session_id" ->
                    sessionId = reader.readText() ?: sessionId

                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val cleanTitle = title ?: return null
        val cleanArtist = artist ?: return null
        val timestampUtc = normalizeTimestamp(rawTimestamp ?: return null) ?: return null

        return Record(
            title = cleanTitle,
            artist = cleanArtist,
            album = album,
            timestampUtc = timestampUtc,
            durationMs = (durationMs ?: 0L).coerceIn(0L, MAX_DURATION_MS),
            listenedMs = (listenedMs ?: 0L).coerceIn(0L, MAX_DURATION_MS),
            sourceApp = sourceApp,
            contentType = contentType,
            skipped = skipped,
            // A row can report silence through either flag; the exported `volumeLevel`
            // is 0.0 exactly when the tab was muted, so it also counts as muted.
            isMuted = muted == true || volumeLevel == 0.0,
            completionPercentage = completion?.toInt()?.coerceIn(0, 100),
            replayCount = replayCount.coerceIn(0, MAX_COUNTER),
            pauseCount = pauseCount.coerceIn(0, MAX_COUNTER),
            seekCount = seekCount.coerceIn(0, MAX_COUNTER),
            positionUpdatesCount = positionUpdatesCount.coerceIn(0, MAX_COUNTER),
            totalPauseDurationMs = totalPauseDurationMs.coerceIn(0L, MAX_DURATION_MS),
            sessionId = sessionId,
        )
    }

    // Normalizes epoch seconds/millis and bounds valid ranges.
    private fun normalizeTimestamp(raw: Long): Long? {
        if (raw <= 0L) return null
        val millis = if (raw < SECONDS_UPPER_BOUND) raw * 1000L else raw
        if (millis < MIN_TIMESTAMP_MS) return null
        if (millis > nowMillis() + FUTURE_TOLERANCE_MS) return null
        return millis
    }

    private fun normalizeContentType(raw: String): String? =
        raw.trim().uppercase().takeIf { it in CONTENT_TYPES }

    private fun JsonReader.readText(): String? = when (peek()) {
        JsonReader.Token.NULL -> {
            nextNull<Any>()
            null
        }

        JsonReader.Token.STRING, JsonReader.Token.NUMBER, JsonReader.Token.BOOLEAN ->
            nextString().trim().take(MAX_TEXT_LENGTH).takeIf { it.isNotEmpty() }

        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readNumber(): Double? = when (peek()) {
        JsonReader.Token.NULL -> {
            nextNull<Any>()
            null
        }

        JsonReader.Token.NUMBER -> nextDouble().takeIf { it.isFinite() }

        // Numbers sometimes travel as strings ("1698246000000").
        JsonReader.Token.STRING -> nextString().trim().toDoubleOrNull()?.takeIf { it.isFinite() }

        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.readBool(): Boolean? = when (peek()) {
        JsonReader.Token.NULL -> {
            nextNull<Any>()
            null
        }

        JsonReader.Token.BOOLEAN -> nextBoolean()

        JsonReader.Token.NUMBER -> nextDouble() != 0.0

        JsonReader.Token.STRING -> when (nextString().trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }

        else -> {
            skipValue()
            null
        }
    }
}
private const val MAX_TEXT_LENGTH = 500
private const val MAX_RECORDS = 100_000
private const val MAX_COUNTER = 1_000_000

private const val MAX_DURATION_MS = 86_400_000L // 24h cap
private const val SECONDS_UPPER_BOUND = 100_000_000_000L // Threshold between epoch sec and ms
private const val MIN_TIMESTAMP_MS = 946_684_800_000L // 2000-01-01 floor
private const val FUTURE_TOLERANCE_MS = 7L * 24 * 60 * 60 * 1000 // Clock skew tolerance

/** Keys a wrapper object may use for its plays array. */
private val WRAPPER_ARRAY_KEYS = setOf("plays", "items", "entries", "data", "history")

private val CONTENT_TYPES = setOf("MUSIC", "PODCAST", "AUDIOBOOK")

