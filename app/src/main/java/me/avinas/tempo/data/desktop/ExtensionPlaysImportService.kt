package me.avinas.tempo.data.desktop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.importexport.ImportExportOperationGate
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.RefreshCoordinator
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.worker.EnrichmentWorker
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Imports plays from a JSON file exported by the Tempo Stats browser extension.
 *
 * Events are recorded with `desktop:<sourceApp>` to match the live LAN sync path
 * ([DesktopPlayIngestionService]). Re-imports are deduplicated by fingerprint
 * in [ListeningEventDao.insertAllBatchedWithDedup].
 */
@Singleton
class ExtensionPlaysImportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val trackResolver: TrackResolver,
    private val listeningEventDao: ListeningEventDao,
    private val artistLinkingService: ArtistLinkingService,
    private val refreshCoordinator: RefreshCoordinator,
) {
    companion object {
        private const val TAG = "ExtensionPlaysImport"

        private const val DESKTOP_SOURCE_PREFIX = "desktop:"
        private const val FALLBACK_SOURCE_APP = "Browser Extension"
        private const val DEFAULT_CONTENT_TYPE = "MUSIC"
        private const val MAX_DISPLAY_NAME_CHARS = 120

        // Floor matching the live desktop path to filter transient clicks.
        private const val MIN_LISTEN_MS = 5_000L
        private const val DEFAULT_COMPLETION_PERCENT = 90
        private const val SKIP_COMPLETION_PERCENT = 30

        private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_FILES_PER_IMPORT = 20
        private const val MAX_ERRORS = 20
        private const val MAX_TRACK_CACHE = 20_000

        private const val EVENT_FLUSH_SIZE = 500
        private const val PROGRESS_STEP = 100

        // Threshold above which enrichment is handed to the background post-import worker.
        private const val INLINE_ENRICH_LIMIT = 25
    }

    private val operationGate = ImportExportOperationGate()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    sealed class ImportState {
        data object Idle : ImportState()

        data class Parsing(
            val fileName: String,
            val fileIndex: Int,
            val fileCount: Int,
        ) : ImportState()

        data class Importing(
            val processed: Int,
            val imported: Int,
        ) : ImportState()

        data class Completed(val result: ImportResult) : ImportState()

        data class Error(val message: String) : ImportState()
    }

    data class ImportResult(
        val recordsRead: Int,
        val eventsImported: Int,
        val tracksCreated: Int,
        val duplicatesSkipped: Int,
        val tooShortSkipped: Int,
        val malformedSkipped: Int,
        val filesProcessed: Int,
        val filesSeen: Int,
        val truncated: Boolean,
        val errors: List<String>,
    ) {
        /** True when the document was understood — a file that is all duplicates still counts. */
        val isSuccess: Boolean get() = recordsRead > 0
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }

    /**
     * Import every play found in [uris] (one file per extension export). Never throws for
     * file content — failures are reported through the returned [ImportResult] and
     * [ImportState.Error] instead.
     */
    suspend fun importFromUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val lease = operationGate.tryAcquire()
        if (lease == null) {
            val message = "Another import or backup is already running"
            _importState.value = ImportState.Error(message)
            return@withContext emptyResult(filesSeen = uris.size, error = message)
        }

        try {
            runImport(uris)
        } finally {
            lease.release()
        }
    }

    private suspend fun runImport(uris: List<Uri>): ImportResult {
        _importState.value = ImportState.Parsing("", 0, uris.size)

        val errors = mutableListOf<String>()
        if (uris.isEmpty()) {
            val message = "No files selected"
            _importState.value = ImportState.Error(message)
            return emptyResult(filesSeen = 0, error = message)
        }
        if (uris.size > MAX_FILES_PER_IMPORT) {
            addCappedError(
                errors,
                "Too many files selected (${uris.size}); reading the first $MAX_FILES_PER_IMPORT",
            )
        }
        val selected = uris.take(MAX_FILES_PER_IMPORT)

        var filesProcessed = 0
        var recordsRead = 0
        var eventsImported = 0
        var tracksCreated = 0
        var duplicates = 0
        var tooShort = 0
        var malformed = 0
        var unresolved = 0
        var truncated = false

        // Buffered across files to batch database writes.
        val pendingBuffer = EventBuffer(EVENT_FLUSH_SIZE)
        val newTrackIds = LinkedHashSet<Long>()
        // Deferred until linkDeferredTracks() to avoid junction writes while streaming.
        val newTracks = LinkedHashMap<Long, Track>()
        val trackCache = HashMap<String, Long>(1024)
        val parser = ExtensionPlaysJsonParser()

            suspend fun flush(final: Boolean = false) {
                val batch = pendingBuffer.swap()
                if (batch.isEmpty()) return
                try {
                    val inserted = listeningEventDao.insertAllBatchedWithDedup(batch)
                    eventsImported += inserted.inserted
                    duplicates += inserted.skipped
                } catch (e: CancellationException) {
                    // Re-queue unflushed events on cancellation unless running the terminal flush.
                    if (!final) pendingBuffer.prepend(batch)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Batch insert failed (${batch.size} events)", e)
                    addCappedError(errors, "Some plays could not be saved")
                }
            }

        for ((index, uri) in selected.withIndex()) {
            coroutineContext.ensureActive()

            val (fileName, declaredSize) = documentMeta(uri, index)

            _importState.value = ImportState.Parsing(fileName, index, selected.size)

            try {
                if (declaredSize != null && declaredSize > MAX_FILE_BYTES) {
                    addCappedError(errors, "Skipped ${declaredSize / 1_048_576}MB file: $fileName")
                    continue
                }

                val opened = context.contentResolver.openInputStream(uri)
                if (opened == null) {
                    addCappedError(errors, "Could not open: $fileName")
                    continue
                }

                val summary = opened.use { raw ->
                    ByteCappedInputStream(raw, MAX_FILE_BYTES).use { capped ->
                        parser.parse(capped) { record ->
                            recordsRead++
                            if (recordsRead % PROGRESS_STEP == 0) {
                                coroutineContext.ensureActive()
                                _importState.value = ImportState.Importing(recordsRead, eventsImported)
                            }

                            val effectiveDuration = maxOf(record.durationMs, record.listenedMs)
                            if (effectiveDuration < MIN_LISTEN_MS) {
                                tooShort++
                            } else {
                                val resolved = resolveEvent(record, trackCache, newTrackIds, newTracks)
                                if (resolved == null) {
                                    unresolved++
                                } else {
                                    if (resolved.isNewTrack) tracksCreated++
                                    pendingBuffer.add(resolved.event)
                                    if (pendingBuffer.size >= EVENT_FLUSH_SIZE) flush()
                                }
                            }
                        }
                    }
                }

                malformed += summary.malformed
                if (summary.truncated) {
                    truncated = true
                    addCappedError(errors, "$fileName holds more plays than one import can read")
                }
                filesProcessed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read $fileName", e)
                addCappedError(errors, "Could not read $fileName")
            }
        }

        flush()

        linkDeferredTracks(newTracks.values)

        if (unresolved > 0) {
            addCappedError(errors, "$unresolved plays could not be saved")
        }

        if (recordsRead == 0 && errors.isEmpty()) {
            addCappedError(errors, "No plays found in the selected files")
        }

        finishSideEffects(newTrackIds, eventsImported)

        val result = ImportResult(
            recordsRead = recordsRead,
            eventsImported = eventsImported,
            tracksCreated = tracksCreated,
            duplicatesSkipped = duplicates,
            tooShortSkipped = tooShort,
            malformedSkipped = malformed,
            filesProcessed = filesProcessed,
            filesSeen = selected.size,
            truncated = truncated,
            errors = errors.toList(),
        )
        _importState.value =
            if (result.isSuccess) {
                ImportState.Completed(result)
            } else {
                ImportState.Error(errors.firstOrNull() ?: "Import failed")
            }
        Log.i(
            TAG,
            "Import finished: $eventsImported events, $tracksCreated tracks, $duplicates duplicates, " +
                "$tooShort short, $malformed malformed from $filesProcessed/${selected.size} files",
        )
        return result
    }

    /**
     * Resolves the record to a Track entity and constructs a [ListeningEvent].
     * Artist linking is deferred to [linkDeferredTracks].
     */
    private suspend fun resolveEvent(
        record: ExtensionPlaysJsonParser.Record,
        trackCache: MutableMap<String, Long>,
        newTrackIds: MutableSet<Long>,
        newTracks: MutableMap<Long, Track>,
    ): ResolvedEvent? {
        val cacheKey = trackCacheKey(record)

        trackCache[cacheKey]?.let { return ResolvedEvent(buildEvent(it, record), isNewTrack = false) }

        val resolution = try {
            trackResolver.resolve(
                TrackResolver.Query(
                    title = record.title,
                    artist = record.artist,
                    album = record.album,
                    duration = record.durationMs.takeIf { it > 0L },
                ),
                contentType = record.contentType ?: DEFAULT_CONTENT_TYPE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve a track for an imported play", e)
            return null
        }

        if (trackCache.size >= MAX_TRACK_CACHE) trackCache.clear()
        trackCache[cacheKey] = resolution.trackId

        if (resolution.isNewTrack) {
            newTrackIds.add(resolution.trackId)
            newTracks.putIfAbsent(resolution.trackId, resolution.track)
        }

        return ResolvedEvent(buildEvent(resolution.trackId, record), isNewTrack = resolution.isNewTrack)
    }

    /**
     * Links artists for new tracks created during the import.
     */
    private suspend fun linkDeferredTracks(tracks: Collection<Track>) {
        for (track in tracks) {
            coroutineContext.ensureActive()
            try {
                artistLinkingService.linkArtistsForTrack(track)
            } catch (e: Exception) {
                Log.w(TAG, "Could not link artists for track ${track.id}", e)
            }
        }
    }

    /**
     * Constructs a [ListeningEvent] matching the live LAN ingestion schema.
     */
    private fun buildEvent(trackId: Long, record: ExtensionPlaysJsonParser.Record): ListeningEvent {
        val completion =
            record.completionPercentage
                ?: if (record.durationMs > 0L) {
                    ((record.listenedMs.toDouble() / record.durationMs) * 100).toInt().coerceIn(0, 100)
                } else {
                    DEFAULT_COMPLETION_PERCENT
                }

        return ListeningEvent(
            track_id = trackId,
            timestamp = record.timestampUtc,
            playDuration = record.listenedMs.takeIf { it > 0L } ?: record.durationMs,
            completionPercentage = completion,
            source = sourceFor(record.sourceApp),
            wasSkipped = record.skipped ?: (completion < SKIP_COMPLETION_PERCENT),
            isReplay = record.replayCount > 0,
            estimatedDurationMs = record.durationMs.takeIf { it > 0L },
            pauseCount = record.pauseCount,
            sessionId = record.sessionId,
            totalPauseDurationMs = record.totalPauseDurationMs,
            seekCount = record.seekCount,
            positionUpdatesCount = record.positionUpdatesCount,
            // 0 indicates muted playback (0 volume), null indicates unmuted or unspecified.
            volumeLevel = if (record.isMuted) 0 else null,
        )
    }

    private fun sourceFor(sourceApp: String?): String {
        val app = sourceApp
            ?.trim()
            .orEmpty()
            .removePrefix(DESKTOP_SOURCE_PREFIX)
            .takeIf { it.isNotEmpty() }
            ?: FALLBACK_SOURCE_APP
        return DESKTOP_SOURCE_PREFIX + app
    }

    /**
     * Builds a delimited lowercase key (title\0artist\0album) for track lookup.
     */
    private fun trackCacheKey(record: ExtensionPlaysJsonParser.Record): String {
        val titleLower = record.title.lowercase()
        val artistLower = record.artist.lowercase()
        val album = record.album
        return buildString(titleLower.length + artistLower.length + (album?.length ?: 0) + 2) {
            append(titleLower)
            append('\u0000')
            append(artistLower)
            append('\u0000')
            if (album != null) append(album.lowercase())
        }
    }

    /**
     * Schedules enrichment and triggers UI refresh after import completes.
     */
    private fun finishSideEffects(newTrackIds: Set<Long>, eventsImported: Int) {
        if (newTrackIds.isNotEmpty()) {
            try {
                if (newTrackIds.size <= INLINE_ENRICH_LIMIT) {
                    newTrackIds.forEach { EnrichmentWorker.enqueueImmediate(context, it) }
                } else {
                    EnrichmentWorker.schedulePostImportEnrichment(context, newTrackIds.size.toLong())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not queue enrichment for imported tracks", e)
            }
        }

        if (eventsImported > 0) {
            // Notify observers to refresh listening history in the UI.
            refreshCoordinator.notifyNewTrackRecorded()
        }
    }

    private fun addCappedError(errors: MutableList<String>, message: String) {
        // Cap reported errors to prevent unbounded list growth.
        if (errors.size < MAX_ERRORS) errors.add(message)
        else if (errors.size == MAX_ERRORS) errors.add("…and more (capped at $MAX_ERRORS)")
    }

    private fun emptyResult(filesSeen: Int, error: String) = ImportResult(
        recordsRead = 0,
        eventsImported = 0,
        tracksCreated = 0,
        duplicatesSkipped = 0,
        tooShortSkipped = 0,
        malformedSkipped = 0,
        filesProcessed = 0,
        filesSeen = filesSeen,
        truncated = false,
        errors = listOf(error),
    )

    /**
     * Queries display name and declared byte size from the document provider.
     */
    private fun documentMeta(uri: Uri, fallbackIndex: Int): Pair<String, Long?> {
        val fallback = "file_${fallbackIndex + 1}" to null
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return fallback
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size =
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    (name?.take(MAX_DISPLAY_NAME_CHARS) ?: fallback.first) to size
                } ?: fallback
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the metadata of a selected file", e)
            fallback
        }
    }

    /** Stream filter enforcing a maximum byte limit. */
    private class ByteCappedInputStream(stream: InputStream, private val maxBytes: Long) :
        FilterInputStream(stream) {
        private var bytesRead = 0L

        private fun count(read: Int) {
            if (read <= 0) return
            bytesRead += read
            if (bytesRead > maxBytes) throw IOException("File exceeds ${maxBytes / 1_048_576}MB limit")
        }

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
    }

    private data class ResolvedEvent(val event: ListeningEvent, val isNewTrack: Boolean)

    /**
     * Staging buffer for batch inserting listening events.
     */
    private class EventBuffer(private val capacity: Int) {
        private var pending = ArrayList<ListeningEvent>(capacity)

        fun add(event: ListeningEvent) {
            pending.add(event)
        }

        val size: Int get() = pending.size

        fun isEmpty(): Boolean = pending.isEmpty()

        fun swap(): ArrayList<ListeningEvent> {
            val batch = pending
            pending = ArrayList(capacity)
            return batch
        }

        fun prepend(batch: ArrayList<ListeningEvent>) {
            pending.addAll(0, batch)
        }
    }
}




