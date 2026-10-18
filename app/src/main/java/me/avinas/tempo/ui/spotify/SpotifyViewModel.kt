package me.avinas.tempo.ui.spotify

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.remote.spotify.SpotifyAuthManager
import me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage
import me.avinas.tempo.data.spotify.SpotifyImportService
import me.avinas.tempo.data.spotify.SpotifyTopItemsService
import me.avinas.tempo.data.spotify.SpotifyHistoryReconstructionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Spotify integration state for the profile screens. Reads only from the database through
 * repositories, never the API: that work belongs to `EnrichmentWorker` in the background, so
 * the UI always shows cached data.
 */
@HiltViewModel
class SpotifyViewModel @Inject constructor(
    private val authManager: SpotifyAuthManager,
    private val tokenStorage: SpotifyTokenStorage,
    private val enrichedMetadataRepository: EnrichedMetadataRepository,
    private val spotifyImportService: SpotifyImportService,
    private val spotifyTopItemsService: SpotifyTopItemsService,
    private val historyReconstructionService: SpotifyHistoryReconstructionService
) : ViewModel() {

    val authState = authManager.authState
    
    // True while the auth flow is out in the browser.
    private val _isPendingAuth = MutableStateFlow(false)
    val isPendingAuth: StateFlow<Boolean> = _isPendingAuth.asStateFlow()
    
    private val _importState = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val importState: StateFlow<SpotifyImportState> = _importState.asStateFlow()

    private val _spotifyStats = MutableStateFlow(SpotifyStats())
    val spotifyStats: StateFlow<SpotifyStats> = _spotifyStats.asStateFlow()

    private val _showDisconnectDialog = MutableStateFlow(false)
    val showDisconnectDialog: StateFlow<Boolean> = _showDisconnectDialog.asStateFlow()

    private val _showConnectedMessage = MutableStateFlow(false)
    val showConnectedMessage: StateFlow<Boolean> = _showConnectedMessage.asStateFlow()

    init {
        loadStats()
    }

    fun isConnected(): Boolean = authManager.isConnected()

    fun getUserDisplayName(): String? = tokenStorage.getUserDisplayName()

    /** Starts OAuth and sets [isPendingAuth]; the caller launches the returned browser Intent. */
    fun startLogin(): Intent {
        _isPendingAuth.value = true
        return authManager.startLoginFlow()
    }
    
    /** Call on resume to detect a return from the browser. */
    fun checkPendingAuth() {
        _isPendingAuth.value = tokenStorage.hasPendingAuth()
    }
    
    /** Call when auth completes or fails. */
    fun clearPendingAuth() {
        _isPendingAuth.value = false
    }

    /**
     * Not on the normal auth path: `SpotifyCallbackActivity` handles the callback and
     * `MainActivity` then calls `reconstructHistory()` directly. Kept for direct use or tests.
     */
    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            val success = authManager.handleCallback(uri)
            Log.d("SpotifyViewModel", "handleAuthCallback: success=$success")
            if (success) {
                enrichedMetadataRepository.queueAllForSpotifyEnrichment()
                _showConnectedMessage.value = true
                loadStats()
                
                // History reconstruction runs from MainActivity after auth callback to prevent duplicate triggers
            }
        }
    }

    fun showDisconnectConfirmation() {
        _showDisconnectDialog.value = true
    }

    fun dismissDisconnectDialog() {
        _showDisconnectDialog.value = false
    }

    /** Disconnects and clears all stored Spotify data. */
    fun disconnect() {
        viewModelScope.launch {
            enrichedMetadataRepository.clearAllSpotifyData()
            
            authManager.disconnect()
            
            _showDisconnectDialog.value = false
            loadStats()
        }
    }

    fun dismissConnectedMessage() {
        _showConnectedMessage.value = false
    }

    /** Reads counts from local enrichment data; no API calls. */
    fun loadStats() {
        viewModelScope.launch {
            val enrichedCount = enrichedMetadataRepository.countTracksWithSpotifyFeatures()
            val pendingCount = enrichedMetadataRepository.countTracksPendingSpotifyEnrichment()
            
            _spotifyStats.value = SpotifyStats(
                enrichedTracksCount = enrichedCount,
                pendingTracksCount = pendingCount,
                isLoading = false
            )
        }
    }

    data class SpotifyStats(
        val enrichedTracksCount: Int = 0,
        val pendingTracksCount: Int = 0,
        val isLoading: Boolean = true
    )
    
    /** Imports the last ~50 plays from Spotify's recently-played endpoint. */
    fun importRecentPlays() {
        if (_importState.value is SpotifyImportState.Importing) return
        
        viewModelScope.launch {
            _importState.value = SpotifyImportState.Importing(0)
            
            try {
                val result = spotifyImportService.importRecentlyPlayed()
                if (result.isSuccess) {
                    _importState.value = SpotifyImportState.Success(result.eventsCreated)
                } else {
                    _importState.value = SpotifyImportState.Error(
                        result.errors.firstOrNull() ?: "Import failed"
                    )
                }
            } catch (e: Exception) {
                _importState.value = SpotifyImportState.Error(e.message ?: "Import failed")
            }
        }
    }
    
    fun clearImportState() {
        _importState.value = SpotifyImportState.Idle
    }
    
    // SPOTIFY TOP ITEMS (Library Population)
    
    private val _topItemsImportState = MutableStateFlow<TopItemsImportState>(TopItemsImportState.Idle)
    val topItemsImportState: StateFlow<TopItemsImportState> = _topItemsImportState.asStateFlow()
    
    /**
     * Imports top tracks and artists, populating the library with metadata and genres.
     * Spotify is a data source only: there is no separate "Spotify Tops" section.
     */
    fun importTopItems() {
        if (_topItemsImportState.value is TopItemsImportState.Importing) {
            Log.d("SpotifyViewModel", "importTopItems already in progress, skipping")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting importTopItems()")
        
        viewModelScope.launch {
            _topItemsImportState.value = TopItemsImportState.Importing(0, 100, "Starting...")
            
            val result = spotifyTopItemsService.importTopItems { current, total, message ->
                _topItemsImportState.value = TopItemsImportState.Importing(current, total, message)
            }
            
            if (result.isSuccess) {
                Log.i("SpotifyViewModel", "Top items import successful: ${result.tracksCreated} tracks, ${result.artistsCreated} artists, ${result.genresCollected} genres, ${result.listeningEventsCreated} listening events")
                _topItemsImportState.value = TopItemsImportState.Success(
                    tracksCreated = result.tracksCreated,
                    artistsCreated = result.artistsCreated,
                    genresCollected = result.genresCollected,
                    listeningEventsCreated = result.listeningEventsCreated
                )
            } else {
                Log.e("SpotifyViewModel", "Top items import failed: ${result.errors}")
                _topItemsImportState.value = TopItemsImportState.Error(
                    result.errors.firstOrNull() ?: "Import failed"
                )
            }
        }
    }
    
    fun clearTopItemsImportState() {
        _topItemsImportState.value = TopItemsImportState.Idle
    }
    
    // HISTORY RECONSTRUCTION (Enhanced Import)
    
    private val _reconstructionState = MutableStateFlow<HistoryReconstructionState>(HistoryReconstructionState.Idle)
    val reconstructionState: StateFlow<HistoryReconstructionState> = _reconstructionState.asStateFlow()
    
    // Track if background processing is running to prevent duplicate launches
    private var isBackgroundProcessingRunning = false
    
    // Track if import has completed this session to prevent re-running
    private var hasCompletedImportThisSession = false
    
    /**
     * The Hybrid "Real + Smart" reconstruction: Time Machine (liked tracks with exact
     * timestamps), Artifact Hunter ("Your Top Songs 20XX" playlists) and Smart Mixer
     * (algorithmic distribution with a circadian rhythm bias).
     */
    fun reconstructHistory() {
        if (_reconstructionState.value is HistoryReconstructionState.Reconstructing) {
            Log.d("SpotifyViewModel", "History reconstruction already in progress, skipping")
            return
        }
        
        if (hasCompletedImportThisSession) {
            Log.d("SpotifyViewModel", "History reconstruction already completed this session, skipping")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting history reconstruction")
        
        viewModelScope.launch {
            _reconstructionState.value = HistoryReconstructionState.Reconstructing(
                progress = 0,
                total = 100,
                phase = "Initializing",
                message = "Starting history reconstruction..."
            )
            
            val result = historyReconstructionService.reconstructHistory { current, total, phase, message ->
                _reconstructionState.value = HistoryReconstructionState.Reconstructing(
                    progress = current,
                    total = total,
                    phase = phase,
                    message = message
                )
            }
            
            if (result.isSuccess) {
                Log.i("SpotifyViewModel", """
                    History reconstruction successful:
                    - Tracks: ${result.tracksCreated}
                    - Artists: ${result.artistsCreated}
                    - Events: ${result.listeningEventsCreated}
                    - Liked tracks used: ${result.likedTracksFound}
                    - Yearly playlists: ${result.yearlyPlaylistsFound}
                    - Top tracks: ${result.topTracksUsed}
                    - Pending playlists: ${result.pendingYearlyPlaylists.size}
                """.trimIndent())
                
                _reconstructionState.value = HistoryReconstructionState.Success(
                    tracksCreated = result.tracksCreated,
                    artistsCreated = result.artistsCreated,
                    listeningEventsCreated = result.listeningEventsCreated,
                    likedTracksFound = result.likedTracksFound,
                    yearlyPlaylistsFound = result.yearlyPlaylistsFound,
                    topTracksUsed = result.topTracksUsed
                )
                
                hasCompletedImportThisSession = true
                
                if (result.hasPendingHistory && !isBackgroundProcessingRunning) {
                    Log.i("SpotifyViewModel", "Starting background processing of ${result.pendingYearlyPlaylists.size} remaining yearly playlists")
                    processRemainingYearlyPlaylists(result.pendingYearlyPlaylists)
                }
            } else {
                Log.e("SpotifyViewModel", "History reconstruction failed: ${result.errors}")
                _reconstructionState.value = HistoryReconstructionState.Error(
                    result.errors.firstOrNull() ?: "Reconstruction failed"
                )
            }
        }
    }
    
    fun clearReconstructionState() {
        _reconstructionState.value = HistoryReconstructionState.Idle
    }
    
    /** The enhanced import, recommended for new users over [importTopItems]. */
    fun importWithHistoryReconstruction() {
        if (_topItemsImportState.value is TopItemsImportState.Importing ||
            _reconstructionState.value is HistoryReconstructionState.Reconstructing) {
            Log.d("SpotifyViewModel", "Import already in progress")
            return
        }
        
        Log.d("SpotifyViewModel", "Starting enhanced import with history reconstruction")
        
        viewModelScope.launch {
            reconstructHistory()
        }
    }
    
    /** Processes the older yearly playlists after the initial import, without blocking the UI. */
    private fun processRemainingYearlyPlaylists(pendingPlaylists: List<SpotifyHistoryReconstructionService.PendingPlaylist>) {
        if (isBackgroundProcessingRunning) {
            Log.d("SpotifyViewModel", "Background processing already running, skipping")
            return
        }
        
        isBackgroundProcessingRunning = true
        
        viewModelScope.launch {
            try {
                Log.i("SpotifyViewModel", "Background: Processing ${pendingPlaylists.size} pending yearly playlists")
                
                val backgroundResult = historyReconstructionService.processPendingYearlyPlaylists(pendingPlaylists)
                
                Log.i("SpotifyViewModel", """
                    Background processing completed:
                    - Tracks: ${backgroundResult.tracksCreated}
                    - Events: ${backgroundResult.eventsCreated}
                    - Playlists processed: ${pendingPlaylists.size}
                """.trimIndent())
                
                // Update the UI state with enriched totals if we're still showing success
                val currentState = _reconstructionState.value
                if (currentState is HistoryReconstructionState.Success && backgroundResult.eventsCreated > 0) {
                    _reconstructionState.value = currentState.copy(
                        tracksCreated = currentState.tracksCreated + backgroundResult.tracksCreated,
                        listeningEventsCreated = currentState.listeningEventsCreated + backgroundResult.eventsCreated,
                        yearlyPlaylistsFound = currentState.yearlyPlaylistsFound + pendingPlaylists.size
                    )
                }
                
            } catch (e: Exception) {
                Log.e("SpotifyViewModel", "Background playlist processing failed", e)
                // Don't update state to error - initial import was successful
            } finally {
                isBackgroundProcessingRunning = false
                Log.d("SpotifyViewModel", "Background processing finished")
            }
        }
    }
}

sealed class SpotifyImportState {
    object Idle : SpotifyImportState()
    data class Importing(val progress: Int) : SpotifyImportState()
    data class Success(val tracksImported: Int) : SpotifyImportState()
    data class Error(val message: String) : SpotifyImportState()
}

sealed class TopItemsImportState {
    object Idle : TopItemsImportState()
    data class Importing(val current: Int, val total: Int, val message: String) : TopItemsImportState()
    data class Success(
        val tracksCreated: Int, 
        val artistsCreated: Int, 
        val genresCollected: Int,
        val listeningEventsCreated: Int
    ) : TopItemsImportState()
    data class Error(val message: String) : TopItemsImportState()
}

sealed class HistoryReconstructionState {
    object Idle : HistoryReconstructionState()
    
    data class Reconstructing(
        val progress: Int,
        val total: Int,
        val phase: String,
        val message: String
    ) : HistoryReconstructionState()
    
    data class Success(
        val tracksCreated: Int,
        val artistsCreated: Int,
        val listeningEventsCreated: Int,
        val likedTracksFound: Int,
        val yearlyPlaylistsFound: Int,
        val topTracksUsed: Int
    ) : HistoryReconstructionState() {
        // Number of tracks with REAL timing data
        val tracksWithRealData: Int get() = likedTracksFound + yearlyPlaylistsFound
        
        val summary: String
            get() = buildString {
                if (listeningEventsCreated > 0) {
                    append("Created $listeningEventsCreated events from real data:\n")
                    if (likedTracksFound > 0) append("• $likedTracksFound liked songs (exact dates)\n")
                    if (yearlyPlaylistsFound > 0) append("• $yearlyPlaylistsFound tracks from yearly playlists\n")
                } else {
                    append("No listening history created.\n")
                }
                if (topTracksUsed > 0) {
                    append("\nLibrary populated with $topTracksUsed top tracks.")
                }
            }
        
        val hasListeningData: Boolean get() = listeningEventsCreated > 0
    }
    
    data class Error(val message: String) : HistoryReconstructionState()
}
