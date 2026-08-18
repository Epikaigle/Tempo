package me.avinas.tempo.data.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
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
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.worker.EnrichmentWorker
import okio.buffer
import okio.source
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class YouTubeMusicImportService @Inject constructor(
    private val trackResolver: TrackResolver,
    private val listeningEventDao: ListeningEventDao,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val statsRepository: StatsRepository
) {
    companion object {
        private const val TAG = "YouTubeMusicImport"
        private const val BATCH_SIZE = 50
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
        private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024
        private const val MAX_STRING_LENGTH = 500
        private const val CANCELLATION_CHECK_INTERVAL = 100
        private const val ESTIMATED_DURATION_MS = 210_000L
        private const val YOUTUBE_LAUNCH_EPOCH = 1112620800000L
        private const val MAX_FUTURE_MS = 365L * 24 * 60 * 60 * 1000
        const val IMPORT_SOURCE = "com.google.android.apps.youtube.music.import.json"
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
        val nonMusicSkipped: Int,
        val filesProcessed: Int,
        val totalEntries: Int,
        val errors: List<String>
    ) {
        val isSuccess: Boolean get() = errors.isEmpty() || tracksImported > 0
    }

    data class ParsedEntry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val youtubeVideoId: String?,
        val endTimeMillis: Long,
        val isPodcast: Boolean = false,
        val msPlayed: Long? = null
    )

    suspend fun importFromUris(
        context: Context,
        uris: List<Uri>
    ): ImportResult = withContext(Dispatchers.IO) {
        _importState.value = ImportState.Parsing("", 0, uris.size)

        val allEntries = mutableListOf<ParsedEntry>()
        val errors = mutableListOf<String>()
        var filesProcessed = 0

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

                val entries = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffered = BufferedInputStream(stream, 8192)
                    buffered.mark(8192)
                    val magic = ByteArray(4)
                    val bytesRead = buffered.read(magic)
                    buffered.reset()

                    val isZipMagic = bytesRead >= 4 &&
                        magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                    val isZipByName = fileName.endsWith(".zip", ignoreCase = true)

                    if (isZipMagic || isZipByName) {
                        parseZipStream(buffered, fileName)
                    } else {
                        parseJsonStream(buffered, fileName)
                    }
                } ?: run {
                    errors.add("Could not open: $fileName")
                    ParseResult()
                }

                if (entries.parsed.isEmpty()) {
                    errors.addAll(entries.errors)
                    if (entries.errors.isEmpty()) {
                        errors.add("No valid YouTube Music entries in: $fileName")
                    }
                    continue
                }

                errors.addAll(entries.errors)
                allEntries.addAll(entries.parsed)
                filesProcessed++
                Log.i(TAG, "Parsed ${entries.parsed.size} YouTube Music entries from $fileName (skipped ${entries.nonMusicSkipped} non-music)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $fileName", e)
                errors.add("Failed to parse $fileName: ${e.message}")
            }
        }

        if (allEntries.isEmpty()) {
            val result = ImportResult(0, 0, 0, 0, 0, filesProcessed, 0, errors.ifEmpty { listOf("No YouTube Music entries found in files") })
            _importState.value = ImportState.Completed(result)
            return@withContext result
        }

        Log.i(TAG, "Total parsed YouTube Music entries: ${allEntries.size}, starting import...")

        val podcastsSkipped = allEntries.count { it.isPodcast }
        val musicEntries = allEntries.filter { !it.isPodcast }
            .sortedBy { it.endTimeMillis }

        var tracksImported = 0
        var eventsCreated = 0
        var duplicatesSkipped = 0

        val trackCache = mutableMapOf<String, Long>()
        val pendingEvents = mutableListOf<ListeningEvent>()
        val pendingEnrichedMetadata = mutableListOf<Pair<Long, ParsedEntry>>()
        val existingTrackIds = mutableSetOf<Long>()
        val total = musicEntries.size

        musicEntries.forEachIndexed { index, entry ->
            if (index % CANCELLATION_CHECK_INTERVAL == 0) {
                coroutineContext.ensureActive()
            }

            if (index % BATCH_SIZE == 0) {
                _importState.value = ImportState.Importing(index, total, tracksImported, eventsCreated)
            }

            try {
                val result = processEntry(entry, trackCache, pendingEvents, pendingEnrichedMetadata, existingTrackIds)
                when (result) {
                    ProcessResult.NewTrack -> {
                        tracksImported++
                        eventsCreated++
                    }
                    ProcessResult.ExistingTrack -> eventsCreated++
                    ProcessResult.Duplicate -> duplicatesSkipped++
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

        if (eventsCreated > 0) {
            statsRepository.invalidateCache()
        }

        for ((trackId, entry) in pendingEnrichedMetadata) {
            createEnrichedMetadata(trackId, entry)
        }

        val requeuedCount = requeueExistingTracksForEnrichment(existingTrackIds)

        val result = ImportResult(
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
            duplicatesSkipped = duplicatesSkipped,
            podcastsSkipped = podcastsSkipped,
            nonMusicSkipped = 0,
            filesProcessed = filesProcessed,
            totalEntries = allEntries.size,
            errors = errors
        )

        _importState.value = ImportState.Completed(result)
        Log.i(TAG, "Import complete: $tracksImported tracks, $eventsCreated events, $duplicatesSkipped duplicates, $podcastsSkipped podcasts skipped")

        if (tracksImported > 0 || requeuedCount > 0) {
            try {
                EnrichmentWorker.schedulePostImportEnrichment(context, (tracksImported + requeuedCount).toLong())
                Log.i(TAG, "Scheduled post-import enrichment for $tracksImported new + $requeuedCount re-queued tracks")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule post-import enrichment", e)
            }
        }

        result
    }

    private data class ParseResult(
        val parsed: List<ParsedEntry> = emptyList(),
        val errors: List<String> = emptyList(),
        val htmlDetected: Boolean = false,
        val nonMusicSkipped: Int = 0
    )

    private fun parseJsonStream(inputStream: InputStream, fileName: String): ParseResult {
        return parseJsonFromSourceWithHtmlCheck(inputStream.source().buffer(), fileName)
    }

    private fun parseJsonFromSourceWithHtmlCheck(source: okio.BufferedSource, fileName: String): ParseResult {
        val peekSource = source.peek()
        val firstChars = readFirstNonWhitespaceChars(peekSource, 20)

        if (firstChars.startsWith("<!DOCTYPE", ignoreCase = true) ||
            firstChars.startsWith("<html", ignoreCase = true)
        ) {
            val htmlContent = source.readUtf8()
            return parseHtmlString(htmlContent, fileName)
        }

        return parseJsonFromSource(source, fileName)
    }

    private fun parseZipStream(inputStream: InputStream, fileName: String): ParseResult {
        val allEntries = mutableListOf<ParsedEntry>()
        val allErrors = mutableListOf<String>()
        var nonMusicSkipped = 0
        var jsonFilesFound = 0
        var htmlFilesFound = 0
        val allZipEntryNames = mutableListOf<String>()

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.lowercase()
                    allZipEntryNames.add(entry.name)

                    val isWatchHistory = entryName.contains("watch-history") ||
                        entryName.contains("watch_history") ||
                        entryName.contains("myactivity")
                    val isHistoryJson = isWatchHistory && entryName.endsWith(".json")
                    val isHistoryHtml = isWatchHistory && entryName.endsWith(".html")

                    if (isHistoryJson) {
                        jsonFilesFound++
                        val entryLabel = "$fileName!/${entry.name}"
                        val bytes = zis.readBytes()
                        val source = okio.Buffer().write(bytes)
                        val result = parseJsonFromSourceWithHtmlCheck(source, entryLabel)
                        allEntries.addAll(result.parsed)
                        allErrors.addAll(result.errors)
                        nonMusicSkipped += result.nonMusicSkipped
                        if (result.htmlDetected) {
                            allErrors.add("HTML format detected in ${entry.name} inside $fileName. Re-export from Takeout choosing JSON format.")
                        }
                    } else if (isHistoryHtml) {
                        htmlFilesFound++
                        val entryLabel = "$fileName!/${entry.name}"
                        val htmlContent = String(zis.readBytes(), Charsets.UTF_8)
                        val result = parseHtmlString(htmlContent, entryLabel)
                        allEntries.addAll(result.parsed)
                        allErrors.addAll(result.errors)
                        nonMusicSkipped += result.nonMusicSkipped
                        Log.i(TAG, "Parsed ${result.parsed.size} entries from HTML file ${entry.name} in ZIP")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ZIP $fileName", e)
            return ParseResult(errors = listOf("Failed to read ZIP $fileName: ${e.message}"))
        }

        if (jsonFilesFound == 0 && htmlFilesFound == 0) {
            Log.i(TAG, "ZIP $fileName contains ${allZipEntryNames.size} entries: ${allZipEntryNames.joinToString(", ")}")
            val error = when {
                allZipEntryNames.isEmpty() -> {
                    "ZIP $fileName appears to be empty or is another part of a split archive. " +
                        "Select the ZIP that contains the 'history' folder (watch-history.json), or select all ZIP parts at once."
                }
                else -> {
                    val sampleNames = allZipEntryNames.take(15).joinToString(", ")
                    val ellipsis = if (allZipEntryNames.size > 15) "..." else ""
                    "No watch-history file found in ZIP $fileName. Found ${allZipEntryNames.size} files: $sampleNames$ellipsis " +
                        "If your export was split into multiple ZIPs, select the one containing the 'history' folder — or select all of them at once."
                }
            }
            return ParseResult(errors = listOf(error))
        }

        Log.i(TAG, "Found $jsonFilesFound JSON and $htmlFilesFound HTML watch-history file(s) in ZIP $fileName, parsed ${allEntries.size} entries")
        return ParseResult(parsed = allEntries, errors = allErrors, nonMusicSkipped = nonMusicSkipped)
    }

    private fun parseJsonFromSource(source: okio.BufferedSource, fileName: String): ParseResult {
        return try {
            val reader = JsonReader.of(source)
            val entries = mutableListOf<ParsedEntry>()
            val errors = mutableListOf<String>()
            var nonMusicSkipped = 0

            reader.beginArray()
            while (reader.hasNext()) {
                try {
                    val entry = readWatchHistoryEntry(reader)
                    if (entry == null) {
                        nonMusicSkipped++
                    } else if (entry.isPodcast) {
                        entries.add(entry)
                    } else {
                        entries.add(entry)
                    }
                } catch (e: Exception) {
                    try {
                        reader.skipValue()
                    } catch (_: Exception) {}
                }
            }
            reader.endArray()

            ParseResult(parsed = entries, errors = errors, nonMusicSkipped = nonMusicSkipped)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON array in $fileName", e)
            ParseResult(errors = listOf("Failed to parse $fileName: ${e.message}"))
        }
    }

    private fun readFirstNonWhitespaceChars(source: okio.BufferedSource, count: Int): String {
        val sb = StringBuilder()
        while (!source.exhausted() && sb.length < count) {
            val b = source.readByte().toInt()
            if (!b.toChar().isWhitespace()) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun readWatchHistoryEntry(reader: JsonReader): ParsedEntry? {
        var header: String? = null
        var title: String? = null
        var titleUrl: String? = null
        var time: String? = null
        var description: String? = null
        val subtitles = mutableListOf<Pair<String?, String?>>()
        val products = mutableListOf<String>()
        val details = mutableListOf<String>()
        var hasContentDetails = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "header" -> header = reader.nextStringOrNull()
                "title" -> title = reader.nextStringOrNull()
                "titleUrl" -> titleUrl = reader.nextStringOrNull()
                "time" -> time = reader.nextStringOrNull()
                "description" -> description = reader.nextStringOrNull()
                "products" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        products.add(reader.nextStringOrNull() ?: "")
                    }
                    reader.endArray()
                }
                "subtitles" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var subName: String? = null
                        var subUrl: String? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> subName = reader.nextStringOrNull()
                                "url" -> subUrl = reader.nextStringOrNull()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        subtitles.add(subName to subUrl)
                    }
                    reader.endArray()
                }
                "details" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> details.add(reader.nextStringOrNull() ?: "")
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                }
                "contentDetails" -> {
                    hasContentDetails = true
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (hasContentDetails && header == null && title == null) return null

        val isYTM = header == "YouTube Music" ||
            products.any { it.equals("YouTube Music", ignoreCase = true) } ||
            titleUrl?.contains("music.youtube.com") == true

        if (!isYTM) return null

        if (title.isNullOrBlank()) return null

        val titleTrimmed = title.trim()
        if (titleTrimmed == "Viewed Ads On YouTube Homepage") return null
        if (titleTrimmed.startsWith("Visited ")) return null
        if (titleUrl?.contains("youtube.com/post/") == true) return null

        val rawTitle = stripWatchedPrefix(title)
        if (rawTitle.isBlank()) return null

        if (rawTitle.startsWith("http://", ignoreCase = true) ||
            rawTitle.startsWith("https://", ignoreCase = true)
        ) return null

        val videoId = extractVideoId(titleUrl)

        val timestamp = parseTimestamp(time)
        if (timestamp == 0L) return null
        if (timestamp < YOUTUBE_LAUNCH_EPOCH) return null
        if (timestamp > System.currentTimeMillis() + MAX_FUTURE_MS) return null

        val artistFromSubtitles = subtitles.firstOrNull()?.first?.let { cleanArtistName(it) }
        val (cleanTitle, artistFromTitle) = if (artistFromSubtitles != null) {
            rawTitle to null
        } else {
            parseTitleAndArtist(rawTitle)
        }
        val artist = (artistFromSubtitles ?: artistFromTitle)?.let { sanitizeString(it) }

        if (artist.isNullOrBlank()) return null

        val finalTitle = sanitizeString(cleanTitle)
        if (finalTitle.isBlank()) return null

        if (isAdOrNonMusic(finalTitle, artist)) return null
        if (isAdFromDetails(details)) return null

        val album = extractAlbum(subtitles, description)
        val isPodcast = isPodcast(description, subtitles)

        return ParsedEntry(
            trackName = finalTitle,
            artistName = artist,
            albumName = album?.let { sanitizeString(it) },
            youtubeVideoId = videoId,
            endTimeMillis = timestamp,
            isPodcast = isPodcast
        )
    }

    private fun stripWatchedPrefix(title: String): String {
        val watched = "Watched "
        return if (title.startsWith(watched, ignoreCase = true)) {
            title.substring(watched.length).trim()
        } else {
            title.trim()
        }
    }

    private fun parseTitleAndArtist(title: String): Pair<String, String?> {
        val parts = title.split(" - ")
        return when {
            parts.size >= 2 -> parts.first() to parts.drop(1).joinToString(" - ")
            else -> title to null
        }
    }

    private fun cleanArtistName(name: String): String {
        var cleaned = name.trim()
        if (cleaned.endsWith(" - Topic")) {
            cleaned = cleaned.removeSuffix(" - Topic").trim()
        }
        if (cleaned.endsWith(" - Topic.")) {
            cleaned = cleaned.removeSuffix(" - Topic.").trim()
        }
        return cleaned
    }

    private fun extractAlbum(
        subtitles: List<Pair<String?, String?>>,
        description: String?
    ): String? {
        subtitles.getOrNull(1)?.first?.let { return it.trim() }

        if (description != null) {
            val albumMatch = Regex("from album[:\\s]+(.+)", RegexOption.IGNORE_CASE).find(description)
            if (albumMatch != null) {
                return albumMatch.groupValues[1].trim()
            }
        }
        return null
    }

    private fun isAdOrNonMusic(title: String, artist: String): Boolean {
        val titleLower = title.lowercase().trim()
        val artistLower = artist.lowercase().trim()

        val nonMusicNames = setOf("youtube", "youtube music", "advertisement", "ad", "video unavailable")
        if (titleLower in nonMusicNames) return true
        if (artistLower in nonMusicNames) return true
        if (artistLower.contains("youtube music")) return true

        return false
    }

    private fun isAdFromDetails(details: List<String>): Boolean {
        return details.any { it.lowercase().contains("ads") }
    }

    private fun isPodcast(
        description: String?,
        subtitles: List<Pair<String?, String?>>
    ): Boolean {
        if (description?.lowercase()?.contains("podcast") == true) return true
        if (subtitles.any { it.first?.lowercase()?.contains("podcast") == true }) return true
        return false
    }

    private fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null

        try {
            val uri = Uri.parse(url)
            val vParam = uri.getQueryParameter("v")
            if (!vParam.isNullOrBlank() && vParam.length == 11) return vParam

            if (url.contains("youtu.be/")) {
                val path = uri.lastPathSegment
                if (!path.isNullOrBlank() && path.length == 11) return path
            }

            val pattern = Regex("[?&]v=([^&]+)")
            pattern.find(url)?.let {
                val id = it.groupValues[1]
                if (id.length == 11) return id
            }
        } catch (_: Exception) {}

        return null
    }

    private sealed class ProcessResult {
        object NewTrack : ProcessResult()
        object ExistingTrack : ProcessResult()
        object Duplicate : ProcessResult()
    }

    private suspend fun processEntry(
        entry: ParsedEntry,
        trackCache: MutableMap<String, Long>,
        pendingEvents: MutableList<ListeningEvent>,
        pendingEnrichedMetadata: MutableList<Pair<Long, ParsedEntry>>,
        existingTrackIds: MutableSet<Long>
    ): ProcessResult {
        if (entry.endTimeMillis == 0L) return ProcessResult.Duplicate

        val cacheKey = entry.youtubeVideoId ?: "${entry.trackName}|${entry.artistName}"
        val cachedTrackId = trackCache[cacheKey]

        val trackId: Long
        val isNewTrack: Boolean

        if (cachedTrackId != null) {
            trackId = cachedTrackId
            isNewTrack = false
        } else {
            val resolution = trackResolver.resolve(
                TrackResolver.Query(
                    title = entry.trackName,
                    artist = entry.artistName,
                    album = entry.albumName,
                    youtubeId = entry.youtubeVideoId
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

        if (!isNewTrack) {
            existingTrackIds.add(trackId)
        }

        val event = createListeningEvent(trackId, entry)
        pendingEvents.add(event)
        return if (isNewTrack) ProcessResult.NewTrack else ProcessResult.ExistingTrack
    }

    private fun estimateDuration(entry: ParsedEntry): Long {
        return entry.msPlayed?.takeIf { it > 0 } ?: ESTIMATED_DURATION_MS
    }

    private fun createListeningEvent(trackId: Long, entry: ParsedEntry): ListeningEvent {
        val estimatedDuration = estimateDuration(entry)
        val completionPercentage = DEFAULT_COMPLETION_PERCENTAGE

        return ListeningEvent(
            track_id = trackId,
            timestamp = entry.endTimeMillis - estimatedDuration,
            playDuration = estimatedDuration,
            completionPercentage = completionPercentage,
            source = IMPORT_SOURCE,
            wasSkipped = false,
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
                spotifyId = null,
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

    /**
     * Re-queue existing tracks for enrichment after import.
     * - Re-queues FAILED tracks and ENRICHED tracks missing album art as PENDING
     * - Creates PENDING entries for existing tracks that have no enriched_metadata row
     * Returns the total number of tracks queued for enrichment.
     */
    private suspend fun requeueExistingTracksForEnrichment(existingTrackIds: Set<Long>): Int {
        if (existingTrackIds.isEmpty()) return 0

        var requeued = 0
        existingTrackIds.chunked(BATCH_SIZE).forEach { batch ->
            try {
                requeued += enrichedMetadataDao.requeueTracksForEnrichment(batch)

                val missingIds = enrichedMetadataDao.findTrackIdsWithoutEnrichedMetadata(batch)
                for (trackId in missingIds) {
                    createEnrichedMetadata(trackId, ParsedEntry(
                        trackName = "",
                        artistName = "",
                        albumName = null,
                        youtubeVideoId = null,
                        endTimeMillis = 0L
                    ))
                    requeued++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-queue batch of ${batch.size} tracks for enrichment", e)
            }
        }

        if (requeued > 0) {
            Log.i(TAG, "Re-queued $requeued existing tracks for enrichment")
        }
        return requeued
    }

    private fun parseTimestamp(value: String?): Long = parseYouTubeTimestamp(value)

    private fun parseHtmlString(htmlContent: String, fileName: String): ParseResult {
        val entries = mutableListOf<ParsedEntry>()
        val errors = mutableListOf<String>()
        var nonMusicSkipped = 0

        try {
            val linkPattern = Pattern.compile(
                "<a\\s+href=\"([^\"]*(?:watch\\?v=|youtu\\.be/)([A-Za-z0-9_-]{11}))\"[^>]*>([^<]*)</a>"
            )
            val allLinkPattern = Pattern.compile("<a\\s+href=\"[^\"]*\"[^>]*>([^<]*)</a>")

            // ponytail: lazy sequence avoids OOM — split() materialized every chunk as a copied String at once
            val chunks = htmlContent.splitToSequence("<div class=\"outer-cell")

            for (chunk in chunks) {
                if (chunk.isBlank()) continue

                val isYTM = chunk.contains("YouTube Music", ignoreCase = true)
                if (!isYTM) {
                    if (chunk.contains("watch?v=") || chunk.contains("youtu.be/")) {
                        nonMusicSkipped++
                    }
                    continue
                }

                val linkMatcher = linkPattern.matcher(chunk)
                if (!linkMatcher.find()) continue

                val videoUrl = linkMatcher.group(1)
                val videoId = linkMatcher.group(2)
                val rawTitle = linkMatcher.group(3)?.trim() ?: continue

                val title = stripWatchedPrefix(rawTitle)
                if (title.isBlank()) continue
                if (title.startsWith("http://", ignoreCase = true) ||
                    title.startsWith("https://", ignoreCase = true)
                ) continue

                val artistMatcher = allLinkPattern.matcher(chunk)
                var artistName: String? = null
                var firstLinkSkipped = false
                while (artistMatcher.find()) {
                    if (!firstLinkSkipped) {
                        firstLinkSkipped = true
                        continue
                    }
                    val name = artistMatcher.group(1)?.trim()
                    if (!name.isNullOrBlank()) {
                        artistName = cleanArtistName(name)
                        break
                    }
                }

                if (artistName.isNullOrBlank()) {
                    val (titlePart, artistPart) = parseTitleAndArtist(title)
                    if (artistPart != null) {
                        artistName = artistPart
                    }
                }

                val finalTitle = sanitizeString(title)
                val finalArtist = artistName?.let { sanitizeString(it) }

                if (finalTitle.isBlank() || finalArtist.isNullOrBlank()) continue
                if (isAdOrNonMusic(finalTitle, finalArtist)) continue

                val timestamp = parseHtmlTimestamp(chunk)
                if (timestamp == 0L) continue
                if (timestamp < YOUTUBE_LAUNCH_EPOCH) continue
                if (timestamp > System.currentTimeMillis() + MAX_FUTURE_MS) continue

                val watchTime = parseWatchTime(chunk)
                val isPodcast = chunk.lowercase().contains("podcast")

                entries.add(ParsedEntry(
                    trackName = finalTitle,
                    artistName = finalArtist,
                    albumName = null,
                    youtubeVideoId = videoId,
                    endTimeMillis = timestamp,
                    isPodcast = isPodcast,
                    msPlayed = watchTime
                ))
            }

            Log.i(TAG, "Parsed ${entries.size} YouTube Music entries from HTML $fileName (skipped $nonMusicSkipped non-music)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HTML $fileName", e)
            errors.add("Failed to parse HTML $fileName: ${e.message}")
        }

        return ParseResult(parsed = entries, errors = errors, nonMusicSkipped = nonMusicSkipped)
    }

    private fun parseHtmlTimestamp(text: String): Long {
        val cleanText = text.replace(Regex("<[^>]+>"), "\n").trim()
        val lines = cleanText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val timestampText = lines.lastOrNull { it.matches(Regex(".*\\d{4}.*")) } ?: return 0L

        var cleanedTs = timestampText.trim().replace(Regex("\\s+"), " ")

        // Strip trailing timezone abbreviation (e.g. "IST", "PST", "EST", "CET", "CEST", "GMT")
        // YouTube Takeout HTML timestamps can include locale-specific TZ abbreviations
        cleanedTs = cleanedTs.replace(Regex("\\s+[A-Z]{2,5}$"), "")

        val formats = listOf(
            "MMM d, yyyy, h:mm:ss a" to Locale.US,
            "MMM d, yyyy, H:mm:ss" to Locale.US,
            "MMM d, yyyy, h:mm a" to Locale.US,
            "MMM d, yyyy" to Locale.US,
            "d MMM yyyy, H:mm:ss" to Locale.US,
            "d MMM yyyy, HH:mm:ss" to Locale.US,
            "d MMM. yyyy, H:mm:ss" to Locale.US,
            "d MMM. yyyy, HH:mm:ss" to Locale.US,
            "d. MMM yyyy, HH:mm:ss" to Locale.GERMANY,
            "d MMM yyyy, HH:mm:ss" to Locale.FRANCE,
            "yyyy-MM-dd HH:mm:ss" to Locale.US,
            "yyyy-MM-dd HH:mm" to Locale.US,
            "yyyy-MM-dd" to Locale.US
        )

        for ((pattern, locale) in formats) {
            try {
                val format = DateTimeFormatter.ofPattern(pattern, locale)
                val ldt = LocalDateTime.parse(cleanedTs, format)
                return ldt.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {}
        }

        Log.w(TAG, "Could not parse HTML timestamp: $timestampText")
        return 0L
    }

    private fun parseWatchTime(text: String): Long? {
        val match = Regex(
            "Watch time:\\s*(?:(\\d+)\\s*(?:minutes?|mins?))?\\s*(?:(\\d+)\\s*(?:seconds?|secs?))?",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (match == null) return null

        val minutes = match.groupValues[1].toIntOrNull() ?: 0
        val seconds = match.groupValues[2].toIntOrNull() ?: 0

        if (minutes == 0 && seconds == 0) return null

        return (minutes * 60L + seconds) * 1000L
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
