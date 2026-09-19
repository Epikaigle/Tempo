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
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.ImportPhase
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import me.avinas.tempo.worker.SpotifyJsonImportWorker
import javax.inject.Inject

@HiltViewModel
class SpotifyJsonImportViewModel @Inject constructor(
    private val spotifyJsonImportService: SpotifyJsonImportService,
    private val tracker: AnalyticsTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow<SpotifyJsonImportUiState>(SpotifyJsonImportUiState.Idle)
    val uiState: StateFlow<SpotifyJsonImportUiState> = _uiState.asStateFlow()

    val importState = spotifyJsonImportService.importState

    fun importFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) {
            _uiState.value = SpotifyJsonImportUiState.Error("No files selected")
            return
        }

        tracker.track(FeatureUsed(TempoFeature.SPOTIFY_JSON_IMPORT))
        _uiState.value = SpotifyJsonImportUiState.Importing

        viewModelScope.launch {
            // Reported from here rather than the worker because this is the in-app import
            // path; the worker's path is reported by the worker itself, so they never
            // double-count.
            val startedAt = System.currentTimeMillis()
            try {
                // ponytail: app context — the Activity may be gone long before a big import ends.
                val result = spotifyJsonImportService.importFromUris(context.applicationContext, uris)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.SPOTIFY_JSON,
                        phase = if (result.isSuccess) ImportPhase.COMPLETED else ImportPhase.FAILED,
                        records = result.tracksImported,
                        // The service reports failures as strings, so the category cannot be
                        // recovered without parsing free text — which the schema forbids.
                        failure = if (result.isSuccess) null else FailureClass.UNKNOWN,
                        durationMillis = System.currentTimeMillis() - startedAt
                    )
                )
                _uiState.value = if (result.isSuccess) {
                    SpotifyJsonImportUiState.Completed(result)
                } else {
                    SpotifyJsonImportUiState.Error(result.errors.joinToString("; "))
                }
            } catch (e: Exception) {
                Log.e("SpotifyJsonImportVM", "Import failed", e)
                tracker.track(
                    ImportRun(
                        provider = ImportProvider.SPOTIFY_JSON,
                        phase = ImportPhase.FAILED,
                        records = 0,
                        failure = FailureClassifier.of(e),
                        durationMillis = System.currentTimeMillis() - startedAt
                    )
                )
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
