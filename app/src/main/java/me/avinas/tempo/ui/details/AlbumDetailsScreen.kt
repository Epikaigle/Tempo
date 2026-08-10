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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.stats.AlbumDetails
import me.avinas.tempo.data.stats.TrackWithStats
import me.avinas.tempo.ui.components.AlbumShareCard
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorSoft
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDim
import me.avinas.tempo.ui.theme.TempoSurfaceCard
import me.avinas.tempo.ui.theme.TempoSurfaceChip
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningSoft
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextQuaternary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.premiumClickable

@Composable
fun AlbumDetailsScreen(
    albumId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    viewModel: AlbumDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val albumDetails = uiState.albumDetails
    var dominantColor by remember { mutableStateOf(TempoPrimary) }
    var showShareDialog by remember { mutableStateOf(false) }

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = TempoPrimary
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
                    dominantColor = dominantColor,
                    onShareClick = { showShareDialog = true }
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
                            containerColor = TempoSurfaceChip,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = uiState.error ?: stringResource(R.string.details_album_not_found),
                        color = TextSecondary,
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

    albumDetails?.let { details ->
        if (showShareDialog) {
            SharePreviewDialog(
                onDismiss = { showShareDialog = false },
                themes = ShareTheme.entries,
                contentForTheme = { AlbumShareCard(albumDetails = details, theme = it) }
            )
        }
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
    dominantColor: Color,
    onShareClick: () -> Unit
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
                        containerColor = TempoSurfaceChip,
                        contentColor = TextPrimary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }

                Text(
                    text = stringResource(if (isEditMode) R.string.album_edit_title else R.string.details_album),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                // Only real albums (more than one song) are worth sharing —
                // a 1-track "album" is a single; share it from the song screen.
                if (albumDetails.tracks.size > 1) {
                    IconButton(
                        onClick = onShareClick,
                        modifier = Modifier.premiumClickable(onClick = onShareClick),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = TempoSurfaceChip,
                            contentColor = TextPrimary
                        )
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_content_description)
                        )
                    }
                }

                IconButton(
                    onClick = onToggleEdit,
                    modifier = Modifier.premiumClickable(onClick = onToggleEdit),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isEditMode) TempoPrimary else TempoSurfaceChip,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
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
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${albumDetails.tracks.size} ${if (albumDetails.tracks.size == 1) "song" else "songs"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
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
                    color = Divider,
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
                                    TempoPrimary,
                                    TempoPrimaryDim
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Album",
                        tint = TextSecondary,
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
        color = TextPrimary,
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
        color = TextSecondary,
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
                    color = TextTertiary
                )
            }
            if (year != null && releaseType != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextQuaternary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (releaseType != null) {
                Text(
                    text = releaseType.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
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
                    icon = Icons.Default.MusicNote,
                    iconTint = TempoError,
                    label = stringResource(R.string.details_total_plays),
                    value = formatCount(totalPlayCount.toLong()),
                    valueColor = TempoErrorSoft,
                    modifier = Modifier.weight(1f)
                )
                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)
                AlbumStatCell(
                    icon = Icons.Default.AccessTime,
                    iconTint = TempoInfo,
                    label = stringResource(R.string.details_listening_time),
                    value = formatListeningTime(totalTimeMs),
                    valueColor = TempoInfoSoft,
                    modifier = Modifier.weight(1f)
                )
            }
            AlbumStatDivider(orientation = AlbumStatDividerOrientation.Horizontal)
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                AlbumStatCell(
                    icon = Icons.Default.Album,
                    iconTint = TempoPrimary,
                    label = stringResource(R.string.details_tracks),
                    value = tracks.size.toString(),
                    valueColor = TempoAccent,
                    modifier = Modifier.weight(1f)
                )
                AlbumStatDivider(orientation = AlbumStatDividerOrientation.Vertical)
                AlbumStatCell(
                    icon = Icons.Default.CheckCircle,
                    iconTint = TempoWarning,
                    label = stringResource(R.string.details_completion_rate),
                    value = "%.0f%%".format(completionRate),
                    valueColor = TempoWarningSoft,
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
                    .background(Divider)
            )
        }

        AlbumStatDividerOrientation.Vertical -> {
            Box(
                modifier = Modifier
                    .height(60.dp)
                    .width(1.dp)
                    .background(Divider)
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
                color = TextSecondary,
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
            .background(TempoPrimary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Default.Add,
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
        backgroundColor = TempoSurfaceCard,
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
                        color = TempoPrimary,
                        modifier = Modifier.width(32.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = track.track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
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
                                color = TextSecondary
                            )
                            val duration = track.track.duration
                            if (duration != null && duration > 0) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextQuaternary
                                )
                                Text(
                                    text = formatDuration(duration),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
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
                                .background(TempoPrimary.copy(alpha = 0.15f))
                                .clickable { onRemoveTrack?.invoke(track) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove from album",
                                tint = TempoPrimary,
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
                            .background(Divider)
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
            colors = CardDefaults.cardColors(containerColor = TempoSurfaceDialog)
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
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (artistName.isNotEmpty())
                        stringResource(R.string.album_add_song_desc, artistName)
                    else stringResource(R.string.album_add_song_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.album_add_song_search),
                            color = TextQuaternary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = TextTertiary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TempoPrimary,
                        focusedBorderColor = TempoPrimary,
                        unfocusedBorderColor = Divider
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
                            CircularProgressIndicator(color = TempoPrimary)
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
                                color = TextTertiary,
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
                color = TextPrimary
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.album_remove_msg, trackTitle),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.album_remove_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.common_remove),
                    color = TempoPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = TextPrimary
                )
            }
        },
        containerColor = TempoSurfaceDialog,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
