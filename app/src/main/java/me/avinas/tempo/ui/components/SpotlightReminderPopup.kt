package me.avinas.tempo.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSurfaceCard
import me.avinas.tempo.ui.theme.TempoSurfacePopup
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary

enum class SpotlightReminderType {
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * Popup reminder for Spotlight Story availability.
 * Shows on Sunday (Weekly), last day of month (Monthly), or December 1st (Yearly).
 */
@Composable
fun SpotlightReminderPopup(
    type: SpotlightReminderType,
    timeRange: TimeRange? = null,
    onDismiss: () -> Unit,
    onViewStory: () -> Unit
) {
    // Animation states
    var visible by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    val targetTimeRange = timeRange ?: when (type) {
        SpotlightReminderType.WEEKLY -> TimeRange.THIS_WEEK
        SpotlightReminderType.MONTHLY -> TimeRange.THIS_MONTH
        SpotlightReminderType.YEARLY -> TimeRange.THIS_YEAR
    }
    val targetPeriodLabel = remember(targetTimeRange) {
        SpotlightPeriodFormatter.periodLabel(targetTimeRange)
    }
    val actionText = remember(targetTimeRange) {
        SpotlightPeriodFormatter.viewStoryText(context, targetTimeRange)
    }

    // Determine messaging based on type
    val (title, subtitle, iconGradient) = when (type) {
        SpotlightReminderType.WEEKLY -> Triple(
            "Your Weekly Wrapped\nIs Ready!",
            "Check out your listening story from $targetPeriodLabel",
            listOf(TempoPrimary, TempoPrimaryMuted)
        )
        SpotlightReminderType.MONTHLY -> Triple(
            "Your Monthly Wrapped\nIs Ready!",
            "Check out your listening story from $targetPeriodLabel",
            listOf(TempoAccent, TempoPrimary)
        )
        SpotlightReminderType.YEARLY -> Triple(
            "Your Yearly Wrapped\nIs Here!",
            "Dive into your $targetPeriodLabel listening journey",
            listOf(TempoPrimary, TempoInfo)
        )
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // Scrim background with fade animation
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                // Main content with scale animation
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(
                        initialScale = 0.8f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = scaleOut(targetScale = 0.8f, animationSpec = tween(200)) + 
                           fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(horizontal = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(
                                    16.dp,
                                    RoundedCornerShape(28.dp),
                                    spotColor = Color.Black.copy(alpha = 0.5f)
                                )
                                .clip(RoundedCornerShape(28.dp))
                                .background(TempoSurfacePopup)
                                .border(1.dp, Divider, RoundedCornerShape(28.dp))
                        ) {
                            Box {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    // Close button (top-right)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton(
                                            onClick = {
                                                visible = false
                                                // Delay dismiss to allow exit animation
                                                coroutineScope.launch {
                                                    kotlinx.coroutines.delay(200)
                                                    onDismiss()
                                                }
                                            },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(
                                                    TempoSurfaceCard,
                                                    CircleShape
                                                )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = TextPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // Icon with gradient background
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .background(
                                                brush = Brush.linearGradient(colors = iconGradient),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Pulsing animation for icon
                                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                        val scale by infiniteTransition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 1.1f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1000, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "iconPulse"
                                        )
                                        
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .scale(scale)
                                        )
                                    }
                                    
                                    // Title
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        lineHeight = MaterialTheme.typography.headlineSmall.fontSize * 1.2
                                    )
                                    
                                    // Subtitle
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // View Story button
                                    Button(
                                        onClick = {
                                            visible = false
                                            coroutineScope.launch {
                                                kotlinx.coroutines.delay(200)
                                                onViewStory()
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.White,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(28.dp),
                                        elevation = ButtonDefaults.buttonElevation(
                                            defaultElevation = 4.dp,
                                            pressedElevation = 8.dp
                                        )
                                    ) {
                                        Text(
                                            text = actionText,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
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
