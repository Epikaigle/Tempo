package me.avinas.tempo.ui.deezer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.deezer.DeezerDataImportService
import me.avinas.tempo.worker.DeezerImportWorker
import javax.inject.Inject

@HiltViewModel
class DeezerImportViewModel @Inject constructor(
    private val importService: DeezerDataImportService,
    private val tracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeezerImportUiState>(DeezerImportUiState.Idle)
    val uiState: StateFlow<DeezerImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    init {
        // The import itself runs in DeezerImportWorker (foreground, survives
        // navigation and process death). This shared singleton service's state flow
        // drives both this UI and the worker's notification, so translate its states
        // into UI state here — same pattern used by YouTubeMusicImportViewModel.
        viewModelScope.launch {
            importService.importState.collect { state ->
                when (state) {
                    is DeezerDataImportService.ImportState.Parsing,
                    is DeezerDataImportService.ImportState.Importing -> {
                        _uiState.value = DeezerImportUiState.Importing
                    }

                    is DeezerDataImportService.ImportState.Completed -> {
                        if (_uiState.value is DeezerImportUiState.Importing) {
                            _uiState.value =
                                if (state.result.isSuccess) {
                                    DeezerImportUiState.Completed(state.result)
                                } else {
                                    DeezerImportUiState.Error(
                                        state.result.errors.firstOrNull() ?: "Deezer import failed",
                                    )
                                }
                        }
                    }

                    is DeezerDataImportService.ImportState.Error -> {
                        if (_uiState.value is DeezerImportUiState.Importing) {
                            _uiState.value = DeezerImportUiState.Error(state.message)
                        }
                    }

                    is DeezerDataImportService.ImportState.Idle -> Unit
                }
            }
        }
    }

    fun importFile(context: Context, uri: Uri) {
        if (
            _uiState.value is DeezerImportUiState.Importing ||
            importService.importState.value is DeezerDataImportService.ImportState.Parsing ||
            importService.importState.value is DeezerDataImportService.ImportState.Importing
        ) {
            return
        }

        tracker.track(FeatureUsed(TempoFeature.DEEZER_IMPORT))
        _uiState.value = DeezerImportUiState.Importing

        // Persist the SAF grant so the worker can still read the file if
        // WorkManager restarts it after a process death.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Grant not persistable — fine while the process lives.
        }

        // ImportRun analytics come from the worker, which owns the import now.
        DeezerImportWorker.enqueueImport(context, uri.toString())
    }

    fun resetState() {
        _uiState.value = DeezerImportUiState.Idle
        importService.resetState()
    }
}

sealed class DeezerImportUiState {
    object Idle : DeezerImportUiState()
    object Importing : DeezerImportUiState()
    data class Completed(val result: DeezerDataImportService.ImportResult) : DeezerImportUiState()
    data class Error(val message: String) : DeezerImportUiState()
}
