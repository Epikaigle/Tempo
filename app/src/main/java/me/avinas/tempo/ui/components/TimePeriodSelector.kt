package me.avinas.tempo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.theme.*

/**
 * Editorial segmented pill selector for time periods (Week / Month / Year / All-Time).
 *
 * Upgraded with:
 * - Active pill with specular gradient and subtle accent glow
 * - High-contrast [TextOnAccent] for active selection
 * - Tactile haptic feedback on tab changes
 * - Sleek sunken rail background with hairline glass border
 */
@Composable
fun TimePeriodSelector(
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
    availableRanges: List<TimeRange> = listOf(TimeRange.THIS_MONTH, TimeRange.THIS_YEAR, TimeRange.ALL_TIME),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val railShape = RoundedCornerShape(22.dp)
    val pillShape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(railShape)
            .background(TempoDarkSurfaceSunken)
            .border(1.dp, GlassBorderSoft, railShape)
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            availableRanges.forEach { range ->
                val isSelected = range == selectedRange
                val contentColor by animateColorAsState(
                    if (isSelected) TextOnAccent else TextSecondary,
                    label = "textColor"
                )

                val displayText = when (range) {
                    TimeRange.THIS_WEEK -> stringResource(R.string.stats_range_week)
                    TimeRange.THIS_MONTH -> stringResource(R.string.stats_range_month)
                    TimeRange.THIS_YEAR -> stringResource(R.string.stats_range_year)
                    TimeRange.ALL_TIME -> stringResource(R.string.stats_range_all_time)
                    else -> range.name
                }

                val itemWeight = 2f + displayText.length.toFloat()

                val activeModifier = if (isSelected) {
                    Modifier
                        .shadow(
                            elevation = 4.dp,
                            shape = pillShape,
                            ambientColor = Color.Black.copy(alpha = 0.25f),
                            spotColor = Color.Black.copy(alpha = 0.35f)
                        )
                        .clip(pillShape)
                        .background(TempoPrimary)
                        .border(0.5.dp, Color.White.copy(alpha = 0.18f), pillShape)
                } else {
                    Modifier
                        .clip(pillShape)
                        .background(Color.Transparent)
                }

                Box(
                    modifier = Modifier
                        .weight(itemWeight)
                        .height(36.dp)
                        .then(activeModifier)
                        .premiumClickable(
                            onClick = {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRangeSelected(range)
                                }
                            },
                            pressedScale = 0.97f
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayText,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
