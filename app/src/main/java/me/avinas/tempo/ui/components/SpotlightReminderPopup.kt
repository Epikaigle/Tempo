package me.avinas.tempo.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    val (title, subtitle, accentColor) = when (type) {
        SpotlightReminderType.WEEKLY -> Triple(
            "Your Weekly Wrapped Is Ready",
            "Check out your listening story from $targetPeriodLabel",
            TempoPrimary
        )
        SpotlightReminderType.MONTHLY -> Triple(
            "Your Monthly Wrapped Is Ready",
            "Check out your listening story from $targetPeriodLabel",
            TempoAccent
        )
        SpotlightReminderType.YEARLY -> Triple(
            "Your Yearly Wrapped Is Here",
            "Dive into your $targetPeriodLabel listening journey",
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
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(animationSpec = tween(250)),
                    exit = scaleOut(targetScale = 0.92f, animationSpec = tween(180)) +
                            fadeOut(animationSpec = tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(horizontal = 24.dp)
                    ) {
                        TempoDialogSurface {
                            Spacer(modifier = Modifier.height(8.dp))

                            TempoDialogIcon(
                                icon = Icons.Default.AutoAwesome,
                                tint = accentColor,
                                size = 56
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            TempoDialogTitle(text = title)

                            Spacer(modifier = Modifier.height(8.dp))

                            TempoDialogBody(text = subtitle)

                            Spacer(modifier = Modifier.height(24.dp))

                            TempoDialogPrimaryButton(
                                text = actionText,
                                onClick = { dismissAnimated(onViewStory) }
                            )

                            Spacer(modifier = Modifier.height(4.dp))

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
