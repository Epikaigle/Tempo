package me.avinas.tempo.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.ImportPhase
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.desktop.ExtensionPlaysImportService
import javax.inject.Inject

/**
 * Drives the extension plays import screen, orchestrating file selection and [ExtensionPlaysImportService].
 */
@HiltViewModel
class ExtensionImportViewModel @Inject constructor(
    private val importService: ExtensionPlaysImportService,
    private val tracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExtensionImportUiState>(ExtensionImportUiState.Idle)
    val uiState: StateFlow<ExtensionImportUiState> = _uiState.asStateFlow()

    private val _selectedUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedUris: StateFlow<List<Uri>> = _selectedUris.asStateFlow()

    val importState: StateFlow<ExtensionPlaysImportService.ImportState> = importService.importState

    private var importJob: Job? = null

    fun setSelectedUris(uris: List<Uri>) {
        _selectedUris.value = uris
    }

    fun startImport() {
        if (importJob?.isActive == true) return

        val uris = _selectedUris.value
        if (uris.isEmpty()) {
            _uiState.value = ExtensionImportUiState.Error("No files selected")
            return
        }

        tracker.track(FeatureUsed(TempoFeature.EXTENSION_PLAYS_IMPORT))
        _uiState.value = ExtensionImportUiState.Importing

        importJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val result = importService.importFromUris(uris)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.BROWSER_EXTENSION,
                        phase = if (result.isSuccess) ImportPhase.COMPLETED else ImportPhase.FAILED,
                        records = result.eventsImported,
                        // Map import failure to generic parse error for analytics classification.
                        failure = if (result.isSuccess) null else FailureClass.PARSE,
                        durationMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
                _uiState.value =
                    if (result.isSuccess) {
                        ExtensionImportUiState.Completed(result)
                    } else {
                        ExtensionImportUiState.Error(result.errors.firstOrNull() ?: "Import failed")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Extension plays import failed", e)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.BROWSER_EXTENSION,
                        phase = ImportPhase.FAILED,
                        records = 0,
                        failure = FailureClassifier.of(e),
                        durationMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
                _uiState.value = ExtensionImportUiState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun resetState() {
        _uiState.value = ExtensionImportUiState.Idle
        _selectedUris.value = emptyList()
        importService.resetState()
    }

    private companion object {
        private const val TAG = "ExtensionImportVM"
    }
}

sealed class ExtensionImportUiState {
    data object Idle : ExtensionImportUiState()

    data object Importing : ExtensionImportUiState()

    data class Completed(val result: ExtensionPlaysImportService.ImportResult) : ExtensionImportUiState()

    data class Error(val message: String) : ExtensionImportUiState()
}
