package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ArtistShareCard
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.ShareTheme
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.GoldPrimary
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorSoft
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSuccessDeep
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
import java.text.SimpleDateFormat
import java.util.*
import me.avinas.tempo.ui.theme.SilverLight

@Composable
fun ArtistDetailsScreen(
    artistId: Long? = null,
    artistName: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    viewModel: ArtistDetailsViewModel = hiltViewModel()
) {
    // Load artist by ID or name
    LaunchedEffect(artistId, artistName) {
        if (artistId != null && artistId > 0) {
            viewModel.loadArtistById(artistId)
        } else if (artistName != null) {
            viewModel.loadArtistByName(artistName)
        }
    }
    
    val uiState by viewModel.uiState.collectAsState()
    val artistDetails = uiState.artistDetails

    DeepOceanBackground {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TempoPrimary)
            }
        } else if (artistDetails != null) {
            ArtistDetailsContent(
                artistDetails = artistDetails,
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onNavigateToSong = onNavigateToSong,
                onRefreshImage = { viewModel.refreshArtistImage() },
                onShowRenameDialog = { viewModel.showRenameDialog() },
                onReloadArtist = { viewModel.reloadCurrentArtist() }
            )
            
            // Rename dialog
            if (uiState.showRenameDialog) {
                ArtistRenameDialog(
                    currentName = artistDetails.artist.name,
                    artistId = artistDetails.artist.id,
                    splitArtists = uiState.detectedSplitArtists,
                    isDetecting = uiState.isDetectingSplits,
                    isRenaming = uiState.isRenaming,
                    renameSuccess = uiState.renameSuccess,
                    onDetectSplits = { newName -> viewModel.detectSplitsAndRename(newName) },
                    onConfirmRenameAndMerge = { newName, mergeIds ->
                        viewModel.confirmRenameAndMerge(newName, mergeIds)
                    },
                    onConfirmRenameOnly = { newName -> viewModel.confirmRenameOnly(newName) },
                    onDismiss = { viewModel.dismissRenameDialog() }
                )
            }
        } else {
            // Error state with back button and retry option
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Back button row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                
                // Error content
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.error ?: stringResource(R.string.details_artist_not_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.retry() },
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            Text(stringResource(R.string.common_retry), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistDetailsContent(
    artistDetails: ArtistDetails,
    uiState: ArtistDetailsUiState,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onRefreshImage: () -> Unit,
    onShowRenameDialog: () -> Unit = {},
    onReloadArtist: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Top Bar
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
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            
            Text(
                text = stringResource(R.string.details_artist_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            
            IconButton(
                onClick = { showShareDialog = true },
                modifier = Modifier.premiumClickable(onClick = { showShareDialog = true }),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_content_description))
            }
            
            // More options menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(TempoSurfaceDialog)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MergeType,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.details_merge_with), color = Color.White)
                            }
                        },
                        onClick = {
                            showMenu = false
                            showMergeDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CallSplit,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.details_split_artist), color = Color.White)
                            }
                        },
                        onClick = {
                            showMenu = false
                            showSplitDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Rename Artist", color = Color.White)
                            }
                        },
                        onClick = {
                            showMenu = false
                            onShowRenameDialog()
                        }
                    )
                }
            }
        }


        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "hero_section") {
                Spacer(modifier = Modifier.height(8.dp))
                ArtistHeroSection(
                    artistDetails = artistDetails,
                    onRefreshImage = onRefreshImage,
                    isRefreshingImage = uiState.isRefreshingImage,
                    onShowRenameDialog = onShowRenameDialog
                )
            }

            item(key = "stats_grid") {
                Spacer(modifier = Modifier.height(24.dp))
                ArtistStatsGrid(artistDetails = artistDetails)
            }

            if (artistDetails.peakListeningHour != null) {
                item(key = "peak_hour") {
                    Spacer(modifier = Modifier.height(24.dp))
                    PeakHourCard(
                        peakHour = artistDetails.peakListeningHour,
                        formattedHour = artistDetails.peakHourFormatted
                    )
                }
            }

            item(key = "listening_journey") {
                Spacer(modifier = Modifier.height(24.dp))
                ListeningJourneySection(artistDetails = artistDetails)
            }
            
            // Top Songs Section
            item(key = "top_songs_section") {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.details_top_songs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
                TopSongsPanel(
                    songs = artistDetails.topSongs.take(5),
                    onNavigateToSong = onNavigateToSong
                )
            }
            
            // Top Albums Section
            if (artistDetails.topAlbums.isNotEmpty()) {
                item(key = "top_albums_header") {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.details_top_albums),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                
                item(key = "top_albums_row") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        itemsIndexed(
                            items = artistDetails.topAlbums,
                            key = { index, album -> "album_${index}_${album.album}" },
                            contentType = { _, _ -> "album" }
                        ) { _, album ->
                            TopAlbumCard(album = album)
                        }
                    }
                }
            }
            
            // Mood & Genre Section - temporarily disabled (MoodInsightsSection not implemented)
            /*
            if (artistDetails.moodSummary != null) {
                item(key = "mood_insights") {
                    Spacer(modifier = Modifier.height(32.dp))
                    MoodInsightsSection(moodSummary = artistDetails.moodSummary)
                }
            }
            */
            
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showShareDialog) {
            SharePreviewDialog(
                onDismiss = { showShareDialog = false },
                themes = ShareTheme.entries,
                contentForTheme = { ArtistShareCard(artistDetails = artistDetails, percentile = uiState.artistPercentile, theme = it) }
            )
        }
        
        // Artist Merge Dialog
        if (showMergeDialog) {
            ArtistMergeSearchDialog(
                sourceArtistId = artistDetails.artist.id,
                sourceArtistName = artistDetails.artist.name,
                onDismiss = { showMergeDialog = false },
                onMergeComplete = {
                    // Navigate back after successful merge
                    onNavigateBack()
                }
            )
        }

        // Artist Split Dialog
        if (showSplitDialog) {
            ArtistSplitDialog(
                sourceArtistId = artistDetails.artist.id,
                sourceArtistName = artistDetails.artist.name,
                onDismiss = { showSplitDialog = false },
                onSplitComplete = { sourceDeleted ->
                    if (sourceDeleted) {
                        // The artist no longer exists — leave the details screen
                        onNavigateBack()
                    } else {
                        // Reload to reflect the tracks that were moved out
                        onReloadArtist()
                    }
                }
            )
        }
    }
}

@Composable
fun ArtistHeroSection(
    artistDetails: ArtistDetails,
    onRefreshImage: () -> Unit,
    isRefreshingImage: Boolean = false,
    onShowRenameDialog: () -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
         // Premium Glow Container
        Box(contentAlignment = Alignment.Center) {
            // Glow Layer (Indigo/Violet for Artists)
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                TempoPrimaryMuted.copy(alpha = 0.3f), // Indigo
                                Color.Transparent
                            )
                        )
                    )
            )

            // Image Container - Circular shape
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    TempoPrimaryMuted.copy(alpha = 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                val context = LocalContext.current
                val imageUrl = artistDetails.artist.imageUrl

                if (imageUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(TempoPrimaryMuted, TempoPrimary)
                                )
                            )
                            .border(
                                3.dp,
                                Color.White,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = artistDetails.artist.name.firstOrNull()?.uppercase() ?: "?",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    // Image container with circular clip and white border
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .border(
                                3.dp,
                                Color.White,
                                CircleShape
                            )
                    ) {
                        CachedAsyncImage(
                            imageUrl = imageUrl,
                            contentDescription = "Artist Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            targetSizeDp = 220,
                            onError = {
                                android.util.Log.e("ArtistHeroSection", "Failed to load image: ${it.result.throwable.message}")
                            }
                        )
                    }

                    // Refresh button overlay (positioned at bottom-right of circular image)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 4.dp, bottom = 4.dp)
                            .clickable { onRefreshImage() }
                    ) {
                        if (isRefreshingImage) {
                            // Loading spinner
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            // Refresh icon
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.6f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.details_refresh_artist_image),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Artist name with edit icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = artistDetails.artist.name,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onShowRenameDialog,
                modifier = Modifier.size(28.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White.copy(alpha = 0.8f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.details_rename_artist),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Combined Country and Genres Metadata Cluster
        val country = artistDetails.country
        val genresList = artistDetails.topGenres.ifEmpty { artistDetails.artist.genres }
        
        if (!country.isNullOrBlank() || genresList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                if (!country.isNullOrBlank()) {
                    Text(
                        text = "📍 $country",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SilverLight // Slate 200
                    )
                    if (genresList.isNotEmpty()) {
                        Text(
                            text = "  •  ",
                            color = TextQuaternary, // Slate 600
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                if (genresList.isNotEmpty()) {
                    Text(
                        text = genresList.take(3).joinToString(", ") { it.replaceFirstChar { char -> char.uppercase() } },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary, // Slate 400
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistStatsGrid(artistDetails: ArtistDetails) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stat 1: Total Plays
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = TempoError, // High-contrast Red
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.details_total_plays).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, // Slate 400
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatCount(artistDetails.personalPlayCount.toLong()),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = TempoErrorSoft // Soft Red
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .height(60.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                // Stat 2: Listening Time
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = TempoInfo, // High-contrast Blue
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.details_listening_time).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, // Slate 400
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatListeningTime(artistDetails.personalTotalTimeMs.toLong()),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = TempoInfoSoft // Soft Blue
                    )
                }
            }

            // Horizontal Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            // Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stat 3: Unique Songs
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = TempoPrimary, // High-contrast Purple
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.details_unique_tracks).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, // Slate 400
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatCount(artistDetails.uniqueTracksPlayed.toLong()),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = TempoAccent // Soft Purple
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .height(60.dp)
                        .width(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                // Stat 4: Unique Albums
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            tint = TempoWarning, // High-contrast Amber
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.details_unique_albums).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, // Slate 400
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatCount(artistDetails.uniqueAlbumsPlayed.toLong()),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = TempoWarningSoft // Soft Amber
                    )
                }
            }
        }
    }
}



@Composable
fun TopSongsPanel(
    songs: List<TopTrack>,
    onNavigateToSong: (Long) -> Unit
) {
    if (songs.isEmpty()) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White.copy(alpha = 0.03f),
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            songs.forEachIndexed { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSong(song.trackId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = TempoPrimary,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.Start
                    )

                    CachedAsyncImage(
                        imageUrl = song.albumArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = song.album ?: stringResource(R.string.details_unknown_album),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${song.playCount}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.details_plays).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (index < songs.lastIndex) {
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
fun TopAlbumCard(album: TopAlbum) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .padding(bottom = 8.dp)
    ) {
        // Album art with shadow and rounded corners
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(TempoSurfaceDialog), // Dark background for empty space
            contentAlignment = Alignment.Center
        ) {
            if (album.albumArtUrl.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White.copy(alpha = 0.4f)
                )
            } else {
                CachedAsyncImage(
                    imageUrl = album.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Album info
        Text(
            text = album.album,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.details_plays_count, album.playCount),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary, // Slate 400
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}





fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

fun formatListeningTime(millis: Long): String {
    val totalMinutes = millis / 1000 / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}


@Composable
fun FanStatusBadge(playCount: Int, percentile: Double? = null) {
    val (status, emoji, color, description) = if (percentile != null) {
        when {
            percentile <= 1.0 -> Quadruple(stringResource(R.string.fan_status_top_1), "👑", GoldPrimary, stringResource(R.string.fan_status_top_1_desc))
            percentile <= 5.0 -> Quadruple(stringResource(R.string.fan_status_top_5), "🌟", TempoWarning, stringResource(R.string.fan_status_top_5_desc))
            percentile <= 10.0 -> Quadruple(stringResource(R.string.fan_status_top_10), "🔥", TempoError, stringResource(R.string.fan_status_top_10_desc))
            percentile <= 25.0 -> Quadruple(stringResource(R.string.fan_status_top_25), "🎧", TempoInfo, stringResource(R.string.fan_status_top_25_desc))
            percentile <= 50.0 -> Quadruple(stringResource(R.string.fan_status_top_50), "🎵", TempoPrimary, stringResource(R.string.fan_status_top_50_desc))
            else -> Quadruple(stringResource(R.string.fan_status_listener), "🎵", TextSecondary, stringResource(R.string.fan_status_listener_desc))
        }
    } else {
         when {
            playCount > 1000 -> Quadruple(stringResource(R.string.fan_status_ultimate), "👑", GoldPrimary, stringResource(R.string.fan_status_ultimate_desc))
            playCount > 500 -> Quadruple(stringResource(R.string.fan_status_super), "🌟", TempoWarning, stringResource(R.string.fan_status_super_desc))
            playCount > 200 -> Quadruple(stringResource(R.string.fan_status_big), "🔥", TempoError, stringResource(R.string.fan_status_big_desc))
            playCount > 50 -> Quadruple(stringResource(R.string.fan_status_regular), "🎧", TempoInfo, stringResource(R.string.fan_status_regular_desc))
            else -> Quadruple(stringResource(R.string.fan_status_listener), "🎵", TextSecondary, stringResource(R.string.fan_status_listener_desc))
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = color.copy(alpha = 0.03f),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(80.dp)
                    .background(color)
            )
            
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 22.sp)
                }
                
                Column {
                    Text(
                        text = status.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun PeakHourCard(peakHour: Int, formattedHour: String) {
    val isDay = peakHour in 6..17
    val (emoji, accentColor, description) = if (isDay) {
        Triple("☀️", TempoWarning, stringResource(R.string.details_most_active) + " during the day")
    } else {
        Triple("🌙", TempoPrimary, stringResource(R.string.details_most_active) + " at night")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(accentColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 20.sp)
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.details_peak_hour).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, // Slate 400
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formattedHour,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = "•",
                    color = TextQuaternary // Slate 600
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary // Slate 300
                )
            }
        }
    }
}

@Composable
fun ListeningJourneySection(artistDetails: ArtistDetails) {
    val firstListen = artistDetails.firstListenedDate ?: artistDetails.firstDiscovery?.firstListenTimestamp
    val lastListen = artistDetails.lastListenedDate

    if (firstListen == null && lastListen == null) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Color.White.copy(alpha = 0.04f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "YOUR JOURNEY",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, // Slate 400
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (firstListen != null && lastListen != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = 17.dp, top = 28.dp)
                            .width(2.dp)
                            .height(
                                if (artistDetails.firstDiscovery != null) 100.dp else 50.dp
                            )
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    if (firstListen != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(TempoSuccessDeep.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "🚀", fontSize = 16.sp)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.details_first_listen).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary, // Slate 500
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatDate(firstListen),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                
                                if (artistDetails.firstDiscovery != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val dateStr = formatDate(artistDetails.firstDiscovery.firstListenTimestamp)
                                    Text(
                                        text = buildAnnotatedString {
                                            append("You discovered ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append(artistDetails.artist.name)
                                            }
                                            append(" and have since listened to ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append(formatListeningTime(artistDetails.personalTotalTimeMs))
                                            }
                                            append(" across ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append("${artistDetails.uniqueTracksPlayed}")
                                            }
                                            append(" different songs.")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary, // Slate 400
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    if (lastListen != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(TempoInfo.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "🎧", fontSize = 16.sp)
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.details_last_listen).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary, // Slate 500
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatDate(lastListen),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineItem(title: String, date: String, icon: String, accentColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accentColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, fontSize = 16.sp)
        }
        Column {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary, // Slate 500
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = date,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

fun formatDate(timestamp: Long): String {
    // Exact date format
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
