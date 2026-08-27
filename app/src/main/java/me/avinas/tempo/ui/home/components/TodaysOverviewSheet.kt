package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.PeriodComparison
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ArtAtmosphereLayer
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

/* Rank medal accents — shared by every ranked row on the overview. */
private val RankGold = Color(0xFFFFD479)
private val RankSilver = Color(0xFFC7CCD4)
private val RankBronze = Color(0xFFCD8B5A)

/**
 * Staggered content entrance: each section fades and lifts in a beat after
 * the previous one once the sheet has settled.
 */
@Composable
private fun EntranceReveal(index: Int, content: @Composable () -> Unit) {
    val appear = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(startDelayMs(index))
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 30f
        }
    ) {
        content()
    }
}

private fun startDelayMs(index: Int): Long = (60L + index * 55L).coerceAtMost(360L)

/**
 * Full-screen "Today's listening" overview presented as an overlay on top of the
 * Home feed. Opened by tapping the Today's Listen widget or its "More insights"
 * link. Slides up over a dimmed, art-tinted backdrop; sections cascade in.
 * [onDismiss] fires after the exit animation completes.
 */
@Composable
fun TodaysOverviewOverlay(
    overview: ListeningOverview?,
    topTracks: List<TopTrack>,
    topArtists: List<TopArtist>,
    hourlyDistribution: List<HourlyDistribution>,
    periodComparison: PeriodComparison?,
    onDismiss: () -> Unit,
    onViewAllStats: () -> Unit,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Sheet progress: 0f = off-screen, 1f = settled open.
    val progress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(isClosing) {
        if (isClosing) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
            onDismiss()
        }
    }

    // Dialog ensures the overview sheet renders above app navigation chrome
    Dialog(
        onDismissRequest = { if (!isClosing) isClosing = true },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
    Box(modifier = modifier.fillMaxSize()) {
        // Backdrop scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(TempoDarkBackground.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (!isClosing) isClosing = true }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - progress.value) * size.height
                    alpha = progress.value
                }
                .background(TempoDarkBackground)
        ) {
            // Ambient album art background layer
            ArtAtmosphereLayer(
                artUrl = topTracks.firstOrNull()?.albumArtUrl
                    ?: topArtists.firstOrNull()?.imageUrl,
                tint = TempoPrimaryDeep
            )

            // Top and bottom gradients for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            listOf(TempoDarkBackground.copy(alpha = 0.82f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.22f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, TempoDarkBackground.copy(alpha = 0.86f))
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxSize()) {
                TodaysOverviewHeader(onClose = { if (!isClosing) isClosing = true })

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    var sectionIndex = 0

                    if (overview != null) {
                        EntranceReveal(sectionIndex++) {
                            HeroListeningCard(overview = overview, periodComparison = periodComparison)
                        }
                        EntranceReveal(sectionIndex++) {
                            MetricTiles(
                                tracks = overview.uniqueTracksCount,
                                artists = overview.uniqueArtistsCount,
                                albums = overview.uniqueAlbumsCount,
                                avgSessionMs = overview.averageSessionDurationMs
                            )
                        }
                    }

                    if (hourlyDistribution.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            PeakHoursCard(hourlyDistribution = hourlyDistribution)
                        }
                    }

                    if (topTracks.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            TopTracksSection(
                                tracks = topTracks,
                                onTrackClick = { track -> onNavigateToTrack(track.trackId) }
                            )
                        }
                    }

                    if (topArtists.isNotEmpty()) {
                        EntranceReveal(sectionIndex++) {
                            TopArtistsSection(
                                artists = topArtists,
                                onArtistClick = { artist ->
                                    val id = artist.artistId
                                    if (id != null && id > 0) {
                                        onNavigateToArtist("id:$id")
                                    } else {
                                        onNavigateToArtist(artist.artist)
                                    }
                                }
                            )
                        }
                    }

                    EntranceReveal(sectionIndex++) {
                        FooterActions(
                            onClose = { if (!isClosing) isClosing = true },
                            onViewAllStats = { if (!isClosing) onViewAllStats() }
                        )
                    }
                }
            }
        }
    }
    }
}

/* ---------------------------------- Header ---------------------------------- */

@Composable
private fun TodaysOverviewHeader(onClose: () -> Unit) {
    val dateLabel = remember {
        LocalDate.now()
            .format(DateTimeFormatter.ofPattern("MMMM d", Locale.US))
            .uppercase()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TODAY · $dateLabel",
                style = KickerSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = TempoAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your listening",
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                color = TextPrimary
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(GlassFrostSoft)
                .border(0.5.dp, GlassBorderSoft, CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close today's overview",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/* --------------------------------- Hero card -------------------------------- */

@Composable
private fun HeroListeningCard(
    overview: ListeningOverview,
    periodComparison: PeriodComparison?
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        accentColor = TempoPrimary,
        accentStrength = 0.06f,
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(TempoPrimary.copy(alpha = 0.13f))
                            .border(0.75.dp, TempoPrimary.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "TOTAL LISTENING TIME",
                        style = KickerSmall,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                        color = TextTertiary
                    )
                }

                DayOverDayChip(periodComparison = periodComparison)
            }

            Text(
                text = formatHeroTime(overview.totalListeningTimeMs),
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                letterSpacing = (-0.8).sp,
                color = TextPrimary,
                maxLines = 1,
                softWrap = false
            )

            Text(
                text = buildString {
                    append("${overview.totalPlayCount} ${if (overview.totalPlayCount == 1) "play" else "plays"}")
                    if (overview.uniqueTracksCount > 0) {
                        append(" · ${overview.uniqueTracksCount} ${if (overview.uniqueTracksCount == 1) "track" else "tracks"}")
                    }
                    if (overview.uniqueArtistsCount > 0) {
                        append(" · ${overview.uniqueArtistsCount} ${if (overview.uniqueArtistsCount == 1) "artist" else "artists"}")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.5.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            TodayVsYesterdayBar(periodComparison = periodComparison)
        }
    }
}

/** Two-tone bar comparing today's listening time against yesterday's. */
@Composable
private fun TodayVsYesterdayBar(periodComparison: PeriodComparison?) {
    if (periodComparison == null) return
    val currentMs = periodComparison.currentPeriodTimeMs.coerceAtLeast(0L)
    val previousMs = periodComparison.previousPeriodTimeMs.coerceAtLeast(0L)
    val totalMs = currentMs + previousMs
    if (totalMs <= 0L) return

    // Fraction-based width clamped to 4–96% to avoid weight(0f) crashes when either period is zero.
    val todayFraction = (currentMs.toFloat() / totalMs.toFloat()).coerceIn(0.04f, 0.96f)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(todayFraction)
                    .background(TempoPrimary)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "TODAY",
                style = KickerSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                color = TempoAccent.copy(alpha = 0.85f)
            )
            Text(
                text = "YESTERDAY",
                style = KickerSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                color = TextQuaternary
            )
        }
    }
}

@Composable
private fun DayOverDayChip(periodComparison: PeriodComparison?) {
    val change = periodComparison?.timeChangePercent ?: return
    when {
        change > 0.5 -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingUp,
            text = "+${change.roundToInt()}% vs yesterday",
            color = TempoPrimary
        )
        change < -0.5 -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingDown,
            text = "-${abs(change.roundToInt())}% vs yesterday",
            color = TextSecondary
        )
        else -> TrendChip(
            iconVector = Icons.AutoMirrored.Rounded.TrendingFlat,
            text = "Same as yesterday",
            color = TextSecondary
        )
    }
}

@Composable
private fun TrendChip(iconVector: ImageVector, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(100.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

/* ------------------------------- Metric tiles ------------------------------- */

/** 2×2 grid of summary stat tiles. */
@Composable
private fun MetricTiles(tracks: Int, artists: Int, albums: Int, avgSessionMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            listOf(
                MetricTileData("TRACKS", "$tracks", Icons.Rounded.MusicNote, TempoPrimary),
                MetricTileData("ARTISTS", "$artists", Icons.Filled.Person, TempoInfo)
            ),
            listOf(
                MetricTileData("ALBUMS", "$albums", Icons.Rounded.Album, TempoCyan),
                MetricTileData(
                    "AVG SESSION",
                    formatShortDuration(avgSessionMs),
                    Icons.Rounded.Timer,
                    GoldPrimary
                )
            )
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { data ->
                    MetricTile(data = data, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class MetricTileData(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val accent: Color
)

@Composable
private fun MetricTile(data: MetricTileData, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(GlassFrostSoft)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(18.dp))
            .padding(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(data.accent.copy(alpha = 0.13f))
                    .border(0.5.dp, data.accent.copy(alpha = 0.24f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = data.icon,
                    contentDescription = null,
                    tint = data.accent,
                    modifier = Modifier.size(14.dp)
                )
            }

            Text(
                text = data.value,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 23.sp,
                color = TextPrimary,
                maxLines = 1,
                softWrap = false
            )

            Text(
                text = data.label,
                style = KickerSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = data.accent.copy(alpha = 0.85f)
            )
        }
    }
}

/* ------------------------------- Peak hours --------------------------------- */

@Composable
private fun PeakHoursCard(hourlyDistribution: List<HourlyDistribution>) {
    val peak = remember(hourlyDistribution) {
        hourlyDistribution.filter { it.playCount > 0 }.maxByOrNull { it.playCount }
    }
    val peakCaption = peak?.let { "${formatHourLabel(it.hour)} · ${it.playCount} ${if (it.playCount == 1) "play" else "plays"}" }

    SectionCard(title = "PEAK HOURS", caption = peakCaption) {
        TodaysHoursChart(hourlyDistribution = hourlyDistribution)
    }
}

/** 24-hour play volume distribution chart with current hour highlight. */
@Composable
private fun TodaysHoursChart(hourlyDistribution: List<HourlyDistribution>) {
    val currentHour = remember { LocalTime.now().hour }
    val maxPlays = remember(hourlyDistribution) {
        hourlyDistribution.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            (0..23).forEach { hour ->
                val playCount = hourlyDistribution.find { it.hour == hour }?.playCount ?: 0
                val heightPercent = (playCount.toFloat() / maxPlays).coerceIn(0.06f, 1f)
                val isCurrentHour = hour == currentHour

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (playCount > 0) Modifier.fillMaxHeight(heightPercent)
                            else Modifier.height(2.dp)
                        )
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(
                            when {
                                isCurrentHour && playCount > 0 -> TempoAccentBright
                                isCurrentHour -> TempoPrimary.copy(alpha = 0.45f)
                                playCount > 0 -> {
                                    val alpha = 0.55f + (0.45f * (playCount.toFloat() / maxPlays))
                                    TempoPrimary.copy(alpha = alpha)
                                }
                                else -> Color.White.copy(alpha = 0.07f)
                            }
                        )
                )
            }
        }

        // One segment per 6-hour block, mirroring the bar row's own weights.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = KickerSmall,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextQuaternary,
                    letterSpacing = 0.4.sp,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/* ------------------------------ Top tracks ---------------------------------- */

@Composable
private fun TopTracksSection(tracks: List<TopTrack>, onTrackClick: (TopTrack) -> Unit) {
    SectionCard(title = "TOP TRACKS") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tracks.forEachIndexed { index, track ->
                OverviewRankRow(
                    rank = index + 1,
                    title = track.title,
                    subtitle = track.artist,
                    meta = "${track.playCount} ${if (track.playCount == 1) "play" else "plays"} · ${formatTrackDuration(track.totalTimeMs)}",
                    artUrl = track.albumArtUrl,
                    onClick = { onTrackClick(track) }
                )
            }
        }
    }
}

/* ------------------------------ Top artists --------------------------------- */

@Composable
private fun TopArtistsSection(artists: List<TopArtist>, onArtistClick: (TopArtist) -> Unit) {
    SectionCard(title = "TOP ARTISTS") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            artists.forEachIndexed { index, artist ->
                OverviewRankRow(
                    rank = index + 1,
                    title = artist.artist,
                    subtitle = "${artist.uniqueTracks} ${if (artist.uniqueTracks == 1) "track" else "tracks"} today",
                    meta = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"} · ${formatLongListeningTime(artist.totalTimeMs)}",
                    artUrl = artist.imageUrl,
                    onClick = { onArtistClick(artist) }
                )
            }
        }
    }
}

@Composable
private fun OverviewRankRow(
    rank: Int,
    title: String,
    subtitle: String,
    meta: String,
    artUrl: String?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 7.dp)
    ) {
        // Medal badge for the podium, quiet numeral for the rest.
        if (rank <= 3) {
            val medalColor = when (rank) {
                1 -> RankGold
                2 -> RankSilver
                else -> RankBronze
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(medalColor.copy(alpha = 0.16f), CircleShape)
                    .border(1.dp, medalColor.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = medalColor
                )
            }
        } else {
            Text(
                text = "$rank",
                style = KickerSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextQuaternary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(TempoPrimary.copy(alpha = 0.10f))
        ) {
            CachedAsyncImage(
                imageUrl = artUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetSizeDp = 100,
                placeholder = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(TempoPrimary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = TempoPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.5.sp,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = TempoAccent.copy(alpha = 0.75f),
                maxLines = 1
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = TextQuaternary,
            modifier = Modifier.size(11.dp)
        )
    }
}

/* ------------------------------ Shared pieces -------------------------------- */

/** Section container with title and optional caption. */
@Composable
private fun SectionCard(
    title: String,
    caption: String? = null,
    content: @Composable () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        variant = GlassCardVariant.Obsidian,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TempoPrimary.copy(alpha = 0.75f))
                    )
                    Text(
                        text = title,
                        style = KickerSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                        color = TextTertiary
                    )
                }

                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TempoAccent.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
            content()
        }
    }
}

/** Action buttons for navigating to full stats or closing the sheet. */
@Composable
private fun FooterActions(onClose: () -> Unit, onViewAllStats: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1.6f)
                .height(52.dp)
                .shadow(10.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(26.dp))
                .background(TempoPrimary)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onViewAllStats
                )
        ) {
            Text(
                text = "Full stats hub",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp,
                color = TextOnAccent
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextOnAccent.copy(alpha = 0.8f),
                modifier = Modifier.size(11.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(GlassFrostSoft)
                .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(26.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Close",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = TextSecondary
            )
        }
    }
}

/* -------------------------------- Formatters --------------------------------- */

/** Hero numeral form: "2h 41m", "41m", "38s". */
private fun formatHeroTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        ms <= 0L -> "0m"
        hours == 0L && mins == 0L -> "${ms / 1000}s"
        hours == 0L -> "${mins}m"
        mins == 0L -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

/** Compact form for tiles and per-artist totals ("38m", "2.4h"). */
private fun formatShortDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0L -> "0m"
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        else -> String.format(Locale.US, "%.1fh", minutes / 60.0)
    }
}

/** Hours-and-minutes form ("2h 41m", "38m") for per-artist time totals. */
private fun formatLongListeningTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return when {
        ms <= 0L -> "0m"
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
    return "$h ${if (hour >= 12) "PM" else "AM"}"
}








