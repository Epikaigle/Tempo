package me.avinas.tempo.data.musicolet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.worker.EnrichmentWorker
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Imports listening history from a Musicolet backup.
 *
 * Musicolet is an offline player, so it has no server to fetch history from. What it
 * has is a backup ZIP (Settings → Backup and restore → Backup now) holding two kinds
 * of Blowfish-encrypted SQLite database: `DB_SONGS_LOG` with one row per song and its
 * lifetime play count and last-played time, and one `PCs_M_<year>.<month>` database per
 * month of play counts ([MusicoletArchiveReader]).
 *
 * Musicolet never records a timestamp per play, so the timeline is reconstructed from
 * period counts ([MusicoletReconstruction]) and every event this service writes is
 * marked as such by its source, which ranks it below every measured source in
 * [me.avinas.tempo.data.local.SourceAuthority]. Re-importing the same backup is a
 * no-op: the reconstruction is deterministic, so the events carry identical
 * content fingerprints and [ListeningEventDao.insertAllBatchedWithDedup] drops them.
 */
@Singleton
class MusicoletImportService
    @Inject
    constructor(
        private val trackResolver: TrackResolver,
        private val listeningEventDao: ListeningEventDao,
        private val artistLinkingService: ArtistLinkingService,
        private val enrichedMetadataDao: EnrichedMetadataDao,
        private val statsRepository: StatsRepository,
        private val opener: MusicoletTableOpener,
    ) {
        companion object {
            private const val TAG = "MusicoletImport"

            /**
             * Musicolet stores counts, not plays, and the flag lives in the source
             * string so `SourceAuthority` can rank it as reconstructed history and
             * refuse to let it displace anything Tempo measured itself.
             */
            const val IMPORT_SOURCE = "in.krosbits.musicolet.import.reconstructed"

            /** Musicolet's own play threshold means a play happened; assume it completed. */
            private const val COMPLETION_PERCENTAGE = 80

            /** Used when the database has no usable duration for a song. */
            private const val DEFAULT_TRACK_DURATION_MS = 210_000L

            /** Longer than any song; a bogus duration must not bill hours of listening. */
            private const val MAX_TRACK_DURATION_MS = 6L * 3_600_000L

            private const val INSERT_BATCH = 500
            private const val CANCELLATION_CHECK_INTERVAL = 64
            private const val MAX_STORED_ERRORS = 50
            private const val MAX_STORED_WARNINGS = 20

            /** Bounds track resolution on a database that is not really a library. */
            private const val MAX_SONGS = 200_000

            private const val WORK_DIR_PREFIX = "musicolet_import_"
        }

        private val parser = MusicoletBackupParser(opener)

        private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
        val importState: StateFlow<ImportState> = _importState.asStateFlow()

        sealed class ImportState {
            object Idle : ImportState()

            data class Parsing(
                val fileName: String,
                val filesProcessed: Int,
                val totalFiles: Int,
            ) : ImportState()

            data class Importing(
                val current: Long,
                val total: Long,
                val tracksImported: Int,
                val eventsCreated: Int,
            ) : ImportState()

            data class Completed(
                val result: ImportResult,
            ) : ImportState()

            data class Error(
                val message: String,
            ) : ImportState()
        }

        data class ImportResult(
            /** Songs in the backup database. */
            val songsFound: Int,
            /** Songs that had any play to import. */
            val songsImported: Int,
            val tracksImported: Int,
            val eventsCreated: Int,
            val duplicatesSkipped: Int,
            val periodFilesRead: Int,
            val filesProcessed: Int,
            /** Plays Musicolet counted but never dated (older than its period files). */
            val undatedPlays: Long = 0,
            /** How many of those were placed anyway, at the start of the song's history. */
            val anchoredUndatedPlays: Long = 0,
            /** Plays added from an exact last-played time outside the reconstructed periods. */
            val lastPlayedAnchors: Long = 0,
            /** Plays that could not be placed without colliding with another. */
            val droppedPlays: Long = 0,
            /** Plays for songs that are no longer in the backup's song database. */
            val unmatchedPeriodPlays: Long = 0,
            /** True when the event ceiling was hit and later songs were left out. */
            val truncated: Boolean = false,
            val earliestPlayMs: Long? = null,
            val latestPlayMs: Long? = null,
            val errors: List<String> = emptyList(),
            val warnings: List<String> = emptyList(),
        ) {
            val isSuccess: Boolean get() = errors.isEmpty() || eventsCreated > 0
        }

        /**
         * Imports every Musicolet backup or `DB_SONGS_LOG` file in [uris].
         *
         * A bare database yields last-played times only — its period files live beside
         * it inside the backup — which the warnings say plainly rather than silently
         * importing a thin slice of history.
         */
        suspend fun importFromUris(
            context: Context,
            uris: List<Uri>,
            options: MusicoletImportOptions = MusicoletImportOptions(),
        ): ImportResult =
            withContext(Dispatchers.IO) {
                _importState.value = ImportState.Parsing("", 0, uris.size)

                val errors = mutableListOf<String>()
                var suppressedErrors = 0
                val warnings = mutableListOf<String>()
                var suppressedWarnings = 0

                fun addError(message: String) {
                    if (errors.size < MAX_STORED_ERRORS) errors.add(message) else suppressedErrors++
                }
                fun addWarning(message: String) {
                    if (warnings.size < MAX_STORED_WARNINGS) warnings.add(message) else suppressedWarnings++
                }

                // Decrypted databases are the listener's history in the clear, so a run that
                // was killed mid-import (or a process death WorkManager restarted) must not
                // leave them behind in the cache directory.
                cleanStaleWorkDirs(context.cacheDir)

                val workDir = File(context.cacheDir, "$WORK_DIR_PREFIX${System.currentTimeMillis()}")
                try {
                    val songs = mutableListOf<MusicoletSong>()
                    val periodPlays = mutableListOf<MusicoletPeriodPlays>()
                    var periodFilesRead = 0
                    var unmatchedPeriodPlays = 0L
                    var filesProcessed = 0

                    for ((index, uri) in uris.withIndex()) {
                        coroutineContext.ensureActive()
                        val fileName = displayName(context, uri) ?: "file_${index + 1}"
                        _importState.value = ImportState.Parsing(fileName, index, uris.size)

                        try {
                            val archive =
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    MusicoletArchiveReader.read(stream, fileName, workDir)
                                }
                            if (archive == null) {
                                addError("Could not open $fileName.")
                                continue
                            }

                            val parsed = parser.parse(archive)
                            parsed.warnings.forEach { addWarning(it) }
                            if (parsed.songs.isEmpty()) {
                                addError("No Musicolet songs found in $fileName.")
                                continue
                            }

                            // Songs from several picks are concatenated, so each file's
                            // song indices are offset into the combined list.
                            val offset = songs.size
                            songs += parsed.songs
                            parsed.periodPlays.forEach { periodPlays += it.copy(songIndex = it.songIndex + offset) }
                            periodFilesRead += parsed.periodFilesRead
                            unmatchedPeriodPlays += parsed.unmatchedPeriodPlays
                            filesProcessed++

                            if (songs.size > MAX_SONGS) {
                                addError("Stopped after $MAX_SONGS songs; that is larger than a real library.")
                                break
                            }
                        } catch (e: OutOfMemoryError) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read $fileName", e)
                            addError("Could not read $fileName: ${e.message}")
                        }
                    }

                    if (songs.isEmpty()) {
                        if (errors.isEmpty()) addError("No Musicolet listening data found.")
                        val result = ImportResult(
                            songsFound = 0,
                            songsImported = 0,
                            tracksImported = 0,
                            eventsCreated = 0,
                            duplicatesSkipped = 0,
                            periodFilesRead = 0,
                            filesProcessed = 0,
                            errors = errors,
                            warnings = warnings,
                        )
                        _importState.value = ImportState.Completed(result)
                        return@withContext result
                    }

                    if (periodFilesRead == 0) {
                        addWarning(
                            "This file has no per-month play counts, so only each song's " +
                                "last-played time could be imported. Import the whole backup ZIP " +
                                "from Musicolet's Backup and restore to bring the full history across.",
                        )
                    }

                    val plan =
                        MusicoletReconstruction.plan(
                            backup =
                                MusicoletParsedBackup(
                                    songs = songs,
                                    periodPlays = periodPlays,
                                    periodFilesRead = periodFilesRead,
                                    unmatchedPeriodPlays = unmatchedPeriodPlays,
                                    warnings = warnings,
                                ),
                            options = MusicoletReconstruction.Options(options.includeUndatedPlays),
                            nowMs = System.currentTimeMillis(),
                            zone = ZoneId.systemDefault(),
                        )

                    val imported = importPlannedPlays(context, songs, plan)

                    if (suppressedErrors > 0) errors.add("...and $suppressedErrors more errors omitted.")
                    if (suppressedWarnings > 0) warnings.add("...and $suppressedWarnings more notes omitted.")

                    val result =
                        ImportResult(
                            songsFound = songs.size,
                            songsImported = plan.songPlays.size,
                            tracksImported = imported.tracksImported,
                            eventsCreated = imported.eventsCreated,
                            duplicatesSkipped = imported.duplicatesSkipped,
                            periodFilesRead = periodFilesRead,
                            filesProcessed = filesProcessed,
                            undatedPlays = plan.undatedPlays,
                            anchoredUndatedPlays = plan.anchoredUndatedPlays,
                            lastPlayedAnchors = plan.lastPlayedAnchors,
                            droppedPlays = plan.droppedPlays,
                            unmatchedPeriodPlays = unmatchedPeriodPlays,
                            truncated = plan.truncated,
                            earliestPlayMs = imported.earliestPlayMs,
                            latestPlayMs = imported.latestPlayMs,
                            errors = errors,
                            warnings = warnings,
                        )

                    _importState.value = ImportState.Completed(result)
                    Log.i(
                        TAG,
                        "Import complete: ${result.eventsCreated} events from ${result.songsImported} songs " +
                            "(${result.duplicatesSkipped} duplicates, ${plan.undatedPlays} undated, " +
                            "${plan.droppedPlays} unplaceable)",
                    )
                    result
                } catch (e: OutOfMemoryError) {
                    val message = "Not enough memory to read this backup."
                    Log.e(TAG, message, e)
                    _importState.value = ImportState.Error(message)
                    throw e
                } finally {
                    // The decrypted databases are the listener's history in the clear;
                    // they never outlive the import that needed them.
                    deleteRecursively(workDir)
                }
            }

        private data class ImportedCounts(
            val tracksImported: Int,
            val eventsCreated: Int,
            val duplicatesSkipped: Int,
            val earliestPlayMs: Long?,
            val latestPlayMs: Long?,
        )

        /** Resolves each song to a track and writes its reconstructed plays. */
        private suspend fun importPlannedPlays(
            context: Context,
            songs: List<MusicoletSong>,
            plan: MusicoletReconstruction.Plan,
        ): ImportedCounts {
            var tracksImported = 0
            var eventsCreated = 0
            var duplicatesSkipped = 0
            var earliest: Long? = null
            var latest: Long? = null

            val newTrackIds = LinkedHashSet<Long>()
            val newTracks = ArrayList<Pair<Long, MusicoletSong>>()
            val trackCache = HashMap<String, Long>()
            val batch = ArrayList<ListeningEvent>(INSERT_BATCH)
            val total = plan.totalPlays
            var processed = 0L

            suspend fun flush() {
                if (batch.isEmpty()) return
                val pending = batch.toList()
                batch.clear()
                try {
                    val insert = listeningEventDao.insertAllBatchedWithDedup(pending)
                    eventsCreated += insert.inserted
                    duplicatesSkipped += insert.skipped
                } catch (e: Exception) {
                    // One poisoned batch must not lose the rest of the import; the DAO's
                    // own dedup is bypassed here, so re-check the tolerance window per row.
                    Log.e(TAG, "Batch insert failed, falling back to individual inserts", e)
                    for (event in pending) {
                        try {
                            val near =
                                listeningEventDao.countEventsNearTimestamp(
                                    event.track_id,
                                    event.timestamp - ListeningEventDao.DUPLICATE_TOLERANCE_MS,
                                    event.timestamp + ListeningEventDao.DUPLICATE_TOLERANCE_MS,
                                )
                            if (near == 0) {
                                listeningEventDao.insert(event)
                                eventsCreated++
                            } else {
                                duplicatesSkipped++
                            }
                        } catch (e2: Exception) {
                            Log.w(TAG, "Failed to insert event for track ${event.track_id}", e2)
                        }
                    }
                }
            }

            for ((position, songPlays) in plan.songPlays.withIndex()) {
                if (position % CANCELLATION_CHECK_INTERVAL == 0) {
                    coroutineContext.ensureActive()
                    _importState.value =
                        ImportState.Importing(processed, total, tracksImported, eventsCreated)
                }

                val song = songs[songPlays.songIndex]
                val cacheKey = "${song.title}\u0000${song.artist}"
                val trackId =
                    trackCache[cacheKey]
                        ?: run {
                            val resolution =
                                trackResolver.resolve(
                                    TrackResolver.Query(
                                        title = song.title,
                                        artist = song.artist,
                                        album = song.album,
                                        duration = song.durationMs,
                                    ),
                                )
                            if (resolution.isNewTrack) {
                                tracksImported++
                                newTrackIds.add(resolution.trackId)
                                newTracks.add(resolution.trackId to song)
                                try {
                                    artistLinkingService.linkArtistsForTrack(resolution.track)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to link artists for track ${resolution.trackId}", e)
                                }
                            }
                            trackCache[cacheKey] = resolution.trackId
                            resolution.trackId
                        }

                val duration = playDurationOf(song)
                for (timestamp in songPlays.timestamps) {
                    batch.add(buildEvent(trackId, timestamp, duration))
                    if (timestamp < (earliest ?: Long.MAX_VALUE)) earliest = timestamp
                    if (timestamp > (latest ?: Long.MIN_VALUE)) latest = timestamp
                    if (batch.size >= INSERT_BATCH) flush()
                }
                processed += songPlays.timestamps.size
            }
            flush()

            if (eventsCreated > 0) {
                try {
                    statsRepository.invalidateCache()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to invalidate stats cache after import", e)
                }
            }

            for ((trackId, song) in newTracks) {
                createEnrichedMetadata(trackId, song)
            }
            if (newTrackIds.isNotEmpty()) {
                try {
                    EnrichmentWorker.schedulePostImportEnrichment(context, newTrackIds.size.toLong())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to schedule post-import enrichment", e)
                }
            }

            return ImportedCounts(tracksImported, eventsCreated, duplicatesSkipped, earliest, latest)
        }

        private fun buildEvent(
            trackId: Long,
            timestamp: Long,
            duration: Long,
        ): ListeningEvent =
            ListeningEvent(
                track_id = trackId,
                timestamp = timestamp,
                playDuration = duration,
                completionPercentage = COMPLETION_PERCENTAGE,
                source = IMPORT_SOURCE,
                wasSkipped = false,
                isReplay = false,
                estimatedDurationMs = duration,
                pauseCount = 0,
                sessionId = null,
                endTimestamp = timestamp + duration,
            )

        private fun playDurationOf(song: MusicoletSong): Long =
            song.durationMs?.takeIf { it in 1..MAX_TRACK_DURATION_MS } ?: DEFAULT_TRACK_DURATION_MS

        private suspend fun createEnrichedMetadata(
            trackId: Long,
            song: MusicoletSong,
        ) {
            try {
                enrichedMetadataDao.upsert(
                    EnrichedMetadata(
                        trackId = trackId,
                        spotifyId = null,
                        artistName = song.artist,
                        albumTitle = song.album,
                        enrichmentStatus = EnrichmentStatus.PENDING,
                        cacheTimestamp = System.currentTimeMillis(),
                        lastEnrichmentAttempt = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create enrichment row for track $trackId", e)
            }
        }

        fun resetState() {
            _importState.value = ImportState.Idle
        }

        private fun displayName(
            context: Context,
            uri: Uri,
        ): String? =
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment
            } catch (e: Exception) {
                uri.lastPathSegment
            }

        /**
         * Removes import directories left by an earlier run — one killed mid-import, or a
         * process death WorkManager restarted — so decrypted history does not sit in the
         * cache directory indefinitely. At most one import runs at a time (the worker
         * enqueues under a unique name with `ExistingWorkPolicy.KEEP`), so any directory
         * that exists before this run begins is a leftover and never in use.
         */
        private fun cleanStaleWorkDirs(cacheDir: File) {
            try {
                cacheDir.listFiles()?.forEach { entry ->
                    if (entry.isDirectory && entry.name.startsWith(WORK_DIR_PREFIX)) {
                        deleteRecursively(entry)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up stale import directories", e)
            }
        }

        private fun deleteRecursively(dir: File) {
            try {
                dir.walkBottomUp().forEach { if (it.isFile) it.delete() else it.delete() }
                dir.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up import directory ${dir.name}", e)
            }
        }
    }

/**
 * What the listener chose on the import screen.
 *
 * Musicolet counts plays from before it started keeping per-month files but has no date
 * for them. [includeUndatedPlays] decides whether those are placed at the start of the
 * song's known history — which makes lifetime totals match Musicolet, at the cost of
 * dating plays earlier than they happened — or left out and merely reported.
 */
data class MusicoletImportOptions(
    val includeUndatedPlays: Boolean = false,
)
