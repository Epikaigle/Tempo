package me.avinas.tempo.ui.home

import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDim
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningDeep
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.TempoSurfaceSunken

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.TimePeriodSelector
import me.avinas.tempo.ui.components.VibeShareCard
import me.avinas.tempo.ui.home.components.*
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.TimeRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.repository.GamificationRepository
import me.avinas.tempo.ui.profile.CompactLevelRing
import me.avinas.tempo.ui.components.LevelUpOverlay
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.utils.ReviewUtils
import me.avinas.tempo.ui.theme.TempoPrimaryMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToStats: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToSpotlight: (TimeRange?, Boolean) -> Unit,
    onNavigateToSupportedApps: () -> Unit,
    onNavigateToProfile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var isLaunchingReview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    
    // Gamification state
    val gamificationRepo = viewModel.gamificationRepository
    val userLevel by gamificationRepo.observeUserLevel().collectAsState(initial = null)
    
    // Level-up detection
    var lastKnownLevel by remember { mutableStateOf(-1) }
    var showLevelUp by remember { mutableStateOf(false) }
    var levelUpLevel by remember { mutableStateOf(1) }
    var levelUpTitle by remember { mutableStateOf("") }
    var showVibeShare by remember { mutableStateOf(false) }
    val audioFeatures = uiState.audioFeatures
    
    LaunchedEffect(userLevel?.currentLevel) {
        val currentLevel = userLevel?.currentLevel ?: 0
        if (lastKnownLevel >= 0 && currentLevel > lastKnownLevel) {
            levelUpLevel = currentLevel
            levelUpTitle = userLevel?.title ?: ""
            showLevelUp = true
        }
        if (currentLevel > 0) lastKnownLevel = currentLevel
    }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    scope.launch {
                        viewModel.refresh()
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(bottom = 200.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item(key = "header") {
                        VibeHeader(
                            energy = uiState.audioFeatures?.averageEnergy ?: 0.5f,
                            valence = uiState.audioFeatures?.averageValence ?: 0.5f,
                            userName = uiState.userName ?: stringResource(R.string.home_user_default),
                            profileImagePath = uiState.profileImagePath,
                            isNewUser = uiState.isNewUser,
                            userLevel = userLevel?.currentLevel,
                            levelProgress = userLevel?.levelProgress ?: 0f,
                            levelTitle = userLevel?.title,
                            isGamificationEnabled = uiState.isGamificationEnabled,
                            onLevelClick = onNavigateToProfile
                        )
                    }
                    
                    if (uiState.hasData) {
                        item(key = "hero") {
                            val overview = uiState.listeningOverview
                            val comparison = uiState.periodComparison
                            
                            val timeString = remember(uiState.listeningOverview?.totalListeningTimeMs) {
                                val totalMs = uiState.listeningOverview?.totalListeningTimeMs ?: 0
                                val totalMinutes = totalMs / 1000 / 60
                                val hours = totalMinutes / 60
                                val minutes = totalMinutes % 60
                                val decimalTime = String.format(java.util.Locale.US, "%.1f", totalMinutes / 60.0)
                                if (hours == 0L) {
                                    if (minutes > 0) "${minutes}m" else "0m"
                                } else if (hours < 10) {
                                    "${decimalTime}h"
                                } else {
                                    "${hours}h ${minutes}m"
                                }
                            }

                            val trendData = remember(uiState.dailyListening) {
                                uiState.dailyListening.map { it.totalTimeMs.toFloat() / 1000 / 60 }
                            }
                            
                            val dailyLabels = uiState.chartLabels

                            val periodLabel = when(uiState.selectedTimeRange) {
                                TimeRange.TODAY -> stringResource(R.string.period_today)
                                TimeRange.THIS_WEEK -> stringResource(R.string.period_this_week)
                                TimeRange.THIS_MONTH -> stringResource(R.string.period_this_month)
                                TimeRange.THIS_YEAR -> stringResource(R.string.period_this_year)
                                TimeRange.ALL_TIME -> stringResource(R.string.period_all_time)
                            }
                            
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 16.dp)
                            ) {
                                HeroCard(
                                    userName = uiState.userName ?: stringResource(R.string.home_user_default),
                                    listeningTime = timeString,
                                    periodLabel = periodLabel,
                                    timeChangePercent = uiState.periodComparison?.timeChangePercent ?: 0.0,
                                    trendData = trendData,
                                    selectedRange = uiState.selectedTimeRange,
                                    dailyLabels = dailyLabels
                                )
                            }
                        }

                        item(key = "spotlight") {
                            val walkthroughController = me.avinas.tempo.ui.components.LocalWalkthroughController.current
                            LaunchedEffect(uiState.hasData) {
                                if (uiState.hasData) {
                                    walkthroughController.checkAndTrigger(me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT)
                                }
                            }

                            val directStoryTimeRange = remember {
                                me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.getDirectStoryTimeRange()
                            }

                            // Stable key for the currently-unlocked story period. When it matches the
                            // persisted lastViewedSpotlightPeriod the ring renders gray (Instagram-style).
                            val currentPeriodKey = remember(directStoryTimeRange) {
                                directStoryTimeRange?.let {
                                    "${it.name}:${me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter.effectivePeriodStart(it)}"
                                }
                            }
                            val spotlightViewed = currentPeriodKey != null &&
                                    currentPeriodKey == uiState.lastViewedSpotlightPeriod

                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                SpotlightStoryCard(
                                    onClick = {
                                        walkthroughController.dismiss()
                                        if (directStoryTimeRange != null) {
                                            onNavigateToSpotlight(directStoryTimeRange, false)
                                        } else {
                                            onNavigateToSpotlight(null, false)
                                        }
                                    },
                                    onRingClick = {
                                        walkthroughController.dismiss()
                                        // Mark this story period as viewed so the ring turns gray
                                        // and stays gray until a new period unlocks.
                                        currentPeriodKey?.let { viewModel.markSpotlightViewed(it) }
                                        if (directStoryTimeRange != null) {
                                            onNavigateToSpotlight(directStoryTimeRange, true)
                                        }
                                    },
                                    albumArtUrl = uiState.spotlightTopTrack?.albumArtUrl,
                                    storyAvailable = directStoryTimeRange != null,
                                    viewed = spotlightViewed,
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        walkthroughController.registerTarget(
                                            me.avinas.tempo.ui.components.WalkthroughStep.HOME_SPOTLIGHT,
                                            coordinates
                                        )
                                    }
                                )
                            }
                        }

                        item(key = "quickstats") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                QuickStatsRow(
                                    topArtistName = uiState.topArtist?.artist,
                                    topArtistImage = uiState.topArtist?.imageUrl,
                                    topTrackName = uiState.topTrack?.title,
                                    topTrackImage = uiState.topTrack?.albumArtUrl,
                                    onArtistClick = {
                                        val artist = uiState.topArtist
                                        if (artist != null) {
                                            val id = artist.artistId
                                            if (id != null && id > 0) {
                                                onNavigateToArtist("id:$id")
                                            } else {
                                                onNavigateToArtist(artist.artist)
                                            }
                                        }
                                    },
                                    onTrackClick = {
                                        uiState.topTrack?.trackId?.let { trackId ->
                                            onNavigateToTrack(trackId)
                                        }
                                    }
                                )
                            }
                        }

                    } else if (!uiState.isLoading) {
                        item(key = "empty") {
                            me.avinas.tempo.ui.components.EmptyState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(me.avinas.tempo.ui.utils.rememberScreenHeightPercentage(0.7f)),
                                timeRange = if (uiState.isNewUser) null else uiState.selectedTimeRange,
                                onCheckSupportedApps = onNavigateToSupportedApps
                            )
                        }
                    }
                    
                    if (uiState.todayOverview?.totalPlayCount?.let { it > 0 } == true) {
                        item(key = "todays_listen") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                TodaysListenWidget(
                                    todayOverview = uiState.todayOverview,
                                    topTrack = uiState.todayTopTrack,
                                    topArtist = uiState.todayTopArtist,
                                    hourlyDistribution = uiState.todayHourlyDistribution,
                                    onTrackClick = {
                                        uiState.todayTopTrack?.trackId?.let { trackId ->
                                            onNavigateToTrack(trackId)
                                        }
                                    },
                                    onArtistClick = {
                                        val artist = uiState.todayTopArtist
                                        if (artist != null) {
                                            val id = artist.artistId
                                            if (id != null && id > 0) {
                                                onNavigateToArtist("id:$id")
                                            } else {
                                                onNavigateToArtist(artist.artist)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.insights.isNotEmpty()) {
                        item(key = "insights_header") {
                            Text(
                                text = stringResource(R.string.home_your_signal),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp)
                            )
                        }
                        
                        item(key = "insights_feed") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                InsightFeed(
                                    insights = uiState.insights,
                                    onNavigateToTrack = onNavigateToTrack,
                                    onNavigateToArtist = onNavigateToArtist
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }

            val headerAlpha by remember {
                derivedStateOf {
                    val firstVisibleItem = lazyListState.firstVisibleItemIndex
                    val firstVisibleItemScrollOffset = lazyListState.firstVisibleItemScrollOffset
                    if (firstVisibleItem > 0) 1f else (firstVisibleItemScrollOffset.toFloat() / 400f).coerceIn(0f, 1f)
                }
            }
            val headerAlphaAnimated by animateFloatAsState(
                targetValue = headerAlpha,
                label = "headerAlpha"
            )
            val backgroundColor = TempoBackground.copy(alpha = headerAlphaAnimated)

            // Custom Top Bar (Non-blocking when transparent)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .statusBarsPadding()
                    .height(64.dp) // Standard AppBar height
            ) {
                // Title
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = headerAlphaAnimated)
                    )
                }
                
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "My Music DNA" share — only when audio features are available
                    if (audioFeatures != null && audioFeatures.tracksWithFeatures > 0) {
                        IconButton(
                            onClick = { showVibeShare = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (headerAlphaAnimated > 0.5f) Color.Transparent else Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.share_content_description)
                            )
                        }
                    }

                    // Settings Button
                    IconButton(
                        onClick = onNavigateToSettings,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (headerAlphaAnimated > 0.5f) Color.Transparent else Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                }
            }
            
            // Time Period Filter (Floating)
            TimePeriodSelector(
                selectedRange = uiState.selectedTimeRange,
                onRangeSelected = viewModel::onTimeRangeSelected,
                availableRanges = listOf(TimeRange.THIS_WEEK, TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 100.dp)
                    .padding(horizontal = 32.dp)
            )
        }
        
        // Rate App Bottom Sheet
        if (uiState.showRateAppPopup) {
            RateAppBottomSheet(
                onDismiss = viewModel::onRateAppDismissed,
                onRate = {
                    if (isLaunchingReview) return@RateAppBottomSheet
                    val activity = ReviewUtils.run { context.findActivity() }
                    if (activity == null) {
                        ReviewUtils.openPlayStoreListing(context)
                        viewModel.onRateAppFlowHandled()
                        return@RateAppBottomSheet
                    }
                    // Set the guard BEFORE launching the coroutine so a rapid second tap
                    // is blocked even if the coroutine hasn't begun executing yet.
                    isLaunchingReview = true
                    scope.launch {
                        val launchedInAppReview = ReviewUtils.launchInAppReview(activity)
                        if (!launchedInAppReview) {
                            ReviewUtils.openPlayStoreListing(context)
                        }
                        viewModel.onRateAppFlowHandled()
                        isLaunchingReview = false
                    }
                },
                isSubmitting = isLaunchingReview
            )
        }
        
        // Spotlight Story Reminder Popup
        val reminderType = uiState.reminderType
        if (uiState.showSpotlightReminder && reminderType != null) {
            me.avinas.tempo.ui.components.SpotlightReminderPopup(
                type = reminderType,
                timeRange = uiState.reminderTimeRange,
                onDismiss = viewModel::dismissSpotlightReminder,
                onViewStory = {
                    // Navigate to Spotlight with the specified time range
                    onNavigateToSpotlight(uiState.reminderTimeRange, false)
                    // Dismiss the reminder
                    viewModel.dismissSpotlightReminder()
                }
            )
        }

        // Share Nudge Bottom Sheet — only when no other popup is up (never stack)
        val shareNudgeType = uiState.shareNudgeType
        if (uiState.showShareNudge && shareNudgeType != null &&
            !uiState.showRateAppPopup && !uiState.showSpotlightReminder
        ) {
            ShareNudgeBottomSheet(
                type = shareNudgeType,
                onDismiss = viewModel::onShareNudgeDismissed,
                onAction = {
                    viewModel.onShareNudgeAction()
                    when (shareNudgeType) {
                        ShareNudgeType.STATS -> onNavigateToStats()
                        ShareNudgeType.WEEKLY -> onNavigateToSpotlight(TimeRange.THIS_WEEK, true)
                        ShareNudgeType.MONTHLY -> onNavigateToSpotlight(TimeRange.THIS_MONTH, true)
                    }
                }
            )
        }

        // "My Music DNA" share dialog
        if (showVibeShare && audioFeatures != null) {
            SharePreviewDialog(
                onDismiss = { showVibeShare = false },
                themes = ShareTheme.entries,
                contentForTheme = {
                    VibeShareCard(
                        userName = uiState.userName,
                        periodLabel = when (uiState.selectedTimeRange) {
                            TimeRange.TODAY -> "TODAY"
                            TimeRange.THIS_WEEK -> "THIS WEEK"
                            TimeRange.THIS_MONTH -> "THIS MONTH"
                            TimeRange.THIS_YEAR -> "THIS YEAR"
                            TimeRange.ALL_TIME -> "ALL TIME"
                        },
                        audioFeatures = audioFeatures,
                        backgroundImageUrl = uiState.topArtist?.imageUrl ?: uiState.topTrack?.albumArtUrl,
                        theme = it
                    )
                }
            )
        }

        // Level Up Celebration Overlay
        if (uiState.isGamificationEnabled && showLevelUp) {
            LevelUpOverlay(
                newLevel = levelUpLevel,
                title = levelUpTitle,
                onDismiss = { showLevelUp = false }
            )
        }
    }
}

@Composable
private fun mapInsightToHabit(insight: InsightCardData): HabitInsightData {
    val (icon, color, gradient) = when(insight.type) {
        InsightType.MOOD -> Triple(Icons.Default.Face, TempoPrimary, listOf(TempoPrimary.copy(alpha=0.4f), TempoPrimaryDim.copy(alpha=0.1f)))
        InsightType.PEAK_TIME -> Triple(Icons.Default.DateRange, TempoWarning, listOf(TempoWarning.copy(alpha=0.4f), TempoWarningDeep.copy(alpha=0.1f)))
        InsightType.BINGE -> Triple(Icons.Filled.Bolt, TempoPrimary, listOf(TempoPrimary.copy(alpha=0.4f), TempoPrimaryDim.copy(alpha=0.1f)))
        InsightType.DISCOVERY -> Triple(Icons.Default.Celebration, TempoSuccessDeep, listOf(TempoSuccessDeep.copy(alpha=0.4f), TempoSuccess.copy(alpha=0.1f)))
        InsightType.ENERGY -> Triple(Icons.Default.Bolt, TempoError, listOf(TempoError.copy(alpha=0.4f), TempoErrorDeep.copy(alpha=0.1f)))
        InsightType.DANCEABILITY -> Triple(Icons.Default.Celebration, TempoAccent, listOf(TempoAccent.copy(alpha=0.4f), TempoPrimaryMuted.copy(alpha=0.1f)))
        InsightType.TEMPO -> Triple(Icons.Default.Speed, TempoCyan, listOf(TempoCyan.copy(alpha=0.4f), TempoCyan.copy(alpha=0.1f)))
        InsightType.ACOUSTICNESS -> Triple(Icons.Default.Piano, TempoSuccess, listOf(TempoSuccess.copy(alpha=0.4f), TempoSuccess.copy(alpha=0.1f)))
        else -> Triple(Icons.Default.DateRange, TextTertiary, listOf(TextTertiary.copy(alpha=0.2f), TempoSurfaceSunken.copy(alpha=0.1f)))
    }
    
    return HabitInsightData(
        title = insight.title,
        subtitle = insight.description,
        icon = icon,
        iconColor = color,
        iconBgColor = color.copy(alpha = 0.2f),
        gradient = gradient
    )
}
