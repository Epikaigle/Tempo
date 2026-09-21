package me.avinas.tempo.ui.deezer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.deezer.DeezerDataImportService
import me.avinas.tempo.worker.DeezerImportWorker
import javax.inject.Inject

@HiltViewModel
class DeezerImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val importService: DeezerDataImportService,
    private val tracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeezerImportUiState>(DeezerImportUiState.Idle)
    val uiState: StateFlow<DeezerImportUiState> = _uiState.asStateFlow()

    val importState = importService.importState

    private var activeWorkId: java.util.UUID? = null

    init {
        // 1. Live in-process state flow: provides high-frequency progress while the app is alive
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
                                        state.result.errors.firstOrNull() ?: context.getString(R.string.deezer_import_error_generic),
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

        // 2. Persistent WorkManager observation: survives navigation and process death
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(DeezerImportWorker.WORK_NAME)
                .collect { infos ->
                    val trackedInfo =
                        activeWorkId?.let { id ->
                            infos.firstOrNull { it.id == id }
                        }
                    val info =
                        trackedInfo
                            ?: infos
                                .firstOrNull {
                                    it.state == WorkInfo.State.ENQUEUED ||
                                        it.state == WorkInfo.State.RUNNING ||
                                        it.state == WorkInfo.State.BLOCKED
                                }?.also { activeWorkId = it.id }
                            ?: return@collect

                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.BLOCKED -> {
                            if (_uiState.value !is DeezerImportUiState.Importing) {
                                _uiState.value = DeezerImportUiState.Importing
                            }
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            if (_uiState.value is DeezerImportUiState.Importing &&
                                importService.importState.value !is DeezerDataImportService.ImportState.Completed
                            ) {
                                val result = DeezerDataImportService.ImportResult(
                                    tracksImported = info.outputData.getInt(DeezerImportWorker.KEY_TRACKS_IMPORTED, 0),
                                    eventsCreated = info.outputData.getInt(DeezerImportWorker.KEY_EVENTS_CREATED, 0),
                                    duplicatesSkipped = info.outputData.getInt(DeezerImportWorker.KEY_DUPLICATES_SKIPPED, 0),
                                    shortPlaysSkipped = info.outputData.getInt(DeezerImportWorker.KEY_SHORT_PLAYS_SKIPPED, 0),
                                    malformedRows = info.outputData.getInt(DeezerImportWorker.KEY_MALFORMED_ROWS, 0),
                                    totalEntries = info.outputData.getInt(DeezerImportWorker.KEY_TOTAL_ENTRIES, 0),
                                    errors =
                                        info.outputData
                                            .getStringArray(DeezerImportWorker.KEY_WARNINGS)
                                            ?.toList()
                                            .orEmpty(),
                                )
                                _uiState.value = DeezerImportUiState.Completed(result)
                            }
                            activeWorkId = null
                        }

                        WorkInfo.State.FAILED -> {
                            if (_uiState.value is DeezerImportUiState.Importing &&
                                importService.importState.value !is DeezerDataImportService.ImportState.Completed
                            ) {
                                val errorMsg =
                                    info.outputData.getString(DeezerImportWorker.KEY_ERROR_MESSAGE)
                                        ?: context.getString(R.string.deezer_import_error_generic)
                                _uiState.value = DeezerImportUiState.Error(errorMsg)
                            }
                            activeWorkId = null
                        }

                        WorkInfo.State.CANCELLED -> {
                            if (_uiState.value is DeezerImportUiState.Importing) {
                                _uiState.value =
                                    DeezerImportUiState.Error(
                                        context.getString(R.string.deezer_import_error_cancelled),
                                    )
                            }
                            activeWorkId = null
                        }
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
        activeWorkId = DeezerImportWorker.enqueueImport(context, uri.toString())
    }

    fun cancelImport() {
        DeezerImportWorker.cancel(context)
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
