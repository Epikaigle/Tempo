package me.avinas.tempo.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.components.CelebrationParticlesCanvas
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.*
import java.util.Locale
import kotlin.math.sin

/**
 * Fullscreen celebration overlay shown when the user unlocks or upgrades badges.
 * Supports paging through multiple badges, sharing, and reduced motion.
 */
@Composable
fun BadgeCelebrationOverlay(
    badges: List<Badge>,
    userName: String = "Listener",
    profileImagePath: String? = null,
    onDismiss: () -> Unit,
    onShareBadge: ((Badge) -> Unit)? = null
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val badge = badges.getOrNull(currentIndex)

    if (badge == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val intrinsicColor = remember(badge.badgeId) { getUniqueBadgeColor(badge.badgeId) }
    val rarity = remember(badge.badgeId) { GamificationEngine.getRarity(badge.badgeId) }
    val rarityColor = remember(rarity) { getRarityColor(rarity) }
    val xpEarned = remember(badge.badgeId, badge.stars) {
        GamificationEngine.getBadgeXpContribution(badge.badgeId, badge.stars)
    }

    val reducedMotion = rememberReducedMotion()
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    var isClosing by remember { mutableStateOf(false) }

    // Spatial emergence physics
    val scrimAlpha = remember { Animatable(0f) }
    val emblemScale = remember { Animatable(if (reducedMotion) 0.85f else 0.28f) }
    val emblemAlpha = remember { Animatable(0f) }
    val glowScale = remember { Animatable(0.20f) }
    val contentAlpha = remember { Animatable(0f) }
    val contentOffsetY = remember { Animatable(if (reducedMotion) 0f else 48f) }

    // Sequential Star Stamping Animatable: 0f -> 1f triggers punchy stamp of current star
    val starStampAnim = remember(currentIndex) { Animatable(0f) }

    // Dismissal coordinator: smoothly recedes back into surface
    fun dismissSmoothly(onComplete: () -> Unit) {
        if (isClosing) return
        isClosing = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        coroutineScope.launch {
            launch {
                contentAlpha.animateTo(0f, tween(150, easing = FastOutLinearInEasing))
                contentOffsetY.animateTo(24f, tween(150, easing = FastOutLinearInEasing))
            }
            launch {
                emblemScale.animateTo(if (reducedMotion) 0.80f else 0.35f, tween(180, easing = FastOutLinearInEasing))
                emblemAlpha.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
                glowScale.animateTo(0.20f, tween(180, easing = FastOutLinearInEasing))
            }
            launch {
                scrimAlpha.animateTo(0f, tween(200, easing = FastOutLinearInEasing))
            }
            delay(210)
            onComplete()
        }
    }

    // Launch emergence on entry or badge step
    LaunchedEffect(badge.badgeId, currentIndex) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        launch {
            scrimAlpha.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        }
        launch {
            emblemAlpha.animateTo(1f, tween(120, easing = LinearEasing))
            emblemScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = if (reducedMotion) 1f else 0.58f,
                    stiffness = if (reducedMotion) 400f else 280f
                )
            )
        }
        launch {
            glowScale.animateTo(
                targetValue = 1.25f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            if (!reducedMotion) delay(80)
            launch {
                contentAlpha.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            }
            launch {
                contentOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                )
            }
        }
        // Delayed star stamp punch
        launch {
            delay(320)
            starStampAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF04060A).copy(alpha = 0.92f * scrimAlpha.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (currentIndex < badges.size - 1) {
                    currentIndex++
                } else {
                    dismissSmoothly(onDismiss)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // High-energy particle celebration burst
        CelebrationParticlesCanvas(
            colors = listOf(
                intrinsicColor,
                rarityColor,
                TempoWarning,
                TempoWarningBright,
                TempoAccent,
                Color.White
            ),
            particleCount = if (reducedMotion) 0 else 60,
            triggerKey = currentIndex
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            // Milestone Kicker
            val kickerText = when {
                badge.isMaxed -> "BADGE FULLY MASTERED · 5 STARS"
                badge.stars > 1 -> "STAR TIER PROMOTION · ★ ${badge.stars} OF 5"
                else -> "NEW TROPHY UNLOCKED"
            }
            val kickerColor = if (badge.isMaxed) TempoWarningBright else intrinsicColor

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }
            ) {
                Icon(
                    imageVector = if (badge.isMaxed) Icons.Default.AutoAwesome else Icons.Rounded.EmojiEvents,
                    contentDescription = null,
                    tint = kickerColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = kickerText,
                    style = KickerSmall,
                    fontWeight = FontWeight.Bold,
                    color = kickerColor,
                    letterSpacing = 1.5.sp
                )
            }

            // Emblem Stage with Blooming Aura
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                // Blooming ambient glow aura behind the emblem
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer {
                            scaleX = glowScale.value
                            scaleY = glowScale.value
                            alpha = (0.55f * emblemAlpha.value).coerceIn(0f, 0.55f)
                        }
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    intrinsicColor.copy(alpha = 0.50f),
                                    intrinsicColor.copy(alpha = 0.0f)
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Emblem in 3D perspective emergence
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = emblemScale.value
                        scaleY = emblemScale.value
                        alpha = emblemAlpha.value
                        cameraDistance = 12f * density
                    }
                ) {
                    BadgeEmblem(
                        badge = badge,
                        intrinsicColor = intrinsicColor,
                        modifier = Modifier.size(126.dp)
                    )
                }
            }

            // Content Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = contentOffsetY.value * density
                    }
            ) {
                // Badge Name & Description
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = badge.name,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = badge.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Metadata Chips: Rarity, Category & XP
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rarity pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(rarityColor.copy(alpha = 0.14f))
                            .border(0.8.dp, rarityColor.copy(alpha = 0.40f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = rarity.label.uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = rarityColor,
                            letterSpacing = 1.sp
                        )
                    }

                    // XP Reward Chip
                    val rewardXp = if (xpEarned > 0) xpEarned else rarity.xpPerStar.toLong()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(TempoWarning.copy(alpha = 0.14f))
                            .border(0.8.dp, TempoWarning.copy(alpha = 0.40f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = TempoWarning,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "+$rewardXp XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TempoWarning,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                // Interactive Star Stamping Display Card
                GlassCard(
                    variant = GlassCardVariant.Obsidian,
                    shape = RoundedCornerShape(20.dp),
                    borderColor = intrinsicColor.copy(alpha = 0.35f),
                    borderWidth = 0.8.dp,
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "TROPHY STAR PROGRESSION",
                            style = KickerSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextTertiary,
                            letterSpacing = 1.2.sp
                        )

                        // 5-Star Row with sequential stamping
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (starIndex in 1..5) {
                                val isEarnedStar = starIndex <= badge.stars
                                val isNewlyEarned = starIndex == badge.stars

                                val starScale = if (isNewlyEarned) {
                                    lerp(0.4f, 1f, starStampAnim.value)
                                } else 1f

                                val starTint = if (isEarnedStar) {
                                    if (badge.isMaxed) intrinsicColor else TempoWarningBright
                                } else Color.White.copy(alpha = 0.12f)

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    if (isNewlyEarned && starStampAnim.value > 0.05f) {
                                        // Radiant starburst ring behind newest stamped star
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp * starStampAnim.value)
                                                .background(
                                                    brush = Brush.radialGradient(
                                                        listOf(
                                                            TempoWarningBright.copy(alpha = (1f - starStampAnim.value) * 0.7f),
                                                            Color.Transparent
                                                        )
                                                    ),
                                                    shape = CircleShape
                                                )
                                        )
                                    }

                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = starTint,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer {
                                                scaleX = starScale
                                                scaleY = starScale
                                            }
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (badge.isMaxed) "All 5 Star Tiers Completed · Mastered"
                                   else "${badge.progress} / ${badge.maxProgress} towards Star ${badge.stars + 1}",
                            style = CaptionSmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Multi-Badge Pagination Dots if more than 1 badge
                if (badges.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        badges.forEachIndexed { idx, _ ->
                            val active = idx == currentIndex
                            Box(
                                modifier = Modifier
                                    .size(if (active) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (active) intrinsicColor else Color.White.copy(alpha = 0.25f))
                            )
                        }
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Share Button
                    if (onShareBadge != null) {
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onShareBadge(badge)
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                GlassBorderMedium
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TempoDarkSurfaceElevated.copy(alpha = 0.6f),
                                contentColor = TextPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Next or Continue Button
                    Button(
                        onClick = {
                            if (currentIndex < badges.size - 1) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentIndex++
                            } else {
                                dismissSmoothly(onDismiss)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = intrinsicColor,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = if (currentIndex < badges.size - 1) "Next Trophy (${currentIndex + 1}/${badges.size})" else "Claim & Continue",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (currentIndex < badges.size - 1) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "Tap anywhere to continue",
                    style = CaptionSmall,
                    color = TextTertiary
                )
            }
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
