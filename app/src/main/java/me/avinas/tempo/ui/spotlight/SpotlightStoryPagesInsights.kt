package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import java.util.Locale
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorAlt
import me.avinas.tempo.ui.theme.TempoErrorSoft

// =====================================================================
// INSIGHT STORY PAGES — Discovery, Week rhythm, Binge, Vibes, Mood,
// Personality
// =====================================================================

@Composable
fun DiscoveryCountPage(page: SpotlightStoryPage.DiscoveryCount) {
    val artistsCount = rememberStoryCountUp(page.uniqueArtists.toFloat(), delayMillis = 400)
    val tracksCount = rememberStoryCountUp(page.uniqueTracks.toFloat(), delayMillis = 550, durationMillis = 1600)

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_music_universe),
        labelIcon = Icons.Default.Explore,
        conversationalText = page.conversationalText,
        background = {
            StoryPulsingGlow(color = TempoCyan, size = 300.dp)
        }
    ) {
        val dimens = LocalSpotlightDimens.current
        val statFontSize = (dimens.textDisplay.value * 0.45f).sp

        // Two big counters
        EnterAnimation(delay = StoryTiming.Hero) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
            ) {
                StoryStatTile(
                    value = String.format(Locale.US, "%,d", artistsCount.toInt()),
                    label = stringResource(R.string.spotlight_artists),
                    modifier = Modifier.weight(1f),
                    valueColor = TempoAccent,
                    backgroundColor = TempoPrimary.copy(alpha = 0.10f),
                    borderColor = TempoPrimary.copy(alpha = 0.28f),
                    valueFontSize = statFontSize
                )
                StoryStatTile(
                    value = String.format(Locale.US, "%,d", tracksCount.toInt()),
                    label = stringResource(R.string.spotlight_unique_songs),
                    modifier = Modifier.weight(1f),
                    valueColor = TempoCyan,
                    backgroundColor = TempoCyan.copy(alpha = 0.10f),
                    borderColor = TempoCyan.copy(alpha = 0.28f),
                    valueFontSize = statFontSize
                )
            }
        }

        // Callouts: new discoveries + comparison
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (page.newArtistsThisPeriod > 0) {
                EnterAnimation(delay = StoryTiming.Content) {
                    StoryChip(
                        text = stringResource(R.string.spotlight_new_artists_chip, page.newArtistsThisPeriod, page.timeRangeLabel),
                        icon = Icons.Default.Star,
                        accentColor = TempoSuccessDeep
                    )
                }
                Spacer(modifier = Modifier.height(dimens.spacerMedium))
            }
            if (page.comparativeText != null) {
                EnterAnimation(delay = StoryTiming.ContentSub) {
                    StoryChip(
                        text = page.comparativeText,
                        accentColor = TempoCyan
                    )
                }
            }
        }
    }
}

// ── Weekday vs Weekend ──────────────────────────────────────────────

private val WeekdayBlue = TempoInfoSoft
private val WeekdayBlueBright = TempoInfoSoft
private val WeekendAmber = TempoWarning
private val WeekendAmberBright = TempoWarningBright

@Composable
fun WeekdayVsWeekendPage(page: SpotlightStoryPage.WeekdayVsWeekend) {
    val isWeekendDominant = page.dominantSide == "weekend"
    val barFractions = rememberStoryBarFractions(
        targets = List(7) { (page.dailyIntensity.getOrNull(it) ?: 0) / 100f },
        baseDelayMillis = 400,
        stepDelayMillis = 70
    )

    val dayNamesRes = listOf(
        R.string.spotlight_day_mon, R.string.spotlight_day_tue, R.string.spotlight_day_wed,
        R.string.spotlight_day_thu, R.string.spotlight_day_fri, R.string.spotlight_day_sat,
        R.string.spotlight_day_sun
    )
    val isWeekend = listOf(false, false, false, false, false, true, true)

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_week_vs_weekend),
        conversationalText = page.conversationalText
    ) {
        val dimens = LocalSpotlightDimens.current

        // VS cards
        EnterAnimation(delay = StoryTiming.Hero) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
            ) {
                VsStatCard(
                    minutes = page.weekdayAvgMinutes,
                    sideLabel = stringResource(R.string.spotlight_weekdays),
                    nickname = page.weekdayLabel,
                    isDominant = !isWeekendDominant,
                    accent = WeekdayBlue,
                    accentBright = WeekdayBlueBright,
                    modifier = Modifier.weight(1f)
                )
                VsStatCard(
                    minutes = page.weekendAvgMinutes,
                    sideLabel = stringResource(R.string.spotlight_weekends),
                    nickname = page.weekendLabel,
                    isDominant = isWeekendDominant,
                    accent = WeekendAmber,
                    accentBright = WeekendAmberBright,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 7-day rhythm chart
        EnterAnimation(delay = StoryTiming.Content) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                val barMaxHeight = 120.dp * dimens.scale
                dayNamesRes.forEachIndexed { i, dayRes ->
                    val weekend = isWeekend[i]
                    val barColor = when {
                        weekend && isWeekendDominant -> WeekendAmber
                        !weekend && !isWeekendDominant -> WeekdayBlue
                        weekend -> WeekendAmber.copy(alpha = 0.5f)
                        else -> WeekdayBlue.copy(alpha = 0.5f)
                    }
                    val intensity = barFractions[i].coerceAtLeast(0.04f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(barMaxHeight * intensity)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 6.dp * dimens.scale,
                                        topEnd = 6.dp * dimens.scale
                                    )
                                )
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(barColor, barColor.copy(alpha = 0.5f))
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                        Text(
                            text = stringResource(dayRes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                            color = if (weekend) WeekendAmberBright.copy(alpha = 0.9f)
                            else Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun VsStatCard(
    minutes: Int,
    sideLabel: String,
    nickname: String,
    isDominant: Boolean,
    accent: Color,
    accentBright: Color,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier,
        backgroundColor = if (isDominant) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
        borderColor = if (isDominant) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f),
        contentPadding = PaddingValues(12.dp * dimens.scale)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "$minutes",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = dimens.textHeadline,
                    fontWeight = FontWeight.Black
                ),
                color = if (isDominant) accentBright else Color.White.copy(alpha = 0.55f),
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.spotlight_min_per_day),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
            Text(
                text = sideLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = (dimens.textBody.value * 0.85f).sp
                ),
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
            Text(
                text = nickname,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                // Hidden when not dominant — keeps both cards the same height
                color = if (isDominant) accentBright else Color.Transparent,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Binge Session ───────────────────────────────────────────────────

private val BingeRose = TempoErrorAlt
private val BingeRoseDeep = TempoErrorAlt

@Composable
fun BingeSessionPage(page: SpotlightStoryPage.BingeSession) {
    val animatedCount = rememberStoryCountUp(page.bingeCount.toFloat(), delayMillis = 500, durationMillis = 1200)
    val animatedMinutes = rememberStoryCountUp(page.totalBingeMinutes.toFloat(), delayMillis = 650, durationMillis = 1400)

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_longest_binge),
        conversationalText = page.conversationalText,
        background = {
            StoryPulsingGlow(color = BingeRoseDeep, size = 320.dp, minAlpha = 0.10f, maxAlpha = 0.24f)
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Artist reveal
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = page.artistName,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = dimens.textHeadline,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = BingeRoseDeep.copy(alpha = 0.6f),
                            offset = Offset(0f, 4f),
                            blurRadius = 20f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                StoryChip(
                    text = stringResource(R.string.spotlight_marathon_session),
                    accentColor = BingeRose
                )
            }
        }

        // Stats: tracks in a row + total time
        EnterAnimation(delay = StoryTiming.Content) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
            ) {
                val statFontSize = (dimens.textDisplay.value * 0.36f).sp
                StoryStatTile(
                    value = animatedCount.toInt().toString(),
                    label = stringResource(R.string.spotlight_tracks_in_a_row),
                    modifier = Modifier.weight(1f),
                    valueColor = BingeRose,
                    backgroundColor = BingeRoseDeep.copy(alpha = 0.10f),
                    borderColor = BingeRoseDeep.copy(alpha = 0.30f),
                    valueFontSize = statFontSize
                )
                StoryStatTile(
                    value = formatStoryMinutes(animatedMinutes.toInt()),
                    label = stringResource(R.string.spotlight_straight),
                    modifier = Modifier.weight(1f),
                    valueColor = TempoErrorSoft,
                    backgroundColor = BingeRoseDeep.copy(alpha = 0.10f),
                    borderColor = BingeRoseDeep.copy(alpha = 0.30f),
                    valueFontSize = statFontSize
                )
            }
        }
    }
}

// ── Time of Day Vibes ───────────────────────────────────────────────

private class VibePeriod(
    val labelRes: Int,
    val emoji: String,
    val percent: Int,
    val color: Color,
    val gradientEnd: Color
)

@Composable
fun TimeOfDayVibesPage(page: SpotlightStoryPage.TimeOfDayVibes) {
    val periods = listOf(
        VibePeriod(R.string.spotlight_period_morning, "🌅", page.morningPercent, TempoWarningBright, TempoWarning),
        VibePeriod(R.string.spotlight_period_afternoon, "☀️", page.afternoonPercent, TempoSuccessDeep, TempoSuccessDeep),
        VibePeriod(R.string.spotlight_period_evening, "🌆", page.eveningPercent, TempoInfoSoft, TempoInfo),
        VibePeriod(R.string.spotlight_period_night, "🌙", page.nightPercent, TempoAccentBright, TempoPrimary)
    )
    val totalPct = periods.sumOf { it.percent }.coerceAtLeast(1)
    val dominant = periods.firstOrNull {
        stringResource(it.labelRes).equals(page.dominantPeriod, ignoreCase = true)
    } ?: periods.maxByOrNull { it.percent }!!

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_when_you_listen),
        conversationalText = page.conversationalText
    ) {
        val dimens = LocalSpotlightDimens.current

        // Dominant period
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = dominant.emoji,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = (dimens.textDisplay.value * 0.7f).sp
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                Text(
                    text = stringResource(dominant.labelRes),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = dimens.textHeadline,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = dominant.gradientEnd.copy(alpha = 0.5f),
                            offset = Offset(0f, 3f),
                            blurRadius = 12f
                        )
                    ),
                    color = dominant.color,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.spotlight_listener),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // Share-of-day bars
        Column(modifier = Modifier.fillMaxWidth()) {
            periods.forEachIndexed { index, period ->
                EnterAnimation(delay = StoryTiming.stagger(index, base = StoryTiming.Content, step = 100)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp * dimens.scale),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = period.emoji,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = (dimens.textBody.value * 1.1f).sp
                            ),
                            modifier = Modifier.width(32.dp * dimens.scale)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StoryProgressBar(
                            targetFraction = period.percent / totalPct.toFloat(),
                            color = period.color,
                            gradientColors = listOf(period.gradientEnd.copy(alpha = 0.7f), period.color),
                            delayMillis = StoryTiming.Content + index * 100,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${period.percent}%",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                            fontWeight = FontWeight.Bold,
                            color = period.color,
                            modifier = Modifier.width(36.dp * dimens.scale),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

// ── Audio Mood ──────────────────────────────────────────────────────

@Composable
fun AudioMoodPage(page: SpotlightStoryPage.AudioMood) {
    val moodBars = listOf(
        Triple(R.string.spotlight_mood_energy, page.energyPercent, TempoError),
        Triple(R.string.spotlight_mood_positivity, page.valencePercent, TempoWarning),
        Triple(R.string.spotlight_mood_danceability, page.danceabilityPercent, TempoSuccessDeep),
        Triple(R.string.spotlight_mood_acoustic, page.acousticnessPercent, TempoInfoSoft)
    )

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_sound_profile),
        conversationalText = page.conversationalText
    ) {
        val dimens = LocalSpotlightDimens.current

        // Dominant mood
        EnterAnimation(delay = StoryTiming.Hero) {
            Text(
                text = page.dominantMood,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = dimens.textHeadline,
                    fontWeight = FontWeight.Black,
                    shadow = Shadow(
                        color = TempoPrimary.copy(alpha = 0.5f),
                        offset = Offset(0f, 4f),
                        blurRadius = 16f
                    )
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        // Mood bars
        Column(modifier = Modifier.fillMaxWidth()) {
            moodBars.forEachIndexed { index, (labelRes, percent, color) ->
                EnterAnimation(delay = StoryTiming.stagger(index, base = StoryTiming.Content, step = 120)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp * dimens.scale)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                        StoryProgressBar(
                            targetFraction = percent / 100f,
                            color = color,
                            delayMillis = StoryTiming.Content + index * 120,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

// ── Personality ─────────────────────────────────────────────────────

@Composable
fun PersonalityPage(page: SpotlightStoryPage.Personality) {
    val (icon, color) = getPersonalityAssets(page.personalityType)

    val ringTransition = rememberInfiniteTransition(label = "personalityRing")
    val ringAngle by ringTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAngle"
    )
    val iconPulse by ringTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconPulse"
    )

    StoryPageScaffold(
        label = stringResource(R.string.spotlight_listening_personality),
        conversationalText = page.conversationalText,
        background = {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.35f),
                            color.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.maxDimension * 0.8f
                    )
                )
            }
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Cinematic rotating ring + icon
        EnterAnimation(delay = StoryTiming.Hero) {
            Box(
                modifier = Modifier.size((280.dp * dimens.scale).coerceIn(220.dp, 340.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Rotating sweep ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(ringAngle)
                        .background(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    color.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
                // Mask ring center so only the rim shows
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.88f)
                        .background(TempoBackground, CircleShape)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .size(80.dp * dimens.scale)
                        .scale(iconPulse)
                )
            }
        }

        // Reveal
        EnterAnimation(delay = StoryTiming.HeroSub) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = page.personalityType,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = (dimens.textDisplay.value * 1.2f).coerceAtMost(56f).sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (2f * dimens.scale).sp
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(dimens.spacerMedium))

                Text(
                    text = page.description,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = dimens.textBody,
                        lineHeight = (dimens.textBody.value * 1.6f).sp
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun getPersonalityAssets(type: String): Pair<ImageVector, Color> {
    return when (type) {
        stringResource(R.string.personality_party_starter_name) -> Icons.Default.Celebration to TempoErrorAlt
        stringResource(R.string.personality_intense_soul_name) -> Icons.Default.Bolt to TempoError
        stringResource(R.string.personality_peaceful_optimist_name) -> Icons.Default.Spa to TempoSuccessDeep
        stringResource(R.string.personality_deep_thinker_name) -> Icons.Default.SelfImprovement to TempoInfoSoft
        stringResource(R.string.personality_dance_floor_regular_name) -> Icons.Default.MusicNote to TempoWarning
        stringResource(R.string.personality_balanced_enthusiast_name) -> Icons.Default.Balance to TempoAccentBright
        else -> Icons.Default.Psychology to TempoAccent
    }
}
