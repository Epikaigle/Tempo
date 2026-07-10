package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats
import me.avinas.tempo.data.local.entities.Track
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
                    isEditMode = uiState.isEditMode,
                    onNavigateBack = onNavigateBack,
                    onNavigateToSong = onNavigateToSong,
                    onToggleEdit = viewModel::toggleEditMode,
                    onAddClick = viewModel::openAddDialog,
                    onRemoveTrack = viewModel::requestRemove,
                    onPaletteExtracted = { color -> dominantColor = color },
                    dominantColor = dominantColor
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
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
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.error ?: "Album not found",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (uiState.addDialogVisible) {
        AddTrackToAlbumDialog(
            query = uiState.addQuery,
            results = uiState.addResults,
            isSearching = uiState.isSearching,
            artistName = albumDetails?.artistName.orEmpty(),
            onQueryChange = viewModel::onAddQueryChange,
            onTrackSelected = viewModel::addTrackToAlbum,
            onDismiss = viewModel::closeAddDialog
        )
    }

    uiState.pendingRemove?.let { track ->
        RemoveFromAlbumDialog(
            trackTitle = track.track.title,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove
        )
    }
}

@Composable
private fun AlbumDetailsContent(
    albumDetails: AlbumDetails,
    isEditMode: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onToggleEdit: () -> Unit,
    onAddClick: () -> Unit,
    onRemoveTrack: (TrackWithStats) -> Unit,
    onPaletteExtracted: (Color) -> Unit,
    dominantColor: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    ) {
        // Top bar
        item(key = "top_bar") {
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
                    text = stringResource(if (isEditMode) R.string.album_edit_title else R.string.details_album),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                IconButton(
                    onClick = onToggleEdit,
                    modifier = Modifier.premiumClickable(onClick = onToggleEdit),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isEditMode) TempoRed else Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = if (isEditMode) "Done" else "Edit"
                    )
                }
            }
        }

        // Hero section
        item(key = "hero_section") {
            Spacer(modifier = Modifier.height(8.dp))
            AlbumHeroSection(
                albumDetails = albumDetails,
                dominantColor = dominantColor,
                onPaletteExtracted = onPaletteExtracted
            )
        }

        // Stats grid
        item(key = "stats_grid") {
            Spacer(modifier = Modifier.height(28.dp))
            AlbumStatsGrid(albumDetails = albumDetails)
        }

        // Tracks header
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${albumDetails.tracks.size} ${if (albumDetails.tracks.size == 1) "song" else "songs"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (isEditMode) {
                        AddSongPill(onClick = onAddClick)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Track list
        item(key = "track_list") {
            AlbumTrackList(
                tracks = albumDetails.tracks,
                onNavigateToSong = onNavigateToSong,
                isEditMode = isEditMode,
                onRemoveTrack = onRemoveTrack
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AlbumHeroSection(
    albumDetails: AlbumDetails,
    dominantColor: Color,
    onPaletteExtracted: (Color) -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(272.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            dominantColor.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
        Card(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp)
        ) {
            val artworkUrl = albumDetails.album.artworkUrl
            if (artworkUrl.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
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
                        contentDescription = "Album",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            } else {
                CachedAsyncImage(
                    imageUrl = artworkUrl,
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        val bitmap = (state.result.image as? BitmapImage)?.bitmap
                        if (bitmap != null) {
                            val palette = Palette.from(bitmap).generate()
                            val dominantSwatch = palette.dominantSwatch
                            if (dominantSwatch != null) {
                                onPaletteExtracted(Color(dominantSwatch.rgb))
                            }
                        }
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = albumDetails.album.title,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = albumDetails.artistName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.8f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    val year = albumDetails.album.releaseYear
    val releaseType = albumDetails.album.releaseType
    if (year != null || releaseType != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (year != null) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            if (year != null && releaseType != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (releaseType != null) {
                Text(
                    text = releaseType.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun AlbumStatsGrid(albumDetails: AlbumDetails) {
    val completionRate = albumDetails.completionRate
    val totalPlayCount = albumDetails.totalPlayCount
    val totalTimeMs = albumDetails.totalTimeMs
    val tracks = albumDetails.tracks

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                AlbumStatCell(
                    icon = Icons.Rounded.MusicNote,
                    iconTint = Color(0xFFEF4444),
                    label = stringResource(R.string.details_total_plays),
                    value = formatCount(totalPlayCount.toLong()),
                    valueColor = Color(0xFFFCA5A5),
                    modifier = Modifier.weight(1f)
                )
                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)
                AlbumStatCell(
                    icon = Icons.Rounded.AccessTime,
                    iconTint = Color(0xFF3B82F6),
                    label = stringResource(R.string.details_listening_time),
                    value = formatListeningTime(totalTimeMs),
                    valueColor = Color(0xFF93C5FD),
                    modifier = Modifier.weight(1f)
                )
            }
            AlbumStatDivider(orientation = AlbumStatDividerOrientation.Horizontal)
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                AlbumStatCell(
                    icon = Icons.Rounded.Album,
                    iconTint = Color(0xFF8B5CF6),
                    label = stringResource(R.string.details_tracks),
                    value = tracks.size.toString(),
                    valueColor = Color(0xFFD8B4FE),
                    modifier = Modifier.weight(1f)
                )
                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)
                AlbumStatCell(
                    icon = Icons.Rounded.CheckCircle,
                    iconTint = Color(0xFFF59E0B),
                    label = stringResource(R.string.details_completion_rate),
                    value = "%.0f%%".format(completionRate),
                    valueColor = Color(0xFFFDBA74),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

enum class AlbumStatDividerOrientation {
    Horizontal,
    Vertical
}

@Composable
private fun AlbumStatDivider(orientation: AlbumStatDividerOrientation) {
    when (orientation) {
        AlbumStatDividerOrientation.Horizontal -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }

        AlbumStatDividerOrientation.Vertical -> {
            Box(
                modifier = Modifier
                    .height(60.dp)
                    .width(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
        }
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
private fun AddSongPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(TempoRed)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.album_add_song),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AlbumTrackList(
    tracks: List<TrackWithStats>,
    onNavigateToSong: (Long) -> Unit,
    isEditMode: Boolean = false,
    onRemoveTrack: ((TrackWithStats) -> Unit)? = null
) {
    if (tracks.isEmpty()) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White.copy(alpha = 0.03f),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            tracks.forEachIndexed { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isEditMode) Modifier
                            else Modifier.clickable { onNavigateToSong(track.track.id) }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = TempoRed,
                        modifier = Modifier.width(32.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
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
                            val duration = track.track.duration
                            if (duration != null && duration > 0) {
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

                    // Trailing affordance only in edit mode: the remove button. In view mode
                    // the whole row is clickable to open the song, so a play icon would be redundant.
                    if (isEditMode) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(TempoRed.copy(alpha = 0.15f))
                                .clickable { onRemoveTrack?.invoke(track) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove from album",
                                tint = TempoRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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

@Composable
private fun AddTrackToAlbumDialog(
    query: String,
    results: List<Track>,
    isSearching: Boolean,
    artistName: String,
    onQueryChange: (String) -> Unit,
    onTrackSelected: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.album_add_song_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (artistName.isNotEmpty())
                        stringResource(R.string.album_add_song_desc, artistName)
                    else stringResource(R.string.album_add_song_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.album_add_song_search),
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = TempoRed,
                        focusedBorderColor = TempoRed,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                when {
                    isSearching -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = TempoRed)
                        }
                    }

                    results.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.album_no_candidates),
                                color = Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(items = results, key = { it.id }) { track ->
                                TrackSearchItem(track = track) { onTrackSelected(track) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveFromAlbumDialog(
    trackTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.album_remove_title),
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.album_remove_msg, trackTitle),
                    color = Color.White.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.album_remove_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.common_remove),
                    color = TempoRed,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = Color.White
                )
            }
        },
        containerColor = Color(0xFF1E293B),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
