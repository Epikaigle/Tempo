package me.avinas.tempo.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.CaptionSmall
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.GlassBorderMedium
import me.avinas.tempo.ui.theme.GlassBorderStrong
import me.avinas.tempo.ui.theme.InsightBinge
import me.avinas.tempo.ui.theme.InsightDanceability
import me.avinas.tempo.ui.theme.KickerSmall
import me.avinas.tempo.ui.theme.LevelRingSweepEnd
import me.avinas.tempo.ui.theme.LevelRingSweepMid
import me.avinas.tempo.ui.theme.LevelRingSweepStart
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoDarkSurfaceElevated
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoErrorSoft
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import me.avinas.tempo.ui.theme.TempoWarningBright

// Widest the scrolling content ever gets — expanded widths (tablets, landscape,
// foldables) keep a readable measure instead of stretching cards edge to edge.
private val ProfileMaxContentWidth = 660.dp

// ── Category & difficulty identity — data colormaps, not chrome tokens ──
private fun getCategoryColor(category: String): Color = when (category) {
    "MILESTONE" -> TempoWarning
    "TIME" -> TempoInfo
    "STREAK" -> TempoError
    "DISCOVERY" -> TempoSuccessDeep
    "ENGAGEMENT" -> InsightDanceability
    "LEVEL" -> InsightBinge
    else -> Color.Gray
}

private fun getCategoryLabel(category: String): String = when (category) {
    "MILESTONE" -> "Milestones"
    "TIME" -> "Time"
    "STREAK" -> "Streaks"
    "DISCOVERY" -> "Discovery"
    "ENGAGEMENT" -> "Engagement"
    "LEVEL" -> "Levels"
    else -> category
}

private fun getChallengeCategoryIcon(category: String): ImageVector = when (category) {
    "VOLUME" -> Icons.Default.MusicNote
    "TIME" -> Icons.Default.Schedule
    "VARIETY" -> Icons.Default.Palette
    "DISCOVERY" -> Icons.Default.AutoAwesome
    "EXPLORATION" -> Icons.Default.Explore
    else -> Icons.Default.Star
}

private fun getDifficultyColor(difficulty: String): Color = when (difficulty) {
    "EASY" -> TempoSuccessDeep
    "MEDIUM" -> TempoWarning
    "HARD" -> TempoError
    else -> Color.Gray
}

// Primitives — hairline tracks, quiet headers, glass pills
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = CaptionSmall,
                color = TextTertiary
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = TempoPrimary,
    brush: Brush? = null,
    trackColor: Color = Color.White.copy(alpha = 0.08f),
    height: Dp = 6.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
        )
    }
}

@Composable
private fun TopBarActionButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(TempoDarkSurface)
            .border(1.dp, GlassBorderMedium, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Compact identity shown in the top bar once the hero card scrolls out of view. */
@Composable
private fun CollapsingProfileTitle(visible: Boolean, userName: String, level: Int, title: String) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = userName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Level $level · $title",
                style = MaterialTheme.typography.labelSmall,
                color = TempoAccent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun XpChip(xp: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TempoWarning.copy(alpha = 0.10f))
            .border(1.dp, TempoWarning.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(14.dp))
        Text(text = "+$xp XP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TempoWarning)
    }
}

@Composable
private fun StarsChip(total: Int, max: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TempoWarning.copy(alpha = 0.10f))
            .border(1.dp, TempoWarning.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(14.dp))
        Text(text = "$total / $max", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TempoWarning)
    }
}

// Main screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val heroScrolledPast by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    DeepOceanBackground {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Width buckets drive every measurement below: compact (<380dp) trims
            // chrome, expanded (600dp+) caps the content measure and centers it.
            val compact = maxWidth < 380.dp
            val expanded = maxWidth >= 600.dp
            val sidePadding = if (compact) 16.dp else if (expanded) 24.dp else 20.dp
            // Shared by every scrolling item so horizontal padding and the
            // expanded-width cap can never drift apart between sections.
            val contentModifier = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.widthIn(max = ProfileMaxContentWidth) else Modifier)
                .padding(horizontal = sidePadding)
            val badgeColumns = when {
                maxWidth >= 840.dp -> 4
                expanded -> 3
                else -> 2
            }
            val tabs = if (compact) listOf("Quests", "Badges") else listOf("Challenges", "Badges")
            // Clears the overlay top bar: status bar + 44dp buttons + 12dp vertical margins.
            val heroTopClearance = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp

            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { scope.launch { viewModel.refresh() } },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 132.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item(key = "hero") {
                            Column(modifier = contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(heroTopClearance))
                                HeroProfileSection(
                                    userLevel = uiState.userLevel,
                                    userTitle = uiState.userTitle,
                                    userName = uiState.userName,
                                    profileImagePath = uiState.profileImagePath,
                                    compact = compact
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }

                        item(key = "stats_tabs") {
                            Column(
                                modifier = contentModifier,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                StatsSection(
                                    userLevel = uiState.userLevel,
                                    compact = compact,
                                    streakAtRisk = uiState.streakAtRisk,
                                    timeRemaining = uiState.streakTimeRemaining,
                                    streakDurationMinutes = uiState.streakDurationMinutes
                                )
                                TabSwitcher(
                                    tabs = tabs,
                                    selectedTab = selectedTab,
                                    onTabSelected = { selectedTab = it }
                                )
                            }
                        }

                        if (selectedTab == 0) {
                            if (uiState.challenges.isNotEmpty()) {
                                challengesSection(
                                    challenges = uiState.challenges,
                                    totalXpAvailable = uiState.challengeXpTotal,
                                    claimedChallengeIds = uiState.claimedChallengeIds,
                                    onClaimChallenge = viewModel::claimChallenge,
                                    contentModifier = contentModifier
                                )
                            } else if (!uiState.isLoading) {
                                item(key = "empty_challenges") {
                                    Column(modifier = contentModifier) {
                                        Spacer(modifier = Modifier.height(24.dp))
                                        EmptyChallengesState()
                                    }
                                }
                            }
                        } else {
                            badgeSection(
                                allBadges = uiState.allBadges,
                                filteredBadges = uiState.filteredBadges,
                                earnedCount = uiState.earnedCount,
                                totalCount = uiState.totalCount,
                                totalStars = uiState.totalStars,
                                maxPossibleStars = uiState.maxPossibleStars,
                                categories = uiState.categories,
                                selectedCategory = uiState.selectedCategory,
                                onCategorySelected = viewModel::onCategorySelected,
                                contentModifier = contentModifier,
                                badgeColumns = badgeColumns
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(if (expanded) Modifier.widthIn(max = ProfileMaxContentWidth) else Modifier)
                        .statusBarsPadding()
                        .padding(horizontal = sidePadding, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarActionButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CollapsingProfileTitle(
                            visible = heroScrolledPast,
                            userName = uiState.userName,
                            level = uiState.userLevel.currentLevel,
                            title = uiState.userTitle
                        )
                    }
                    TopBarActionButton(icon = Icons.Default.Settings, contentDescription = "Settings", onClick = onNavigateToSettings)
                }
            }

            if (uiState.showLevelUpCelebration) {
                LevelUpCelebration(level = uiState.userLevel.currentLevel, onDismiss = viewModel::dismissLevelUpCelebration)
            }

            if (uiState.unacknowledgedBadges.isNotEmpty() && !uiState.showLevelUpCelebration) {
                NewBadgeCelebrationOverlay(
                    badges = uiState.unacknowledgedBadges,
                    onDismiss = { viewModel.acknowledgeBadges(uiState.unacknowledgedBadges.map { it.badgeId }) }
                )
            }
        }
    }
}

// Hero — avatar + identity + level progress on one quiet glass surface
@Composable
private fun HeroProfileSection(
    userLevel: UserLevel,
    userTitle: String,
    userName: String,
    profileImagePath: String?,
    compact: Boolean = false
) {
    val animatedProgress by animateFloatAsState(
        targetValue = userLevel.levelProgress,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "levelProgress"
    )
    val progressPercent = (animatedProgress * 100).roundToInt()

    GlassCard(
        variant = GlassCardVariant.QuietGlass,
        accentColor = TempoPrimary,
        contentPadding = PaddingValues(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroAvatar(
                    progress = animatedProgress,
                    level = userLevel.currentLevel,
                    userName = userName,
                    profileImagePath = profileImagePath,
                    compact = compact
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = userTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TempoAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = Divider)

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Level ${userLevel.currentLevel}",
                        style = KickerSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "$progressPercent%",
                        style = KickerSmall,
                        color = TempoAccent
                    )
                }

                ProgressTrack(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth(),
                    brush = Brush.horizontalGradient(
                        listOf(LevelRingSweepStart, LevelRingSweepMid, LevelRingSweepEnd)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${userLevel.totalXp} XP total",
                        style = CaptionSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "${userLevel.xpRemaining} XP to next level",
                        style = CaptionSmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroAvatar(
    progress: Float,
    level: Int,
    userName: String,
    profileImagePath: String?,
    compact: Boolean = false
) {
    val ringSize = if (compact) 96.dp else 108.dp
    val innerSize = if (compact) 76.dp else 86.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val strokeWidth = 6.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            // Story-ring: quiet white track, level-sweep arc, dot at the arc end.
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(LevelRingSweepStart, LevelRingSweepMid, LevelRingSweepEnd),
                        center
                    ),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            // End dot — marks the live edge of progress.
            val angle = Math.toRadians((-90f + sweep).toDouble())
            val dotCenter = Offset(
                center.x + radius * cos(angle).toFloat(),
                center.y + radius * sin(angle).toFloat()
            )
            drawCircle(color = LevelRingSweepEnd, radius = 6.dp.toPx(), center = dotCenter)
            drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = dotCenter)
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(TempoDarkSurfaceElevated)
                .border(1.dp, GlassBorderStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (profileImagePath.isNullOrBlank()) {
                Text(
                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            } else {
                CachedAsyncImage(
                    imageUrl = profileImagePath,
                    contentDescription = "Avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-4).dp)
                .background(TempoPrimary, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(text = "LVL $level", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = TextOnAccent)
        }
    }
}

// Stats — one obsidian block: streak + inline best/XP. Clean risk banner.
@Composable
private fun StatsSection(
    userLevel: UserLevel,
    compact: Boolean,
    streakAtRisk: Boolean = false,
    timeRemaining: String = "",
    streakDurationMinutes: Long = Long.MAX_VALUE
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (streakAtRisk) {
            StreakRiskBanner(streakDurationMinutes = streakDurationMinutes, timeRemaining = timeRemaining)
        }

        SectionHeader(
            title = "Listening rhythm",
            subtitle = if (streakAtRisk) "Your streak needs attention today."
                       else "You're building a solid habit. Here's the run so far."
        )

        GlassCard(variant = GlassCardVariant.Obsidian, contentPadding = PaddingValues(20.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = "Current streak", style = MaterialTheme.typography.labelMedium, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${userLevel.currentStreak}",
                            style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = if (userLevel.currentStreak == 1) "day in motion" else "days in motion",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextSecondary
                        )
                    }
                    ListeningStatusChip(streakAtRisk = streakAtRisk, timeRemaining = timeRemaining)
                }

                HorizontalDivider(color = Divider)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.EmojiEvents,
                        value = "${userLevel.longestStreak}",
                        label = "Best streak",
                        accent = TempoAccent
                    )
                    Box(modifier = Modifier.width(1.dp).height(30.dp).background(Divider))
                    InlineStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.AutoAwesome,
                        value = "${userLevel.totalXp}",
                        label = "Total XP",
                        accent = TempoWarning
                    )
                }
            }
        }
    }
}

@Composable
private fun StreakRiskBanner(streakDurationMinutes: Long, timeRemaining: String) {
    val riskColor = when {
        streakDurationMinutes > 360 -> TempoErrorSoft
        streakDurationMinutes > 180 -> TempoError
        streakDurationMinutes > 60 -> TempoErrorDeep
        else -> TempoErrorDeep
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(riskColor.copy(alpha = 0.10f))
            .border(1.dp, riskColor.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = Icons.Default.LocalFireDepartment, contentDescription = null, tint = riskColor, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Streak at risk", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(text = "Play something in the next $timeRemaining to keep it alive.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun ListeningStatusChip(streakAtRisk: Boolean, timeRemaining: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TempoDarkSurfaceElevated)
            .border(1.dp, GlassBorderMedium, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = if (streakAtRisk) TempoErrorSoft else TempoAccent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (streakAtRisk) "Ends in $timeRemaining" else "Safe today",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}

@Composable
private fun InlineStat(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextTertiary, fontWeight = FontWeight.Medium)
        }
    }
}

// Tab switcher — segmented control, teal indicator (no gradient)
@Composable
private fun TabSwitcher(
    tabs: List<String>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TempoDarkSurface)
            .border(1.dp, GlassBorderMedium, RoundedCornerShape(16.dp))
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / tabs.size
        val indicatorPosition by animateFloatAsState(
            targetValue = selectedTab.toFloat(),
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "tabIndicator"
        )
        // Indicator layer: matchParentSize() sizes this box to the height the
        // tab Row gives the parent without taking part in its measurement, so
        // the sliding highlight can use fillMaxHeight() without an intrinsic
        // measurement (intrinsic queries on BoxWithConstraints/SubcomposeLayout
        // crash with IllegalStateException).
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .offset(x = tabWidth * indicatorPosition)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TempoPrimary.copy(alpha = 0.16f))
                    .border(1.dp, TempoPrimary.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTab == index
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) TextPrimary else TextTertiary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                )
            }
        }
    }
}

// Challenges — quiet glass cards, one accent per difficulty
private fun LazyListScope.challengesSection(
    challenges: List<DailyChallenge>,
    totalXpAvailable: Int,
    claimedChallengeIds: Set<Long>,
    onClaimChallenge: (Long) -> Unit,
    contentModifier: Modifier
) {
    val completedCount = challenges.count { it.isCompleted }
    item(key = "challenges_header") {
        // Recomputed per composition so the countdown stays accurate while the screen is open.
        val resetLabel = run {
            val midnight = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val diffMs = midnight - System.currentTimeMillis()
            val h = (diffMs / (1000 * 60 * 60)).toInt()
            val m = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()
            if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
        }
        Column(modifier = contentModifier) {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(
                title = "Daily challenges",
                subtitle = "$completedCount of ${challenges.size} complete · $resetLabel",
                trailing = { XpChip(xp = totalXpAvailable) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProgressTrack(
                progress = if (challenges.isEmpty()) 0f else completedCount.toFloat() / challenges.size,
                modifier = Modifier.fillMaxWidth(),
                color = TempoPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    itemsIndexed(challenges, key = { _, c -> "challenge_${c.id}" }) { _, challenge ->
        Column(modifier = contentModifier) {
            ChallengeCard(
                challenge = challenge,
                isClaimed = challenge.id in claimedChallengeIds,
                onClaim = { onClaimChallenge(challenge.id) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyChallengesState(modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier,
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(imageVector = Icons.Default.Schedule, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(34.dp))
            Text(text = "Nothing queued yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextSecondary)
            Text(
                text = "Pull to refresh or keep listening — new challenges will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChallengeCard(
    challenge: DailyChallenge,
    isClaimed: Boolean,
    onClaim: () -> Unit
) {
    val isCompleted = challenge.isCompleted
    val accent = if (isCompleted) TempoSuccessDeep else getDifficultyColor(challenge.difficulty)
    val animatedProgress by animateFloatAsState(
        targetValue = challenge.progressFraction,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "challengeProgress"
    )
    val haptic = LocalHapticFeedback.current

    GlassCard(
        variant = GlassCardVariant.QuietGlass,
        accentColor = accent,
        borderColor = if (isCompleted) TempoSuccessDeep.copy(alpha = 0.35f) else null,
        contentPadding = PaddingValues(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = if (isCompleted) 0.18f else 0.12f))
                        .border(1.dp, accent.copy(alpha = if (isCompleted) 0.45f else 0.28f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getChallengeCategoryIcon(challenge.category),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accent))
                        Text(
                            text = challenge.difficulty,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "·  ${challenge.category.replace("_", " ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = challenge.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = challenge.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        lineHeight = 20.sp
                    )
                }
                // Fixed floor keeps the reward column a stable width so the title
                // line doesn't reflow between a 2-digit and 3-digit XP reward.
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 48.dp)) {
                    Text(text = "+${challenge.xpReward}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
                    Text(text = "XP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent.copy(alpha = 0.7f), letterSpacing = 1.sp)
                }
            }

            ProgressTrack(progress = animatedProgress, modifier = Modifier.fillMaxWidth(), color = accent)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${challenge.currentProgress} / ${challenge.targetValue}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCompleted) TempoSuccessDeep else TextSecondary
                )
                when {
                    isCompleted && !isClaimed -> {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onClaim()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TempoPrimary,
                                contentColor = TextOnAccent
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Claim +${challenge.xpReward} XP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    isCompleted -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = TempoSuccessDeep, modifier = Modifier.size(16.dp))
                            Text(text = "Claimed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = TempoSuccessDeep)
                        }
                    }
                    else -> {
                        Text(
                            text = "In progress",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            }
        }
    }
}

// Badges — glass grid. Emblem keeps the medal character; card stays quiet.
private fun LazyListScope.badgeSection(
    allBadges: List<Badge>,
    filteredBadges: List<Badge>,
    earnedCount: Int,
    totalCount: Int,
    totalStars: Int,
    maxPossibleStars: Int,
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit,
    contentModifier: Modifier,
    badgeColumns: Int = 2
) {
    val collectionProgress = if (totalCount == 0) 0f else earnedCount.toFloat() / totalCount
    item(key = "badges_header") {
        val beginnerIds = GamificationEngine.BEGINNER_BADGES
        val almostThereBadge = remember(allBadges) {
            allBadges.filter { !it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f }
                .maxByOrNull { it.progressFraction }
        }
        val nextStarBadge = remember(allBadges) {
            if (almostThereBadge != null) null else {
                allBadges.filter { it.isEarned && !it.isMaxed && it.badgeId !in beginnerIds && it.progressFraction >= 0.5f }
                    .maxByOrNull { it.progressFraction }
            }
        }
        val spotlightBadge = almostThereBadge ?: nextStarBadge

        Column(modifier = contentModifier) {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(
                title = "Your collection",
                subtitle = "$earnedCount of $totalCount badges earned.",
                trailing = if (totalStars > 0) ({ StarsChip(total = totalStars, max = maxPossibleStars) }) else null
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProgressTrack(progress = collectionProgress, modifier = Modifier.fillMaxWidth(), color = TempoWarning)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { onCategorySelected(null) },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TempoPrimary.copy(alpha = 0.14f),
                        containerColor = Color.Transparent,
                        labelColor = TextSecondary,
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = GlassBorderMedium,
                        selectedBorderColor = TempoPrimary.copy(alpha = 0.40f),
                        enabled = true,
                        selected = selectedCategory == null
                    )
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        label = { Text(getCategoryLabel(category)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TempoPrimary.copy(alpha = 0.14f),
                            containerColor = Color.Transparent,
                            labelColor = TextSecondary,
                            selectedLabelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = GlassBorderMedium,
                            selectedBorderColor = TempoPrimary.copy(alpha = 0.40f),
                            enabled = true,
                            selected = selectedCategory == category
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (spotlightBadge != null) {
                val isAlmostThere = almostThereBadge != null
                val accent = if (isAlmostThere) TempoPrimary else TempoWarning
                val remaining = (spotlightBadge.maxProgress - spotlightBadge.progress).coerceAtLeast(0)
                val remainingLabel = "$remaining"
                val progressLabel = "${spotlightBadge.progress} / ${spotlightBadge.maxProgress}"
                val animatedProgress by animateFloatAsState(
                    targetValue = spotlightBadge.progressFraction,
                    animationSpec = tween(1200, easing = FastOutSlowInEasing),
                    label = "spotlightProgress"
                )
                GlassCard(
                    variant = GlassCardVariant.QuietGlass,
                    accentColor = accent,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BadgeEmblem(
                                badge = spotlightBadge,
                                intrinsicColor = getUniqueBadgeColor(spotlightBadge.badgeId),
                                modifier = Modifier.size(52.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = if (isAlmostThere) "Almost there" else "Next star up",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = spotlightBadge.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isAlmostThere) "$remainingLabel to go — keep it up."
                                           else "Earned — push for the next tier.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary
                                )
                            }
                            Text(
                                text = progressLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                        }
                        ProgressTrack(progress = animatedProgress, modifier = Modifier.fillMaxWidth(), color = accent)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    itemsIndexed(
        filteredBadges.chunked(badgeColumns),
        key = { index, _ -> "badge_row_$index" }
    ) { _, rowBadges ->
        Column(modifier = contentModifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowBadges.forEach { badge ->
                    BadgeCard(badge = badge, modifier = Modifier.weight(1f))
                }
                repeat(badgeColumns - rowBadges.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge, modifier: Modifier = Modifier) {
    val isEarned = badge.isEarned
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val isBeginner = badge.badgeId in GamificationEngine.BEGINNER_BADGES
    val animatedProgress by animateFloatAsState(
        targetValue = badge.progressFraction,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "badgeProgress"
    )
    val earnedDate = remember(badge.earnedAt) {
        if (badge.isEarned && badge.earnedAt > 0L) {
            java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(badge.earnedAt))
        } else null
    }

    GlassCard(
        modifier = modifier,
        variant = if (isEarned) GlassCardVariant.TintedSolid else GlassCardVariant.Obsidian,
        accentColor = if (isEarned) intrinsicColor else null,
        borderColor = if (isEarned) intrinsicColor.copy(alpha = 0.30f) else null,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BadgeEmblem(badge = badge, intrinsicColor = intrinsicColor, modifier = Modifier.size(64.dp))
            Text(
                text = badge.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isEarned) TextPrimary else TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 36.dp)
            )
            Text(
                text = rarity.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isEarned) rarityColor else rarityColor.copy(alpha = 0.55f),
                letterSpacing = 1.sp
            )
            if (isEarned) {
                if (isBeginner) {
                    Text(
                        text = "UNLOCKED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = intrinsicColor,
                        letterSpacing = 1.sp
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 1..5) {
                            val starColor = if (i <= badge.stars) {
                                if (badge.isMaxed) intrinsicColor else TempoWarningBright
                            } else Color.White.copy(alpha = 0.12f)
                            Icon(
                                imageVector = if (i <= badge.stars) Icons.Default.Star else Icons.Default.StarOutline,
                                contentDescription = null,
                                tint = starColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (badge.isMaxed) {
                        Text(
                            text = "MAXED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TempoWarningBright,
                            letterSpacing = 1.sp
                        )
                    } else {
                        ProgressTrack(
                            progress = animatedProgress,
                            modifier = Modifier.fillMaxWidth(),
                            color = intrinsicColor,
                            height = 4.dp
                        )
                        Text(
                            text = "${badge.progress} / ${badge.maxProgress}  →  ★${badge.stars + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp
                        )
                    }
                }
                if (earnedDate != null) {
                    Text(
                        text = "EARNED · $earnedDate",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        letterSpacing = 1.5.sp,
                        color = intrinsicColor.copy(alpha = 0.85f)
                    )
                }
            } else {
                ProgressTrack(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth(),
                    color = intrinsicColor.copy(alpha = 0.55f),
                    height = 4.dp
                )
                Text(
                    text = "${badge.progress} / ${badge.maxProgress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
        }
    }
}

// Badge emblem — a struck collector's medallion. Rarity decides the metal
// of the rim, the badge's intrinsic color becomes the enamel face. Locked
// badges are unstruck blanks: dark graphite with a faint imprint. The
// specular highlight is frozen — no infinite sweep, no per-badge timers.
@Composable
private fun BadgeEmblem(badge: Badge, intrinsicColor: Color, modifier: Modifier = Modifier) {
    val isEarned = badge.isEarned
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val metal = getRarityMetal(rarity)
    val glowAlpha = if (isEarned) getRarityGlowAlpha(rarity) else 0f
    val art = remember(badge.badgeId) { BadgeArt.artFor(badge.badgeId) }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val faceRadius = radius * 0.79f

            // Rarity aura — soft light bleeding off EPIC+ coins.
            if (glowAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(rarityColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = radius * 1.05f
                    ),
                    radius = radius * 1.05f,
                    center = center
                )
            }

            // Rim — the coin is struck from its rarity metal.
            val rimBrush = if (rarity == GamificationEngine.BadgeRarity.MYTHIC && isEarned) {
                Brush.sweepGradient(metal, center)
            } else {
                Brush.linearGradient(
                    colors = metal,
                    start = Offset(size.width * 0.15f, 0f),
                    end = Offset(size.width * 0.85f, size.height)
                )
            }
            drawCircle(brush = rimBrush, radius = radius, center = center)

            // Enamel face — the badge's own color, lit from the upper left.
            val faceBrush = if (isEarned) {
                Brush.radialGradient(
                    colors = listOf(
                        lerp(intrinsicColor, Color.White, 0.38f),
                        intrinsicColor,
                        lerp(intrinsicColor, Color.Black, 0.42f)
                    ),
                    center = Offset(center.x - radius * 0.3f, center.y - radius * 0.38f),
                    radius = radius * 1.35f
                )
            } else {
                Brush.radialGradient(
                    colors = listOf(Color(0xFF2B2B31), Color(0xFF131317)),
                    center = Offset(center.x - radius * 0.25f, center.y - radius * 0.3f),
                    radius = radius * 1.2f
                )
            }
            drawCircle(brush = faceBrush, radius = faceRadius, center = center)

            // Engraved hairline ring just inside the rim.
            drawCircle(
                color = Color.White.copy(alpha = if (isEarned) 0.25f else 0.08f),
                radius = faceRadius * 0.9f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Static specular — a frozen foil highlight, one diagonal stripe.
            if (isEarned) {
                val coinPath = Path().apply { addOval(Rect(center, faceRadius)) }
                clipPath(coinPath) {
                    rotate(degrees = -24f, pivot = center) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            ),
                            topLeft = Offset(center.x - faceRadius * 0.9f, center.y - radius * 1.6f),
                            size = Size(faceRadius * 0.7f, radius * 3.2f)
                        )
                    }
                }
            }
        }

        Icon(
            imageVector = art,
            contentDescription = badge.name,
            tint = if (isEarned) Color.White.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.22f),
            modifier = Modifier.size(maxWidth * 0.40f)
        )
    }
}

// ── Badge identity — intrinsic enamel colors are a data colormap, one per
// badge id; rarity decides the metal the medallion is struck from. ──
private fun getUniqueBadgeColor(badgeId: String): Color = when (badgeId) {
    "first_play" -> Color(0xFF10B981)
    "plays_100" -> Color(0xFF3B82F6)
    "plays_500" -> Color(0xFF8B5CF6)
    "plays_1000" -> Color(0xFFEC4899)
    "plays_5000" -> Color(0xFFF43F5E)
    "plays_10000" -> Color(0xFFEAB308)
    "time_1h" -> Color(0xFF06B6D4)
    "time_24h" -> Color(0xFF0EA5E9)
    "time_100h" -> Color(0xFF6366F1)
    "time_500h" -> Color(0xFFD946EF)
    "streak_7" -> Color(0xFFF97316)
    "streak_30" -> Color(0xFFEF4444)
    "streak_100" -> Color(0xFFDC2626)
    "streak_365" -> Color(0xFF991B1B)
    "artists_10" -> Color(0xFF14B8A6)
    "artists_50" -> Color(0xFF22C55E)
    "artists_100" -> Color(0xFF84CC16)
    "genres_10" -> Color(0xFFF59E0B)
    "genres_25" -> Color(0xFFD97706)
    "night_owl" -> Color(0xFF312E81)
    "early_bird" -> Color(0xFFFBBF24)
    "marathon" -> Color(0xFF4F46E5)
    "level_5" -> Color(0xFF6EE7B7)
    "level_10" -> Color(0xFF34D399)
    "level_25" -> Color(0xFF10B981)
    "level_50" -> Color(0xFF059669)
    "level_75" -> Color(0xFF047857)
    "level_100" -> Color(0xFF064E3B)
    else -> Color(0xFFA855F7)
}

private fun getRarityColor(rarity: GamificationEngine.BadgeRarity): Color = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> Color(0xFF9CA3AF)
    GamificationEngine.BadgeRarity.RARE -> Color(0xFF3B82F6)
    GamificationEngine.BadgeRarity.EPIC -> Color(0xFFA855F7)
    GamificationEngine.BadgeRarity.LEGENDARY -> Color(0xFFF59E0B)
    GamificationEngine.BadgeRarity.MYTHIC -> Color(0xFFEC4899)
}

/** The metal gradient of the coin rim, light struck from the upper left. */
private fun getRarityMetal(rarity: GamificationEngine.BadgeRarity): List<Color> = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON ->
        listOf(Color(0xFFDCDFE4), Color(0xFF9CA3AB), Color(0xFF5F646B), Color(0xFFB9BDC3))
    GamificationEngine.BadgeRarity.RARE ->
        listOf(Color(0xFFF4F8FF), Color(0xFFC3D5EE), Color(0xFF8199BE), Color(0xFFE1EBF8))
    GamificationEngine.BadgeRarity.EPIC ->
        listOf(Color(0xFFFFF6D9), Color(0xFFF4CF6D), Color(0xFFBA8C20), Color(0xFFF1DE9E))
    GamificationEngine.BadgeRarity.LEGENDARY ->
        listOf(Color(0xFFFFEFEE), Color(0xFFF8C3CC), Color(0xFFC57486), Color(0xFFFFDCE1))
    GamificationEngine.BadgeRarity.MYTHIC ->
        listOf(Color(0xFFE4D4FF), Color(0xFFAEE9F7), Color(0xFFFBD3E9), Color(0xFFD8F5E3), Color(0xFFFDE9C8))
}

private fun getRarityGlowAlpha(rarity: GamificationEngine.BadgeRarity): Float = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> 0f
    GamificationEngine.BadgeRarity.RARE -> 0f
    GamificationEngine.BadgeRarity.EPIC -> 0.20f
    GamificationEngine.BadgeRarity.LEGENDARY -> 0.30f
    GamificationEngine.BadgeRarity.MYTHIC -> 0.42f
}

// Celebrations — event-driven, short-lived. Solid scrim, no glass overlay.
@Composable
fun LevelUpCelebration(level: Int, onDismiss: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "levelUpScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        ConfettiEffect()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .scale(scale)
                .padding(32.dp)
        ) {
            Text(
                text = "LEVEL UP!",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = TempoWarning
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(TempoWarning.copy(alpha = 0.3f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$level",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
            }
            Text(
                text = "You've reached level $level. Keep the music playing.",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tap anywhere to continue",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@Composable
fun NewBadgeCelebrationOverlay(badges: List<Badge>, onDismiss: () -> Unit) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val badge = badges.getOrNull(currentIndex)

    if (badge == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "badgeCelebScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable {
                if (currentIndex < badges.size - 1) currentIndex++ else onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        ConfettiEffect()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .scale(scale)
                .padding(32.dp)
        ) {
            Text(
                text = "BADGE UNLOCKED",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = TempoWarning,
                letterSpacing = 2.sp
            )
            BadgeEmblem(
                badge = badge,
                intrinsicColor = getUniqueBadgeColor(badge.badgeId),
                modifier = Modifier.size(100.dp)
            )
            Text(
                text = badge.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = badge.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(18.dp))
                Text(
                    text = "${GamificationEngine.getRarity(badge.badgeId).label} · ★ ${badge.stars} of 5",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TempoWarning
                )
            }
            if (badges.size > 1) {
                Text(
                    text = "${currentIndex + 1} of ${badges.size} — tap to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            } else {
                Text(
                    text = "Tap anywhere to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
fun ConfettiEffect() {
    val particles = remember {
        List(40) { i ->
            Triple(
                (i * 37 % 100) / 100f,
                (i * 53 % 100) / 100f,
                listOf(TempoWarning, TempoPrimary, TempoError, TempoInfo, TempoSuccessDeep)[i % 5]
            )
        }
    }
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 2500, easing = LinearEasing),
        label = "confetti"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { (x, delay, color) ->
            val p = ((progress + delay) % 1f)
            val yPos = p * this.size.height
            val xPos = x * this.size.width + sin(p * 12f) * 30f
            val alpha = if (p > 0.8f) (1f - p) / 0.2f else 1f
            drawRect(
                color = color.copy(alpha = alpha * 0.8f),
                topLeft = Offset(xPos, yPos),
                size = Size(8f, 12f)
            )
        }
    }
}

// Level ring — shared with HomeScreen's compact header ring
@Composable
fun CompactLevelRing(
    progress: Float,
    level: Int,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            val radius = (this.size.minDimension - strokeWidth) / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(LevelRingSweepStart, LevelRingSweepMid, LevelRingSweepEnd),
                        center
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = "$level",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
    }
}
