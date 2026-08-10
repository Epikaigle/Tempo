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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoAccentBright
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorAlt
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryDeep
import me.avinas.tempo.ui.theme.TempoPrimaryDim
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSky
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TempoWarningDeep
import me.avinas.tempo.ui.theme.TempoWarningSoft
import me.avinas.tempo.ui.theme.TextQuaternary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotlightStoryScreen(
    storyPages: List<SpotlightStoryPage>,
    onClose: () -> Unit
) {
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
            TempoPrimaryMuted, // Indigo - curiosity
            TempoAccent, // Light Indigo
            TempoInfo  // Deep Indigo
        )
        is SpotlightStoryPage.ListeningStreak -> Triple(
            TempoPrimaryMuted, // Violet - building momentum
            TempoPrimary, // Light Violet
            TempoPrimaryDim  // Deep Violet
        )
        is SpotlightStoryPage.ListeningClock -> Triple(
            TempoSurfaceDialog, // Deep Indigo (night)
            TempoInfo, // Indigo
            TempoInfo  // Bright Indigo
        )
        
        // ACT 2: DISCOVERIES - Warm, exciting ambers/pinks
        is SpotlightStoryPage.TopArtist -> Triple(
            TempoPrimary, // Pink - passion
            TempoErrorAlt, // Light Pink
            TempoErrorDeep  // Dark Pink
        )
        is SpotlightStoryPage.TopAlbum -> Triple(
            TempoWarningDeep, // Amber - warmth
            TempoWarning, // Light Amber
            TempoWarningDeep  // Dark Amber
        )
        is SpotlightStoryPage.TopSongs -> Triple(
            TempoWarning, // Amber - energy
            TempoWarningBright, // Light Amber
            TempoWarningDeep  // Dark Amber
        )
        is SpotlightStoryPage.TopTrackSetup -> Triple(
            TempoWarning, // Orange - anticipation
            TempoWarningSoft, // Light Orange
            TempoWarningDeep  // Dark Orange
        )
        is SpotlightStoryPage.TopGenres -> Triple(
            TempoSuccessDeep, // Emerald - exploration
            TempoSuccessDeep, // Light Emerald
            TempoSuccessDeep  // Dark Emerald
        )
        is SpotlightStoryPage.DiscoveryCount -> Triple(
            TempoCyan, // Cyan - discovery
            TempoCyan, // Light Cyan
            TempoCyan  // Dark Cyan
        )
        is SpotlightStoryPage.WeekdayVsWeekend -> Triple(
            TempoSky, // Sky blue - patterns
            TempoSky, // Light Sky
            TempoSky  // Dark Sky
        )
        is SpotlightStoryPage.BingeSession -> Triple(
            TempoErrorAlt, // Rose - intensity
            TempoErrorAlt, // Light Rose
            TempoErrorDeep  // Dark Rose
        )
        is SpotlightStoryPage.TimeOfDayVibes -> Triple(
            TempoPrimary, // Violet - moods
            TempoAccentBright, // Light Violet
            TempoPrimaryMuted  // Dark Violet
        )
        
        // ACT 3: CLIMAX - Bold, celebratory reds/golds
        is SpotlightStoryPage.AudioMood -> Triple(
            TempoError, // Red - emotional peak
            TempoError, // Light Red
            TempoErrorDeep  // Dark Red
        )
        is SpotlightStoryPage.Personality -> Triple(
            TempoWarningDeep, // Gold - REVELATION
            TempoWarningBright, // Light Gold
            TempoWarningDeep  // Dark Gold
        )
        is SpotlightStoryPage.BadgesEarned -> Triple(
            TempoWarningDeep, // Amber - achievement
            TempoWarning, // Light Amber
            TempoWarningDeep  // Dark Amber
        )
        
        // ACT 4: RESOLUTION - Serene, reflective teals/whites
        is SpotlightStoryPage.LevelUp -> Triple(
            TempoPrimary, // Teal - growth
            TempoAccent, // Light Teal
            TempoPrimaryDeep  // Dark Teal
        )
        is SpotlightStoryPage.TitleEarned -> Triple(
            TempoPrimaryDeep, // Dark Teal - identity
            TempoAccent, // Light Teal
            TempoPrimaryDeep  // Deep Teal
        )
        is SpotlightStoryPage.Conclusion -> Triple(
            TextTertiary, // Slate - reflection
            TextSecondary, // Light Slate
            TextQuaternary  // Dark Slate
        )
        null -> Triple(TempoPrimaryMuted, TempoAccent, TempoInfo)
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

    // Reset progress when page changes
    LaunchedEffect(pagerState.currentPage) {
        currentProgress.snapTo(0f)
    }

    // Auto-advance logic with pause support
    LaunchedEffect(pagerState.currentPage, isPaused) {
        if (!isPaused) {
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
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.3f))
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
