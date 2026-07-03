package me.avinas.tempo.data.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.TrackResolver
import okio.buffer
import okio.source
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class SpotifyJsonImportService @Inject constructor(
    private val trackResolver: TrackResolver,
    private val listeningEventDao: ListeningEventDao,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao
) {
    companion object {
        private const val TAG = "SpotifyJsonImport"
        private const val BATCH_SIZE = 50
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
        private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024
        private const val MAX_MS_PLAYED = 86_400_000L
        private const val MAX_STRING_LENGTH = 500
        private const val CANCELLATION_CHECK_INTERVAL = 100
        const val IMPORT_SOURCE = "com.spotify.music.import.json"

        // Plays shorter than this are treated as noise (loading errors, misclicks)
        // and do not produce listening events. Matches the reconstruction service floor.
        private const val MIN_MS_PLAYED_FOR_EVENT = 30_000L

        // Spotify reason_end values that indicate an explicit user skip.
        // "trackdone" = completed; "endplay"/"logout"/"unexpected" = interrupted, not skips.
        private val SKIP_REASONS = setOf("fwdbtn", "backbtn", "donebad")
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    sealed class ImportState {
        object Idle : ImportState()
        data class Parsing(val fileName: String, val filesProcessed: Int, val totalFiles: Int) : ImportState()
        data class Importing(val current: Int, val total: Int, val tracksImported: Int, val eventsCreated: Int) : ImportState()
        data class Completed(val result: ImportResult) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    data class ImportResult(
        val tracksImported: Int,
        val eventsCreated: Int,
        val duplicatesSkipped: Int,
        val podcastsSkipped: Int,
        val lowQualitySkipped: Int,
        val filesProcessed: Int,
        val totalEntries: Int,
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = errors.isEmpty() || (eventsCreated > 0 && errors.size < eventsCreated)
    }

    data class ParsedEntry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val spotifyTrackId: String?,
        val endTimeMillis: Long,
        val msPlayed: Long,
        val wasSkipped: Boolean = false,
        val isPodcast: Boolean = false
    )

    suspend fun importFromUris(
        context: Context,
        uris: List<Uri>
    ): ImportResult = withContext(Dispatchers.IO) {
        _importState.value = ImportState.Parsing("", 0, uris.size)

        val errors = mutableListOf<String>()
        var filesProcessed = 0
        var totalEntries = 0
        var tracksImported = 0
        var eventsCreated = 0
        var duplicatesSkipped = 0
        var podcastsSkipped = 0
        var lowQualitySkipped = 0
        var musicProcessedSoFar = 0

        // Track cache persists across files so a track resolved in an earlier file
        // is reused in later files without re-querying the database.
        val trackCache = mutableMapOf<String, Long>()

        for ((index, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()

            val fileName = getFileName(context, uri) ?: "file_${index + 1}"
            _importState.value = ImportState.Parsing(fileName, index, uris.size)

            try {
                val fileSize = getFileSize(context, uri)
                if (fileSize != null && fileSize > MAX_FILE_SIZE_BYTES) {
                    errors.add("File too large (${fileSize / 1_048_576}MB): $fileName")
                    continue
                }

                val parseResult = context.contentResolver.openInputStream(uri)?.use { stream ->
                    parseJsonStream(stream, fileName)
                } ?: ParseResult(emptyList(), 0).also {
                    errors.add("Could not open: $fileName")
                }

                if (parseResult.malformedCount > 0) {
                    errors.add("Skipped ${parseResult.malformedCount} malformed entries in $fileName")
                }

                if (parseResult.entries.isEmpty() && parseResult.malformedCount == 0 &&
                    !errors.any { it.contains(fileName) }) {
                    errors.add("No valid entries in: $fileName")
                    continue
                }

                if (parseResult.entries.isEmpty()) continue

                val filePodcasts = parseResult.entries.count { it.isPodcast }
                val musicEntries = parseResult.entries.filter { !it.isPodcast }
                    .sortedBy { it.endTimeMillis }

                totalEntries += parseResult.entries.size
                podcastsSkipped += filePodcasts

                val counts = processAndFlushEntries(
                    musicEntries = musicEntries,
                    trackCache = trackCache,
                    progressBase = musicProcessedSoFar,
                    progressTotal = totalEntries - podcastsSkipped,
                    errors = errors
                )

                musicProcessedSoFar += musicEntries.size
                tracksImported += counts.tracksImported
                eventsCreated += counts.eventsCreated
                duplicatesSkipped += counts.duplicatesSkipped
                lowQualitySkipped += counts.lowQualitySkipped

                filesProcessed++
                Log.i(TAG, "Processed ${parseResult.entries.size} entries from $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $fileName", e)
                errors.add("Failed to parse $fileName: ${e.message}")
            }
        }

        if (totalEntries == 0 && errors.isEmpty()) {
            errors.add("No entries found in files")
        }

        val result = ImportResult(
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
            duplicatesSkipped = duplicatesSkipped,
            podcastsSkipped = podcastsSkipped,
            lowQualitySkipped = lowQualitySkipped,
            filesProcessed = filesProcessed,
            totalEntries = totalEntries,
            errors = errors
        )

        _importState.value = ImportState.Completed(result)
        Log.i(TAG, "Import complete: $tracksImported tracks, $eventsCreated events, $duplicatesSkipped duplicates, $podcastsSkipped podcasts, $lowQualitySkipped low-quality skipped")
        result
    }

    private data class EntryProcessCounts(
        val tracksImported: Int,
        val eventsCreated: Int,
        val duplicatesSkipped: Int,
        val lowQualitySkipped: Int
    )

    private suspend fun processAndFlushEntries(
        musicEntries: List<ParsedEntry>,
        trackCache: MutableMap<String, Long>,
        progressBase: Int,
        progressTotal: Int,
        errors: MutableList<String>
    ): EntryProcessCounts {
        val pendingEvents = mutableListOf<ListeningEvent>()
        val pendingEnrichedMetadata = mutableListOf<Pair<Long, ParsedEntry>>()
        var tracksImported = 0
        var eventsCreated = 0
        var duplicatesSkipped = 0
        var lowQualitySkipped = 0

        musicEntries.forEachIndexed { index, entry ->
            if (index % CANCELLATION_CHECK_INTERVAL == 0) {
                coroutineContext.ensureActive()
            }

            if (index % BATCH_SIZE == 0) {
                _importState.value = ImportState.Importing(progressBase + index, progressTotal, tracksImported, eventsCreated)
            }

            try {
                when (processEntry(entry, trackCache, pendingEvents, pendingEnrichedMetadata)) {
                    ProcessResult.NewTrack -> { tracksImported++; eventsCreated++ }
                    ProcessResult.ExistingTrack -> eventsCreated++
                    ProcessResult.Duplicate -> duplicatesSkipped++
                    ProcessResult.LowQuality -> lowQualitySkipped++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import: ${entry.trackName}", e)
                errors.add("Failed: ${entry.trackName}")
            }
        }

        if (pendingEvents.isNotEmpty()) {
            try {
                val insertResult = listeningEventDao.insertAllBatchedWithDedup(pendingEvents)
                eventsCreated = insertResult.inserted
                duplicatesSkipped += insertResult.skipped
                Log.i(TAG, "Batch inserted ${insertResult.inserted} events, skipped ${insertResult.skipped} duplicates, replaced ${insertResult.replaced} lower-authority")
            } catch (e: Exception) {
                Log.e(TAG, "Batch insert failed, falling back to individual inserts", e)
                eventsCreated = 0
                for (event in pendingEvents) {
                    try {
                        val count = listeningEventDao.countEventsNearTimestamp(
                            event.track_id,
                            event.timestamp - ListeningEventDao.DUPLICATE_TOLERANCE_MS,
                            event.timestamp + ListeningEventDao.DUPLICATE_TOLERANCE_MS
                        )
                        if (count == 0) {
                            listeningEventDao.insert(event)
                            eventsCreated++
                        } else {
                            duplicatesSkipped++
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to insert event for track ${event.track_id}", e2)
                    }
                }
            }
        }

        for ((trackId, entry) in pendingEnrichedMetadata) {
            createEnrichedMetadata(trackId, entry)
        }

        return EntryProcessCounts(tracksImported, eventsCreated, duplicatesSkipped, lowQualitySkipped)
    }

    private data class ParseResult(val entries: List<ParsedEntry>, val malformedCount: Int)

    private fun parseJsonStream(inputStream: InputStream, fileName: String): ParseResult {
        val source = inputStream.source().buffer()

        if (fileName.contains("endsong", ignoreCase = true)) {
            return parseEndsongStream(source)
        }

        if (fileName.contains("StreamingHistory", ignoreCase = true)) {
            return parseStreamingHistoryStream(source)
        }

        val peekSource = source.peek()
        val firstChar = skipWhitespace(peekSource)

        return when {
            firstChar == '['.code.toLong() -> {
                try {
                    parseStreamingHistoryStream(source)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed as StreamingHistory, trying endsong array format", e)
                    parseEndsongStream(source)
                }
            }
            firstChar == '{'.code.toLong() -> parseEndsongNdjsonStream(source)
            else -> {
                Log.w(TAG, "Unknown format for $fileName, attempting StreamingHistory parse")
                parseStreamingHistoryStream(source)
            }
        }
    }

    private fun skipWhitespace(source: okio.BufferedSource): Long {
        while (!source.exhausted()) {
            val b = source.readByte().toInt()
            if (!b.toChar().isWhitespace()) {
                return b.toLong()
            }
        }
        return -1
    }

    private fun parseStreamingHistoryStream(source: okio.BufferedSource): ParseResult {
        val reader = JsonReader.of(source)
        val entries = mutableListOf<ParsedEntry>()
        var malformed = 0

        reader.beginArray()
        while (reader.hasNext()) {
            try {
                val entry = readStreamingHistoryEntry(reader)
                if (entry != null) entries.add(entry) else malformed++
            } catch (e: Exception) {
                reader.skipValue()
                malformed++
            }
        }
        reader.endArray()
        return ParseResult(entries, malformed)
    }

    private fun readStreamingHistoryEntry(reader: JsonReader): ParsedEntry? {
        var endTime: String? = null
        var artistName: String? = null
        var trackName: String? = null
        var albumName: String? = null
        var trackUri: String? = null
        var msPlayed: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "endTime" -> endTime = reader.nextString()
                "artistName" -> artistName = reader.nextStringOrNull()
                "trackName" -> trackName = reader.nextStringOrNull()
                "albumName" -> albumName = reader.nextStringOrNull()
                "trackUri" -> trackUri = reader.nextStringOrNull()
                "msPlayed" -> msPlayed = reader.nextLongOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (trackName.isNullOrBlank() || artistName.isNullOrBlank()) return null

        val spotifyTrackId = trackUri?.let {
            if (it.startsWith("spotify:track:")) it.removePrefix("spotify:track:") else null
        }

        return ParsedEntry(
            trackName = sanitizeString(trackName),
            artistName = sanitizeString(artistName),
            albumName = albumName?.let { sanitizeString(it) },
            spotifyTrackId = spotifyTrackId,
            endTimeMillis = parseTimestamp(endTime),
            msPlayed = sanitizeMsPlayed(msPlayed)
        )
    }

    private fun parseEndsongStream(source: okio.BufferedSource): ParseResult {
        val reader = JsonReader.of(source)

        val peekSource = source.peek()
        val firstChar = skipWhitespace(peekSource)

        if (firstChar == '['.code.toLong()) {
            val entries = mutableListOf<ParsedEntry>()
            var malformed = 0
            reader.beginArray()
            while (reader.hasNext()) {
                try {
                    val entry = readEndsongEntry(reader)
                    if (entry != null) entries.add(entry) else malformed++
                } catch (e: Exception) {
                    reader.skipValue()
                    malformed++
                }
            }
            reader.endArray()
            return ParseResult(entries, malformed)
        } else {
            return parseEndsongNdjsonStream(source)
        }
    }

    private fun parseEndsongNdjsonStream(source: okio.BufferedSource): ParseResult {
        val entries = mutableListOf<ParsedEntry>()
        var malformed = 0
        val adapter = moshi.adapter(SpotifyEndsongEntry::class.java)

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            try {
                val entry = adapter.fromJson(line)
                val parsed = convertEndsongEntry(entry)
                if (parsed != null) entries.add(parsed) else malformed++
            } catch (e: Exception) {
                malformed++
            }
        }
        if (malformed > 0) {
            Log.w(TAG, "Skipped $malformed malformed NDJSON lines")
        }
        return ParseResult(entries, malformed)
    }

    private fun readEndsongEntry(reader: JsonReader): ParsedEntry? {
        var timestamp: String? = null
        var msPlayed: Long? = null
        var trackName: String? = null
        var artistName: String? = null
        var albumName: String? = null
        var trackUri: String? = null
        var episodeName: String? = null
        var episodeShowName: String? = null
        var episodeUri: String? = null
        var reasonEnd: String? = null
        var skipped: Boolean? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "ts" -> timestamp = reader.nextStringOrNull()
                "ms_played" -> msPlayed = reader.nextLongOrNull()
                "master_metadata_track_name" -> trackName = reader.nextStringOrNull()
                "master_metadata_album_artist_name" -> artistName = reader.nextStringOrNull()
                "master_metadata_album_album_name" -> albumName = reader.nextStringOrNull()
                "spotify_track_uri" -> trackUri = reader.nextStringOrNull()
                "episode_name" -> episodeName = reader.nextStringOrNull()
                "episode_show_name" -> episodeShowName = reader.nextStringOrNull()
                "spotify_episode_uri" -> episodeUri = reader.nextStringOrNull()
                "reason_end" -> reasonEnd = reader.nextStringOrNull()
                "skipped" -> skipped = reader.nextBooleanOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val isPodcast = episodeUri != null || episodeName != null
        if (isPodcast) {
            val name = episodeName ?: trackName ?: return null
            val artist = episodeShowName ?: artistName ?: return null
            return ParsedEntry(
                trackName = sanitizeString(name),
                artistName = sanitizeString(artist),
                albumName = null,
                spotifyTrackId = null,
                endTimeMillis = parseTimestamp(timestamp),
                msPlayed = sanitizeMsPlayed(msPlayed),
                isPodcast = true
            )
        }

        if (trackName.isNullOrBlank() || artistName.isNullOrBlank()) return null

        val spotifyTrackId = trackUri?.let {
            if (it.startsWith("spotify:track:")) it.removePrefix("spotify:track:") else null
        }

        return ParsedEntry(
            trackName = sanitizeString(trackName),
            artistName = sanitizeString(artistName),
            albumName = albumName?.let { sanitizeString(it) },
            spotifyTrackId = spotifyTrackId,
            endTimeMillis = parseTimestamp(timestamp),
            msPlayed = sanitizeMsPlayed(msPlayed),
            wasSkipped = skipped == true || reasonEnd in SKIP_REASONS
        )
    }

    private fun convertEndsongEntry(entry: SpotifyEndsongEntry?): ParsedEntry? {
        if (entry == null) return null

        if (entry.isPodcast) return ParsedEntry(
            trackName = sanitizeString(entry.episodeName ?: entry.trackName ?: return null),
            artistName = sanitizeString(entry.episodeShowName ?: entry.artistName ?: return null),
            albumName = null,
            spotifyTrackId = null,
            endTimeMillis = entry.timestampMillis,
            msPlayed = sanitizeMsPlayed(entry.msPlayed),
            isPodcast = true
        )

        val trackName = entry.trackName ?: return null
        val artistName = entry.artistName ?: return null
        if (trackName.isBlank() || artistName.isBlank()) return null

        return ParsedEntry(
            trackName = sanitizeString(trackName),
            artistName = sanitizeString(artistName),
            albumName = entry.albumName?.let { sanitizeString(it) },
            spotifyTrackId = entry.spotifyTrackId,
            endTimeMillis = entry.timestampMillis,
            msPlayed = sanitizeMsPlayed(entry.msPlayed),
            wasSkipped = entry.skipped == true || entry.reasonEnd in SKIP_REASONS
        )
    }

    private sealed class ProcessResult {
        object NewTrack : ProcessResult()
        object ExistingTrack : ProcessResult()
        object Duplicate : ProcessResult()
        object LowQuality : ProcessResult()
    }

    private suspend fun processEntry(
        entry: ParsedEntry,
        trackCache: MutableMap<String, Long>,
        pendingEvents: MutableList<ListeningEvent>,
        pendingEnrichedMetadata: MutableList<Pair<Long, ParsedEntry>>
    ): ProcessResult {
        if (entry.endTimeMillis == 0L) return ProcessResult.Duplicate

        if (entry.msPlayed < MIN_MS_PLAYED_FOR_EVENT) return ProcessResult.LowQuality

        val cacheKey = entry.spotifyTrackId
            ?: "${entry.trackName}|${entry.artistName}|${entry.albumName ?: ""}"
        val cachedTrackId = trackCache[cacheKey]

        val trackId: Long
        val isNewTrack: Boolean

        if (cachedTrackId != null) {
            trackId = cachedTrackId
            isNewTrack = false
        } else {
            // Layer 3 + 4: centralized scored track identity resolution with
            // automatic metadata backfill. Event-level dedup is owned entirely by
            // the batch insert (Layer 1 fingerprint + Layer 2 cross-source
            // reconciliation), so there is no per-event pre-check here — every
            // qualifying entry is built and the batch decides what is genuinely new.
            val resolution = trackResolver.resolve(
                TrackResolver.Query(
                    title = entry.trackName,
                    artist = entry.artistName,
                    album = entry.albumName,
                    spotifyId = entry.spotifyTrackId
                )
            )
            trackId = resolution.trackId
            trackCache[cacheKey] = trackId
            isNewTrack = resolution.isNewTrack

            if (isNewTrack) {
                try {
                    artistLinkingService.linkArtistsForTrack(resolution.track)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to link artists for track $trackId", e)
                }
                pendingEnrichedMetadata.add(trackId to entry)
            }
        }

        val event = createListeningEvent(trackId, entry)
        pendingEvents.add(event)
        return if (isNewTrack) ProcessResult.NewTrack else ProcessResult.ExistingTrack
    }

    private fun estimateDuration(entry: ParsedEntry): Long {
        return if (entry.msPlayed > 0) entry.msPlayed else 210_000L
    }

    private fun createListeningEvent(trackId: Long, entry: ParsedEntry): ListeningEvent {
        val estimatedDuration = estimateDuration(entry)

        val completionPercentage = if (entry.wasSkipped) {
            40
        } else {
            DEFAULT_COMPLETION_PERCENTAGE
        }

        return ListeningEvent(
            track_id = trackId,
            timestamp = entry.endTimeMillis - estimatedDuration,
            playDuration = estimatedDuration,
            completionPercentage = completionPercentage,
            source = IMPORT_SOURCE,
            wasSkipped = entry.wasSkipped,
            isReplay = false,
            estimatedDurationMs = estimatedDuration,
            pauseCount = 0,
            sessionId = null,
            endTimestamp = entry.endTimeMillis
        )
    }

    private suspend fun createEnrichedMetadata(trackId: Long, entry: ParsedEntry) {
        try {
            val metadata = EnrichedMetadata(
                trackId = trackId,
                spotifyId = entry.spotifyTrackId,
                artistName = entry.artistName,
                albumTitle = entry.albumName,
                enrichmentStatus = EnrichmentStatus.PENDING,
                cacheTimestamp = System.currentTimeMillis(),
                lastEnrichmentAttempt = System.currentTimeMillis()
            )
            enrichedMetadataDao.upsert(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create enriched metadata for track $trackId", e)
        }
    }

    private fun parseTimestamp(value: String?): Long = parseSpotifyTimestamp(value)

    private fun sanitizeMsPlayed(msPlayed: Long?): Long {
        if (msPlayed == null || msPlayed < 0) return 0L
        return msPlayed.coerceAtMost(MAX_MS_PLAYED)
    }

    private fun sanitizeString(value: String): String {
        return value.trim().take(MAX_STRING_LENGTH)
    }

    private fun getFileSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get file size", e)
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get file name", e)
            null
        }
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }
}

private fun JsonReader.nextStringOrNull(): String? {
    return if (peek() == JsonReader.Token.NULL) {
        nextNull<Unit>()
        null
    } else {
        nextString()
    }
}

private fun JsonReader.nextLongOrNull(): Long? {
    return if (peek() == JsonReader.Token.NULL) {
        nextNull<Unit>()
        null
    } else {
        nextLong()
    }
}

private fun JsonReader.nextBooleanOrNull(): Boolean? {
    return if (peek() == JsonReader.Token.NULL) {
        nextNull<Unit>()
        null
    } else {
        nextBoolean()
    }
}
