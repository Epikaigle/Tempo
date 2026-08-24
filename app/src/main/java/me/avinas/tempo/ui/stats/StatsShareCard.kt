package me.avinas.tempo.ui.stats

import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.components.ShareThemePalette
import me.avinas.tempo.ui.components.ShareThemeDecorations
import me.avinas.tempo.ui.components.contrastingText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopArtist
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.FitToHeight
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.details.formatListeningTime

enum class StatsShareCount(val count: Int) {
    TOP_3(3),
    TOP_5(5),
    TOP_10(10)
}

enum class StatsShareLayout { LIST, PODIUM, GRID }

// Theme system (ShareTheme / ShareThemePalette) lives in
// ui/components/ShareTheme.kt — shared with the song/artist details share
// cards so every share surface offers the same themes.

data class StatsShareConfig(
    val count: StatsShareCount = StatsShareCount.TOP_5,
    val theme: ShareTheme = ShareTheme.MIDNIGHT,
    val layout: StatsShareLayout = StatsShareLayout.LIST,
    val showSummary: Boolean = true
)

data class StatsItemInfo(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val plays: Int,
    val timeMs: Long
)

fun statsItemInfo(item: Any): StatsItemInfo? = when (item) {
    is TopTrack -> StatsItemInfo(
        title = item.title,
        subtitle = item.artist,
        imageUrl = item.albumArtUrl,
        plays = item.playCount,
        timeMs = item.totalTimeMs
    )
    is TopArtist -> StatsItemInfo(
        title = item.artist,
        subtitle = "${item.playCount} plays",
        imageUrl = item.imageUrl,
        plays = item.playCount,
        timeMs = item.totalTimeMs
    )
    is TopAlbum -> StatsItemInfo(
        title = item.album,
        subtitle = item.artist,
        imageUrl = item.albumArtUrl,
        plays = item.playCount,
        timeMs = item.totalTimeMs
    )
    else -> null
}

@Composable
fun StatsShareCard(
    tab: StatsTab,
    timeRange: TimeRange,
    items: List<Any>,
    overview: ListeningOverview?,
    config: StatsShareConfig = StatsShareConfig(),
    modifier: Modifier = Modifier
) {
    val palette = config.theme.palette
    val titleRes = when (tab) {
        StatsTab.TOP_SONGS -> R.string.stats_share_top_songs
        StatsTab.TOP_ARTISTS -> R.string.stats_share_top_artists
        StatsTab.TOP_ALBUMS -> R.string.stats_share_top_albums
    }
    val periodRes = when (timeRange) {
        TimeRange.TODAY -> R.string.stats_share_period_today
        TimeRange.THIS_WEEK -> R.string.stats_share_period_this_week
        TimeRange.THIS_MONTH -> R.string.stats_share_period_this_month
        TimeRange.THIS_YEAR -> R.string.stats_share_period_this_year
        TimeRange.ALL_TIME -> R.string.stats_share_period_all_time
    }
    val ranked = remember(items, config.count) {
        items.mapNotNull { statsItemInfo(it) }.take(config.count.count)
    }
    val bgImage = ranked.firstOrNull()?.imageUrl

    StatsShareBackground(imageUrl = bgImage, palette = palette, modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                // Top safe-zone: clears the Instagram/story username + avatar
                // overlay on the upper-left. Bottom padding reserves the TEMPO
                // watermark footer (16dp inset + ~20dp text) so the last list
                // row never crowds it. FitToHeight below scales the whole
                // column uniformly into the remaining height, so every layout
                // (list/podium/grid) and count (3/5/10) keeps its structure.
                .padding(top = 52.dp, bottom = 56.dp)
        ) {
            FitToHeight(modifier = Modifier.fillMaxWidth()) {                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(24.dp)
                                .background(palette.accent, RoundedCornerShape(2.dp))
                        )
                        Column {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                            Text(
                                text = stringResource(periodRes),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.accent
                            )
                        }
                    }

                    if (config.showSummary && overview != null) {
                        SummaryCard(overview = overview, palette = palette)
                    }

                    if (ranked.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.stats_no_stats_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        when (config.layout) {
                            StatsShareLayout.LIST -> ListLayout(ranked, palette, config.count)
                            StatsShareLayout.PODIUM -> PodiumLayout(ranked, palette, config.count)
                            StatsShareLayout.GRID -> GridLayout(ranked, palette, config.count)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsShareBackground(
    imageUrl: String?,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.background(brush = Brush.verticalGradient(palette.gradient))) {
        if (palette.usesArtwork && !imageUrl.isNullOrBlank()) {
            CachedAsyncImage(
                imageUrl = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                allowHardware = false,
                blurRadius = 48.dp
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = Brush.verticalGradient(palette.overlay))
            )
        }
        // Theme-specific backdrop decoration (glow orbs, aurora bands, rays…)
        ShareThemeDecorations(palette = palette)
        content()
        // Branding footer
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TEMPO",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = palette.branding,
                letterSpacing = 4.sp
            )
        }
    }
}

@Composable
private fun SummaryCard(overview: ListeningOverview, palette: ShareThemePalette) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = palette.surface,
        shape = palette.cardShape,
        borderColor = palette.accent.copy(alpha = 0.25f),
        borderWidth = 1.dp,
        contentPadding = PaddingValues(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_time),
                value = formatListeningTime(overview.totalListeningTimeMs),
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            SummaryDivider(palette)
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_plays),
                value = overview.totalPlayCount.toString(),
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            SummaryDivider(palette)
            SummaryStat(
                label = stringResource(R.string.stats_share_stat_unique),
                value = overview.uniqueTracksCount.toString(),
                palette = palette,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, palette: ShareThemePalette, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            letterSpacing = 1.5.sp
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SummaryDivider(palette: ShareThemePalette) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .width(1.dp)
            .background(palette.divider)
    )
}

// Rank-badge/pedestal text contrast is provided by
// ShareThemePalette.contrastingText() in ui/components/ShareTheme.kt.

@Composable
private fun RankBadge(rank: Int, size: Dp, palette: ShareThemePalette) {
    val colors = when (rank) {
        1 -> listOf(palette.rank1Tint, palette.rank1Tint)
        2 -> listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8))
        3 -> listOf(Color(0xFFCD7F32), Color(0xFFB45309))
        else -> listOf(palette.accent.copy(alpha = 0.55f), palette.accent.copy(alpha = 0.25f))
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(brush = Brush.linearGradient(colors), palette.badgeShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = palette.contrastingText(colors.first()),
            fontSize = (size.value * 0.5f).sp
        )
    }
}

@Composable
private fun ItemThumbnail(
    size: Dp,
    imageUrl: String?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .border(borderWidth, borderColor, shape)
    ) {
        CachedAsyncImage(
            imageUrl = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            allowHardware = false,
            placeholder = {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                }
            }
        )
    }
}

@Composable
private fun ListLayout(items: List<StatsItemInfo>, palette: ShareThemePalette, count: StatsShareCount) {
    val showHero = count != StatsShareCount.TOP_10
    val rowHeight = when (count) {
        StatsShareCount.TOP_3 -> 46.dp
        StatsShareCount.TOP_5 -> 44.dp
        StatsShareCount.TOP_10 -> 36.dp
    }
    val thumbSize = when (count) {
        StatsShareCount.TOP_3 -> 38.dp
        StatsShareCount.TOP_5 -> 36.dp
        StatsShareCount.TOP_10 -> 30.dp
    }
    val showSubtitle = count != StatsShareCount.TOP_10

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHero) {
            val hero = items.first()
            Row(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ItemThumbnail(
                    size = 56.dp,
                    imageUrl = hero.imageUrl,
                    shape = CircleShape,
                    borderColor = palette.rank1Tint,
                    borderWidth = 2.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stats_share_rank_1),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.rank1Tint,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = hero.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showSubtitle) {
                        Text(
                            text = hero.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = formatListeningTime(hero.timeMs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent
                )
            }
            val rest = items.drop(1)
            if (rest.isNotEmpty()) {
                HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            }
            rest.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 2,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = showSubtitle,
                    palette = palette
                )
            }
        } else {
            items.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 1,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = showSubtitle,
                    palette = palette
                )
            }
        }
    }
}

@Composable
private fun CompactRow(
    rank: Int,
    info: StatsItemInfo,
    rowHeight: Dp,
    thumbSize: Dp,
    showSubtitle: Boolean,
    palette: ShareThemePalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RankBadge(rank = rank, size = 20.dp, palette = palette)
        ItemThumbnail(size = thumbSize, imageUrl = info.imageUrl, shape = palette.thumbShape)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showSubtitle) {
                Text(
                    text = info.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = formatListeningTime(info.timeMs),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = palette.accent
        )
    }
}

@Composable
private fun PodiumLayout(items: List<StatsItemInfo>, palette: ShareThemePalette, count: StatsShareCount) {
    val topThree = items.take(3)
    val rest = items.drop(3)
    val rowHeight = when (count) {
        StatsShareCount.TOP_3 -> 40.dp
        StatsShareCount.TOP_5 -> 38.dp
        StatsShareCount.TOP_10 -> 30.dp
    }
    val thumbSize = when (count) {
        StatsShareCount.TOP_3 -> 30.dp
        StatsShareCount.TOP_5 -> 28.dp
        StatsShareCount.TOP_10 -> 24.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (topThree.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(190.dp)) {
                // Glow behind the winner
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 4.dp)
                        .size(180.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(palette.rank1Tint.copy(alpha = 0.3f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val steps = mutableListOf<PodiumStepData>()
                    if (topThree.size > 1) {
                        steps.add(PodiumStepData(topThree[1], 2, 50.dp, 48.dp, Color(0xFFE2E8F0)))
                    }
                    steps.add(PodiumStepData(topThree[0], 1, 74.dp, 60.dp, palette.rank1Tint, isWinner = true))
                    if (topThree.size > 2) {
                        steps.add(PodiumStepData(topThree[2], 3, 38.dp, 48.dp, Color(0xFFCD7F32)))
                    }
                    steps.forEach { PodiumStep(it, palette) }
                }
            }
        }
        if (rest.isNotEmpty()) {
            HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            rest.forEachIndexed { index, info ->
                CompactRow(
                    rank = index + 4,
                    info = info,
                    rowHeight = rowHeight,
                    thumbSize = thumbSize,
                    showSubtitle = count != StatsShareCount.TOP_10,
                    palette = palette
                )
            }
        }
    }
}

private data class PodiumStepData(
    val info: StatsItemInfo,
    val rank: Int,
    val pedestalHeight: Dp,
    val avatarSize: Dp,
    val tint: Color,
    val isWinner: Boolean = false
)

@Composable
private fun PodiumStep(step: PodiumStepData, palette: ShareThemePalette) {
    Column(
        modifier = Modifier.width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        if (step.isWinner) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = step.tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        ItemThumbnail(
            size = step.avatarSize,
            imageUrl = step.info.imageUrl,
            shape = CircleShape,
            borderColor = step.tint,
            borderWidth = if (step.isWinner) 3.dp else 2.dp
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = step.info.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = formatListeningTime(step.info.timeMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            // Medal tints (esp. silver) are tuned for dark backdrops; fall back to
            // the theme's readable primary text on light themes.
            color = if (palette.isDark) step.tint else palette.textPrimary
        )
        Spacer(modifier = Modifier.height(5.dp))
        // Pedestal
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(step.pedestalHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(step.tint.copy(alpha = 0.9f), step.tint.copy(alpha = 0.25f))
                    ),
                    shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                )
                .border(1.dp, step.tint.copy(alpha = 0.5f), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${step.rank}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = palette.contrastingText(step.tint)
            )
        }
    }
}

@Composable
private fun GridLayout(items: List<StatsItemInfo>, palette: ShareThemePalette, count: StatsShareCount) {
    val featured = count != StatsShareCount.TOP_10
    val featuredHeight = when (count) {
        StatsShareCount.TOP_3 -> 150.dp
        StatsShareCount.TOP_5 -> 120.dp
        StatsShareCount.TOP_10 -> 0.dp
    }
    val cellHeight = when (count) {
        StatsShareCount.TOP_3 -> 92.dp
        StatsShareCount.TOP_5 -> 84.dp
        StatsShareCount.TOP_10 -> 68.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (featured && items.isNotEmpty()) {
            PosterCell(
                info = items.first(),
                rank = 1,
                height = featuredHeight,
                palette = palette,
                large = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        val gridItems = if (featured) items.drop(1) else items
        val startIndex = if (featured) 2 else 1
        gridItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEachIndexed { colIndex, info ->
                    PosterCell(
                        info = info,
                        rank = startIndex + rowIndex * 2 + colIndex,
                        height = cellHeight,
                        palette = palette,
                        large = false,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PosterCell(
    info: StatsItemInfo,
    rank: Int,
    height: Dp,
    palette: ShareThemePalette,
    large: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = palette.cardShape
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(palette.cellBackground)
    ) {
        CachedAsyncImage(
            imageUrl = info.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            allowHardware = false,
            placeholder = {
                Box(
                    modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.textSecondary)
                }
            }
        )
        // Bottom gradient for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        )
        // Rank badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(if (large) 26.dp else 22.dp)
                .background(
                    brush = Brush.linearGradient(listOf(palette.accent, palette.accent.copy(alpha = 0.7f))),
                    palette.badgeShape
                )
                .border(1.dp, Color.Black.copy(alpha = 0.15f), palette.badgeShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = palette.contrastingText(palette.accent),
                fontSize = if (large) 12.sp else 10.sp
            )
        }
        // Title + time
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = info.title,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (large) {
                    Text(
                        text = info.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatListeningTime(info.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
