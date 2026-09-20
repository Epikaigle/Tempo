package me.avinas.tempo.data.deezer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.worker.EnrichmentWorker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class DeezerDataImportService @Inject constructor(
    private val trackResolver: TrackResolver,
    private val listeningEventDao: ListeningEventDao,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val trackRepository: TrackRepository,
    private val statsRepository: StatsRepository,
) {
    companion object {
        private const val TAG = "DeezerDataImport"
        private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024
        private const val MIN_MS_PLAYED_FOR_EVENT = 30_000L
        private const val FLUSH_BATCH_SIZE = 500
        private const val MAX_CACHE_SIZE = 50_000
        private const val MAX_ERRORS = 20
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
        private const val TEMP_FILE_PREFIX = "tempo_deezer_"
        private const val MAX_DISPLAY_NAME_LENGTH = 200
        const val IMPORT_SOURCE = "com.deezer.music.import.xlsx"
    }

    sealed class ImportState {
        object Idle : ImportState()
        data class Parsing(val fileName: String) : ImportState()
        data class Importing(
            val current: Int,
            val total: Int,
            val tracksImported: Int,
            val eventsCreated: Int,
        ) : ImportState()
        data class Completed(val result: ImportResult) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    data class ImportResult(
        val tracksImported: Int,
        val eventsCreated: Int,
        val duplicatesSkipped: Int,
        val shortPlaysSkipped: Int,
        val malformedRows: Int,
        val totalEntries: Int,
        val errors: List<String>,
    ) {
        val isSuccess: Boolean
            get() = when {
                eventsCreated > 0 -> true
                errors.isNotEmpty() -> false
                duplicatesSkipped > 0 || shortPlaysSkipped > 0 -> true
                else -> false
            }
    }

    private val importMutex = Mutex()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
    ): ImportResult =
        importMutex.withLock {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                cleanupStaleTempFiles(appContext.cacheDir)

                val fileName = sanitizeDisplayName(getFileName(appContext, uri) ?: "deezer-data.xlsx")
                _importState.value = ImportState.Parsing(fileName)

                val errors = mutableListOf<String>()
                val declaredSize = getFileSize(appContext, uri)
                if (declaredSize != null && declaredSize > MAX_FILE_SIZE_BYTES) {
                    val result =
                        ImportResult(
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            listOf("Deezer export is larger than 500 MB"),
                        )
                    _importState.value = ImportState.Error(result.errors.first())
                    return@withContext result
                }

                var tempFile: File? = null
                var importStarted = false
                try {
                    tempFile = File.createTempFile(TEMP_FILE_PREFIX, ".xlsx", appContext.cacheDir)
                    copyUriWithLimit(appContext, uri, tempFile)
                    val parsed =
                        DeezerXlsxParser.parse(tempFile) {
                            coroutineContext.ensureActive()
                        }
                    if (parsed.entries.isEmpty()) {
                        throw IllegalArgumentException("No valid Deezer listening history entries found")
                    }

                    importStarted = true
                    val result = importEntries(parsed, errors)

                    if (result.tracksImported > 0 || result.eventsCreated > 0 || result.duplicatesSkipped > 0) {
                        try {
                            EnrichmentWorker.schedulePostImportEnrichment(
                                appContext,
                                result.tracksImported.toLong(),
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to schedule post-import enrichment", e)
                        }
                    }

                    _importState.value = ImportState.Completed(result)
                    result
                } catch (e: CancellationException) {
                    _importState.value = ImportState.Idle
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Deezer import failed", e)
                    addCappedError(errors, userFacingError(e))
                    val result = ImportResult(0, 0, 0, 0, 0, 0, errors.toList())
                    _importState.value = ImportState.Error(errors.firstOrNull() ?: "Deezer import failed")
                    result
                } finally {
                    if (importStarted) {
                        try {
                            // A cancelled/partially-failed import may already have committed batches.
                            statsRepository.invalidateCache()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to invalidate stats after Deezer import", e)
                        }
                    }

                    tempFile?.let { file ->
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "Could not delete temporary Deezer import file")
                        }
                    }
                }
            }
        }

    private suspend fun importEntries(
        parsed: DeezerXlsxParser.ParseResult,
        errors: MutableList<String>,
    ): ImportResult {
        val trackCache = HashMap<String, TrackResolver.Resolution>()
        val metadataPrepared = HashMap<Long, PreparedMetadata>()
        val isrcIndex = HashMap<String, Long>()
        val ambiguousIsrcs = HashSet<String>()
        enrichedMetadataDao.getTrackIsrcRefs().forEach { ref ->
            val normalized = canonicalIsrc(ref.isrc)
            if (normalized.isBlank() || normalized in ambiguousIsrcs) return@forEach

            val existingTrackId = isrcIndex[normalized]
            when {
                existingTrackId == null -> isrcIndex[normalized] = ref.trackId
                existingTrackId != ref.trackId -> {
                    // Never pick an arbitrary winner when legacy data already contains
                    // the same ISRC on multiple tracks. Fall back to exact textual
                    // matching for that ISRC instead.
                    isrcIndex.remove(normalized)
                    ambiguousIsrcs.add(normalized)
                }
            }
        }
        val pendingEvents = ArrayList<ListeningEvent>(FLUSH_BATCH_SIZE)
        var tracksImported = 0
        var eventsCreated = 0
        var duplicatesSkipped = 0
        var shortPlaysSkipped = 0

        suspend fun flush() {
            if (pendingEvents.isEmpty()) return
            try {
                val result = listeningEventDao.insertAllBatchedWithDedup(pendingEvents)
                eventsCreated += result.inserted
                duplicatesSkipped += result.skipped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert Deezer event batch", e)
                addCappedError(errors, "A batch of listening events could not be imported")
            } finally {
                pendingEvents.clear()
            }
        }

        parsed.entries.forEachIndexed { index, entry ->
            if (index % 100 == 0) {
                coroutineContext.ensureActive()
                _importState.value = ImportState.Importing(
                    current = index,
                    total = parsed.entries.size,
                    tracksImported = tracksImported,
                    eventsCreated = eventsCreated,
                )
            }

            if (entry.msPlayed < MIN_MS_PLAYED_FOR_EVENT) {
                shortPlaysSkipped++
                return@forEachIndexed
            }

            try {
                val resolution = resolveTrack(entry, trackCache, isrcIndex)
                if (resolution.isNewTrack) {
                    tracksImported++
                    try {
                        artistLinkingService.linkArtistsForTrack(resolution.track)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to link artists for Deezer track " + resolution.trackId, e)
                    }
                }

                val prepared = metadataPrepared[resolution.trackId]
                val hasNewIsrc = prepared?.isrc == null && entry.isrc != null
                val hasNewAlbum = prepared?.album == null && entry.albumName != null
                if (prepared == null || hasNewIsrc || hasNewAlbum) {
                    try {
                        preserveDeezerMetadata(resolution.trackId, entry)
                        metadataPrepared[resolution.trackId] = PreparedMetadata(
                            isrc = prepared?.isrc ?: entry.isrc,
                            album = prepared?.album ?: entry.albumName,
                        )
                        entry.isrc?.let { isrcIndex.putIfAbsent(it, resolution.trackId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Metadata enrichment is secondary: never lose a valid listening
                        // event solely because ISRC/album persistence failed.
                        Log.w(TAG, "Failed to preserve Deezer metadata for track " + resolution.trackId, e)
                        addCappedError(errors, "Metadata for a Deezer track could not be saved")
                    }
                }

                val endTimestamp = entry.listenedAtMillis
                val startTimestamp = (endTimestamp - entry.msPlayed).coerceAtLeast(0L)
                val knownDurationMs = resolution.track.duration?.takeIf { it > 0L }
                val completionPercentage =
                    knownDurationMs?.let { duration ->
                        ((entry.msPlayed * 100L) / duration)
                            .coerceIn(0L, 100L)
                            .toInt()
                    } ?: DEFAULT_COMPLETION_PERCENTAGE
                pendingEvents.add(
                    ListeningEvent(
                        track_id = resolution.trackId,
                        timestamp = startTimestamp,
                        playDuration = entry.msPlayed,
                        completionPercentage = completionPercentage,
                        source = IMPORT_SOURCE,
                        wasSkipped = knownDurationMs != null && completionPercentage < 30,
                        isReplay = false,
                        estimatedDurationMs = knownDurationMs,
                        pauseCount = 0,
                        sessionId = null,
                        endTimestamp = endTimestamp,
                    ),
                )
                if (metadataPrepared.size > MAX_CACHE_SIZE) metadataPrepared.clear()
                if (pendingEvents.size >= FLUSH_BATCH_SIZE) flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import a Deezer history row", e)
                addCappedError(errors, "A listening-history row could not be imported")
            }
        }

        flush()
        _importState.value = ImportState.Importing(
            current = parsed.entries.size,
            total = parsed.entries.size,
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
        )

        return ImportResult(
            tracksImported = tracksImported,
            eventsCreated = eventsCreated,
            duplicatesSkipped = duplicatesSkipped,
            shortPlaysSkipped = shortPlaysSkipped,
            malformedRows = parsed.malformedRows,
            totalEntries = parsed.entries.size + parsed.malformedRows,
            errors = errors.toList(),
        )
    }

    private suspend fun resolveTrack(
        entry: DeezerXlsxParser.Entry,
        trackCache: MutableMap<String, TrackResolver.Resolution>,
        isrcIndex: MutableMap<String, Long>,
    ): TrackResolver.Resolution {
        val cacheKey = entry.isrc?.let { "isrc:" + it }
            ?: "meta:" + entry.trackName.lowercase() + "|" + entry.artistName.lowercase() + "|" +
                entry.albumName.orEmpty().lowercase()

        trackCache[cacheKey]?.let { return it }

        entry.isrc?.let { isrc ->
            // ISRC is authoritative. Prefer it over all textual matching.
            isrcIndex[isrc]?.let { trackId ->
                trackRepository.getById(trackId).first()?.let { track ->
                    val resolution = TrackResolver.Resolution(
                        trackId = track.id,
                        isNewTrack = false,
                        track = track,
                    )
                    cacheTrackResolution(cacheKey, resolution, trackCache)
                    return resolution
                }
            }

            // If Tempo does not know this ISRC yet, an exact title+artist match is
            // safe enough to backfill it. Do NOT use TrackResolver's fuzzy artist
            // containment here: an authoritative ISRC must never validate a fuzzy
            // textual match (e.g. "Queen" vs "Queen Latifah").
            trackRepository.findByTitleAndArtist(entry.trackName, entry.artistName)?.let { exact ->
                val exactIsrc = enrichedMetadataDao.forTrackSync(exact.id)?.isrc
                    ?.let(::canonicalIsrc)

                if (exactIsrc == null || exactIsrc == isrc) {
                    var track = exact
                    if (track.album.isNullOrBlank() && !entry.albumName.isNullOrBlank()) {
                        track = track.copy(album = entry.albumName)
                        trackRepository.update(track)
                    }
                    val resolution = TrackResolver.Resolution(
                        trackId = track.id,
                        isNewTrack = false,
                        track = track,
                    )
                    cacheTrackResolution(cacheKey, resolution, trackCache)
                    return resolution
                }

                // Same textual identity but a different authoritative ISRC:
                // keep the recordings separate.
                val resolution = createDeezerTrack(entry)
                cacheTrackResolution(cacheKey, resolution, trackCache)
                return resolution
            }

            // No authoritative or exact textual identity exists. Creating a fresh
            // row is safer than attaching this ISRC to a fuzzy candidate.
            val resolution = createDeezerTrack(entry)
            cacheTrackResolution(cacheKey, resolution, trackCache)
            return resolution
        }

        // Deezer rows without ISRC fall back to Tempo's normal textual resolver.
        val resolution = trackResolver.resolve(
            TrackResolver.Query(
                title = entry.trackName,
                artist = entry.artistName,
                album = entry.albumName,
            ),
        )

        cacheTrackResolution(cacheKey, resolution, trackCache)
        return resolution
    }

    private fun cacheTrackResolution(
        cacheKey: String,
        resolution: TrackResolver.Resolution,
        trackCache: MutableMap<String, TrackResolver.Resolution>,
    ) {
        trackCache[cacheKey] = resolution.copy(isNewTrack = false)
        if (trackCache.size > MAX_CACHE_SIZE) trackCache.clear()
    }

    private suspend fun createDeezerTrack(
        entry: DeezerXlsxParser.Entry,
    ): TrackResolver.Resolution {
        val track = Track(
            title = entry.trackName,
            artist = entry.artistName,
            album = entry.albumName,
            duration = null,
            albumArtUrl = null,
            spotifyId = null,
            youtubeId = null,
            musicbrainzId = null,
            primaryArtistId = null,
            contentType = "MUSIC",
        )
        val id = trackRepository.insert(track)
        return TrackResolver.Resolution(
            trackId = id,
            isNewTrack = true,
            track = track.copy(id = id),
        )
    }

    private data class PreparedMetadata(
        val isrc: String?,
        val album: String?,
    )

    private fun canonicalIsrc(value: String): String =
        value.trim().uppercase().replace("-", "").replace(" ", "")

    private suspend fun preserveDeezerMetadata(
        trackId: Long,
        entry: DeezerXlsxParser.Entry,
    ) {
        val existing = enrichedMetadataDao.forTrackSync(trackId)
        if (existing == null) {
            enrichedMetadataDao.upsert(
                EnrichedMetadata(
                    trackId = trackId,
                    albumTitle = entry.albumName,
                    artistName = entry.artistName,
                    isrc = entry.isrc,
                    enrichmentStatus = EnrichmentStatus.PENDING,
                    cacheTimestamp = System.currentTimeMillis(),
                ),
            )
            return
        }

        var updated = existing
        var changed = false
        if (updated.isrc.isNullOrBlank() && !entry.isrc.isNullOrBlank()) {
            updated = updated.copy(isrc = entry.isrc)
            changed = true
        }
        if (updated.albumTitle.isNullOrBlank() && !entry.albumName.isNullOrBlank()) {
            updated = updated.copy(albumTitle = entry.albumName)
            changed = true
        }
        if (updated.artistName.isNullOrBlank()) {
            updated = updated.copy(artistName = entry.artistName)
            changed = true
        }
        if (changed) enrichedMetadataDao.update(updated)
    }

    private suspend fun copyUriWithLimit(context: Context, uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open Deezer export")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FILE_SIZE_BYTES) {
                        throw IOException("Deezer export is larger than 500 MB")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun cleanupStaleTempFiles(cacheDir: File) {
        cacheDir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith(TEMP_FILE_PREFIX) &&
                    file.name.endsWith(".xlsx", ignoreCase = true)
            }
            ?.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "Could not delete stale Deezer import file")
                }
            }
    }

    private fun sanitizeDisplayName(value: String): String =
        value
            .replace(Regex("[\\r\\n\\t\\u0000-\\u001F\\u007F]"), " ")
            .trim()
            .take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "deezer-data.xlsx" }

    private fun getFileSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read Deezer export size", e)
        null
    }

    private fun getFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read Deezer export name", e)
        null
    }

    private fun userFacingError(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("10_listeningHistory", ignoreCase = true) ->
                "This file does not contain Deezer listening history (10_listeningHistory)"
            message.contains("No valid Deezer listening history entries", ignoreCase = true) ->
                "No Deezer listening-history entries were found in this export"
            message.contains("XLSX", ignoreCase = true) || error is java.util.zip.ZipException ->
                "The selected file is not a valid Deezer XLSX export"
            else -> "Deezer import failed"
        }
    }

    private fun addCappedError(errors: MutableList<String>, message: String) {
        if (errors.size < MAX_ERRORS) errors.add(message)
        else if (errors.size == MAX_ERRORS) errors.add("…and more errors")
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }
}
