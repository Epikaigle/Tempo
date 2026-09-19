package me.avinas.tempo.ui.youtube

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
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import me.avinas.tempo.worker.YouTubeMusicImportWorker
import javax.inject.Inject

@HiltViewModel
class YouTubeMusicImportViewModel
    @Inject
    constructor(
        private val youTubeMusicImportService: YouTubeMusicImportService,
        private val tracker: AnalyticsTracker,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<YouTubeMusicImportUiState>(YouTubeMusicImportUiState.Idle)
        val uiState: StateFlow<YouTubeMusicImportUiState> = _uiState.asStateFlow()

        val importState = youTubeMusicImportService.importState

        init {
            // The import itself runs in YouTubeMusicImportWorker (foreground, survives
            // navigation and process death). This shared singleton service's state flow
            // drives both this UI and the worker's notification, so translate its states
            // into UI state here — same mapping the old inline path used.
            //
            // Completed is only honored while an import is in flight: a stale
            // Completed (finished while this screen was closed) would otherwise flash
            // the result and auto-navigate back, blocking a new import. In-process a
            // Parsing/Importing state always means the worker is genuinely running.
            viewModelScope.launch {
                youTubeMusicImportService.importState.collect { state ->
                    when (state) {
                        is YouTubeMusicImportService.ImportState.Parsing,
                        is YouTubeMusicImportService.ImportState.Importing,
                        -> {
                            _uiState.value = YouTubeMusicImportUiState.Importing
                        }

                        is YouTubeMusicImportService.ImportState.Completed -> {
                            if (_uiState.value is YouTubeMusicImportUiState.Importing) {
                                _uiState.value =
                                    if (state.result.isSuccess) {
                                        YouTubeMusicImportUiState.Completed(state.result)
                                    } else {
                                        YouTubeMusicImportUiState.Error(state.result.errors.joinToString("; "))
                                    }
                            }
                        }

                        is YouTubeMusicImportService.ImportState.Idle,
                        is YouTubeMusicImportService.ImportState.Error,
                        -> {
                            Unit
                        }
                    }
                }
            }
        }

        fun importFiles(
            context: Context,
            uris: List<Uri>,
        ) {
            if (uris.isEmpty()) {
                _uiState.value = YouTubeMusicImportUiState.Error("No files selected")
                return
            }

            tracker.track(FeatureUsed(TempoFeature.YTMUSIC_IMPORT))
            _uiState.value = YouTubeMusicImportUiState.Importing

            // Persist the SAF grants so the worker can still read the parts if
            // WorkManager restarts it after a process death.
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Grant not persistable — fine while the process lives.
                }
            }

            // ImportRun analytics come from the worker, which owns the import now.
            YouTubeMusicImportWorker.enqueueImport(context, uris.map { it.toString() })
        }

        fun resetState() {
            _uiState.value = YouTubeMusicImportUiState.Idle
            youTubeMusicImportService.resetState()
        }
    }

sealed class YouTubeMusicImportUiState {
    object Idle : YouTubeMusicImportUiState()

    object Importing : YouTubeMusicImportUiState()

    data class Completed(
        val result: YouTubeMusicImportService.ImportResult,
    ) : YouTubeMusicImportUiState()

    data class Error(
        val message: String,
    ) : YouTubeMusicImportUiState()
}
