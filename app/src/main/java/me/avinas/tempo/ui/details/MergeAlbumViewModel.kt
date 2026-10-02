package me.avinas.tempo.ui.details

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.dao.AlbumSearchResult
import me.avinas.tempo.data.repository.AlbumMergeRepository
import javax.inject.Inject

/**
 * UI state for album merge dialog.
 */
data class MergeAlbumUiState(
    val query: String = "",
    val searchResults: List<AlbumSearchResult> = emptyList(),
    val artistAlbums: List<AlbumSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingArtistAlbums: Boolean = false,
    val mergeStatus: AlbumMergeStatus = AlbumMergeStatus.Idle,
    val pendingMergeTarget: AlbumSearchResult? = null
)

/**
 * Status of album merge operation.
 */
sealed class AlbumMergeStatus {
    object Idle : AlbumMergeStatus()
    object Processing : AlbumMergeStatus()
    data class Success(val targetAlbumId: Long) : AlbumMergeStatus()
    data class Error(val message: String) : AlbumMergeStatus()
}

/**
 * ViewModel for merging albums.
 *
 * Allows users to search for a target album and merge the source album into it.
 * After merge:
 * - Duplicate tracks are merged and listening history consolidated
 * - Unique tracks are reassigned to the target album
 * - Scrobble archive rows are updated
 * - Metadata is merged
 * - Source album is deleted
 */
@HiltViewModel
class MergeAlbumViewModel @Inject constructor(
    private val albumMergeRepository: AlbumMergeRepository
) : ViewModel() {
    companion object {
        private const val TAG = "MergeAlbumViewModel"
    }

    private val _uiState = MutableStateFlow(MergeAlbumUiState())
    val uiState: StateFlow<MergeAlbumUiState> = _uiState.asStateFlow()

    private var sourceAlbumId: Long = -1
    private var sourceArtistId: Long = -1
    private var searchJob: Job? = null

    /**
     * Set the source album and artist ID.
     * Resets state and eagerly loads other albums by the same artist.
     */
    fun setSourceAlbum(albumId: Long, artistId: Long) {
        _uiState.value = MergeAlbumUiState()
        sourceAlbumId = albumId
        sourceArtistId = artistId
        loadArtistAlbums(artistId, albumId)
    }

    private fun loadArtistAlbums(artistId: Long, currentAlbumId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingArtistAlbums = true)
            try {
                val albums = albumMergeRepository.getAlbumsForArtist(
                    artistId = artistId,
                    excludeAlbumId = if (currentAlbumId > 0) currentAlbumId else null
                )
                _uiState.value = _uiState.value.copy(
                    artistAlbums = albums,
                    isLoadingArtistAlbums = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artist albums: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoadingArtistAlbums = false)
            }
        }
    }

    /**
     * Handle search query changes with debounce.
     */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(200)
                searchAlbums(trimmed)
            }
        } else {
            _uiState.value = _uiState.value.copy(
                searchResults = emptyList(),
                isSearching = false
            )
        }
    }

    private suspend fun searchAlbums(query: String) {
        _uiState.value = _uiState.value.copy(isSearching = true)
        try {
            val results = albumMergeRepository.searchAlbums(
                query = query,
                excludeAlbumId = if (sourceAlbumId > 0) sourceAlbumId else null,
                limit = 25
            )
            _uiState.value = _uiState.value.copy(
                searchResults = results,
                isSearching = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                mergeStatus = AlbumMergeStatus.Error(e.message ?: "Search failed")
            )
        }
    }

    /**
     * Select an album as the merge target (shows confirmation).
     */
    fun selectAlbumForMerge(targetAlbum: AlbumSearchResult) {
        _uiState.value = _uiState.value.copy(pendingMergeTarget = targetAlbum)
    }

    /**
     * Cancel the pending merge and clear selection.
     */
    fun cancelMerge() {
        _uiState.value = _uiState.value.copy(pendingMergeTarget = null)
    }

    /**
     * Confirm and execute the merge with the selected target album.
     */
    fun confirmMerge() {
        val target = _uiState.value.pendingMergeTarget ?: return

        if (sourceAlbumId <= 0) {
            _uiState.value = _uiState.value.copy(
                mergeStatus = AlbumMergeStatus.Error("Source album not set"),
                pendingMergeTarget = null
            )
            return
        }

        if (_uiState.value.mergeStatus is AlbumMergeStatus.Processing) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                mergeStatus = AlbumMergeStatus.Processing,
                pendingMergeTarget = null
            )
            try {
                val success = albumMergeRepository.mergeAlbums(
                    sourceAlbumId = sourceAlbumId,
                    targetAlbumId = target.id
                )
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        mergeStatus = AlbumMergeStatus.Success(target.id)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        mergeStatus = AlbumMergeStatus.Error("Merge failed")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Album merge failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    mergeStatus = AlbumMergeStatus.Error(e.message ?: "Merge failed")
                )
            }
        }
    }

    /**
     * Reset the merge status to idle.
     */
    fun resetStatus() {
        _uiState.value = _uiState.value.copy(
            mergeStatus = AlbumMergeStatus.Idle,
            pendingMergeTarget = null
        )
    }
}
