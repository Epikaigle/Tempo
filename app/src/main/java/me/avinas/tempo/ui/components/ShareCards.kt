package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground

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
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.TrackDetails
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq

/**
 * Common background for share cards. Themed via [ShareThemePalette] so every
 * share surface (stats, song, artist) offers the same theme set and keeps
 * text readable on both dark and light backdrops.
 */
@Composable
fun ShareCardBackground(
    imageUrl: String? = null,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(brush = Brush.verticalGradient(palette.gradient))
    ) {
        // Blurred artwork backdrop — only for themes built on the photo
        if (palette.usesArtwork && !imageUrl.isNullOrBlank()) {
            CachedAsyncImage(
                imageUrl = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                allowHardware = false,
                blurRadius = 48.dp
            )
            // Overlay gradient to ensure high readability and contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = Brush.verticalGradient(palette.overlay))
            )
        }

        // Theme-specific backdrop decoration (glow orbs, aurora bands, rays…)
        ShareThemeDecorations(palette = palette)

        content()

        // Minimal Branding at Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp), // Moved upward (increased from 32dp)
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.share_brand),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = palette.branding,
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
    val palette = theme.palette
    ShareCardBackground(
        imageUrl = artistDetails.artist.imageUrl,
        palette = palette,
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
                        .border(4.dp, palette.divider, CircleShape)
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
                                modifier = Modifier.fillMaxSize().background(palette.cellPlaceholder),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.textSecondary)
                            }
                        }
                    )
                }

                // Artist Name
                Text(
                    text = artistDetails.artist.name,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 32.sp),
                    color = palette.textPrimary,
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
                                backgroundColor = palette.surface,
                                fillMaxWidth = false
                            ) {
                                Text(
                                    text = stringResource(R.string.share_country_format, artistDetails.country),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textPrimary,
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
                                    percentile <= 1.0 -> Triple(stringResource(R.string.fan_status_top_1), "👑", Color(0xFFFFD700))
                                    percentile <= 5.0 -> Triple(stringResource(R.string.fan_status_top_5), "🌟", Color(0xFFF59E0B))
                                    percentile <= 10.0 -> Triple(stringResource(R.string.fan_status_top_10), "🔥", Color(0xFFEF4444))
                                    percentile <= 25.0 -> Triple(stringResource(R.string.fan_status_top_25), "🎧", Color(0xFF3B82F6))
                                    percentile <= 50.0 -> Triple(stringResource(R.string.fan_status_top_50), "🎵", Color(0xFF8B5CF6))
                                    else -> Triple(stringResource(R.string.fan_status_listener), "🎵", Color(0xFF94A3B8))
                                }
                            } else {
                                when {
                                    artistDetails.personalPlayCount > 1000 -> Triple(stringResource(R.string.fan_status_ultimate), "👑", Color(0xFFFFD700))
                                    artistDetails.personalPlayCount > 500 -> Triple(stringResource(R.string.fan_status_super), "🌟", Color(0xFFF59E0B))
                                    artistDetails.personalPlayCount > 200 -> Triple(stringResource(R.string.fan_status_big), "🔥", Color(0xFFEF4444))
                                    else -> Triple(stringResource(R.string.fan_status_regular), "🎧", Color(0xFF3B82F6))
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
                                    // Semantic badge colors are tuned for dark
                                    // backdrops; fall back to the theme's strong
                                    // text on light themes to stay readable.
                                    color = if (palette.isDark) badgeColor else palette.textStrong,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Stats Grid (GlassCard)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape
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
                                color = palette.textSecondary,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.share_min_format, artistDetails.personalTotalTimeMinutes),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.share_unique_songs),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = artistDetails.uniqueTracksPlayed.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                        }
                    }
                }

                // Top Songs Section
                if (artistDetails.topSongs.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, palette.divider, palette.cardShape),
                        backgroundColor = palette.surfaceStrong,
                        shape = palette.cardShape
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.share_top_songs),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            artistDetails.topSongs.take(3).forEachIndexed { index, song ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = palette.divider,
                                        thickness = 0.5.dp
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val badgeColors = when (index) {
                                        0 -> listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
                                        1 -> listOf(Color(0xFFE2E8F0), Color(0xFF94A3B8))
                                        else -> listOf(Color(0xFFCD7F32), Color(0xFFB45309))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(
                                                brush = Brush.linearGradient(colors = badgeColors),
                                                shape = palette.badgeShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.contrastingText(badgeColors.first()),
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.share_plays_format, song.playCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textSecondary
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
    val palette = theme.palette
    ShareCardBackground(
        imageUrl = trackDetails.track.albumArtUrl,
        palette = palette,
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
                                        palette.heroGlow,
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    GlassCard(
                        modifier = Modifier.size(280.dp),
                        shape = palette.cardShape
                    ) {
                        CachedAsyncImage(
                            imageUrl = trackDetails.track.albumArtUrl,
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
                    }
                }

                // Track Info
                Text(
                    text = trackDetails.track.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black, fontSize = 28.sp),
                    color = palette.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                Text(
                    text = trackDetails.track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    // Lavender artist credit is tuned for dark backdrops; use the
                    // theme accent on light themes to stay readable.
                    color = if (palette.isDark) Color(0xFFE9D5FF) else palette.accent,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Detailed Stats Grid
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape
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
                                    tint = if (palette.isDark) Color(0xFFF472B6) else palette.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.share_plays_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = 2.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trackDetails.playCount.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                        }

                        // Vertical Divider
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        // Total Time
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFFC084FC) else palette.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.share_minutes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = 2.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = trackDetails.totalTimeMinutes.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
