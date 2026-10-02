package me.avinas.tempo.ui.lastfm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.FeatureUsed
import me.avinas.tempo.data.analytics.ImportPhase
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.analytics.TempoFeature
import me.avinas.tempo.data.lastfm.LastFmImportService
import me.avinas.tempo.data.local.dao.LastFmImportMetadataDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.worker.LastFmImportWorker
import javax.inject.Inject

/**
 * ViewModel for Last.fm import UI.
 */
@HiltViewModel
class LastFmViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val lastFmImportService: LastFmImportService,
        private val userPreferencesDao: UserPreferencesDao,
        private val importMetadataDao: LastFmImportMetadataDao,
        private val tracker: AnalyticsTracker,
    ) : ViewModel() {
        companion object {
            private const val TAG = "LastFmViewModel"
        }

        private val _uiState = MutableStateFlow(LastFmUiState())
        val uiState: StateFlow<LastFmUiState> = _uiState.asStateFlow()

        // Import progress from service
        val importProgress = lastFmImportService.progress

        /** Wall-clock start of the current import, for ImportRun duration analytics. */
        private var importStartedAt = 0L

        init {
            loadConnectionState()
            observeImportProgress()
        }

        /**
         * Mirror the import service's progress into UI state.
         *
         * The import itself runs in a WorkManager worker, so this ViewModel can be destroyed and
         * recreated (process death, navigating away and back) while the import keeps going. Deriving
         * `isImporting` from the service's progress flow - rather than from a flag set at enqueue
         * time - keeps the progress screen correct across that recreation.
         */
        private fun observeImportProgress() {
            viewModelScope.launch {
                lastFmImportService.progress.collect { progress ->
                    when (progress) {
                        is LastFmImportService.ImportProgress.Discovering,
                        is LastFmImportService.ImportProgress.Importing,
                        is LastFmImportService.ImportProgress.Processing,
                        is LastFmImportService.ImportProgress.RateLimited,
                        -> {
                            _uiState.value = _uiState.value.copy(isImporting = true)
                        }

                        is LastFmImportService.ImportProgress.Completed -> {
                            // syncNewScrobbles() also emits Completed; only an import that this
                            // ViewModel started should drive the completion screen.
                            if (_uiState.value.isImporting) {
                                val result = progress.result
                                tracker.track(
                                    ImportRun(
                                        provider = ImportProvider.LASTFM,
                                        phase = if (result.success) ImportPhase.COMPLETED else ImportPhase.FAILED,
                                        records = (result.activeSetCount + result.archivedCount).toInt(),
                                        failure = result.errorMessage?.let { FailureClassifier.of(IllegalStateException(it)) },
                                        durationMillis = System.currentTimeMillis() - importStartedAt,
                                    ),
                                )
                                _uiState.value =
                                    _uiState.value.copy(
                                        isImporting = false,
                                        isConnected = true,
                                        importResult = result,
                                        error = if (result.success) null else result.errorMessage,
                                    )
                                loadConnectionState()
                            }
                        }

                        is LastFmImportService.ImportProgress.Failed -> {
                            _uiState.value =
                                _uiState.value.copy(
                                    isImporting = false,
                                    error = progress.error,
                                )
                        }

                        LastFmImportService.ImportProgress.Idle -> {
                            _uiState.value = _uiState.value.copy(isImporting = false)
                        }
                    }
                }
            }
        }

        /**
         * Load the current Last.fm connection state from preferences.
         */
        private fun loadConnectionState() {
            viewModelScope.launch {
                val prefs = userPreferencesDao.getSync() ?: UserPreferences()
                // Load last sync time from import metadata so it survives app restarts
                val lastSyncDisplay =
                    if (prefs.lastfmConnected) {
                        importMetadataDao.getLatestCompleted()?.let { meta ->
                            val syncedAt = meta.lastSyncTimestamp ?: meta.importCompletedAt
                            if (syncedAt != null) {
                                val date = java.util.Date(syncedAt)
                                val fmt = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                                "Last synced ${fmt.format(date)}"
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                _uiState.value =
                    _uiState.value.copy(
                        isConnected = prefs.lastfmConnected,
                        username = prefs.lastfmUsername,
                        syncFrequency = prefs.lastfmSyncFrequency ?: "NONE",
                        lastSyncResult = lastSyncDisplay,
                    )
            }
        }

        /**
         * Discover a Last.fm user's account info.
         */
        fun discoverUser(username: String) {
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = true,
                        error = null,
                    )

                val result = lastFmImportService.discoverUser(username)

                result.fold(
                    onSuccess = { discovery ->
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                discoveryResult = discovery,
                                inputUsername = username,
                                showTierSelection = true,
                            )
                    },
                    onFailure = { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = error.message ?: "Failed to connect to Last.fm",
                            )
                    },
                )
            }
        }

        /**
         * Start the import with the selected tier.
         *
         * Runs in a WorkManager worker (foreground service, resumable) rather than in
         * viewModelScope, so a 20+ minute import survives the user leaving the screen.
         */
        fun startImportDirect(tier: LastFmImportService.TierConfig) {
            val discovery = _uiState.value.discoveryResult ?: return
            val username = _uiState.value.inputUsername ?: return

            _uiState.value =
                _uiState.value.copy(
                    isImporting = true,
                    selectedTier = tier,
                    showTierSelection = false,
                    error = null,
                )

            importStartedAt = System.currentTimeMillis()
            tracker.track(FeatureUsed(TempoFeature.LASTFM_IMPORT))
            LastFmImportWorker.enqueueInitialImport(
                context = context,
                username = username,
                tier = tier.name,
                totalScrobbles = discovery.totalScrobbles,
            )

            Log.i(TAG, "Enqueued Last.fm import for $username with tier ${tier.name}")
        }

        /**
         * Disconnect Last.fm (doesn't delete data).
         */
        fun disconnect() {
            viewModelScope.launch {
                val prefs = userPreferencesDao.getSync() ?: UserPreferences()
                userPreferencesDao.upsert(
                    prefs.copy(
                        lastfmConnected = false,
                        lastfmUsername = null,
                        lastfmSyncFrequency = "NONE",
                    ),
                )

                // Cancel any scheduled sync
                LastFmImportWorker.cancelIncrementalSync(context)

                _uiState.value =
                    _uiState.value.copy(
                        isConnected = false,
                        username = null,
                        syncFrequency = "NONE",
                    )
            }
        }

        /**
         * Update sync frequency setting.
         */
        fun setSyncFrequency(frequency: String) {
            viewModelScope.launch {
                val prefs = userPreferencesDao.getSync() ?: UserPreferences()
                userPreferencesDao.upsert(prefs.copy(lastfmSyncFrequency = frequency))

                if (frequency != "NONE") {
                    LastFmImportWorker.scheduleIncrementalSync(context, frequency)
                } else {
                    LastFmImportWorker.cancelIncrementalSync(context)
                }

                _uiState.value = _uiState.value.copy(syncFrequency = frequency)
            }
        }

        /**
         * Trigger manual sync.
         */
        fun syncNow() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isSyncing = true)

                val result = lastFmImportService.syncNewScrobbles()

                result.fold(
                    onSuccess = { count ->
                        val now = java.util.Date()
                        val fmt = java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
                        _uiState.value =
                            _uiState.value.copy(
                                isSyncing = false,
                                lastSyncResult =
                                    if (count > 0) {
                                        "Synced $count new scrobbles — ${fmt.format(now)}"
                                    } else {
                                        "Up to date as of ${fmt.format(now)}"
                                    },
                            )
                    },
                    onFailure = { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isSyncing = false,
                                error = error.message,
                            )
                    },
                )
            }
        }

        /**
         * Get archive statistics.
         */
        fun loadArchiveStats() {
            viewModelScope.launch {
                val stats = lastFmImportService.getArchiveStats()
                _uiState.value = _uiState.value.copy(archiveStats = stats)
            }
        }

        /**
         * Cancel current import.
         */
        fun cancelImport() {
            lastFmImportService.cancelImport()
            LastFmImportWorker.cancelAll(context)
            _uiState.value =
                _uiState.value.copy(
                    isImporting = false,
                    showTierSelection = false,
                )
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _uiState.value = _uiState.value.copy(error = null)
        }

        /**
         * Clear sync result message.
         */
        fun clearSyncResult() {
            _uiState.value = _uiState.value.copy(lastSyncResult = null)
        }

        /**
         * Reset to initial state (for starting over).
         */
        fun reset() {
            lastFmImportService.resetProgress()
            _uiState.value =
                LastFmUiState(
                    isConnected = _uiState.value.isConnected,
                    username = _uiState.value.username,
                    syncFrequency = _uiState.value.syncFrequency,
                )
        }
    }

/**
 * UI state for Last.fm screens.
 */
data class LastFmUiState(
    // Connection state
    val isConnected: Boolean = false,
    val username: String? = null,
    val syncFrequency: String = "NONE",
    // Discovery state
    val isLoading: Boolean = false,
    val inputUsername: String? = null,
    val discoveryResult: LastFmImportService.DiscoveryResult? = null,
    val showTierSelection: Boolean = false,
    // Import state
    val isImporting: Boolean = false,
    val selectedTier: LastFmImportService.TierConfig? = null,
    val importResult: LastFmImportService.ImportResult? = null,
    // Sync state
    val isSyncing: Boolean = false,
    val lastSyncResult: String? = null,
    // Archive stats
    val archiveStats: LastFmImportService.ArchiveStats? = null,
    // Error state
    val error: String? = null,
)
