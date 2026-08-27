package me.avinas.tempo.ui.details

/* Tempo · ArtistDetails · Listening Profile.
 * Mirrors the SongDetails editorial system:
 * - Frosted header with collapsed title on scroll + gradient scrim
 * - Dominant-color accent extracted from the artist image
 * - ArtAtmosphere room layer, skeleton loading, hairline stat masthead
 * - Design tokens (TextPrimary/Secondary/Tertiary, KickerSmall, DisplayFontFamily)
 * - Zero emojis, 100% vector icons
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ArtistDetails
import me.avinas.tempo.data.stats.TopAlbum
import me.avinas.tempo.data.stats.TopTrack
import me.avinas.tempo.ui.components.ArtAtmosphereLayer
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SharePreviewDialog
import me.avinas.tempo.ui.components.ArtistShareCard
import me.avinas.tempo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
            ArtistDetailsLoadingSkeleton()
        } else if (artistDetails != null) {
            ArtistDetailsContent(
                artistDetails = artistDetails,
                uiState = uiState,
                onNavigateBack = onNavigateBack,
                onNavigateToSong = onNavigateToSong,
                onRefreshImage = { viewModel.refreshArtistImage() },
                onShowRenameDialog = { viewModel.showRenameDialog() },
                onShowSplitDialog = { viewModel.showSplitDialog() }
            )
        } else {
            // Error state with retry
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = TempoError.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = uiState.error ?: stringResource(R.string.details_artist_not_found),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = { viewModel.retry() },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.8.dp, GlassBorderMedium),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    ) {
                        Text(stringResource(R.string.common_retry).uppercase(Locale.getDefault()))
                    }
                }
            }
        }
    }

    // Rename dialog lives outside the loading/error branches
    val details = artistDetails
    if (details != null && uiState.showRenameDialog) {
        ArtistRenameDialog(
            currentName = details.artist.name,
            artistId = details.artist.id,
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

    // Split-artist dialog (manual escape hatch for wrongly merged/collapsed artists)
    if (details != null && uiState.showSplitDialog) {
        val splitSourceId = details.artist.id
        ArtistSplitDialog(
            sourceArtistId = splitSourceId,
            sourceArtistName = details.artist.name,
            onDismiss = { viewModel.dismissSplitDialog() },
            onSplitComplete = { sourceDeleted ->
                viewModel.dismissSplitDialog()
                if (sourceDeleted) {
                    // The artist no longer exists — leave the details screen
                    onNavigateBack()
                } else {
                    // Reload to reflect the tracks that were moved out
                    viewModel.loadArtistById(splitSourceId)
                }
            }
        )
    }
}

@Composable
private fun ArtistDetailsLoadingSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(modifier = Modifier.size(232.dp), cornerRadius = 116.dp)
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

@Composable
fun ArtistDetailsContent(
    artistDetails: ArtistDetails,
    uiState: ArtistDetailsUiState,
    onNavigateBack: () -> Unit,
    onNavigateToSong: (Long) -> Unit,
    onRefreshImage: () -> Unit,
    onShowRenameDialog: () -> Unit = {},
    onShowSplitDialog: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    val accent = rememberArtistAccentColor(artistDetails.artist.imageUrl)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Collapsed header title appears when the hero name scrolls under the
    // top bar — anchored to its live position, not a scroll-pixel guess.
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

    Box(modifier = Modifier.fillMaxSize()) {
        // The room recolors per artist: blurred image wash behind all content.
        ArtAtmosphereLayer(
            artUrl = artistDetails.artist.imageUrl?.takeIf { it.isNotBlank() },
            tint = accent,
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
            // 1. Hero Stage
            item(key = "hero_section") {
                ArtistHeroSection(
                    artistDetails = artistDetails,
                    accent = accent,
                    onRefreshImage = onRefreshImage,
                    isRefreshingImage = uiState.isRefreshingImage,
                    onShowRenameDialog = onShowRenameDialog,
                    onNamePositioned = { top ->
                        val collapsed = top <= headerBottomPx
                        if (collapsed != showCollapsedTitle) {
                            showCollapsedTitle = collapsed
                        }
                    },
                )
            }

            // 2. Master Stats Masthead
            item(key = "stats_grid") {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ArtistStatMasthead(artistDetails = artistDetails, accent = accent)
                }
            }

            // 3. Fan Standing
            item(key = "fan_status") {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                    FanStandingCard(
                        playCount = artistDetails.personalPlayCount,
                        percentile = uiState.artistPercentile,
                        accent = accent,
                    )
                }
            }

            // 4. Listening Journey & Milestones
            item(key = "listening_journey") {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                    ListeningJourneySection(artistDetails = artistDetails, tint = accent)
                }
            }

            // 5. Top Songs
            if (artistDetails.topSongs.isNotEmpty()) {
                item(key = "top_songs_section") {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
                        TopSongsPanel(
                            songs = artistDetails.topSongs.take(5),
                            onNavigateToSong = onNavigateToSong,
                            accent = accent,
                        )
                    }
                }
            }

            // 6. Top Albums
            if (artistDetails.topAlbums.isNotEmpty()) {
                item(key = "top_albums_header") {
                    Text(
                        text = stringResource(R.string.details_top_albums),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)
                    )
                }

                item(key = "top_albums_row") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        itemsIndexed(
                            items = artistDetails.topAlbums,
                            key = { index, album -> "album_${index}_${album.album}" },
                            contentType = { _, _ -> "album" }
                        ) { _, album ->
                            TopAlbumCard(album = album, accent = accent)
                        }
                    }
                }
            }

            // 7. Footer
            item(key = "footer") {
                Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp)) {
                    ArtistDetailsFooter()
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
        ArtistTopBarNavigation(
            showCollapsedTitle = showCollapsedTitle,
            title = artistDetails.artist.name,
            subtitle = artistDetails.topGenres.firstOrNull()
                ?: artistDetails.artist.genres.firstOrNull(),
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
                    text = { Text(stringResource(R.string.details_merge_with), color = TextPrimary) },
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
                    text = { Text(stringResource(R.string.details_rename_artist), color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = TextPrimary,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onShowRenameDialog()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.details_split_artist), color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.CallSplit,
                            contentDescription = null,
                            tint = TextPrimary,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onShowSplitDialog()
                    },
                )
            }
        }

        if (showShareDialog) {
            SharePreviewDialog(
                onDismiss = { showShareDialog = false },
                contentToShare = { theme ->
                    ArtistShareCard(artistDetails = artistDetails, percentile = uiState.artistPercentile, theme = theme)
                }
            )
        }

        // Artist Merge Dialog
        if (showMergeDialog) {
            ArtistMergeSearchDialog(
                sourceArtistId = artistDetails.artist.id,
                sourceArtistName = artistDetails.artist.name,
                onDismiss = { showMergeDialog = false },
                onMergeComplete = {
                    onNavigateBack()
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Top Bar Navigation with Collapsible Title
// ──────────────────────────────────────────────────────────────

@Composable
private fun ArtistTopBarNavigation(
    showCollapsedTitle: Boolean,
    title: String,
    subtitle: String?,
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
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.details_action_back),
            onClick = onNavigateBack,
        )

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
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
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
                contentDescription = stringResource(R.string.share_content_description),
                onClick = onShare,
            )
            FrostedHeaderButton(
                icon = Icons.Default.MoreVert,
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
// 1. Hero Stage
// ──────────────────────────────────────────────────────────────

@Composable
fun ArtistHeroSection(
    artistDetails: ArtistDetails,
    accent: Color = TempoPrimary,
    onRefreshImage: () -> Unit,
    isRefreshingImage: Boolean = false,
    onShowRenameDialog: () -> Unit = {},
    onNamePositioned: (Float) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Soft accent halo — ties the extracted tint to the hero and gives
        // the portrait depth beyond its border.
        val glowBrush = remember(accent) {
            Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.20f),
                    accent.copy(alpha = 0.0f),
                )
            )
        }

        Box(
            modifier = Modifier.size(248.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = glowBrush, shape = CircleShape)
            )

            Box(
                modifier = Modifier.size(232.dp),
                contentAlignment = Alignment.Center,
            ) {
                val imageUrl = artistDetails.artist.imageUrl
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(
                            elevation = 22.dp,
                            shape = CircleShape,
                            ambientColor = GlassShadowTeal,
                            spotColor = accent.copy(alpha = 0.22f),
                        )
                        .clip(CircleShape)
                        .background(TempoDarkSurfaceSunken)
                        .border(1.dp, GlassBorderStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (imageUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.20f))
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = artistDetails.artist.name.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontFamily = DisplayFontFamily,
                                ),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        }
                    } else {
                        CachedAsyncImage(
                            imageUrl = imageUrl,
                            contentDescription = artistDetails.artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            targetSizeDp = 232,
                        )
                    }
                }

                // Refresh image control (bottom-right overlay on the image)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(36.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.45f),
                            spotColor = Color.Black.copy(alpha = 0.55f),
                        )
                        .clip(CircleShape)
                        .background(TempoDarkSurfaceElevated.copy(alpha = 0.90f))
                        .border(1.dp, GlassBorderStrong, CircleShape)
                        .premiumClickable(onClick = onRefreshImage),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRefreshingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.details_refresh_artist_image),
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Name and Rename
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { coordinates ->
                    onNamePositioned(coordinates.boundsInWindow().top)
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artistDetails.artist.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = DisplayFontFamily,
                    letterSpacing = (-0.5).sp,
                ),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GlassFrostSoft)
                    .border(0.6.dp, GlassBorderSoft, CircleShape)
                    .premiumClickable(onClick = onShowRenameDialog),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.details_rename_artist),
                    tint = TextTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // Country & Genres metadata line
        val country = artistDetails.country
        val genresList = artistDetails.topGenres.ifEmpty { artistDetails.artist.genres }
        val metaParts = listOfNotNull(
            country?.takeIf { it.isNotBlank() },
            genresList.take(3)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } },
        )
        if (metaParts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = metaParts.joinToString("   ·   "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

// ──────────────────────────────────────────────────────────────
// 2. Master Stats Masthead
// ──────────────────────────────────────────────────────────────

@Composable
fun ArtistStatMasthead(artistDetails: ArtistDetails, accent: Color = TempoPrimary) {
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
                EditorialStatBlock(
                    label = stringResource(R.string.details_total_plays),
                    value = formatCount(artistDetails.personalPlayCount.toLong()),
                    subtext = stringResource(R.string.details_stat_recorded_library),
                    accentTint = accent,
                    modifier = Modifier.weight(1f),
                )

                HairlineDividerVertical()

                EditorialStatBlock(
                    label = stringResource(R.string.details_listening_time),
                    value = formatListeningTime(artistDetails.personalTotalTimeMs.toLong()),
                    subtext = stringResource(R.string.details_stat_total_recorded),
                    accentTint = accent,
                    modifier = Modifier.weight(1f),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(GlassBorderSoft),
            )

            // Row 2: Unique Songs & Albums
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialStatBlock(
                    label = stringResource(R.string.details_unique_tracks),
                    value = formatCount(artistDetails.uniqueTracksPlayed.toLong()),
                    subtext = stringResource(R.string.details_stat_recorded_library),
                    accentTint = accent,
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                )

                HairlineDividerVertical()

                EditorialStatBlock(
                    label = stringResource(R.string.details_unique_albums),
                    value = formatCount(artistDetails.uniqueAlbumsPlayed.toLong()),
                    subtext = stringResource(R.string.details_stat_total_recorded),
                    accentTint = accent,
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HairlineDividerVertical() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(0.8.dp)
            .background(GlassBorderSoft),
    )
}

// ──────────────────────────────────────────────────────────────
// 3. Fan Standing
// ──────────────────────────────────────────────────────────────

@Composable
fun FanStandingCard(
    playCount: Int,
    percentile: Double? = null,
    accent: Color = TempoPrimary,
) {
    val tier = if (percentile != null) {
        when {
            percentile <= 1.0 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_top_1),
                description = stringResource(R.string.fan_status_top_1_desc),
                icon = Icons.Rounded.AutoAwesome,
                pastelBackground = Color(0xFFFEF3C7),
                pastelBorder = Color(0xFFFDE68A),
                iconBackground = Color(0xFFFDE68A),
                iconBorder = Color(0xFFF59E0B).copy(alpha = 0.35f),
                iconTint = Color(0xFFB45309),
                titleColor = Color(0xFF92400E),
                descColor = Color(0xFF78350F).copy(alpha = 0.88f),
            )
            percentile <= 5.0 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_top_5),
                description = stringResource(R.string.fan_status_top_5_desc),
                icon = Icons.Rounded.AutoAwesome,
                pastelBackground = Color(0xFFFFEDD5),
                pastelBorder = Color(0xFFFED7AA),
                iconBackground = Color(0xFFFED7AA),
                iconBorder = Color(0xFFEA580C).copy(alpha = 0.35f),
                iconTint = Color(0xFFEA580C),
                titleColor = Color(0xFF9A3412),
                descColor = Color(0xFF7C2D12).copy(alpha = 0.88f),
            )
            percentile <= 10.0 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_top_10),
                description = stringResource(R.string.fan_status_top_10_desc),
                icon = Icons.Rounded.LocalFireDepartment,
                pastelBackground = Color(0xFFFFE4E6),
                pastelBorder = Color(0xFFFECDD3),
                iconBackground = Color(0xFFFECDD3),
                iconBorder = Color(0xFFE11D48).copy(alpha = 0.35f),
                iconTint = Color(0xFFE11D48),
                titleColor = Color(0xFF9F1239),
                descColor = Color(0xFF881337).copy(alpha = 0.88f),
            )
            percentile <= 25.0 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_top_25),
                description = stringResource(R.string.fan_status_top_25_desc),
                icon = Icons.Rounded.Headphones,
                pastelBackground = Color(0xFFE0F2FE),
                pastelBorder = Color(0xFFBAE6FD),
                iconBackground = Color(0xFFBAE6FD),
                iconBorder = Color(0xFF0284C7).copy(alpha = 0.35f),
                iconTint = Color(0xFF0284C7),
                titleColor = Color(0xFF075985),
                descColor = Color(0xFF0C4A6E).copy(alpha = 0.88f),
            )
            percentile <= 50.0 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_top_50),
                description = stringResource(R.string.fan_status_top_50_desc),
                icon = Icons.Rounded.MusicNote,
                pastelBackground = Color(0xFFCCFBF1),
                pastelBorder = Color(0xFF99F6E4),
                iconBackground = Color(0xFF99F6E4),
                iconBorder = Color(0xFF0D9488).copy(alpha = 0.35f),
                iconTint = Color(0xFF0D9488),
                titleColor = Color(0xFF115E59),
                descColor = Color(0xFF134E4A).copy(alpha = 0.88f),
            )
            else -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_listener),
                description = stringResource(R.string.fan_status_listener_desc),
                icon = Icons.Rounded.MusicNote,
                pastelBackground = Color(0xFFF1F5F9),
                pastelBorder = Color(0xFFE2E8F0),
                iconBackground = Color(0xFFE2E8F0),
                iconBorder = Color(0xFF94A3B8).copy(alpha = 0.35f),
                iconTint = Color(0xFF475569),
                titleColor = Color(0xFF334155),
                descColor = Color(0xFF475569).copy(alpha = 0.88f),
            )
        }
    } else {
        when {
            playCount > 1000 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_ultimate),
                description = stringResource(R.string.fan_status_ultimate_desc),
                icon = Icons.Rounded.AutoAwesome,
                pastelBackground = Color(0xFFFEF3C7),
                pastelBorder = Color(0xFFFDE68A),
                iconBackground = Color(0xFFFDE68A),
                iconBorder = Color(0xFFF59E0B).copy(alpha = 0.35f),
                iconTint = Color(0xFFB45309),
                titleColor = Color(0xFF92400E),
                descColor = Color(0xFF78350F).copy(alpha = 0.88f),
            )
            playCount > 500 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_super),
                description = stringResource(R.string.fan_status_super_desc),
                icon = Icons.Rounded.AutoAwesome,
                pastelBackground = Color(0xFFFFEDD5),
                pastelBorder = Color(0xFFFED7AA),
                iconBackground = Color(0xFFFED7AA),
                iconBorder = Color(0xFFEA580C).copy(alpha = 0.35f),
                iconTint = Color(0xFFEA580C),
                titleColor = Color(0xFF9A3412),
                descColor = Color(0xFF7C2D12).copy(alpha = 0.88f),
            )
            playCount > 200 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_big),
                description = stringResource(R.string.fan_status_big_desc),
                icon = Icons.Rounded.LocalFireDepartment,
                pastelBackground = Color(0xFFFFE4E6),
                pastelBorder = Color(0xFFFECDD3),
                iconBackground = Color(0xFFFECDD3),
                iconBorder = Color(0xFFE11D48).copy(alpha = 0.35f),
                iconTint = Color(0xFFE11D48),
                titleColor = Color(0xFF9F1239),
                descColor = Color(0xFF881337).copy(alpha = 0.88f),
            )
            playCount > 50 -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_regular),
                description = stringResource(R.string.fan_status_regular_desc),
                icon = Icons.Rounded.Headphones,
                pastelBackground = Color(0xFFE0F2FE),
                pastelBorder = Color(0xFFBAE6FD),
                iconBackground = Color(0xFFBAE6FD),
                iconBorder = Color(0xFF0284C7).copy(alpha = 0.35f),
                iconTint = Color(0xFF0284C7),
                titleColor = Color(0xFF075985),
                descColor = Color(0xFF0C4A6E).copy(alpha = 0.88f),
            )
            else -> FanStandingTierStyle(
                status = stringResource(R.string.fan_status_listener),
                description = stringResource(R.string.fan_status_listener_desc),
                icon = Icons.Rounded.MusicNote,
                pastelBackground = Color(0xFFF1F5F9),
                pastelBorder = Color(0xFFE2E8F0),
                iconBackground = Color(0xFFE2E8F0),
                iconBorder = Color(0xFF94A3B8).copy(alpha = 0.35f),
                iconTint = Color(0xFF475569),
                titleColor = Color(0xFF334155),
                descColor = Color(0xFF475569).copy(alpha = 0.88f),
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = tier.pastelBackground,
        border = BorderStroke(1.dp, tier.pastelBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tier.iconBackground)
                    .border(0.8.dp, tier.iconBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tier.icon,
                    contentDescription = null,
                    tint = tier.iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tier.status.uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    color = tier.titleColor,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tier.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tier.descColor,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

private data class FanStandingTierStyle(
    val status: String,
    val description: String,
    val icon: ImageVector,
    val pastelBackground: Color,
    val pastelBorder: Color,
    val iconBackground: Color,
    val iconBorder: Color,
    val iconTint: Color,
    val titleColor: Color,
    val descColor: Color,
)

// ──────────────────────────────────────────────────────────────
// 4. Listening Journey & Milestones
// ──────────────────────────────────────────────────────────────

@Composable
fun ListeningJourneySection(artistDetails: ArtistDetails, tint: Color = TempoPrimary) {
    val firstListen = artistDetails.firstListenedDate ?: artistDetails.firstDiscovery?.firstListenTimestamp
    val lastListen = artistDetails.lastListenedDate

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
                // Milestone 1: Discovery
                if (firstListen != null) {
                    MilestoneEntry(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.details_first_listen),
                        value = formatDate(firstListen),
                        subtext = null,
                        iconTint = tint,
                    )
                }

                // Milestone 2: Listening streak
                if (artistDetails.listeningStreakDays > 1) {
                    MilestoneEntry(
                        icon = Icons.Rounded.LocalFireDepartment,
                        title = stringResource(R.string.details_streak_days),
                        value = stringResource(R.string.details_trends_days, artistDetails.listeningStreakDays),
                        subtext = stringResource(R.string.details_streak_desc),
                        iconTint = tint,
                    )
                }

                // Milestone 3: Peak listening hour
                if (artistDetails.peakListeningHour != null) {
                    val isDay = artistDetails.peakListeningHour in 6..17
                    MilestoneEntry(
                        icon = if (isDay) Icons.Rounded.WbSunny else Icons.Rounded.NightsStay,
                        title = stringResource(R.string.details_peak_hour),
                        value = artistDetails.peakHourFormatted,
                        subtext = stringResource(R.string.details_most_active) + " " +
                            if (isDay) {
                                stringResource(R.string.details_streak_day_window)
                            } else {
                                stringResource(R.string.details_streak_night_window)
                            },
                        iconTint = tint,
                    )
                }

                // Milestone 4: Latest session
                if (lastListen != null) {
                    MilestoneEntry(
                        icon = Icons.Rounded.Headphones,
                        title = stringResource(R.string.details_last_listen),
                        value = formatDate(lastListen),
                        subtext = stringResource(R.string.details_timeline_latest),
                        iconTint = TextTertiary,
                    )
                }
            }

            // Discovery narrative
            if (artistDetails.firstDiscovery != null && firstListen != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.details_journey_discovered_prefix))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                            append(artistDetails.artist.name)
                        }
                        append(" ")
                        append(stringResource(R.string.details_journey_discovered_since))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                            append(formatListeningTime(artistDetails.personalTotalTimeMs))
                        }
                        append(" ")
                        append(stringResource(R.string.details_journey_across))
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                            append("${artistDetails.uniqueTracksPlayed}")
                        }
                        append(" ")
                        append(stringResource(R.string.details_journey_songs))
                        append(".")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 19.sp,
                )
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
// 5. Top Songs
// ──────────────────────────────────────────────────────────────

@Composable
fun TopSongsPanel(
    songs: List<TopTrack>,
    onNavigateToSong: (Long) -> Unit,
    accent: Color = TempoPrimary,
) {
    if (songs.isEmpty()) return

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        variant = GlassCardVariant.QuietGlass,
        accentColor = accent,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.details_top_songs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            songs.forEachIndexed { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .premiumClickable(onClick = { onNavigateToSong(song.trackId) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.width(30.dp),
                        textAlign = TextAlign.Start
                    )

                    CachedAsyncImage(
                        imageUrl = song.albumArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = song.album ?: stringResource(R.string.details_unknown_album),
                            style = CaptionSmall,
                            color = TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp),
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${song.playCount}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = DisplayFontFamily,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.details_plays).uppercase(Locale.getDefault()),
                            style = CaptionSmall,
                            color = TextTertiary,
                        )
                    }
                }

                if (index < songs.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .fillMaxWidth()
                            .height(0.6.dp)
                            .background(GlassBorderSoft)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 6. Top Albums
// ──────────────────────────────────────────────────────────────

@Composable
fun TopAlbumCard(album: TopAlbum, accent: Color = TempoPrimary) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .padding(bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = GlassShadowTeal,
                    spotColor = accent.copy(alpha = 0.18f),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(TempoDarkSurfaceSunken)
                .border(0.8.dp, GlassBorderSoft, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (album.albumArtUrl.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = TextTertiary
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

        Text(
            text = album.album,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.details_plays_count, album.playCount),
            style = CaptionSmall,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Accent color extraction
// ──────────────────────────────────────────────────────────────

@Composable
private fun rememberArtistAccentColor(imageUrl: String?): Color {
    var accent by remember { mutableStateOf(TempoPrimary) }
    val context = LocalContext.current

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl)
                .size(64, 64)
                .build(),
        )
        var bitmap = (result.image as? BitmapImage)?.bitmap ?: return@LaunchedEffect
        if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        }
        Palette.from(bitmap).generate { palette ->
            val color = palette?.let {
                it.vibrantSwatch?.rgb
                    ?: it.mutedSwatch?.rgb
                    ?: it.dominantSwatch?.rgb
            }?.let { conditionedAccent(Color(it)) } ?: TempoPrimary
            accent = color
        }
    }
    return accent
}

// ──────────────────────────────────────────────────────────────
// Formatting helpers (formatListeningTime is used by other screens)
// ──────────────────────────────────────────────────────────────

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

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ──────────────────────────────────────────────────────────────
// Footer
// ──────────────────────────────────────────────────────────────

@Composable
private fun ArtistDetailsFooter() {
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

