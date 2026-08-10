package me.avinas.tempo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.ui.tooling.preview.Preview
import me.avinas.tempo.ui.theme.TempoTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R




@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    timeRange: TimeRange? = null,
    onCheckSupportedApps: () -> Unit
) {
    // Container that fills the available space
    Box(
         modifier = modifier.fillMaxSize(),
         contentAlignment = Alignment.Center
    ) {
        if (timeRange != null && timeRange != TimeRange.ALL_TIME && timeRange != TimeRange.TODAY) {
            TimeRangeEmptyState(
                timeRange = timeRange,
                onCheckSupportedApps = onCheckSupportedApps
            )
        } else {
            SetupGuideEmptyState(onCheckSupportedApps = onCheckSupportedApps)
        }
    }
}

@Composable
private fun TimeRangeEmptyState(
    timeRange: TimeRange,
    onCheckSupportedApps: () -> Unit
) {
    // Calculate precise date label for the "Context"
    val contextLabel = remember(timeRange) {
        val now = java.time.LocalDate.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d")
        
        when (timeRange) {
            TimeRange.THIS_WEEK -> {
                // Assuming Monday start for simplicity, or localized
                val startOfWeek = now.minusDays(now.dayOfWeek.value.toLong() - 1)
                val endOfWeek = startOfWeek.plusDays(6)
                "${startOfWeek.format(formatter)} - ${endOfWeek.format(formatter)}"
            }
            TimeRange.THIS_MONTH -> java.time.format.DateTimeFormatter.ofPattern("MMMM").format(now)
            TimeRange.THIS_YEAR -> java.time.format.DateTimeFormatter.ofPattern("yyyy").format(now)
            else -> ""
        }
    }

    // Distinct copy for "Reassurance" vs just "Empty"
    // The goal is to tell the user: "We are looking, just nothing found YET."
    val (icon, title, message) = when (timeRange) {
        TimeRange.THIS_WEEK -> Triple(
            Icons.Rounded.DateRange, 
            "Quiet week so far", 
            "We're listening, but haven't seen any plays yet. Start streaming to see your weekly pulse."
        )
        TimeRange.THIS_MONTH -> Triple(
            Icons.Rounded.DateRange, 
            "Fresh month, fresh start", 
            "Your history is safe. Play music to spark this month's stats."
        )
        TimeRange.THIS_YEAR -> Triple(
            Icons.Rounded.AutoAwesome, 
            "2026 has begun", 
            "Your year in review starts now. Keep listening to build your story."
        )
        else -> Triple(
            Icons.Rounded.DateRange, 
            "No Data Yet", 
            "Start listening to see your stats here."
        )
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp), // Standard outer margins
        backgroundColor = TempoPrimary.copy(alpha = 0.1f),
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // "System Status" Badge - Reassures the user the app is working
            Surface(
                color = TempoSuccess.copy(alpha = 0.15f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(100),
                border = androidx.compose.foundation.BorderStroke(1.dp, TempoSuccess.copy(alpha = 0.3f)),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape).background(TempoSuccess),
                        contentAlignment = Alignment.Center
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.empty_tracking_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = TempoSuccess,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Title & Context
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            // Explicit Date Context
            // Helps user understand "Oh, this is why it's empty, it's a new week"
            if (contextLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = contextLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = TextTertiary, 
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Reassuring Body
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Secondary Action - Just in case they suspect a connection issue
            Button(
                onClick = onCheckSupportedApps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary, 
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
               Text(stringResource(R.string.empty_check_apps), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) 
            }
        }
    }
}

@Composable
private fun SetupGuideEmptyState(
    onCheckSupportedApps: () -> Unit
) {

    
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        backgroundColor = TempoPrimary.copy(alpha = 0.12f),
        variant = GlassCardVariant.HighProminence, // Stand out more
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header
            Text(
                text = stringResource(R.string.empty_getting_started),
                style = MaterialTheme.typography.headlineSmall, // Bigger than titleLarge
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.empty_stats_flowing),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Trust Signal
            Surface(
                color = TempoSuccess.copy(alpha = 0.1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TempoSuccess.copy(alpha = 0.2f))
            ) {
                 Text(
                    text = stringResource(R.string.empty_no_account),
                    style = MaterialTheme.typography.labelMedium,
                    color = TempoSuccess,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Visual Steps - Compact & Clean
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StepItem(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic, 
                    text = stringResource(R.string.empty_open_apps)
                )
                StepItem(
                    icon = Icons.Rounded.Headphones, 
                    text = stringResource(R.string.empty_play_songs)
                )
                StepItem(
                    icon = Icons.Rounded.GraphicEq, 
                    text = stringResource(R.string.empty_return_insights)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Primary Action
            Button(
                onClick = onCheckSupportedApps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.empty_check_apps),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StepItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Divider, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = TempoPrimary, 
                modifier = Modifier.size(18.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = text, 
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp
        )
    }
}


@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EmptyStatePreview() {
    TempoTheme {
        EmptyState(
            modifier = Modifier.fillMaxSize(),
            onCheckSupportedApps = {}
        )
    }
}
