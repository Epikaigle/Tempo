package me.avinas.tempo.data.deezer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import me.avinas.tempo.data.local.dao.TrackArtistDao
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.ArtistRole
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.local.entities.TrackArtist
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackResolver
import me.avinas.tempo.utils.ArtistParser
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
    private val trackArtistDao: TrackArtistDao,
    private val artistLinkingService: ArtistLinkingService,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    private val trackRepository: TrackRepository,
    private val statsRepository: StatsRepository,
) {
    companion object {
        private const val TAG = "DeezerDataImport"
        private const val MAX_FILE_SIZE_BYTES = 256L * 1024 * 1024
        private const val SKIP_PLAY_DURATION_MS = 30_000L
        private const val FLUSH_BATCH_SIZE = 500
        private const val MAX_CACHE_SIZE = 50_000
        private const val MAX_ERRORS = 20
        private const val DEFAULT_COMPLETION_PERCENTAGE = 80
        private const val TEMP_FILE_PREFIX = "tempo_deezer_"
        private const val MAX_DISPLAY_NAME_LENGTH = 200
        private val ISRC_REGEX = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
        const val IMPORT_SOURCE = "com.deezer.music.import.xlsx"

        internal fun deezerArtistCredits(value: String): List<String> {
            val cleaned = value.trim()
            if (cleaned.isEmpty()) return emptyList()

            // In the official export, Deezer separates distinct artist credits
            // with commas. Characters such as '&', '/', '+' and ' x ' can belong
            // to one Deezer artist entity and must not be split further.
            if (!cleaned.contains(',')) return listOf(cleaned)

            // Preserve known comma-containing artist names already recognized by
            // Tempo (for example names where the comma is part of the stage name).
            val wholeParsed = ArtistParser.getAllArtists(cleaned)
            if (
                wholeParsed.size == 1 &&
                ArtistParser.isStrictSameArtist(wholeParsed.single(), cleaned)
            ) {
                return listOf(cleaned)
            }

            return cleaned
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { ArtistParser.normalizeForSearch(it) }
        }

        internal fun albumsCompatibleForIsrcCandidate(
            existingAlbum: String?,
            incomingAlbum: String?,
        ): Boolean {
            val existing = existingAlbum?.trim()?.takeIf { it.isNotEmpty() }
            val incoming = incomingAlbum?.trim()?.takeIf { it.isNotEmpty() }
            return existing == null || incoming == null || existing.equals(incoming, ignoreCase = true)
        }

        internal fun shouldImportDeezerPlay(
            msPlayed: Long,
            minimumPlayDurationMs: Long = TrackingRulesPreferences.DEFAULT_MIN_PLAY_DURATION_MS,
        ): Boolean = msPlayed >= minimumPlayDurationMs

        internal fun isDeezerSkip(
            msPlayed: Long,
            completionPercentage: Int,
        ): Boolean =
            msPlayed < SKIP_PLAY_DURATION_MS || completionPercentage < 30

        internal fun findIncomingAmbiguousIsrcs(
            entries: List<DeezerXlsxParser.Entry>,
            minimumPlayDurationMs: Long = TrackingRulesPreferences.DEFAULT_MIN_PLAY_DURATION_MS,
            cancellationCheck: (() -> Unit)? = null,
        ): Set<String> {
            val knownArtists = HashMap<String, MutableList<String>>()
            val ambiguous = HashSet<String>()

            entries.forEachIndexed { index, entry ->
                if (index % 256 == 0) cancellationCheck?.invoke()

                // Rows that cannot produce a listening event under Tempo's current
                // minimum-play rule must not weaken an otherwise authoritative ISRC.
                // Short plays that do meet the minimum are retained as skips, so they
                // still participate in identity resolution.
                if (!shouldImportDeezerPlay(entry.msPlayed, minimumPlayDurationMs)) {
                    return@forEachIndexed
                }

                val isrc = entry.isrc ?: return@forEachIndexed
                if (isrc in ambiguous) return@forEachIndexed

                val artists =
                    deezerArtistCredits(entry.artistName)
                        .filterNot { artist ->
                            ArtistParser.isUnknownArtist(artist) ||
                                ArtistParser.isPlaceholderArtistName(artist)
                        }
                if (artists.isEmpty()) return@forEachIndexed

                val previous = knownArtists[isrc]
                if (previous == null) {
                    knownArtists[isrc] = artists.toMutableList()
                    return@forEachIndexed
                }

                val sharesStrictArtist =
                    previous.any { previousArtist ->
                        artists.any { incomingArtist ->
                            ArtistParser.isStrictSameArtist(previousArtist, incomingArtist)
                        }
                    }
                if (!sharesStrictArtist) {
                    ambiguous.add(isrc)
                    return@forEachIndexed
                }

                artists.forEach { incomingArtist ->
                    if (previous.none { ArtistParser.isStrictSameArtist(it, incomingArtist) }) {
                        previous.add(incomingArtist)
                    }
                }
            }

            return ambiguous
        }
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
                            listOf("Deezer export is larger than 256 MB"),
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
                    val minimumPlayDurationMs =
                        TrackingRulesPreferences(appContext).minimumPlayDurationMs
                    val result = importEntries(parsed, errors, minimumPlayDurationMs)

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
        minimumPlayDurationMs: Long,
    ): ImportResult {
        val trackCache = HashMap<String, TrackResolver.Resolution>()
        val metadataPrepared = HashMap<Long, PreparedMetadata>()
        val artistCreditsPrepared = HashSet<String>()
        val createdTrackIds = LinkedHashSet<Long>()
        val isrcIndex = HashMap<String, Long>()
        val importContext = coroutineContext
        val ambiguousIsrcs =
            findIncomingAmbiguousIsrcs(
                parsed.entries,
                minimumPlayDurationMs = minimumPlayDurationMs,
            ) {
                importContext.ensureActive()
            }.toHashSet()
        enrichedMetadataDao.getTrackIsrcRefs().forEach { ref ->
            val normalized = canonicalIsrc(ref.isrc) ?: return@forEach
            if (normalized in ambiguousIsrcs) return@forEach

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

        try {
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
    
                if (!shouldImportDeezerPlay(entry.msPlayed, minimumPlayDurationMs)) {
                    shortPlaysSkipped++
                    return@forEachIndexed
                }
    
                try {
                    val resolution = resolveTrack(entry, trackCache, isrcIndex, ambiguousIsrcs)
                    if (resolution.isNewTrack) {
                        createdTrackIds.add(resolution.trackId)
                        tracksImported++
                        try {
                            linkDeezerArtistsForTrack(resolution.track)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to link artists for Deezer track " + resolution.trackId, e)
                        }
                    }
    
                    try {
                        deezerArtistCredits(entry.artistName).forEachIndexed { creditIndex, creditedArtist ->
                            preserveAdditionalDeezerArtistCredit(
                                resolution.trackId,
                                creditedArtist,
                                isPrimaryCredit = creditIndex == 0,
                                artistCreditsPrepared,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to preserve an additional Deezer artist credit", e)
                        addCappedError(errors, "An additional artist credit could not be saved")
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
                            entry.isrc
                                ?.takeIf { it !in ambiguousIsrcs }
                                ?.let { isrcIndex.putIfAbsent(it, resolution.trackId) }
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
                            wasSkipped = isDeezerSkip(entry.msPlayed, completionPercentage),
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
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cleanupOrphanedCreatedTracks(createdTrackIds)
            }
            throw e
        }

        if (errors.isNotEmpty() && createdTrackIds.isNotEmpty()) {
            val removed = cleanupOrphanedCreatedTracks(createdTrackIds)
            tracksImported = (tracksImported - removed).coerceAtLeast(0)
        }
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
        ambiguousIsrcs: Set<String>,
    ): TrackResolver.Resolution {
        val cacheKey =
            entry.isrc
                ?.takeIf { it !in ambiguousIsrcs }
                ?.let { "isrc:" + it }
                ?: "meta:" + entry.trackName.lowercase() + "|" + entry.artistName.lowercase() + "|" +
                    entry.albumName.orEmpty().lowercase()

        trackCache[cacheKey]?.let { return it }

        entry.isrc?.let { isrc ->
            // A non-ambiguous validated ISRC is authoritative. Prefer it over
            // all textual matching whenever it identifies one existing track.
            isrcIndex[isrc]
                ?.takeIf { isrc !in ambiguousIsrcs }
                ?.let { trackId ->
                    trackRepository.getById(trackId).first()?.let { existingTrack ->
                        var track = promoteUnknownArtistFromDeezer(existingTrack, entry.artistName)
                        track = fillMissingAlbumFromDeezer(track, entry.albumName)
                        val resolution = TrackResolver.Resolution(
                            trackId = track.id,
                            isNewTrack = false,
                            track = track,
                        )
                        cacheTrackResolution(cacheKey, resolution, trackCache)
                        return resolution
                    }
                }

            // No indexed ISRC match exists. Inspect every track with the exact
            // same title instead of accepting TrackDao's arbitrary LIMIT 1 result:
            // Tempo can legitimately contain several versions with the same title/artist.
            val sameTitleCandidates = trackRepository.findCandidatesByTitle(entry.trackName)
            val compatibleCandidates = ArrayList<Track>()
            for (candidate in sameTitleCandidates) {
                val candidateIsrc =
                    enrichedMetadataDao.forTrackSync(candidate.id)?.isrc
                        ?.let(::canonicalIsrc)
                if (candidateIsrc == null || candidateIsrc == isrc) {
                    compatibleCandidates.add(candidate)
                }
            }

            val incomingArtists = deezerArtistCredits(entry.artistName)

            fun chooseByAlbum(candidates: List<Track>): Track? {
                if (candidates.isEmpty()) return null

                if (candidates.size == 1) {
                    return candidates.single()
                        .takeIf { candidate ->
                            albumsCompatibleForIsrcCandidate(candidate.album, entry.albumName)
                        }
                }

                val album = entry.albumName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                return candidates
                    .filter { candidate ->
                        candidate.album?.trim()?.equals(album, ignoreCase = true) == true
                    }
                    .singleOrNull()
            }

            // First prefer the complete artist string when it is strictly the same.
            val exactArtistCandidates =
                compatibleCandidates.filter { candidate ->
                    ArtistParser.isStrictSameArtist(candidate.artist, entry.artistName)
                }
            val exactArtistMatch = chooseByAlbum(exactArtistCandidates)
            if (exactArtistMatch != null) {
                val track = fillMissingAlbumFromDeezer(exactArtistMatch, entry.albumName)
                val resolution = TrackResolver.Resolution(
                    trackId = track.id,
                    isNewTrack = false,
                    track = track,
                )
                cacheTrackResolution(cacheKey, resolution, trackCache)
                return resolution
            }

            // A legacy placeholder with the exact title is safe to promote only
            // when album metadata does not contradict Deezer. This avoids leaving
            // an "Unknown Artist" duplicate solely because the old row had no ISRC.
            val placeholderCandidates =
                compatibleCandidates.filter { candidate ->
                    ArtistParser.isUnknownArtist(candidate.artist) ||
                        ArtistParser.isPlaceholderArtistName(candidate.artist)
                }
            val placeholderMatch = chooseByAlbum(placeholderCandidates)
            if (placeholderMatch != null) {
                var track = promoteUnknownArtistFromDeezer(placeholderMatch, entry.artistName)
                track = fillMissingAlbumFromDeezer(track, entry.albumName)
                val resolution = TrackResolver.Resolution(
                    trackId = track.id,
                    isNewTrack = false,
                    track = track,
                )
                cacheTrackResolution(cacheKey, resolution, trackCache)
                return resolution
            }

            // Deezer may expose one credited artist while another source stores the
            // complete collaboration string. Match individual artists strictly —
            // never by substring/fuzzy containment.
            val individualArtistCandidates =
                compatibleCandidates.filter { candidate ->
                    comparableArtistCredits(candidate.artist).any { candidateArtist ->
                        incomingArtists.any { incomingArtist ->
                            ArtistParser.isStrictSameArtist(candidateArtist, incomingArtist)
                        }
                    }
                }
            val individualArtistMatch = chooseByAlbum(individualArtistCandidates)
            if (individualArtistMatch != null) {
                val track = fillMissingAlbumFromDeezer(individualArtistMatch, entry.albumName)
                val resolution = TrackResolver.Resolution(
                    trackId = track.id,
                    isNewTrack = false,
                    track = track,
                )
                cacheTrackResolution(cacheKey, resolution, trackCache)
                return resolution
            }

            // No authoritative or safely disambiguated textual identity exists.
            // Creating a fresh row is safer than attaching this ISRC to a fuzzy candidate.
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

    private suspend fun cleanupOrphanedCreatedTracks(
        createdTrackIds: Set<Long>,
    ): Int {
        if (createdTrackIds.isEmpty()) return 0

        val trackIdsWithEvents = HashSet<Long>()
        createdTrackIds.toList().chunked(900).forEach { chunk ->
            listeningEventDao.getTimestampsForTracks(chunk)
                .forEach { row -> trackIdsWithEvents.add(row.track_id) }
        }

        var removed = 0
        for (trackId in createdTrackIds) {
            if (trackId in trackIdsWithEvents) continue
            val result = trackRepository.deleteTrackWithAllData(trackId)
            if (result.success) removed++
        }
        if (removed > 0) {
            Log.i(TAG, "Removed $removed orphaned tracks left by an interrupted Deezer import")
        }
        return removed
    }

    private fun comparableArtistCredits(value: String): List<String> =
        buildList {
            val cleaned = value.trim()
            if (cleaned.isNotEmpty()) add(cleaned)
            addAll(deezerArtistCredits(value))
            addAll(ArtistParser.getAllArtists(value))
        }.distinctBy { ArtistParser.normalizeForSearch(it) }

    private suspend fun linkDeezerArtistsForTrack(track: Track): Track {
        val artists =
            deezerArtistCredits(track.artist)
                .filterNot { artist ->
                    ArtistParser.isUnknownArtist(artist) ||
                        ArtistParser.isPlaceholderArtistName(artist)
                }
                .map { artistLinkingService.getOrCreateArtist(it) }
                .distinctBy { it.id }

        if (artists.isEmpty()) return track

        // This path is used only for a newly-created Deezer track or when
        // replacing an Unknown Artist placeholder, so rebuilding its junction
        // rows is safe and avoids leaving the placeholder relationship behind.
        trackArtistDao.deleteAllForTrack(track.id)
        artists.forEachIndexed { index, artist ->
            trackArtistDao.insert(
                TrackArtist(
                    trackId = track.id,
                    artistId = artist.id,
                    role = if (index == 0) ArtistRole.PRIMARY else ArtistRole.PERFORMER,
                    creditOrder = index,
                ),
            )
        }

        val updated = track.copy(primaryArtistId = artists.first().id)
        if (updated != track) trackRepository.update(updated)
        return updated
    }

    private suspend fun fillMissingAlbumFromDeezer(
        track: Track,
        deezerAlbum: String?,
    ): Track {
        val album = deezerAlbum?.trim()?.takeIf { it.isNotEmpty() } ?: return track
        if (!track.album.isNullOrBlank()) return track

        val updated = track.copy(album = album)
        return try {
            trackRepository.update(updated)
            updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Album metadata is useful but secondary; never lose the play for it.
            Log.w(TAG, "Failed to fill a missing album from Deezer metadata", e)
            track
        }
    }

    private suspend fun promoteUnknownArtistFromDeezer(
        track: Track,
        deezerArtist: String,
    ): Track {
        val existingIsPlaceholder =
            ArtistParser.isUnknownArtist(track.artist) ||
                ArtistParser.isPlaceholderArtistName(track.artist)
        val deezerIsUsable =
            !ArtistParser.isUnknownArtist(deezerArtist) &&
                !ArtistParser.isPlaceholderArtistName(deezerArtist)

        if (!existingIsPlaceholder || !deezerIsUsable) {
            return track
        }

        val candidate = track.copy(
            artist = deezerArtist.trim(),
            primaryArtistId = null,
        )

        try {
            trackRepository.update(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to replace an unknown artist from Deezer metadata", e)
            return track
        }

        return try {
            linkDeezerArtistsForTrack(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The authoritative artist name is already persisted. Keep it even if
            // rebuilding the artist junction table must be retried later.
            Log.w(TAG, "Failed to relink the promoted Deezer artist", e)
            candidate
        }
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

    private suspend fun preserveAdditionalDeezerArtistCredit(
        trackId: Long,
        creditedArtistName: String,
        isPrimaryCredit: Boolean,
        preparedCredits: MutableSet<String>,
    ) {
        val credited = creditedArtistName.trim()
        if (
            credited.isBlank() ||
            ArtistParser.isUnknownArtist(credited) ||
            ArtistParser.isPlaceholderArtistName(credited)
        ) {
            return
        }

        val cacheKey = trackId.toString() + "|" + credited.lowercase()
        if (cacheKey in preparedCredits) return

        val artist = artistLinkingService.getOrCreateArtist(credited)
        if (!trackArtistDao.hasRelationship(trackId, artist.id)) {
            val nextOrder =
                (trackArtistDao.getRelationshipsForTrack(trackId).maxOfOrNull { it.creditOrder } ?: -1) + 1
            trackArtistDao.insert(
                TrackArtist(
                    trackId = trackId,
                    artistId = artist.id,
                    role = if (isPrimaryCredit) ArtistRole.PRIMARY else ArtistRole.PERFORMER,
                    creditOrder = nextOrder,
                ),
            )
        }

        // Tempo's primary artist rankings still aggregate the denormalized
        // tracks.artist string, so keep that representation in sync as well.
        // Preserve the existing syntax and append only a genuinely missing
        // Deezer credit.
        trackRepository.getById(trackId).first()?.let { current ->
            val alreadyPresent =
                comparableArtistCredits(current.artist).any { existingArtist ->
                    ArtistParser.isStrictSameArtist(existingArtist, credited)
                }

            var updated = current
            if (isPrimaryCredit && current.primaryArtistId == null) {
                updated = updated.copy(primaryArtistId = artist.id)
            }
            if (!alreadyPresent && !ArtistParser.isUnknownArtist(current.artist)) {
                val updatedArtist =
                    if (current.artist.isBlank()) credited else current.artist.trim() + ", " + credited
                updated = updated.copy(artist = updatedArtist)
            }
            if (updated != current) trackRepository.update(updated)
        }

        preparedCredits.add(cacheKey)
        if (preparedCredits.size > MAX_CACHE_SIZE) preparedCredits.clear()
    }

    private data class PreparedMetadata(
        val isrc: String?,
        val album: String?,
    )

    private fun canonicalIsrc(value: String): String? {
        val normalized = value.trim().uppercase().replace("-", "").replace(" ", "")
        return normalized.takeIf(ISRC_REGEX::matches)
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
        val storedIsrc = updated.isrc?.let(::canonicalIsrc)
        if (storedIsrc == null && entry.isrc != null) {
            // Repair blank *and invalid* legacy ISRC values with Deezer's validated ISRC.
            updated = updated.copy(isrc = entry.isrc)
            changed = true
        }
        if (updated.albumTitle.isNullOrBlank() && !entry.albumName.isNullOrBlank()) {
            updated = updated.copy(albumTitle = entry.albumName)
            changed = true
        }
        if (
            updated.artistName.isNullOrBlank() ||
            ArtistParser.isUnknownArtist(updated.artistName.orEmpty()) ||
            ArtistParser.isPlaceholderArtistName(updated.artistName.orEmpty())
        ) {
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
                        throw IOException("Deezer export is larger than 256 MB")
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
            message.contains("too large", ignoreCase = true) ->
                "This Deezer export is too large to import safely on this device"
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
