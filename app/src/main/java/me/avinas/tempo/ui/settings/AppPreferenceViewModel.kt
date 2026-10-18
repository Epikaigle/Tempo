package me.avinas.tempo.ui.settings

import me.avinas.tempo.data.local.entities.ManualContentRuleResolver
import me.avinas.tempo.data.local.DatabaseTransactionRunner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.avinas.tempo.data.DefaultMusicApps
import me.avinas.tempo.data.InstalledApp
import me.avinas.tempo.data.InstalledAppsProvider
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
    /** Rejected-but-installed apps awaiting an Allow decision. */
    val detectedApps: List<DetectedAppUi> = emptyList(),
    /** Apps that can never be tracked (voice notes, dialers, recorders), shown read-only. */
    val neverTrackedApps: List<InstalledApp> = emptyList(),
    /**
     * Installed apps on no list at all, matched against the current search. The screen used to
     * filter only Tempo's curated lists, so searching for an app Tempo did not know about
     * always returned "No apps found".
     */
    val searchResults: List<InstalledApp> = emptyList(),
    val installedPackageNames: Set<String> = emptySet(),
    val contentOverrides: List<ManualContentMark> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

/**
 * An inbox entry: an app Tempo refused to track, plus enough context for the user to recognize
 * what it was doing ("Voice message", 4 times).
 */data class DetectedAppUi(
    val packageName: String,
    val displayName: String,
    val lastTitle: String?,
    val detectionCount: Int
)

/** Snapshot of the Room-backed lists, combined before the query-dependent projection. */
private data class AppLists(
    val preinstalled: List<AppPreference>,
    val userAdded: List<AppPreference>,
    val blocked: List<AppPreference>,
    val detected: List<me.avinas.tempo.data.local.entities.DetectedApp>
)

@HiltViewModel
class AppPreferenceViewModel @Inject constructor(
    private val repository: AppPreferenceRepository,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val trackRepository: TrackRepository,
    private val listeningRepository: ListeningRepository,
    private val statsRepository: StatsRepository,
    private val transactionRunner: DatabaseTransactionRunner,
    private val installedAppsProvider: InstalledAppsProvider,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(AppPreferenceUiState())
    val uiState: StateFlow<AppPreferenceUiState> = _uiState.asStateFlow()

    /**
     * Every launchable app on the device, with labels.
     *
     * Populated from [InstalledAppsProvider], which memoises the scan. Doing this work here on
     * every screen open — as an earlier version did — meant one resource load per installed app
     * per visit, because resolving a label opens the target app's resources.
     */
    private val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    init {
        // Seed default apps for fresh installs (no-op if already seeded)
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedDefaultAppsIfNeeded()
            installedApps.value = installedAppsProvider.get()
        }

        // App state and manual content overrides both come from Room so the settings
        // screen and MusicTrackingService observe the same source of truth.
        viewModelScope.launch {
            val appLists =
                combine(
                    repository.getPreinstalledApps(),
                    repository.getUserAddedApps(),
                    repository.getBlockedApps(),
                    repository.getDetectedApps()
                ) { preinstalled, userAdded, blocked, detected ->
                    AppLists(preinstalled, userAdded, blocked, detected)
                }

            combine(
                appLists,
                manualContentMarkDao.getAllMarks(),
                _searchQuery,
                installedApps
            ) { lists, manualMarks, query, installed ->
                buildUiState(lists, manualMarks, query, installed)
            }
                .flowOn(Dispatchers.Default)
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    private fun buildUiState(
        lists: AppLists,
        manualMarks: List<ManualContentMark>,
        query: String,
        installed: List<InstalledApp>
    ): AppPreferenceUiState {
        val installedPackages = installed.mapTo(mutableSetOf()) { it.packageName }
        val matches: (String, String) -> Boolean = { displayName, packageName ->
            query.isBlank() ||
                displayName.contains(query, ignoreCase = true) ||
                packageName.contains(query, ignoreCase = true)
        }

        val preinstalled = lists.preinstalled
            .filter { matches(it.displayName, it.packageName) }
            .sortedWith(
                compareByDescending<AppPreference> { it.packageName in installedPackages }
                    .thenBy { it.displayName }
            )

        val userAdded = lists.userAdded
            .filter { matches(it.displayName, it.packageName) }
            .sortedWith(
                compareByDescending<AppPreference> { it.packageName in installedPackages }
                    .thenBy { it.displayName }
            )

        // Never-tracked apps are enforced in code, not as database rows, so they never appeared
        // in any section — which is why users could not find Viber or Signal on this screen at
        // all. Surface the installed ones, read-only, with the reason.
        val neverTracked = installed
            .filter { it.packageName in DefaultMusicApps.NEVER_TRACK_PACKAGES }
            .filter { matches(it.displayName, it.packageName) }

        // Everything the user could be looking for but that Tempo has no opinion on yet.
        val known = buildSet {
            lists.preinstalled.mapTo(this) { it.packageName }
            lists.userAdded.mapTo(this) { it.packageName }
            lists.blocked.mapTo(this) { it.packageName }
            lists.detected.mapTo(this) { it.packageName }
            addAll(DefaultMusicApps.NEVER_TRACK_PACKAGES)
        }

        val searchResults = if (query.isBlank()) {
            emptyList()
        } else {
            installed
                .filter { it.packageName !in known }
                .filter { matches(it.displayName, it.packageName) }
        }

        val blocked = lists.blocked
            .filter { it.packageName !in DefaultMusicApps.NEVER_TRACK_PACKAGES }
            .filter { matches(it.displayName, it.packageName) }

        val detectedApps = lists.detected
            .map { detected ->
                val installedApp = installed.firstOrNull { it.packageName == detected.packageName }
                DetectedAppUi(
                    packageName = detected.packageName,
                    displayName = installedApp?.displayName
                        ?: DefaultMusicApps.getDisplayName(detected.packageName),
                    lastTitle = detected.lastTitle,
                    detectionCount = detected.detectionCount
                )
            }
            .filter { matches(it.displayName, it.packageName) }
            .distinctBy { it.packageName }

        val contentOverrides = manualMarks.filter {
            it.contentType == CONTENT_TYPE_ALWAYS_MUSIC ||
                it.contentType == CONTENT_TYPE_NON_MUSIC ||
                it.contentType == CONTENT_TYPE_LEGACY_VIDEO
        }

        return AppPreferenceUiState(
            preinstalledApps = preinstalled,
            userAddedApps = userAdded,
            blockedApps = blocked,
            detectedApps = detectedApps,
            neverTrackedApps = neverTracked,
            searchResults = searchResults,
            installedPackageNames = installedPackages,
            contentOverrides = contentOverrides,
            searchQuery = query,
            isLoading = false
        )
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Grants tracking permission to an app the default-deny model rejected.
     *
     * Resolves the label from the installed list when the caller does not have one, so the
     * "Your Apps" entry is named correctly instead of showing a bare package name.
     */
    fun allowDetectedApp(packageName: String) {
        viewModelScope.launch {
            repository.allowDetectedApp(packageName, resolveDisplayName(packageName))
        }
    }

    /** Allows an app on no list at all: the one the user found by searching. */
    fun allowApp(packageName: String) {
        viewModelScope.launch {
            repository.addCustomApp(packageName, resolveDisplayName(packageName))
        }
    }

    /**
     * Blocks an app, whether or not it already has a preference row.
     *
     * One entry point covers all three places a Block button appears: a curated app in Music
     * Apps, an inbox suggestion, and an app found by search. Only the first has a row already.
     */
    fun blockApp(packageName: String) {
        viewModelScope.launch {
            repository.blockApp(packageName, resolveDisplayName(packageName))
        }
    }

    private fun resolveDisplayName(packageName: String): String =
        installedApps.value.firstOrNull { it.packageName == packageName }?.displayName
            ?: DefaultMusicApps.getDisplayName(packageName)

    fun toggleAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setAppEnabled(packageName, enabled)
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
            transactionRunner.run {
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
    }

    private suspend fun matchingTracks(title: String, artist: String): List<Track> =
        trackRepository.all().first().filter { track ->
            when {
                title.isNotBlank() && artist.isNotBlank() ->
                    track.title.trim().equals(title.trim(), ignoreCase = true) &&
                        track.artist.trim().equals(artist.trim(), ignoreCase = true)
                title.isNotBlank() -> track.title.trim().equals(title.trim(), ignoreCase = true)
                else -> track.artist.trim().equals(artist.trim(), ignoreCase = true)
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
        marks: List<me.avinas.tempo.data.local.entities.ManualContentMark>
    ): String? = ManualContentRuleResolver.resolve(marks, title, artist)?.contentType?.uppercase()

    fun removeContentOverride(mark: ManualContentMark) {
        viewModelScope.launch {
            var changed = false
            transactionRunner.run {
                manualContentMarkDao.deleteMark(mark)

                // Deleting a specific exception can reveal a broader rule underneath it.
                // Re-apply that effective rule to existing local data immediately so history/stats
                // and future tracking do not disagree until the next play.
                val remainingMarks = manualContentMarkDao.getAllSync()
                val affectedTracks = trackRepository.all().first().filter { track ->
                    ManualContentRuleResolver.matches(mark, track.title, track.artist)
                }

                affectedTracks.forEach { track ->
                    when (effectiveContentType(track.title, track.artist, remainingMarks)) {
                        CONTENT_TYPE_NON_MUSIC, CONTENT_TYPE_LEGACY_VIDEO -> {
                            listeningRepository.deleteByTrackId(track.id)
                            changed = true
                        }
                        CONTENT_TYPE_ALWAYS_MUSIC -> {
                            if (track.contentType != "MUSIC") {
                                trackRepository.update(track.copy(contentType = "MUSIC"))
                                changed = true
                            }
                        }
                    }
                }
            }

            if (changed) statsRepository.invalidateCache()
        }
    }

    companion object {
        const val CONTENT_TYPE_ALWAYS_MUSIC = "ALWAYS_MUSIC"
        const val CONTENT_TYPE_NON_MUSIC = "NON_MUSIC"
        const val CONTENT_TYPE_LEGACY_VIDEO = "VIDEO"
    }
}
