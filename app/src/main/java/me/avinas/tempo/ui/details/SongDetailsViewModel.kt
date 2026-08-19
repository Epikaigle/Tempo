package me.avinas.tempo.ui.details

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.repository.TrackAudioFeatures
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.worker.EnrichmentWorker
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for Song Details screen.
 *
 * Data Flow Pattern: Enrichment → Database → UI
 * - This ViewModel ONLY reads from database via Repository (never makes API calls)
 * - Track metadata is fetched from database cache
 * - Mood/genre derived from MusicBrainz tags & Spotify audio features
 * - Engagement metrics computed from listening behavior
 * - Audio preview handled locally via Media3 ExoPlayer
 */
@HiltViewModel
class SongDetailsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val trackRepository: TrackRepository,
    private val trackAliasRepository: TrackAliasRepository,
    private val listeningEventDao: ListeningEventDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val trackId: Long = checkNotNull(savedStateHandle["trackId"])

    private val _uiState = MutableStateFlow(SongDetailsUiState())
    val uiState: StateFlow<SongDetailsUiState> = _uiState.asStateFlow()

    // ExoPlayer for 30-second audio preview
    private var exoPlayer: ExoPlayer? = null
    private var previewProgressJob: Job? = null

    private val _isPlayingPreview = MutableStateFlow(false)
    val isPlayingPreview: StateFlow<Boolean> = _isPlayingPreview.asStateFlow()

    private val _previewProgress = MutableStateFlow(0f)
    val previewProgress: StateFlow<Float> = _previewProgress.asStateFlow()

    private val _previewPositionMs = MutableStateFlow(0L)
    val previewPositionMs: StateFlow<Long> = _previewPositionMs.asStateFlow()

    // Guards on-demand enrichment so it fires once per screen entry, not on every reload
    private var hasTriggeredOnDemandEnrichment = false

    init {
        loadTrackDetails()
        // Auto-refresh when enrichment completes so newly fetched album art/metadata appears
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates().collect {
                if (_uiState.value.trackDetails?.track?.albumArtUrl.isNullOrBlank() ||
                    _uiState.value.audioFeatures == null
                ) {
                    loadTrackDetails(quiet = true)
                }
            }
        }
    }

    /**
     * Load track details from database cache.
     */
    private fun loadTrackDetails(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _uiState.update { it.copy(isLoading = true) }
            try {
                val details = statsRepository.getTrackDetails(trackId)
                val history = statsRepository.getTrackListeningHistory(trackId, TimeRange.ALL_TIME)
                val enrichedMetadata = enrichedMetadataRepository.forTrackSync(trackId)
                val audioFeatures = statsRepository.getTrackAudioFeatures(trackId)
                val engagement = statsRepository.getTrackEngagement(trackId)
                val events = listeningEventDao.getEventsForTrack(trackId)

                // Derive mood from MusicBrainz tags if available
                val moodSummary = if (enrichedMetadata != null) {
                    val tags = enrichedMetadata.tags
                    val genres = enrichedMetadata.genres
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        TagBasedMoodAnalyzer.getMoodSummary(tags, genres)
                    } else null
                } else null

                // Compute peak binge day
                val peakBinge = history.maxByOrNull { it.playCount }?.let {
                    if (it.playCount > 0) Pair(it.date, it.playCount) else null
                }

                // Compute habitual listening hour
                val habitualHour = if (events.isNotEmpty()) {
                    val hourCounts = events.groupBy {
                        try {
                            Instant.ofEpochMilli(it.timestamp)
                                .atZone(ZoneId.systemDefault())
                                .hour
                        } catch (_: Exception) {
                            12
                        }
                    }.mapValues { it.value.size }
                    val peakHour = hourCounts.maxByOrNull { it.value }?.key
                    when (peakHour) {
                        in 5..11 -> "Morning Focus · ${if (peakHour == 0) 12 else peakHour} AM"
                        in 12..16 -> "Afternoon Vibe · ${if (peakHour == 12) 12 else peakHour?.minus(12)} PM"
                        in 17..21 -> "Evening Wind-down · ${peakHour?.minus(12)} PM"
                        in 22..23, in 0..4 -> "Night Owl · ${if (peakHour == 0) 12 else if (peakHour != null && peakHour > 12) peakHour - 12 else peakHour} ${if (peakHour != null && peakHour >= 12) "PM" else "AM"}"
                        else -> null
                    }
                } else null

                // Audio preview URL
                val previewUrl = enrichedMetadata?.previewUrl ?: enrichedMetadata?.spotifyPreviewUrl

                // Streaming URLs
                val spotifyTrackUrl = details.spotifyUrl 
                    ?: enrichedMetadata?.spotifyTrackUrl 
                    ?: enrichedMetadata?.spotifyId?.let { "https://open.spotify.com/track/$it" }
                val appleMusicUrl = details.appleMusicUrl ?: enrichedMetadata?.appleMusicUrl

                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        trackDetails = details,
                        listeningHistory = history,
                        moodSummary = moodSummary,
                        audioFeatures = audioFeatures,
                        engagement = engagement,
                        genre = enrichedMetadata?.genres?.firstOrNull() 
                            ?: enrichedMetadata?.tags?.firstOrNull(),
                        releaseDate = enrichedMetadata?.releaseDateFull ?: enrichedMetadata?.releaseDate,
                        releaseYear = enrichedMetadata?.releaseYear,
                        recordLabel = enrichedMetadata?.recordLabel,
                        previewUrl = previewUrl,
                        spotifyTrackUrl = spotifyTrackUrl,
                        appleMusicUrl = appleMusicUrl,
                        peakBingeDay = peakBinge,
                        habitualHour = habitualHour
                    ) 
                }

                // On-demand enrichment trigger
                if (!hasTriggeredOnDemandEnrichment
                    && details.track.albumArtUrl.isNullOrBlank()
                    && enrichedMetadata?.enrichmentStatus != EnrichmentStatus.ENRICHED) {
                    hasTriggeredOnDemandEnrichment = true
                    enrichedMetadataRepository.markForReEnrichment(trackId)
                    EnrichmentWorker.enqueueImmediate(context, trackId)
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load track details"
                    ) 
                }
            }
        }
    }

    fun refresh() {
        loadTrackDetails()
    }

    // Audio Preview Controls
    fun toggleAudioPreview() {
        val url = _uiState.value.previewUrl ?: return
        if (_isPlayingPreview.value) {
            pauseAudioPreview()
        } else {
            playAudioPreview(url)
        }
    }

    fun pauseAudioPreview() {
        _isPlayingPreview.value = false
        previewProgressJob?.cancel()
        exoPlayer?.pause()
    }

    private fun playAudioPreview(url: String) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            _isPlayingPreview.value = false
                            _previewProgress.value = 0f
                            _previewPositionMs.value = 0L
                            previewProgressJob?.cancel()
                        }
                    }
                })
            }
        }

        val player = exoPlayer ?: return
        val currentMedia = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentMedia != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
        player.play()
        _isPlayingPreview.value = true

        previewProgressJob?.cancel()
        previewProgressJob = viewModelScope.launch {
            while (_isPlayingPreview.value) {
                val current = player.currentPosition
                val duration = player.duration.coerceAtLeast(30_000L)
                _previewPositionMs.value = current
                _previewProgress.value = if (duration > 0) (current.toFloat() / duration).coerceIn(0f, 1f) else 0f
                // 4Hz is enough for the timecode (second precision) — the UI
                // tweens the fill between samples, and skipping 20Hz state
                // churn keeps the whole screen from recomposing constantly.
                delay(250)
            }
        }
    }

    fun stopAudioPreview() {
        _isPlayingPreview.value = false
        _previewProgress.value = 0f
        _previewPositionMs.value = 0L
        previewProgressJob?.cancel()
        exoPlayer?.stop()
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPreview()
        exoPlayer?.release()
        exoPlayer = null
    }

    /**
     * Delete the track and all its associated data from the database.
     */
    fun deleteTrack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                val result = trackRepository.deleteTrackWithAllData(trackId)
                if (result.success) {
                    _uiState.update { it.copy(isDeleting = false, showDeleteDialog = false, trackDetails = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            error = result.error ?: "Failed to delete song"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        error = e.message ?: "Failed to delete song"
                    )
                }
            }
        }
    }

    fun showDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun showEditTitleDialog() {
        _uiState.update {
            it.copy(
                showEditTitleDialog = true,
                editTitleError = null,
                mergeTargetTrack = null
            )
        }
    }

    fun dismissEditTitleDialog() {
        _uiState.update {
            it.copy(
                showEditTitleDialog = false,
                isSavingTitle = false,
                editTitleError = null,
                mergeTargetTrack = null
            )
        }
    }

    fun clearEditTitleWarnings() {
        _uiState.update {
            if (it.editTitleError == null) it
            else it.copy(editTitleError = null)
        }
    }

    /**
     * Update the track title with duplicate check.
     */
    fun updateTrackTitle(newTitle: String) {
        val trimmed = newTitle.trim()
        val currentTitle = _uiState.value.trackDetails?.track?.title

        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(editTitleError = "Title cannot be empty") }
            return
        }
        if (currentTitle != null && trimmed == currentTitle) {
            _uiState.update { it.copy(editTitleError = "Title is unchanged") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true, editTitleError = null, mergeTargetTrack = null) }
            try {
                val artist = _uiState.value.trackDetails?.track?.artist.orEmpty()
                val existing = trackRepository.findByTitleAndArtist(trimmed, artist)
                if (existing != null && existing.id != trackId) {
                    _uiState.update {
                        it.copy(isSavingTitle = false, mergeTargetTrack = existing)
                    }
                    return@launch
                }

                if (currentTitle != null) {
                    trackAliasRepository.createAlias(trackId, currentTitle, artist)
                }

                trackRepository.updateTitle(trackId, trimmed)

                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        showEditTitleDialog = false,
                        mergeTargetTrack = null,
                        editTitleError = null
                    )
                }
                loadTrackDetails()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        editTitleError = e.message ?: "Failed to update title"
                    )
                }
            }
        }
    }

    /**
     * Confirm merging track into existing duplicate.
     */
    fun confirmEditTitleMerge() {
        val target = _uiState.value.mergeTargetTrack ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingTitle = true) }
            try {
                trackAliasRepository.mergeTracks(trackId, target.id)
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        showEditTitleDialog = false,
                        mergeTargetTrack = null,
                        trackDetails = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSavingTitle = false,
                        editTitleError = e.message ?: "Merge failed"
                    )
                }
            }
        }
    }

    fun cancelEditTitleMerge() {
        _uiState.update { it.copy(mergeTargetTrack = null) }
    }
}

@androidx.compose.runtime.Immutable
data class SongDetailsUiState(
    val isLoading: Boolean = true,
    val trackDetails: TrackDetails? = null,
    val listeningHistory: List<DailyListening> = emptyList(),
    val moodSummary: TagBasedMoodAnalyzer.MoodSummary? = null,
    val audioFeatures: TrackAudioFeatures? = null,
    val engagement: TrackEngagement? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val previewUrl: String? = null,
    val spotifyTrackUrl: String? = null,
    val appleMusicUrl: String? = null,
    val peakBingeDay: Pair<String, Int>? = null,
    val habitualHour: String? = null,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val showEditTitleDialog: Boolean = false,
    val isSavingTitle: Boolean = false,
    val editTitleError: String? = null,
    val mergeTargetTrack: Track? = null
)
