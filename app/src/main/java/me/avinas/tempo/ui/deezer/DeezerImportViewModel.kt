package me.avinas.tempo.ui.deezer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import me.avinas.tempo.data.deezer.DeezerDataImportService
import javax.inject.Inject

@HiltViewModel
class DeezerImportViewModel @Inject constructor(
    private val importService: DeezerDataImportService,
    private val tracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeezerImportUiState>(DeezerImportUiState.Idle)
    val uiState: StateFlow<DeezerImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    fun importFile(context: Context, uri: Uri) {
        tracker.track(FeatureUsed(TempoFeature.DEEZER_IMPORT))
        _uiState.value = DeezerImportUiState.Importing
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val result = importService.importFromUri(context.applicationContext, uri)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.DEEZER,
                        phase = if (result.isSuccess) ImportPhase.COMPLETED else ImportPhase.FAILED,
                        records = result.tracksImported,
                        failure = if (result.isSuccess) null else FailureClass.UNKNOWN,
                        durationMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
                _uiState.value =
                    if (result.isSuccess) {
                        DeezerImportUiState.Completed(result)
                    } else {
                        DeezerImportUiState.Error(
                            result.errors.firstOrNull() ?: "Deezer import failed",
                        )
                    }
            } catch (e: CancellationException) {
                _uiState.value = DeezerImportUiState.Idle
                throw e
            } catch (e: Exception) {
                Log.e("DeezerImportVM", "Import failed", e)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.DEEZER,
                        phase = ImportPhase.FAILED,
                        records = 0,
                        failure = FailureClassifier.of(e),
                        durationMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
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
