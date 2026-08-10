package me.avinas.tempo.ui.profile

import me.avinas.tempo.ui.theme.BronzeDark
import me.avinas.tempo.ui.theme.BronzeLight
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.GoldDark
import me.avinas.tempo.ui.theme.GoldLight
import me.avinas.tempo.ui.theme.GoldPrimary
import me.avinas.tempo.ui.theme.SilverDark
import me.avinas.tempo.ui.theme.SilverLight
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorAlt
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoErrorSoft
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSky
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TempoSuccessBright
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoSurface
import me.avinas.tempo.ui.theme.TempoSurfaceElevated
import me.avinas.tempo.ui.theme.TempoSurfaceSunken
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TempoWarningDeep
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextQuaternary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.StarHalf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin


private fun darkTint(color: Color, factor: Float = 0.22f): Color = Color(
    red = (color.red * factor + 0.04f).coerceIn(0f, 1f),
    green = (color.green * factor + 0.04f).coerceIn(0f, 1f),
    blue = (color.blue * factor + 0.04f).coerceIn(0f, 1f)
)

// =====================
// Badge icon mapping
// =====================
private fun getBadgeIcon(iconName: String): ImageVector = when (iconName) {
    "music_note" -> Icons.Default.MusicNote
    "century" -> Icons.Default.Star
    "star_half" -> Icons.AutoMirrored.Filled.StarHalf
    "star" -> Icons.Default.Star
    "diamond" -> Icons.Default.Diamond
    "emoji_events" -> Icons.Default.EmojiEvents
    "timer" -> Icons.Default.Timer
    "schedule" -> Icons.Default.Schedule
    "hourglass_full" -> Icons.Default.HourglassFull
    "headphones" -> Icons.Default.Headphones
    "local_fire_department" -> Icons.Default.LocalFireDepartment
    "whatshot" -> Icons.Default.Whatshot
    "military_tech" -> Icons.Default.MilitaryTech
    "auto_awesome" -> Icons.Default.AutoAwesome
    "explore" -> Icons.Default.Explore
    "collections" -> Icons.Default.Collections
    "public" -> Icons.Default.Public
    "category" -> Icons.Default.Category
    "palette" -> Icons.Default.Palette
    "nightlight" -> Icons.Default.Nightlight
    "wb_sunny" -> Icons.Default.WbSunny
    "directions_run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "grade" -> Icons.Default.Grade
    "looks_one" -> Icons.Default.LooksOne
    "workspace_premium" -> Icons.Default.WorkspacePremium
    "shield" -> Icons.Default.Shield
    else -> Icons.Default.Star
}

private fun getCategoryColor(category: String): Color = when (category) {
    "MILESTONE" -> TempoWarning
    "TIME" -> TempoInfo
    "STREAK" -> TempoError
    "DISCOVERY" -> TempoSuccessDeep
    "ENGAGEMENT" -> TempoAccent
    "LEVEL" -> TempoPrimary
    else -> TextTertiary
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
            .background(TempoSurface)
            .border(1.dp, Divider, RoundedCornerShape(24.dp))
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
                    color = TempoAccentBright,
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
    color: Color = TempoPrimary,
    brush: Brush? = null,
    trackColor: Color = Divider,
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
            .background(TempoSurface)
            .border(1.dp, Divider, CircleShape)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun XpChip(xp: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TempoPrimary.copy(alpha = 0.12f))
            .border(1.dp, TempoPrimary.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = TempoAccentBright, modifier = Modifier.size(14.dp))
        Text(text = "+$xp XP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TempoAccentBright)
    }
}

@Composable
private fun StarsChip(total: Int, max: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TempoWarning.copy(alpha = 0.12f))
            .border(1.dp, TempoWarning.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(14.dp))
        Text(text = "$total / $max", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TempoWarning)
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
                                    onClaimChallenge = viewModel::claimChallenge,
                                    sidePadding = sidePadding
                                )
                            } else {
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
                        color = TempoAccentBright,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = Divider)

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
                    color = TempoAccentBright,
                    fontWeight = FontWeight.Bold
                )
            }

            ProgressTrack(
                progress = animatedProgress,
                modifier = Modifier.fillMaxWidth(),
                brush = Brush.horizontalGradient(listOf(TempoPrimary, TempoAccent, TempoPrimaryMuted))
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
                color = Divider,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(TempoPrimary, TempoAccentBright)),
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(CircleShape)
                .background(TempoSurfaceElevated)
                .border(1.dp, Divider, CircleShape),
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
                .background(TempoPrimary, RoundedCornerShape(8.dp))
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
                    accent = TempoAccentBright
                )
                Box(modifier = Modifier.width(1.dp).height(30.dp).background(Divider))
                InlineStat(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.AutoAwesome,
                    value = "${userLevel.xpRemaining}",
                    label = "XP to level",
                    accent = TempoWarning
                )
            }
        }

        Text(
            text = "Best streak is ${userLevel.longestStreak} days. You're ${(userLevel.levelProgress * 100).roundToInt()}% of the way to the next level.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
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
            .background(TempoSurfaceElevated)
            .border(1.dp, Divider, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = if (streakAtRisk) TempoErrorSoft else TempoAccentBright,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TempoSurface)
            .border(1.dp, Divider, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) TempoPrimary.copy(alpha = 0.22f) else Color.Transparent)
                    .border(1.dp, if (selected) TempoPrimary.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) TextPrimary else TextTertiary,
                    maxLines = 1
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
    onClaimChallenge: (Long) -> Unit,
    sidePadding: Dp
) {
    val completedCount = challenges.count { it.isCompleted }
    item(key = "challenges_header") {
        val resetLabel = remember {
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
                color = TempoPrimary
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
            ChallengeCard(challenge = challenge, onClaim = { onClaimChallenge(challenge.id) })
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
            .background(TempoSurface)
            .border(1.dp, Divider, RoundedCornerShape(24.dp))
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
private fun ChallengeCard(challenge: DailyChallenge, onClaim: () -> Unit) {
    val isCompleted = challenge.isCompleted
    val diffColor = when (challenge.difficulty) {
        "EASY" -> TempoSuccessDeep
        "MEDIUM" -> TempoWarning
        "HARD" -> TempoError
        else -> TextTertiary
    }
    val accent = if (isCompleted) TempoSuccessDeep else diffColor
    val animatedProgress by animateFloatAsState(
        targetValue = challenge.progressFraction,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "challengeProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TempoSurface)
            .border(1.dp, if (isCompleted) TempoSuccessDeep.copy(alpha = 0.30f) else Divider, RoundedCornerShape(20.dp))
    ) {
        if (isCompleted) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(TempoSuccessDeep.copy(alpha = 0.16f), TempoSuccessDeep.copy(alpha = 0.04f), Color.Transparent)
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
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
            Spacer(modifier = Modifier.width(14.dp))
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
                text = "${challenge.currentProgress}/${challenge.targetValue} progress",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCompleted) TempoSuccessDeep else TextSecondary
            )
            Text(
                text = if (isCompleted) "Completed" else "In progress",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
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
                        selectedContainerColor = TempoPrimary.copy(alpha = 0.18f),
                        containerColor = Color.Transparent,
                        labelColor = TextSecondary,
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Divider,
                        selectedBorderColor = TempoPrimary.copy(alpha = 0.5f),
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
                            borderColor = Divider,
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
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(16.dp))
                    Text(text = spotlightLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                BadgeCard(badge = spotlightBadge, modifier = Modifier.fillMaxWidth(), isSpotlight = true)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Divider)
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
                    .background(TempoSurface, RoundedCornerShape(20.dp))
                    .border(1.dp, Divider, RoundedCornerShape(20.dp)),
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
    "first_play" -> TempoSuccessDeep
    "plays_100" -> TempoInfo
    "plays_500" -> TempoPrimary
    "plays_1000" -> TempoPrimary
    "plays_5000" -> TempoErrorAlt
    "plays_10000" -> TempoWarning
    "time_1h" -> TempoCyan
    "time_24h" -> TempoSky
    "time_100h" -> TempoPrimaryMuted
    "time_500h" -> TempoAccent
    "streak_7" -> TempoWarning
    "streak_30" -> TempoError
    "streak_100" -> TempoError
    "streak_365" -> TempoErrorDeep
    "artists_10" -> TempoPrimaryMuted
    "artists_50" -> TempoSuccess
    "artists_100" -> TempoSuccess
    "genres_10" -> TempoWarning
    "genres_25" -> TempoWarningDeep
    "night_owl" -> TempoPrimaryDeep
    "early_bird" -> TempoWarningBright
    "marathon" -> TempoPrimary
    "level_5" -> TempoSuccessBright
    "level_10" -> TempoSuccessBright
    "level_25" -> TempoSuccessDeep
    "level_50" -> TempoSuccessDeep
    "level_75" -> TempoSuccessDeep
    "level_100" -> TempoSuccessDeep
    else -> TempoAccent
}

// =====================
// Rarity presentation — a second axis on top of the star-tier metallic material.
// COMMON: clean, no extras. RARE: colored rim. EPIC+: rim + aura glow.
// =====================
private fun getRarityColor(rarity: GamificationEngine.BadgeRarity): Color = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> TextTertiary
    GamificationEngine.BadgeRarity.RARE -> TempoInfo
    GamificationEngine.BadgeRarity.EPIC -> TempoAccent
    GamificationEngine.BadgeRarity.LEGENDARY -> TempoWarning
    GamificationEngine.BadgeRarity.MYTHIC -> TempoPrimary
}

private fun getRarityRimAlpha(rarity: GamificationEngine.BadgeRarity): Float = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> 0f
    GamificationEngine.BadgeRarity.RARE -> 0.45f
    GamificationEngine.BadgeRarity.EPIC -> 0.65f
    GamificationEngine.BadgeRarity.LEGENDARY -> 0.8f
    GamificationEngine.BadgeRarity.MYTHIC -> 0.95f
}

private fun getRarityGlowAlpha(rarity: GamificationEngine.BadgeRarity): Float = when (rarity) {
    GamificationEngine.BadgeRarity.COMMON -> 0f
    GamificationEngine.BadgeRarity.RARE -> 0f
    GamificationEngine.BadgeRarity.EPIC -> 0.20f
    GamificationEngine.BadgeRarity.LEGENDARY -> 0.30f
    GamificationEngine.BadgeRarity.MYTHIC -> 0.42f
}

private fun getBadgeShapePath(size: Size, badgeId: String): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width.coerceAtMost(size.height) / 2f

    fun drawPolygon(sides: Int, rotationDegrees: Float = 0f) {
        for (i in 0 until sides) {
            val angle = i * (360f / sides) + rotationDegrees
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + radius * cos(rad).toFloat()
            val y = cy + radius * sin(rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    fun drawStar(points: Int, innerRatio: Float, rotationDegrees: Float = 0f) {
        for (i in 0 until points * 2) {
            val angle = i * (180f / points) + rotationDegrees
            val rad = Math.toRadians(angle.toDouble())
            val r = if (i % 2 == 0) radius else radius * innerRatio
            val x = cx + r * cos(rad).toFloat()
            val y = cy + r * sin(rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    fun drawShield(widthFactor: Float = 1f) {
        val w = radius * widthFactor
        path.moveTo(cx, cy - radius)
        path.lineTo(cx + w, cy - radius * 0.8f)
        path.lineTo(cx + w, cy + radius * 0.2f)
        path.quadraticTo(cx + w * 0.5f, cy + radius, cx, cy + radius)
        path.quadraticTo(cx - w * 0.5f, cy + radius, cx - w, cy + radius * 0.2f)
        path.lineTo(cx - w, cy - radius * 0.8f)
        path.close()
    }

    when (badgeId) {
        "first_play" -> drawPolygon(3, -90f)
        "plays_100" -> drawPolygon(4, 45f)
        "plays_500" -> drawPolygon(5, -90f)
        "plays_1000" -> drawPolygon(6, 0f)
        "plays_5000" -> drawPolygon(8, 22.5f)
        "plays_10000" -> drawStar(10, 0.7f, -90f)
        "time_1h" -> drawStar(4, 0.6f, 0f)
        "time_24h" -> drawStar(8, 0.8f, 0f)
        "time_100h" -> drawStar(12, 0.85f, 0f)
        "time_500h" -> drawStar(24, 0.9f, 0f)
        "streak_7" -> drawShield(0.7f)
        "streak_30" -> drawShield(0.85f)
        "streak_100" -> drawShield(1.0f)
        "streak_365" -> drawStar(16, 0.6f, -90f)
        "artists_10" -> drawStar(4, 0.3f, 45f)
        "artists_50" -> drawStar(8, 0.5f, 22.5f)
        "artists_100" -> drawStar(12, 0.5f, 0f)
        "genres_10" -> drawStar(5, 0.4f, -90f)
        "genres_25" -> drawStar(7, 0.45f, -90f)
        "night_owl" -> { path.addOval(androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius)) }
        "early_bird" -> drawStar(8, 0.6f, 0f)
        "marathon" -> drawPolygon(4, 0f)
        "level_5" -> drawPolygon(3, 90f)
        "level_10" -> { path.moveTo(cx, cy - radius); path.lineTo(cx + radius * 0.8f, cy); path.lineTo(cx, cy + radius); path.lineTo(cx - radius * 0.8f, cy); path.close() }
        "level_25" -> drawPolygon(5, 90f)
        "level_50" -> drawPolygon(6, 30f)
        "level_75" -> drawStar(6, 0.7f, 30f)
        "level_100" -> drawStar(8, 0.7f, 22.5f)
        else -> drawPolygon(6, 0f)
    }
    return path
}

@Composable
private fun BadgeEmblem(badge: Badge, intrinsicColor: Color, modifier: Modifier = Modifier) {
    val isEarned = badge.isEarned
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val rimAlpha = if (isEarned) getRarityRimAlpha(rarity) else 0f
    val glowAlpha = if (isEarned) getRarityGlowAlpha(rarity) else 0f
    val metallicShineColors = when {
        !isEarned -> listOf(TempoSurfaceElevated, TempoSurface, TempoSurface)
        badge.stars <= 2 -> listOf(BronzeLight, BronzeLight, BronzeDark, BronzeDark)
        badge.stars <= 4 -> listOf(TextPrimary, SilverLight, SilverDark, TextQuaternary)
        else -> listOf(GoldLight, GoldPrimary, GoldDark, GoldDark)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = getBadgeShapePath(size, badge.badgeId)
            val center = Offset(size.width / 2f, size.height / 2f)

            // Rarity aura — a soft circular glow behind the medal for EPIC+.
            if (glowAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(rarityColor.copy(alpha = glowAlpha), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f
                    )
                )
            }

            // Metallic body (full size).
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    colors = metallicShineColors,
                    start = Offset(size.width * 0.1f, 0f),
                    end = Offset(size.width * 0.9f, size.height)
                ),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )

            // Rarity rim — a colored edge that telegraphs rarity at a glance.
            if (rimAlpha > 0f) {
                drawPath(
                    path = path,
                    color = rarityColor.copy(alpha = rimAlpha),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawContext.transform.translate(center.x, center.y)
            drawContext.transform.scale(0.88f, 0.88f)
            drawContext.transform.translate(-center.x, -center.y)

            val innerPath = getBadgeShapePath(size, badge.badgeId)
            if (isEarned) {
                drawPath(
                    path = innerPath,
                    brush = Brush.radialGradient(
                        colors = listOf(TextTertiary, intrinsicColor, intrinsicColor.copy(alpha = 0.7f), TempoBackground.copy(alpha = 0.5f)),
                        center = Offset(center.x * 0.7f, center.y * 0.5f),
                        radius = size.width * 0.9f
                    )
                )
            } else {
                drawPath(
                    path = innerPath,
                    brush = Brush.radialGradient(
                        colors = listOf(TempoSurfaceElevated, TempoSurface),
                        center = center,
                        radius = size.width * 0.7f
                    )
                )
            }
        }

        Icon(
            imageVector = getBadgeIcon(badge.iconName),
            contentDescription = badge.name,
            tint = if (isEarned) TextPrimary else Divider,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun BadgeCard(badge: Badge, modifier: Modifier = Modifier, isSpotlight: Boolean = false) {
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val isEarned = badge.isEarned
    val targetProgress = if (isEarned) 1f else badge.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "fill"
    )
    val containerColor = if (isEarned) darkTint(intrinsicColor) else TempoSurfaceSunken
    val isBeginner = badge.badgeId in GamificationEngine.BEGINNER_BADGES
    // Border intensity escalates with rarity so prestige reads at a glance.
    val earnedBorderAlpha = (0.20f + rarity.sortWeight * 0.12f).coerceAtMost(0.70f)

    Card(
        modifier = modifier.animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEarned) (3 + rarity.sortWeight).dp else 2.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isSpotlight) {
            BorderStroke(2.dp, intrinsicColor.copy(alpha = 0.7f))
        } else {
            BorderStroke(1.dp, if (isEarned) intrinsicColor.copy(alpha = earnedBorderAlpha) else Divider)
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BadgeEmblem(badge = badge, intrinsicColor = intrinsicColor, modifier = Modifier.size(76.dp))

                Spacer(modifier = Modifier.height(12.dp))

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
                                val activeColor = if (badge.isMaxed) intrinsicColor else TempoWarningBright
                                val starColor = if (i <= badge.stars) activeColor else TextQuaternary
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
                            Box(
                                modifier = Modifier
                                    .background(intrinsicColor.copy(alpha = 0.22f), RoundedCornerShape(50))
                                    .border(1.dp, intrinsicColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(text = "MAXED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = intrinsicColor, letterSpacing = 1.sp)
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(TempoBackground.copy(alpha = 0.3f))
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
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(TempoBackground.copy(alpha = 0.35f))
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(rarityColor.copy(alpha = if (isEarned) 0.16f else 0.08f), RoundedCornerShape(50))
                    .border(1.dp, rarityColor.copy(alpha = if (isEarned) 0.45f else 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
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
            .background(Divider)
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
                    color = Divider,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                brush = Brush.sweepGradient(listOf(TempoPrimary, TempoAccent, TempoPrimaryMuted)),
                    startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Text(text = "$level", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 10.sp)
        }

        Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 1)
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
            .background(TempoBackground.copy(alpha = 0.9f))
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
                            if (currentBadge.isMaxed) badgeColor else GoldPrimary
                        } else Divider,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // The payoff — show the XP this badge is now worth so the unlock feels earned.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(TempoWarning.copy(alpha = 0.14f))
                    .border(1.dp, TempoWarning.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = TempoWarning, modifier = Modifier.size(16.dp))
                Text(
                    text = "WORTH $worthXp XP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TempoWarning,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(text = "Tap anywhere to continue", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)

            if (badges.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "${currentIndex + 1} of ${badges.size}", style = MaterialTheme.typography.labelMedium, color = TextTertiary)
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
                .background(TempoBackground.copy(alpha = 0.8f))
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
                            colors = listOf(TempoPrimary.copy(alpha = 0.3f), TempoAccentBright.copy(alpha = 0.1f), Color.Transparent)
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
                    color = TempoWarning,
                    modifier = Modifier.scale(1.1f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "$level", style = MaterialTheme.typography.displayLarge, fontSize = 120.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "You reached Level $level", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = TextOnAccent),
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
                color = listOf(TempoPrimary, TempoAccent, TempoPrimaryMuted, TempoWarning, TempoSuccessDeep).random(),
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
