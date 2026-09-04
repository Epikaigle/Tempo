package me.avinas.tempo.ui.components

import android.graphics.Bitmap
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
import androidx.compose.runtime.remember
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.profile.BadgeEmblem
import me.avinas.tempo.ui.profile.getUniqueBadgeColor
import me.avinas.tempo.ui.profile.getRarityColor
import me.avinas.tempo.ui.profile.getLevelTierAccent
import me.avinas.tempo.ui.profile.getCategoryLabel
import me.avinas.tempo.ui.theme.DisplayFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Common background for share cards. Themed via [ShareThemePalette] so every
 * share surface (stats, song, artist) offers the same theme set and keeps
 * text readable on both dark and light backdrops.
 */
@Composable
fun ShareCardBackground(
    imageUrl: String? = null,
    backdropBitmap: Bitmap? = null,
    customBackdrop: (@Composable BoxScope.() -> Unit)? = null,
    palette: ShareThemePalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(brush = Brush.verticalGradient(palette.gradient))
    ) {
        // Blurred artwork backdrop or single custom canvas acting like image.
        // On ASCII, fallback ascii simple is used.
        // On MINIMUM and DAYLIGHT (warm), they stay untouched as they have no relation with image.
        // On FLUTED_GLASS, the backdropBitmap is refracted via the authentic renderFlutedGlass pass.
        // On GLITCH, the backdrop uses authentic motion blur + glitch effect on the album art.
        if (
            palette.usesArtwork &&
            palette.backdrop != ShareBackdropStyle.ASCII_ARTWORK &&
            palette.backdrop != ShareBackdropStyle.FLUTED_GLASS &&
            palette.backdrop != ShareBackdropStyle.GLITCH_MOTION
        ) {
            if (!imageUrl.isNullOrBlank()) {
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
            } else if (customBackdrop != null) {
                // Single custom canva acting like image!
                customBackdrop()
                // Overlay gradient to ensure high readability and contrast
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = Brush.verticalGradient(palette.overlay))
                )
            }
        } else if (
            palette.backdrop == ShareBackdropStyle.GLITCH_MOTION &&
            customBackdrop != null &&
            backdropBitmap == null &&
            imageUrl.isNullOrBlank()
        ) {
            customBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = Brush.verticalGradient(palette.overlay))
            )
        }

        // Theme-specific backdrop decoration (glow orbs, fluted glass, glitch, ascii, rings, sun wash)
        ShareThemeDecorations(
            palette = palette,
            imageUrl = imageUrl,
            backdropBitmap = backdropBitmap
        )

        content()

        // Minimal Branding at Bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
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
                .padding(top = 26.dp, bottom = 64.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Standing / Fan Status Kicker
                val standingKicker = remember(percentile, artistDetails.personalPlayCount) {
                    resolveArtistStandingKicker(percentile, artistDetails.personalPlayCount)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.surface)
                        .border(1.dp, palette.divider, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = standingKicker,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) palette.accent else palette.textStrong,
                        letterSpacing = 1.5.sp,
                        fontSize = 10.sp
                    )
                }

                // Artist Image with Glow
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        palette.heroGlow.copy(alpha = 0.85f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .clip(CircleShape)
                            .border(3.dp, palette.divider, CircleShape)
                    ) {
                        CachedAsyncImage(
                            imageUrl = artistDetails.artist.imageUrl,
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

                // Artist Name
                Text(
                    text = artistDetails.artist.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = palette.headlineWeight ?: FontWeight.Black,
                        fontSize = 24.sp,
                        lineHeight = 28.sp
                    ),
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
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
                                    percentile == 0.0 -> Triple("#1 Artist", "👑", Color(0xFFFFD700))
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
                                    color = if (palette.isDark) badgeColor else palette.textStrong,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Optional Artist Flex Highlight Pill (streak, discovery date, or genre)
                val artistFlexPill = remember(artistDetails.listeningStreakDays, artistDetails.firstListenedDate, artistDetails.firstDiscovery, artistDetails.topGenres) {
                    resolveArtistFlexPill(
                        listeningStreakDays = artistDetails.listeningStreakDays,
                        firstListenedDate = artistDetails.firstListenedDate,
                        firstDiscoveryTimestamp = artistDetails.firstDiscovery?.firstListenTimestamp,
                        topGenre = artistDetails.topGenres.firstOrNull()
                    )
                }
                if (artistFlexPill != null) {
                    GlassCard(
                        shape = RoundedCornerShape(50),
                        backgroundColor = palette.surfaceStrong,
                        borderColor = palette.divider,
                        borderWidth = 1.dp,
                        fillMaxWidth = false
                    ) {
                        Text(
                            text = artistFlexPill,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                // Stats Grid (GlassCard) - 3 Columns: Plays, Time, Unique Songs
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Col 1: PLAYS
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFFF472B6) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "PLAYS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = artistDetails.personalPlayCount.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
                            )
                        }

                        // Vertical Divider with generous breathing room
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .height(26.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        // Col 2: TIME
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFFC084FC) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "TIME",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            val formattedTime = if (artistDetails.personalTotalTimeMinutes >= 60) {
                                String.format(Locale.US, "%.1fh", artistDetails.personalTotalTimeMinutes / 60.0)
                            } else {
                                "${artistDetails.personalTotalTimeMinutes}m"
                            }
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
                            )
                        }

                        // Vertical Divider with generous breathing room
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .height(26.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        // Col 3: SONGS
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFF38BDF8) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "SONGS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = artistDetails.uniqueTracksPlayed.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
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
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.share_top_songs),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textPrimary,
                                fontWeight = palette.labelWeight ?: FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            artistDetails.topSongs.take(3).forEachIndexed { index, song ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 3.dp),
                                        color = palette.divider,
                                        thickness = 0.5.dp
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
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
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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

                // Mini Equalizer Waveform Bars (Tactile music player aesthetic)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val barHeights = listOf(6.dp, 14.dp, 10.dp, 18.dp, 12.dp, 16.dp, 8.dp, 12.dp, 6.dp)
                    barHeights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(h)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (palette.isDark) palette.accent.copy(alpha = 0.5f)
                                    else palette.textSecondary.copy(alpha = 0.4f)
                                )
                        )
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
    modifier: Modifier = Modifier,
    genre: String? = null,
    mood: String? = null,
    habitualHour: String? = null,
    peakBingeDay: Pair<String, Int>? = null,
    completionRate: Float? = null,
    skipRate: Float? = null,
    releaseYear: Int? = null,
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
                .padding(top = 26.dp, bottom = 64.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Standing / Flex Status Kicker
                val standingKicker = remember(trackDetails.peakRank, trackDetails.playCount, completionRate, skipRate) {
                    resolveSongStandingKicker(
                        peakRank = trackDetails.peakRank,
                        playCount = trackDetails.playCount,
                        completionRate = completionRate,
                        skipRate = skipRate
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.surface)
                        .border(1.dp, palette.divider, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = standingKicker,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) palette.accent else palette.textStrong,
                        letterSpacing = 1.5.sp,
                        fontSize = 10.sp
                    )
                }

                // Hero Album Art with Glow & Favorite Badge
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        palette.heroGlow.copy(alpha = 0.85f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    GlassCard(
                        modifier = Modifier.size(192.dp),
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

                    if (trackDetails.isFavorite) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE11D48))
                                .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Track Info (Title, Artist, Album/Year)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = trackDetails.track.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = palette.headlineWeight ?: FontWeight.Black,
                            fontSize = 22.sp,
                            lineHeight = 26.sp
                        ),
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Text(
                        text = trackDetails.track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (palette.isDark) Color(0xFFE9D5FF) else palette.accent,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )

                    val albumYearText = remember(trackDetails.track.album, releaseYear) {
                        val album = trackDetails.track.album?.takeIf { it.isNotBlank() }
                        when {
                            album != null && releaseYear != null -> "$album • $releaseYear"
                            album != null -> album
                            releaseYear != null -> "Released $releaseYear"
                            else -> null
                        }
                    }
                    if (albumYearText != null) {
                        Text(
                            text = albumYearText,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Col 3 flex stat resolved before card
                val flexStat = remember(
                    trackDetails.firstPlayed,
                    trackDetails.peakRank,
                    completionRate,
                    trackDetails.isFavorite
                ) {
                    resolveSongFlexStat(
                        firstPlayed = trackDetails.firstPlayed,
                        peakRank = trackDetails.peakRank,
                        completionRate = completionRate,
                        isFavorite = trackDetails.isFavorite
                    )
                }

                // 3 Core Flex Stats Grid: Plays | Time | Standout Flex
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Col 1: PLAYS
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFFF472B6) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.share_plays_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = trackDetails.playCount.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
                            )
                        }

                        // Vertical Divider with generous breathing room
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .height(26.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        // Col 2: TIME
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFFC084FC) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "TIME",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            val formattedTime = if (trackDetails.totalTimeMinutes >= 60) {
                                String.format(Locale.US, "%.1fh", trackDetails.totalTimeMinutes / 60.0)
                            } else {
                                "${trackDetails.totalTimeMinutes}m"
                            }
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
                            )
                        }

                        // Vertical Divider with generous breathing room
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .height(26.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        // Col 3: THE FLEX (Discovery Date / Peak Rank / Finish Rate)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = flexStat.icon,
                                    contentDescription = null,
                                    tint = if (palette.isDark) Color(0xFF38BDF8) else palette.accent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = flexStat.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary,
                                    letterSpacing = palette.labelTracking ?: 1.5.sp,
                                    fontSize = 9.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = flexStat.value,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = palette.headlineWeight ?: FontWeight.Black,
                                color = palette.textPrimary,
                                fontSize = 20.sp
                            )
                        }
                    }
                }

                // Standout Flex Highlight Pill (Cherry-picked editorial flex)
                val flexHighlight = remember(peakBingeDay, habitualHour, skipRate, mood, genre) {
                    resolveSongFlexHighlight(
                        peakBingeDay = peakBingeDay,
                        habitualHour = habitualHour,
                        skipRate = skipRate,
                        playCount = trackDetails.playCount,
                        mood = mood,
                        genre = genre
                    )
                }

                if (flexHighlight != null) {
                    GlassCard(
                        shape = RoundedCornerShape(50),
                        backgroundColor = palette.surfaceStrong,
                        borderColor = palette.divider,
                        borderWidth = 1.dp,
                        fillMaxWidth = false
                    ) {
                        Text(
                            text = flexHighlight,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                // Mini Equalizer Waveform Bars (Tactile music player aesthetic)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    val barHeights = listOf(6.dp, 14.dp, 10.dp, 18.dp, 12.dp, 16.dp, 8.dp, 12.dp, 6.dp)
                    barHeights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(h)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (palette.isDark) palette.accent.copy(alpha = 0.5f)
                                    else palette.textSecondary.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
}

internal data class SongFlexStat(
    val label: String,
    val value: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

internal fun resolveSongStandingKicker(
    peakRank: Int?,
    playCount: Int,
    completionRate: Float?,
    skipRate: Float?
): String = when {
    peakRank == 1 -> "👑 #1 ALL-TIME TRACK"
    peakRank != null && peakRank <= 5 -> "🌟 TOP $peakRank IN LIBRARY"
    peakRank != null && peakRank <= 20 -> "🔥 TOP $peakRank ROTATION"
    playCount >= 100 -> "⚡ CENTURY CLUB • 100+ SPINS"
    playCount >= 50 -> "⚡ HEAVY ROTATION"
    skipRate != null && skipRate < 0.05f && playCount >= 15 -> "🎧 ZERO SKIPS • DEEP ROTATION"
    else -> "TEMPO • TRACK HIGHLIGHT"
}

internal fun resolveSongFlexStat(
    firstPlayed: Long?,
    peakRank: Int?,
    completionRate: Float?,
    isFavorite: Boolean
): SongFlexStat = when {
    firstPlayed != null && firstPlayed > 0L -> {
        val sdf = SimpleDateFormat("MMM ''yy", Locale.getDefault())
        SongFlexStat("SINCE", sdf.format(Date(firstPlayed)), Icons.Default.History)
    }
    peakRank != null -> {
        SongFlexStat("PEAK", "#$peakRank", Icons.Default.EmojiEvents)
    }
    completionRate != null -> {
        SongFlexStat("FINISH", "${(completionRate * 100).toInt()}%", Icons.Default.CheckCircle)
    }
    else -> {
        SongFlexStat("STATUS", if (isFavorite) "Fav" else "Spun", Icons.Default.Star)
    }
}

internal fun resolveSongFlexHighlight(
    peakBingeDay: Pair<String, Int>?,
    habitualHour: String?,
    skipRate: Float?,
    playCount: Int,
    mood: String?,
    genre: String?
): String? = when {
    peakBingeDay != null && peakBingeDay.second >= 4 -> {
        "🔥 Binge Record: ${peakBingeDay.second} plays in a day"
    }
    habitualHour != null -> {
        "🌙 Signature ${habitualHour.lowercase()} soundtrack"
    }
    skipRate != null && skipRate < 0.08f && playCount >= 10 -> {
        "✨ 100% Loyalty • Never Skipped"
    }
    mood != null -> {
        "✨ Vibe: $mood"
    }
    genre != null -> {
        "🎧 $genre"
    }
    else -> null
}

internal fun resolveArtistStandingKicker(
    percentile: Double?,
    playCount: Int
): String = when {
    percentile != null && percentile == 0.0 && playCount >= 10 -> "👑 #1 ALL-TIME ARTIST"
    percentile != null && percentile <= 1.0 && playCount >= 25 -> "👑 TOP 1% IN YOUR LIBRARY"
    percentile != null && percentile <= 5.0 && playCount >= 20 -> "🌟 TOP 5% IN YOUR LIBRARY"
    percentile != null && percentile <= 10.0 && playCount >= 15 -> "🔥 TOP 10% IN YOUR LIBRARY"
    playCount >= 500 -> "⚡ CENTURY CLUB • 500+ SPINS"
    playCount >= 200 -> "⚡ HEAVY ROTATION • 200+ SPINS"
    playCount >= 50 -> "🎧 ROTATION REGULAR"
    else -> "TEMPO • ARTIST SPOTLIGHT"
}

internal fun resolveArtistFlexPill(
    listeningStreakDays: Int,
    firstListenedDate: Long?,
    firstDiscoveryTimestamp: Long?,
    topGenre: String? = null
): String? = when {
    listeningStreakDays >= 3 -> {
        "🔥 $listeningStreakDays-day listening streak"
    }
    firstListenedDate != null && firstListenedDate > 0L -> {
        val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        "🎧 Listening since ${sdf.format(Date(firstListenedDate))}"
    }
    firstDiscoveryTimestamp != null && firstDiscoveryTimestamp > 0L -> {
        val sdf = SimpleDateFormat("yyyy", Locale.getDefault())
        "🎧 Discovered in ${sdf.format(Date(firstDiscoveryTimestamp))}"
    }
    !topGenre.isNullOrBlank() -> {
        "🎵 $topGenre"
    }
    else -> null
}

/**
 * 9:16 Optimized Share Card for Badges / Trophy Achievements
 */
@Composable
fun BadgeShareCard(
    badge: Badge,
    userName: String,
    profileImagePath: String? = null,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    val palette = theme.palette
    val intrinsicColor = getUniqueBadgeColor(badge.badgeId)
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity)
    val categoryLabel = remember(badge.category) {
        getCategoryLabel(badge.category).uppercase(Locale.getDefault())
    }
    val xpEarned = GamificationEngine.getBadgeXpContribution(badge.badgeId, badge.stars)
    val earnedDate = remember(badge.earnedAt) {
        if (badge.isEarned && badge.earnedAt > 0L) {
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(badge.earnedAt))
        } else "Recent"
    }

    val backdropBitmap = remember(badge.badgeId) {
        createBadgeBackdropBitmap(badge = badge)
    }

    ShareCardBackground(
        imageUrl = null,
        backdropBitmap = backdropBitmap,
        customBackdrop = {
            BadgeCustomCanvas(badge = badge)
        },
        palette = palette,
        modifier = modifier.aspectRatio(9f / 16f)
    ) {
        FitToHeight(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, bottom = 68.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top Identity Pill: User profile stamp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.surface)
                        .border(1.dp, palette.divider, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(intrinsicColor.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!profileImagePath.isNullOrBlank()) {
                            CachedAsyncImage(
                                imageUrl = profileImagePath,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                allowHardware = false
                            )
                        } else {
                            Text(
                                text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    Text(
                        text = if (badge.isMaxed) "TROPHY MASTERED" else "TROPHY UNLOCKED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) intrinsicColor else palette.textStrong,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Hero Stage: Grand Silhouette Emblem with Layered Blooming Halo
                Box(contentAlignment = Alignment.Center) {
                    // Outer diffuse glow
                    Box(
                        modifier = Modifier
                            .size(176.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    listOf(
                                        intrinsicColor.copy(alpha = if (palette.isDark) 0.42f else 0.20f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // Concentric accent ring
                    Box(
                        modifier = Modifier
                            .size(152.dp)
                            .border(
                                width = 1.dp,
                                color = rarityColor.copy(alpha = if (palette.isDark) 0.35f else 0.18f),
                                shape = CircleShape
                            )
                    )

                    BadgeEmblem(
                        badge = badge,
                        intrinsicColor = intrinsicColor,
                        modifier = Modifier.size(136.dp)
                    )
                }

                // Category Kicker, Title & Description
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "✦ $categoryLabel DISCIPLINE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        ),
                        color = if (palette.isDark) intrinsicColor else palette.textStrong
                    )
                    Text(
                        text = badge.name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = palette.headlineWeight ?: FontWeight.Black,
                            fontSize = 26.sp,
                            letterSpacing = (-0.5).sp
                        ),
                        color = palette.textPrimary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 30.sp,
                        maxLines = 2
                    )
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = palette.textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp,
                        maxLines = 2
                    )
                }

                // Prestige Rarity Badge & 5-Star Constellation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.surfaceStrong)
                        .border(1.dp, rarityColor.copy(alpha = if (palette.isDark) 0.40f else 0.20f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(rarityColor.copy(alpha = if (palette.isDark) 0.22f else 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = rarity.label.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Black,
                            color = if (palette.isDark) rarityColor else palette.textStrong,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (i in 1..5) {
                            val active = i <= badge.stars
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = if (active) (if (badge.isMaxed) intrinsicColor else Color(0xFFFBBF24))
                                       else palette.textSecondary.copy(alpha = 0.22f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // Micro Progress Bar if in-progress
                if (!badge.isMaxed && badge.maxProgress > 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "TIER PROGRESS",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.Bold,
                                color = palette.textSecondary,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "${badge.progress} / ${badge.maxProgress}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette.divider)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(badge.progressFraction)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            listOf(intrinsicColor, rarityColor)
                                        )
                                    )
                            )
                        }
                    }
                }

                // Structured Telemetry Dossier Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = palette.surface,
                    shape = palette.cardShape,
                    borderColor = palette.divider,
                    borderWidth = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "RANK TIER",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (badge.isMaxed) "Master" else "Tier ${badge.stars}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (badge.isMaxed && palette.isDark) intrinsicColor else palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = if (badge.isMaxed) "5 of 5 Stars" else "${badge.stars} of 5 Stars",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "XP VALUE",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (xpEarned > 0) "+$xpEarned XP" else "+${rarity.xpPerStar * badge.stars.coerceAtLeast(1)} XP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (palette.isDark) Color(0xFFFBBF24) else palette.textPrimary
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = "${rarity.label} Tier",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp)
                                .background(palette.divider)
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "ACHIEVED",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = earnedDate,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = palette.textPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = "Archive Record",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 9:16 Optimized Share Card for Level Up Milestones
 */
@Composable
fun MilestoneShareCard(
    level: Int,
    title: String,
    userName: String,
    profileImagePath: String? = null,
    totalXp: Long = 0,
    streak: Int = 0,
    theme: ShareTheme = ShareTheme.MIDNIGHT,
    modifier: Modifier = Modifier
) {
    val palette = theme.palette
    val tierAccent = getLevelTierAccent(level)

    val backdropBitmap = remember(level) {
        createMilestoneBackdropBitmap(level = level)
    }

    ShareCardBackground(
        imageUrl = null,
        backdropBitmap = backdropBitmap,
        customBackdrop = {
            MilestoneCustomCanvas(level = level)
        },
        palette = palette,
        modifier = modifier.aspectRatio(9f / 16f)
    ) {
        FitToHeight(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, bottom = 68.dp, start = 24.dp, end = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Identity Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.surface)
                        .border(1.dp, palette.divider, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(tierAccent.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!profileImagePath.isNullOrBlank()) {
                            CachedAsyncImage(
                                imageUrl = profileImagePath,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                allowHardware = false
                            )
                        } else {
                            Text(
                                text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary
                            )
                        }
                    }
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    Text(
                        text = "LEVEL PROMOTION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (palette.isDark) tierAccent else palette.textStrong,
                        letterSpacing = 1.2.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Hero Level Crest
                Box(contentAlignment = Alignment.Center) {
                    // Outer radial aura
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    listOf(
                                        tierAccent.copy(alpha = if (palette.isDark) 0.38f else 0.20f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // Concentric Ring
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceStrong)
                            .border(3.dp, tierAccent.copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "LEVEL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = tierAccent,
                                letterSpacing = 2.sp
                            )
                            Text(
                                text = "$level",
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontFamily = DisplayFontFamily,
                                    fontSize = 52.sp,
                                    letterSpacing = (-1.5).sp
                                ),
                                fontWeight = FontWeight.Black,
                                color = palette.textPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Listener Title & Description
                Text(
                    text = title.ifBlank { "Dedicated Listener" },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = palette.headlineWeight ?: FontWeight.Black
                    ),
                    color = palette.textPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 32.sp
                )

                Text(
                    text = "A milestone reached on the listening rhythm.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Stats Grid
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
                                text = "TOTAL XP",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(Locale.getDefault(), "%,d", totalXp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
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
                                text = "STREAK",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (streak > 0) "$streak days" else "Active",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
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
                                text = "RANK TIER",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                letterSpacing = palette.labelTracking ?: 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    level >= 100 -> "Mythic"
                                    level >= 75 -> "Rose"
                                    level >= 50 -> "Gold"
                                    level >= 35 -> "Purple"
                                    level >= 20 -> "Electric"
                                    level >= 10 -> "Cyan"
                                    level >= 5 -> "Emerald"
                                    else -> "Studio"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = palette.headlineWeight ?: FontWeight.Bold,
                                color = if (palette.isDark) tierAccent else palette.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
