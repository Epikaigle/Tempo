package me.avinas.tempo.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.spotlight.SpotlightPeriodFormatter
import me.avinas.tempo.ui.theme.*

enum class SpotlightReminderType {
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * Dialog prompting the user to view their weekly, monthly, or yearly Spotlight story
 * once new listening data is ready.
 */
@Composable
fun SpotlightReminderPopup(
    type: SpotlightReminderType,
    timeRange: TimeRange? = null,
    onDismiss: () -> Unit,
    onViewStory: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { visible = true }

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

    val (kickerText, title, subtitle, accentColor) = when (type) {
        SpotlightReminderType.WEEKLY -> Quad(
            "WEEKLY STORY",
            "Your Weekly Wrapped",
            "Your listening journey from $targetPeriodLabel is ready to explore.",
            TempoPrimary
        )
        SpotlightReminderType.MONTHLY -> Quad(
            "MONTHLY MILESTONE",
            "Your Monthly Wrapped",
            "Your listening story from $targetPeriodLabel is now unlocked.",
            TempoAccent
        )
        SpotlightReminderType.YEARLY -> Quad(
            "ANNUAL WRAPPED",
            "Your Yearly Wrapped",
            "Dive into your complete $targetPeriodLabel sonic journey.",
            TempoPrimary
        )
    }

    fun dismissAnimated(action: () -> Unit) {
        visible = false
        coroutineScope.launch {
            delay(200)
            action()
        }
    }

    Dialog(
        onDismissRequest = { dismissAnimated(onDismiss) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(animationSpec = tween(250)),
                    exit = scaleOut(targetScale = 0.90f, animationSpec = tween(180)) +
                            fadeOut(animationSpec = tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .padding(horizontal = 16.dp)
                    ) {
                        TempoDialogSurface {
                            Spacer(modifier = Modifier.height(4.dp))

                            // Top Kicker Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(accentColor.copy(alpha = 0.08f))
                                    .border(1.dp, accentColor.copy(alpha = 0.20f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = kickerText,
                                    style = KickerSmall,
                                    color = accentColor,
                                    letterSpacing = 0.8.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Clean single-tile celebration emblem
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(accentColor.copy(alpha = 0.10f))
                                    .border(1.dp, accentColor.copy(alpha = 0.22f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = TempoIcons.SparkleBurst,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Display Headline
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Subtitle
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                lineHeight = 21.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // Feature Preview Pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SpotlightFeatureChip(text = "Top Songs")
                                SpotlightFeatureChip(text = "Sonic Aura")
                                SpotlightFeatureChip(text = "Listening Stats")
                            }

                            Spacer(modifier = Modifier.height(26.dp))

                            TempoDialogPrimaryButton(
                                text = actionText,
                                onClick = { dismissAnimated(onViewStory) },
                                icon = TempoIcons.SparkleBurst,
                                containerColor = accentColor,
                                contentColor = TextOnAccent
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            TempoDialogSecondaryButton(
                                text = "Not now",
                                onClick = { dismissAnimated(onDismiss) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotlightFeatureChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
