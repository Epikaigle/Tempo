package me.avinas.tempo.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
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
import kotlin.math.roundToInt

// =====================================================================
// Design tokens — one disciplined palette, clear hierarchy, no neon.
// =====================================================================
private val ProfileSurface = Color(0xFF131316)          // card surface on OLED black
private val ProfileSurfaceRaised = Color(0xFF1B1B20)    // slightly raised element
private val ProfileBorder = Color.White.copy(alpha = 0.08f)
private val ProfileBorderStrong = Color.White.copy(alpha = 0.14f)
private val ProfileAccent = Color(0xFF8B5CF6)           // primary violet (fills)
private val ProfileAccentLight = Color(0xFFA78BFA)      // accent text
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.66f)
private val TextTertiary = Color.White.copy(alpha = 0.45f)
private val Amber = Color(0xFFF59E0B)
private val Emerald = Color(0xFF10B981)
private val Danger = Color(0xFFEF4444)

private fun darkTint(color: Color, factor: Float = 0.22f): Color = Color(
    red = (color.red * factor + 0.04f).coerceIn(0f, 1f),
    green = (color.green * factor + 0.04f).coerceIn(0f, 1f),
    blue = (color.blue * factor + 0.04f).coerceIn(0f, 1f)
)

private fun getCategoryColor(category: String): Color = when (category) {
    "MILESTONE" -> Color(0xFFF59E0B)
    "TIME" -> Color(0xFF3B82F6)
    "STREAK" -> Color(0xFFEF4444)
    "DISCOVERY" -> Color(0xFF10B981)
    "ENGAGEMENT" -> Color(0xFFA855F7)
    "LEVEL" -> Color(0xFFEC4899)
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

// =====================================================================
// Primitives — flat surfaces, solid progress, clean headers
// =====================================================================
@Composable
private fun ProfileSurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ProfileSurface)
            .border(1.dp, ProfileBorder, RoundedCornerShape(24.dp))
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    eyebrow: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = ProfileAccentLight,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = ProfileAccent,
    brush: Brush? = null,
    trackColor: Color = Color.White.copy(alpha = 0.10f),
    height: Dp = 8.dp
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
                .let { if (brush != null) it.background(brush) else it.background(color) }
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
            .background(ProfileSurface)
            .border(1.dp, ProfileBorder, CircleShape)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(20.dp))
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
                color = ProfileAccentLight,
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
            .background(ProfileAccent.copy(alpha = 0.12f))
            .border(1.dp, ProfileAccent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = ProfileAccentLight, modifier = Modifier.size(14.dp))
        Text(text = "+$xp XP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ProfileAccentLight)
    }
}

@Composable
private fun StarsChip(total: Int, max: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Amber.copy(alpha = 0.12f))
            .border(1.dp, Amber.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Amber, modifier = Modifier.size(14.dp))
        Text(text = "$total / $max", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Amber)
    }
}

// =====================================================================
// Main screen
// =====================================================================
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
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { scope.launch { viewModel.refresh() } },
                modifier = Modifier.fillMaxSize()
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val compact = maxWidth < 380.dp
                    val tabs = if (compact) listOf("Quests", "Badges") else listOf("Challenges", "Badges")
                    val sidePadding = if (compact) 16.dp else 20.dp

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 132.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item(key = "hero") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(92.dp))
                                HeroProfileSection(
                                    userLevel = uiState.userLevel,
                                    userTitle = uiState.userTitle,
                                    userName = uiState.userName,
                                    profileImagePath = uiState.profileImagePath
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }

                        item(key = "stats_tabs") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = sidePadding),
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
                                    sidePadding = sidePadding
                                )
                            } else if (!uiState.isLoading) {
                                item(key = "empty_challenges") {
                                    Column(modifier = Modifier.padding(horizontal = sidePadding)) {
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
                                sidePadding = sidePadding
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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

// =====================================================================
// Hero — avatar + identity + level progress (one calm surface)
// =====================================================================
@Composable
private fun HeroProfileSection(
    userLevel: UserLevel,
    userTitle: String,
    userName: String,
    profileImagePath: String?
) {
    val animatedProgress by animateFloatAsState(
        targetValue = userLevel.levelProgress,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "levelProgress"
    )
    val progressPercent = (animatedProgress * 100).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProfileSurfaceCard(contentPadding = PaddingValues(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroAvatar(
                    progress = animatedProgress,
                    level = userLevel.currentLevel,
                    userName = userName,
                    profileImagePath = profileImagePath
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = userTitle.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ProfileAccentLight,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = ProfileBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LEVEL ${userLevel.currentLevel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.labelLarge,
                    color = ProfileAccentLight,
                    fontWeight = FontWeight.Bold
                )
            }

            ProgressTrack(
                progress = animatedProgress,
                modifier = Modifier.fillMaxWidth(),
                brush = Brush.horizontalGradient(listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1)))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "${userLevel.totalXp} XP total", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                Text(text = "${userLevel.xpRemaining} to next level", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
        }
    }
}

@Composable
private fun HeroAvatar(
    progress: Float,
    level: Int,
    userName: String,
    profileImagePath: String?
) {
    val ringSize = 108.dp
    val innerSize = 86.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ringSize)) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val strokeWidth = 7.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(ProfileAccent, ProfileAccentLight)),
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(ProfileSurfaceRaised)
                .border(1.dp, ProfileBorderStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (profileImagePath.isNullOrBlank()) {
                Text(
                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
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
                .offset(y = (-6).dp)
                .background(ProfileAccent, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(text = "LVL $level", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// =====================================================================
// Stats — one card: streak + inline best/XP. Clean risk banner.
// =====================================================================
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
            eyebrow = "Overview",
            title = "Listening rhythm",
            subtitle = if (streakAtRisk) "Your streak needs attention today."
                       else "You're building a solid habit. Here's the run so far."
        )

        ProfileSurfaceCard {
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
                        fontWeight = FontWeight.Bold,
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

            HorizontalDivider(color = ProfileBorder)

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
                    accent = ProfileAccentLight
                )
                Box(modifier = Modifier.width(1.dp).height(30.dp).background(ProfileBorder))
                InlineStat(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.AutoAwesome,
                    value = "${userLevel.totalXp}",
                    label = "Total XP",
                    accent = Amber
                )
            }
        }
    }
}

@Composable
private fun StreakRiskBanner(streakDurationMinutes: Long, timeRemaining: String) {
    val riskColor = when {
        streakDurationMinutes > 360 -> Color(0xFFFCA5A5)
        streakDurationMinutes > 180 -> Color(0xFFEF4444)
        streakDurationMinutes > 60 -> Color(0xFFB91C1C)
        else -> Color(0xFF7F1D1D)
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
            .background(ProfileSurfaceRaised)
            .border(1.dp, ProfileBorderStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = if (streakAtRisk) Color(0xFFFCA5A5) else ProfileAccentLight,
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

// =====================================================================
// Tab switcher — segmented control, tinted (no gradient)
// =====================================================================
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
            .background(ProfileSurface)
            .border(1.dp, ProfileBorder, RoundedCornerShape(16.dp))
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
                    .background(ProfileAccent.copy(alpha = 0.22f))
                    .border(1.dp, ProfileAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
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

// =====================================================================
// Challenges — single-surface cards, one accent per difficulty
// =====================================================================
private fun LazyListScope.challengesSection(
    challenges: List<DailyChallenge>,
    totalXpAvailable: Int,
    claimedChallengeIds: Set<Long>,
    onClaimChallenge: (Long) -> Unit,
    sidePadding: Dp
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
        ) {
            SectionHeader(
                eyebrow = "Quests",
                title = "Daily challenges",
                subtitle = "$completedCount of ${challenges.size} complete · $resetLabel",
                trailing = { XpChip(xp = totalXpAvailable) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProgressTrack(
                progress = if (challenges.isEmpty()) 0f else completedCount.toFloat() / challenges.size,
                modifier = Modifier.fillMaxWidth(),
                color = ProfileAccent
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    itemsIndexed(challenges, key = { _, c -> "challenge_${c.id}" }) { _, challenge ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
        ) {
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
private fun EmptyChallengesState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ProfileSurface)
            .border(1.dp, ProfileBorder, RoundedCornerShape(24.dp))
            .padding(28.dp),
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

@Composable
private fun ChallengeCard(
    challenge: DailyChallenge,
    isClaimed: Boolean,
    onClaim: () -> Unit
) {
    val isCompleted = challenge.isCompleted
    val diffColor = when (challenge.difficulty) {
        "EASY" -> Emerald
        "MEDIUM" -> Amber
        "HARD" -> Danger
        else -> Color.Gray
    }
    val accent = if (isCompleted) Emerald else diffColor
    val animatedProgress by animateFloatAsState(
        targetValue = challenge.progressFraction,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "challengeProgress"
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ProfileSurface)
            .border(1.dp, if (isCompleted) Emerald.copy(alpha = 0.30f) else ProfileBorder, RoundedCornerShape(20.dp))
    ) {
        if (isCompleted && !isClaimed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Emerald.copy(alpha = 0.16f), Emerald.copy(alpha = 0.04f), Color.Transparent)
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth(),
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
            Column(horizontalAlignment = Alignment.End) {
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
                color = if (isCompleted) Emerald else TextSecondary
            )
            when {
                isCompleted && !isClaimed -> {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClaim()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Emerald,
                            contentColor = Color(0xFF052E1E)
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
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Emerald, modifier = Modifier.size(16.dp))
                        Text(text = "Claimed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Emerald)
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

// =====================================================================
// Badges — clean grid. Emblem keeps the medal character; card stays flat.
// =====================================================================
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
    sidePadding: Dp
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sidePadding)
        ) {
            SectionHeader(
                eyebrow = "Achievements",
                title = "Your collection",
                subtitle = "$earnedCount of $totalCount badges earned.",
                trailing = if (totalStars > 0) ({ StarsChip(total = totalStars, max = maxPossibleStars) }) else null
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProgressTrack(progress = collectionProgress, modifier = Modifier.fillMaxWidth(), color = Amber)
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
                        selectedContainerColor = ProfileAccent.copy(alpha = 0.18f),
                        containerColor = Color.Transparent,
                        labelColor = TextSecondary,
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = ProfileBorder,
                        selectedBorderColor = ProfileAccent.copy(alpha = 0.5f),
                        enabled = true,
                        selected = selectedCategory == null
                    ),
                    shape = CircleShape
                )
                categories.forEach { category ->
                    val categoryColor = getCategoryColor(category)
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        label = { Text(getCategoryLabel(category), maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = categoryColor.copy(alpha = 0.16f),
                            containerColor = Color.Transparent,
                            labelColor = TextSecondary,
                            selectedLabelColor = categoryColor
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = ProfileBorder,
                            selectedBorderColor = categoryColor.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selectedCategory == category
                        ),
                        shape = CircleShape
                    )
                }
            }

            if (spotlightBadge != null) {
                Spacer(modifier = Modifier.height(16.dp))
                val spotlightLabel = if (spotlightBadge.isEarned) "Next star" else "Almost there"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
                    Text(text = spotlightLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                BadgeCard(badge = spotlightBadge, modifier = Modifier.fillMaxWidth(), isSpotlight = true)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = ProfileBorder)
            }
        }
    }

    if (filteredBadges.isEmpty()) {
        item(key = "badges_empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePadding)
                    .height(120.dp)
                    .background(ProfileSurface, RoundedCornerShape(20.dp))
                    .border(1.dp, ProfileBorder, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "No badges to show", color = TextTertiary)
            }
        }
    } else {
        // Trophy-case order: earned first, then by rarity (prestige), then by stars.
        val sortedBadges = filteredBadges.sortedWith(
            compareByDescending<Badge> { it.isEarned }
                .thenByDescending { GamificationEngine.getRarity(it.badgeId).sortWeight }
                .thenByDescending { it.stars }
        )
        items(sortedBadges.chunked(2), key = { row -> row.joinToString(",") { it.badgeId } }) { rowBadges ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowBadges.forEach { badge -> BadgeCard(badge = badge, modifier = Modifier.weight(1f)) }
                repeat(2 - rowBadges.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

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

// =====================
// Rarity presentation — rarity decides the *material* the medallion is
// struck from: pewter, silver, gold, rose gold, prismatic. The metal of the
// rim is the first thing the eye reads, so prestige telegraphs instantly.
// =====================
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

private fun getRarityGlintAlpha(rarity: GamificationEngine.BadgeRarity): Float = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> 0.13f
    GamificationEngine.BadgeRarity.RARE -> 0.15f
    GamificationEngine.BadgeRarity.EPIC -> 0.18f
    GamificationEngine.BadgeRarity.LEGENDARY -> 0.22f
    GamificationEngine.BadgeRarity.MYTHIC -> 0.28f
}

// =====================================================================
// Badge emblem — a struck collector's medallion. Rarity decides the metal
// of the rim, the badge's intrinsic color becomes the enamel face, and a
// slow specular glint sweeps across earned coins like foil under light.
// Locked badges are unstruck blanks: dark graphite with a faint imprint.
// =====================================================================
@Composable
private fun BadgeEmblem(badge: Badge, intrinsicColor: Color, modifier: Modifier = Modifier) {
    val isEarned = badge.isEarned
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val metal = getRarityMetal(rarity)
    val glowAlpha = if (isEarned) getRarityGlowAlpha(rarity) else 0f
    val glintAlpha = getRarityGlintAlpha(rarity)
    val art = remember(badge.badgeId) { BadgeArt.artFor(badge.badgeId) }

    // Slow glint sweep — staggered per badge so a grid of coins never syncs up.
    val glint = rememberInfiniteTransition(label = "badgeGlint")
    val glintPhase by glint.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3400, easing = LinearEasing)),
        label = "glintPhase"
    )
    val glintOffset = remember(badge.badgeId) {
        (kotlin.math.abs(badge.badgeId.hashCode()) % 100) / 100f
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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

            // Specular glint — a diagonal foil highlight crossing the face.
            if (isEarned) {
                val phase = (glintPhase + glintOffset) % 1f
                val stripeWidth = radius * 0.85f
                val travel = radius * 4f
                val x = -stripeWidth + travel * phase
                val coinPath = Path().apply { addOval(Rect(center, faceRadius)) }
                clipPath(coinPath) {
                    rotate(degrees = -24f, pivot = center) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = glintAlpha),
                                    Color.Transparent
                                ),
                                startX = x,
                                endX = x + stripeWidth
                            ),
                            topLeft = Offset(x, center.y - radius * 1.6f),
                            size = Size(stripeWidth, radius * 3.2f)
                        )
                    }
                }
            }
        }

        Icon(
            imageVector = art,
            contentDescription = badge.name,
            tint = if (isEarned) Color.White.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.22f),
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun BadgeCard(badge: Badge, modifier: Modifier = Modifier, isSpotlight: Boolean = false) {
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val isEarned = badge.isEarned
    val isBeginner = badge.badgeId in GamificationEngine.BEGINNER_BADGES
    val targetProgress = if (isEarned) 1f else badge.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "fill"
    )
    // Mint mark — when this badge was earned, like an engraving on a real coin.
    val earnedDate = remember(badge.earnedAt) {
        if (badge.isEarned && badge.earnedAt > 0L) {
            java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(badge.earnedAt))
        } else null
    }
    // Border intensity escalates with rarity so prestige reads at a glance.
    val earnedBorderAlpha = (0.20f + rarity.sortWeight * 0.12f).coerceAtMost(0.70f)

    Card(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEarned) (2 + rarity.sortWeight).dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = if (isSpotlight) {
            BorderStroke(2.dp, intrinsicColor.copy(alpha = 0.7f))
        } else {
            BorderStroke(1.dp, if (isEarned) intrinsicColor.copy(alpha = earnedBorderAlpha) else ProfileBorder)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isEarned) {
                        Brush.verticalGradient(listOf(darkTint(intrinsicColor, 0.40f), ProfileSurface))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFF191920), ProfileSurface))
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Display case — showcase glow, the medallion, and a pedestal shadow.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        if (isEarned) intrinsicColor.copy(alpha = 0.20f) else intrinsicColor.copy(alpha = 0.06f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BadgeEmblem(badge = badge, intrinsicColor = intrinsicColor, modifier = Modifier.size(76.dp))
                        Spacer(modifier = Modifier.height(7.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 46.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Transparent)
                                    )
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isEarned) TextPrimary else TextTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = badge.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEarned) TextSecondary else TextTertiary,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 13.sp
                )

                if (earnedDate != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "EARNED · $earnedDate",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = intrinsicColor.copy(alpha = 0.85f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isEarned) {
                    if (isBeginner) {
                        Box(
                            modifier = Modifier
                                .background(intrinsicColor.copy(alpha = 0.22f), RoundedCornerShape(50))
                                .border(1.dp, intrinsicColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = "UNLOCKED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = intrinsicColor, letterSpacing = 1.sp)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (i in 1..5) {
                                val activeColor = if (badge.isMaxed) intrinsicColor else Color(0xFFFBBF24)
                                val starColor = if (i <= badge.stars) activeColor else Color.White.copy(alpha = 0.12f)
                                Icon(
                                    imageVector = if (i <= badge.stars) Icons.Default.Star else Icons.Default.StarOutline,
                                    contentDescription = null,
                                    tint = starColor,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (badge.isMaxed) {
                            // Gold foil — a maxed badge is the peak of its line.
                            Box(
                                modifier = Modifier
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFF59E0B).copy(alpha = 0.28f), Color(0xFFEAB308).copy(alpha = 0.28f))
                                        ),
                                        RoundedCornerShape(50)
                                    )
                                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(text = "MAXED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFCD34D), letterSpacing = 1.sp)
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedProgress).clip(CircleShape).background(intrinsicColor)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${badge.progress} / ${badge.maxProgress}  →  ★${badge.stars + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedProgress).clip(CircleShape).background(intrinsicColor.copy(alpha = 0.55f))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${badge.progress} / ${badge.maxProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }

            // Rarity tag — always visible so locked badges advertise the prize they hide.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(rarityColor.copy(alpha = if (isEarned) 0.14f else 0.07f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(rarityColor))
                Text(
                    text = rarity.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isEarned) rarityColor else rarityColor.copy(alpha = 0.55f),
                    fontSize = 8.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// Safe blur modifier that works across different API levels
fun Modifier.safeBlur(radius: androidx.compose.ui.unit.Dp): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) this.blur(radius) else this

// =====================
// Compact Level Ring for HomeScreen header
// =====================
@Composable
fun CompactLevelRing(
    level: Int,
    progress: Float,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "compactLevelProgress"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
            Canvas(modifier = Modifier.size(28.dp)) {
                val strokeWidth = 3.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2
                val topLeft = Offset((size.width - radius * 2) / 2, (size.height - radius * 2) / 2)
                val arcSize = Size(radius * 2, radius * 2)
                drawArc(
                    color = Color.White.copy(alpha = 0.15f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                brush = Brush.sweepGradient(listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1))),
                    startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Text(text = "$level", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 10.sp)
        }

        Text(text = title, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
    }
}

// =====================================================================
// New Badge Celebration Overlay
// =====================================================================
@Composable
fun NewBadgeCelebrationOverlay(badges: List<Badge>, onDismiss: () -> Unit) {
    if (badges.isEmpty()) return

    var currentIndex by remember { mutableStateOf(0) }
    val currentBadge = badges[currentIndex]

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(currentIndex) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        kotlinx.coroutines.delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable {
                if (currentIndex < badges.size - 1) currentIndex++ else onDismiss()
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "badgeAura")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f, targetValue = 1.1f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "badgeScale"
        )

        val badgeColor = getUniqueBadgeColor(currentBadge.badgeId)
        val rarity = GamificationEngine.getRarity(currentBadge.badgeId)
        val rarityColor = getRarityColor(rarity)
        val worthXp = GamificationEngine.getBadgeXpContribution(currentBadge.badgeId, currentBadge.stars)
        Box(
            modifier = Modifier
                .size(350.dp)
                .scale(scale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(rarityColor.copy(alpha = 0.35f), badgeColor.copy(alpha = 0.08f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        ConfettiEffect()

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val titleText = if (currentBadge.isEarned && currentBadge.stars == 1) "NEW BADGE UNLOCKED!" else "BADGE UPGRADED!"
            Text(
                text = titleText,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = badgeColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = rarity.label.uppercase() + " ACHIEVEMENT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = rarityColor,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Pop-in animation keyed to each badge so the reveal feels earned, not stamped.
            val popIn = remember(currentIndex) { Animatable(0f) }
            LaunchedEffect(currentIndex) {
                popIn.snapTo(0f)
                popIn.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .scale(0.82f + 0.18f * popIn.value)
                    .alpha(popIn.value)
            ) {
                BadgeCard(badge = currentBadge, modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 1..5) {
                    val isEarnedStar = i <= currentBadge.stars
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = if (isEarnedStar) {
                            if (currentBadge.isMaxed) badgeColor else Color(0xFFFFD700)
                        } else Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // The payoff — show the XP this badge is now worth so the unlock feels earned.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Amber.copy(alpha = 0.14f))
                    .border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
                Text(
                    text = "WORTH $worthXp XP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Amber,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(text = "Tap anywhere to continue", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.5f))

            if (badges.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "${currentIndex + 1} of ${badges.size}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.3f))
            }
        }
    }
}

// =====================================================================
// Level Up Celebration Overlay
// =====================================================================
@Composable
fun LevelUpCelebration(level: Int, onDismiss: () -> Unit) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        kotlinx.coroutines.delay(100)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        repeat(5) {
            kotlinx.coroutines.delay(150)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    if (isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable { onDismiss() }
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "aura")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f, targetValue = 1.2f,
                animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "auraScale"
            )

            Box(
                modifier = Modifier
                    .size(300.dp)
                    .scale(scale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(ProfileAccent.copy(alpha = 0.3f), ProfileAccentLight.copy(alpha = 0.1f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )

            ConfettiEffect()

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    text = "LEVEL UP!",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Amber,
                    modifier = Modifier.scale(1.1f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "$level", style = MaterialTheme.typography.displayLarge, fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "You reached Level $level", style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.9f))
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Awesome!", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConfettiEffect() {
    val particles = remember {
        List(50) {
            ConfettiParticle(
                x = (0..1000).random() / 1000f,
                y = (0..1000).random() / 1000f - 1f,
                color = listOf(Color(0xFFEC4899), Color(0xFFA855F7), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFF10B981)).random(),
                speed = (5..15).random() / 1000f,
                radius = (5..15).random().toFloat()
            )
        }
    }

    val timer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        timer.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart)
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            val animatedY = (particle.y + timer.value * particle.speed * 50) % 1.5f - 0.2f
            drawCircle(
                color = particle.color,
                radius = particle.radius,
                center = Offset(x = particle.x * size.width, y = animatedY * size.height),
                alpha = if (animatedY > 1f) 0f else 1f
            )
        }
    }
}

data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val color: Color,
    val speed: Float,
    val radius: Float
)
