package me.avinas.tempo.ui.musicolet

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
import me.avinas.tempo.data.musicolet.MusicoletImportOptions
import me.avinas.tempo.data.musicolet.MusicoletImportService
import me.avinas.tempo.worker.MusicoletImportWorker
import javax.inject.Inject

@HiltViewModel
class MusicoletImportViewModel
    @Inject
    constructor(
        private val musicoletImportService: MusicoletImportService,
        private val tracker: AnalyticsTracker,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MusicoletImportUiState>(MusicoletImportUiState.Idle)
        val uiState: StateFlow<MusicoletImportUiState> = _uiState.asStateFlow()

        /**
         * Whether plays Musicolet counted but never dated are placed at the start of the
         * song's known history. Off by default: the timeline stays truthful, and the
         * plays are reported instead.
         */
        private val _includeUndatedPlays = MutableStateFlow(false)
        val includeUndatedPlays: StateFlow<Boolean> = _includeUndatedPlays.asStateFlow()

        val importState = musicoletImportService.importState

        init {
            // The import runs in MusicoletImportWorker so it survives navigation and
            // process death; this shared service's flow drives both the UI and the
            // worker's notification, so translate its states here.
            //
            // Completed is honoured only while an import is in flight — a stale
            // Completed from a run that finished while this screen was closed would
            // otherwise flash its result and navigate back, blocking a new import.
            viewModelScope.launch {
                musicoletImportService.importState.collect { state ->
                    when (state) {
                        is MusicoletImportService.ImportState.Parsing,
                        is MusicoletImportService.ImportState.Importing,
                        -> {
                            _uiState.value = MusicoletImportUiState.Importing
                        }

                        is MusicoletImportService.ImportState.Completed -> {
                            if (_uiState.value is MusicoletImportUiState.Importing) {
                                _uiState.value =
                                    if (state.result.isSuccess) {
                                        MusicoletImportUiState.Completed(state.result)
                                    } else {
                                        MusicoletImportUiState.Error(
                                            state.result.errors.joinToString("; ")
                                                .ifBlank { "No Musicolet listening data found." },
                                        )
                                    }
                            }
                        }

                        is MusicoletImportService.ImportState.Idle,
                        is MusicoletImportService.ImportState.Error,
                        -> Unit
                    }
                }
            }
        }

        fun setIncludeUndatedPlays(value: Boolean) {
            _includeUndatedPlays.value = value
        }

        fun importFile(
            context: Context,
            uri: Uri,
        ) {
            tracker.track(FeatureUsed(TempoFeature.MUSICOLET_IMPORT))
            _uiState.value = MusicoletImportUiState.Importing

            // Persist the SAF grant so the worker can still read the file if WorkManager
            // restarts it after the process is killed.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Not persistable — fine while the process lives.
            }

            MusicoletImportWorker.enqueueImport(
                context = context,
                fileUri = uri.toString(),
                options = MusicoletImportOptions(includeUndatedPlays = _includeUndatedPlays.value),
            )
        }

        fun resetState() {
            _uiState.value = MusicoletImportUiState.Idle
            musicoletImportService.resetState()
        }
    }

sealed class MusicoletImportUiState {
    object Idle : MusicoletImportUiState()

    object Importing : MusicoletImportUiState()

    data class Completed(
        val result: MusicoletImportService.ImportResult,
    ) : MusicoletImportUiState()

    data class Error(
        val message: String,
    ) : MusicoletImportUiState()
}
