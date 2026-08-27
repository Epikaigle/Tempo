package me.avinas.tempo.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.profile.ProfileIdentityManager
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.ui.onboarding.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val tokenStorage: me.avinas.tempo.data.remote.spotify.SpotifyTokenStorage,
    private val preferencesRepository: me.avinas.tempo.data.repository.PreferencesRepository,
    private val listeningEventDao: ListeningEventDao,
    private val profileIdentityManager: ProfileIdentityManager,
    val gamificationRepository: me.avinas.tempo.data.repository.GamificationRepository,
    private val refreshCoordinator: me.avinas.tempo.data.repository.RefreshCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadData()
        observeDataChanges()
        checkSpotlightReminder()
    }
    
    private fun observeDataChanges() {
        viewModelScope.launch {
            _uiState
                .map { it.selectedTimeRange }
                .distinctUntilChanged()
                .flatMapLatest { timeRange ->
                    statsRepository.observeListeningOverview(timeRange, withLeeway = false)
                }
                .distinctUntilChanged()
                .collect { overview ->
                    _uiState.update { it.copy(listeningOverview = overview, hasData = overview.totalPlayCount > 0) }
                    loadData()
                }
        }
        
        // Also observe metadata updates (album art, artist images) from enrichment
        viewModelScope.launch {
            statsRepository.observeMetadataUpdates()
                .debounce(3_000)
                .collect {
                    loadData()
                }
        }
        
        // Listen for new track events from MusicTrackingService
        viewModelScope.launch {
            refreshCoordinator.refreshEvents
                .debounce(2_000)
                .collect {
                    loadData()
                }
        }
        
        // Listen for preference changes (e.g. Gamification toggle, spotlight viewed state)
        viewModelScope.launch {
            var lastGamificationState: Boolean? = null
            preferencesRepository.preferences().collect { prefs ->
                val state = prefs?.isGamificationEnabled ?: true
                if (lastGamificationState != null && lastGamificationState != state) {
                    loadData() // Reload if toggle changed
                }
                lastGamificationState = state

                // Compute spotlight story viewed state
                val storyTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                val currentKey = if (storyTimeRange != null) {
                    me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.storyPeriodKey(storyTimeRange)
                } else null
                val viewed = currentKey != null && currentKey == prefs?.lastSpotlightStoryViewed
                _uiState.update { it.copy(spotlightStoryViewed = viewed) }
            }
        }
    }

    fun onTimeRangeSelected(timeRange: TimeRange) {
        _uiState.update { it.copy(selectedTimeRange = timeRange, isLoading = true) }
        loadData()
    }

    suspend fun refresh() {
        val startTime = System.currentTimeMillis()
        _uiState.update { it.copy(isRefreshing = true, isLoading = true) }
        try {
            fetchData()
        } finally {
            // Ensure spinner shows for at least 600ms so it doesn't flash away
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 600) delay(600 - elapsed)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            fetchData()
        }
    }

    private suspend fun fetchData() {
        try {
            val timeRange = _uiState.value.selectedTimeRange
            
            // Fetch all required data in PARALLEL using async/await
            // This reduces total loading time from sum of all calls to max of all calls
            coroutineScope {
                val overviewDeferred = async { statsRepository.getListeningOverview(timeRange, withLeeway = false) }
                val periodComparisonDeferred = async { statsRepository.getPeriodComparison(timeRange, withLeeway = false) }
                // Dynamic data limit based on time range for chart visualization
                val dataLimit = when (timeRange) {
                    TimeRange.TODAY, TimeRange.THIS_WEEK -> 7
                    TimeRange.THIS_MONTH -> 31
                    TimeRange.THIS_YEAR -> 365
                    TimeRange.ALL_TIME -> 365
                }
                val rawDailyListeningDeferred = async { statsRepository.getDailyListening(timeRange, dataLimit, withLeeway = false) }
                val topTracksDeferred = async { statsRepository.getTopTracks(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1, withLeeway = false) }
                val topArtistsDeferred = async { statsRepository.getTopArtists(timeRange, sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE, pageSize = 1, withLeeway = false) }
                val discoveryStatsDeferred = async { statsRepository.getDiscoveryStats(timeRange, withLeeway = false) }
                val mostActiveHourDeferred = async { statsRepository.getMostActiveHour(timeRange, withLeeway = false) }
                val audioFeaturesDeferred = async { statsRepository.getAudioFeaturesStats(timeRange, withLeeway = false) }
                val insightsDeferred = async { statsRepository.getInsights(timeRange, withLeeway = false) }
                // Use ALL_TIME stats for rate app check - ensures consistent behavior regardless of current filter
                val allTimeOverviewDeferred = async { statsRepository.getListeningOverview(TimeRange.ALL_TIME) }
                val profileIdentityDeferred = async { profileIdentityManager.getProfileIdentity() }
                
                // Today's Listen Widget data
                val todayOverviewDeferred = async { statsRepository.getListeningOverview(TimeRange.TODAY) }
                val todayTopTracksDeferred = async { statsRepository.getTopTracks(TimeRange.TODAY, sortBy = me.avinas.tempo.data.repository.SortBy.PLAY_COUNT, pageSize = 5) }
                val todayTopArtistsDeferred = async { statsRepository.getTopArtists(TimeRange.TODAY, sortBy = me.avinas.tempo.data.repository.SortBy.PLAY_COUNT, pageSize = 5) }
                val todayHourlyDeferred = async { statsRepository.getHourlyDistribution(TimeRange.TODAY) }
                val todayPeriodComparisonDeferred = async { statsRepository.getPeriodComparison(TimeRange.TODAY, withLeeway = false) }
                
                // Read isGamificationEnabled setting
                val isGamificationEnabledDeferred = async {
                    preferencesRepository.preferences().first()?.isGamificationEnabled ?: true
                }
                
                // Spotlight story top track — fetched for the unlocked story period with leeway
                // so the ring shows the correct album art from the story's time period.
                val spotlightTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                val spotlightTopTrackDeferred = if (spotlightTimeRange != null) {
                    async {
                        statsRepository.getTopTracks(
                            spotlightTimeRange,
                            sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE,
                            pageSize = 1,
                            withLeeway = true  // Match story system's period calculation
                        )
                    }
                } else null
                
                // Await all results
                val overview = overviewDeferred.await()
                val periodComparison = periodComparisonDeferred.await()
                val rawDailyListening = rawDailyListeningDeferred.await()
                val topTracks = topTracksDeferred.await()
                val topArtists = topArtistsDeferred.await()
                val discoveryStats = discoveryStatsDeferred.await()
                val mostActiveHour = mostActiveHourDeferred.await()
                val audioFeatures = audioFeaturesDeferred.await()
                val allTimeOverview = allTimeOverviewDeferred.await()
                val profileIdentity = profileIdentityDeferred.await()
                val spotlightTopTrack = spotlightTopTrackDeferred?.await()?.items?.firstOrNull()
                val userName = profileIdentity.userName
                    .takeIf { it.isNotBlank() }
                    ?: tokenStorage.getUserDisplayName()?.split(" ")?.firstOrNull()
                    ?: "User"
                
                // Await Today's Listen data
                val todayOverview = todayOverviewDeferred.await()
                val todayTopTracks = todayTopTracksDeferred.await()
                val todayTopArtists = todayTopArtistsDeferred.await()
                val todayHourly = todayHourlyDeferred.await()
                val todayPeriodComparison = todayPeriodComparisonDeferred.await()
                
                // Fetch Gamification Data
                val isGamificationEnabled = isGamificationEnabledDeferred.await()
                val userLevel = if (isGamificationEnabled) gamificationRepository.getUserLevel() else null
                val nextBadge = if (isGamificationEnabled) gamificationRepository.getNextEarnableBadge() else null
                
                // Process chart data and generate labels for interactive trend line
                val (dailyListening, chartLabels) = processChartData(timeRange, rawDailyListening)
                
                // Inject Gamification Card if enabled
                val combinedInsights = mutableListOf<InsightCardData>()
                if (isGamificationEnabled && userLevel != null) {
                    combinedInsights.add(
                        InsightCardData(
                            title = "Level Progress", // Not displayed by GamificationCard
                            description = "Your current level and next badge",
                            type = InsightType.LOYALTY,
                            payload = InsightPayload.GamificationProgress(
                                level = userLevel,
                                nextBadge = nextBadge
                            )
                        )
                    )
                }
                
                // Combine insights (Gamification Card first, then others)
                combinedInsights.addAll(insightsDeferred.await())
                
                val hasData = overview.totalPlayCount > 0
                val earliestTimestamp = statsRepository.getEarliestDataTimestamp()
                val isNewUser = earliestTimestamp == null
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listeningOverview = overview,
                        periodComparison = periodComparison,
                        chartLabels = chartLabels,
                        dailyListening = dailyListening,
                        topTrack = topTracks.items.firstOrNull(),
                        topArtist = topArtists.items.firstOrNull(),
                        spotlightTopTrack = spotlightTopTrack,
                        discoveryStats = discoveryStats,
                        mostActiveHour = mostActiveHour,
                        audioFeatures = audioFeatures,
                        insights = combinedInsights,
                        userName = userName,
                        profileImagePath = profileIdentity.profileImagePath,
                        hasData = hasData,
                        isNewUser = isNewUser,
                        // visible until the user actively interacts with it. Without this guard,
                        // any subsequent data reload (e.g. time-range change) would call
                        // shouldShowRateApp() which now sees the 3-day cooldown has just been
                        // written and returns false, silently dismissing the popup mid-interaction.
                        showRateAppPopup = it.showRateAppPopup || shouldShowRateApp(),
                        isGamificationEnabled = isGamificationEnabled,
                        // Today's Listen Widget + Today Overview overlay lists
                        todayOverview = todayOverview,
                        todayTopTrack = todayTopTracks.items.firstOrNull(),
                        todayTopArtist = todayTopArtists.items.firstOrNull(),
                        todayTopTracks = todayTopTracks.items.take(5),
                        todayTopArtists = todayTopArtists.items.take(5),
                        todayHourlyDistribution = todayHourly,
                        todayPeriodComparison = todayPeriodComparison
                    )
                }

                // Share nudge — evaluate once per session, once home data is ready.
                maybeShowShareNudge()
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private suspend fun shouldShowRateApp(): Boolean {
        // Check cheapest gates first to short-circuit before hitting the DB.
        val preferences = context.dataStore.data.first()

        // 1. Already handled — permanent suppression.
        val hasRated = preferences[booleanPreferencesKey("rate_app_rated")] ?: false
        if (hasRated) return false

        // 2. Dismissed too many times.
        val dismissCount = preferences[intPreferencesKey("rate_app_dismiss_count")] ?: 0
        if (dismissCount >= 2) return false

        // 3. Cooldown: don't re-prompt within 3 days of the last showing.
        val lastShown = preferences[longPreferencesKey("rate_app_last_shown")] ?: 0L
        val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastShown <= threeDaysMs) return false

        // 4. Install age: only prompt users who have had the app for at least 7 days.
        //    firstInstallTime survives app updates but not reinstalls.
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        val firstInstallTime = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .firstInstallTime
        } catch (_: Exception) {
            System.currentTimeMillis() // fail-open: don't block the prompt if we can't read
        }
        if (System.currentTimeMillis() - firstInstallTime < sevenDaysMs) return false

        // 5. Engagement: > 2 hours REAL listening AND > 50 REAL plays
        //    (excludes imported Spotify history — source NOT LIKE '%import%')
        val realListeningTimeMs = listeningEventDao.getRealListeningTimeMs()
        val realPlayCount = listeningEventDao.getRealPlayCount()
        if (realListeningTimeMs < 2 * 60 * 60 * 1000 || realPlayCount < 50) return false

        // All gates passed — record the timestamp and show the popup.
        context.dataStore.edit { prefs ->
            prefs[longPreferencesKey("rate_app_last_shown")] = System.currentTimeMillis()
        }
        return true
    }
    
    /**
     * Process chart data and generate labels based on time range.
     * Returns chronologically sorted data (oldest to newest) with matching labels.
     * Aggregates data by month when there are too many days for better visualization.
     * 
     * Feature contributed by @FlazeIGuess (PR #1) with modifications.
     */
    private fun processChartData(
        timeRange: TimeRange,
        rawData: List<me.avinas.tempo.data.stats.DailyListening>
    ): Pair<List<me.avinas.tempo.data.stats.DailyListening>, List<String>> {
        val today = java.time.LocalDate.now()
        
        return when (timeRange) {
            TimeRange.TODAY, TimeRange.THIS_WEEK -> {
                val last7Days = (0..6).map { dayOffset -> 
                    today.minusDays(dayOffset.toLong()) 
                }.reversed()
                
                val rawDataMap = rawData.associateBy { it.date }
                val data = last7Days.map { date ->
                    val dateStr = date.toString()
                    rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
                        date = dateStr,
                        playCount = 0,
                        totalTimeMs = 0,
                        uniqueTracks = 0,
                        uniqueArtists = 0
                    )
                }
                
                // Force English locale for day names
                val labels = last7Days.map { date ->
                    date.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.SHORT,
                        java.util.Locale.ENGLISH
                    )
                }
                
                Pair(data, labels)
            }
            
            TimeRange.THIS_MONTH -> {
                val firstDayOfMonth = today.withDayOfMonth(1)
                val daysInMonth = (0 until today.dayOfMonth).map { 
                    firstDayOfMonth.plusDays(it.toLong()) 
                }
                
                val rawDataMap = rawData.associateBy { it.date }
                val data = daysInMonth.map { date ->
                    val dateStr = date.toString()
                    rawDataMap[dateStr] ?: me.avinas.tempo.data.stats.DailyListening(
                        date = dateStr,
                        playCount = 0,
                        totalTimeMs = 0,
                        uniqueTracks = 0,
                        uniqueArtists = 0
                    )
                }
                
                // Show "Day 1", "Day 2", etc.
                val labels = daysInMonth.map { date ->
                    "Day ${date.dayOfMonth}"
                }
                
                Pair(data, labels)
            }
            
            TimeRange.THIS_YEAR -> {
                val sortedData = rawData.sortedBy { it.date }
                
                // If more than 60 days, aggregate by month
                if (sortedData.size > 60) {
                    val monthlyData = aggregateByMonth(sortedData)
                    val labels = monthlyData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            date.month.getDisplayName(
                                java.time.format.TextStyle.SHORT,
                                java.util.Locale.ENGLISH
                            )
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(monthlyData, labels)
                } else {
                    // Show daily data with day + month labels
                    val labels = sortedData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            "${date.dayOfMonth} ${date.month.getDisplayName(
                                java.time.format.TextStyle.SHORT,
                                java.util.Locale.ENGLISH
                            )}"
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(sortedData, labels)
                }
            }
            
            TimeRange.ALL_TIME -> {
                val sortedData = rawData.sortedBy { it.date }
                
                // If 60 days or less, show daily data like THIS_YEAR
                // If more than 60 days, aggregate by month
                if (sortedData.size <= 60) {
                    // Show daily data with day + month labels
                    val labels = sortedData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            val year = date.year
                            val currentYear = today.year
                            
                            // For current year, show "15 Jan"
                            // For past years, show "15 Jan 2023"
                            if (year == currentYear) {
                                "${date.dayOfMonth} ${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )}"
                            } else {
                                "${date.dayOfMonth} ${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )} ${year}"
                            }
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(sortedData, labels)
                } else {
                    // Aggregate by month for better visualization
                    val monthlyData = aggregateByMonth(sortedData)
                    val labels = monthlyData.map { daily ->
                        try {
                            val date = java.time.LocalDate.parse(daily.date)
                            val year = date.year
                            val currentYear = today.year
                            
                            // For current year, show "Jan"
                            // For past years, show "Jan 2023"
                            if (year == currentYear) {
                                date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )
                            } else {
                                "${date.month.getDisplayName(
                                    java.time.format.TextStyle.SHORT,
                                    java.util.Locale.ENGLISH
                                )} ${year}"
                            }
                        } catch (e: Exception) {
                            daily.date
                        }
                    }
                    Pair(monthlyData, labels)
                }
            }
        }
    }
    
    /**
     * Aggregates daily listening data by month.
     * Returns one data point per month with the first day of the month as the date.
     */
    private fun aggregateByMonth(
        dailyData: List<me.avinas.tempo.data.stats.DailyListening>
    ): List<me.avinas.tempo.data.stats.DailyListening> {
        return dailyData
            .groupBy { daily ->
                try {
                    val date = java.time.LocalDate.parse(daily.date)
                    "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
                } catch (e: Exception) {
                    daily.date
                }
            }
            .map { (yearMonth, monthData) ->
                // Use the first day of the month as the representative date
                val firstDate = try {
                    val parts = yearMonth.split("-")
                    "${parts[0]}-${parts[1]}-01"
                } catch (e: Exception) {
                    monthData.first().date
                }
                
                me.avinas.tempo.data.stats.DailyListening(
                    date = firstDate,
                    playCount = monthData.sumOf { it.playCount },
                    totalTimeMs = monthData.sumOf { it.totalTimeMs },
                    // Use sum for unique counts to approximate monthly totals
                    uniqueTracks = monthData.sumOf { it.uniqueTracks },
                    uniqueArtists = monthData.sumOf { it.uniqueArtists }
                )
            }
            .sortedBy { it.date }
    }

    fun onRateAppFlowHandled() {
        // Dismiss immediately so the user sees instant feedback.
        _uiState.update { it.copy(showRateAppPopup = false) }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                // The Play Review API does not tell us whether the user submitted a rating.
                // We use this flag only to suppress re-prompting after handling the CTA.
                prefs[booleanPreferencesKey("rate_app_rated")] = true
            }
        }
    }
    
    fun onRateAppDismissed() {
        // Dismiss immediately so the user sees instant feedback.
        _uiState.update { it.copy(showRateAppPopup = false) }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[intPreferencesKey("rate_app_dismiss_count")] ?: 0
                prefs[intPreferencesKey("rate_app_dismiss_count")] = current + 1
            }
        }
    }
    
    /**
     * Session guard: the share nudge is evaluated at most once per app session so a
     * time-range change or pull-to-refresh can never re-trigger it mid-use.
     */
    private var shareNudgeEvaluated = false

    /**
     * Smart, non-intrusive promotion of the share feature. On app open (once per
     * session, after home data settles) it checks a chain of gates and — only when
     * all pass — opens the artist share preview directly on the home screen.
     * Gates (cheapest first):
     *  1. Dismissed 3 times — the user isn't interested; stop permanently.
     *  2. Cooldown — at most once every 7 days.
     *  3. Shared anything in the last 14 days — they already know the feature.
     *  4. Fewer than 20 real plays — nothing worth sharing yet (also skips new users).
     *  5. Never stacks on top of the rate popup or spotlight reminder.
     */
    private fun maybeShowShareNudge() {
        if (shareNudgeEvaluated) return
        shareNudgeEvaluated = true
        viewModelScope.launch {
            try {
                // Let the home screen settle before any overlay appears.
                delay(1500)

                val state = _uiState.value
                if (state.showRateAppPopup || state.showSpotlightReminder || state.showShareNudge) return@launch

                val preferences = context.dataStore.data.first()

                // 1. Dismissed too many times — permanent suppression.
                val dismissCount = preferences[intPreferencesKey("share_nudge_dismiss_count")] ?: 0
                if (dismissCount >= 3) return@launch

                // 2. Cooldown: at most once every 7 days.
                val lastShown = preferences[longPreferencesKey("share_nudge_last_shown")] ?: 0L
                if (System.currentTimeMillis() - lastShown <= 7 * 24 * 60 * 60 * 1000L) return@launch

                // 3. Shared in the last 14 days — they already know the feature.
                val lastShared = preferences[longPreferencesKey("share_last_success")] ?: 0L
                if (lastShared > 0L && System.currentTimeMillis() - lastShared <= 14 * 24 * 60 * 60 * 1000L) return@launch

                // 4. Engagement: enough real plays for a card worth sharing.
                if (listeningEventDao.getRealPlayCount() < 20) return@launch

                // Freshest recap period wins for the card; weekly otherwise.
                val today = java.time.LocalDate.now()
                val timeRange =
                    if (today.dayOfMonth == today.lengthOfMonth()) TimeRange.THIS_MONTH
                    else TimeRange.THIS_WEEK
                val artists = statsRepository.getTopArtists(
                    timeRange,
                    sortBy = me.avinas.tempo.data.repository.SortBy.COMBINED_SCORE,
                    pageSize = 10,
                    withLeeway = false
                ).items
                if (artists.isEmpty()) return@launch
                val overview = statsRepository.getListeningOverview(timeRange, withLeeway = false)
                if (overview.totalPlayCount <= 0) return@launch

                // Mark shown immediately so re-entry can't double-fire.
                context.dataStore.edit { prefs ->
                    prefs[longPreferencesKey("share_nudge_last_shown")] = System.currentTimeMillis()
                }

                _uiState.update {
                    it.copy(
                        showShareNudge = true,
                        shareNudgeTimeRange = timeRange,
                        shareNudgeArtists = artists,
                        shareNudgeOverview = overview
                    )
                }
            } catch (e: Exception) {
                android.util.Log.d("HomeViewModel", "Share nudge skipped: ${e.message}")
            }
        }
    }

    /**
     * The user closed the share preview without sharing. Counts toward the
     * 3-dismiss permanent suppression.
     */
    fun onShareNudgeDismissed() {
        _uiState.update {
            it.copy(
                showShareNudge = false,
                shareNudgeTimeRange = null,
                shareNudgeArtists = emptyList(),
                shareNudgeOverview = null
            )
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[intPreferencesKey("share_nudge_dismiss_count")] ?: 0
                prefs[intPreferencesKey("share_nudge_dismiss_count")] = current + 1
            }
        }
    }

    /**
     * The user shared from the nudge. Engagement: reset the dismiss counter so the
     * nudge can return after the normal cooldown, and record the share.
     */
    fun onShareNudgeShared() {
        _uiState.update {
            it.copy(
                showShareNudge = false,
                shareNudgeTimeRange = null,
                shareNudgeArtists = emptyList(),
                shareNudgeOverview = null
            )
        }
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[intPreferencesKey("share_nudge_dismiss_count")] = 0
                prefs[longPreferencesKey("share_last_success")] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Check if we should show a Spotlight Story reminder.
     * Shows reminder on:
     * - Sunday (for THIS_WEEK story)
     * - Last day of the month (for THIS_MONTH story)
     * - December 1st (for THIS_YEAR story)
     */
    private fun checkSpotlightReminder() {
        viewModelScope.launch {
            val today = java.time.LocalDate.now()
            val todayString = today.toString() // YYYY-MM-DD format
            
            android.util.Log.d("HomeViewModel", "Checking Spotlight reminder for date: $todayString")
            
            // Get user preferences to check if reminder already shown
            val preferences = preferencesRepository.preferences().first() ?: return@launch

            // Check for monthly reminder (last day of month)
            val isLastDayOfMonth = today.dayOfMonth == today.lengthOfMonth()
            if (isLastDayOfMonth && preferences.lastMonthlyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is last day of month, checking data availability...")
                // Ensure we have data for this month
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_MONTH)
                android.util.Log.d("HomeViewModel", "Monthly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing MONTHLY Spotlight reminder")
                    _uiState.update { 
                        it.copy(
                            showSpotlightReminder = true,
                            reminderTimeRange = TimeRange.THIS_MONTH,
                            reminderType = me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY
                        ) 
                    }
                    return@launch
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping monthly reminder: no data for THIS_MONTH")
                }
            } else if (isLastDayOfMonth) {
                android.util.Log.d("HomeViewModel", "Last day of month, but already shown: ${preferences.lastMonthlyReminderShown}")
            }
            
            // Check for yearly reminder (December 1st)
            val isDecemberFirst = today.monthValue == 12 && today.dayOfMonth == 1
            if (isDecemberFirst && preferences.lastYearlyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is December 1st, checking data availability...")
                // Ensure we have data for this year
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_YEAR)
                android.util.Log.d("HomeViewModel", "Yearly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing YEARLY Spotlight reminder")
                    _uiState.update { 
                        it.copy(
                            showSpotlightReminder = true,
                            reminderTimeRange = TimeRange.THIS_YEAR,
                            reminderType = me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY
                        ) 
                    }
                    return@launch
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping yearly reminder: no data for THIS_YEAR")
                }
            } else if (isDecemberFirst) {
                android.util.Log.d("HomeViewModel", "December 1st, but already shown: ${preferences.lastYearlyReminderShown}")
            }
            
            // Check for weekly reminder (Sunday)
            val isSunday = today.dayOfWeek == java.time.DayOfWeek.SUNDAY
            if (isSunday && preferences.lastWeeklyReminderShown != todayString) {
                android.util.Log.d("HomeViewModel", "Is Sunday, checking data availability...")
                // Ensure we have data for this week
                val overview = statsRepository.getListeningOverview(TimeRange.THIS_WEEK)
                android.util.Log.d("HomeViewModel", "Weekly data: totalPlayCount=${overview.totalPlayCount}")
                if (overview.totalPlayCount > 0) {
                    android.util.Log.i("HomeViewModel", "✅ Showing WEEKLY Spotlight reminder")
                    _uiState.update { 
                        it.copy(
                            showSpotlightReminder = true,
                            reminderTimeRange = TimeRange.THIS_WEEK,
                            reminderType = me.avinas.tempo.ui.components.SpotlightReminderType.WEEKLY
                        ) 
                    }
                } else {
                    android.util.Log.d("HomeViewModel", "❌ Skipping weekly reminder: no data for THIS_WEEK")
                }
            } else if (isSunday) {
                android.util.Log.d("HomeViewModel", "Sunday, but already shown: ${preferences.lastWeeklyReminderShown}")
            }
        }
    }
    
    /**
     * Dismiss the Spotlight reminder and save state to prevent showing again.
     */
    fun dismissSpotlightReminder() {
        viewModelScope.launch {
            val today = java.time.LocalDate.now().toString()
            val preferences = preferencesRepository.preferences().first() ?: return@launch
            
            // Update preferences based on reminder type
            val updatedPrefs = when (_uiState.value.reminderType) {
                me.avinas.tempo.ui.components.SpotlightReminderType.WEEKLY -> 
                    preferences.copy(lastWeeklyReminderShown = today)
                me.avinas.tempo.ui.components.SpotlightReminderType.MONTHLY -> 
                    preferences.copy(lastMonthlyReminderShown = today)
                me.avinas.tempo.ui.components.SpotlightReminderType.YEARLY -> 
                    preferences.copy(lastYearlyReminderShown = today)
                null -> preferences
            }
            
            preferencesRepository.upsert(updatedPrefs)
            
            // Hide popup
            _uiState.update { 
                it.copy(
                    showSpotlightReminder = false,
                    reminderTimeRange = null,
                    reminderType = null
                ) 
            }
        }
    }

    /**
     * Mark the current Spotlight story period as viewed.
     * Called when the user taps the story ring/card on the home screen.
     * Persists the period key so the ring shows gray until a new story period unlocks.
     */
    fun onSpotlightViewed() {
        viewModelScope.launch {
            val storyTimeRange = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                ?: return@launch
            val key = me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.storyPeriodKey(storyTimeRange)
            preferencesRepository.updateLastSpotlightStoryViewed(key)
        }
    }
}

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.THIS_WEEK,
    val hasData: Boolean = false,
    val isNewUser: Boolean = false,
    val isGamificationEnabled: Boolean = true,
    
    // Data fields
    val listeningOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val periodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null,
    val dailyListening: List<me.avinas.tempo.data.stats.DailyListening> = emptyList(),
    val chartLabels: List<String> = emptyList(),  // Labels for interactive chart
    val topTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val topArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats? = null,
    val mostActiveHour: me.avinas.tempo.data.stats.HourlyDistribution? = null,
    val audioFeatures: me.avinas.tempo.data.stats.AudioFeaturesStats? = null,
    val insights: List<me.avinas.tempo.data.stats.InsightCardData> = emptyList(),
    val userName: String? = null,
    val profileImagePath: String? = null,
    val showRateAppPopup: Boolean = false,

    // Share Nudge — gated promotion of the share feature; opens the artist share
    // preview (StatsShareDialog) directly instead of showing a message popup.
    val showShareNudge: Boolean = false,
    val shareNudgeTimeRange: TimeRange? = null,
    val shareNudgeArtists: List<me.avinas.tempo.data.stats.TopArtist> = emptyList(),
    val shareNudgeOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    
    // Spotlight Story Reminder
    val showSpotlightReminder: Boolean = false,
    val reminderTimeRange: TimeRange? = null,
    val reminderType: me.avinas.tempo.ui.components.SpotlightReminderType? = null,
    
    // Spotlight story top track (correct period, with leeway)
    val spotlightTopTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    
    // Today's Listen Widget
    val todayOverview: me.avinas.tempo.data.stats.ListeningOverview? = null,
    val todayTopTrack: me.avinas.tempo.data.stats.TopTrack? = null,
    val todayTopArtist: me.avinas.tempo.data.stats.TopArtist? = null,
    val todayTopTracks: List<me.avinas.tempo.data.stats.TopTrack> = emptyList(),
    val todayTopArtists: List<me.avinas.tempo.data.stats.TopArtist> = emptyList(),
    val todayHourlyDistribution: List<me.avinas.tempo.data.stats.HourlyDistribution> = emptyList(),
    val todayPeriodComparison: me.avinas.tempo.data.stats.PeriodComparison? = null,
    val spotlightStoryViewed: Boolean = false
)
