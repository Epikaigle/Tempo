package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoWarningBright
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoWarning

// =====================================================================
// CORE STORY PAGES — Listening Minutes, Streak, Listening Clock
// =====================================================================

@Composable
fun ListeningMinutesPage(page: SpotlightStoryPage.ListeningMinutes) {
    val animatedMinutes = rememberStoryCountUp(
        targetValue = page.totalMinutes.toFloat(),
        delayMillis = 400,
        durationMillis = 2000
    )

    StoryPageScaffold(
        label = SpotlightPeriodFormatter.storyTitle(
            context = LocalContext.current,
            timeRange = page.timeRange,
            year = page.year
        ),
        conversationalText = page.conversationalText,
        background = {
            StoryPulsingGlow(
                color = Color.White,
                size = 340.dp,
                minAlpha = 0.04f,
                maxAlpha = 0.12f
            )
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Hero: "You listened for 15,280 minutes"
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.spotlight_listened_for),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(dimens.spacerSmall))
                Text(
                    text = String.format(Locale.US, "%,d", animatedMinutes.toInt()),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = dimens.textDisplay,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.3f),
                            offset = Offset(0f, 4f),
                            blurRadius = 12f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = (dimens.textDisplay.value * 1.1f).sp
                )
                Text(
                    text = stringResource(R.string.spotlight_listened_minutes),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    letterSpacing = (2f * dimens.scale).sp
                )
            }
        }

        // Callouts: non-stop days + comparison
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val daysInt = (page.totalMinutes.toFloat() / (24 * 60)).toInt()
            if (daysInt > 0) {
                EnterAnimation(delay = StoryTiming.Content) {
                    StoryChip(
                        text = stringResource(R.string.spotlight_days_nonstop, daysInt),
                        accentColor = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(dimens.spacerMedium))
            }
            if (page.comparativeText != null) {
                EnterAnimation(delay = StoryTiming.ContentSub) {
                    StoryChip(
                        text = page.comparativeText,
                        accentColor = TempoAccentBright
                    )
                }
            }
        }
    }
}

// ── Listening Streak ────────────────────────────────────────────────

private val StreakOrange = TempoWarning
private val StreakGold = TempoWarningBright

@Composable
fun ListeningStreakPage(page: SpotlightStoryPage.ListeningStreak) {
    val animatedStreak = rememberStoryCountUp(
        targetValue = page.currentStreakDays.toFloat(),
        delayMillis = 400,
        durationMillis = 1800
    )

    // Gentle flame flicker
    val fireTransition = rememberInfiniteTransition(label = "fire")
    val fireScale by fireTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "fireScale"
    )

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_listening_streak_label),
        conversationalText = page.conversationalText,
        background = {
            StoryPulsingGlow(
                color = TempoWarning,
                size = 350.dp,
                minAlpha = 0.12f,
                maxAlpha = 0.30f,
                periodMillis = 1400
            )
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Hero: flame + count-up
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp * dimens.scale)
                            .scale(fireScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        TempoWarning.copy(alpha = 0.5f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = StreakOrange,
                        modifier = Modifier
                            .size(72.dp * dimens.scale)
                            .scale(fireScale)
                    )
                }

                Spacer(modifier = Modifier.height(dimens.spacerSmall))

                Text(
                    text = animatedStreak.toInt().toString(),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = dimens.textDisplay,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = TempoWarning.copy(alpha = 0.5f),
                            offset = Offset(0f, 4f),
                            blurRadius = 20f
                        )
                    ),
                    color = StreakGold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (page.currentStreakDays == 1) {
                        stringResource(R.string.spotlight_day_in_a_row)
                    } else {
                        stringResource(R.string.spotlight_days_in_a_row)
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    letterSpacing = (1f * dimens.scale).sp
                )
            }
        }

        // Stats: best streak + total active days
        EnterAnimation(delay = StoryTiming.Content) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
            ) {
                StoryStatTile(
                    value = page.longestStreakDays.toString(),
                    label = stringResource(R.string.spotlight_best_streak),
                    modifier = Modifier.weight(1f),
                    valueColor = StreakGold,
                    borderColor = StreakGold.copy(alpha = 0.25f)
                )
                StoryStatTile(
                    value = page.totalActiveDays.toString(),
                    label = stringResource(R.string.spotlight_active_days),
                    modifier = Modifier.weight(1f),
                    valueColor = StreakGold,
                    borderColor = StreakGold.copy(alpha = 0.25f)
                )
            }
        }

        if (page.comparativeText != null) {
            EnterAnimation(delay = StoryTiming.ContentSub) {
                StoryChip(
                    text = page.comparativeText,
                    accentColor = StreakGold
                )
            }
        }
    }
}

// ── Listening Clock ─────────────────────────────────────────────────

@Composable
fun ListeningClockPage(page: SpotlightStoryPage.ListeningClock) {
    val isNightOwl = page.listenerType.contains("night", ignoreCase = true) ||
            page.peakHour >= 18 || page.peakHour < 5
    val accentColor = if (isNightOwl) TempoAccent else TempoWarningBright
    val secondaryColor = if (isNightOwl) TempoAccent else TempoWarningBright

    val clockProgress = rememberStoryCountUp(
        targetValue = 1f,
        delayMillis = 300,
        durationMillis = 1200
    )

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_listening_clock_label),
        conversationalText = page.conversationalText
    ) {
        val dimens = LocalSpotlightDimens.current

        // Hero: radial 24-hour clock
        EnterAnimation(delay = StoryTiming.Hero) {
            Box(
                modifier = Modifier.size(dimens.bubbleMain),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outerRadius = size.minDimension / 2f
                    val innerRadius = outerRadius * 0.35f
                    val barMaxHeight = outerRadius - innerRadius

                    // Background track ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.1f),
                        radius = innerRadius + barMaxHeight / 2f,
                        style = Stroke(width = barMaxHeight)
                    )

                    val numBars = 24
                    for (i in 0 until numBars) {
                        val level = (page.hourlyLevels.getOrNull(i) ?: 0) / 100f
                        val animLevel = level * clockProgress
                        val angleDeg = (i.toFloat() / numBars) * 360f - 90f
                        val angleRad = (angleDeg * PI / 180f).toFloat()

                        val barHeight = barMaxHeight * animLevel.coerceAtLeast(0.05f)
                        val startR = innerRadius
                        val endR = startR + barHeight

                        val isPeak = i == page.peakHour
                        val barColor = when {
                            isPeak -> accentColor
                            level > 0.6f -> secondaryColor.copy(alpha = 0.9f)
                            level > 0.3f -> Color.White.copy(alpha = 0.65f)
                            else -> Color.White.copy(alpha = 0.25f)
                        }

                        drawLine(
                            color = barColor,
                            start = Offset(cx + cos(angleRad) * startR, cy + sin(angleRad) * startR),
                            end = Offset(cx + cos(angleRad) * endR, cy + sin(angleRad) * endR),
                            strokeWidth = if (isPeak) 6.dp.toPx() else 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Center readout
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isNightOwl) Icons.Default.Nightlight else Icons.Default.WbSunny,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(28.dp * dimens.scale)
                    )
                    Text(
                        text = page.peakHourLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.spotlight_peak),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Listener type badge
        EnterAnimation(delay = StoryTiming.Content) {
            StoryChip(
                text = page.listenerType,
                accentColor = accentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                borderColor = accentColor.copy(alpha = 0.35f)
            )
        }

        // Clock orientation labels
        EnterAnimation(delay = StoryTiming.ContentSub) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    R.string.spotlight_clock_12am,
                    R.string.spotlight_clock_6am,
                    R.string.spotlight_clock_12pm,
                    R.string.spotlight_clock_6pm
                ).forEach { labelRes ->
                    StoryChip(
                        text = stringResource(labelRes),
                        accentColor = Color.White.copy(alpha = 0.65f),
                        backgroundColor = Color.White.copy(alpha = 0.05f),
                        borderColor = Color.White.copy(alpha = 0.12f)
                    )
                }
            }
        }
    }
}
