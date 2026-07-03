package me.avinas.tempo.ui.details

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard

import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.premiumClickable
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.SongShareCard
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoPrimary
import androidx.compose.ui.graphics.nativeCanvas
import me.avinas.tempo.ui.theme.SecondaryPurple
import me.avinas.tempo.ui.theme.AccentPurple
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import me.avinas.tempo.ui.theme.SubtlerGlass
import me.avinas.tempo.ui.theme.GlassWhite
import me.avinas.tempo.ui.theme.ElectricBlue
import me.avinas.tempo.ui.theme.GoldenAmber
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.SubtlerGlass as SubtlerGlassColor
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.material.icons.rounded.Album
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SongDetailsScreen(
    trackId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    viewModel: SongDetailsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val trackDetails = uiState.trackDetails
    val context = LocalContext.current
    val isSpotifyConnected = spotifyViewModel.isConnected()

    DeepOceanBackground {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TempoRed)
            }
        } else if (trackDetails != null) {
            SongDetailsContent(
                trackDetails = trackDetails,
                listeningHistory = uiState.listeningHistory,
                moodSummary = uiState.moodSummary,
                engagement = uiState.engagement,
                genre = uiState.genre,
                releaseDate = uiState.releaseDate,
                releaseYear = uiState.releaseYear,
                recordLabel = uiState.recordLabel,
                isSpotifyConnected = isSpotifyConnected,
                onNavigateBack = onNavigateBack,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                viewModel = viewModel
            )
        } else {
            // Error state
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = uiState.error ?: stringResource(R.string.details_track_not_found),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Handle navigation after successful deletion
        // Guard: skip when isLoading is true to avoid racing with initial data fetch
        // (trackDetails is null during initial load AND after deletion)
        LaunchedEffect(uiState.showDeleteDialog, uiState.trackDetails, uiState.isLoading) {
            if (!uiState.isLoading && !uiState.showDeleteDialog && !uiState.isDeleting && uiState.trackDetails == null) {
                // Successfully deleted (or track not found after load), navigate back
                onNavigateBack()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsContent(
    trackDetails: TrackDetails,
    listeningHistory: List<DailyListening>,
    moodSummary: TagBasedMoodAnalyzer.MoodSummary?,
    engagement: TrackEngagement?,
    genre: String?,
    releaseDate: String?,
    releaseYear: Int?,
    recordLabel: String?,
    isSpotifyConnected: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    viewModel: SongDetailsViewModel = hiltViewModel()
) {
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(Color(0xFFC026D3)) }
    
    Box(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier.premiumClickable(onClick = onNavigateBack),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            
            Text(
                text = stringResource(R.string.details_song_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showShareDialog = true },
                    modifier = Modifier.premiumClickable(onClick = { showShareDialog = true }),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_content_description))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.premiumClickable(onClick = { showMenu = true }),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B)) // Dark slate background
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details_merge_duplicate), color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.CallMerge,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            },
                            onClick = {
                                showMenu = false
                                showMergeDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.details_delete_song), color = Color.Red) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color.Red
                                )
                            },
                            onClick = {
                                showMenu = false
                                viewModel.showDeleteDialog()
                            }
                        )
                    }
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
                SongHeroSection(
                    trackDetails = trackDetails,
                    dominantColor = dominantColor,
                    onPaletteExtracted = { dominantColor = it },
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum,
                    onEditTitle = { viewModel.showEditTitleDialog() }
                )
            }
            

            
            item(key = "listener_identity") {
                if (engagement != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    ListenerIdentityCard(
                        engagement = engagement,
                        isFavorite = trackDetails.isFavorite
                    )
                } else if (trackDetails.isFavorite) {
                    Spacer(modifier = Modifier.height(24.dp))
                    AchievementBadge()
                }
            }

            item(key = "stats_grid") {
                StatsGrid(
                    trackDetails = trackDetails,
                    genre = genre,
                    releaseDate = releaseDate,
                    releaseYear = releaseYear
                )
            }

            item(key = "listening_journey") {
                SongListeningJourneySection(
                    trackDetails = trackDetails,
                    engagement = engagement
                )
            }

            item(key = "listening_trends") {
                Spacer(modifier = Modifier.height(16.dp))
                ListeningTrendsChart(history = listeningHistory)
            }

            // Mood & Genre Section (from MusicBrainz tags)
            if (moodSummary != null) {
                item(key = "mood_insights") {
                    Spacer(modifier = Modifier.height(24.dp))
                    MoodInsightsSection(moodSummary = moodSummary)
                }
            }

            // Engagement Section (from user behavior)
            if (engagement != null) {
                item(key = "engagement_section") {
                    Spacer(modifier = Modifier.height(24.dp))
                    EngagementSection(engagement = engagement)
                }
            }
        }
        }
    }


    if (showShareDialog) {
        SharePreviewDialog(
            onDismiss = { showShareDialog = false },
            contentToShare = {
                SongShareCard(trackDetails = trackDetails)
            }
        )
    }

    if (showMergeDialog) {
        MergeSearchDialog(
            sourceTrackId = trackDetails.track.id,
            onDismiss = { showMergeDialog = false },
            onTrackSelected = { /* Handled in ViewModel */ }
        )
    }

    val uiState by viewModel.uiState.collectAsState()
    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteDialog() },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.details_delete_song_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.details_delete_song_confirmation))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "\"${trackDetails.track.title}\" by ${trackDetails.track.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.details_delete_song_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.details_delete_song_bullet_library))
                    Text(stringResource(R.string.details_delete_song_bullet_history, trackDetails.playCount))
                    Text(stringResource(R.string.details_delete_song_bullet_metadata))
                    Text(stringResource(R.string.details_delete_song_bullet_artist))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.details_delete_song_undone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                if (uiState.isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    TextButton(
                        onClick = {
                            viewModel.deleteTrack()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.history_delete_button).uppercase())
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    val mergeTarget = uiState.mergeTargetTrack
    if (uiState.showEditTitleDialog && mergeTarget == null) {
        EditTitleDialog(
            currentTitle = trackDetails.track.title,
            isSaving = uiState.isSavingTitle,
            error = uiState.editTitleError,
            onClearWarnings = viewModel::clearEditTitleWarnings,
            onSave = { newTitle -> viewModel.updateTrackTitle(newTitle) },
            onDismiss = viewModel::dismissEditTitleDialog
        )
    }

    if (mergeTarget != null) {
        MergeConfirmDialog(
            target = mergeTarget,
            isMerging = uiState.isSavingTitle,
            onConfirm = viewModel::confirmEditTitleMerge,
            onDismiss = viewModel::cancelEditTitleMerge
        )
    }
}

@Composable
private fun EditTitleDialog(
    currentTitle: String,
    isSaving: Boolean,
    error: String?,
    onClearWarnings: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleText by remember { mutableStateOf(currentTitle) }
    val trimmed = titleText.trim()
    val isUnchanged = trimmed == currentTitle
    val canSave = trimmed.isNotEmpty() && !isUnchanged && !isSaving

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        icon = {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.details_edit_title_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        if (it.length <= 200) {
                            titleText = it
                            if (error != null) onClearWarnings()
                        }
                    },
                    label = { Text(stringResource(R.string.details_edit_title_label)) },
                    singleLine = true,
                    isError = error != null || trimmed.isEmpty(),
                    supportingText = {
                        when {
                            error != null -> Text(
                                error,
                                color = MaterialTheme.colorScheme.error
                            )
                            trimmed.isEmpty() -> Text(
                                stringResource(R.string.details_edit_title_empty),
                                color = MaterialTheme.colorScheme.error
                            )
                            isUnchanged -> Text(
                                stringResource(R.string.details_edit_title_unchanged),
                                color = Color(0xFF94A3B8)
                            )
                            else -> Text(
                                "${titleText.length} / 200",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        },
        confirmButton = {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(
                    onClick = { onSave(trimmed) },
                    enabled = canSave
                ) {
                    Text(stringResource(R.string.common_save).uppercase())
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun MergeConfirmDialog(
    target: me.avinas.tempo.data.local.entities.Track,
    isMerging: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isMerging) onDismiss() },
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.CallMerge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.details_edit_title_merge_title)) },
        text = {
            Text(
                stringResource(
                    R.string.details_edit_title_merge_message,
                    target.title,
                    target.artist
                )
            )
        },
        confirmButton = {
            if (isMerging) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.details_edit_title_merge_button).uppercase())
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isMerging
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun SongHeroSection(
    trackDetails: TrackDetails,
    dominantColor: Color,
    onPaletteExtracted: (Color) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onEditTitle: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Premium Showcase Container (Vinyl + Album Cover)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 24.dp)
        ) {
            // Glow behind the vinyl record
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Vinyl Record peeking out from the right
            Canvas(
                modifier = Modifier
                    .size(200.dp)
                    .offset(x = 48.dp)
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f
                
                // Draw vinyl body (black/dark grey)
                drawCircle(
                    color = Color(0xFF0F0F11),
                    radius = radius
                )
                
                // Draw concentric groove lines
                val grooveCount = 6
                for (i in 1..grooveCount) {
                    val r = radius * (0.35f + (i.toFloat() / grooveCount) * 0.55f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = r,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                
                // Draw center label (using dominantColor)
                drawCircle(
                    color = dominantColor,
                    radius = radius * 0.3f
                )
                
                // Draw center hole
                drawCircle(
                    color = Color(0xFF000000),
                    radius = radius * 0.08f
                )
            }

            // Floating Album Art Container with offset shadow and premium border
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .offset(x = (-32).dp)
                    .shadow(16.dp, RoundedCornerShape(20.dp), spotColor = dominantColor)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        2.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.4f), dominantColor.copy(alpha = 0.4f))
                        ),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                me.avinas.tempo.ui.components.AlbumArtImage(
                    albumArtUrl = trackDetails.track.albumArtUrl,
                    localArtUrl = trackDetails.localBackupArtUrl,
                    contentDescription = "Album Art for ${trackDetails.track.title}",
                    modifier = Modifier.fillMaxSize(),
                    onPaletteExtracted = onPaletteExtracted
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Title with high contrast white text and edit icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = trackDetails.track.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(end = 30.dp)
                    .premiumClickable(onClick = onEditTitle)
            )

            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.details_edit_title),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.CenterEnd)
                    .premiumClickable(onClick = onEditTitle)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Metadata cluster: High-contrast Obsidian Pills
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            val artistIdentifier = if (trackDetails.track.primaryArtistId != null && trackDetails.track.primaryArtistId > 0) {
                "id:${trackDetails.track.primaryArtistId}"
            } else {
                trackDetails.track.artist
            }
            
            // Artist Pill (Obsidian + Subtle Border)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .premiumClickable(onClick = { 
                        onNavigateToArtist(artistIdentifier)
                    })
                    .background(Color(0xFF0F0F12))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFA78BFA), // Softer lavender tint
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = trackDetails.track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (!trackDetails.track.album.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                
                // Album Pill (Obsidian + Subtle Border)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .premiumClickable(onClick = { 
                            onNavigateToAlbum(trackDetails.track.album, trackDetails.track.artist)
                        })
                        .background(Color(0xFF0F0F12))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24), // Softer amber/gold
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = trackDetails.track.album,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ListenerIdentityCard(engagement: TrackEngagement, isFavorite: Boolean) {
    val score = engagement.engagementScore
    val (status, emoji, color, description) = when {
        score >= 90 -> Quadruple("Ultimate Obsession", "💎", Color(0xFF8B5CF6), "This song has completely dominated your listening sessions recently!")
        score >= 80 -> Quadruple("Personal Favorite", "❤️", Color(0xFFEF4444), "One of your most-played and highest-engagement tracks.")
        score >= 70 -> Quadruple("Heavy Rotation", "🔥", Color(0xFFF59E0B), "You return to this track frequently. It's a staple in your rotation.")
        score >= 55 -> Quadruple("Regular Jam", "⭐", Color(0xFF10B981), "You consistently listen to this track and rarely skip it.")
        score >= 40 -> Quadruple("Casual Favorite", "👍", Color(0xFF3B82F6), "A track you enjoy having in your queue from time to time.")
        score >= 25 -> Quadruple("Occasional Play", "🎵", Color(0xFF06B6D4), "You listen to this track casually when it comes up.")
        score >= 10 -> Quadruple("Ambient Stream", "🎧", Color(0xFF64748B), "This track mostly plays in the background of your sessions.")
        else -> Quadruple("Quick Skip", "⏭️", Color(0xFF94A3B8), "You tend to skip this track quickly when it starts playing.")
    }

    val borderBrush = remember(score, color) {
        when {
            score >= 90 -> Brush.linearGradient(listOf(Color(0xFF8B5CF6).copy(alpha = 0.4f), Color(0xFF3B82F6).copy(alpha = 0.4f)))
            score >= 80 -> Brush.linearGradient(listOf(Color(0xFFEF4444).copy(alpha = 0.4f), Color(0xFFF43F5E).copy(alpha = 0.4f)))
            score >= 70 -> Brush.linearGradient(listOf(Color(0xFFF59E0B).copy(alpha = 0.4f), Color(0xFFD97706).copy(alpha = 0.4f)))
            score >= 55 -> Brush.linearGradient(listOf(Color(0xFF10B981).copy(alpha = 0.4f), Color(0xFF059669).copy(alpha = 0.4f)))
            else -> Brush.linearGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.15f)))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, borderBrush), RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side ticket stub head (colored glow indicator + emoji)
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(color.copy(alpha = 0.15f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 26.sp)
            }
            
            // Vertical dotted separator line
            Canvas(modifier = Modifier.width(2.dp).fillMaxHeight()) {
                val strokeWidth = 2.dp.toPx()
                val dashPathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                val paint = android.graphics.Paint().apply {
                    this.color = Color.White.copy(alpha = 0.2f).toArgb()
                    this.style = android.graphics.Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    this.pathEffect = dashPathEffect
                }
                drawContext.canvas.nativeCanvas.drawLine(
                    0f, 0f, 0f, size.height, paint
                )
            }
            
            // Right side ticket details
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = status.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                        if (isFavorite) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFEF4444).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "FAVORITE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFCA5A5)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }
    }
}

@Composable
fun SongListeningJourneySection(trackDetails: TrackDetails, engagement: TrackEngagement?) {
    val firstListen = trackDetails.firstPlayed ?: engagement?.firstPlayedTimestamp
    val lastListen = trackDetails.lastPlayed ?: engagement?.lastPlayedTimestamp

    if (firstListen == null && lastListen == null) return

    Spacer(modifier = Modifier.height(24.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "YOUR JOURNEY WITH THIS SONG",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Draw continuous vertical dashed timeline connector line in background
            if (firstListen != null && lastListen != null) {
                Canvas(
                    modifier = Modifier
                        .padding(start = 19.dp, top = 24.dp)
                        .width(2.dp)
                        .height(if (engagement != null) 148.dp else 70.dp)
                ) {
                    val strokeWidth = 2.dp.toPx()
                    val dashPathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                    val paint = android.graphics.Paint().apply {
                        this.color = Color.White.copy(alpha = 0.2f).toArgb()
                        this.style = android.graphics.Paint.Style.STROKE
                        this.strokeWidth = strokeWidth
                        this.pathEffect = dashPathEffect
                    }
                    drawContext.canvas.nativeCanvas.drawLine(
                        0f, 0f, 0f, size.height, paint
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (firstListen != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Node Indicator (Emerald Glow)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f), CircleShape)
                                .border(1.5.dp, Color(0xFF10B981), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🚀", fontSize = 16.sp)
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.details_first_listen).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatSongDate(firstListen),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            if (engagement != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                // Styled Narrative Card (high contrast dark graphite)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F0F12), RoundedCornerShape(16.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = buildAnnotatedString {
                                            append("You discovered this song ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append("${engagement.daysSinceFirstPlay} days ago")
                                            }
                                            append(". Since then, you've played it ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append("${trackDetails.playCount} times")
                                            }
                                            append(" across ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                                                append("${engagement.uniqueSessionsCount} sessions")
                                            }
                                            append(". You listen with a ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFA78BFA))) {
                                                append(engagement.listeningBehavior.lowercase())
                                            }
                                            append(" pattern, making it a ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFCA5A5))) {
                                                append(engagement.engagementLevel.lowercase())
                                            }
                                            append(" for you.")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFCBD5E1),
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (lastListen != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Node Indicator (Blue Glow)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFF3B82F6).copy(alpha = 0.2f), CircleShape)
                                .border(1.5.dp, Color(0xFF3B82F6), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🎧", fontSize = 16.sp)
                        }
                        
                        Column {
                            Text(
                                text = stringResource(R.string.details_last_listen).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatSongDate(lastListen),
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

private fun formatSongDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun AchievementBadge() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0xFFEF4444).copy(alpha = 0.4f), Color(0xFFF43F5E).copy(alpha = 0.4f)))), RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🏆", fontSize = 26.sp)
            }
            
            // Vertical dotted separator line
            Canvas(modifier = Modifier.width(2.dp).fillMaxHeight()) {
                val strokeWidth = 2.dp.toPx()
                val dashPathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                val paint = android.graphics.Paint().apply {
                    this.color = Color.White.copy(alpha = 0.2f).toArgb()
                    this.style = android.graphics.Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                    this.pathEffect = dashPathEffect
                }
                drawContext.canvas.nativeCanvas.drawLine(
                    0f, 0f, 0f, size.height, paint
                )
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.details_favorite_title).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.details_favorite_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCBD5E1)
                )
            }
        }
    }
}

@Composable
fun StatsGrid(
    trackDetails: TrackDetails,
    genre: String?,
    releaseDate: String?,
    releaseYear: Int?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Widget 1: Times Played (Full Width Hero Widget)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F0F12), RoundedCornerShape(24.dp))
                .border(
                    BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), 
                    RoundedCornerShape(24.dp)
                )
                .padding(16.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFA78BFA), // Lavender tint
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.details_stat_times_played).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = trackDetails.playCount.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White // High contrast but clean white instead of soft red
                )
            }
        }

        // Row 2: Total Listening & Peak Position (Side-by-side)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Widget 2: Total Listening
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.details_stat_total_listening).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${trackDetails.totalTimeMinutes}m",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF93C5FD) // Soft Blue
                    )
                }
            }

            // Widget 3: Peak Position
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.details_stat_peak_position).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "#${trackDetails.peakRank ?: "-"}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFD8B4FE) // Soft Purple
                    )
                }
            }
        }

        // Row 3: Release Date & Genre (Side-by-side)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Widget 4: Release Date
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.details_stat_release_date).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatReleaseDate(releaseDate) ?: releaseYear?.toString() ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFCBD5E1) // Clean grey instead of soft green
                    )
                }
            }

            // Widget 5: Genre
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0F0F12), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.details_stat_genre).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = genre ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFCBD5E1) // Clean grey instead of soft orange
                    )
                }
            }
        }
    }
}



@Composable
fun ListeningTrendsChart(history: List<DailyListening>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0xFF0F0F12), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.details_listening_trends).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.details_no_data_available), color = Color.White.copy(alpha = 0.4f))
                }
            } else {
                val chartData = remember(history) {
                    val maxPlays = history.maxOfOrNull { it.playCount }?.toFloat()?.coerceAtLeast(1f) ?: 1f
                    val yRange = maxPlays * 1.25f
                    Triple(history, maxPlays, yRange)
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    // Studio-Grid Dot Matrix Background
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val gridRows = 4
                        val gridCols = 8
                        for (i in 0..gridRows) {
                            val y = i * (size.height / gridRows)
                            drawLine(
                                color = Color.White.copy(alpha = 0.04f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        for (i in 0..gridCols) {
                            val x = i * (size.width / gridCols)
                            drawLine(
                                color = Color.White.copy(alpha = 0.04f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val yRange = chartData.third
                        val widthPerPoint = size.width / (history.size - 1).coerceAtLeast(1)
                        
                        val path = Path()
                        val fillPath = Path()
                        
                        fillPath.moveTo(0f, size.height)
                        
                        if (history.size == 1) {
                            val item = history.first()
                            val y = size.height - (item.playCount / yRange * size.height)
                            
                            path.moveTo(0f, y)
                            path.lineTo(size.width, y)
                            
                            fillPath.lineTo(0f, y)
                            fillPath.lineTo(size.width, y)
                            fillPath.lineTo(size.width, size.height)
                        } else {
                            history.forEachIndexed { index, item ->
                                val x = index * widthPerPoint
                                val y = size.height - (item.playCount / yRange * size.height)
                                
                                if (index == 0) {
                                    path.moveTo(x, y)
                                    fillPath.lineTo(x, y)
                                } else {
                                    val prevX = (index - 1) * widthPerPoint
                                    val prevY = size.height - (history[index - 1].playCount / yRange * size.height)
                                    val controlX1 = prevX + (x - prevX) / 2
                                    val controlX2 = prevX + (x - prevX) / 2
                                    
                                    path.cubicTo(controlX1, prevY, controlX2, y, x, y)
                                    fillPath.cubicTo(controlX1, prevY, controlX2, y, x, y)
                                }
                            }
                            fillPath.lineTo(size.width, size.height)
                        }
                        
                        fillPath.close()
                        
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6).copy(alpha = 0.35f), // Glowing purple gradient fill
                                    Color(0xFF8B5CF6).copy(alpha = 0.0f)
                                )
                            )
                        )
                        
                        drawPath(
                            path = path,
                            color = Color(0xFF8B5CF6),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MoodInsightsSection(moodSummary: TagBasedMoodAnalyzer.MoodSummary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0xFF0F0F12), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.details_mood_genre).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Synthesizer Rack Panel (Horizontal rows structured as modules)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Module 1: Mood
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MoodIndicator(
                        emoji = moodSummary.moodEmoji,
                        label = moodSummary.moodName,
                        sublabel = stringResource(R.string.details_mood_label)
                    )
                }

                // Module 2: Energy
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MoodIndicator(
                        emoji = getEnergyEmoji(moodSummary.energy.value),
                        label = moodSummary.energyName,
                        sublabel = stringResource(R.string.details_energy_label)
                    )
                }

                // Module 3: Genre (If available)
                if (moodSummary.primaryGenre != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MoodIndicator(
                            emoji = "🎵",
                            label = moodSummary.primaryGenre.take(12),
                            sublabel = stringResource(R.string.details_genre_label)
                        )
                    }
                }
            }
            
            // Energy VU Equalizer Meter (Segmented LEDs)
            Spacer(modifier = Modifier.height(24.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.details_estimated_energy),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${moodSummary.energyPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                
                // Segmented LED Meter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val totalBars = 20
                    val activeBars = (moodSummary.energy.value * totalBars).toInt()
                    
                    for (i in 0 until totalBars) {
                        val isActive = i < activeBars
                        val color = if (isActive) {
                            val fraction = i.toFloat() / totalBars
                            androidx.compose.ui.graphics.lerp(Color(0xFF8B5CF6).copy(alpha = 0.8f), Color(0xFFC084FC).copy(alpha = 0.8f), fraction)
                        } else {
                            Color(0xFF222226)
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp) // Thinner LED segments
                                .background(color, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
            
            // Mood tags if available
            if (moodSummary.moodTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    moodSummary.moodTags.forEach { tag ->
                        TagChip(tag = tag)
                    }
                }
            }
        }
    }
}

@Composable
fun EngagementSection(engagement: TrackEngagement) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0xFF0F0F12), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.details_your_engagement).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 1.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = engagement.engagementEmoji,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = engagement.engagementLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = getEngagementColor(engagement.engagementScore),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Engagement Score Widget (Large Glowing Slider style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.details_engagement_score),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "${engagement.engagementScore}/100",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val engagementColor = getEngagementColor(engagement.engagementScore)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = engagement.engagementScore / 100f)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF3B82F6), // Electric Blue
                                        engagementColor
                                    )
                                )
                            )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Behavior stats row (Assembled as three modular panels)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(
                        stringResource(R.string.details_stat_full_plays),
                        "${engagement.fullPlaysCount}",
                        stringResource(R.string.details_stat_of_plays, engagement.playCount)
                    ),
                    Triple(
                        stringResource(R.string.details_stat_avg_completion),
                        "${engagement.averageCompletionPercent.toInt()}%",
                        engagement.completionPattern
                    ),
                    Triple(
                        stringResource(R.string.details_stat_replays),
                        "${engagement.replayCount}",
                        stringResource(R.string.details_stat_back_to_back)
                    )
                ).forEach { (label, value, subtext) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BehaviorStat(label = label, value = value, subtext = subtext)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Completion & Skip Rate Row (card-free partitioned, side by side modules)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Completion Rate Gauge Module
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.details_completion_rate).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { engagement.averageCompletionPercent / 100f },
                                modifier = Modifier.size(64.dp), // slightly smaller
                                color = Color(0xFF10B981).copy(alpha = 0.8f), // softer emerald
                                trackColor = Color.White.copy(alpha = 0.08f),
                                strokeWidth = 4.dp // thinner
                            )
                            Text(
                                text = "${engagement.averageCompletionPercent.toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                
                // Skip Rate Gauge Module
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.details_skip_rate).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val skipRate = if (engagement.playCount > 0) (engagement.skipsCount.toFloat() / engagement.playCount) else 0f
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { skipRate.coerceIn(0f, 1f) },
                                modifier = Modifier.size(64.dp), // slightly smaller
                                color = if (skipRate > 0.5f) Color(0xFFEF4444).copy(alpha = 0.8f) else Color(0xFFF59E0B).copy(alpha = 0.8f), // softer red/amber
                                trackColor = Color.White.copy(alpha = 0.08f),
                                strokeWidth = 4.dp // thinner
                            )
                            Text(
                                text = "${(skipRate * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            
            // Skip/Pause/Session details log panel at the bottom
            val logs = remember(engagement) {
                val list = mutableListOf<String>()
                if (engagement.skipsCount > 0) {
                    list.add("• Skipped ${engagement.skipsCount} times during playback.")
                }
                if (engagement.averagePauseCount > 0.5f) {
                    val pauseText = when {
                        engagement.averagePauseCount >= 3f -> "• Paused frequently (avg ${engagement.averagePauseCount} times per play)."
                        engagement.averagePauseCount >= 1f -> "• Paused occasionally during playback."
                        else -> "• Paused rarely."
                    }
                    list.add(pauseText)
                }
                if (engagement.uniqueSessionsCount > 1) {
                    list.add("• Listened across ${engagement.uniqueSessionsCount} unique sessions.")
                }
                list
            }
            
            if (logs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        logs.forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TagChip(tag: String) {
    Box(
        modifier = Modifier
            .background(
                Color(0xFF6366F1), // Solid Indigo 500
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = tag.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun BehaviorStat(
    label: String,
    value: String,
    subtext: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
        Text(
            text = subtext,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp
        )
    }
}

private fun getEngagementColor(score: Int): Color {
    return when {
        score >= 80 -> Color(0xFFEF4444) // Red - favorite
        score >= 60 -> Color(0xFFF59E0B) // Amber - loved
        score >= 40 -> Color(0xFF22C55E) // Green - enjoyed
        score >= 20 -> Color(0xFF3B82F6) // Blue - casual
        else -> Color(0xFF6B7280)        // Gray - background
    }
}

@Composable
fun MoodIndicator(
    emoji: String,
    label: String,
    sublabel: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = sublabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun AudioFeatureBar(
    label: String,
    value: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.1f),
        )
    }
}

private fun getEnergyEmoji(energy: Float): String {
    return when {
        energy >= 0.7f -> "⚡"
        energy >= 0.5f -> "🔥"
        energy >= 0.3f -> "🌊"
        else -> "😴"
    }
}

fun formatReleaseDate(dateString: String?): String? {
    if (dateString.isNullOrBlank()) return null
    return try {
        // Try parsing ISO date (e.g. 2023-01-15)
        val date = java.time.LocalDate.parse(dateString.take(10))
        java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy").format(date)
    } catch (e: Exception) {
        // If parsing fails (e.g. just a year), just return it as is or null if we want to fallback to year int
        null 
    }
}
