package me.avinas.tempo.ui.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import me.avinas.tempo.worker.SpotifyJsonImportWorker
import javax.inject.Inject

@HiltViewModel
class SpotifyJsonImportViewModel @Inject constructor(
    private val spotifyJsonImportService: SpotifyJsonImportService
) : ViewModel() {

    private val _uiState = MutableStateFlow<SpotifyJsonImportUiState>(SpotifyJsonImportUiState.Idle)
    val uiState: StateFlow<SpotifyJsonImportUiState> = _uiState.asStateFlow()

    val importState = spotifyJsonImportService.importState

    fun importFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) {
            _uiState.value = SpotifyJsonImportUiState.Error("No files selected")
            return
        }

        _uiState.value = SpotifyJsonImportUiState.Importing

        viewModelScope.launch {
            try {
                val result = spotifyJsonImportService.importFromUris(context, uris)
                _uiState.value = if (result.isSuccess) {
                    SpotifyJsonImportUiState.Completed(result)
                } else {
                    SpotifyJsonImportUiState.Error(result.errors.joinToString("; "))
                }
            } catch (e: Exception) {
                Log.e("SpotifyJsonImportVM", "Import failed", e)
                _uiState.value = SpotifyJsonImportUiState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun resetState() {
        _uiState.value = SpotifyJsonImportUiState.Idle
        spotifyJsonImportService.resetState()
    }
}

sealed class SpotifyJsonImportUiState {
    object Idle : SpotifyJsonImportUiState()
    object Importing : SpotifyJsonImportUiState()
    data class Completed(val result: SpotifyJsonImportService.ImportResult) : SpotifyJsonImportUiState()
    data class Error(val message: String) : SpotifyJsonImportUiState()
}
