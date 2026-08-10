package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.LocalInCaptureContext

// =====================================================================
// SPOTLIGHT STORY — SHARED DESIGN SYSTEM
// ---------------------------------------------------------------------
// Every story page is built from the same small set of primitives:
//
//   StoryPageScaffold   - background slot + label header + content + footer
//   StoryGlassCard      - one card style used everywhere
//   StoryChip           - pill highlight (comparisons, badges, callouts)
//   StoryStatTile       - value-over-label stat card
//   StoryRankMiniCard   - compact ranked row used in top-charts grids
//   StoryHeroImage      - artwork with a soft radial glow
//   StoryProgressBar    - animated, capture-aware bar
//
// Shared motion language (see StoryTiming) and shared sizing
// (see SpotlightDimens / LocalSpotlightDimens) keep all pages visually
// and rhythmically consistent, and every animation snaps to its final
// state in capture mode (LocalInCaptureContext) so shared screenshots
// always render complete.
// =====================================================================

// ── Dimensions ──────────────────────────────────────────────────────

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
    // Reference height: 800dp (approx generic phone height).
    // Clamped between 0.75 (small phones) and 1.2 (large/tall phones)
    // so the UI doesn't look too tiny or too blown up.
    val scale = (maxHeight.value / 800f).coerceIn(0.75f, 1.2f)

    return remember(scale, maxHeight) {
        SpotlightDimens(
            scale = scale,

            screenTopPadding = (maxHeight * 0.08f).coerceAtLeast(32.dp),
            screenBottomPadding = (maxHeight * 0.15f).coerceAtLeast(130.dp), // Clears 'Share Your Story' button
            horizontalPadding = 24.dp,
            spacerSmall = (maxHeight * 0.015f).coerceAtLeast(8.dp),
            spacerMedium = (maxHeight * 0.025f).coerceAtLeast(16.dp),
            spacerLarge = (maxHeight * 0.05f).coerceAtLeast(32.dp),

            textDisplay = (88.sp.value * scale).sp,
            textHeadline = (32.sp.value * scale).sp,
            textTitle = (24.sp.value * scale).sp,
            textBody = (16.sp.value * scale).sp,
            textLabel = (12.sp.value * scale).sp,

            imageMain = (maxHeight * 0.25f).coerceIn(160.dp, 260.dp),
            imageList = (maxHeight * 0.06f).coerceIn(40.dp, 56.dp),
            imageGrid = (maxHeight * 0.035f).coerceIn(24.dp, 36.dp),
            bubbleMain = (maxHeight * 0.3f).coerceIn(200.dp, 300.dp),

            cardCornerRadius = 20.dp * scale,
            gridSpacing = 8.dp
        )
    }
}

/** Provided by [StoryPageScaffold]; read by every Story* component. */
val LocalSpotlightDimens = staticCompositionLocalOf<SpotlightDimens> {
    error("LocalSpotlightDimens used outside of StoryPageScaffold")
}

// ── Motion ──────────────────────────────────────────────────────────

/**
 * Single source of truth for story choreography. Every page reveals in
 * the same order: header → hero → content → footer, with list items
 * cascading via [stagger].
 */
object StoryTiming {
    const val Header = 0
    const val Hero = 150
    const val HeroSub = 250
    const val Content = 350
    const val ContentSub = 500
    const val Footer = 650

    fun stagger(index: Int, base: Int = Content, step: Int = 70): Int = base + index * step
}

/** Standard page-entry animation: short fade + rise. No-ops in capture mode. */
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
            delay((delay + 50).toLong())
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

/** Capture-aware animated number. Snaps instantly when capturing a share image. */
@Composable
fun rememberStoryCountUp(
    targetValue: Float,
    delayMillis: Int = 300,
    durationMillis: Int = 1400
): Float {
    val inCapture = LocalInCaptureContext.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(targetValue) {
        if (inCapture) {
            anim.snapTo(targetValue)
        } else {
            delay(delayMillis.toLong())
            anim.animateTo(targetValue, tween(durationMillis, easing = FastOutSlowInEasing))
        }
    }
    return anim.value
}

/** Capture-aware animated fractions for bar charts, cascading per index. */
@Composable
fun rememberStoryBarFractions(
    targets: List<Float>,
    baseDelayMillis: Int = StoryTiming.Content,
    stepDelayMillis: Int = 80,
    durationMillis: Int = 800
): List<Float> {
    val inCapture = LocalInCaptureContext.current
    val anims = remember(targets.size) { List(targets.size) { Animatable(0f) } }
    LaunchedEffect(targets) {
        if (inCapture) {
            anims.forEachIndexed { i, anim -> anim.snapTo(targets[i].coerceIn(0f, 1f)) }
        } else {
            anims.forEachIndexed { i, anim ->
                launch {
                    delay((baseDelayMillis + i * stepDelayMillis).toLong())
                    anim.animateTo(targets[i].coerceIn(0f, 1f), tween(durationMillis, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    return anims.map { it.value }
}

// ── Page scaffold ───────────────────────────────────────────────────

/**
 * The single layout every story page is built on:
 *
 * ```
 * ┌──────────────────────────┐
 * │  background slot (glows) │
 * │  StoryLabel (header)     │
 * │  ┌────────────────────┐  │
 * │  │ content (weight 1) │  │
 * │  └────────────────────┘  │
 * │  conversational footer   │
 * └──────────────────────────┘
 * ```
 *
 * Provides [LocalSpotlightDimens], applies the standard safe padding and
 * runs the shared reveal choreography. Pages only declare what makes them
 * unique — their background ambience and their content.
 */
@Composable
fun StoryPageScaffold(
    label: String?,
    modifier: Modifier = Modifier,
    labelIcon: ImageVector? = null,
    labelColor: Color = Color.White.copy(alpha = 0.85f),
    conversationalText: String? = null,
    contentArrangement: Arrangement.Vertical = Arrangement.SpaceEvenly,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val dimens = rememberSpotlightDimens(maxHeight)
        CompositionLocalProvider(LocalSpotlightDimens provides dimens) {
            background()

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
                if (label != null) {
                    EnterAnimation(delay = StoryTiming.Header) {
                        StoryLabel(text = label, icon = labelIcon, color = labelColor)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = contentArrangement
                ) {
                    content()
                }

                if (conversationalText != null) {
                    EnterAnimation(delay = StoryTiming.Footer) {
                        StoryConversationalText(text = conversationalText)
                    }
                }
            }
        }
    }
}

// ── Typography primitives ───────────────────────────────────────────

/** Consistent page header: small bold label, centered, optional leading icon. */
@Composable
fun StoryLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Color.White.copy(alpha = 0.85f)
) {
    val dimens = LocalSpotlightDimens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp * dimens.scale)
            )
            Spacer(modifier = Modifier.width(8.dp * dimens.scale))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The poetic one-liner that closes a page — always italic, centered, dimmed. */
@Composable
fun StoryConversationalText(
    text: String,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSpotlightDimens.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = dimens.textBody,
            fontStyle = FontStyle.Italic
        ),
        color = Color.White.copy(alpha = 0.75f),
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
    )
}

// ── Cards ───────────────────────────────────────────────────────────

/** The one and only card style on story pages: frosted fill + hairline border. */
@Composable
fun StoryGlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(LocalSpotlightDimens.current.cardCornerRadius),
    backgroundColor: Color = Color.White.copy(alpha = 0.07f),
    borderColor: Color = Color.White.copy(alpha = 0.13f),
    contentPadding: PaddingValues = PaddingValues(14.dp),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

/** Pill-shaped highlight used for comparisons, callouts and metadata. */
@Composable
fun StoryChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: Color = Color.White,
    backgroundColor: Color = accentColor.copy(alpha = 0.12f),
    borderColor: Color = accentColor.copy(alpha = 0.3f)
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(50),
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentPadding = PaddingValues(
            horizontal = 16.dp * dimens.scale,
            vertical = 7.dp * dimens.scale
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp * dimens.scale)
                )
                Spacer(modifier = Modifier.width(6.dp * dimens.scale))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = (dimens.textBody.value * 0.85f).sp
                ),
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Value-over-label stat card. All story stats (plays, days, XP…) use this. */
@Composable
fun StoryStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White,
    backgroundColor: Color = Color.White.copy(alpha = 0.06f),
    borderColor: Color = Color.White.copy(alpha = 0.14f),
    valueFontSize: TextUnit = LocalSpotlightDimens.current.textTitle
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentPadding = PaddingValues(
            horizontal = 12.dp * dimens.scale,
            vertical = 14.dp * dimens.scale
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = valueFontSize),
                fontWeight = FontWeight.Black,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp * dimens.scale))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = dimens.textLabel),
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Ranked list items ───────────────────────────────────────────────

/** Compact ranked cell used in the 2-column grids of the top-charts pages. */
@Composable
fun StoryRankMiniCard(
    rank: Int,
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    imageShape: Shape = CircleShape
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
        backgroundColor = Color.White.copy(alpha = 0.07f),
        borderColor = Color.White.copy(alpha = 0.13f),
        contentPadding = PaddingValues(
            horizontal = 8.dp * dimens.scale,
            vertical = 6.dp * dimens.scale
        ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.width(22.dp * dimens.scale)
            )
            CachedAsyncImage(
                imageUrl = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(dimens.imageGrid).clip(imageShape),
                contentScale = ContentScale.Crop,
                allowHardware = false
            )
            Spacer(modifier = Modifier.width(8.dp * dimens.scale))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Full-width ranked row used for the final (#10) slot of top-charts pages. */
@Composable
fun StoryRankFooterRow(
    rank: Int,
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    imageShape: Shape = CircleShape
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dimens.cardCornerRadius * 0.6f),
        backgroundColor = Color.White.copy(alpha = 0.1f),
        borderColor = Color.White.copy(alpha = 0.2f),
        contentPadding = PaddingValues(12.dp * dimens.scale),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.width(30.dp * dimens.scale)
            )
            CachedAsyncImage(
                imageUrl = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(dimens.imageList).clip(imageShape),
                contentScale = ContentScale.Crop,
                allowHardware = false
            )
            Spacer(modifier = Modifier.width(12.dp * dimens.scale))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = dimens.textBody),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (dimens.textBody.value * 0.85f).sp
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Media & charts ──────────────────────────────────────────────────

/** Artwork with a soft radial glow behind it. Used for hero images on every chart page. */
@Composable
fun StoryHeroImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = LocalSpotlightDimens.current.imageMain,
    shape: Shape = CircleShape,
    glowColor: Color = Color.White.copy(alpha = 0.25f),
    borderColor: Color = Color.White.copy(alpha = 0.2f),
    borderWidth: Dp = 3.dp,
    blurRadius: Dp = 0.dp
) {
    val dimens = LocalSpotlightDimens.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size + 40.dp * dimens.scale)
                .background(
                    brush = Brush.radialGradient(listOf(glowColor, Color.Transparent)),
                    shape = shape
                )
        )
        CachedAsyncImage(
            imageUrl = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(borderWidth * dimens.scale, borderColor, shape)
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
            contentScale = ContentScale.Crop,
            allowHardware = false
        )
    }
}

/** Animated, capture-aware horizontal bar with a soft gradient fill. */
@Composable
fun StoryProgressBar(
    targetFraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    delayMillis: Int = StoryTiming.Content,
    trackColor: Color = Color.White.copy(alpha = 0.1f),
    gradientColors: List<Color>? = null
) {
    val dimens = LocalSpotlightDimens.current
    val fraction = rememberStoryCountUp(
        targetValue = targetFraction.coerceIn(0f, 1f),
        delayMillis = delayMillis,
        durationMillis = 900
    )
    val fillBrush = remember(gradientColors, color) {
        Brush.horizontalGradient(gradientColors ?: listOf(color.copy(alpha = 0.75f), color))
    }
    Box(
        modifier = modifier
            .height(10.dp * dimens.scale)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(fillBrush)
        )
    }
}

/** Breathing radial glow used to give pages ambient depth behind the content. */
@Composable
fun StoryPulsingGlow(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 320.dp,
    minAlpha: Float = 0.10f,
    maxAlpha: Float = 0.30f,
    periodMillis: Int = 2200,
    alignment: Alignment = Alignment.Center
) {
    val dimens = LocalSpotlightDimens.current
    val transition = rememberInfiniteTransition(label = "StoryGlow")
    val glowScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = minAlpha,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    Box(modifier = modifier.fillMaxSize(), contentAlignment = alignment) {
        Box(
            modifier = Modifier
                .size(size * dimens.scale)
                .scale(glowScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = glowAlpha),
                            color.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}

// ── Formatting ──────────────────────────────────────────────────────

/** "2h 14m" / "45m" using the shared story strings. */
@Composable
fun formatStoryMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(R.string.spotlight_time_hours, hours, minutes)
    } else {
        stringResource(R.string.spotlight_time_minutes, minutes)
    }
}
