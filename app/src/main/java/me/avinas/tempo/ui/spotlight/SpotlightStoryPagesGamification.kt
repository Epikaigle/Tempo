package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.components.LocalInCaptureContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoSuccessDeep

// =====================================================================
// GAMIFICATION STORY PAGES — Badges, Level Up, Title Earned
// =====================================================================

private val BadgeGold = TempoWarningBright
private val BadgeAmber = TempoWarning

@Composable
fun BadgesEarnedPage(page: SpotlightStoryPage.BadgesEarned) {
    StoryPageScaffold(
        label = stringResource(R.string.spotlight_badges_earned),
        labelIcon = Icons.Default.EmojiEvents,
        conversationalText = page.conversationalText,
        background = {
            StoryPulsingGlow(
                color = BadgeAmber,
                size = 260.dp,
                minAlpha = 0.12f,
                maxAlpha = 0.30f,
                alignment = Alignment.TopCenter
            )
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Badge grid (2 columns)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp * dimens.scale),
            modifier = Modifier.fillMaxWidth()
        ) {
            page.badges.chunked(2).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    row.forEachIndexed { colIndex, badge ->
                        Box(modifier = Modifier.weight(1f)) {
                            EnterAnimation(
                                delay = StoryTiming.stagger(rowIndex * 2 + colIndex, base = StoryTiming.Hero)
                            ) {
                                BadgeTile(badge = badge, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Collection progress
        EnterAnimation(delay = StoryTiming.ContentSub) {
            StoryChip(
                text = stringResource(R.string.spotlight_badges_count, page.totalEarned, page.totalPossible),
                accentColor = BadgeGold,
                backgroundColor = BadgeAmber.copy(alpha = 0.10f),
                borderColor = BadgeAmber.copy(alpha = 0.30f)
            )
        }
    }
}

@Composable
private fun BadgeTile(
    badge: SpotlightStoryPage.BadgesEarned.BadgeEntry,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSpotlightDimens.current
    val accent = badgeCategoryColor(badge.category)
    StoryGlassCard(
        modifier = modifier,
        backgroundColor = accent.copy(alpha = 0.08f),
        borderColor = accent.copy(alpha = 0.25f),
        contentPadding = PaddingValues(10.dp * dimens.scale)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = spotlightBadgeIcon(badge.iconName),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp * dimens.scale)
            )
            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
            // Star rating
            Row(horizontalArrangement = Arrangement.Center) {
                repeat(badge.stars) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = BadgeGold,
                        modifier = Modifier.size(12.dp * dimens.scale)
                    )
                }
                repeat((5 - badge.stars).coerceAtLeast(0)) {
                    Icon(
                        imageVector = Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = BadgeGold.copy(alpha = 0.4f),
                        modifier = Modifier.size(12.dp * dimens.scale)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
            Text(
                text = badge.name,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = (dimens.textBody.value * 0.88f).sp,
                    fontWeight = FontWeight.Bold
                ),
                color = accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = badge.description,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (dimens.textLabel.value * 0.8f).sp
                ),
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun badgeCategoryColor(category: String): Color = when (category) {
    "MILESTONE" -> TempoWarning
    "TIME" -> TempoSuccessDeep
    "STREAK" -> TempoError
    "DISCOVERY" -> TempoInfoSoft
    "ENGAGEMENT" -> TempoAccentBright
    "LEVEL" -> TempoWarningBright
    else -> TempoAccent
}

/** Mirrors the badge icon vocabulary used by the profile screen. */
private fun spotlightBadgeIcon(iconName: String): ImageVector = when (iconName) {
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

// ── Level Up ────────────────────────────────────────────────────────

private val LevelGold = TempoWarningBright
private val LevelAmber = TempoWarning

@Composable
fun LevelUpPage(page: SpotlightStoryPage.LevelUp) {
    val animatedLevel = rememberStoryCountUp(page.currentLevel.toFloat(), delayMillis = 300, durationMillis = 1000)
    val animatedXp = rememberStoryCountUp(page.xpEarnedThisPeriod.toFloat(), delayMillis = 500, durationMillis = 1200)

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_level_up),
        labelIcon = Icons.Default.Bolt,
        labelColor = LevelGold,
        conversationalText = page.conversationalText,
        background = { LevelUpStreakBackground() }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Level reveal
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.spotlight_level_label),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = dimens.textLabel,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (4f * dimens.scale).sp
                    ),
                    color = LevelGold.copy(alpha = 0.7f)
                )
                Text(
                    text = animatedLevel.toInt().toString(),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = (dimens.textDisplay.value * 1.1f).sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = LevelAmber.copy(alpha = 0.6f),
                            offset = Offset(0f, 6f),
                            blurRadius = 24f
                        )
                    ),
                    color = LevelGold
                )
                Text(
                    text = page.currentTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                    fontWeight = FontWeight.Bold,
                    color = LevelGold.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Progress to next level
        EnterAnimation(delay = StoryTiming.Content) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.spotlight_lv_format, page.currentLevel),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                        fontWeight = FontWeight.Bold,
                        color = LevelGold
                    )
                    Text(
                        text = stringResource(R.string.spotlight_lv_format, page.currentLevel + 1),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                StoryProgressBar(
                    targetFraction = page.levelProgress.coerceIn(0f, 1f),
                    color = LevelAmber,
                    gradientColors = listOf(TempoError, LevelAmber, LevelGold),
                    delayMillis = StoryTiming.Content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // XP earned card
        EnterAnimation(delay = StoryTiming.ContentSub) {
            StoryGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = LevelAmber.copy(alpha = 0.08f),
                borderColor = LevelAmber.copy(alpha = 0.25f),
                contentPadding = PaddingValues(16.dp * dimens.scale),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        val xpDisplayed = animatedXp.toLong()
                        val xpText = if (xpDisplayed >= 1000) {
                            stringResource(R.string.spotlight_xp_format_k, xpDisplayed / 1000f)
                        } else {
                            stringResource(R.string.spotlight_xp_format, xpDisplayed)
                        }
                        Text(
                            text = xpText,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = dimens.textTitle,
                                fontWeight = FontWeight.Black
                            ),
                            color = LevelGold
                        )
                        Text(
                            text = stringResource(R.string.spotlight_earned_this_period),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                    Text(
                        text = page.currentTitle,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = (dimens.textBody.value * 0.85f).sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = LevelGold.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** Horizontal energy streak across the middle of the page. */
@Composable
private fun LevelUpStreakBackground() {
    val dimens = LocalSpotlightDimens.current
    val transition = rememberInfiniteTransition(label = "levelStreak")
    val streakAlpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streakAlpha"
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp * dimens.scale)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            LevelAmber.copy(alpha = streakAlpha),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

// ── Title Earned ────────────────────────────────────────────────────

@Composable
fun TitleEarnedPage(page: SpotlightStoryPage.TitleEarned) {
    val inCapture = LocalInCaptureContext.current
    val titleColor = titleColorFor(page.newTitle)

    // Ceremonial swap: old title fades away, new title rises
    val oldTitleAlpha = remember { Animatable(0f) }
    val newTitleOffset = remember { Animatable(80f) }
    val newTitleAlpha = remember { Animatable(0f) }
    val ringAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (inCapture) {
            oldTitleAlpha.snapTo(0f)
            newTitleOffset.snapTo(0f)
            newTitleAlpha.snapTo(1f)
            ringAlpha.snapTo(1f)
        } else {
            launch {
                delay(300)
                ringAlpha.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
            }
            delay(200)
            oldTitleAlpha.animateTo(1f, tween(400))
            delay(500)
            oldTitleAlpha.animateTo(0f, tween(350))
            delay(100)
            newTitleOffset.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
            newTitleAlpha.animateTo(1f, tween(500))
        }
    }

    val haloTransition = rememberInfiniteTransition(label = "titleHalo")
    val haloAngle by haloTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloAngle"
    )

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_new_title),
        labelColor = titleColor,
        conversationalText = page.conversationalText,
        background = {
            TitleHaloBackground(
                color = titleColor,
                angle = haloAngle,
                alpha = ringAlpha.value
            )
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Title swap stage
        Box(
            modifier = Modifier
                .height(100.dp * dimens.scale)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (page.previousTitle.isNotBlank()) {
                Text(
                    text = page.previousTitle,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = (dimens.textTitle.value * 0.9f).sp,
                        textDecoration = TextDecoration.LineThrough
                    ),
                    color = Color.White.copy(alpha = oldTitleAlpha.value * 0.4f),
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = page.newTitle,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = (dimens.textHeadline.value * 1.1f).sp,
                    fontWeight = FontWeight.Black,
                    shadow = Shadow(
                        color = titleColor.copy(alpha = 0.8f),
                        offset = Offset(0f, 6f),
                        blurRadius = 24f
                    )
                ),
                color = titleColor.copy(alpha = newTitleAlpha.value),
                modifier = Modifier.offset(y = newTitleOffset.value.dp),
                textAlign = TextAlign.Center
            )
        }

        // Stats
        EnterAnimation(delay = StoryTiming.ContentSub) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
            ) {
                StoryStatTile(
                    value = stringResource(R.string.spotlight_lv_format, page.currentLevel),
                    label = stringResource(R.string.spotlight_level),
                    modifier = Modifier.weight(1f),
                    valueColor = titleColor,
                    backgroundColor = titleColor.copy(alpha = 0.08f),
                    borderColor = titleColor.copy(alpha = 0.25f)
                )
                StoryStatTile(
                    value = page.uniqueArtists.toString(),
                    label = stringResource(R.string.spotlight_artists_discovered),
                    modifier = Modifier.weight(1f),
                    valueColor = titleColor,
                    backgroundColor = titleColor.copy(alpha = 0.08f),
                    borderColor = titleColor.copy(alpha = 0.25f)
                )
            }
        }
    }
}
/** Dashed rotating halo ring with a glowing core. */
@Composable
private fun TitleHaloBackground(
    color: Color,
    angle: Float,
    alpha: Float
) {
    val dimens = LocalSpotlightDimens.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(220.dp * dimens.scale)) {
            val strokePx = 2.dp.toPx()
            val dashCount = 24
            val sweepEach = 360f / dashCount
            for (i in 0 until dashCount) {
                drawArc(
                    color = color.copy(alpha = if (i % 2 == 0) 0.6f else 0.15f),
                    startAngle = angle + i * sweepEach,
                    sweepAngle = sweepEach * 0.65f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(16.dp * dimens.scale)
                .background(
                    brush = Brush.radialGradient(listOf(color, Color.Transparent)),
                    shape = CircleShape
                )
        )
    }
}

private fun titleColorFor(title: String): Color = when (title) {
    "Sound God" -> TempoAccent
    "Audiophile" -> TempoAccentBright
    "Music Legend" -> TempoAccent
    "Music Connoisseur" -> TempoInfoSoft
    "Dedicated Listener" -> TempoSuccessDeep
    "Music Enthusiast" -> TempoWarningBright
    "Music Fan" -> TextSecondary
    else -> TempoAccent
}
