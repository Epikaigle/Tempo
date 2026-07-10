package me.avinas.tempo.ui.details

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.worker.EnrichmentWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Song Details screen.
 *
 * Data Flow Pattern: Enrichment → Database → UI
 * - This ViewModel ONLY reads from database via Repository (never makes API calls)
 * - Track metadata is fetched from database cache
 * - Mood/genre derived from MusicBrainz tags
 * - Engagement metrics computed from listening behavior
 * - Background EnrichmentWorker keeps the data fresh via API calls
 * - UI always displays cached data for fast, offline-first experience
 */
@HiltViewModel
class SongDetailsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val trackRepository: TrackRepository,
    private val trackAliasRepository: TrackAliasRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val trackId: Long = checkNotNull(savedStateHandle["trackId"])

    private val _uiState = MutableStateFlow(SongDetailsUiState())
    val uiState: StateFlow<SongDetailsUiState> = _uiState.asStateFlow()

    // Guards on-demand enrichment so it fires once per screen entry, not on every reload
    private var hasTriggeredOnDemandEnrichment = false

    init {
        loadTrackDetails()
        // Auto-refresh when enrichment completes (EnrichmentWorker notifies per-track) so the
        // newly fetched album art appears without a manual pull-to-refresh.
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates().collect {
                if (_uiState.value.trackDetails?.track?.albumArtUrl.isNullOrBlank()) {
                    loadTrackDetails(quiet = true)
                }
            }
        }
    }

    /**
     * Load track details from database (cached enriched data).
     * No API calls are made - all data comes from locally cached enrichment.
     *
     * On-demand enrichment: if this track has no album art and hasn't been fully enriched,
     * trigger EnrichmentWorker for just this track so cover art/genres are fetched in the
     * background. The metadata-update observer above reloads the UI once it lands.
     *
     * @param quiet When true, skip the loading spinner (used for refresh-after-enrichment).
     */
    private fun loadTrackDetails(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _uiState.update { it.copy(isLoading = true) }
            try {
                val details = statsRepository.getTrackDetails(trackId)
                val history = statsRepository.getTrackListeningHistory(trackId, TimeRange.ALL_TIME)
                
                // Get enriched metadata from database cache (no API call)
                // This data was populated by EnrichmentWorker in background
                val enrichedMetadata = enrichedMetadataRepository.forTrackSync(trackId)
                
                // Derive mood from MusicBrainz tags instead of Spotify audio features
                val moodSummary = if (enrichedMetadata != null) {
                    val tags = enrichedMetadata.tags
                    val genres = enrichedMetadata.genres
                    if (tags.isNotEmpty() || genres.isNotEmpty()) {
                        TagBasedMoodAnalyzer.getMoodSummary(tags, genres)
                    } else null
                } else null
                
                // Get engagement metrics from user behavior
                val engagement = statsRepository.getTrackEngagement(trackId)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        trackDetails = details,
                        listeningHistory = history,
                        moodSummary = moodSummary,
                        engagement = engagement,
                        genre = enrichedMetadata?.genres?.firstOrNull() 
                            ?: enrichedMetadata?.tags?.firstOrNull(),
                        releaseDate = enrichedMetadata?.releaseDateFull ?: enrichedMetadata?.releaseDate,
                        releaseYear = enrichedMetadata?.releaseYear,
                        recordLabel = enrichedMetadata?.recordLabel
                    ) 
                }

                // On-demand enrichment: cover art missing and track not fully enriched.
                // Skips already-ENRICHED tracks (those had their chance; periodic retries gaps).
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

    /**
     * Delete the track and all its associated data from the database.
     * This includes listening events, enriched metadata, artist relationships, and content marks.
     */
    fun deleteTrack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                val result = trackRepository.deleteTrackWithAllData(trackId)
                if (result.success) {
                    _uiState.update { it.copy(isDeleting = false, showDeleteDialog = false, trackDetails = null) }
                    // Navigation back is handled by LaunchedEffect in UI (detects trackDetails == null)
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
     * Update the track title.
     *
     * Smart duplicate detection: before saving, checks whether another track already
     * shares the new title + artist. If so, surfaces a merge offer instead of just
     * updating the title — the user clearly wants to consolidate these tracks.
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

                // Smart: if the new title+artist matches another track, offer to merge
                // instead of creating a duplicate entry
                val existing = trackRepository.findByTitleAndArtist(trimmed, artist)
                if (existing != null && existing.id != trackId) {
                    _uiState.update {
                        it.copy(isSavingTitle = false, mergeTargetTrack = existing)
                    }
                    // The edit dialog will be dismissed and a merge confirmation will appear
                    return@launch
                }

                // Remember the original title so future plays still match this track
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
     * Confirm merging the current track into the duplicate that matched during
     * title editing. This moves all listening history and deletes the current track.
     * After merge, the UI navigates back (triggered by trackDetails == null).
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
                // Navigation back is handled by LaunchedEffect in UI (detects trackDetails == null)
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

    /**
     * Dismiss the merge offer and return to editing.
     */
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
    val engagement: TrackEngagement? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val releaseYear: Int? = null,
    val recordLabel: String? = null,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val isDeleting: Boolean = false,
    val showEditTitleDialog: Boolean = false,
    val isSavingTitle: Boolean = false,
    val editTitleError: String? = null,
    val mergeTargetTrack: Track? = null
)
