package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.rememberReducedMotion
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize

/**
 * Welcome onboarding screen in three layers:
 *   back · native revolving notes around the head,
 *   mid  · welcome.png fullscreen art,
 *   top  · headline + CTA overlaid on the image.
 * Entry plays once (backdrop fades, copy rises); the orbit revolves
 * slowly and subtly underneath. Honors reduced motion settings.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current

    // Staggered entry, played ONCE: backdrop fades, headline lands in two
    // beats (line 1, then line 2 + CTA 80ms later) on a soft spring — the
    // hook without eagerness. Orbit keeps revolving subtly underneath.
    val figureEntry = remember { Animatable(0f) }
    val headlineEntry = remember { Animatable(0f) }
    val ctaEntry = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            figureEntry.snapTo(1f)
            headlineEntry.snapTo(1f)
            ctaEntry.snapTo(1f)
        } else {
            launch { figureEntry.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            launch {
                delay(150)
                headlineEntry.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy))
            }
            launch {
                delay(230)
                ctaEntry.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy))
            }
        }
    }

    // Full-bleed: the art owns the whole screen, copy sits on top.
    DeepOceanBackground(
        modifier = Modifier.fillMaxSize(),
    ) {
        // ── BACK + MID — orbit behind, fitted art above, one entry fade ──
        // No slide: fitted art must not translate. No zoom: the full
        // image is always visible.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = figureEntry.value },
        ) {
            WelcomeBackdrop(modifier = Modifier.fillMaxSize())
        }

        // ── TOP — legibility scrim + headline and call to action ──
        // Gentle bottom blend so the copy melts into the artwork.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        brush =
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color(0xFF0A0E0E).copy(alpha = 0.72f),
                            ),
                    ),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = adaptiveSizeByCategory(24.dp, 22.dp, 20.dp))
                    .padding(bottom = rememberScreenHeightPercentage(0.03f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val headlineSize = adaptiveTextUnitByCategory(32.sp, 29.sp, 26.sp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = headlineEntry.value
                    translationY = (1f - headlineEntry.value) * 24f
                }
            ) {
                Text(
                    text = stringResource(R.string.welcome_headline_1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = headlineSize,
                    textAlign = TextAlign.Center,
                    lineHeight = adaptiveTextUnitByCategory(38.sp, 35.sp, 32.sp),
                )
                Text(
                    text = stringResource(R.string.welcome_headline_2),
                    style =
                        MaterialTheme.typography.headlineMedium.copy(
                            brush = Brush.linearGradient(listOf(Color.White, TempoPrimary)),
                        ),
                    fontWeight = FontWeight.Bold,
                    fontSize = headlineSize,
                    textAlign = TextAlign.Center,
                    lineHeight = adaptiveTextUnitByCategory(38.sp, 35.sp, 32.sp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onGetStarted()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = ctaEntry.value
                            translationY = (1f - ctaEntry.value) * 24f
                        }
                        .height(scaledSize(54.dp, 0.85f, 1.1f)),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = TempoPrimary,
                        contentColor = TextOnAccent,
                    ),
                shape = RoundedCornerShape(18.dp),
                elevation =
                    ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 4.dp,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.welcome_get_started),
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Skip — rendered LAST to sit on top (z-ordering in Box)
        TextButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSkip()
            },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)),
        ) {
            Text(
                text = stringResource(R.string.welcome_skip),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
