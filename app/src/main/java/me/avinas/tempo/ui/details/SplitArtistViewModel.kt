package me.avinas.tempo.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.repository.ArtistSplitRepository
import javax.inject.Inject

/**
 * UI model for one track row inside a split group.
 */
data class SplitTrackUi(
    val id: Long,
    val title: String,
    val album: String?,
    val selected: Boolean
)

/**
 * UI model for one raw-name group in the split dialog.
 * Groups whose name differs from the source artist are pre-selected,
 * since those are the common "collapsed artists" case.
 */
data class SplitGroupUi(
    val rawName: String,
    val isSourceName: Boolean,
    val expanded: Boolean,
    val targetName: String,
    val tracks: List<SplitTrackUi>
) {
    val selectedCount: Int get() = tracks.count { it.selected }
}

/**
 * UI state for the artist split dialog.
 */
data class SplitArtistUiState(
    val isLoading: Boolean = true,
    val groups: List<SplitGroupUi> = emptyList(),
    val status: ArtistSplitStatus = ArtistSplitStatus.Idle
)

/**
 * Status of an artist split operation.
 */
sealed class ArtistSplitStatus {
    object Idle : ArtistSplitStatus()
    object Processing : ArtistSplitStatus()
    data class Success(
        val movedCount: Int,
        val targetNames: List<String>,
        val sourceDeleted: Boolean
    ) : ArtistSplitStatus()
    data class Error(val message: String) : ArtistSplitStatus()
}

/**
 * ViewModel for splitting an artist.
 *
 * Loads the source artist's tracks grouped by raw artist string, lets the
 * user pick tracks per group and edit each group's target artist name, then
 * executes the split via [ArtistSplitRepository].
 */
@HiltViewModel
class SplitArtistViewModel @Inject constructor(
    private val artistSplitRepository: ArtistSplitRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplitArtistUiState())
    val uiState: StateFlow<SplitArtistUiState> = _uiState.asStateFlow()

    private var sourceArtistId: Long = -1

    /**
     * Set the source artist and load its split groups.
     * Always resets state for a fresh dialog (mirrors MergeArtistViewModel).
     */
    fun setSourceArtist(id: Long) {
        _uiState.value = SplitArtistUiState()
        sourceArtistId = id
        loadGroups()
    }

    private fun loadGroups() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val groups = artistSplitRepository.getSplitGroups(sourceArtistId).map { group ->
                    SplitGroupUi(
                        rawName = group.rawName,
                        isSourceName = group.isSourceName,
                        expanded = false,
                        targetName = group.rawName,
                        tracks = group.tracks.map { track ->
                            SplitTrackUi(
                                id = track.id,
                                title = track.title,
                                album = track.album,
                                // Pre-select groups that do NOT belong to the
                                // source artist — the collapse-victim case
                                selected = !group.isSourceName
                            )
                        }
                    )
                }
                _uiState.value = _uiState.value.copy(isLoading = false, groups = groups)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    status = ArtistSplitStatus.Error(e.message ?: "Failed to load tracks")
                )
            }
        }
    }

    fun toggleGroupExpanded(rawName: String) =
        updateGroup(rawName) { it.copy(expanded = !it.expanded) }

    fun toggleTrack(rawName: String, trackId: Long) = updateGroup(rawName) { group ->
        group.copy(
            tracks = group.tracks.map {
                if (it.id == trackId) it.copy(selected = !it.selected) else it
            }
        )
    }

    fun toggleAllInGroup(rawName: String, select: Boolean) = updateGroup(rawName) { group ->
        group.copy(tracks = group.tracks.map { it.copy(selected = select) })
    }

    fun updateTargetName(rawName: String, newTarget: String) =
        updateGroup(rawName) { it.copy(targetName = newTarget) }

    private fun updateGroup(rawName: String, transform: (SplitGroupUi) -> SplitGroupUi) {
        _uiState.value = _uiState.value.copy(
            groups = _uiState.value.groups.map {
                if (it.rawName == rawName) transform(it) else it
            }
        )
    }

    /** Total number of tracks currently selected across all groups. */
    val selectedTrackCount: Int
        get() = _uiState.value.groups.sumOf { it.selectedCount }

    /**
     * Execute the split with the current selection.
     */
    fun confirmSplit() {
        val state = _uiState.value
        if (state.status is ArtistSplitStatus.Processing) return

        val moves = state.groups.mapNotNull { group ->
            val ids = group.tracks.filter { it.selected }.map { it.id }
            if (ids.isEmpty() || group.targetName.isBlank()) null
            else ArtistSplitRepository.SplitMove(
                trackIds = ids,
                targetName = group.targetName.trim(),
                rawName = group.rawName
            )
        }

        if (moves.isEmpty()) {
            _uiState.value = state.copy(
                status = ArtistSplitStatus.Error("Select at least one track and a target artist name")
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = ArtistSplitStatus.Processing)
            val result = artistSplitRepository.splitArtist(sourceArtistId, moves)
            _uiState.value = if (result != null) {
                _uiState.value.copy(
                    status = ArtistSplitStatus.Success(
                        movedCount = result.movedTrackCount,
                        targetNames = result.targetArtistNames,
                        sourceDeleted = result.sourceDeleted
                    )
                )
            } else {
                _uiState.value.copy(status = ArtistSplitStatus.Error("Split failed"))
            }
        }
    }

    fun resetStatus() {
        _uiState.value = _uiState.value.copy(status = ArtistSplitStatus.Idle)
    }

    fun reset() {
        sourceArtistId = -1
        _uiState.value = SplitArtistUiState()
    }
}
