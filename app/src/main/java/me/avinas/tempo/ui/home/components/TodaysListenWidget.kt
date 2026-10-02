package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.PeriodComparison
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Today's listening summary card showing total listening time, play count,
 * hourly activity equalizer wave, and the top track or artist.
 */
@Composable
fun TodaysListenWidget(
    todayOverview: ListeningOverview?,
    topTrack: TopTrack?,
    topArtist: TopArtist?,
    hourlyDistribution: List<HourlyDistribution>,
    periodComparison: PeriodComparison? = null,
    onTrackClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onMoreInsightsClick: (() -> Unit)? = null,
    onOpenOverview: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val totalPlays = todayOverview?.totalPlayCount ?: 0
    if (totalPlays <= 0) return

    val overviewAction = onOpenOverview ?: onMoreInsightsClick

    val currentDateLabel = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d", Locale.US)).uppercase()
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = overviewAction != null,
                onClickLabel = "Open today's overview"
            ) { overviewAction?.invoke() },
        shape = RoundedCornerShape(22.dp),
        accentColor = TempoPrimary,
        accentStrength = 0.05f,
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            // ─── Upper Zone: Metric & Day Cadence Wave (Side-by-Side) ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Kicker + Headline Time + Volume subtitle
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Kicker line with trend
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "TODAY · $currentDateLabel",
                            style = KickerSmall,
                            color = TempoPrimaryMuted,
                            letterSpacing = 0.7.sp
                        )

                        Text(
                            text = "·",
                            style = CaptionSmall,
                            color = TextQuaternary
                        )

                        DayOverDayTrendInline(
                            periodComparison = periodComparison
                        )
                    }

                    // Headline listening time
                    Text(
                        text = formatListeningTime(todayOverview?.totalListeningTimeMs ?: 0),
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 29.sp,
                        color = TextPrimary
                    )

                    // Volume summary
                    val tracksCount = todayOverview?.uniqueTracksCount ?: totalPlays
                    Text(
                        text = buildString {
                            append("$totalPlays ${if (totalPlays == 1) "play" else "plays"}")
                            if (tracksCount > 0) {
                                append(" · $tracksCount ${if (tracksCount == 1) "track" else "tracks"}")
                            }
                        },
                        style = CaptionSmall,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Right: Compact Day Cadence Equalizer
                CompactDayCadenceEqualizer(
                    hourlyDistribution = hourlyDistribution,
                    modifier = Modifier
                        .width(116.dp)
                        .height(46.dp)
                )
            }

            // ─── Subtle Hairline Divider ───
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                GlassBorderSoft,
                                GlassBorderSoft,
                                Color.Transparent
                            )
                        )
                    )
            )

            // ─── Lower Zone: Unboxed Media Row (Track or Artist) ───
            if (topTrack != null) {
                CompactTrackRow(
                    track = topTrack,
                    onClick = onTrackClick
                )
            } else if (topArtist != null) {
                CompactArtistRow(
                    artist = topArtist,
                    onClick = onArtistClick
                )
            } else {
                CompactRecapFooter(
                    totalPlays = totalPlays,
                    todayOverview = todayOverview,
                    onOpenOverview = overviewAction
                )
            }
        }
    }
}

/**
 * Inline trend delta indicator.
 */
@Composable
private fun DayOverDayTrendInline(
    periodComparison: PeriodComparison?
) {
    if (periodComparison != null) {
        val timeChange = periodComparison.timeChangePercent
        when {
            timeChange > 0.5 -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = TempoPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "+${timeChange.roundToInt()}%",
                        style = CaptionSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TempoPrimary
                    )
                }
            }
            timeChange < -0.5 -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "-${abs(timeChange).roundToInt()}%",
                        style = CaptionSmall,
                        fontWeight = FontWeight.Medium,
                        color = TextTertiary
                    )
                }
            }
            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingFlat,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "even",
                        style = CaptionSmall,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }
            }
        }
    } else {
        Text(
            text = "today",
            style = CaptionSmall,
            color = TextTertiary
        )
    }
}

/**
 * Compact, organic hourly cadence equalizer wave.
 */
@Composable
private fun CompactDayCadenceEqualizer(
    hourlyDistribution: List<HourlyDistribution>,
    modifier: Modifier = Modifier
) {
    val currentHour = remember { LocalTime.now().hour }
    val isReducedMotion = rememberReducedMotion()

    val animProgress = remember { Animatable(if (isReducedMotion) 1f else 0f) }
    LaunchedEffect(hourlyDistribution) {
        if (!isReducedMotion) {
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
            )
        }
    }

    val maxPlays = remember(hourlyDistribution) {
        hourlyDistribution.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    }

    val startHour = remember(hourlyDistribution) {
        val firstActive = hourlyDistribution.filter { it.playCount > 0 }.minOfOrNull { it.hour } ?: 8
        firstActive.coerceAtMost(8).coerceAtLeast(0)
    }

    val endHour = remember(startHour, currentHour, hourlyDistribution) {
        val lastActive = hourlyDistribution.filter { it.playCount > 0 }.maxOfOrNull { it.hour } ?: currentHour
        maxOf(currentHour, lastActive, startHour + 9).coerceAtMost(23)
    }

    val hoursToShow = remember(startHour, endHour) {
        (startHour..endHour).toList()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // The Bars
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hoursToShow.forEach { hour ->
                val hourData = hourlyDistribution.find { it.hour == hour }
                val playCount = hourData?.playCount ?: 0
                val isCurrentHour = hour == currentHour
                val isPeak = playCount > 0 && playCount == maxPlays

                val normalizedHeight = if (playCount > 0) {
                    ((playCount.toFloat() / maxPlays) * animProgress.value).coerceIn(0.15f, 1f)
                } else {
                    0.08f
                }
                val barBrush: Brush = when {
                    isCurrentHour && playCount > 0 -> {
                        Brush.verticalGradient(
                            listOf(
                                TempoAccentBright,
                                TempoPrimary
                            )
                        )
                    }
                    isPeak -> {
                        Brush.verticalGradient(
                            listOf(
                                TempoAccent,
                                TempoPrimary
                            )
                        )
                    }
                    playCount > 0 -> {
                        val alpha = (0.7f + 0.3f * (playCount.toFloat() / maxPlays)).coerceIn(0.6f, 1f)
                        Brush.verticalGradient(
                            listOf(
                                TempoPrimary.copy(alpha = alpha),
                                TempoPrimaryDeep.copy(alpha = alpha * 0.85f)
                            )
                        )
                    }
                    isCurrentHour -> {
                        SolidColor(TempoPrimary.copy(alpha = 0.35f))
                    }
                    else -> {
                        SolidColor(Color.White.copy(alpha = 0.08f))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(normalizedHeight)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                        .background(barBrush)
                )
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Time axis labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatHourLabel(startHour),
                style = CaptionSmall,
                fontSize = 10.sp,
                color = TextQuaternary,
                letterSpacing = 0.2.sp
            )

            Text(
                text = if (endHour == currentHour) "NOW" else formatHourLabel(endHour),
                style = CaptionSmall,
                fontSize = 10.sp,
                fontWeight = if (endHour == currentHour) FontWeight.Bold else FontWeight.Normal,
                color = if (endHour == currentHour) TempoPrimary else TextQuaternary,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Row displaying today's top track with album art and play metrics.
 */
@Composable
private fun CompactTrackRow(
    track: TopTrack,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.premiumClickable(onClick = onClick, pressedScale = 0.98f)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Album art
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TempoDarkSurfaceElevated)
                .border(0.5.dp, GlassBorderMedium, RoundedCornerShape(10.dp))
        ) {
            CachedAsyncImage(
                imageUrl = track.albumArtUrl,
                contentDescription = "Album art for ${track.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(TempoPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = TempoPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }

        // Title, artist, and plays
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = if (track.playCount > 1) "MOST PLAYED" else "LEAD TRACK",
                    style = KickerSmall,
                    fontSize = 10.5.sp,
                    color = TempoPrimary,
                    letterSpacing = 0.6.sp
                )

                Text(
                    text = "·",
                    style = CaptionSmall,
                    color = TextQuaternary
                )

                Text(
                    text = "${track.playCount} ${if (track.playCount == 1) "play" else "plays"}",
                    style = CaptionSmall,
                    color = TextTertiary
                )

                if (track.totalTimeMs > 0) {
                    Text(
                        text = "· ${formatTrackDuration(track.totalTimeMs)}",
                        style = CaptionSmall,
                        color = TextTertiary
                    )
                }
            }

            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Circular chevron button
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(TempoPrimary.copy(alpha = 0.08f))
                .border(0.5.dp, TempoPrimary.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "View track details",
                tint = TempoPrimary,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/**
 * Row displaying today's top artist with avatar and play metrics.
 */
@Composable
private fun CompactArtistRow(
    artist: TopArtist,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.premiumClickable(onClick = onClick, pressedScale = 0.98f)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Circular artist photo
        CachedAsyncImage(
            imageUrl = artist.imageUrl,
            contentDescription = "Photo of ${artist.artist}",
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TempoDarkSurfaceElevated)
                .border(0.5.dp, GlassBorderMedium, CircleShape),
            contentScale = ContentScale.Crop,
            placeholder = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TempoInfo.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TempoInfoSoft,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        // Artist name and stats
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "TOP ARTIST",
                style = KickerSmall,
                fontSize = 10.5.sp,
                color = TempoInfoSoft,
                letterSpacing = 0.6.sp
            )
            Text(
                text = artist.artist,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"} today",
                style = CaptionSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Circular chevron button
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(TempoInfo.copy(alpha = 0.10f))
                .border(0.5.dp, TempoInfo.copy(alpha = 0.24f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = "View artist details",
                tint = TempoInfoSoft,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

/**
 * Fallback recap row when neither top track nor top artist is present.
 */
@Composable
private fun CompactRecapFooter(
    totalPlays: Int,
    todayOverview: ListeningOverview?,
    onOpenOverview: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = TempoPrimary,
                modifier = Modifier.size(14.dp)
            )
            val artistsCount = todayOverview?.uniqueArtistsCount ?: 0
            Text(
                text = if (artistsCount > 0) "$artistsCount artists active today" else "Today's recap",
                style = CaptionSmall,
                color = TextSecondary
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .clickable(enabled = onOpenOverview != null) { onOpenOverview?.invoke() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "Recap",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = TempoAccent
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TempoAccent,
                modifier = Modifier.size(9.dp)
            )
        }
    }
}

private fun formatListeningTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        hours == 0L && mins == 0L && totalSeconds > 0 -> "${totalSeconds}s"
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0L -> "${seconds}s"
        else -> String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatHourLabel(hour: Int): String {
    val h = when (val mod = hour % 12) {
        0 -> 12
        else -> mod
    }
    val amPm = if (hour >= 12) "PM" else "AM"
    return "$h $amPm"
}
