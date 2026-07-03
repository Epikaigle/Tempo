package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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

private val WarmAmber = Color(0xFFD4956B)
private val SageGreen = Color(0xFF7DAF9C)
private val MutedBlue = Color(0xFF8AABD0)
private val CardSurface = Color(0xFF24211D)
private val BoxSurface = Color(0xFF000000)

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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardSurface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(WarmAmber.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Today,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today's Listen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = getTodayGreeting(hourlyDistribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f)
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
                    color = SageGreen,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatPill(
                icon = Icons.Default.Headphones,
                value = formatListeningTime(todayOverview?.totalListeningTimeMs ?: 0),
                label = "listening",
                color = WarmAmber,
                modifier = Modifier.weight(1f)
            )

            StatPill(
                icon = Icons.Default.MusicNote,
                value = "${todayOverview?.totalPlayCount ?: 0}",
                label = "tracks",
                color = SageGreen,
                modifier = Modifier.weight(1f)
            )
        }

        // Hourly Activity Visualization
        if (hourlyDistribution.isNotEmpty()) {
            HourlyActivityChart(hourlyDistribution)
        }

        // Top Track & Artist Row
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
                        accentColor = WarmAmber,
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
                        accentColor = MutedBlue,
                        onClick = onArtistClick,
                        modifier = Modifier.weight(1f)
                    )
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
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BoxSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun LiveIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "LivePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(SageGreen.copy(alpha = pulseAlpha))
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
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        hoursToShow.forEach { hour ->
            val hourData = hourlyData.find { it.hour == hour }
            val playCount = hourData?.playCount ?: 0
            val heightPercent = (playCount.toFloat() / maxPlays).coerceIn(0.06f, 1f)
            val isCurrentHour = hour == currentHour
            val isFutureHour = hour > currentHour

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(heightPercent)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(
                        when {
                            isFutureHour -> Color.White.copy(alpha = 0.05f)
                            isCurrentHour -> WarmAmber
                            else -> SageGreen.copy(alpha = 0.5f)
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
    val clickableModifier = if (onClick != null) {
        Modifier.then(modifier).clickable(onClick = onClick)
    } else modifier

    Box(
        modifier = clickableModifier
            .clip(RoundedCornerShape(14.dp))
            .background(BoxSurface)
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
                            .background(accentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isTrack) Icons.Default.MusicNote else Icons.Default.Person,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = accentColor
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
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
