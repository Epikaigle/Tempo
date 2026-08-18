package me.avinas.tempo.ui.details

/* Tempo · SongDetails · Audio Features & Listening History.
 * Human-centered copy standards (behuman):
 * - Clear, concrete facts over adjectives and puffery
 * - Direct, verb-first buttons and concise microcopy
 * - Human musical translations paired with precision data
 * - Zero emojis, 100% SVG vector icons and disciplined layout tokens
 */

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
import me.avinas.tempo.data.repository.TrackAudioFeatures
import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TagBasedMoodAnalyzer
import me.avinas.tempo.data.stats.TrackDetails
import me.avinas.tempo.data.stats.TrackEngagement
import me.avinas.tempo.ui.components.AlbumArtImage
import me.avinas.tempo.ui.components.ArtAtmosphereLayer
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.SongShareCard
import me.avinas.tempo.ui.spotify.SpotifyViewModel
import me.avinas.tempo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun SongDetailsScreen(
    trackId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    viewModel: SongDetailsViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isPlayingPreview by viewModel.isPlayingPreview.collectAsState()
    val previewProgress by viewModel.previewProgress.collectAsState()
    val previewPositionMs by viewModel.previewPositionMs.collectAsState()
    val trackDetails = uiState.trackDetails

    // Clean up preview on back navigation
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopAudioPreview()
        }
    }

    if (uiState.isLoading) {
        SongDetailsLoadingSkeleton()
    } else if (trackDetails != null) {
        SongDetailsContent(
            trackDetails = trackDetails,
            uiState = uiState,
            isPlayingPreview = isPlayingPreview,
            previewProgress = previewProgress,
            previewPositionMs = previewPositionMs,
            onTogglePreview = { viewModel.toggleAudioPreview() },
            onNavigateBack = onNavigateBack,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum,
            viewModel = viewModel,
        )
    } else {
        DeepOceanBackground {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicOff,
                    contentDescription = null,
                    tint = TempoError.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = uiState.error ?: stringResource(R.string.details_track_not_found),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.8.dp, GlassBorderMedium),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    Text(stringResource(R.string.common_retry).uppercase(Locale.getDefault()))
                }
            }
        }
    }

    // Auto-return only after successful delete/merge; a load error stays put so
    // the retry button above remains reachable.
    LaunchedEffect(uiState.showDeleteDialog, uiState.trackDetails, uiState.isLoading, uiState.error) {
        if (!uiState.isLoading && !uiState.showDeleteDialog && !uiState.isDeleting &&
            uiState.trackDetails == null && uiState.error == null
        ) {
            onNavigateBack()
        }
    }
}

@Composable
private fun SongDetailsLoadingSkeleton() {
    DeepOceanBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkeletonBlock(modifier = Modifier.size(232.dp), cornerRadius = 22.dp)
            Spacer(modifier = Modifier.height(24.dp))
            SkeletonBlock(modifier = Modifier.size(160.dp, 22.dp), cornerRadius = 8.dp)
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(modifier = Modifier.size(100.dp, 14.dp), cornerRadius = 7.dp)
            Spacer(modifier = Modifier.height(30.dp))
            SkeletonBlock(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(152.dp),
                cornerRadius = 22.dp,
            )
        }
    }
}

// Shared with AlbumDetailsScreen (same package).
@Composable
internal fun SkeletonBlock(modifier: Modifier, cornerRadius: Dp) {
    val reducedMotion = rememberReducedMotion()
    // Layer-phase pulse: no recomposition per frame.
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.40f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .graphicsLayer { alpha = if (reducedMotion) 0.65f else pulse.value }
            .clip(RoundedCornerShape(cornerRadius))
            .background(GlassFrostMedium)
            .border(0.8.dp, GlassBorderSoft, RoundedCornerShape(cornerRadius))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailsContent(
    trackDetails: TrackDetails,
    uiState: SongDetailsUiState,
    isPlayingPreview: Boolean,
    previewProgress: Float,
    previewPositionMs: Long,
    onTogglePreview: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    viewModel: SongDetailsViewModel = hiltViewModel(),
) {
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    val dominantColor = rememberDominantColor(trackDetails)
    val listState = rememberLazyListState()

    // Collapsed header title appears when the hero title itself scrolls under
    // the top bar — anchored to its live position, not a scroll-pixel guess.
    // The callback compares against the threshold and only writes on a flip,
    // so scrolling never churns recomposition.
    val density = LocalDensity.current
    val headerBottomPx = with(density) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp).toPx()
    }
    var showCollapsedTitle by remember { mutableStateOf(false) }
    val headerScrimAlpha by animateFloatAsState(
        targetValue = if (showCollapsedTitle) 1f else 0f,
        animationSpec = tween(220),
        label = "headerScrim",
    )

    // Sections below need real playback data, not just an empty engagement row.
    val engagement = uiState.engagement
    val hasPlaybackData = engagement != null && engagement.playCount > 0

    DeepOceanBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            // The room recolors per track: blurred cover-art wash behind all
            // content. Falls through to the plain DeepOcean base when no art.
            ArtAtmosphereLayer(
                artUrl = trackDetails.track.albumArtUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MusicBrainzEnrichmentService.fixHttpUrl(it) }
                    ?: trackDetails.localBackupArtUrl,
                tint = dominantColor,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp,
                    bottom = 64.dp
                ),
            ) {
                // 1. Hero Stage (Artwork, Audio Preview Pill, Title, Artist, Album, Meta)
                item(key = "hero_section") {
                    SongHeroEditorialStage(
                        trackDetails = trackDetails,
                        dominantColor = dominantColor,
                        previewUrl = uiState.previewUrl,
                        isPlayingPreview = isPlayingPreview,
                        previewProgress = previewProgress,
                        previewPositionMs = previewPositionMs,
                        onTogglePreview = onTogglePreview,
                        onNavigateToArtist = onNavigateToArtist,
                        onNavigateToAlbum = onNavigateToAlbum,
                        onEditTitle = { viewModel.showEditTitleDialog() },
                        onTitlePositioned = { top ->
                            val collapsed = top <= headerBottomPx
                            if (collapsed != showCollapsedTitle) {
                                showCollapsedTitle = collapsed
                            }
                        },
                        genre = uiState.genre,
                        releaseDate = uiState.releaseDate,
                        releaseYear = uiState.releaseYear,
                        recordLabel = uiState.recordLabel,
                    )
                }

                // 2. High-Contrast Master Stats
                item(key = "master_stats_masthead") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        MasterStatMasthead(
                            trackDetails = trackDetails,
                            engagement = uiState.engagement,
                            dominantColor = dominantColor,
                        )
                    }
                }

                // 3. Listening Activity Over Time (Touch-Scrubbable with Micro-Stats)
                if (uiState.listeningHistory.isNotEmpty()) {
                    item(key = "temporal_rhythm") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            TemporalRhythmSection(
                                history = uiState.listeningHistory,
                                peakBinge = uiState.peakBingeDay,
                                tint = dominantColor,
                            )
                        }
                    }
                }

                // 4. Audio Features & Sonic Profile (hidden until data exists)
                if (uiState.audioFeatures != null || uiState.moodSummary != null) {
                    item(key = "acoustic_dna") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            AcousticDNASpecSheet(
                                audioFeatures = uiState.audioFeatures,
                                moodSummary = uiState.moodSummary,
                                tint = dominantColor,
                            )
                        }
                    }
                }

                // 5. Listening Standing
                if (hasPlaybackData || trackDetails.isFavorite) {
                    item(key = "listener_affinity") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            if (hasPlaybackData) {
                                ListenerAffinitySection(
                                    engagement = engagement,
                                    isFavorite = trackDetails.isFavorite,
                                    tint = dominantColor,
                                )
                            } else {
                                FavoriteBadgeCard()
                            }
                        }
                    }
                }

                // 6. Listening Timeline & Milestones
                val hasJourney = trackDetails.firstPlayed != null ||
                    trackDetails.lastPlayed != null ||
                    uiState.engagement?.firstPlayedTimestamp != null ||
                    uiState.peakBingeDay != null ||
                    uiState.habitualHour != null

                if (hasJourney) {
                    item(key = "chronological_odyssey") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            ListeningOdysseySection(
                                trackDetails = trackDetails,
                                engagement = uiState.engagement,
                                peakBingeDay = uiState.peakBingeDay,
                                habitualHour = uiState.habitualHour,
                                tint = dominantColor,
                            )
                        }
                    }
                }

                // 7. Playback Breakdown
                if (hasPlaybackData) {
                    item(key = "playback_telemetry") {
                        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                            PlaybackFidelitySection(
                                engagement = engagement,
                                tint = dominantColor,
                            )
                        }
                    }
                }

                // 8. Streaming & Actions
                item(key = "streaming_hub") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
                        StreamingHubSection(
                            trackDetails = trackDetails,
                            spotifyUrl = uiState.spotifyTrackUrl,
                            appleMusicUrl = uiState.appleMusicUrl,
                            onShare = { showShareDialog = true },
                        )
                    }
                }

                // 9. Footer
                item(key = "footer") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp)) {
                        SongDetailsFooter()
                    }
                }
            }

            // Gradient scrim so scrolled content never collides with the
            // collapsed title; fades in with the title itself.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(136.dp)
                    .graphicsLayer { alpha = headerScrimAlpha }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                TempoDarkBackground.copy(alpha = 0.94f),
                                TempoDarkBackground.copy(alpha = 0.60f),
                                Color.Transparent,
                            )
                        )
                    )
            )

            // Fixed Top Bar with Collapsible Title
            TopBarNavigation(
                showCollapsedTitle = showCollapsedTitle,
                title = trackDetails.track.title,
                artist = trackDetails.track.artist,
                onNavigateBack = onNavigateBack,
                onShare = { showShareDialog = true },
                onMenuClick = { showMenu = true },
            )

            // Overflow Menu
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 52.dp, end = 16.dp)
            ) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(TempoDarkSurfaceElevated),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details_merge_duplicate), color = TextPrimary) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.CallMerge,
                                contentDescription = null,
                                tint = TextPrimary,
                            )
                        },
                        onClick = {
                            showMenu = false
                            showMergeDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details_delete_song), color = TempoError) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = TempoError,
                            )
                        },
                        onClick = {
                            showMenu = false
                            viewModel.showDeleteDialog()
                        },
                    )
                }
            }
        }
    }

    if (showShareDialog) {
        SharePreviewDialog(
            onDismiss = { showShareDialog = false },
            contentToShare = {
                SongShareCard(trackDetails = trackDetails)
            },
        )
    }

    if (showMergeDialog) {
        MergeSearchDialog(
            sourceTrackId = trackDetails.track.id,
            onDismiss = { showMergeDialog = false },
            onTrackSelected = { /* Handled in ViewModel */ },
        )
    }

    if (uiState.showDeleteDialog) {
        DeleteSongConfirmDialog(
            trackDetails = trackDetails,
            isDeleting = uiState.isDeleting,
            onConfirm = viewModel::deleteTrack,
            onDismiss = viewModel::dismissDeleteDialog,
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
            onDismiss = viewModel::dismissEditTitleDialog,
        )
    }

    if (mergeTarget != null) {
        MergeConfirmDialog(
            target = mergeTarget,
            isMerging = uiState.isSavingTitle,
            onConfirm = viewModel::confirmEditTitleMerge,
            onDismiss = viewModel::cancelEditTitleMerge,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Top Bar Navigation with Collapsible Title
// ──────────────────────────────────────────────────────────────

@Composable
private fun TopBarNavigation(
    showCollapsedTitle: Boolean,
    title: String,
    artist: String,
    onNavigateBack: () -> Unit,
    onShare: () -> Unit,
    onMenuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrostedHeaderButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.details_action_back),
            onClick = onNavigateBack,
        )

        // Collapsed Header Title (smooth fade in)
        AnimatedVisibility(
            visible = showCollapsedTitle,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -10 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -10 },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (!showCollapsedTitle) {
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FrostedHeaderButton(
                icon = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.details_action_share_card),
                onClick = onShare,
            )

            FrostedHeaderButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.details_action_song_options),
                onClick = onMenuClick,
            )
        }
    }
}

@Composable
private fun FrostedHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(GlassFrostMedium)
            .border(0.8.dp, GlassBorderSoft, CircleShape)
            .premiumClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 1. Song Hero Stage
// ──────────────────────────────────────────────────────────────

@Composable
fun SongHeroEditorialStage(
    trackDetails: TrackDetails,
    dominantColor: Color,
    previewUrl: String?,
    isPlayingPreview: Boolean,
    previewProgress: Float,
    previewPositionMs: Long,
    onTogglePreview: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onEditTitle: () -> Unit,
    onTitlePositioned: (Float) -> Unit = {},
    genre: String? = null,
    releaseDate: String? = null,
    releaseYear: Int? = null,
    recordLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Soft dominant-color halo — ties the extracted tint to the hero
        // and gives the art depth beyond its shadow.
        val glowBrush = remember(dominantColor) {
            Brush.radialGradient(
                colors = listOf(
                    dominantColor.copy(alpha = 0.20f),
                    dominantColor.copy(alpha = 0.0f),
                )
            )
        }
        // Artwork Box
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = glowBrush, shape = CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(232.dp)
                    .shadow(
                        elevation = 22.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = GlassShadowTeal,
                        spotColor = dominantColor.copy(alpha = 0.22f),
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(TempoDarkSurfaceSunken)
                    .border(1.dp, GlassBorderStrong, RoundedCornerShape(22.dp)),
            ) {
                AlbumArtImage(
                    albumArtUrl = trackDetails.track.albumArtUrl,
                    localArtUrl = trackDetails.localBackupArtUrl,
                    contentDescription = stringResource(
                        R.string.details_cover_artwork_cd, trackDetails.track.title
                    ),
                    modifier = Modifier.fillMaxSize(),
                )

                // Favorite Badge
                if (trackDetails.isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(TempoDarkSurface.copy(alpha = 0.85f))
                            .border(0.6.dp, GlassBorderSoft, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = stringResource(R.string.details_favorite_track_cd),
                            tint = TempoError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 30s Audio Preview Pill
        if (!previewUrl.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            AudioPreviewPill(
                isPlaying = isPlayingPreview,
                progress = previewProgress,
                positionMs = previewPositionMs,
                onToggle = onTogglePreview,
                accentColor = dominantColor,
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Title and Edit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    onTitlePositioned(coordinates.boundsInWindow().top)
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = trackDetails.track.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = DisplayFontFamily,
                    letterSpacing = (-0.5).sp,
                ),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .premiumClickable(onClick = onEditTitle),
            )

            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GlassFrostSoft)
                    .border(0.6.dp, GlassBorderSoft, CircleShape)
                    .premiumClickable(onClick = onEditTitle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.details_edit_title),
                    tint = TextTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Artist link
        val artistIdentifier = if (trackDetails.track.primaryArtistId != null && trackDetails.track.primaryArtistId > 0) {
            "id:${trackDetails.track.primaryArtistId}"
        } else {
            trackDetails.track.artist
        }
        ClickableEntityRow(
            text = trackDetails.track.artist,
            style = MaterialTheme.typography.titleMedium,
            tint = TextPrimary,
            onClick = { onNavigateToArtist(artistIdentifier) },
        )

        // Album link
        if (!trackDetails.track.album.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            ClickableEntityRow(
                text = trackDetails.track.album!!,
                style = MaterialTheme.typography.bodyMedium,
                tint = TextSecondary,
                onClick = { onNavigateToAlbum(trackDetails.track.album!!, trackDetails.track.artist) },
            )
        }

        // Metadata line (Contrast compliant TextTertiary)
        val metaParts = listOfNotNull(
            releaseDate?.let { formatReleaseDate(it) } ?: releaseYear?.toString(),
            genre?.takeIf { it.isNotBlank() },
            recordLabel?.takeIf { it.isNotBlank() },
        )
        if (metaParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = metaParts.joinToString("   ·   "),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun AudioPreviewPill(
    isPlaying: Boolean,
    progress: Float,
    positionMs: Long,
    onToggle: () -> Unit,
    accentColor: Color,
) {
    val secondsElapsed = (positionMs / 1000).toInt().coerceIn(0, 30)
    val timecode = stringResource(R.string.details_preview_timecode, secondsElapsed)
    // Position arrives at 4Hz; tween bridges the gaps so the fill glides.
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(250, easing = LinearEasing),
        label = "previewProgressFill",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPlaying) TempoDarkSurfaceElevated else GlassFrostSoft)
            .then(
                if (isPlaying) {
                    Modifier.border(0.8.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            .premiumClickable(onClick = onToggle, pressedScale = 0.96f),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = 14.dp,
                top = 6.dp,
                bottom = if (isPlaying || progress > 0f) 8.dp else 7.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.details_preview_pause_cd else R.string.details_preview_play_cd
                ),
                tint = if (isPlaying) accentColor else TextTertiary,
                modifier = Modifier.size(14.dp),
            )

            Text(
                text = if (isPlaying) timecode else stringResource(R.string.details_preview_label),
                style = CaptionSmall,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isPlaying) TextPrimary else TextSecondary,
            )

            if (isPlaying) {
                MiniEqualizer(color = accentColor)
            }
        }

        // Hairline position indicator — appears only once listening has
        // started; never on the untouched idle chip.
        if (isPlaying || progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(accentColor.copy(alpha = 0.20f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .background(accentColor)
                )
            }
        }
    }
}

@Composable
private fun MiniEqualizer(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    // Draw-phase animation: the bars pulse without recomposing the pill.
    val phase = rememberInfiniteTransition(label = "previewEq").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
        label = "previewEqPhase",
    )

    Canvas(modifier = modifier.size(width = 12.dp, height = 12.dp)) {
        val barWidth = 2.5.dp.toPx()
        val gap = ((size.width - barWidth * 3f) / 2f).coerceAtLeast(0f)
        repeat(3) { index ->
            val level = if (reducedMotion) {
                listOf(0.40f, 0.85f, 0.60f)[index]
            } else {
                val wave = sin(phase.value * 2f * PI.toFloat() * (1.4f + index * 0.75f))
                0.30f + 0.65f * (0.5f + 0.5f * wave)
            }
            val barHeight = size.height * level
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
internal fun ClickableEntityRow(
    text: String,
    style: TextStyle,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .premiumClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(15.dp),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 2. High-Contrast Master Stats
// ──────────────────────────────────────────────────────────────

@Composable
fun MasterStatMasthead(
    trackDetails: TrackDetails,
    engagement: TrackEngagement?,
    dominantColor: Color,
) {
    val formattedTime = if (trackDetails.totalTimeMinutes >= 60) {
        val hrs = trackDetails.totalTimeMinutes / 60
        val mins = trackDetails.totalTimeMinutes % 60
        "${hrs}h ${mins}m"
    } else {
        "${trackDetails.totalTimeMinutes}m"
    }

    val rankText = trackDetails.peakRank?.let { "#$it" } ?: "—"
    val rankSubtext = when (trackDetails.peakRank) {
        1 -> stringResource(R.string.details_rank_most_played)
        in 2..5 -> stringResource(R.string.details_rank_top_5)
        in 6..20 -> stringResource(R.string.details_rank_top_20)
        else -> stringResource(R.string.details_rank_library)
    }

    val hasEngagement = engagement != null && engagement.playCount > 0
    val loyaltyRate = if (hasEngagement) engagement.averageCompletionPercent.roundToInt() else null
    val loyaltyText = loyaltyRate?.let { "$it%" } ?: "—"
    val loyaltySubtext = when {
        loyaltyRate == null -> stringResource(R.string.details_awaiting_playback)
        loyaltyRate >= 90 -> stringResource(R.string.details_completion_to_end)
        loyaltyRate >= 70 -> stringResource(R.string.details_completion_high)
        loyaltyRate >= 50 -> stringResource(R.string.details_completion_moderate)
        else -> stringResource(R.string.details_completion_skipped)
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        variant = GlassCardVariant.Obsidian,
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Row 1: Plays & Time
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stat 1: Total Plays
                EditorialStatBlock(
                    label = stringResource(R.string.details_total_plays),
                    value = "${trackDetails.playCount}",
                    subtext = stringResource(R.string.details_stat_recorded_library),
                    accentTint = dominantColor,
                    modifier = Modifier.weight(1f),
                )

                // Vertical Hairline Divider
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft),
                )

                // Stat 2: Listening Time
                EditorialStatBlock(
                    label = stringResource(R.string.details_listening_time),
                    value = formattedTime,
                    subtext = stringResource(R.string.details_stat_total_recorded),
                    accentTint = dominantColor,
                    modifier = Modifier.weight(1f),
                )
            }

            // Horizontal Hairline Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(GlassBorderSoft),
            )

            // Row 2: Peak Rank & Completion Rate
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stat 3: Peak Rank
                EditorialStatBlock(
                    label = stringResource(R.string.details_peak_position),
                    value = rankText,
                    subtext = rankSubtext,
                    accentTint = dominantColor,
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                )

                // Vertical Hairline Divider
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft),
                )

                // Stat 4: Completion Rate (semantic — the one tint with meaning)
                EditorialStatBlock(
                    label = stringResource(R.string.details_completion_rate),
                    value = loyaltyText,
                    subtext = loyaltySubtext,
                    accentTint = when {
                        loyaltyRate == null -> TextTertiary
                        loyaltyRate >= 70 -> TempoSuccess
                        else -> TempoWarning
                    },
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun EditorialStatBlock(
    label: String,
    value: String,
    subtext: String,
    accentTint: Color,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
) {
    Column(
        modifier = modifier.padding(
            horizontal = 18.dp,
            vertical = if (isCompact) 14.dp else 18.dp
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accentTint)
            )
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = KickerSmall,
                color = TextTertiary,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = DisplayFontFamily,
                fontSize = if (isCompact) 22.sp else 28.sp,
                letterSpacing = (-0.5).sp,
            ),
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtext,
            style = CaptionSmall,
            color = TextTertiary,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 3. Listening Activity Over Time
// ──────────────────────────────────────────────────────────────

@Composable
fun TemporalRhythmSection(
    history: List<DailyListening>,
    peakBinge: Pair<String, Int>?,
    tint: Color = TempoPrimary,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = tint,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_listening_trends),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                if (peakBinge != null && peakBinge.second > 1) {
                    Text(
                        text = stringResource(
                            R.string.details_trends_peak,
                            peakBinge.second,
                            formatChartDate(peakBinge.first),
                        ),
                        style = CaptionSmall,
                        color = TextTertiary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            ScrubbableRhythmChart(
                history = history,
                peakBinge = peakBinge,
                tint = tint,
            )
        }
    }
}

@Composable
private fun ScrubbableRhythmChart(
    history: List<DailyListening>,
    peakBinge: Pair<String, Int>?,
    tint: Color,
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var clearJob by remember { mutableStateOf<Job?>(null) }
    val haptic = LocalHapticFeedback.current

    val activeDays = remember(history) { history.count { it.playCount > 0 } }
    val totalPlaysInPeriod = remember(history) { history.sumOf { it.playCount } }
    val avgPerActiveDay = if (activeDays > 0) String.format(Locale.getDefault(), "%.1f", totalPlaysInPeriod.toFloat() / activeDays) else "1.0"

    val maxPlays = remember(history) {
        history.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
    }

    // Bars grow in with a small stagger on first appearance
    val reducedMotion = rememberReducedMotion()
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(history) {
        if (entrance.value < 1f) {
            entrance.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        }
    }

    // Screen-reader summary of the currently inspected day
    val inspectedItem = selectedIndex?.takeIf { it in history.indices }?.let { history[it] }
    val chartStateDescription = if (inspectedItem != null) {
        val mins = (inspectedItem.totalTimeMs / 60000L).toInt()
        val timeStr = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        stringResource(
            R.string.details_trends_day_summary,
            formatSongDate(parseIsoToTimestamp(inspectedItem.date)),
            inspectedItem.playCount,
            timeStr,
        )
    } else {
        stringResource(R.string.details_trends_scrub_hint)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Summary Micro-Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RhythmSummaryItem(
                label = stringResource(R.string.details_trends_active_days),
                value = stringResource(R.string.details_trends_days, activeDays),
            )
            RhythmSummaryItem(
                label = stringResource(R.string.details_trends_daily_avg),
                value = stringResource(R.string.details_trends_per_day, avgPerActiveDay),
            )
            RhythmSummaryItem(
                label = stringResource(R.string.details_trends_peak_day),
                value = stringResource(R.string.details_plays_count, peakBinge?.second ?: maxPlays),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(GlassBorderSoft))
        Spacer(modifier = Modifier.height(12.dp))

        // Inspection HUD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (inspectedItem != null) {
                val mins = (inspectedItem.totalTimeMs / 60000L).toInt()
                val timeStr = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                val playsLabel = stringResource(
                    if (inspectedItem.playCount == 1) R.string.details_trends_hud_one else R.string.details_trends_hud_many,
                    inspectedItem.playCount,
                    timeStr,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TempoDarkSurfaceElevated)
                        .border(0.8.dp, GlassBorderMedium, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatSongDate(parseIsoToTimestamp(inspectedItem.date)),
                        style = KickerSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = "·",
                        style = CaptionSmall,
                        color = TextTertiary,
                    )
                    Text(
                        text = playsLabel,
                        style = KickerSmall,
                        color = tint,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.details_trends_scrub_hint),
                    style = CaptionSmall,
                    color = TextTertiary,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Density Bars Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .semantics { stateDescription = chartStateDescription }
                .pointerInput(history) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            clearJob?.cancel()
                            val idx = ((offset.x / size.width) * history.size).toInt().coerceIn(0, history.size - 1)
                            if (selectedIndex != idx) {
                                selectedIndex = idx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val idx = ((change.position.x / size.width) * history.size).toInt().coerceIn(0, history.size - 1)
                            if (selectedIndex != idx) {
                                selectedIndex = idx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            clearJob = scope.launch {
                                delay(1800)
                                selectedIndex = null
                            }
                        },
                        onDragCancel = {
                            selectedIndex = null
                        }
                    )
                }
                .pointerInput(history) {
                    detectTapGestures { offset ->
                        clearJob?.cancel()
                        val idx = ((offset.x / size.width) * history.size).toInt().coerceIn(0, history.size - 1)
                        selectedIndex = idx
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clearJob = scope.launch {
                            delay(2200)
                            selectedIndex = null
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barCount = history.size
                val totalWidth = size.width
                val totalHeight = size.height
                val barSpacing = 2.dp.toPx()
                val availableWidth = totalWidth - (barSpacing * (barCount - 1).coerceAtLeast(0))
                val barWidth = (availableWidth / barCount.coerceAtLeast(1)).coerceIn(2.5.dp.toPx(), 14.dp.toPx())

                // Baseline rule
                drawLine(
                    color = GlassBorderSoft,
                    start = Offset(0f, totalHeight),
                    end = Offset(totalWidth, totalHeight),
                    strokeWidth = 1.dp.toPx(),
                )

                history.forEachIndexed { index, item ->
                    val x = index * (totalWidth / barCount.coerceAtLeast(1))
                    val isSelected = selectedIndex == index
                    val isPeak = item.playCount == maxPlays && maxPlays > 1

                    if (item.playCount == 0) {
                        // Honest zero: a baseline dot, never a stub that
                        // implies activity on an empty day.
                        drawCircle(
                            color = if (isSelected) TextPrimary else GlassBorderMedium,
                            radius = 1.5.dp.toPx(),
                            center = Offset(x + barWidth / 2f, totalHeight - 1.5.dp.toPx()),
                        )
                        return@forEachIndexed
                    }

                    val fraction = (item.playCount.toFloat() / maxPlays).coerceIn(0.06f, 1f)
                    val stagger = ((entrance.value * (barCount + 10)) - index).coerceIn(0f, 1f)
                    val barHeight = totalHeight * fraction * stagger

                    val barColor = when {
                        isSelected -> TextPrimary
                        isPeak -> tint
                        else -> tint.copy(alpha = 0.55f)
                    }

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, totalHeight - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Date Axis
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatChartDate(history.first().date),
                style = CaptionSmall,
                color = TextTertiary,
            )
            Text(
                text = formatChartDate(history.last().date),
                style = CaptionSmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun RhythmSummaryItem(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = KickerSmall,
            color = TextTertiary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 4. Audio Features
// ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcousticDNASpecSheet(
    audioFeatures: TrackAudioFeatures?,
    moodSummary: TagBasedMoodAnalyzer.MoodSummary?,
    tint: Color = TempoPrimary,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = tint,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_audio_features_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = when {
                        audioFeatures != null -> stringResource(R.string.details_af_verified)
                        moodSummary != null -> stringResource(R.string.details_af_tag_estimate)
                        else -> stringResource(R.string.details_af_awaiting)
                    },
                    style = CaptionSmall,
                    color = TextTertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (audioFeatures == null && moodSummary == null) {
                // No analysis yet — the caller keeps this whole card hidden
                // until enrichment produces data; nothing to render here.
            } else {
                // Readouts: BPM & Harmonic Key
                val bpm = audioFeatures?.tempo?.roundToInt()
                val tempoDesc = audioFeatures?.tempoDescription ?: when {
                    bpm == null -> stringResource(R.string.details_af_pending_tempo)
                    bpm > 140 -> stringResource(R.string.details_af_fast)
                    bpm > 120 -> stringResource(R.string.details_af_upbeat)
                    bpm > 95 -> stringResource(R.string.details_af_moderate)
                    else -> stringResource(R.string.details_af_laidback)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tempo / BPM
                    AcousticHeaderMetric(
                        icon = Icons.Rounded.Speed,
                        label = stringResource(R.string.details_af_tempo),
                        primaryValue = bpm?.let { "$it BPM" } ?: "—",
                        sublabel = tempoDesc,
                        tint = tint,
                        modifier = Modifier.weight(1f),
                    )

                    // Vertical Hairline Divider
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(0.8.dp)
                            .background(GlassBorderSoft)
                    )

                    // Harmonic Key Signature
                    val keyName = audioFeatures?.musicalKey ?: moodSummary?.moodName ?: "—"
                    val keyDesc = audioFeatures?.moodDescription
                        ?: moodSummary?.energyName
                        ?: stringResource(R.string.details_af_awaiting_profile)

                    AcousticHeaderMetric(
                        icon = Icons.Rounded.GraphicEq,
                        label = stringResource(R.string.details_af_key),
                        primaryValue = keyName,
                        sublabel = keyDesc,
                        tint = tint,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (audioFeatures != null) {
                    Spacer(modifier = Modifier.height(20.dp))

                    // Spectrum Meters (Real values from audio features)
                    val energyDesc = when {
                        audioFeatures.energy >= 0.8f -> stringResource(R.string.details_energy_high)
                        audioFeatures.energy >= 0.5f -> stringResource(R.string.details_energy_mid)
                        else -> stringResource(R.string.details_energy_low)
                    }

                    val valenceDesc = when {
                        audioFeatures.valence >= 0.7f -> stringResource(R.string.details_valence_high)
                        audioFeatures.valence >= 0.45f -> stringResource(R.string.details_valence_mid)
                        else -> stringResource(R.string.details_valence_low)
                    }

                    val danceDesc = when {
                        audioFeatures.danceability >= 0.75f -> stringResource(R.string.details_dance_high)
                        audioFeatures.danceability >= 0.5f -> stringResource(R.string.details_dance_mid)
                        else -> stringResource(R.string.details_dance_low)
                    }

                    val acousticDesc = when {
                        audioFeatures.acousticness >= 0.6f -> stringResource(R.string.details_acoustic_high)
                        audioFeatures.acousticness >= 0.3f -> stringResource(R.string.details_acoustic_mid)
                        else -> stringResource(R.string.details_acoustic_low)
                    }

                    // One accent (the artwork tint), staggered reveal
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AcousticSpecMeter(label = stringResource(R.string.details_energy_label), humanDesc = energyDesc, fraction = audioFeatures.energy, tint = tint, delayMs = 0)
                        AcousticSpecMeter(label = stringResource(R.string.details_mood_label), humanDesc = valenceDesc, fraction = audioFeatures.valence, tint = tint, delayMs = 70)
                        AcousticSpecMeter(label = stringResource(R.string.details_danceability_label), humanDesc = danceDesc, fraction = audioFeatures.danceability, tint = tint, delayMs = 140)
                        AcousticSpecMeter(label = stringResource(R.string.details_acoustic_label), humanDesc = acousticDesc, fraction = audioFeatures.acousticness, tint = tint, delayMs = 210)
                    }
                }

                // Dynamic Badges & Mood Tags
                val dynamicBadges = mutableListOf<String>()
                if (audioFeatures != null) {
                    if (audioFeatures.energy >= 0.75f) dynamicBadges.add(stringResource(R.string.details_badge_intensity))
                    if (audioFeatures.danceability >= 0.75f) dynamicBadges.add(stringResource(R.string.details_badge_club))
                    if (audioFeatures.valence <= 0.35f) dynamicBadges.add(stringResource(R.string.details_badge_nocturnal))
                    if (audioFeatures.acousticness >= 0.60f) dynamicBadges.add(stringResource(R.string.details_badge_organic))
                    if (audioFeatures.tempo >= 130) dynamicBadges.add(stringResource(R.string.details_badge_fast))
                }

                val moodTags = moodSummary?.moodTags.orEmpty()
                val combinedBadges = (dynamicBadges + moodTags).distinct().take(5)

                if (combinedBadges.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(18.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        combinedBadges.forEach { tag ->
                            MoodTagPill(tag = tag)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcousticHeaderMetric(
    icon: ImageVector,
    label: String,
    primaryValue: String,
    sublabel: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label.uppercase(Locale.getDefault()),
                style = KickerSmall,
                color = TextTertiary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = primaryValue,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sublabel,
            style = CaptionSmall,
            color = TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AcousticSpecMeter(
    label: String,
    humanDesc: String,
    fraction: Float,
    tint: Color,
    delayMs: Int = 0,
) {
    val percent = (fraction * 100).roundToInt().coerceIn(0, 100)
    val animatedPercent by animateIntAsState(
        targetValue = percent,
        animationSpec = tween(durationMillis = 550, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "meterPercent",
    )
    val animatedFraction = rememberAnimatedFraction(fraction, delayMs = delayMs, durationMs = 550)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = Kicker,
                    color = TextPrimary,
                )
                Text(
                    text = "· $humanDesc",
                    style = CaptionSmall,
                    color = TextTertiary,
                )
            }
            Text(
                text = "$animatedPercent%",
                style = Kicker,
                color = tint,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            // Track
            drawLine(
                color = GlassBorderSoft,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            // Filled bar
            if (animatedFraction > 0f) {
                drawLine(
                    color = tint,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width * animatedFraction.coerceIn(0f, 1f), size.height / 2f),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun MoodTagPill(tag: String) {
    Box(
        modifier = Modifier
            .background(GlassFrostMedium, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 5. Listening Standing
// ──────────────────────────────────────────────────────────────

@Composable
fun ListenerAffinitySection(
    engagement: TrackEngagement,
    isFavorite: Boolean,
    tint: Color = TempoPrimary,
) {
    val score = engagement.engagementScore
    val tier = when {
        score >= 90 -> AffinityTierInfo(
            Icons.Rounded.AutoAwesome,
            stringResource(R.string.details_tier_top_status),
            stringResource(R.string.details_tier_top_name),
            stringResource(R.string.details_tier_top_desc),
        )
        score >= 80 -> AffinityTierInfo(
            Icons.Rounded.LocalFireDepartment,
            stringResource(R.string.details_tier_heavy_status),
            stringResource(R.string.details_tier_heavy_name),
            stringResource(R.string.details_tier_heavy_desc),
        )
        score >= 70 -> AffinityTierInfo(
            Icons.Rounded.Speed,
            stringResource(R.string.details_tier_regular_status),
            stringResource(R.string.details_tier_regular_name),
            stringResource(R.string.details_tier_regular_desc),
        )
        score >= 55 -> AffinityTierInfo(
            Icons.Rounded.MusicNote,
            stringResource(R.string.details_tier_moderate_status),
            stringResource(R.string.details_tier_moderate_name),
            stringResource(R.string.details_tier_moderate_desc),
        )
        score >= 40 -> AffinityTierInfo(
            Icons.Rounded.Headphones,
            stringResource(R.string.details_tier_occasional_status),
            stringResource(R.string.details_tier_occasional_name),
            stringResource(R.string.details_tier_occasional_desc),
        )
        score >= 25 -> AffinityTierInfo(
            Icons.Rounded.GraphicEq,
            stringResource(R.string.details_tier_background_status),
            stringResource(R.string.details_tier_background_name),
            stringResource(R.string.details_tier_background_desc),
        )
        else -> AffinityTierInfo(
            Icons.Rounded.Timeline,
            stringResource(R.string.details_tier_rare_status),
            stringResource(R.string.details_tier_rare_name),
            stringResource(R.string.details_tier_rare_desc),
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.TintedSolid,
        accentColor = tint,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_standing_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = stringResource(R.string.details_standing_score, score),
                    style = CaptionSmall,
                    color = TextTertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = tier.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = tier.status.uppercase(Locale.getDefault()),
                            style = KickerSmall,
                            color = tint,
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tier.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = TextPrimary,
                    )
                }

                Text(
                    text = "$score",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = DisplayFontFamily,
                        fontSize = 24.sp,
                    ),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Precision Meter Rule with Diamond Needle
            MeterRule(
                fraction = score / 100f,
                color = tint,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Narrative Insight
            Text(
                text = buildAnnotatedString {
                    append(tier.description)
                    append(" ")
                    append(stringResource(R.string.details_tier_played_prefix))
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                        append(stringResource(R.string.details_tier_sessions, engagement.uniqueSessionsCount))
                    }
                    if (engagement.replayCount > 0) {
                        append(" ")
                        append(stringResource(R.string.details_tier_with))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = tint)) {
                            append(stringResource(R.string.details_tier_replays, engagement.replayCount))
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 19.sp,
            )
        }
    }
}

private data class AffinityTierInfo(
    val icon: ImageVector,
    val status: String,
    val name: String,
    val description: String,
)

@Composable
fun FavoriteBadgeCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.TintedSolid,
        accentColor = TempoError,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(TempoError.copy(alpha = 0.14f))
                    .border(1.dp, TempoError.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = TempoError,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.details_favorite_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.details_favorite_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 6. Listening Timeline & Milestones
// ──────────────────────────────────────────────────────────────

@Composable
fun ListeningOdysseySection(
    trackDetails: TrackDetails,
    engagement: TrackEngagement?,
    peakBingeDay: Pair<String, Int>?,
    habitualHour: String?,
    tint: Color = TempoPrimary,
) {
    val firstListen = trackDetails.firstPlayed ?: engagement?.firstPlayedTimestamp
    val lastListen = trackDetails.lastPlayed ?: engagement?.lastPlayedTimestamp

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = tint,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.details_timeline_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Milestone 1: Discovery Date
                if (firstListen != null) {
                    MilestoneEntry(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.details_stat_first_played),
                        value = formatSongDate(firstListen),
                        subtext = if (engagement != null && engagement.daysSinceFirstPlay > 0) {
                            stringResource(R.string.details_days_ago, engagement.daysSinceFirstPlay)
                        } else null,
                        iconTint = tint,
                    )
                }

                // Milestone 2: Peak Record
                if (peakBingeDay != null && peakBingeDay.second > 1) {
                    MilestoneEntry(
                        icon = Icons.Rounded.LocalFireDepartment,
                        title = stringResource(R.string.details_timeline_peak_day),
                        value = stringResource(R.string.details_plays_count, peakBingeDay.second),
                        subtext = formatChartDate(peakBingeDay.first),
                        iconTint = tint,
                    )
                }

                // Milestone 3: Habitual Hour
                if (habitualHour != null) {
                    MilestoneEntry(
                        icon = Icons.Rounded.NightsStay,
                        title = stringResource(R.string.details_most_active),
                        value = habitualHour,
                        subtext = stringResource(R.string.details_timeline_peak_window),
                        iconTint = tint,
                    )
                }

                // Milestone 4: Latest Session
                if (lastListen != null) {
                    MilestoneEntry(
                        icon = Icons.Rounded.Headphones,
                        title = stringResource(R.string.details_timeline_recent),
                        value = formatSongDate(lastListen),
                        subtext = stringResource(R.string.details_timeline_latest),
                        iconTint = TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MilestoneEntry(
    icon: ImageVector,
    title: String,
    value: String,
    subtext: String?,
    iconTint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f))
                .border(0.8.dp, iconTint.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(Locale.getDefault()),
                style = KickerSmall,
                color = TextTertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
        }

        if (subtext != null) {
            Text(
                text = subtext,
                style = CaptionSmall,
                color = TextSecondary,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 7. Playback Breakdown
// ──────────────────────────────────────────────────────────────

@Composable
fun PlaybackFidelitySection(
    engagement: TrackEngagement,
    tint: Color = TempoPrimary,
) {
    val total = engagement.playCount.coerceAtLeast(1)
    val fullPlaysRatio = (engagement.fullPlaysCount.toFloat() / total).coerceIn(0f, 1f)
    val partialPlaysRatio = (engagement.partialPlaysCount.toFloat() / total).coerceIn(0f, 1f)
    val skipsRatio = (engagement.skipsCount.toFloat() / total).coerceIn(0f, 1f)

    val narrative = when {
        fullPlaysRatio >= 0.8f -> stringResource(R.string.details_playback_narrative_full)
        fullPlaysRatio >= 0.5f -> stringResource(R.string.details_playback_narrative_mixed)
        else -> stringResource(R.string.details_playback_narrative_skipped)
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = tint,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.details_playback_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = stringResource(R.string.details_playback_full_pct, (fullPlaysRatio * 100).roundToInt()),
                    style = CaptionSmall,
                    fontWeight = FontWeight.Medium,
                    color = TempoSuccess,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Segmented bar: one clipped rounded track with plain segment
            // rects inside — no per-segment corner seams between neighbors.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(GlassBorderSoft)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    if (fullPlaysRatio > 0f) {
                        drawRect(
                            color = TempoSuccess,
                            topLeft = Offset(0f, 0f),
                            size = Size(w * fullPlaysRatio, size.height),
                        )
                    }
                    if (partialPlaysRatio > 0f) {
                        drawRect(
                            color = TempoWarning,
                            topLeft = Offset(w * fullPlaysRatio, 0f),
                            size = Size(w * partialPlaysRatio, size.height),
                        )
                    }
                    if (skipsRatio > 0f) {
                        drawRect(
                            color = TempoError,
                            topLeft = Offset(w * (fullPlaysRatio + partialPlaysRatio), 0f),
                            size = Size(w * skipsRatio, size.height),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Legend with Typographical Ranges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FidelityLegendItem(
                    color = TempoSuccess,
                    label = stringResource(R.string.details_playback_full_label),
                    count = engagement.fullPlaysCount,
                )
                FidelityLegendItem(
                    color = TempoWarning,
                    label = stringResource(R.string.details_playback_partial_label),
                    count = engagement.partialPlaysCount,
                )
                FidelityLegendItem(
                    color = TempoError,
                    label = stringResource(R.string.details_playback_skipped_label),
                    count = engagement.skipsCount,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Narrative callout (clean flattened style without nested borders)
            Text(
                text = narrative,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Telemetry Data Points
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                TelemetryMiniCell(
                    label = stringResource(R.string.details_stat_replays),
                    value = "${engagement.replayCount}",
                    subtext = stringResource(R.string.details_telemetry_replays_sub),
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft)
                )

                TelemetryMiniCell(
                    label = stringResource(R.string.details_telemetry_pauses),
                    value = String.format(Locale.getDefault(), "%.1f", engagement.averagePauseCount),
                    subtext = stringResource(R.string.details_telemetry_pauses_sub),
                    modifier = Modifier.weight(1f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.8.dp)
                        .background(GlassBorderSoft)
                )

                TelemetryMiniCell(
                    label = stringResource(R.string.details_telemetry_sessions),
                    value = "${engagement.uniqueSessionsCount}",
                    subtext = stringResource(R.string.details_telemetry_sessions_sub),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FidelityLegendItem(
    color: Color,
    label: String,
    count: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = stringResource(R.string.details_playback_legend_fmt, label, count),
            style = CaptionSmall,
            color = TextTertiary,
        )
    }
}

@Composable
private fun TelemetryMiniCell(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = KickerSmall,
            color = TextTertiary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
            ),
            color = TextPrimary,
        )
        Text(
            text = subtext,
            style = CaptionSmall,
            color = TextTertiary,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 8. Streaming & Actions
// ──────────────────────────────────────────────────────────────

@Composable
fun StreamingHubSection(
    trackDetails: TrackDetails,
    spotifyUrl: String?,
    appleMusicUrl: String?,
    onShare: () -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        // Streaming buttons + their header only exist when there is somewhere
        // to deep-link; the share CTA stands alone otherwise.
        if (spotifyUrl != null || appleMusicUrl != null) {
            Text(
                text = stringResource(R.string.details_streaming_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (spotifyUrl != null) {
                    StreamingButton(
                        label = "Spotify",
                        icon = Icons.Rounded.GraphicEq,
                        tint = SpotifyGreen,
                        containerColor = SpotifyGreen.copy(alpha = 0.16f),
                        borderColor = SpotifyGreen.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUrl))
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.details_spotify_not_found), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                if (appleMusicUrl != null) {
                    StreamingButton(
                        label = "Apple Music",
                        icon = Icons.Rounded.Album,
                        tint = LastFmRed,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(appleMusicUrl))
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.details_apple_not_found), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Share Card Action — the screen's single solid-white primary CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(PillSurface)
                .premiumClickable(onClick = onShare, pressedScale = 0.98f)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = PillTextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.details_action_share_card),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = PillTextPrimary,
                    letterSpacing = 0.3.sp,
                )
            }
        }
    }
}

@Composable
private fun StreamingButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = TempoDarkSurfaceElevated,
    borderColor: Color = GlassBorderSoft,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(14.dp))
            .premiumClickable(onClick = onClick, pressedScale = 0.97f)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = Kicker,
                color = TextPrimary,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Meter Rule with Diamond Needle
// ──────────────────────────────────────────────────────────────

@Composable
private fun MeterRule(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated = rememberAnimatedFraction(fraction, delayMs = 100, durationMs = 600)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val midY = size.height / 2f
        drawLine(
            color = GlassBorderMedium,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (animated > 0f) {
            val endX = size.width * animated
            drawLine(
                color = color,
                start = Offset(0f, midY),
                end = Offset(endX, midY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            rotate(45f, pivot = Offset(endX, midY)) {
                drawRect(
                    color = color,
                    topLeft = Offset(endX - 3.5.dp.toPx(), midY - 3.5.dp.toPx()),
                    size = Size(7.dp.toPx(), 7.dp.toPx()),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Dialogs: Edit Title & Delete Song
// ──────────────────────────────────────────────────────────────

@Composable
private fun EditTitleDialog(
    currentTitle: String,
    isSaving: Boolean,
    error: String?,
    onClearWarnings: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var titleText by remember { mutableStateOf(currentTitle) }
    val trimmed = titleText.trim()
    val isUnchanged = trimmed == currentTitle
    val canSave = trimmed.isNotEmpty() && !isUnchanged && !isSaving

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = TempoDarkSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = null,
                tint = TempoPrimary,
            )
        },
        title = { Text(stringResource(R.string.details_edit_title_dialog_title), color = TextPrimary) },
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
                            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                            trimmed.isEmpty() -> Text(stringResource(R.string.details_edit_title_empty), color = MaterialTheme.colorScheme.error)
                            isUnchanged -> Text(stringResource(R.string.details_edit_title_unchanged), color = TextTertiary)
                            else -> Text("${titleText.length} / 200", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TempoPrimary,
                        unfocusedBorderColor = TextPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = TempoPrimary,
                        unfocusedLabelColor = TextPrimary.copy(alpha = 0.6f),
                        cursorColor = TempoPrimary,
                    ),
                )
            }
        },
        confirmButton = {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TempoPrimary)
            } else {
                TextButton(
                    onClick = { onSave(trimmed) },
                    enabled = canSave,
                ) {
                    Text(stringResource(R.string.common_save).uppercase(), color = if (canSave) TempoPrimary else TextTertiary)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.common_cancel), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun MergeConfirmDialog(
    target: me.avinas.tempo.data.local.entities.Track,
    isMerging: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isMerging) onDismiss() },
        containerColor = TempoDarkSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                Icons.AutoMirrored.Rounded.CallMerge,
                contentDescription = null,
                tint = TempoPrimary,
            )
        },
        title = { Text(stringResource(R.string.details_edit_title_merge_title), color = TextPrimary) },
        text = {
            Text(
                stringResource(
                    R.string.details_edit_title_merge_message,
                    target.title,
                    target.artist,
                ),
                color = TextSecondary,
            )
        },
        confirmButton = {
            if (isMerging) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TempoPrimary)
            } else {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.details_edit_title_merge_button).uppercase(), color = TempoPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isMerging,
            ) {
                Text(stringResource(R.string.common_cancel), color = TextSecondary)
            }
        },
    )
}

@Composable
private fun DeleteSongConfirmDialog(
    trackDetails: TrackDetails,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        containerColor = TempoDarkSurfaceElevated,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.details_delete_song_title), color = TextPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.details_delete_song_confirmation), color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "“${trackDetails.track.title}” by ${trackDetails.track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.details_delete_song_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.details_delete_song_bullet_library), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.details_delete_song_bullet_history, trackDetails.playCount), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.details_delete_song_bullet_metadata), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.details_delete_song_bullet_artist), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.details_delete_song_undone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TempoError)
            } else {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.history_delete_button).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(stringResource(R.string.common_cancel), color = TextSecondary)
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────
// Footer
// ──────────────────────────────────────────────────────────────

@Composable
private fun SongDetailsFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(1.dp)
                .background(GlassBorderSoft),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.details_footer).uppercase(Locale.getDefault()),
            style = KickerSmall,
            color = TextTertiary,
            letterSpacing = 2.sp,
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Helpers & Motion Primitives
// ──────────────────────────────────────────────────────────────

private fun formatSongDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatChartDate(isoDate: String): String {
    if (isoDate.isBlank()) return ""
    return try {
        java.time.LocalDate.parse(isoDate.take(10))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    } catch (_: Exception) {
        isoDate
    }
}

private fun parseIsoToTimestamp(isoDate: String): Long {
    return try {
        val localDate = java.time.LocalDate.parse(isoDate.take(10))
        localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}

fun formatReleaseDate(dateString: String?): String? {
    if (dateString.isNullOrBlank()) return null
    return try {
        val date = java.time.LocalDate.parse(dateString.take(10))
        java.time.format.DateTimeFormatter
            .ofPattern("MMMM d, yyyy")
            .format(date)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun rememberAnimatedFraction(
    target: Float,
    delayMs: Int = 0,
    durationMs: Int = 600,
): Float {
    if (rememberReducedMotion()) return target
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(target) { started = true }
    val fraction by animateFloatAsState(
        targetValue = if (started) target else 0f,
        animationSpec = tween(durationMillis = durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "fraction",
    )
    return fraction
}

/**
 * Keeps artwork-derived accents usable on dark glass: tames neon saturation
 * and lifts near-black swatches into a readable accent band. Grays keep their
 * hue (coercing saturation on a gray would fabricate a red tint).
 */
internal fun conditionedAccent(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.12f) {
        hsv[2] = hsv[2].coerceIn(0.55f, 0.80f)
    } else {
        hsv[1] = hsv[1].coerceIn(0.30f, 0.72f)
        hsv[2] = hsv[2].coerceIn(0.52f, 0.82f)
    }
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
private fun rememberDominantColor(trackDetails: TrackDetails): Color {
    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(TempoPrimary) }

    LaunchedEffect(trackDetails.track.albumArtUrl, trackDetails.localBackupArtUrl) {
        val url = if (!trackDetails.track.albumArtUrl.isNullOrBlank()) {
            MusicBrainzEnrichmentService.fixHttpUrl(trackDetails.track.albumArtUrl)
        } else {
            trackDetails.localBackupArtUrl
        }
        if (url.isNullOrBlank()) return@LaunchedEffect
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(64, 64)
                .build(),
        )
        var bitmap = (result.image as? BitmapImage)?.bitmap ?: return@LaunchedEffect
        if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        }
        Palette.from(bitmap).generate { palette ->
            val color = palette?.dominantSwatch?.rgb?.let { conditionedAccent(Color(it)) } ?: TempoPrimary
            dominantColor = color
        }
    }
    return dominantColor
}
