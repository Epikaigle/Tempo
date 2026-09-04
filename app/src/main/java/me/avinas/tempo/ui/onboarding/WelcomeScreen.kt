package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.rememberReducedMotion
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize

/** Native aspect ratio of the full_man_vector artwork (807 x 1509). */
private const val MAN_ART_ASPECT = 807f / 1509f

/**
 * Welcome onboarding screen with an animated hero illustration and call to action.
 * Honors reduced motion settings.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val isSmall = isSmallScreen()

    // Staggered entry: figure settles first, headline + CTA follow.
    val figureEntry = remember { Animatable(0f) }
    val copyEntry = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            figureEntry.snapTo(1f)
            copyEntry.snapTo(1f)
        } else {
            launch { figureEntry.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            delay(200)
            copyEntry.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }

    // One ambient loop: the glow behind the notes breathes. Nothing else moves.
    val glowTransition = rememberInfiniteTransition(label = "glow")
    val glowPulse by glowTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Figure sizing from real screen width; art owns the middle of the screen.
    val config = LocalConfiguration.current
    val figureWidth = (config.screenWidthDp * (if (isSmall) 0.62f else 0.72f)).dp
    val glowSize = figureWidth * 0.9f

    DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Hero figure — centered, optically lifted above the bottom stack ──
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = rememberScreenHeightPercentage(if (isSmall) 0.20f else 0.17f))
                .graphicsLayer {
                    alpha = figureEntry.value
                    translationY = (1f - figureEntry.value) * 64f
                }
        ) {
            // Glow behind the notes stream — the art's own light source.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = figureWidth * 0.18f, y = -figureWidth * 0.05f)
                    .size(glowSize)
                    .scale(glowPulse)
                    .graphicsLayer { alpha = figureEntry.value * 0.9f }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                TempoPrimary.copy(alpha = 0.26f),
                                TempoCyan.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            Image(
                painter = painterResource(R.drawable.full_man_vector),
                contentDescription = null, // decorative; the headline carries the meaning
                modifier = Modifier
                    .width(figureWidth)
                    .aspectRatio(MAN_ART_ASPECT)
            )
        }

        // Headline and call to action
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = adaptiveSizeByCategory(24.dp, 22.dp, 20.dp))
                .padding(bottom = rememberScreenHeightPercentage(0.03f))
                .graphicsLayer {
                    alpha = copyEntry.value
                    translationY = (1f - copyEntry.value) * 40f
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val headlineSize = adaptiveTextUnitByCategory(32.sp, 29.sp, 26.sp)
            Text(
                text = stringResource(R.string.welcome_headline_1),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = headlineSize,
                textAlign = TextAlign.Center,
                lineHeight = adaptiveTextUnitByCategory(38.sp, 35.sp, 32.sp)
            )
            Text(
                text = stringResource(R.string.welcome_headline_2),
                style = MaterialTheme.typography.headlineMedium.copy(
                    brush = Brush.linearGradient(listOf(Color.White, TempoPrimary))
                ),
                fontWeight = FontWeight.Bold,
                fontSize = headlineSize,
                textAlign = TextAlign.Center,
                lineHeight = adaptiveTextUnitByCategory(38.sp, 35.sp, 32.sp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledSize(54.dp, 0.85f, 1.1f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = TextOnAccent
                ),
                shape = RoundedCornerShape(18.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Text(
                    text = stringResource(R.string.welcome_get_started),
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Skip — rendered LAST to sit on top (z-ordering in Box)
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp))
        ) {
            Text(
                text = stringResource(R.string.welcome_skip),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
