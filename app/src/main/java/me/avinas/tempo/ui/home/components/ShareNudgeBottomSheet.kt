package me.avinas.tempo.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceSunken
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * Which share surface the nudge promotes. Decided dynamically by
 * [me.avinas.tempo.ui.home.HomeViewModel.shouldShowShareNudge] from the date:
 * freshest recap period wins, otherwise the always-available stats card.
 */
enum class ShareNudgeType {
    WEEKLY,
    MONTHLY,
    STATS
}

/**
 * Bottom sheet promoting the share feature to users who haven't discovered it.
 * Shown on the home screen by the gated algorithm in HomeViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareNudgeBottomSheet(
    type: ShareNudgeType,
    onDismiss: () -> Unit,
    onAction: () -> Unit
) {
    val title = stringResource(
        when (type) {
            ShareNudgeType.WEEKLY -> R.string.share_nudge_weekly_title
            ShareNudgeType.MONTHLY -> R.string.share_nudge_monthly_title
            ShareNudgeType.STATS -> R.string.share_nudge_stats_title
        }
    )
    val message = stringResource(
        when (type) {
            ShareNudgeType.STATS -> R.string.share_nudge_stats_message
            else -> R.string.share_nudge_story_message
        }
    )
    val cta = stringResource(
        when (type) {
            ShareNudgeType.STATS -> R.string.share_nudge_cta_stats
            else -> R.string.share_nudge_cta_story
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TempoSurfaceSunken,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(TempoPrimary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(TempoPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.IosShare,
                        contentDescription = null,
                        tint = TempoPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = TextSecondary,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = cta,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.rate_not_now),
                    color = TextTertiary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
