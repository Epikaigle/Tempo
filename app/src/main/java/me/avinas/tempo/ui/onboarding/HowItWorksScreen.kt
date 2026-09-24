package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.utils.adaptiveSize
import me.avinas.tempo.ui.utils.adaptiveTextUnit
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.isCompactScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.clay.ClayTokens
import kotlinx.coroutines.launch

/**
 * Educational screen explaining how Tempo's notification-based tracking works.
 * Shows a visual flow: Music App → Notification → Tempo → Local Stats
 */
@Composable
fun HowItWorksScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    // Flow cue: continuous dash loop. Phase wraps on multiples of
    // the dash period so Restart never snaps; the arrow bobs on a sine for
    // the same reason (old linear 1.0→1.12 scale visibly jumped each cycle).
    // ponytail perf: the State is held unread — only the two connectors that
    // draw it subscribe, so the screen stops recomposing 60fps. No loop at
    // all under reduced motion.
    val reducedMotion = me.avinas.tempo.ui.theme.rememberReducedMotion()
    val flowState: State<Float> =
        if (reducedMotion) {
            remember { mutableFloatStateOf(0.5f) }
        } else {
            rememberInfiniteTransition(label = "flow").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "arrowProgress"
            )
        }

    // Staggered arrival, played ONCE: rise + fade per step. Old
    // animateFloatAsState(target=1f) started at 1 so no stagger ever played.
    val step1 = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val step2 = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val step3 = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            step1.snapTo(1f); step2.snapTo(1f); step3.snapTo(1f)
        } else {
            launch { step1.animateTo(1f, tween(500, delayMillis = 200, easing = FastOutSlowInEasing)) }
            launch { step2.animateTo(1f, tween(500, delayMillis = 450, easing = FastOutSlowInEasing)) }
            launch { step3.animateTo(1f, tween(500, delayMillis = 700, easing = FastOutSlowInEasing)) }
        }
    }
    val step1Alpha = step1.value
    val step2Alpha = step2.value
    val step3Alpha = step3.value

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Dedicated Top Action Bar with Skip button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = adaptiveSizeByCategory(16.dp, 14.dp, 12.dp),
                        vertical = 4.dp
                    ),
                contentAlignment = Alignment.CenterEnd
            ) {
                val haptic = LocalHapticFeedback.current
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkip()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.welcome_skip),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Main Content Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp))
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Adaptive vertical spacing based on screen height
                val topSpacing = adaptiveSizeByCategory(
                    expanded = rememberScreenHeightPercentage(0.035f),
                    medium = 20.dp,
                    compact = 12.dp
                )
                Spacer(modifier = Modifier.height(topSpacing))

                val isSmall = isSmallScreen()
                // Header
                Text(
                    text = stringResource(R.string.how_it_works_title),
                    style = if (isSmall) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(30.sp, 26.sp, 22.sp)
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))

                Text(
                    text = stringResource(R.string.how_it_works_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 13.sp)
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                // Visual Flow: Three connected steps
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(rememberScreenHeightPercentage(0.018f))

                ) {
                    // Step 1: Music App
                    FlowStep(
                        modifier = Modifier.graphicsLayer {
                            alpha = step1Alpha
                            translationY = (1f - step1Alpha) * 24f
                        },
                        kind = ClayKind.Music,
                        iconColor = ClayTokens.Marigold, // marigold flower — joy/music
                        title = stringResource(R.string.how_it_works_step1_title),
                        subtitle = stringResource(R.string.how_it_works_step1_subtitle)
                    )

                    // Animated connector
                    FlowConnector(progress = flowState, alpha = step1Alpha)

                    // Step 2: Notification
                    FlowStep(
                        modifier = Modifier.graphicsLayer {
                            alpha = step2Alpha
                            translationY = (1f - step2Alpha) * 24f
                        },
                        kind = ClayKind.NotifCard,
                        iconColor = ClayTokens.Sky, // the notification itself, carrying a note
                        title = stringResource(R.string.how_it_works_step2_title),
                        subtitle = stringResource(R.string.how_it_works_step2_subtitle)
                    )

                    // Animated connector
                    FlowConnector(progress = flowState, alpha = step2Alpha)

                    // Step 3: Stats
                    FlowStep(
                        modifier = Modifier.graphicsLayer {
                            alpha = step3Alpha
                            translationY = (1f - step3Alpha) * 24f
                        },
                        kind = ClayKind.Chart,
                        iconColor = TempoPrimary,
                        title = stringResource(R.string.how_it_works_step3_title),
                        subtitle = stringResource(R.string.how_it_works_step3_subtitle)
                    )
                }

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.035f)))

                // Bottom info badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoBadge(kind = ClayKind.Apps, iconTint = ClayTokens.Lavender, text = stringResource(R.string.how_it_works_badge_apps))
                    InfoBadge(kind = ClayKind.Lock, iconTint = ClayTokens.Mint, text = stringResource(R.string.how_it_works_badge_local))
                    InfoBadge(kind = ClayKind.Bolt, iconTint = ClayTokens.Lemon, text = stringResource(R.string.how_it_works_badge_auto))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Pinned CTA — same rect as every other step, never scrolled away.
            OnboardingFooter(
                text = stringResource(R.string.how_it_works_next),
                onClick = onNext
            )
        }
    }
}

@Composable
private fun FlowStep(
    modifier: Modifier = Modifier,
    kind: ClayKind,
    iconColor: Color,
    title: String,
    subtitle: String
) {
    // Card height clamped to 70–90dp across screen sizes
    val cardHeight = rememberClampedHeightPercentage(0.095f, 70.dp, 90.dp)
    
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight),
        backgroundColor = iconColor.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = adaptiveSizeByCategory(18.dp, 14.dp, 12.dp), vertical = adaptiveSizeByCategory(14.dp, 10.dp, 8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Clay object in the same slot — a thing, not a glyph on a tile
            val iconContainerSize = rememberClampedHeightPercentage(0.058f, 40.dp, 52.dp)
            ClayObject(
                kind = kind,
                base = iconColor,
                size = iconContainerSize
            )

            Spacer(modifier = Modifier.width(adaptiveSizeByCategory(20.dp, 16.dp, 12.dp)))

            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = if (isSmallScreen()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(20.sp, 18.sp, 16.sp)
                )
                Spacer(modifier = Modifier.height(adaptiveSizeByCategory(4.dp, 2.dp, 0.dp)))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnitByCategory(15.sp, 13.sp, 12.sp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FlowConnector(
    progress: State<Float>,
    alpha: Float
) {
    // Clamped connector height
    val connectorHeight = rememberClampedHeightPercentage(0.024f, 16.dp, 24.dp)
    // This leaf alone subscribes to the loop — the screen body stays settled.
    val p = progress.value
    Box(
        modifier = Modifier
            .height(connectorHeight)
            .alpha(alpha * 0.6f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val dashWidth = 4.dp.toPx()
            val gapWidth = 4.dp.toPx()
            
            // Draw animated dashed line
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(size.width / 2, 0f),
                end = Offset(size.width / 2, size.height),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dashWidth, gapWidth),
                    phase = p * (dashWidth + gapWidth) * 2
                )
            )
        }
        
        // Arrow icon — sine bob so the Restart wrap never snaps
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .size(18.dp)
                .scale(1f + 0.06f * kotlin.math.sin(p * 2f * kotlin.math.PI).toFloat())
        )
    }
}

@Composable
private fun InfoBadge(
    kind: ClayKind,
    iconTint: Color,
    text: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val badgeSize = adaptiveSizeByCategory(44.dp, 40.dp, 36.dp)
        ClayObject(
            kind = kind,
            base = iconTint,
            size = badgeSize
        )
        Spacer(modifier = Modifier.height(adaptiveSizeByCategory(8.dp, 6.dp, 4.dp)))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = adaptiveTextUnitByCategory(13.sp, 11.sp, 10.sp)
        )
    }
}
