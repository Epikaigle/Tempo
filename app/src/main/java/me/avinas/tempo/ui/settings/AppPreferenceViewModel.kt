package me.avinas.tempo.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.preferences.TrackingRulesPreferences.ContentOverrideType
import me.avinas.tempo.data.repository.AppPreferenceRepository
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import javax.inject.Inject

data class AppPreferenceUiState(
    val preinstalledApps: List<AppPreference> = emptyList(),
    val userAddedApps: List<AppPreference> = emptyList(),
    val blockedApps: List<AppPreference> = emptyList(),
    val installedPackageNames: Set<String> = emptySet(),
    val contentOverrides: List<ManualContentMark> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class AppPreferenceViewModel @Inject constructor(
    private val repository: AppPreferenceRepository,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository,
    private val statsRepository: StatsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(AppPreferenceUiState())
    val uiState: StateFlow<AppPreferenceUiState> = _uiState.asStateFlow()

    init {
        // Seed default apps for fresh installs (no-op if already seeded)
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedDefaultAppsIfNeeded()
        }

        // App state and manual content overrides both come from Room so the settings
        // screen and MusicTrackingService observe the same source of truth.
        viewModelScope.launch {
            combine(
                repository.getPreinstalledApps(),
                repository.getUserAddedApps(),
                repository.getBlockedApps(),
                manualContentMarkDao.getAllMarks(),
                _searchQuery
            ) { preinstalled, userAdded, blocked, manualMarks, query ->
                val installedPackages = try {
                    val pm = context.packageManager
                    pm.getInstalledPackages(0)
                        .map { it.packageName }
                        .toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                val filteredPreinstalled = if (query.isBlank()) preinstalled
                else preinstalled.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }

                val sortedPreinstalled = filteredPreinstalled.sortedWith(
                    compareByDescending<AppPreference> { it.packageName in installedPackages }
                        .thenBy { it.displayName }
                )

                val filteredUserAdded = if (query.isBlank()) userAdded
                else userAdded.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }

                val sortedUserAdded = filteredUserAdded.sortedWith(
                    compareByDescending<AppPreference> { it.packageName in installedPackages }
                        .thenBy { it.displayName }
                )

                val filteredBlocked = if (query.isBlank()) blocked
                else blocked.filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }

                val contentOverrides = manualMarks.filter {
                    it.contentType == CONTENT_TYPE_ALWAYS_MUSIC ||
                        it.contentType == CONTENT_TYPE_NON_MUSIC ||
                        it.contentType == CONTENT_TYPE_LEGACY_VIDEO
                }

                AppPreferenceUiState(
                    preinstalledApps = sortedPreinstalled,
                    userAddedApps = sortedUserAdded,
                    blockedApps = filteredBlocked,
                    installedPackageNames = installedPackages,
                    contentOverrides = contentOverrides,
                    searchQuery = query,
                    isLoading = false
                )
            }
                .flowOn(Dispatchers.Default)
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setAppEnabled(packageName, enabled)
        }
    }

    fun blockApp(packageName: String) {
        viewModelScope.launch {
            repository.setAppBlocked(packageName, true)
        }
    }

    fun unblockApp(packageName: String) {
        viewModelScope.launch {
            repository.setAppBlocked(packageName, false)
        }
    }

    fun addCustomApp(packageName: String, displayName: String) {
        viewModelScope.launch {
            repository.addCustomApp(packageName, displayName)
        }
    }

    fun removeApp(packageName: String) {
        viewModelScope.launch {
            repository.removeApp(packageName)
        }
    }

    fun addContentOverride(title: String, artist: String, type: ContentOverrideType) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() && cleanArtist.isBlank()) return

        val patternType = when {
            cleanTitle.isNotBlank() && cleanArtist.isNotBlank() -> "TITLE_ARTIST"
            cleanTitle.isNotBlank() -> "TITLE"
            else -> "ARTIST"
        }
        val patternValue = if (cleanTitle.isNotBlank()) cleanTitle else cleanArtist
        val contentType = when (type) {
            ContentOverrideType.MUSIC -> CONTENT_TYPE_ALWAYS_MUSIC
            ContentOverrideType.VIDEO -> CONTENT_TYPE_NON_MUSIC
        }

        viewModelScope.launch {
            manualContentMarkDao.insertMark(
                ManualContentMark(
                    targetTrackId = 0L,
                    patternType = patternType,
                    originalTitle = cleanTitle,
                    originalArtist = cleanArtist,
                    patternValue = patternValue,
                    contentType = contentType,
                    markedAt = System.currentTimeMillis()
                )
            )

            // Apply the correction to existing data as well as future plays. We resolve
            // the effective rule per track so a more-specific exception is never erased
            // by a broader artist/title rule.
            val marks = manualContentMarkDao.getAllSync()
            when (type) {
                ContentOverrideType.MUSIC -> normalizeExistingMatchesAsMusic(
                    cleanTitle,
                    cleanArtist,
                    marks
                )
                ContentOverrideType.VIDEO -> removeExistingMatchesFromHistory(
                    cleanTitle,
                    cleanArtist,
                    marks
                )
            }
        }
    }

    private suspend fun matchingTracks(title: String, artist: String): List<Track> =
        trackRepository.all().first().filter { track ->
            when {
                title.isNotBlank() && artist.isNotBlank() ->
                    track.title.equals(title, ignoreCase = true) &&
                        track.artist.equals(artist, ignoreCase = true)
                title.isNotBlank() -> track.title.equals(title, ignoreCase = true)
                else -> track.artist.equals(artist, ignoreCase = true)
            }
        }

    private suspend fun normalizeExistingMatchesAsMusic(
        title: String,
        artist: String,
        marks: List<ManualContentMark>
    ) {
        var changed = false
        matchingTracks(title, artist).forEach { track ->
            if (
                effectiveContentType(track.title, track.artist, marks) == CONTENT_TYPE_ALWAYS_MUSIC &&
                track.contentType != "MUSIC"
            ) {
                trackRepository.update(track.copy(contentType = "MUSIC"))
                changed = true
            }
        }

        if (changed) {
            statsRepository.invalidateCache()
        }
    }

    private suspend fun removeExistingMatchesFromHistory(
        title: String,
        artist: String,
        marks: List<ManualContentMark>
    ) {
        val matchingTrackIds = matchingTracks(title, artist)
            .asSequence()
            .filter { track ->
                effectiveContentType(track.title, track.artist, marks) in setOf(
                    CONTENT_TYPE_NON_MUSIC,
                    CONTENT_TYPE_LEGACY_VIDEO
                )
            }
            .map { it.id }
            .distinct()
            .toList()

        matchingTrackIds.forEach { trackId ->
            listeningRepository.deleteByTrackId(trackId)
        }
        if (matchingTrackIds.isNotEmpty()) {
            statsRepository.invalidateCache()
        }
    }

    /**
     * Resolve the same manual-rule precedence used by the tracking service:
     * TITLE_ARTIST > TITLE > ARTIST, then newest rule for equal specificity.
     */
    private fun effectiveContentType(
        title: String,
        artist: String,
        marks: List<ManualContentMark>
    ): String? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()

        return marks.asSequence()
            .mapNotNull { mark ->
                val patternType = mark.patternType.uppercase()
                val matches = when (patternType) {
                    "TITLE_ARTIST" ->
                        mark.originalTitle.equals(cleanTitle, ignoreCase = true) &&
                            mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    "TITLE" -> mark.originalTitle.equals(cleanTitle, ignoreCase = true)
                    "ARTIST" -> mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    else -> false
                }
                if (!matches) return@mapNotNull null

                val specificity = when (patternType) {
                    "TITLE_ARTIST" -> 3
                    "TITLE" -> 2
                    "ARTIST" -> 1
                    else -> 0
                }
                Triple(mark, specificity, mark.markedAt)
            }
            .maxWithOrNull(
                compareBy<Triple<ManualContentMark, Int, Long>> { it.second }
                    .thenBy { it.third }
            )
            ?.first
            ?.contentType
            ?.uppercase()
    }

    fun removeContentOverride(mark: ManualContentMark) {
        viewModelScope.launch {
            manualContentMarkDao.deleteMark(mark)
        }
    }

    companion object {
        const val CONTENT_TYPE_ALWAYS_MUSIC = "ALWAYS_MUSIC"
        const val CONTENT_TYPE_NON_MUSIC = "NON_MUSIC"
        const val CONTENT_TYPE_LEGACY_VIDEO = "VIDEO"
    }
}
