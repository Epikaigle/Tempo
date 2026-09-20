package me.avinas.tempo.data.deezer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
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
) {
    companion object {
        private const val TAG = "DeezerDataImport"
        private const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024
        private const val MIN_MS_PLAYED_FOR_EVENT = 30_000L
        private const val FLUSH_BATCH_SIZE = 500
        private const val MAX_CACHE_SIZE = 50_000
        private const val MAX_ERRORS = 20
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
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

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    suspend fun importFromUri(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val fileName = getFileName(appContext, uri) ?: "deezer-data.xlsx"
        _importState.value = ImportState.Parsing(fileName)

        val errors = mutableListOf<String>()
        val declaredSize = getFileSize(appContext, uri)
        if (declaredSize != null && declaredSize > MAX_FILE_SIZE_BYTES) {
            val result = ImportResult(0, 0, 0, 0, 0, 0, listOf("Deezer export is larger than 500 MB"))
            _importState.value = ImportState.Error(result.errors.first())
            return@withContext result
        }

        val tempFile = File.createTempFile("tempo_deezer_", ".xlsx", appContext.cacheDir)
        try {
            copyUriWithLimit(appContext, uri, tempFile)
            val parsed = DeezerXlsxParser.parse(tempFile)
            if (parsed.entries.isEmpty()) {
                throw IllegalArgumentException("No valid Deezer listening history entries found")
            }
            val result = importEntries(parsed, errors)
            _importState.value = ImportState.Completed(result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Deezer import failed", e)
            addCappedError(errors, userFacingError(e))
            val result = ImportResult(0, 0, 0, 0, 0, 0, errors.toList())
            _importState.value = ImportState.Error(errors.firstOrNull() ?: "Deezer import failed")
            result
        } finally {
            if (!tempFile.delete()) {
                tempFile.deleteOnExit()
            }
        }
    }

    private suspend fun importEntries(
        parsed: DeezerXlsxParser.ParseResult,
        errors: MutableList<String>,
    ): ImportResult {
        val trackCache = HashMap<String, TrackResolver.Resolution>()
        val metadataPrepared = HashSet<Long>()
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
                val resolution = resolveTrack(entry, trackCache)
                if (resolution.isNewTrack) {
                    tracksImported++
                    runCatching { artistLinkingService.linkArtistsForTrack(resolution.track) }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to link artists for Deezer track " + resolution.trackId, error)
                        }
                }
                if (metadataPrepared.add(resolution.trackId)) {
                    preserveDeezerMetadata(resolution.trackId, entry)
                }

                val endTimestamp = entry.listenedAtMillis
                val startTimestamp = (endTimestamp - entry.msPlayed).coerceAtLeast(0L)
                pendingEvents.add(
                    ListeningEvent(
                        track_id = resolution.trackId,
                        timestamp = startTimestamp,
                        playDuration = entry.msPlayed,
                        completionPercentage = DEFAULT_COMPLETION_PERCENTAGE,
                        source = IMPORT_SOURCE,
                        wasSkipped = false,
                        isReplay = false,
                        estimatedDurationMs = null,
                        pauseCount = 0,
                        sessionId = null,
                        endTimestamp = endTimestamp,
                    ),
                )
                if (pendingEvents.size >= FLUSH_BATCH_SIZE) flush()
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
    ): TrackResolver.Resolution {
        val cacheKey = entry.isrc?.let { "isrc:" + it }
            ?: "meta:" + entry.trackName.lowercase() + "|" + entry.artistName.lowercase() + "|" +
                entry.albumName.orEmpty().lowercase()

        trackCache[cacheKey]?.let { return it }

        entry.isrc?.let { isrc ->
            enrichedMetadataDao.findByIsrc(isrc)?.let { metadata ->
                trackRepository.getById(metadata.trackId).first()?.let { track ->
                    val resolution = TrackResolver.Resolution(
                        trackId = track.id,
                        isNewTrack = false,
                        track = track,
                    )
                    trackCache[cacheKey] = resolution
                    return resolution
                }
            }
        }

        val resolution = trackResolver.resolve(
            TrackResolver.Query(
                title = entry.trackName,
                artist = entry.artistName,
                album = entry.albumName,
            ),
        )

        // Cache subsequent occurrences as existing so a new track is counted only once.
        trackCache[cacheKey] = resolution.copy(isNewTrack = false)
        if (trackCache.size > MAX_CACHE_SIZE) trackCache.clear()
        return resolution
    }

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

    private fun copyUriWithLimit(context: Context, uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open Deezer export")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
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
