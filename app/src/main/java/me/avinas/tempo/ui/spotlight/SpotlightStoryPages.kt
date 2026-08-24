package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan

import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Balance
import kotlinx.coroutines.delay
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.LocalInCaptureContext
import androidx.compose.ui.platform.LocalContext
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.GlassCard
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.filled.KeyboardArrowDown

@Composable
private fun SolidCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color.White.copy(alpha = 0.08f),
    borderColor: Color = Color.White.copy(alpha = 0.12f),
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor, shape)
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

data class SpotlightDimens(
    val scale: Float,
    // Spacing
    val screenTopPadding: Dp,
    val screenBottomPadding: Dp,
    val horizontalPadding: Dp,
    val spacerSmall: Dp,
    val spacerMedium: Dp,
    val spacerLarge: Dp,
    
    // Text Sizes
    val textDisplay: TextUnit,     // very large numbers
    val textHeadline: TextUnit,    // main titles
    val textTitle: TextUnit,       // section titles
    val textBody: TextUnit,        // normal text
    val textLabel: TextUnit,       // small details
    
    // Image Sizes
    val imageMain: Dp,
    val imageList: Dp,
    val imageGrid: Dp,
    val bubbleMain: Dp,
    
    // Layout
    val cardCornerRadius: Dp,
    val gridSpacing: Dp
)

@Composable
fun rememberSpotlightDimens(maxHeight: Dp): SpotlightDimens {
    val density = LocalDensity.current
    // Reference height: 800dp (approx generic phone height)
    // We clamp scale between 0.75 (small phones) and 1.2 (large/tall phones)
    // so UI doesn't look too tiny or too blown up.
    val scale = (maxHeight.value / 800f).coerceIn(0.75f, 1.2f)
    
    return remember(scale, maxHeight) {
        SpotlightDimens(
            scale = scale,

            screenTopPadding = (maxHeight * 0.08f).coerceAtLeast(32.dp),
            screenBottomPadding = (maxHeight * 0.15f).coerceAtLeast(130.dp), // Increased to clear 'Share Your Story' button
            horizontalPadding = 24.dp, 
            spacerSmall = (maxHeight * 0.015f).coerceAtLeast(8.dp),  // 1.5% height
            spacerMedium = (maxHeight * 0.025f).coerceAtLeast(16.dp), // 2.5% height
            spacerLarge = (maxHeight * 0.05f).coerceAtLeast(32.dp),   // 5% height
            
            textDisplay = (88.sp.value * scale).sp,
            textHeadline = (32.sp.value * scale).sp,
            textTitle = (24.sp.value * scale).sp,
            textBody = (16.sp.value * scale).sp,
            textLabel = (12.sp.value * scale).sp,
            
            imageMain = (maxHeight * 0.25f).coerceIn(160.dp, 260.dp), // 25% of screen height
            imageList = (maxHeight * 0.06f).coerceIn(40.dp, 56.dp),   // 6% of screen height
            imageGrid = (maxHeight * 0.035f).coerceIn(24.dp, 36.dp),
            bubbleMain = (maxHeight * 0.3f).coerceIn(200.dp, 300.dp),
            
            cardCornerRadius = 20.dp * scale,
            gridSpacing = 8.dp
        )
    }
}

@Composable
fun EnterAnimation(
    delay: Int = 0,
    content: @Composable () -> Unit
) {
    val inCapture = LocalInCaptureContext.current
    if (inCapture) {
        content()
    } else {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay((delay + 50).toLong())
            visible = true
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(450)) +
                    slideInVertically(
                        animationSpec = tween(450, easing = FastOutSlowInEasing),
                        initialOffsetY = { 40 }
                    )
        ) {
            content()
        }
    }
}

    
@Composable
fun ListeningMinutesPage(page: SpotlightStoryPage.ListeningMinutes) {
    val inCapture = LocalInCaptureContext.current
    val animatedMinutes = remember { Animatable(0f) }
    LaunchedEffect(page.totalMinutes) {
        if (inCapture) {
            animatedMinutes.snapTo(page.totalMinutes.toFloat())
        } else {
            delay(500)
            animatedMinutes.animateTo(
                targetValue = page.totalMinutes.toFloat(),
                animationSpec = tween(durationMillis = 2000, easing = FastOutSlowInEasing)
            )
        }
    }
    
    // Pulsing animation for background
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        // Pulsing Background Effect
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp * dimens.scale)
                    .scale(pulseScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EnterAnimation(delay = 0) {
                val context = LocalContext.current
                val titleText = SpotlightPeriodFormatter.storyTitle(
                    context = context,
                    timeRange = page.timeRange,
                    year = page.year
                )

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    letterSpacing = (1f * dimens.scale).sp,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            EnterAnimation(delay = 200) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.spotlight_listened_for),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(dimens.spacerSmall))

                    Text(
                        text = String.format(java.util.Locale.US, "%,d", animatedMinutes.value.toInt()),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = dimens.textDisplay,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.3f),
                                offset = Offset(0f, 4f),
                                blurRadius = 12f
                            )
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = (dimens.textDisplay.value * 1.1f).sp
                    )

                    Text(
                        text = stringResource(R.string.spotlight_listened_minutes),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = (2f * dimens.scale).sp
                    )

                    Spacer(modifier = Modifier.height(dimens.spacerSmall))

                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            
            Spacer(modifier = Modifier.weight(1f))
            
            val days = page.totalMinutes.toFloat() / (24 * 60)
            val daysInt = days.toInt()
            if (daysInt > 0) {
                EnterAnimation(delay = 600) {
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color.White.copy(alpha = 0.1f),
                        contentPadding = PaddingValues(horizontal = dimens.horizontalPadding, vertical = dimens.spacerSmall)
                    ) {
                        Text(
                            text = stringResource(R.string.spotlight_days_nonstop, daysInt),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
            
            // Comparative surprise callout
            if (page.comparativeText != null) {
                EnterAnimation(delay = 800) {
                    Spacer(modifier = Modifier.height(dimens.spacerMedium))
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color.White.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50)
                            )
                            .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                            .padding(horizontal = 20.dp * dimens.scale, vertical = 10.dp * dimens.scale)
                    ) {
                        Text(
                            text = page.comparativeText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = (dimens.textBody.value * 0.9f).sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFFBBF24),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopArtistPage(page: SpotlightStoryPage.TopArtist) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        // Dynamic sizing for Hero Image based on available height
        val heroImageScale = if (page.topArtists.size > 5) 0.6f else 1.0f
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                )
        ) {
            // HEADER - Fixed
            EnterAnimation(delay = 0) {
                 Text(
                     text = stringResource(R.string.spotlight_top_artist_label),
                     style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                     fontWeight = FontWeight.Bold,
                     color = Color.White.copy(alpha = 0.85f),
                     modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                     textAlign = TextAlign.Center
                 )
             }
             
             // MAIN CONTENT - Weighted Distribution
             Column(modifier = Modifier.weight(1f)) {
                 
                 // 1. HERO SECTION (~45% weight) - Increased to reduce grid height further
                 Column(
                     modifier = Modifier.weight(0.45f).fillMaxWidth(),
                     horizontalAlignment = Alignment.CenterHorizontally,
                     verticalArrangement = Arrangement.Center
                 ) {
                     EnterAnimation(delay = 200) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             val imageSize = dimens.imageMain * heroImageScale
                             
                             Box(contentAlignment = Alignment.Center) {
                                 // Glow effect
                                 Box(
                                     modifier = Modifier
                                         .size(imageSize + (40.dp * dimens.scale * heroImageScale))
                                         .background(
                                             brush = Brush.radialGradient(
                                                 colors = listOf(
                                                     Color(0xFFA855F7).copy(alpha = 0.4f),
                                                     Color.Transparent
                                                 )
                                             ),
                                             shape = CircleShape
                                         )
                                 )
                                 
                                 CachedAsyncImage(
                                     imageUrl = page.topArtistImageUrl,
                                     contentDescription = null,
                                     modifier = Modifier
                                         .size(imageSize)
                                         .clip(CircleShape)
                                         .border(4.dp * dimens.scale * heroImageScale, Color.White.copy(alpha = 0.2f), CircleShape),
                                     contentScale = ContentScale.Crop,
                                     allowHardware = false
                                 )
                             }
                             
                             Spacer(modifier = Modifier.height(dimens.spacerSmall)) // Reduced spacer
                             
                             Text(
                                 text = page.topArtistName,
                                 style = MaterialTheme.typography.displaySmall.copy(
                                     fontSize = (dimens.textHeadline.value * heroImageScale).sp
                                 ),
                                 fontWeight = FontWeight.Black,
                                 color = Color.White,
                                 textAlign = TextAlign.Center,
                                 maxLines = 1,
                                 overflow = TextOverflow.Ellipsis
                             )

                             Spacer(modifier = Modifier.height(dimens.spacerSmall))

                             Text(
                                 text = page.conversationalText,
                                 style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                                 fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                 color = Color.White.copy(alpha = 0.8f),
                                 textAlign = TextAlign.Center,
                                 maxLines = 2,
                                 overflow = TextOverflow.Ellipsis
                             )
                         }
                     }
                 }
                 
                 // 2. GRID SECTION (~40% weight) - 4 Rows - Further Reduced
                 Column(
                    modifier = Modifier.weight(0.40f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly 
                 ) {
                     val gridItems = page.topArtists.drop(1).take(8) // Rank 2 to 9
                     val chunkedItems = gridItems.chunked(2)
                     
                     chunkedItems.forEachIndexed { rowIndex, rowItems ->
                         Row(
                             modifier = Modifier.fillMaxWidth().weight(1f),
                             horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             rowItems.forEachIndexed { colIndex, artist ->
                                 Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                 ) {
                                      EnterAnimation(delay = 400 + (rowIndex * 100) + (colIndex * 50)) {
                                          SolidCard(
                                              modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
                                              shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                              backgroundColor = Color.White.copy(alpha = 0.08f),
                                              borderColor = Color.White.copy(alpha = 0.15f),
                                              contentPadding = PaddingValues(horizontal = 8.dp * dimens.scale, vertical = 4.dp * dimens.scale)
                                          ) {
                                              Row(verticalAlignment = Alignment.CenterVertically) {
                                                  Text(
                                                      text = "#${artist.rank}",
                                                      style = MaterialTheme.typography.labelSmall,
                                                      color = Color.White.copy(alpha = 0.6f),
                                                      modifier = Modifier.width((20.dp * dimens.scale))
                                                  )
                                                  CachedAsyncImage(
                                                      imageUrl = artist.imageUrl,
                                                      contentDescription = null,
                                                      modifier = Modifier.size(dimens.imageGrid).clip(CircleShape),
                                                      contentScale = ContentScale.Crop,
                                                      allowHardware = false
                                                  )
                                                  Spacer(modifier = Modifier.width(8.dp * dimens.scale))
                                                  Text(
                                                      text = artist.name,
                                                      style = MaterialTheme.typography.bodySmall,
                                                      fontWeight = FontWeight.SemiBold,
                                                      color = Color.White,
                                                      maxLines = 2,
                                                      overflow = TextOverflow.Ellipsis
                                                  )
                                              }
                                          }
                                       }
                                 }
                             }
                             if (rowItems.size == 1) {
                                 Spacer(modifier = Modifier.weight(1f))
                             }
                         }
                     }
                 }
                 
                 // 3. FOOTER SECTION (Rank 10) (~15% weight)
                Box(
                    modifier = Modifier.weight(0.15f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastArtist = page.topArtists.drop(1).drop(8).firstOrNull() // Rank 10
                    if (lastArtist != null) {
                          EnterAnimation(delay = 800) {
                             SolidCard(
                                 modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                                 shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                 backgroundColor = Color.White.copy(alpha = 0.1f),
                                 borderColor = Color.White.copy(alpha = 0.2f),
                                 contentPadding = PaddingValues(12.dp * dimens.scale)
                             ) {
                                 Row(verticalAlignment = Alignment.CenterVertically) {
                                     Text(
                                         text = "#${lastArtist.rank}",
                                         style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                         color = Color.White.copy(alpha = 0.6f),
                                         modifier = Modifier.width((20.dp * dimens.scale))
                                     )
                                     CachedAsyncImage(
                                         imageUrl = lastArtist.imageUrl,
                                         contentDescription = null,
                                         modifier = Modifier.size(dimens.imageList).clip(CircleShape),
                                         contentScale = ContentScale.Crop,
                                         allowHardware = false
                                     )
                                     Spacer(modifier = Modifier.width(dimens.gridSpacing))
                                     Text(
                                         text = lastArtist.name,
                                         style = MaterialTheme.typography.bodySmall.copy(fontSize = dimens.textLabel),
                                         fontWeight = FontWeight.SemiBold,
                                         color = Color.White,
                                         maxLines = 2,
                                         overflow = TextOverflow.Ellipsis
                                     )
                                 }
                             }
                          }
                    }
                }
             }
        }
    }
}
@Composable
fun TopTrackSetupPage(page: SpotlightStoryPage.TopTrackSetup) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_track_setup_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacerMedium))

            EnterAnimation(delay = 200) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(dimens.spacerLarge))
            
            EnterAnimation(delay = 400) {
                CachedAsyncImage(
                    imageUrl = page.topSongImageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(dimens.imageMain * 0.8f)
                        .clip(RoundedCornerShape(12.dp * dimens.scale))
                        .blur(8.dp * dimens.scale),
                    contentScale = ContentScale.Crop,
                    allowHardware = false
                )
            }
        }
    }
}

@Composable
fun TopSongsPage(page: SpotlightStoryPage.TopSongs) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        // Dynamic sizing for Hero Image based on available height (preventing oversize)
        // If we have a full list (10 items), we must be conservative with the Hero size.
        val heroImageScale = if (page.topSongs.size > 5) 0.6f else 1.0f
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                )
        ) {
            // HEADER - Fixed
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_song_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                    textAlign = TextAlign.Center
                )
            }
            
            // MAIN CONTENT - Weighted Distribution
            Column(modifier = Modifier.weight(1f)) {
                
                // 1. HERO SECTION (~45% weight) - Increased to reduce grid height further
                Column(
                    modifier = Modifier.weight(0.45f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EnterAnimation(delay = 200) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                CachedAsyncImage(
                                    imageUrl = page.topSongImageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dimens.imageMain * heroImageScale)
                                        .clip(RoundedCornerShape(24.dp * dimens.scale))
                                        .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp * dimens.scale)),
                                    contentScale = ContentScale.Crop,
                                    allowHardware = false
                                )
                            }
                            Spacer(modifier = Modifier.height(dimens.spacerSmall))
                            Text(
                                text = page.topSongTitle,
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = (dimens.textHeadline.value * heroImageScale).sp),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = page.topSongArtist,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = (dimens.textTitle.value * heroImageScale).sp),
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            // Total playtime chip for #1 song
                            if (page.totalTimeMs > 0L) {
                                val totalMinutes = (page.totalTimeMs / 60_000).toInt()
                                val hours = totalMinutes / 60
                                val minutes = totalMinutes % 60
                                val playtimeText = if (hours > 0) stringResource(R.string.spotlight_playtime_hours, hours, minutes)
                                                   else stringResource(R.string.spotlight_playtime_minutes, minutes)
                                Spacer(modifier = Modifier.height(dimens.spacerSmall))
                                GlassCard(
                                    modifier = Modifier.wrapContentSize(),
                                    shape = RoundedCornerShape(50),
                                    backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.25f),
                                    contentPadding = PaddingValues(horizontal = 14.dp * dimens.scale, vertical = 5.dp * dimens.scale)
                                ) {
                                    Text(
                                        text = playtimeText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                        color = Color.White.copy(alpha = 0.95f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. GRID SECTION (~40% weight) - 4 Rows - Further Reduced
                Column(
                    modifier = Modifier.weight(0.40f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly 
                ) {
                     val gridItems = page.topSongs.drop(1).take(8) // Rank 2 to 9
                     val chunkedItems = gridItems.chunked(2)
                     
                     chunkedItems.forEachIndexed { rowIndex, rowItems ->
                         Row(
                             modifier = Modifier.fillMaxWidth().weight(1f), // Each row gets equal height
                             horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             rowItems.forEachIndexed { colIndex, song ->
                                 // Inner Item
                                 Box(
                                     modifier = Modifier.weight(1f),
                                     contentAlignment = Alignment.Center
                                 ) {
                                     EnterAnimation(delay = 400 + (rowIndex * 100) + (colIndex * 50)) {
                                         SolidCard(
                                             modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
                                             shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                             backgroundColor = Color.White.copy(alpha = 0.08f),
                                             borderColor = Color.White.copy(alpha = 0.15f),
                                             contentPadding = PaddingValues(horizontal = 8.dp * dimens.scale, vertical = 4.dp * dimens.scale)
                                         ) {
                                             Row(verticalAlignment = Alignment.CenterVertically) {
                                                 Text(
                                                     text = "#${song.rank}",
                                                     style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                                     color = Color.White.copy(alpha = 0.6f),
                                                     modifier = Modifier.width((20.dp * dimens.scale))
                                                 )
                                                 CachedAsyncImage(
                                                     imageUrl = song.imageUrl,
                                                     contentDescription = null,
                                                     modifier = Modifier.size(dimens.imageGrid).clip(RoundedCornerShape(4.dp)),
                                                     contentScale = ContentScale.Crop,
                                                     allowHardware = false
                                                 )
                                                 Spacer(modifier = Modifier.width(8.dp * dimens.scale))
                                                 Column {
                                                     Text(
                                                         text = song.title,
                                                         style = MaterialTheme.typography.bodySmall,
                                                         fontWeight = FontWeight.Bold,
                                                         color = Color.White,
                                                         maxLines = 1,
                                                         overflow = TextOverflow.Ellipsis
                                                     )
                                                     Text(
                                                         text = song.artist,
                                                         style = MaterialTheme.typography.labelSmall,
                                                         color = Color.White.copy(alpha = 0.7f),
                                                         maxLines = 1,
                                                         overflow = TextOverflow.Ellipsis
                                                     )
                                                 }
                                             }
                                         }
                                     }
                                 }
                             }
                             // If row has only 1 item (shouldn't happen with logic but safe-guard), add spacer
                             if (rowItems.size == 1) {
                                 Spacer(modifier = Modifier.weight(1f))
                             }
                         }
                     }
                }

                // 3. FOOTER SECTION (Rank 10) (~15% weight)
                Box(
                    modifier = Modifier.weight(0.15f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val lastItem = page.topSongs.drop(1).drop(8).firstOrNull() // Rank 10
                    if (lastItem != null) {
                        EnterAnimation(delay = 800) {
                            SolidCard(
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                                shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
                                backgroundColor = Color.White.copy(alpha = 0.1f),
                                borderColor = Color.White.copy(alpha = 0.2f),
                                contentPadding = PaddingValues(12.dp * dimens.scale)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                     Text(
                                        text = "#${lastItem.rank}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.width(32.dp * dimens.scale)
                                    )
                                    CachedAsyncImage(
                                        imageUrl = lastItem.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(dimens.imageList).clip(RoundedCornerShape(8.dp * dimens.scale)),
                                        contentScale = ContentScale.Crop,
                                        allowHardware = false
                                    )
                                    Spacer(modifier = Modifier.width(12.dp * dimens.scale))
                                    Column {
                                        Text(
                                            text = lastItem.title,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = dimens.textBody),
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = lastItem.artist,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopGenresPage(page: SpotlightStoryPage.TopGenres) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_genres_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = dimens.spacerSmall)
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Floating Animation
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "floating")
                val floatOffset by infiniteTransition.animateFloat(
                    initialValue = -10f,
                    targetValue = 10f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = tween(2000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "floatOffset"
                )

                // Main Bubble
                EnterAnimation(delay = 200) {
                    val bubbleSize = dimens.bubbleMain
                    Box(
                        modifier = Modifier
                            .offset(y = floatOffset.dp)
                            .size(bubbleSize)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF10B981).copy(alpha = 0.6f),
                                        Color(0xFF10B981).copy(alpha = 0.2f)
                                    )
                                ),
                                shape = CircleShape
                            )
                            .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.3f), CircleShape)
                            .padding(24.dp * dimens.scale),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = page.topGenre,
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = dimens.textHeadline),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                lineHeight = dimens.textHeadline
                            )
                            
                            Spacer(modifier = Modifier.height(dimens.spacerSmall))

                            Text(
                                text = page.conversationalText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                            Text(
                                text = "${page.topGenrePercentage}%",
                                style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textTitle),
                                color = Color(0xFF6EE7B7),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Surrounding Bubbles
                val otherGenres = page.genres.drop(1).take(4)
                val positions = listOf(
                    Alignment.TopStart,
                    Alignment.TopEnd,
                    Alignment.BottomStart,
                    Alignment.BottomEnd
                )
                
                otherGenres.forEachIndexed { index, genre ->
                    if (index < positions.size) {
                        // Staggered floating for other bubbles
                        val otherFloatOffset by infiniteTransition.animateFloat(
                            initialValue = if (index % 2 == 0) 5f else -5f,
                            targetValue = if (index % 2 == 0) -5f else 5f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                animation = tween(2500 + (index * 200), easing = androidx.compose.animation.core.LinearEasing),
                                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                            ),
                            label = "otherFloatOffset"
                        )

                        Box(
                            modifier = Modifier
                                .align(positions[index])
                                .offset(
                                    x = if (index % 2 == 0) 20.dp * dimens.scale else -(20.dp * dimens.scale),
                                    y = (if (index < 2) 20.dp * dimens.scale else -(20.dp * dimens.scale)) + otherFloatOffset.dp
                                )
                        ) {
                            EnterAnimation(delay = 400 + (index * 150)) {
                                val smallBubbleSize = dimens.bubbleMain * 0.5f
                                Box(
                                    modifier = Modifier
                                        .size(smallBubbleSize)
                                        .background(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.15f),
                                                    Color.White.copy(alpha = 0.05f)
                                                )
                                            ),
                                            shape = CircleShape
                                        )
                                        .border(1.dp * dimens.scale, Color.White.copy(alpha = 0.2f), CircleShape)
                                        .padding(12.dp * dimens.scale),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = genre.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textLabel),
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${genre.percentage}%",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (dimens.textLabel.value * 0.8f).sp),
                                            color = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PersonalityPage(page: SpotlightStoryPage.Personality) {
    val (icon, color) = getPersonalityAssets(page.personalityType)
    
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "personality")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        // Full-screen color wash
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.4f),
                            color.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        center = Offset(0.5f, 0.3f)
                    )
                )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_listening_personality),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // CINEMATIC ICON - Huge, rotating, dramatic
            EnterAnimation(delay = 300) {
                Box(
                    modifier = Modifier.size((300.dp * dimens.scale).coerceIn(240.dp, 360.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer rotating ring
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(rotationAngle)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            color.copy(alpha = 0.6f),
                                            Color.Transparent,
                                            color.copy(alpha = 0.3f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        )
                    }
                    
                    // Pulsing glow
                    Box(
                        modifier = Modifier
                            .size(200.dp * dimens.scale)
                            .scale(pulseScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        color.copy(alpha = 0.8f),
                                        color.copy(alpha = 0.3f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    // Core icon
                    Box(
                        modifier = Modifier
                            .size(140.dp * dimens.scale)
                            .background(
                                color = color.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .border(3.dp * dimens.scale, color.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(80.dp * dimens.scale)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // REVEAL - Personality type with dramatic typography
            EnterAnimation(delay = 600) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.personalityType,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = (dimens.textDisplay.value * 1.2f).coerceAtMost(56f).sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (2f * dimens.scale).sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(dimens.spacerLarge))
                    
                    // Description - intimate, left-aligned
                    Text(
                        text = page.description,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = dimens.textBody,
                            lineHeight = (dimens.textBody.value * 1.6f).sp
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Left,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(dimens.spacerMedium))
                    
                    // Conversational - whispered, italic
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (dimens.textBody.value * 0.9f).sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// NEW SPOTLIGHT STORY PAGES

@Composable
fun ListeningStreakPage(page: SpotlightStoryPage.ListeningStreak) {
    val inCapture = LocalInCaptureContext.current
    val animatedStreak = remember { Animatable(0f) }
    LaunchedEffect(page.currentStreakDays) {
        if (inCapture) {
            animatedStreak.snapTo(page.currentStreakDays.toFloat())
        } else {
            delay(400)
            animatedStreak.animateTo(
                targetValue = page.currentStreakDays.toFloat(),
                animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "fire")
    val fireScale by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "fireScale"
    )
    val fireAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "fireAlpha"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm radial glow background
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(350.dp * dimens.scale)
                    .scale(fireScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF6B00).copy(alpha = 0.35f * fireAlpha),
                                Color(0xFFFF9900).copy(alpha = 0.15f * fireAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_listening_streak_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Fire icon with glow
            EnterAnimation(delay = 200) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(120.dp * dimens.scale)
                            .scale(fireScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFF6B00).copy(alpha = 0.5f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    Icon(
                        imageVector = Icons.Default.LocalFireDepartment,
                        contentDescription = null,
                        tint = Color(0xFFFF9900).copy(alpha = fireAlpha),
                        modifier = Modifier.size(72.dp * dimens.scale)
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            // Animated streak number
            EnterAnimation(delay = 300) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = animatedStreak.value.toInt().toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = dimens.textDisplay,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFFF6B00).copy(alpha = 0.5f),
                                offset = Offset(0f, 4f),
                                blurRadius = 20f
                            )
                        ),
                        color = Color(0xFFFFD060),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (page.currentStreakDays == 1) stringResource(R.string.spotlight_day_in_a_row) else stringResource(R.string.spotlight_days_in_a_row),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f),
                        letterSpacing = (1f * dimens.scale).sp
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stats row: longest + total active days
            EnterAnimation(delay = 600) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color.White.copy(alpha = 0.06f),
                        borderColor = Color(0xFFFFD060).copy(alpha = 0.25f),
                        contentPadding = PaddingValues(dimens.spacerSmall)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = page.longestStreakDays.toString(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textTitle),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD060),
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.spotlight_best_streak),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color.White.copy(alpha = 0.06f),
                        borderColor = Color.White.copy(alpha = 0.15f),
                        contentPadding = PaddingValues(dimens.spacerSmall)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = page.totalActiveDays.toString(),
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textTitle),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.spotlight_active_days),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            
            // Comparative surprise callout
            if (page.comparativeText != null) {
                EnterAnimation(delay = 800) {
                    Spacer(modifier = Modifier.height(dimens.spacerMedium))
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFF6B00).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
                            )
                            .border(1.dp * dimens.scale, Color(0xFFFF9900).copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 20.dp * dimens.scale, vertical = 10.dp * dimens.scale)
                    ) {
                        Text(
                            text = page.comparativeText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = (dimens.textBody.value * 0.9f).sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFFFD060),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ListeningClockPage(page: SpotlightStoryPage.ListeningClock) {
    val inCapture = LocalInCaptureContext.current
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (inCapture) {
            animatedProgress.snapTo(1f)
        } else {
            delay(300)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1600, easing = FastOutSlowInEasing)
            )
        }
    }

    val isNightOwl = page.peakHour >= 20 || page.peakHour < 6
    val accentColor = if (isNightOwl) Color(0xFF7C3AED) else Color(0xFFF59E0B)
    val secondaryColor = if (isNightOwl) Color(0xFF60A5FA) else Color(0xFFFBBF24)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_listening_clock_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Radial Clock Canvas
            EnterAnimation(delay = 200) {
                val clockSize = (260.dp * dimens.scale).coerceIn(220.dp, 320.dp)
                Box(
                    modifier = Modifier.size(clockSize),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outerRadius = size.minDimension / 2f
                        val innerRadius = outerRadius * 0.35f
                        val barMaxHeight = outerRadius - innerRadius

                        // Draw background track ring
                        drawCircle(
                            color = Color.White.copy(alpha = 0.1f),
                            radius = (innerRadius + barMaxHeight / 2f),
                            style = Stroke(width = barMaxHeight)
                        )

                        val numBars = 24
                        for (i in 0 until numBars) {
                            val level = (page.hourlyLevels.getOrNull(i) ?: 0) / 100f
                            val animLevel = level * animatedProgress.value
                            val angleDeg = (i.toFloat() / numBars) * 360f - 90f
                            val angleRad = (angleDeg * PI / 180f).toFloat()

                            val barHeight = (barMaxHeight * animLevel.coerceAtLeast(0.05f))
                            val startR = innerRadius
                            val endR = startR + barHeight

                            val isPeak = i == page.peakHour
                            val barColor = when {
                                isPeak -> accentColor
                                level > 0.6f -> secondaryColor.copy(alpha = 0.9f)
                                level > 0.3f -> Color.White.copy(alpha = 0.65f)
                                else -> Color.White.copy(alpha = 0.25f)
                            }

                            val startX = cx + cos(angleRad) * startR
                            val startY = cy + sin(angleRad) * startR
                            val endX = cx + cos(angleRad) * endR
                            val endY = cy + sin(angleRad) * endR

                            drawLine(
                                color = barColor,
                                start = androidx.compose.ui.geometry.Offset(startX, startY),
                                end = androidx.compose.ui.geometry.Offset(endX, endY),
                                strokeWidth = if (isPeak) 8f else 5f,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    // Center label
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isNightOwl) Icons.Default.Nightlight else Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp * dimens.scale)
                        )
                        Text(
                            text = page.peakHourLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.spotlight_peak),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerMedium))

            // Listener type badge
            EnterAnimation(delay = 500) {
                    SolidCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = accentColor.copy(alpha = 0.12f),
                        borderColor = accentColor.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(horizontal = 24.dp * dimens.scale, vertical = 10.dp * dimens.scale)
                    ) {
                        Text(
                            text = page.listenerType,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            EnterAnimation(delay = 700) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Hour labels
            EnterAnimation(delay = 800) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(
                        R.string.spotlight_clock_12am,
                        R.string.spotlight_clock_6am,
                        R.string.spotlight_clock_12pm,
                        R.string.spotlight_clock_6pm
                    ).forEach { labelRes ->
                        SolidCard(
                            modifier = Modifier.wrapContentSize(),
                            shape = RoundedCornerShape(50),
                            backgroundColor = Color.White.copy(alpha = 0.05f),
                            borderColor = Color.White.copy(alpha = 0.12f),
                            borderWidth = 0.5.dp,
                            contentPadding = PaddingValues(horizontal = 8.dp * dimens.scale, vertical = 4.dp * dimens.scale)
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopAlbumPage(page: SpotlightStoryPage.TopAlbum) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Blurred background art
        if (page.albumArtUrl != null) {
            CachedAsyncImage(
                imageUrl = page.albumArtUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp * dimens.scale)
                    .scale(1.3f),
                contentScale = ContentScale.Crop,
                allowHardware = false
            )
            // Darken overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_top_album_label),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Album art - large reveal
            EnterAnimation(delay = 300) {
                val artSize = dimens.imageMain * 1.1f
                Box(contentAlignment = Alignment.Center) {
                    // Glow behind art
                    Box(
                        modifier = Modifier
                            .size(artSize + 50.dp * dimens.scale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(28.dp * dimens.scale)
                            )
                    )
                    CachedAsyncImage(
                        imageUrl = page.albumArtUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(artSize)
                            .clip(RoundedCornerShape(20.dp * dimens.scale))
                            .border(2.dp * dimens.scale, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp * dimens.scale)),
                        contentScale = ContentScale.Crop,
                        allowHardware = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(dimens.spacerMedium))

            // Album name and artist
            EnterAnimation(delay = 500) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.albumName,
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = dimens.textHeadline),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = page.artistName,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Stats row
            EnterAnimation(delay = 700) {
                val totalMinutes = (page.totalTimeMs / 60_000).toInt()
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                val timeText = if (hours > 0) stringResource(R.string.spotlight_time_hours, hours, mins) else stringResource(R.string.spotlight_time_minutes, mins)
                val values = listOf(page.playCount.toString(), timeText, page.uniqueTracksPlayed.toString())
                val maxLen = values.maxOf { it.length }
                val statFontSize = when {
                    maxLen >= 8 -> (dimens.textTitle.value * 0.7f).sp
                    maxLen >= 6 -> (dimens.textTitle.value * 0.8f).sp
                    else -> dimens.textTitle
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    listOf(
                        Pair(values[0], stringResource(R.string.spotlight_plays)),
                        Pair(values[1], stringResource(R.string.spotlight_time_spent)),
                        Pair(values[2], stringResource(R.string.spotlight_tracks))
                    ).forEach { (value, label) ->
                        SolidCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color.White.copy(alpha = 0.06f),
                            borderColor = Color.White.copy(alpha = 0.15f),
                            contentPadding = PaddingValues(vertical = 10.dp * dimens.scale, horizontal = 4.dp * dimens.scale)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = statFontSize),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1
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
fun DiscoveryCountPage(page: SpotlightStoryPage.DiscoveryCount) {
    val inCapture = LocalInCaptureContext.current
    val artistsAnim = remember { Animatable(0f) }
    val tracksAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.uniqueArtists) {
        if (inCapture) {
            artistsAnim.snapTo(page.uniqueArtists.toFloat())
        } else {
            delay(500)
            artistsAnim.animateTo(page.uniqueArtists.toFloat(), tween(1400, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(page.uniqueTracks) {
        if (inCapture) {
            tracksAnim.snapTo(page.uniqueTracks.toFloat())
        } else {
            delay(650)
            tracksAnim.animateTo(page.uniqueTracks.toFloat(), tween(1600, easing = FastOutSlowInEasing))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbScale"
    )
    val orbAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f, targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "orbAlpha"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Background ambient orb
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp * dimens.scale)
                    .scale(orbScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF06B6D4).copy(alpha = orbAlpha),
                                Color(0xFF8B5CF6).copy(alpha = orbAlpha * 0.5f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(20.dp * dimens.scale)
                    )
                    Spacer(modifier = Modifier.width(6.dp * dimens.scale))
                    Text(
                        text = stringResource(R.string.spotlight_music_universe),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // Main counters — two large cards
            EnterAnimation(delay = 300) {
                val targetArtistsText = String.format(java.util.Locale.US, "%,d", page.uniqueArtists)
                val targetTracksText = String.format(java.util.Locale.US, "%,d", page.uniqueTracks)
                val maxLen = maxOf(targetArtistsText.length, targetTracksText.length)
                val sharedFontSize = when {
                    maxLen > 5 -> (dimens.textDisplay.value * 0.42f).sp
                    maxLen > 4 -> (dimens.textDisplay.value * 0.48f).sp
                    maxLen > 3 -> (dimens.textDisplay.value * 0.55f).sp
                    else -> (dimens.textDisplay.value * 0.6f).sp
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    // Artists
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                        borderColor = Color(0xFF8B5CF6).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(vertical = 20.dp * dimens.scale, horizontal = 8.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val artistsText = String.format(java.util.Locale.US, "%,d", artistsAnim.value.toInt())
                            Text(
                                text = artistsText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = sharedFontSize,
                                    fontWeight = FontWeight.Black,
                                    shadow = Shadow(
                                        color = Color(0xFF8B5CF6).copy(alpha = 0.7f),
                                        offset = Offset(0f, 3f),
                                        blurRadius = 12f
                                    )
                                ),
                                color = Color(0xFFC4B5FD),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                            Text(
                                text = stringResource(R.string.spotlight_artists),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                color = Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Tracks
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF06B6D4).copy(alpha = 0.12f),
                        borderColor = Color(0xFF06B6D4).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(vertical = 20.dp * dimens.scale, horizontal = 8.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tracksText = String.format(java.util.Locale.US, "%,d", tracksAnim.value.toInt())
                            Text(
                                text = tracksText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = sharedFontSize,
                                    fontWeight = FontWeight.Black,
                                    shadow = Shadow(
                                        color = Color(0xFF06B6D4).copy(alpha = 0.7f),
                                        offset = Offset(0f, 3f),
                                        blurRadius = 12f
                                    )
                                ),
                                color = Color(0xFF67E8F9),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                            Text(
                                text = stringResource(R.string.spotlight_unique_songs),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                color = Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Conversational tagline
            EnterAnimation(delay = 600) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // New discoveries chip (bottom)
            EnterAnimation(delay = 900) {
                if (page.newArtistsThisPeriod > 0) {
                    GlassCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color(0xFF34D399).copy(alpha = 0.15f),
                        contentPadding = PaddingValues(horizontal = 18.dp * dimens.scale, vertical = 8.dp * dimens.scale)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(14.dp * dimens.scale)
                            )
                            Spacer(modifier = Modifier.width(6.dp * dimens.scale))
                            Text(
                                text = stringResource(R.string.spotlight_new_artists_chip, page.newArtistsThisPeriod, page.timeRangeLabel),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                        }
                    }
                } else {
                    // Spacer placeholder so layout stays consistent
                    Spacer(modifier = Modifier.height(36.dp * dimens.scale))
                }
            }
            
            // Comparative surprise callout
            if (page.comparativeText != null) {
                EnterAnimation(delay = 1100) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFF06B6D4).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
                            )
                            .border(1.dp * dimens.scale, Color(0xFF22D3EE).copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 20.dp * dimens.scale, vertical = 10.dp * dimens.scale)
                    ) {
                        Text(
                            text = page.comparativeText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = (dimens.textBody.value * 0.9f).sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFF67E8F9),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WeekdayVsWeekendPage(page: SpotlightStoryPage.WeekdayVsWeekend) {
    val inCapture = LocalInCaptureContext.current
    val barAnims = remember { List(7) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        if (inCapture) {
            barAnims.forEachIndexed { index, anim ->
                anim.snapTo((page.dailyIntensity.getOrNull(index) ?: 0) / 100f)
            }
        } else {
            delay(400)
            barAnims.forEachIndexed { index, anim ->
                val delayMs = 400L + (index * 80).toLong()
                anim.snapTo(0f)
                delay(delayMs - 400L)
                anim.animateTo(
                    targetValue = (page.dailyIntensity.getOrNull(index) ?: 0) / 100f,
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    val isWeekendDominant = page.dominantSide == "weekend"
    val accentColor = if (isWeekendDominant) Color(0xFFF59E0B) else Color(0xFF60A5FA)
    val dayNamesRes = listOf(
        R.string.spotlight_day_mon, R.string.spotlight_day_tue, R.string.spotlight_day_wed,
        R.string.spotlight_day_thu, R.string.spotlight_day_fri, R.string.spotlight_day_sat,
        R.string.spotlight_day_sun
    )
    val isWeekend = listOf(false, false, false, false, false, true, true)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_week_vs_weekend),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // VS comparison cards
            EnterAnimation(delay = 150) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    // Weekday card
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = if (!isWeekendDominant) Color(0xFF60A5FA).copy(alpha = 0.12f)
                                         else Color.White.copy(alpha = 0.04f),
                        borderColor = if (!isWeekendDominant) Color(0xFF60A5FA).copy(alpha = 0.35f)
                                     else Color.White.copy(alpha = 0.12f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${page.weekdayAvgMinutes}",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = dimens.textHeadline,
                                    fontWeight = FontWeight.Black
                                ),
                                color = if (!isWeekendDominant) Color(0xFF93C5FD) else Color.White.copy(alpha = 0.55f),
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.spotlight_min_per_day),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                            Text(
                                text = stringResource(R.string.spotlight_weekdays),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                            Text(
                                text = page.weekdayLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = if (!isWeekendDominant) Color(0xFF93C5FD) else Color.Transparent,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Weekend card
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = if (isWeekendDominant) Color(0xFFF59E0B).copy(alpha = 0.12f)
                                         else Color.White.copy(alpha = 0.04f),
                        borderColor = if (isWeekendDominant) Color(0xFFF59E0B).copy(alpha = 0.35f)
                                     else Color.White.copy(alpha = 0.12f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${page.weekendAvgMinutes}",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = dimens.textHeadline,
                                    fontWeight = FontWeight.Black
                                ),
                                color = if (isWeekendDominant) Color(0xFFFCD34D) else Color.White.copy(alpha = 0.55f),
                                maxLines = 1
                            )
                            Text(
                                text = stringResource(R.string.spotlight_min_per_day),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                            Text(
                                text = stringResource(R.string.spotlight_weekends),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                            Text(
                                text = page.weekendLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = if (isWeekendDominant) Color(0xFFFCD34D) else Color.Transparent,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 7-bar chart
            val barMaxHeight = remember(dimens.scale) { 120.dp * dimens.scale }
            EnterAnimation(delay = 400) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    dayNamesRes.forEachIndexed { i, dayRes ->
                        val isWknd = isWeekend[i]
                        val barColor = when {
                            isWknd && isWeekendDominant -> Color(0xFFF59E0B)
                            !isWknd && !isWeekendDominant -> Color(0xFF60A5FA)
                            isWknd -> Color(0xFFF59E0B).copy(alpha = 0.5f)
                            else -> Color(0xFF60A5FA).copy(alpha = 0.5f)
                        }
                        val intensity = barAnims[i].value.coerceAtLeast(0.04f)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                                Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .height(barMaxHeight * intensity)
                                    .clip(RoundedCornerShape(topStart = 6.dp * dimens.scale, topEnd = 6.dp * dimens.scale))
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(barColor, barColor.copy(alpha = 0.5f))
                                        )
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                            Text(
                                text = stringResource(dayRes),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = if (isWknd) Color(0xFFFCD34D).copy(alpha = 0.9f)
                                        else Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Tagline
            EnterAnimation(delay = 700) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BingeSessionPage(page: SpotlightStoryPage.BingeSession) {
    val inCapture = LocalInCaptureContext.current
    val countAnim = remember { Animatable(0f) }
    val timeAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.bingeCount) {
        if (inCapture) {
            countAnim.snapTo(page.bingeCount.toFloat())
        } else {
            delay(600)
            countAnim.animateTo(page.bingeCount.toFloat(), tween(1200, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(page.totalBingeMinutes) {
        if (inCapture) {
            timeAnim.snapTo(page.totalBingeMinutes.toFloat())
        } else {
            delay(800)
            timeAnim.animateTo(page.totalBingeMinutes.toFloat(), tween(1400, easing = FastOutSlowInEasing))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm glow background
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(320.dp * dimens.scale)
                    .scale(pulseScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEC4899).copy(alpha = 0.22f),
                                Color(0xFF8B5CF6).copy(alpha = 0.1f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_longest_binge),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Artist name — big reveal
            EnterAnimation(delay = 200) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = page.artistName,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = dimens.textHeadline,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFEC4899).copy(alpha = 0.6f),
                                offset = Offset(0f, 4f),
                                blurRadius = 20f
                            )
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                    SolidCard(
                        modifier = Modifier.wrapContentSize(),
                        shape = RoundedCornerShape(50),
                        backgroundColor = Color(0xFFEC4899).copy(alpha = 0.12f),
                        borderColor = Color(0xFFEC4899).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(horizontal = 16.dp * dimens.scale, vertical = 6.dp * dimens.scale)
                    ) {
                        Text(
                            text = stringResource(R.string.spotlight_marathon_session),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                            color = Color(0xFFF9A8D4),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Stats row
            EnterAnimation(delay = 500) {
                val targetCountText = page.bingeCount.toString()
                val targetMins = page.totalBingeMinutes
                val targetH = targetMins / 60
                val targetM = targetMins % 60
                val worstTimeText = stringResource(R.string.spotlight_time_hours, targetH, 59)
                val targetTimeText = stringResource(R.string.spotlight_time_hours, targetH, targetM)
                val maxLen = maxOf(targetCountText.length, worstTimeText.length, targetTimeText.length)
                val statFontSize = when {
                    maxLen >= 8 -> (dimens.textDisplay.value * 0.38f).sp
                    maxLen >= 6 -> (dimens.textDisplay.value * 0.42f).sp
                    else -> (dimens.textDisplay.value * 0.5f).sp
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFFEC4899).copy(alpha = 0.1f),
                        borderColor = Color(0xFFEC4899).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(16.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = countAnim.value.toInt().toString(),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = statFontSize,
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color(0xFFF9A8D4),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.spotlight_tracks_in_a_row),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.65f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.1f),
                        borderColor = Color(0xFF8B5CF6).copy(alpha = 0.3f),
                        contentPadding = PaddingValues(16.dp * dimens.scale)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val mins = timeAnim.value.toInt()
                            val h = mins / 60
                            val m = mins % 60
                            val timeText = stringResource(R.string.spotlight_time_hours, h, m)
                            
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = statFontSize,
                                    fontWeight = FontWeight.Black
                                ),
                                color = Color(0xFFC4B5FD),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.spotlight_straight),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TimeOfDayVibesPage(page: SpotlightStoryPage.TimeOfDayVibes) {
    data class Period(val labelRes: Int, val emoji: String, val percent: Int, val color: Color, val bg: Color)
    val periods = listOf(
        Period(R.string.spotlight_period_morning,   "🌅", page.morningPercent,   Color(0xFFFBBF24), Color(0xFFF59E0B)),
        Period(R.string.spotlight_period_afternoon, "☀️", page.afternoonPercent,  Color(0xFF34D399), Color(0xFF10B981)),
        Period(R.string.spotlight_period_evening,   "🌆", page.eveningPercent,   Color(0xFF60A5FA), Color(0xFF3B82F6)),
        Period(R.string.spotlight_period_night,     "🌙", page.nightPercent,     Color(0xFFA78BFA), Color(0xFF8B5CF6))
    )

    val totalPct = periods.sumOf { it.percent }.coerceAtLeast(1)

    val inCapture = LocalInCaptureContext.current
    // Each bar animates independently
    val anims = remember { periods.map { Animatable(0f) } }
    LaunchedEffect(Unit) {
        if (inCapture) {
            anims.forEachIndexed { index, anim ->
                anim.snapTo(periods[index].percent / totalPct.toFloat())
            }
        } else {
            delay(400)
            anims.forEachIndexed { index, anim ->
                val delayMs = (index * 120).toLong()
                delay(if (index == 0) delayMs else 120L)
                anim.animateTo(
                    targetValue = periods[index].percent / totalPct.toFloat(),
                    animationSpec = tween(900, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_when_you_listen),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Dominant period big label
            EnterAnimation(delay = 150) {
                val dominant = periods.firstOrNull { stringResource(it.labelRes).lowercase() == page.dominantPeriod }
                    ?: periods.maxByOrNull { it.percent }!!
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = dominant.emoji,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = (dimens.textDisplay.value * 0.7f).sp),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = stringResource(dominant.labelRes),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = dimens.textHeadline,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = dominant.bg.copy(alpha = 0.5f),
                                offset = Offset(0f, 3f),
                                blurRadius = 12f
                            )
                        ),
                        color = dominant.color,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.spotlight_listener),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // Horizontal share bars
            EnterAnimation(delay = 400) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    periods.forEachIndexed { i, period ->
                        val targetFraction = anims[i].value
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Emoji label
                            Text(
                                text = period.emoji,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (dimens.textBody.value * 1.1f).sp
                                ),
                                modifier = Modifier.width(32.dp * dimens.scale)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Bar
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(10.dp * dimens.scale)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.1f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(targetFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(period.bg.copy(alpha = 0.7f), period.color)
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Percent
                            Text(
                                text = "${period.percent}%",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                                fontWeight = FontWeight.Bold,
                                color = period.color,
                                modifier = Modifier.width(36.dp * dimens.scale),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AudioMoodPage(page: SpotlightStoryPage.AudioMood) {
    val inCapture = LocalInCaptureContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_sound_profile),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Dominant mood badge
            EnterAnimation(delay = 150) {
                Text(
                    text = page.dominantMood,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = dimens.textHeadline,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = Color(0xFF8B5CF6).copy(alpha = 0.5f),
                            offset = Offset(0f, 4f),
                            blurRadius = 16f
                        )
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacerSmall))

            EnterAnimation(delay = 250) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(dimens.spacerLarge))

            // Mood bars
            val moodBars = listOf(
                Triple(R.string.spotlight_mood_energy, page.energyPercent, Color(0xFFEF4444)),
                Triple(R.string.spotlight_mood_positivity, page.valencePercent, Color(0xFFF59E0B)),
                Triple(R.string.spotlight_mood_danceability, page.danceabilityPercent, Color(0xFF10B981)),
                Triple(R.string.spotlight_mood_acoustic, page.acousticnessPercent, Color(0xFF60A5FA))
            )

            moodBars.forEachIndexed { index, (label, percent, color) ->
                val animatedWidth = remember { Animatable(0f) }
                LaunchedEffect(percent) {
                    if (inCapture) {
                        animatedWidth.snapTo(percent / 100f)
                    } else {
                        delay((300 + index * 150).toLong())
                        animatedWidth.animateTo(
                            targetValue = percent / 100f,
                            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
                        )
                    }
                }

                EnterAnimation(delay = 300 + index * 150) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Text(
                                text = "${percent}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textBody),
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp * dimens.scale))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp * dimens.scale)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedWidth.value)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                color.copy(alpha = 0.8f),
                                                color
                                            )
                                        )
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(dimens.spacerMedium))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ConclusionPage(page: SpotlightStoryPage.Conclusion) {
    val context = LocalContext.current
    val titleText = remember(page.timeRange) {
        SpotlightPoetry.getHeading(context, page.timeRange)
    }
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        
        // Warm gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF8B5CF6).copy(alpha = 0.08f),
                            Color.Transparent,
                            Color(0xFFEC4899).copy(alpha = 0.06f)
                        )
                    )
                )
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header - Emotional callback
            EnterAnimation(delay = 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = (dimens.textHeadline.value * 1.1f).sp,
                            fontWeight = FontWeight.Black
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    
                    Text(
                        text = page.conversationalText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = dimens.textBody,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp * dimens.scale))
            
            // Total Listening - Hero stat
            EnterAnimation(delay = 200) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%,d", page.totalMinutes),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = (dimens.textDisplay.value * 0.8f).sp,
                            fontWeight = FontWeight.Black
                        ),
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.spotlight_conclusion_min),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                        color = Color.White.copy(alpha = 0.7f),
                        letterSpacing = (1f * dimens.scale).sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp * dimens.scale))
            
            // Personality - Compact inline
            EnterAnimation(delay = 400) {
                SolidCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(dimens.cardCornerRadius),
                    backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.1f),
                    borderColor = Color(0xFF8B5CF6).copy(alpha = 0.25f),
                    contentPadding = PaddingValues(16.dp * dimens.scale)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = getPersonalityAssets(page.personalityType).first,
                            contentDescription = null,
                            tint = getPersonalityAssets(page.personalityType).second,
                            modifier = Modifier.size(36.dp * dimens.scale)
                        )
                        Spacer(modifier = Modifier.width(12.dp * dimens.scale))
                        Column {
                            Text(
                                text = stringResource(R.string.spotlight_listening_personality),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = page.personalityType,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (dimens.textBody.value * 1.1f).sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp * dimens.scale))
            
            // Top Artists & Songs - Two columns
            EnterAnimation(delay = 600) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
                ) {
                    // Top Artists
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFFEC4899).copy(alpha = 0.08f),
                        borderColor = Color(0xFFEC4899).copy(alpha = 0.2f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.spotlight_conclusion_top_artists),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = Color(0xFFF9A8D4),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                            
                            page.topArtists.take(5).forEach { artist ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 3.dp * dimens.scale)
                                ) {
                                    CachedAsyncImage(
                                        imageUrl = artist.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp * dimens.scale).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                        allowHardware = false
                                    )
                                    Spacer(modifier = Modifier.width(6.dp * dimens.scale))
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (dimens.textLabel.value * 0.9f).sp),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    
                    // Top Songs
                    SolidCard(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(dimens.cardCornerRadius),
                        backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.08f),
                        borderColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                        contentPadding = PaddingValues(12.dp * dimens.scale)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.spotlight_conclusion_top_songs),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                                color = Color(0xFFFCD34D),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                            
                            page.topSongs.take(5).forEach { song ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 3.dp * dimens.scale)
                                ) {
                                    CachedAsyncImage(
                                        imageUrl = song.imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp * dimens.scale).clip(RoundedCornerShape(3.dp * dimens.scale)),
                                        contentScale = ContentScale.Crop,
                                        allowHardware = false
                                    )
                                    Spacer(modifier = Modifier.width(6.dp * dimens.scale))
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (dimens.textLabel.value * 0.9f).sp),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp * dimens.scale))
            
            // Closing message - Full circle moment
            EnterAnimation(delay = 800) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.height(16.dp * dimens.scale))
                    val closingResId = remember(page.timeRange) {
                        when (page.timeRange) {
                            me.avinas.tempo.data.stats.TimeRange.THIS_WEEK -> R.string.spotlight_conclusion_closing_week
                            me.avinas.tempo.data.stats.TimeRange.THIS_MONTH -> R.string.spotlight_conclusion_closing_month
                            me.avinas.tempo.data.stats.TimeRange.ALL_TIME -> R.string.spotlight_conclusion_closing_all_time
                            else -> R.string.spotlight_conclusion_closing
                        }
                    }
                    Text(
                        text = stringResource(closingResId),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = dimens.textBody,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            

        }
    }
}


@Composable
private fun getPersonalityAssets(type: String): Pair<ImageVector, Color> {
    return when (type) {
        stringResource(R.string.personality_party_starter_name) -> Icons.Default.Celebration to Color(0xFFF472B6)
        stringResource(R.string.personality_intense_soul_name) -> Icons.Default.Bolt to Color(0xFFEF4444)
        stringResource(R.string.personality_peaceful_optimist_name) -> Icons.Default.Spa to Color(0xFF34D399)
        stringResource(R.string.personality_deep_thinker_name) -> Icons.Default.SelfImprovement to Color(0xFF60A5FA)
        stringResource(R.string.personality_dance_floor_regular_name) -> Icons.Default.MusicNote to Color(0xFFF59E0B)
        stringResource(R.string.personality_balanced_enthusiast_name) -> Icons.Default.Balance to Color(0xFFA78BFA)
        else -> Icons.Default.Psychology to Color(0xFFC4B5FD)
    }
}

// ─────────────────────────────────────────────
// GAMIFICATION PAGES
// ─────────────────────────────────────────────

@Composable
fun BadgesEarnedPage(page: SpotlightStoryPage.BadgesEarned) {
    // Helper: map category to accent color
    fun categoryColor(cat: String): Color = when (cat) {
        "MILESTONE"  -> Color(0xFFF59E0B)
        "TIME"       -> Color(0xFF34D399)
        "STREAK"     -> Color(0xFFEF4444)
        "DISCOVERY"  -> Color(0xFF60A5FA)
        "ENGAGEMENT" -> Color(0xFFA78BFA)
        "LEVEL"      -> Color(0xFFFBBF24)
        else         -> Color(0xFFC4B5FD)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "trophy")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shimmer"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Warm golden ambient glow
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .offset(y = (-40).dp)
                    .size(260.dp * dimens.scale)
                    .alpha(shimmer * 0.35f)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF59E0B), Color(0xFFF59E0B).copy(alpha = 0.3f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            EnterAnimation(delay = 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(32.dp * dimens.scale)
                    )
                    Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                    Text(
                        text = stringResource(R.string.spotlight_badges_earned),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // Badges grid (up to 6, 2 cols)
            EnterAnimation(delay = 200) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp * dimens.scale),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    page.badges.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                        ) {
                            row.forEach { badge ->
                                val accent = categoryColor(badge.category)
                                SolidCard(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(dimens.cardCornerRadius),
                                    backgroundColor = accent.copy(alpha = 0.08f),
                                    borderColor = accent.copy(alpha = 0.25f),
                                    contentPadding = PaddingValues(10.dp * dimens.scale)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Stars
                                        Row(horizontalArrangement = Arrangement.Center) {
                                            repeat(badge.stars) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFBBF24),
                                                    modifier = Modifier.size(12.dp * dimens.scale)
                                                )
                                            }
                                            repeat((5 - badge.stars).coerceAtLeast(0)) {
                                                Icon(
                                                    imageVector = Icons.Default.StarBorder,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFBBF24).copy(alpha = 0.4f),
                                                    modifier = Modifier.size(12.dp * dimens.scale)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp * dimens.scale))
                                        Text(
                                            text = badge.name,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontSize = (dimens.textBody.value * 0.88f).sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = accent,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = badge.description,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = (dimens.textLabel.value * 0.8f).sp
                                            ),
                                            color = Color.White.copy(alpha = 0.6f),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            // Fill space if odd number
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Progress pill
            EnterAnimation(delay = 600) {
                        SolidCard(
                            modifier = Modifier.wrapContentSize(),
                            shape = RoundedCornerShape(50),
                            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.1f),
                            borderColor = Color(0xFFF59E0B).copy(alpha = 0.3f),
                            contentPadding = PaddingValues(horizontal = 20.dp * dimens.scale, vertical = 8.dp * dimens.scale)
                        ) {
                            Text(
                                text = stringResource(R.string.spotlight_badges_count, page.totalEarned, page.totalPossible),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = (dimens.textBody.value * 0.85f).sp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            EnterAnimation(delay = 800) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = dimens.textBody),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LevelUpPage(page: SpotlightStoryPage.LevelUp) {
    val inCapture = LocalInCaptureContext.current
    // Energy/power-up design — orange theme, horizontal glow streak, counter animation
    val levelAnim = remember { Animatable(0f) }
    val xpAnim   = remember { Animatable(0f) }
    val barAnim  = remember { Animatable(0f) }

    LaunchedEffect(page.currentLevel) {
        if (inCapture) {
            levelAnim.snapTo(page.currentLevel.toFloat())
        } else {
            delay(300)
            levelAnim.animateTo(page.currentLevel.toFloat(), tween(1000, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(page.xpEarnedThisPeriod) {
        if (inCapture) {
            xpAnim.snapTo(page.xpEarnedThisPeriod.toFloat())
        } else {
            delay(500)
            xpAnim.animateTo(page.xpEarnedThisPeriod.toFloat(), tween(1200, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(page.levelProgress) {
        if (inCapture) {
            barAnim.snapTo(page.levelProgress.coerceIn(0f, 1f))
        } else {
            delay(800)
            barAnim.animateTo(page.levelProgress.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "lvl")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f, targetValue = 0.38f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pa"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Horizontal streak across the center — distinctly different from radial glow
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp * dimens.scale)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFF59E0B).copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // "LEVEL UP" pill chip header
            EnterAnimation(delay = 0) {
                GlassCard(
                    modifier = Modifier.wrapContentSize(),
                    shape = RoundedCornerShape(50),
                    backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                    contentPadding = PaddingValues(horizontal = 16.dp * dimens.scale, vertical = 6.dp * dimens.scale)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp * dimens.scale)
                        )
                        Spacer(Modifier.width(6.dp * dimens.scale))
                        Text(
                            text = stringResource(R.string.spotlight_level_up),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = dimens.textLabel,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (3f * dimens.scale).sp
                            ),
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }

            // Giant level number — much bigger than TitleEarned's text
            EnterAnimation(delay = 150) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.spotlight_level_label),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = dimens.textLabel,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (4f * dimens.scale).sp
                        ),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = levelAnim.value.toInt().toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = (dimens.textDisplay.value * 1.7f).coerceAtMost(120f).sp,
                            fontWeight = FontWeight.Black,
                            shadow = Shadow(
                                color = Color(0xFFF59E0B),
                                offset = Offset(0f, 0f),
                                blurRadius = 32f
                            )
                        ),
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            // XP Progress bar (tricolor)
            EnterAnimation(delay = 500) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.spotlight_lv_format, page.currentLevel),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = (dimens.textLabel.value * 0.85f).sp),
                            color = Color(0xFFFBBF24).copy(alpha = 0.7f)
                        )
                        Text(
                            text = stringResource(R.string.spotlight_lv_format, page.currentLevel + 1),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = (dimens.textLabel.value * 0.85f).sp),
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                    Spacer(Modifier.height(4.dp * dimens.scale))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp * dimens.scale)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barAnim.value)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFFFBBF24))
                                    )
                                )
                        )
                    }
                }
            }

            // XP gained stat row
            EnterAnimation(delay = 700) {
                        SolidCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.08f),
                            borderColor = Color(0xFFF59E0B).copy(alpha = 0.25f),
                            contentPadding = PaddingValues(16.dp * dimens.scale)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    val xpDisplayed = xpAnim.value.toLong()
                                    val xpText = if (xpDisplayed >= 1000) stringResource(R.string.spotlight_xp_format_k, xpDisplayed / 1000f) else stringResource(R.string.spotlight_xp_format, xpDisplayed)
                                    Text(
                                        text = "+$xpText XP",
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontSize = dimens.textTitle,
                                            fontWeight = FontWeight.Black
                                        ),
                                        color = Color(0xFFFBBF24)
                                    )
                                    Text(
                                        text = stringResource(R.string.spotlight_earned_this_period),
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                                        color = Color.White.copy(alpha = 0.55f)
                                    )
                                }
                                Text(
                                    text = page.currentTitle,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = (dimens.textBody.value * 0.85f).sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFFBBF24).copy(alpha = 0.8f)
                                )
                            }
                        }
            }

            EnterAnimation(delay = 900) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (dimens.textBody.value * 0.9f).sp),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun TitleEarnedPage(page: SpotlightStoryPage.TitleEarned) {
    val inCapture = LocalInCaptureContext.current
    // Completely distinct design: ceremonial scroll/achievement unveil
    // Centered jewel, old title fades above, new title rises from below
    val oldTitleAlpha = remember { Animatable(0f) }
    val newTitleOffset = remember { Animatable(80f) }
    val newTitleAlpha  = remember { Animatable(0f) }
    val ringAlpha      = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (inCapture) {
            oldTitleAlpha.snapTo(0f)
            newTitleOffset.snapTo(0f)
            newTitleAlpha.snapTo(1f)
        } else {
            delay(200)
            oldTitleAlpha.animateTo(1f, tween(400))
            delay(500)
            oldTitleAlpha.animateTo(0f, tween(350))
            delay(100)
            newTitleOffset.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
            newTitleAlpha.animateTo(1f, tween(500))
        }
    }
    LaunchedEffect(Unit) {
        if (inCapture) {
            ringAlpha.snapTo(1f)
        } else {
            delay(300)
            ringAlpha.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
        }
    }

    val titleColor = when (page.newTitle) {
        "Sound God"          -> Color(0xFFE879F9)
        "Audiophile"         -> Color(0xFFC084FC)
        "Music Legend"       -> Color(0xFF818CF8)
        "Music Connoisseur"  -> Color(0xFF60A5FA)
        "Dedicated Listener" -> Color(0xFF34D399)
        "Music Enthusiast"   -> Color(0xFFFBBF24)
        "Music Fan"          -> Color(0xFF94A3B8)
        else                 -> Color(0xFFC4B5FD)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "titlering")
    val ringRotate by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "rotate"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)

        // Decorative rotating halo ring drawn on Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(ringAlpha.value),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(220.dp * dimens.scale)
            ) {
                val strokePx = 2.dp.toPx()
                val radius = size.minDimension / 2 - strokePx
                // Dashed arc effect: draw multiple short arcs
                val dashCount = 24
                val sweepEach = 360f / dashCount
                for (i in 0 until dashCount) {
                    val startAngle = ringRotate + i * sweepEach
                    val arcAlpha = if (i % 2 == 0) 0.6f else 0.15f
                    drawArc(
                        color = titleColor.copy(alpha = arcAlpha),
                        startAngle = startAngle,
                        sweepAngle = sweepEach * 0.65f,
                        useCenter = false,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
            // Inner glow dot
            Box(
                modifier = Modifier
                    .size(16.dp * dimens.scale)
                    .background(
                        brush = Brush.radialGradient(listOf(titleColor, Color.Transparent)),
                        shape = CircleShape
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = dimens.screenTopPadding,
                    start = dimens.horizontalPadding,
                    end = dimens.horizontalPadding,
                    bottom = dimens.screenBottomPadding
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            EnterAnimation(delay = 0) {
                Text(
                    text = stringResource(R.string.spotlight_new_title),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = dimens.textLabel,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (4f * dimens.scale).sp
                    ),
                    color = titleColor.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Animated title swap area
            Box(
                modifier = Modifier
                    .height(100.dp * dimens.scale)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Old title — fades in then out
                if (page.previousTitle.isNotBlank()) {
                    Text(
                        text = page.previousTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = (dimens.textTitle.value * 0.9f).sp,
                            textDecoration = TextDecoration.LineThrough
                        ),
                        color = Color.White.copy(alpha = oldTitleAlpha.value * 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
                // New title — rises from below
                Text(
                    text = page.newTitle,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = (dimens.textHeadline.value * 1.1f).sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(
                            color = titleColor.copy(alpha = 0.8f),
                            offset = Offset(0f, 6f),
                            blurRadius = 24f
                        )
                    ),
                    color = titleColor.copy(alpha = newTitleAlpha.value),
                    modifier = Modifier.offset(y = newTitleOffset.value.dp),
                    textAlign = TextAlign.Center
                )
            }

            // Stats row
            EnterAnimation(delay = 1400) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
                ) {
                    listOf(
                        stringResource(R.string.spotlight_lv_format, page.currentLevel) to stringResource(R.string.spotlight_level),
                        "${page.uniqueArtists}" to stringResource(R.string.spotlight_artists_discovered)
                    ).forEach { (value, label) ->
                        SolidCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(dimens.cardCornerRadius),
                            backgroundColor = titleColor.copy(alpha = 0.08f),
                            borderColor = titleColor.copy(alpha = 0.25f),
                            contentPadding = PaddingValues(14.dp * dimens.scale)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontSize = dimens.textTitle,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = titleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (dimens.textLabel.value * 0.85f).sp),
                                    color = Color.White.copy(alpha = 0.55f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            EnterAnimation(delay = 1600) {
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (dimens.textBody.value * 0.9f).sp),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
