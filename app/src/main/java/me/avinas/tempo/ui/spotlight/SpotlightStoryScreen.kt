package me.avinas.tempo.ui.spotlight

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import android.util.Log
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.avinas.tempo.ui.components.CaptureController
import me.avinas.tempo.ui.components.CaptureWrapper
import me.avinas.tempo.ui.components.rememberCaptureController
import me.avinas.tempo.utils.ShareUtils
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Pause
import me.avinas.tempo.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotlightStoryScreen(
    storyPages: List<SpotlightStoryPage>,
    onClose: () -> Unit
) {
    // Freeze the page list for the whole playback session. The ViewModel regenerates
    // story pages reactively on every new listening event; if the live list were used,
    // a scrobble landing mid-story could change the page count (optional slides vary),
    // clamping the pager's current page and restarting the timer mid-playback.
    val storyPages = remember { storyPages }
    val pagerState = rememberPagerState(pageCount = { storyPages.size })
    val coroutineScope = rememberCoroutineScope()
    val currentProgress = remember { Animatable(0f) }
    var isPaused by remember { mutableStateOf(false) }
    
    // Audio Controller
    val context = LocalContext.current
    val audioController = remember { SpotlightAudioController(context = context, scope = coroutineScope) }
    val isMuted by audioController.isMuted.collectAsState()
    
    DisposableEffect(Unit) {
        onDispose { audioController.release() }
    }
    
    // Manage Audio Playback
    
    // Preload the first track immediately to reduce latency
    LaunchedEffect(Unit) {
        // Find the first page that has a preview URL (usually the Intro)
        val firstAudioPage = storyPages.firstOrNull { it.previewUrl != null }
        val preloadUrl = firstAudioPage?.previewUrl
        
        if (preloadUrl != null) {
            Log.d("SpotlightStoryScreen", "Preloading audio: $preloadUrl")
            audioController.prepare(preloadUrl)
        }
    }
    
    LaunchedEffect(pagerState.currentPage) {
            val page = storyPages.getOrNull(pagerState.currentPage)
            val previewUrl = page?.previewUrl
            
            Log.d("SpotlightStoryScreen", "Page: ${page?.id}, PreviewUrl: $previewUrl")
            
            if (previewUrl != null) {
                if (page is SpotlightStoryPage.TopTrackSetup) {
                    // Lower volume build-up for the top track reveal
                    audioController.playSetup(previewUrl)
                } else {
                    // Standard/High volume for all other pages (Intro, Highlight, Genres, etc.)
                    audioController.playHighlight(previewUrl)
                }
            } else {
                Log.d("SpotlightStoryScreen", "Page has NULL previewUrl, stopping audio")
                audioController.fadeOutAndStop()
            }
    }

    // Determine colors based on current page - Narrative Arc Color Story
    // Act 1 (Setup): Cool blues/purples - curiosity, introspection
    // Act 2 (Discoveries): Warm ambers/pinks - excitement, passion  
    // Act 3 (Climax): Bold reds/golds - revelation, celebration
    // Act 4 (Resolution): Serene teals/whites - reflection, peace
    val currentPage = storyPages.getOrNull(pagerState.currentPage)
    val (primaryColor, secondaryColor, tertiaryColor) = when (currentPage) {
        // ACT 1: SETUP - Cool, introspective blues/purples
        is SpotlightStoryPage.ListeningMinutes -> Triple(
            Color(0xFF6366F1), // Indigo - curiosity
            Color(0xFF818CF8), // Light Indigo
            Color(0xFF4F46E5)  // Deep Indigo
        )
        is SpotlightStoryPage.ListeningStreak -> Triple(
            Color(0xFF7C3AED), // Violet - building momentum
            Color(0xFF8B5CF6), // Light Violet
            Color(0xFF6D28D9)  // Deep Violet
        )
        is SpotlightStoryPage.ListeningClock -> Triple(
            Color(0xFF1E1B4B), // Deep Indigo (night)
            Color(0xFF3730A3), // Indigo
            Color(0xFF4338CA)  // Bright Indigo
        )
        
        // ACT 2: DISCOVERIES - Warm, exciting ambers/pinks
        is SpotlightStoryPage.TopArtist -> Triple(
            Color(0xFFEC4899), // Pink - passion
            Color(0xFFF472B6), // Light Pink
            Color(0xFFBE185D)  // Dark Pink
        )
        is SpotlightStoryPage.TopAlbum -> Triple(
            Color(0xFFD97706), // Amber - warmth
            Color(0xFFF59E0B), // Light Amber
            Color(0xFFB45309)  // Dark Amber
        )
        is SpotlightStoryPage.TopSongs -> Triple(
            Color(0xFFF59E0B), // Amber - energy
            Color(0xFFFBBF24), // Light Amber
            Color(0xFFD97706)  // Dark Amber
        )
        is SpotlightStoryPage.TopTrackSetup -> Triple(
            Color(0xFFFB923C), // Orange - anticipation
            Color(0xFFFDBA74), // Light Orange
            Color(0xFFEA580C)  // Dark Orange
        )
        is SpotlightStoryPage.TopGenres -> Triple(
            Color(0xFF10B981), // Emerald - exploration
            Color(0xFF34D399), // Light Emerald
            Color(0xFF059669)  // Dark Emerald
        )
        is SpotlightStoryPage.DiscoveryCount -> Triple(
            Color(0xFF06B6D4), // Cyan - discovery
            Color(0xFF22D3EE), // Light Cyan
            Color(0xFF0891B2)  // Dark Cyan
        )
        is SpotlightStoryPage.WeekdayVsWeekend -> Triple(
            Color(0xFF0284C7), // Sky blue - patterns
            Color(0xFF38BDF8), // Light Sky
            Color(0xFF0369A1)  // Dark Sky
        )
        is SpotlightStoryPage.BingeSession -> Triple(
            Color(0xFFE11D48), // Rose - intensity
            Color(0xFFFB7185), // Light Rose
            Color(0xFFBE123C)  // Dark Rose
        )
        is SpotlightStoryPage.TimeOfDayVibes -> Triple(
            Color(0xFF8B5CF6), // Violet - moods
            Color(0xFFA78BFA), // Light Violet
            Color(0xFF7C3AED)  // Dark Violet
        )
        
        // ACT 3: CLIMAX - Bold, celebratory reds/golds
        is SpotlightStoryPage.AudioMood -> Triple(
            Color(0xFFDC2626), // Red - emotional peak
            Color(0xFFEF4444), // Light Red
            Color(0xFFB91C1C)  // Dark Red
        )
        is SpotlightStoryPage.Personality -> Triple(
            Color(0xFFEAB308), // Gold - REVELATION
            Color(0xFFFDE047), // Light Gold
            Color(0xFFCA8A04)  // Dark Gold
        )
        is SpotlightStoryPage.BadgesEarned -> Triple(
            Color(0xFFD97706), // Amber - achievement
            Color(0xFFF59E0B), // Light Amber
            Color(0xFFB45309)  // Dark Amber
        )
        
        // ACT 4: RESOLUTION - Serene, reflective teals/whites
        is SpotlightStoryPage.LevelUp -> Triple(
            Color(0xFF14B8A6), // Teal - growth
            Color(0xFF2DD4BF), // Light Teal
            Color(0xFF0F766E)  // Dark Teal
        )
        is SpotlightStoryPage.TitleEarned -> Triple(
            Color(0xFF0D9488), // Dark Teal - identity
            Color(0xFF5EEAD4), // Light Teal
            Color(0xFF115E59)  // Deep Teal
        )
        is SpotlightStoryPage.Conclusion -> Triple(
            Color(0xFF64748B), // Slate - reflection
            Color(0xFF94A3B8), // Light Slate
            Color(0xFF475569)  // Dark Slate
        )
        null -> Triple(Color(0xFF6366F1), Color(0xFF818CF8), Color(0xFF4F46E5))
    }

    val captureController = rememberCaptureController()
    val captureRequested = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            val success = ShareUtils.shareBitmap(context, bitmap)
            if (!success) {
                android.widget.Toast.makeText(context, "Failed to share story", android.widget.Toast.LENGTH_SHORT).show()
            }
            captureRequested.value = false
        }
    }

    // When capture is requested, wait for the ShadowStoryRenderer's AndroidView to be
    // composed + laid out, then trigger the actual bitmap capture.
    LaunchedEffect(captureRequested.value) {
        if (captureRequested.value) {
            withFrameNanos { }
            withFrameNanos { }
            captureController.capture()
        }
    }

    // Tracks which page the current progress bar value belongs to. Progress must be
    // reset inside the auto-advance coroutine itself, not in a separate LaunchedEffect:
    // a sibling reset effect races this one over the Animatable's mutex, and if the
    // timer reads the previous page's near-1f progress before the snap lands, the
    // remaining duration collapses to ~0ms and the story skips pages instantly.
    val timerPageIndex = remember { mutableIntStateOf(-1) }

    // Tracks viewed pages so re-visits skip entry animations
    val seenPages = remember { SnapshotStateMap<String, Boolean>() }
    var lastViewedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != lastViewedIndex) {
            storyPages.getOrNull(lastViewedIndex)?.let { seenPages[it.id] = true }
            lastViewedIndex = pagerState.currentPage
        }
    }

    // Auto-advance logic with pause support
    LaunchedEffect(pagerState.currentPage, isPaused) {
        if (!isPaused) {
             if (timerPageIndex.value != pagerState.currentPage) {
                 currentProgress.snapTo(0f)
                 timerPageIndex.value = pagerState.currentPage
             }
             val remainingTime = (1f - currentProgress.value) * 15000
             if (remainingTime > 0) {
                 currentProgress.animateTo(
                     targetValue = 1f,
                     animationSpec = tween(durationMillis = remainingTime.toInt(), easing = LinearEasing)
                 )
                 if (pagerState.currentPage < storyPages.size - 1) {
                     // Use coroutineScope to ensure animation isn't cancelled when currentPage changes
                     coroutineScope.launch {
                         pagerState.animateScrollToPage(pagerState.currentPage + 1)
                     }
                 } else {
                     onClose()
                 }
             }
        }
    }

    // Main Content Box
    Box(modifier = Modifier.fillMaxSize()) {
        
        // 0. Shadow Renderer (For Sharing) - Hidden offscreen
        // Only composed when a capture is requested — avoids doubling render cost on every frame.
        // Renders the CURRENT page at exactly 1080x1920 px for consistent sharing.
        val shadowPage = storyPages.getOrNull(pagerState.currentPage)
        if (captureRequested.value && shadowPage != null) {
            ShadowStoryRenderer(
                page = shadowPage,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                tertiaryColor = tertiaryColor,
                captureController = captureController
            )
        }
        
        // 1. Visible Content (Background + Pager)
        // No CaptureWrapper here anymore
        SpotlightStoryBackground(
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            tertiaryColor = tertiaryColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false // Disable swipe to rely on taps
                ) { pageIndex ->
                    val page = storyPages[pageIndex]
                    Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalStorySeenPages provides seenPages,
                            LocalStoryPageId provides page.id
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (page) {
                                    is SpotlightStoryPage.ListeningMinutes -> ListeningMinutesPage(page)
                                    is SpotlightStoryPage.ListeningStreak -> ListeningStreakPage(page)
                                    is SpotlightStoryPage.ListeningClock -> ListeningClockPage(page)
                                    is SpotlightStoryPage.TopArtist -> TopArtistPage(page)
                                    is SpotlightStoryPage.TopAlbum -> TopAlbumPage(page)
                                    is SpotlightStoryPage.TopTrackSetup -> TopTrackSetupPage(page)
                                    is SpotlightStoryPage.TopSongs -> TopSongsPage(page)
                                    is SpotlightStoryPage.DiscoveryCount -> DiscoveryCountPage(page)
                                    is SpotlightStoryPage.AudioMood -> AudioMoodPage(page)
                                    is SpotlightStoryPage.WeekdayVsWeekend -> WeekdayVsWeekendPage(page)
                                    is SpotlightStoryPage.BingeSession -> BingeSessionPage(page)
                                    is SpotlightStoryPage.TimeOfDayVibes -> TimeOfDayVibesPage(page)
                                    is SpotlightStoryPage.BadgesEarned -> BadgesEarnedPage(page)
                                    is SpotlightStoryPage.LevelUp -> LevelUpPage(page)
                                    is SpotlightStoryPage.TitleEarned -> TitleEarnedPage(page)
                                    is SpotlightStoryPage.TopGenres -> TopGenresPage(page)
                                    is SpotlightStoryPage.Personality -> PersonalityPage(page)
                                    is SpotlightStoryPage.Conclusion -> ConclusionPage(page)
                                }
                            }
                        }
                    }
                }
                
                // Watermark (Visible on screen too, optional but keeps consistency)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Tempo Spotlight",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                blurRadius = 4f
                            )
                        ),
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // 2. Gesture Overlay (Interactive layer, NOT captured)
        // Placed ABOVE Content to intercept touches throughout the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPaused = true
                            tryAwaitRelease()
                            isPaused = false
                        },
                        onTap = { offset ->
                            val width = size.width
                            coroutineScope.launch {
                                if (offset.x < width * 0.3f) {
                                    // Previous
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                } else {
                                    // Next
                                    if (pagerState.currentPage < storyPages.size - 1) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    } else {
                                        onClose()
                                    }
                                }
                            }
                        }
                    )
                }
        )

        // 3. UI Overlays (Top Bar, Share Button, etc.)

        // Visual indicator shown during hold-to-pause
        AnimatedVisibility(
            visible = isPaused,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.spotlight_paused),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        
        // Top Bar (Progress & Close)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            // Progress Indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                storyPages.forEachIndexed { index, _ ->
                    val progress = when {
                        index < pagerState.currentPage -> 1f
                        index == pagerState.currentPage -> currentProgress.value
                        else -> 0f
                    }
                    // Dim upcoming segments and brighten watched ones
                    val trackAlpha = when {
                        index < pagerState.currentPage -> 0.5f
                        index == pagerState.currentPage -> 0.3f
                        else -> 0.16f
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = trackAlpha))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Top Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute Button
                IconButton(
                    onClick = { audioController.toggleMute() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        // Share Button (Bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp) // Moved up slightly to avoid overlapping watermark if visible
        ) {
             Button(
                onClick = { if (!captureRequested.value) captureRequested.value = true },
                enabled = !captureRequested.value,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.spotlight_share_your_story),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Loading overlay while preparing share image
        if (captureRequested.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.spotlight_preparing),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ShadowStoryRenderer(
    page: SpotlightStoryPage,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    captureController: CaptureController
) {
    // 1. Force Density for consistent scale (Pixel 2 XL density approx)
    val targetDensity = androidx.compose.ui.unit.Density(2.75f)
    
    // 2. Hide offscreen (safe culling avoidance)
    Box(
        modifier = Modifier
            .offset(x = 10000.dp)
            .wrapContentSize(unbounded = true)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides targetDensity
        ) {
            // 3. Custom Layout to force 1080x1920 measurement
            androidx.compose.ui.layout.Layout(
                content = {
                    CaptureWrapper(
                        controller = captureController,
                        modifier = Modifier.size(392.dp, 698.dp) // 1080px/2.75, 1920px/2.75 (Must match desired pixel / density)
                    ) {
                         SpotlightStoryBackground(
                            primaryColor = primaryColor,
                            secondaryColor = secondaryColor,
                            tertiaryColor = tertiaryColor
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (page) {
                                    is SpotlightStoryPage.ListeningMinutes -> ListeningMinutesPage(page)
                                    is SpotlightStoryPage.ListeningStreak -> ListeningStreakPage(page)
                                    is SpotlightStoryPage.ListeningClock -> ListeningClockPage(page)
                                    is SpotlightStoryPage.TopArtist -> TopArtistPage(page)
                                    is SpotlightStoryPage.TopAlbum -> TopAlbumPage(page)
                                    is SpotlightStoryPage.TopTrackSetup -> TopTrackSetupPage(page)
                                    is SpotlightStoryPage.TopSongs -> TopSongsPage(page)
                                    is SpotlightStoryPage.DiscoveryCount -> DiscoveryCountPage(page)
                                    is SpotlightStoryPage.AudioMood -> AudioMoodPage(page)
                                    is SpotlightStoryPage.WeekdayVsWeekend -> WeekdayVsWeekendPage(page)
                                    is SpotlightStoryPage.BingeSession -> BingeSessionPage(page)
                                    is SpotlightStoryPage.TimeOfDayVibes -> TimeOfDayVibesPage(page)
                                    is SpotlightStoryPage.BadgesEarned -> BadgesEarnedPage(page)
                                    is SpotlightStoryPage.LevelUp -> LevelUpPage(page)
                                    is SpotlightStoryPage.TitleEarned -> TitleEarnedPage(page)
                                    is SpotlightStoryPage.TopGenres -> TopGenresPage(page)
                                    is SpotlightStoryPage.Personality -> PersonalityPage(page)
                                    is SpotlightStoryPage.Conclusion -> ConclusionPage(page)
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 24.dp)
                                ) {
                                    Text(
                                        text = "Tempo Spotlight",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                                blurRadius = 4f
                                            )
                                        ),
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            ) { measurables, _ ->
                // Force measure to exactly 1080x1920 pixels
                // We derived the Dp size above (392x698) based on density 2.75
                // 392 * 2.75 = 1078. 698 * 2.75 = 1919.5. Close enough.
                // Better: rely on fixed Pixel size if possible? 
                // CaptureWrapper captures the View. The View size is determined by the Compose layout.
                // If we specify Modifier.size(392.72.dp, 698.18.dp), we get 1080x1920.
                
                val widthPx = 1080
                val heightPx = 1920
                
                val placeable = measurables[0].measure(
                     androidx.compose.ui.unit.Constraints.fixed(widthPx, heightPx)
                )
                
                layout(widthPx, heightPx) {
                    placeable.place(0, 0)
                }
            }
        }
    }
}
