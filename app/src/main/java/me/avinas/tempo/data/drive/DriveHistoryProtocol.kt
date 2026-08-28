package me.avinas.tempo.data.drive

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Versioned, platform-neutral protocol used to exchange listening history through
 * Google Drive's appDataFolder. Android, the browser extension and Tempo Desktop
 * must keep this wire format compatible.
 *
 * Drive is only the transport. The local databases remain the source of truth on
 * each device and every imported event goes through Tempo's normal dedup pipeline.
 */
object DriveHistoryProtocol {
    const val SCHEMA_VERSION = 1
    const val FILE_PREFIX = "tempo_history_v1_"
    const val APP_PROPERTY_KIND = "tempo_kind"
    const val APP_PROPERTY_SCHEMA = "tempo_schema"
    const val APP_PROPERTY_DEVICE_ID = "source_device_id"
    const val APP_PROPERTY_PLATFORM = "source_platform"
    const val APP_PROPERTY_GENERATION = "tempo_generation"
    const val KIND_HISTORY_BATCH = "history_batch"

    // Keep hostile/corrupt Drive files from forcing unbounded allocations. These
    // limits mirror the Desktop reader and are deliberately far above normal
    // 50-event Tempo batches.
    const val MAX_COMPRESSED_BYTES = 10 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 10 * 1024 * 1024
    const val MAX_EVENTS_PER_BATCH = 1_000
    private const val MAX_PRIMARY_TEXT_LENGTH = 1_000

    /** Kept for callers that need a one-off identifier outside retryable uploads. */
    fun newBatchId(): String = UUID.randomUUID().toString()

    /**
     * Generation is the Google-server timestamp of the deletion marker this
     * client explicitly accepted. It is part of the filename as well as Drive
     * metadata so a post-delete re-seed can never collide with an old immutable
     * batch that happens to contain the same deterministic event IDs.
     */
    fun fileName(deviceId: String, batchId: String, generation: Long = 0L): String =
        "${FILE_PREFIX}g${generation}_${deviceId}_${batchId}.json.gz"

    /**
     * Deterministic batch id derived only from the ordered event ids.
     *
     * A network upload may reach Drive even if the client is killed before it can
     * persist its local cursor/flag. Retrying the same chunk must therefore target
     * the same Drive filename instead of creating a second immutable copy.
     */
    fun createBatchId(events: List<DriveHistoryEvent>): String {
        require(events.isNotEmpty()) { "A history batch cannot be empty" }
        return sha256(
            buildString {
                append("tempo-batch-v1")
                events.forEach { event ->
                    append('|')
                    append(event.eventId)
                }
            }
        )
    }

    /**
     * Stable event id for a locally-owned database row. It deliberately includes
     * the random Tempo device id so integer row ids from two devices can never
     * collide. Imported Drive events retain their original event_id instead of
     * generating a new one, preventing sync loops.
     */
    fun createEventId(
        deviceId: String,
        localEventId: Long,
        timestampUtc: Long,
        title: String,
        artist: String
    ): String {
        val canonical = buildString {
            append("tempo-history-v1|")
            append(deviceId)
            append('|')
            append(localEventId)
            append('|')
            append(timestampUtc)
            append('|')
            append(title.trim().lowercase())
            append('|')
            append(artist.trim().lowercase())
        }
        return sha256(canonical)
    }

    fun encodeCompressed(batch: DriveHistoryBatch): ByteArray {
        val json = JSONObject().apply {
            put("schema_version", batch.schemaVersion)
            put("batch_id", batch.batchId)
            put("source_device_id", batch.sourceDeviceId)
            put("source_device_name", batch.sourceDeviceName)
            put("source_platform", batch.sourcePlatform)
            put("created_at_utc", batch.createdAtUtc)
            put("events", JSONArray().apply {
                batch.events.forEach { put(eventToJson(it)) }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_DECOMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the decompressed size limit"
        }

        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(json) }
            output.toByteArray().also { compressed ->
                require(compressed.size <= MAX_COMPRESSED_BYTES) {
                    "Tempo Drive history batch exceeds the compressed size limit"
                }
            }
        }
    }

    fun decodeCompressed(bytes: ByteArray): DriveHistoryBatch {
        require(bytes.size <= MAX_COMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the compressed size limit"
        }

        val jsonBytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_DECOMPRESSED_BYTES) {
                        "Tempo Drive history batch expands beyond the safe size limit"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        val root = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val schema = root.optInt("schema_version", -1)
        require(schema == SCHEMA_VERSION) {
            "Unsupported Tempo history schema: $schema"
        }

        val batchId = root.optString("batch_id")
        val sourceDeviceId = root.optString("source_device_id")
        require(batchId.isNotBlank() && sourceDeviceId.isNotBlank()) {
            "Malformed Tempo Drive history batch identity"
        }

        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        require(eventsJson.length() <= MAX_EVENTS_PER_BATCH) {
            "Tempo Drive history batch contains too many events"
        }
        val events = ArrayList<DriveHistoryEvent>(eventsJson.length())
        for (index in 0 until eventsJson.length()) {
            val event = eventFromJson(eventsJson.getJSONObject(index))
            if (
                event.eventId.isNotBlank() &&
                event.title.isNotBlank() && event.title.length <= MAX_PRIMARY_TEXT_LENGTH &&
                event.artist.isNotBlank() && event.artist.length <= MAX_PRIMARY_TEXT_LENGTH
            ) {
                events.add(event)
            }
        }

        return DriveHistoryBatch(
            schemaVersion = schema,
            batchId = batchId,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = root.optString("source_device_name", "Tempo device"),
            sourcePlatform = root.optString("source_platform", "unknown"),
            createdAtUtc = root.optLong("created_at_utc", 0L),
            events = events
        )
    }

    private fun eventToJson(event: DriveHistoryEvent): JSONObject = JSONObject().apply {
        put("event_id", event.eventId)
        put("title", event.title)
        put("artist", event.artist)
        putNullable("album", event.album)
        put("timestamp_utc", event.timestampUtc)
        put("duration_ms", event.durationMs)
        put("listened_ms", event.listenedMs)
        put("source_app", event.sourceApp)
        put("source", event.source)
        put("skipped", event.skipped)
        put("replay_count", event.replayCount)
        put("completion_percentage", event.completionPercentage)
        put("pause_count", event.pauseCount)
        put("seek_count", event.seekCount)
        putNullable("session_id", event.sessionId)
        putNullable("site", event.site)
        put("content_type", event.contentType)
        putNullable("volume_level", event.volumeLevel)
        put("total_pause_duration_ms", event.totalPauseDurationMs)
        put("position_updates_count", event.positionUpdatesCount)
    }

    private fun eventFromJson(json: JSONObject): DriveHistoryEvent = DriveHistoryEvent(
        eventId = json.optString("event_id"),
        title = json.optString("title"),
        artist = json.optString("artist"),
        album = json.optNullableString("album"),
        timestampUtc = json.optLong("timestamp_utc", 0L),
        durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L),
        listenedMs = json.optLong("listened_ms", 0L).coerceAtLeast(0L),
        sourceApp = json.optString("source_app", "unknown"),
        source = json.optString("source", "unknown"),
        skipped = json.optBoolean("skipped", false),
        replayCount = json.optInt("replay_count", 0).coerceAtLeast(0),
        completionPercentage = json.optInt("completion_percentage", 0).coerceIn(0, 100),
        pauseCount = json.optInt("pause_count", 0).coerceAtLeast(0),
        seekCount = json.optInt("seek_count", 0).coerceAtLeast(0),
        sessionId = json.optNullableString("session_id"),
        site = json.optNullableString("site"),
        contentType = json.optString("content_type", "MUSIC").ifBlank { "MUSIC" },
        volumeLevel = if (json.isNull("volume_level")) null else json.optInt("volume_level"),
        totalPauseDurationMs = json.optLong("total_pause_duration_ms", 0L).coerceAtLeast(0L),
        positionUpdatesCount = json.optInt("position_updates_count", 0).coerceAtLeast(0)
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class DriveHistoryBatch(
    val schemaVersion: Int = DriveHistoryProtocol.SCHEMA_VERSION,
    val batchId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourcePlatform: String,
    val createdAtUtc: Long,
    val events: List<DriveHistoryEvent>
)

data class DriveHistoryEvent(
    val eventId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val timestampUtc: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val sourceApp: String,
    val source: String,
    val skipped: Boolean,
    val replayCount: Int,
    val completionPercentage: Int,
    val pauseCount: Int,
    val seekCount: Int,
    val sessionId: String?,
    val site: String?,
    val contentType: String,
    /** 0 means muted; any positive value means audible; null means unknown. */
    val volumeLevel: Int?,
    val totalPauseDurationMs: Long,
    val positionUpdatesCount: Int
)