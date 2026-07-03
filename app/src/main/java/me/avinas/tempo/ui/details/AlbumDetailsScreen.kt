package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.premiumClickable
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R

@Composable
fun AlbumDetailsScreen(
    albumId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    viewModel: AlbumDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val albumDetails = uiState.albumDetails

    var dominantColor by remember { mutableStateOf(Color(0xFF8B5CF6)) }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = TempoRed
                )
            } else if (albumDetails != null) {
                AlbumDetailsContent(
                    albumDetails = albumDetails,
                    onNavigateBack = onNavigateBack,
                    onNavigateToSong = onNavigateToSong,
                    onPaletteExtracted = { color -> dominantColor = color },
                    dominantColor = dominantColor
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Album not found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumDetailsContent(
    albumDetails: AlbumDetails,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onPaletteExtracted: (Color) -> Unit,
    dominantColor: Color
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Top Bar (matches ArtistDetailsScreen style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.premiumClickable(onClick = onNavigateBack),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            Text(
                text = stringResource(R.string.details_album),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            // Spacer to balance the back button weight
            Spacer(modifier = Modifier.size(48.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Hero Section with Album Art
            item(key = "hero_section") {
                Spacer(modifier = Modifier.height(8.dp))
                AlbumHeroSection(
                    albumDetails = albumDetails,
                    onPaletteExtracted = onPaletteExtracted,
                    dominantColor = dominantColor
                )
            }

            // Stats Grid
            item(key = "stats_grid") {
                Spacer(modifier = Modifier.height(28.dp))
                AlbumStatsGrid(albumDetails = albumDetails)
            }

            // Tracks Section Header
            item(key = "tracks_header") {
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.details_tracks),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${albumDetails.tracks.size} ${if (albumDetails.tracks.size == 1) "song" else "songs"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Track List (single container with dividers)
            item(key = "track_list") {
                AlbumTrackList(
                    tracks = albumDetails.tracks,
                    onNavigateToSong = onNavigateToSong
                )
            }
        }
    }
}

@Composable
fun AlbumHeroSection(
    albumDetails: AlbumDetails,
    onPaletteExtracted: (Color) -> Unit,
    dominantColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Premium glow + album artwork
        Box(contentAlignment = Alignment.Center) {
            // Glow layer (uses extracted dominant color)
            Box(
                modifier = Modifier
                    .size(272.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Album Artwork
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                modifier = Modifier
                    .size(220.dp)
                    .border(
                        2.dp,
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        ),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                val artworkUrl = albumDetails.album.artworkUrl

                if (artworkUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF8B5CF6),
                                        Color(0xFF6D28D9)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    CachedAsyncImage(
                        imageUrl = artworkUrl,
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        targetSizeDp = 220,
                        onSuccess = { state ->
                            val image = state.result.image
                            val bitmap = (image as? BitmapImage)?.bitmap
                            bitmap?.let {
                                Palette.from(it).generate { palette ->
                                    palette?.dominantSwatch?.rgb?.let { color ->
                                        onPaletteExtracted(Color(color))
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Album Title
        Text(
            text = albumDetails.album.title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Artist Name
        Text(
            text = albumDetails.artistName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Metadata cluster: release year + release type
        val year = albumDetails.album.releaseYear
        val releaseType = albumDetails.album.releaseType
        val hasYear = year != null
        val hasType = !releaseType.isNullOrBlank()

        if (hasYear || hasType) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                if (hasYear) {
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE2E8F0)
                    )
                    if (hasType) {
                        Text(
                            text = "  •  ",
                            color = Color(0xFF475569),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (hasType) {
                    Text(
                        text = releaseType!!.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumStatsGrid(albumDetails: AlbumDetails) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stat 1: Total Plays
                AlbumStatCell(
                    icon = Icons.Rounded.MusicNote,
                    iconTint = Color(0xFFEF4444),
                    label = stringResource(R.string.details_total_plays).uppercase(),
                    value = formatCount(albumDetails.totalPlayCount.toLong()),
                    valueColor = Color(0xFFFCA5A5),
                    modifier = Modifier.weight(1f)
                )

                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)

                // Stat 2: Listening Time
                AlbumStatCell(
                    icon = Icons.Rounded.AccessTime,
                    iconTint = Color(0xFF3B82F6),
                    label = stringResource(R.string.details_listening_time).uppercase(),
                    value = formatListeningTime(albumDetails.totalTimeMs),
                    valueColor = Color(0xFF93C5FD),
                    modifier = Modifier.weight(1f)
                )
            }

            AlbumStatDivider(orientation = AlbumStatDividerOrientation.Horizontal)

            // Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stat 3: Tracks
                AlbumStatCell(
                    icon = Icons.Rounded.Album,
                    iconTint = Color(0xFF8B5CF6),
                    label = stringResource(R.string.details_tracks).uppercase(),
                    value = albumDetails.tracks.size.toString(),
                    valueColor = Color(0xFFD8B4FE),
                    modifier = Modifier.weight(1f)
                )

                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)

                // Stat 4: Completion Rate
                AlbumStatCell(
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = Color(0xFFF59E0B),
                    label = stringResource(R.string.details_completion_rate).uppercase(),
                    value = "%.0f%%".format(albumDetails.completionRate),
                    valueColor = Color(0xFFFDBA74),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private enum class AlbumStatDividerOrientation { Horizontal, Vertical }

@Composable
private fun AlbumStatDivider(orientation: AlbumStatDividerOrientation) {
    when (orientation) {
        AlbumStatDividerOrientation.Horizontal -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
        AlbumStatDividerOrientation.Vertical -> Box(
            modifier = Modifier
                .height(60.dp)
                .width(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }
}

@Composable
private fun AlbumStatCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = valueColor
        )
    }
}

@Composable
fun AlbumTrackList(
    tracks: List<TrackWithStats>,
    onNavigateToSong: (Long) -> Unit
) {
    if (tracks.isEmpty()) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White.copy(alpha = 0.03f),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            tracks.forEachIndexed { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSong(track.track.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Track number (accent color)
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = TempoRed,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.Start
                    )

                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${track.playCount} ${if (track.playCount == 1) "play" else "plays"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                            val duration = track.track.duration ?: 0L
                            if (duration > 0) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF475569)
                                )
                                Text(
                                    text = formatDuration(duration),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    // Play icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (index < tracks.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.06f))
                    )
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 1000 / 60
    val seconds = (ms / 1000) % 60
    return "%d:%02d".format(minutes, seconds)
}
