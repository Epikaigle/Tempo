package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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

/**
 * Privacy-focused onboarding screen that reassures users about data safety.
 * Explains: local-only storage, no accounts, music-only notification reading, open source.
 */
@Composable
fun PrivacyExplainerScreen(
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
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
                // Dynamic, device-size aware spacing between Skip bar and main section
                val topSpacing = adaptiveSizeByCategory(
                    expanded = rememberScreenHeightPercentage(0.035f),
                    medium = 20.dp,
                    compact = 12.dp
                )
                Spacer(modifier = Modifier.height(topSpacing))

                // Shield — the trust hook: settles in once on a soft spring,
                // then breathes almost imperceptibly (±3%). Still under
                // reduced motion. The calm is what makes it magnetic, not a loop.
                // ponytail perf: breath State held unread — only the shield
                // leaf subscribes, so the screen body composes once. No loop
                // at all under reduced motion.
                val shieldContainerSize = rememberClampedHeightPercentage(0.10f, 70.dp, 95.dp)
                val reducedMotion = me.avinas.tempo.ui.theme.rememberReducedMotion()
                val shieldEntry = remember { Animatable(if (reducedMotion) 1f else 0f) }
                val breathState: State<Float> =
                    if (reducedMotion) {
                        remember { mutableFloatStateOf(0f) }
                    } else {
                        rememberInfiniteTransition(label = "shield_breath").animateFloat(
                            0f, 1f,
                            infiniteRepeatable(tween(3200, easing = LinearEasing)),
                            label = "breath"
                        )
                    }
                LaunchedEffect(reducedMotion) {
                    if (reducedMotion) shieldEntry.snapTo(1f)
                    else shieldEntry.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy))
                }

                ShieldWithBreath(
                    size = shieldContainerSize,
                    entry = shieldEntry.value,
                    breath = breathState
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.025f)))

                // Header
                Text(
                    text = stringResource(R.string.privacy_title),
                    style = if (isSmallScreen()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(30.sp, 26.sp, 22.sp)
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))

                Text(
                    text = stringResource(R.string.privacy_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 13.sp)
                )

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                // Privacy points with staggered animation
                val listVisible = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { listVisible.value = true }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(rememberScreenHeightPercentage(0.014f))
                ) {
                    // Item 1
                    AnimatedOpacity(delay = 200, visible = listVisible.value) {
                        PrivacyPoint(
                            kind = ClayKind.Phone,
                            iconColor = ClayTokens.Sky,
                            title = stringResource(R.string.privacy_local_title),
                            description = stringResource(R.string.privacy_local_desc)
                        )
                    }

                    // Item 2
                    AnimatedOpacity(delay = 400, visible = listVisible.value) {
                        PrivacyPoint(
                            kind = ClayKind.CloudOff,
                            iconColor = ClayTokens.Peach,
                            title = stringResource(R.string.privacy_no_cloud_title),
                            description = stringResource(R.string.privacy_no_cloud_desc)
                        )
                    }

                    // Item 3
                    AnimatedOpacity(delay = 600, visible = listVisible.value) {
                        PrivacyPoint(
                            kind = ClayKind.Bell,
                            iconColor = ClayTokens.Lavender,
                            title = stringResource(R.string.privacy_notif_title),
                            description = stringResource(R.string.privacy_notif_desc)
                        )
                    }

                    // Item 4
                    AnimatedOpacity(delay = 800, visible = listVisible.value) {
                        PrivacyPoint(
                            kind = ClayKind.Code,
                            iconColor = ClayTokens.Pink,
                            title = stringResource(R.string.privacy_open_source_title),
                            description = stringResource(R.string.privacy_open_source_desc)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                // Bottom quote - flat white pill (no glassmorphism) for strong contrast.
                // Last in the stagger so the eye ends on the promise.
                AnimatedOpacity(delay = 1000, visible = listVisible.value) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White)
                            .padding(
                                horizontal = adaptiveSizeByCategory(18.dp, 16.dp, 14.dp),
                                vertical = adaptiveSizeByCategory(14.dp, 12.dp, 10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.privacy_quote),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = TempoDarkBackground,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = adaptiveTextUnitByCategory(15.sp, 13.sp, 12.sp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Pinned CTA — same rect as every other step, never scrolled away.
            OnboardingFooter(
                text = stringResource(R.string.privacy_got_it),
                onClick = onNext
            )
        }
    }
}

@Composable
private fun ShieldWithBreath(
    size: Dp,
    entry: Float,
    breath: State<Float>
) {
    // This leaf alone re-enters composition on every breath frame.
    val b = breath.value
    val breathScale = 1f + 0.03f * kotlin.math.sin(b * 2f * kotlin.math.PI).toFloat()
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                alpha = entry
                val s = (0.85f + 0.15f * entry) * breathScale
                scaleX = s; scaleY = s
            },
        contentAlignment = Alignment.Center
    ) {
        ClayObject(
            kind = ClayKind.Shield,
            base = ClayTokens.Mint,
            size = size
        )
    }
}

@Composable
private fun PrivacyPoint(
    kind: ClayKind,
    iconColor: Color,
    title: String,
    description: String
) {
    // Clamped card height for consistency
    val cardVerticalPadding = rememberClampedHeightPercentage(0.012f, 8.dp, 12.dp)
    val checkboxSize = rememberClampedHeightPercentage(0.044f, 32.dp, 40.dp)
    
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = iconColor.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = adaptiveSizeByCategory(14.dp, 12.dp, 10.dp), vertical = cardVerticalPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clay object — same slot, a thing, not a glyph on a tile
            ClayObject(
                kind = kind,
                base = iconColor,
                size = checkboxSize
            )

            Spacer(modifier = Modifier.width(adaptiveSizeByCategory(14.dp, 12.dp, 10.dp)))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = adaptiveTextUnitByCategory(17.sp, 15.sp, 14.sp)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = adaptiveTextUnitByCategory(14.sp, 12.sp, 11.sp),
                    lineHeight = adaptiveTextUnitByCategory(20.sp, 17.sp, 15.sp)
                )
            }
        }
    }
}
