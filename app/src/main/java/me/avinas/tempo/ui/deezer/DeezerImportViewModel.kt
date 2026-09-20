package me.avinas.tempo.ui.deezer

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
import me.avinas.tempo.data.deezer.DeezerDataImportService
import javax.inject.Inject

@HiltViewModel
class DeezerImportViewModel @Inject constructor(
    private val importService: DeezerDataImportService,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeezerImportUiState>(DeezerImportUiState.Idle)
    val uiState: StateFlow<DeezerImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    fun importFile(context: Context, uri: Uri) {
        _uiState.value = DeezerImportUiState.Importing
        viewModelScope.launch {
            try {
                val result = importService.importFromUri(context.applicationContext, uri)
                _uiState.value =
                    if (result.isSuccess) {
                        DeezerImportUiState.Completed(result)
                    } else {
                        DeezerImportUiState.Error(
                            result.errors.firstOrNull() ?: "Deezer import failed",
                        )
                    }
            } catch (e: Exception) {
                Log.e("DeezerImportVM", "Import failed", e)
                _uiState.value = DeezerImportUiState.Error("Deezer import failed")
            }
        }
    }

    fun resetState() {
        importService.resetState()
        _uiState.value = DeezerImportUiState.Idle
    }
}

sealed class DeezerImportUiState {
    object Idle : DeezerImportUiState()
    object Importing : DeezerImportUiState()
    data class Completed(val result: DeezerDataImportService.ImportResult) : DeezerImportUiState()
    data class Error(val message: String) : DeezerImportUiState()
}
