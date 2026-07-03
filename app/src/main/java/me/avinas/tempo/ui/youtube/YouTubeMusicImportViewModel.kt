package me.avinas.tempo.ui.youtube

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
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import javax.inject.Inject

@HiltViewModel
class YouTubeMusicImportViewModel @Inject constructor(
    private val youTubeMusicImportService: YouTubeMusicImportService
) : ViewModel() {

    private val _uiState = MutableStateFlow<YouTubeMusicImportUiState>(YouTubeMusicImportUiState.Idle)
    val uiState: StateFlow<YouTubeMusicImportUiState> = _uiState.asStateFlow()

    val importState = youTubeMusicImportService.importState

    fun importFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) {
            _uiState.value = YouTubeMusicImportUiState.Error("No files selected")
            return
        }

        _uiState.value = YouTubeMusicImportUiState.Importing

        viewModelScope.launch {
            try {
                val result = youTubeMusicImportService.importFromUris(context, uris)
                _uiState.value = if (result.isSuccess) {
                    YouTubeMusicImportUiState.Completed(result)
                } else {
                    YouTubeMusicImportUiState.Error(result.errors.joinToString("; "))
                }
            } catch (e: Exception) {
                Log.e("YouTubeMusicImportVM", "Import failed", e)
                _uiState.value = YouTubeMusicImportUiState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun resetState() {
        _uiState.value = YouTubeMusicImportUiState.Idle
        youTubeMusicImportService.resetState()
    }
}

sealed class YouTubeMusicImportUiState {
    object Idle : YouTubeMusicImportUiState()
    object Importing : YouTubeMusicImportUiState()
    data class Completed(val result: YouTubeMusicImportService.ImportResult) : YouTubeMusicImportUiState()
    data class Error(val message: String) : YouTubeMusicImportUiState()
}
