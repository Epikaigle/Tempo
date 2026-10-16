package me.avinas.tempo.ui.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.TempoDialogPrimaryButton
import me.avinas.tempo.ui.components.TempoDialogSecondaryButton
import me.avinas.tempo.ui.components.TempoIcons
import me.avinas.tempo.ui.theme.*

/**
 * Bottom sheet prompting the user for a 1-5 star rating before opening Google Play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateAppBottomSheet(
    onDismiss: () -> Unit,
    onRate: () -> Unit,
    isSubmitting: Boolean = false
) {
    var selectedRating by remember { mutableIntStateOf(5) }
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TempoSurfaceDialog,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.22f))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Clean single-tile header badge
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GoldPrimary.copy(alpha = 0.08f))
                    .border(1.dp, GoldPrimary.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = TempoIcons.StarFilled,
                    contentDescription = null,
                    tint = GoldPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.rate_enjoying),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.rate_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = TextSecondary,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Interactive 5-Star Rating Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (starIndex in 1..5) {
                    val isFilled = starIndex <= selectedRating
                    val scale by animateFloatAsState(
                        targetValue = if (starIndex == selectedRating) 1.15f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "starScale$starIndex"
                    )
                    val starColor by animateColorAsState(
                        targetValue = if (isFilled) GoldPrimary else TextQuaternary,
                        label = "starColor$starIndex"
                    )

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .scale(scale)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isFilled) GoldPrimary.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.02f)
                            )
                            .border(
                                0.5.dp,
                                if (isFilled) GoldPrimary.copy(alpha = 0.20f) else GlassBorderSoft,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedRating = starIndex
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFilled) TempoIcons.StarFilled else TempoIcons.StarOutline,
                            contentDescription = "Rate $starIndex stars",
                            tint = starColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic sentiment micro-label
            val sentimentText = when (selectedRating) {
                5 -> "5.0 ★ Exceptional music tracking"
                4 -> "4.0 ★ Great listening companion"
                3 -> "3.0 ★ Good experience"
                else -> "Help us craft a 5-star experience"
            }
            Text(
                text = sentimentText.uppercase(),
                style = KickerSmall,
                color = TextSecondary,
                letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Value Trust Badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrustBadge(text = "100% Ad-Free")
                TrustBadge(text = "Local-First")
                TrustBadge(text = "No Account")
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isSubmitting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = TempoPrimary,
                        strokeWidth = 2.dp
                    )
                }
            } else {
                TempoDialogPrimaryButton(
                    text = stringResource(R.string.rate_now),
                    onClick = onRate,
                    icon = TempoIcons.GooglePlay,
                    containerColor = TempoPrimary,
                    contentColor = TextOnAccent
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            TempoDialogSecondaryButton(
                text = stringResource(R.string.rate_not_now),
                onClick = onDismiss,
                enabled = !isSubmitting
            )
        }
    }
}

@Composable
private fun TrustBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}
