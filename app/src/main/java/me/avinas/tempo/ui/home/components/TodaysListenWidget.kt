package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.*
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.GlassTintTeal
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessBright


@Composable
fun TodaysListenWidget(
    todayOverview: ListeningOverview?,
    topTrack: TopTrack?,
    topArtist: TopArtist?,
    hourlyDistribution: List<HourlyDistribution>,
    onTrackClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasData = todayOverview?.totalPlayCount ?: 0 > 0
    if (!hasData) return

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        accentColor = TempoPrimary,
        accentStrength = 0.06f,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(TempoPrimary.copy(alpha = 0.14f))
                        .border(1.dp, TempoPrimary.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Today,
                        contentDescription = null,
                        tint = TempoPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Today's Listen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = getTodayGreeting(hourlyDistribution),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LiveIndicator()
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TempoSuccessBright,
                        fontSize = 9.sp,
                        letterSpacing = 0.7.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatPill(
                    icon = Icons.Default.Headphones,
                    value = formatListeningTime(todayOverview?.totalListeningTimeMs ?: 0),
                    label = "listening",
                    tint = TempoPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    icon = Icons.Default.MusicNote,
                    value = "${todayOverview?.totalPlayCount ?: 0}",
                    label = "tracks",
                    tint = TempoAccent,
                    modifier = Modifier.weight(1f)
                )
            }

            if (hourlyDistribution.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Hourly activity",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextQuaternary,
                        letterSpacing = 0.9.sp
                    )
                    HourlyActivityChart(hourlyDistribution)
                }
            }

            if (topTrack != null || topArtist != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (topTrack != null) {
                        TodayItemCard(
                            label = "On Repeat",
                            name = topTrack.title,
                            subtitle = topTrack.artist,
                            imageUrl = topTrack.albumArtUrl,
                            isTrack = true,
                            accentColor = TempoPrimary,
                            onClick = onTrackClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (topArtist != null) {
                        TodayItemCard(
                            label = "Top Artist",
                            name = topArtist.artist,
                            subtitle = "${topArtist.playCount} plays",
                            imageUrl = topArtist.imageUrl,
                            isTrack = false,
                            accentColor = TempoInfo,
                            onClick = onArtistClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun LiveIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "LivePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(TempoSuccessBright.copy(alpha = pulseAlpha))
    )
}

@Composable
private fun HourlyActivityChart(hourlyData: List<HourlyDistribution>) {
    val currentHour = java.time.LocalTime.now().hour
    val maxPlays = hourlyData.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    val startHour = 6
    val endHour = currentHour.coerceAtLeast(startHour + 1)
    val hoursToShow = (startHour..endHour).toList()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassTintTeal.copy(alpha = 0.28f))
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        hoursToShow.forEach { hour ->
            val hourData = hourlyData.find { it.hour == hour }
            val playCount = hourData?.playCount ?: 0
            val heightPercent = (playCount.toFloat() / maxPlays).coerceIn(0.08f, 1f)
            val isCurrentHour = hour == currentHour

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(heightPercent)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(
                        when {
                            isCurrentHour -> TempoPrimary
                            playCount == 0 -> Color.White.copy(alpha = 0.09f)
                            else -> TempoPrimary.copy(alpha = 0.52f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun TodayItemCard(
    label: String,
    name: String,
    subtitle: String,
    imageUrl: String?,
    isTrack: Boolean,
    accentColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val cardModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Box(
        modifier = cardModifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            CachedAsyncImage(
                imageUrl = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(if (isTrack) RoundedCornerShape(10.dp) else CircleShape)
                    .background(Color.White.copy(alpha = 0.05f)),
                contentScale = ContentScale.Crop,
                placeholder = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(if (isTrack) RoundedCornerShape(10.dp) else CircleShape)
                            .background(accentColor.copy(alpha = 0.12f))
                            .border(1.dp, accentColor.copy(alpha = 0.14f), if (isTrack) RoundedCornerShape(10.dp) else CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isTrack) Icons.Default.MusicNote else Icons.Default.Person,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatListeningTime(ms: Long): String {
    val minutes = ms / 60000
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

private fun getTodayGreeting(hourlyData: List<HourlyDistribution>): String {
    val currentHour = java.time.LocalTime.now().hour
    val currentHourData = hourlyData.find { it.hour == currentHour }
    return when {
        currentHourData != null && currentHourData.playCount > 0 -> "${currentHourData.playCount} plays this hour"
        currentHour < 6 -> "Late night vibes"
        currentHour < 12 -> "Morning session"
        currentHour < 17 -> "Afternoon flow"
        currentHour < 21 -> "Evening tunes"
        else -> "Night owl mode"
    }
}
