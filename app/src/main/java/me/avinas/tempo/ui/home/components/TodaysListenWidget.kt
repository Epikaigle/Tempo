package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val hasData = (todayOverview?.totalPlayCount ?: 0) > 0
    if (!hasData) return

    val currentDateFormatted = remember {
        val today = LocalDate.now()
        val formatted = today.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)).uppercase()
        "TODAY · $formatted"
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = onOpenOverview != null,
                onClickLabel = "Open today's overview"
            ) { onOpenOverview?.invoke() },
        shape = RoundedCornerShape(24.dp),
        accentColor = TempoPrimary,
        accentStrength = 0.05f,
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TempoPrimary.copy(alpha = 0.12f))
                            .border(0.75.dp, TempoPrimary.copy(alpha = 0.22f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Text(
                        text = "Today's listening",
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(GlassFrostSoft)
                        .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(100.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = currentDateFormatted,
                        style = KickerSmall,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "LISTENING TIME",
                        style = KickerSmall,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = TextTertiary,
                        maxLines = 1,
                        softWrap = false
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = formatListeningTime(todayOverview?.totalListeningTimeMs ?: 0),
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )

                    Spacer(modifier = Modifier.height(5.dp))

                    DayOverDayTrend(
                        periodComparison = periodComparison,
                        playCount = todayOverview?.totalPlayCount ?: 0
                    )
                }

                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .background(GlassBorderSoft)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1.25f),
                    verticalArrangement = Arrangement.Center
                ) {
                    HourlyEqualizerChart(hourlyDistribution = hourlyDistribution)
                }
            }

            if (topTrack != null) {
                MostPlayedTrackCard(
                    track = topTrack,
                    onClick = onTrackClick
                )
            } else if (topArtist != null) {
                TopArtistCard(
                    artist = topArtist,
                    onClick = onArtistClick
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(GlassBorderSoft)
                )

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
                        val tracksCount = todayOverview?.uniqueTracksCount ?: todayOverview?.totalPlayCount ?: 0
                        val artistsCount = todayOverview?.uniqueArtistsCount ?: 0
                        Text(
                            text = buildString {
                                append("$tracksCount ${if (tracksCount == 1) "track" else "tracks"}")
                                if (artistsCount > 0) {
                                    append(" · $artistsCount ${if (artistsCount == 1) "artist" else "artists"}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            color = TextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = onMoreInsightsClick != null) {
                                onMoreInsightsClick?.invoke()
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "More insights",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
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
        }
    }
}

@Composable
private fun DayOverDayTrend(
    periodComparison: PeriodComparison?,
    playCount: Int
) {
    if (periodComparison != null) {
        val timeChange = periodComparison.timeChangePercent
        when {
            timeChange > 0.5 -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = TempoPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "+${timeChange.roundToInt()}% vs yesterday",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TempoPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            timeChange < -0.5 -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "-${abs(timeChange).roundToInt()}% vs yesterday",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextTertiary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingFlat,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Same as yesterday",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = TempoPrimary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "$playCount ${if (playCount == 1) "play" else "plays"} today",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun HourlyEqualizerChart(hourlyDistribution: List<HourlyDistribution>) {
    val currentHour = remember { LocalTime.now().hour }
    val maxPlays = remember(hourlyDistribution) {
        hourlyDistribution.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    }

    val startHour = remember(hourlyDistribution) {
        val firstActive = hourlyDistribution.filter { it.playCount > 0 }.minOfOrNull { it.hour } ?: 9
        firstActive.coerceAtMost(9).coerceAtLeast(6)
    }
    val endHour = remember(startHour, currentHour) {
        currentHour.coerceAtLeast(startHour + 8).coerceAtMost(23)
    }
    val hoursToShow = remember(startHour, endHour) {
        (startHour..endHour).toList()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hoursToShow.forEach { hour ->
                val hourData = hourlyDistribution.find { it.hour == hour }
                val playCount = hourData?.playCount ?: 0
                val heightPercent = (playCount.toFloat() / maxPlays).coerceIn(0.10f, 1f)
                val isCurrentHour = hour == currentHour

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (playCount > 0) Modifier.fillMaxHeight(heightPercent)
                            else Modifier.height(2.5.dp)
                        )
                        .clip(RoundedCornerShape(topStart = 2.5.dp, topEnd = 2.5.dp))
                        .background(
                            when {
                                isCurrentHour && playCount > 0 -> TempoAccentBright
                                isCurrentHour -> TempoPrimary.copy(alpha = 0.45f)
                                playCount > 0 -> {
                                    val alpha = 0.60f + (0.40f * (playCount.toFloat() / maxPlays))
                                    TempoPrimary.copy(alpha = alpha)
                                }
                                else -> Color.White.copy(alpha = 0.08f)
                            }
                        )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatHourLabel(startHour),
                style = KickerSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextQuaternary,
                letterSpacing = 0.4.sp
            )

            Text(
                text = formatHourLabel(endHour),
                style = KickerSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextQuaternary,
                letterSpacing = 0.4.sp
            )
        }
    }
}

@Composable
private fun MostPlayedTrackCard(
    track: TopTrack,
    onClick: (() -> Unit)?
) {
    val cardModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .padding(12.dp)
    }

    Box(modifier = cardModifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                CachedAsyncImage(
                    imageUrl = track.albumArtUrl,
                    contentDescription = null,
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
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = TempoPrimary.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                // Concentric vinyl groove circles
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 0.75f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.14f),
                        radius = size.minDimension * 0.32f,
                        center = center,
                        style = stroke
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.44f,
                        center = center,
                        style = stroke
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (track.playCount > 1) "MOST PLAYED" else "TOP TRACK",
                        style = KickerSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = TempoPrimary,
                        letterSpacing = 0.8.sp
                    )

                    Text(
                        text = "•",
                        fontSize = 8.sp,
                        color = TextQuaternary
                    )

                    Text(
                        text = "${track.playCount} ${if (track.playCount == 1) "play" else "plays"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextTertiary
                    )
                }

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.5.sp,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (track.totalTimeMs > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 4.dp, vertical = 1.5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(TempoPrimary)
                            )
                            Text(
                                text = formatTrackDuration(track.totalTimeMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.5.sp,
                                color = TextTertiary
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(TempoPrimary.copy(alpha = 0.10f))
                    .border(0.5.dp, TempoPrimary.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = "View track details",
                    tint = TempoPrimary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun TopArtistCard(
    artist: TopArtist,
    onClick: (() -> Unit)?
) {
    val cardModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .padding(12.dp)
    }

    Box(modifier = cardModifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CachedAsyncImage(
                imageUrl = artist.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f)),
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
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TOP ARTIST",
                    style = KickerSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TempoInfoSoft,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = artist.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"} today",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.5.sp,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(TempoInfo.copy(alpha = 0.14f))
                    .border(0.5.dp, TempoInfo.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = "View artist details",
                    tint = TempoInfoSoft,
                    modifier = Modifier.size(11.dp)
                )
            }
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
