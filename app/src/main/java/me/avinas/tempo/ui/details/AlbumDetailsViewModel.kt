package me.avinas.tempo.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats
import me.avinas.tempo.data.local.entities.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    private val _uiState = MutableStateFlow(AlbumDetailsUiState())
    val uiState: StateFlow<AlbumDetailsUiState> = _uiState.asStateFlow()

    // ponytail: single debounce job instead of a Flow; the query is a string field
    // in state, so a plain Job is the smallest thing that debounces without a Flow pipeline.
    private var searchJob: Job? = null

    init {
        loadAlbumDetails()
    }

    private fun loadAlbumDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val details = statsRepository.getAlbumDetails(albumId)
                // Deduplicate tracks once in ViewModel instead of on every recomposition
                val deduplicatedDetails = details.copy(
                    tracks = details.tracks.distinctBy { it.track.id }
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        albumDetails = deduplicatedDetails
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load album details"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadAlbumDetails()
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
    }

    fun openAddDialog() {
        _uiState.update { it.copy(addDialogVisible = true, addQuery = "", addResults = emptyList()) }
        searchCandidates("")
    }

    fun closeAddDialog() {
        searchJob?.cancel()
        _uiState.update { it.copy(addDialogVisible = false, addQuery = "", addResults = emptyList()) }
    }

    fun onAddQueryChange(query: String) {
        _uiState.update { it.copy(addQuery = query) }
        searchCandidates(query)
    }

    private fun searchCandidates(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // ponytail: fixed 220ms debounce; skip leading-edge for blank queries so the
            // dialog shows all candidates immediately on open.
            if (query.isNotBlank()) delay(220)
            _uiState.update { it.copy(isSearching = true) }
            try {
                val results = statsRepository.getCandidateTracksForAlbum(albumId, query)
                val existingIds = _uiState.value.albumDetails
                    ?.tracks?.map { it.track.id }?.toSet() ?: emptySet()
                _uiState.update {
                    it.copy(
                        addResults = results.filter { track -> track.id !in existingIds },
                        isSearching = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun addTrackToAlbum(track: Track) {
        viewModelScope.launch {
            try {
                statsRepository.addTrackToAlbum(albumId, track.id)
                _uiState.update { it.copy(addDialogVisible = false) }
                loadAlbumDetails()
            } catch (e: Exception) {
                // ponytail: local DB update rarely fails; leave dialog open so user can retry.
            }
        }
    }

    fun requestRemove(track: TrackWithStats) {
        _uiState.update { it.copy(pendingRemove = track) }
    }

    fun cancelRemove() {
        _uiState.update { it.copy(pendingRemove = null) }
    }

    fun confirmRemove() {
        val track = _uiState.value.pendingRemove ?: return
        viewModelScope.launch {
            try {
                statsRepository.removeTrackFromAlbum(albumId, track.track.id)
                _uiState.update { it.copy(pendingRemove = null) }
                loadAlbumDetails()
            } catch (e: Exception) {
                _uiState.update { it.copy(pendingRemove = null) }
            }
        }
    }
}

@androidx.compose.runtime.Immutable
data class AlbumDetailsUiState(
    val isLoading: Boolean = true,
    val albumDetails: AlbumDetails? = null,
    val error: String? = null,
    val isEditMode: Boolean = false,
    val addDialogVisible: Boolean = false,
    val addQuery: String = "",
    val addResults: List<Track> = emptyList(),
    val isSearching: Boolean = false,
    val pendingRemove: TrackWithStats? = null
)
