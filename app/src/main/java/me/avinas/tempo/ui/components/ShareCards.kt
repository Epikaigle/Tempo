package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.AudioFeaturesStats
import me.avinas.tempo.data.stats.TrackDetails
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq
import me.avinas.tempo.ui.theme.BronzeLight
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.GoldPrimary
import me.avinas.tempo.ui.theme.SilverLight
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorAlt
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TempoWarningDeep
import me.avinas.tempo.ui.theme.TempoWarningSoft
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * Common background for share cards to ensure brand consistency.
 * When [theme] is provided, the backdrop is rendered via [ShareBackdrop] so the card
 * picks up the theme's structural background (not just colors). Otherwise the classic
 * MIDNIGHT look is used.
 */
@Composable
fun ShareCardBackground(
    imageUrl: String? = null,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    ShareBackdrop(theme = theme, imageUrl = imageUrl, modifier = modifier) {
        content()
        // Minimal Branding at Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.share_brand),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 4.sp
            )
        }
    }
}

/**
 * 9:16 Optimized Share Card for Artists
 */
@Composable
fun ArtistShareCard(
    artistDetails: ArtistDetails,
    percentile: Double? = null,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    ShareCardBackground(
        imageUrl = artistDetails.artist.imageUrl,
        theme = theme,
        modifier = modifier.aspectRatio(9f/16f)
    ) {
        FitToHeight(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Artist Image
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    val imageUrl = artistDetails.artist.imageUrl

                    CachedAsyncImage(
                        imageUrl = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        allowHardware = false,
                        placeholder = {
                            Box(
                                modifier = Modifier.fillMaxSize().background(TextTertiary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                            }
                        }
                    )
                }

                // Artist Name
                Text(
                    text = artistDetails.artist.name,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 32.sp),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 36.sp,
                    maxLines = 2
                )

                // Badges Row (Country + Top 1% Fan)
                if (artistDetails.country != null || artistDetails.personalPlayCount > 50) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (artistDetails.country != null) {
                            GlassCard(
                                shape = RoundedCornerShape(50),
                                backgroundColor = Color.White.copy(alpha = 0.1f),
                                fillMaxWidth = false
                            ) {
                                Text(
                                    text = stringResource(R.string.share_country_format, artistDetails.country),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        if (artistDetails.country != null && artistDetails.personalPlayCount > 50) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        if (artistDetails.personalPlayCount > 50) {
                            val (badgeText, badgeEmoji, badgeColor) = if (percentile != null) {
                                when {
                                    percentile <= 1.0 -> Triple(stringResource(R.string.fan_status_top_1), "👑", GoldPrimary)
                                    percentile <= 5.0 -> Triple(stringResource(R.string.fan_status_top_5), "🌟", TempoWarning)
                                    percentile <= 10.0 -> Triple(stringResource(R.string.fan_status_top_10), "🔥", TempoError)
                                    percentile <= 25.0 -> Triple(stringResource(R.string.fan_status_top_25), "🎧", TempoInfo)
                                    percentile <= 50.0 -> Triple(stringResource(R.string.fan_status_top_50), "🎵", TempoPrimary)
                                    else -> Triple(stringResource(R.string.fan_status_listener), "🎵", TextSecondary)
                                }
                            } else {
                                when {
                                    artistDetails.personalPlayCount > 1000 -> Triple(stringResource(R.string.fan_status_ultimate), "👑", GoldPrimary)
                                    artistDetails.personalPlayCount > 500 -> Triple(stringResource(R.string.fan_status_super), "🌟", TempoWarning)
                                    artistDetails.personalPlayCount > 200 -> Triple(stringResource(R.string.fan_status_big), "🔥", TempoError)
                                    else -> Triple(stringResource(R.string.fan_status_regular), "🎧", TempoInfo)
                                }
                            }
                            GlassCard(
                                shape = RoundedCornerShape(50),
                                backgroundColor = badgeColor.copy(alpha = 0.15f),
                                borderColor = badgeColor.copy(alpha = 0.4f),
                                borderWidth = 1.dp,
                                fillMaxWidth = false
                            ) {
                                Text(
                                    text = "$badgeEmoji $badgeText",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Stats Grid (GlassCard)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_total_time),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.share_min_format, artistDetails.personalTotalTimeMinutes),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_unique_songs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = artistDetails.uniqueTracksPlayed.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }

                // Top Songs Section
                if (artistDetails.topSongs.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                        backgroundColor = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.share_top_songs),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            artistDetails.topSongs.take(3).forEachIndexed { index, song ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = Color.White.copy(alpha = 0.08f),
                                        thickness = 0.5.dp
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(
                                                brush = Brush.linearGradient(
                                                    colors = when (index) {
                                                        0 -> listOf(TempoWarningBright, TempoWarning)
                                                        1 -> listOf(SilverLight, TextSecondary)
                                                        else -> listOf(BronzeLight, TempoWarningDeep)
                                                    }
                                                ),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.share_plays_format, song.playCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 9:16 Optimized Share Card for Songs
 */
@Composable
fun SongShareCard(
    trackDetails: TrackDetails,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    ShareCardBackground(
        imageUrl = trackDetails.track.albumArtUrl,
        theme = theme,
        modifier = modifier.aspectRatio(9f/16f)
    ) {
        FitToHeight(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Album Art with Glow
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    GlassCard(
                        modifier = Modifier.size(280.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        CachedAsyncImage(
                            imageUrl = trackDetails.track.albumArtUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            allowHardware = false,
                            placeholder = {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(TextTertiary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                                }
                            }
                        )
                    }
                }

                // Track Info
                Text(
                    text = trackDetails.track.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 28.sp),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                Text(
                    text = trackDetails.track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = TempoAccent,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Detailed Stats Grid
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play Count
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = TempoErrorAlt,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.share_plays_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    letterSpacing = 2.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trackDetails.playCount.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }

                        // Vertical Divider
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )

                        // Total Time
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = TempoAccentBright,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.share_minutes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    letterSpacing = 2.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trackDetails.totalTimeMinutes.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shareable card for an Album (shown via SharePreviewDialog on the album details screen).
 */
@Composable
fun AlbumShareCard(
    albumDetails: AlbumDetails,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    ShareCardBackground(
        imageUrl = albumDetails.album.artworkUrl,
        theme = theme,
        modifier = modifier.aspectRatio(9f / 16f)
    ) {
        FitToHeight {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Album Artwork
                CachedAsyncImage(
                    imageUrl = albumDetails.album.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )

                // Album Info
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = albumDetails.album.title,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 2
                    )
                    Text(
                        text = albumDetails.artistName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TempoAccent
                    )
                }

                // Release year
                albumDetails.album.releaseYear?.let { year ->
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50.dp)
                    ) {
                        Text(
                            text = year.toString(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Stats Grid: time / plays / completion
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_total_time),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.share_min_format, albumDetails.totalTimeMinutes),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_plays_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = albumDetails.totalPlayCount.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_completion),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(albumDetails.completionRate * 100).roundToInt()}%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                    }
                }

                // Top tracks from the album
                val topTracks = albumDetails.tracks.sortedByDescending { it.playCount }.take(3)
                if (topTracks.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.share_top_tracks),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 2.sp
                            )

                            topTracks.forEachIndexed { index, trackWithStats ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                brush = Brush.linearGradient(
                                                    colors = when (index) {
                                                        0 -> listOf(TempoWarningBright, TempoWarningDeep)
                                                        1 -> listOf(SilverLight, TextSecondary)
                                                        else -> listOf(TempoWarningSoft, Color(0xFFEA580C))
                                                    },
                                                    start = Offset(0f, 0f),
                                                    end = Offset(100f, 100f)
                                                ),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Black,
                                            color = Color.Black
                                        )
                                    }

                                    Text(
                                        text = trackWithStats.track.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )

                                    Text(
                                        text = stringResource(R.string.share_plays_format, trackWithStats.playCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "My Music DNA" — a personality-style share card derived from audio features.
 * Shown via SharePreviewDialog from the home screen.
 */
@Composable
fun VibeShareCard(
    userName: String?,
    periodLabel: String,
    audioFeatures: AudioFeaturesStats,
    backgroundImageUrl: String? = null,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    val persona = stringResource(
        when {
            audioFeatures.averageValence >= 0.6f && audioFeatures.averageEnergy >= 0.6f ->
                R.string.share_dna_persona_firestarter
            audioFeatures.averageValence >= 0.6f && audioFeatures.averageEnergy < 0.4f ->
                R.string.share_dna_persona_dreamer
            audioFeatures.averageValence < 0.4f && audioFeatures.averageEnergy >= 0.6f ->
                R.string.share_dna_persona_storm
            audioFeatures.averageValence < 0.4f && audioFeatures.averageEnergy < 0.4f ->
                R.string.share_dna_persona_midnight
            else -> R.string.share_dna_persona_alchemist
        }
    )

    ShareCardBackground(
        imageUrl = backgroundImageUrl,
        theme = theme,
        modifier = modifier.aspectRatio(9f / 16f)
    ) {
        FitToHeight {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.share_dna_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = userName ?: stringResource(R.string.share_brand),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                // Personality headline
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = persona,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.share_dna_avg_bpm,
                                audioFeatures.averageTempo.roundToInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = TempoAccent,
                            letterSpacing = 1.5.sp
                        )
                    }
                }

                // DNA gauges
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DnaGauge(
                            label = stringResource(R.string.share_dna_energy),
                            value = audioFeatures.averageEnergy,
                            color = TempoError
                        )
                        DnaGauge(
                            label = stringResource(R.string.share_dna_happiness),
                            value = audioFeatures.averageValence,
                            color = TempoWarningBright
                        )
                        DnaGauge(
                            label = stringResource(R.string.share_dna_dance),
                            value = audioFeatures.averageDanceability,
                            color = TempoPrimary
                        )
                        DnaGauge(
                            label = stringResource(R.string.share_dna_acoustic),
                            value = audioFeatures.averageAcousticness,
                            color = Color(0xFF14B8A6)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DnaGauge(label: String, value: Float, color: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp
            )
            Text(
                text = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(color.copy(alpha = 0.6f), color)
                        )
                    )
            )
        }
    }
}
