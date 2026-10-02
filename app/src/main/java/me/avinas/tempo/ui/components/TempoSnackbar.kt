package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.theme.*

/**
 * Severity of an in-app notification. Drives the leading icon badge and
 * action accent. [fromMessage] is a best-effort classifier for the app's
 * English feedback strings; pass an explicit variant when semantics are known.
 */
enum class TempoSnackbarVariant(
    val tint: Color
) {
    Success(TempoSuccess),
    Error(TempoError),
    Warning(TempoWarning),
    Neutral(TempoPrimary);

    companion object {
        fun fromMessage(message: String): TempoSnackbarVariant {
            val m = message.lowercase()
            return when {
                m.startsWith("success") || "successfully" in m -> Success
                "fail" in m || "error" in m || "unable" in m || "cannot" in m || "not available" in m -> Error
                else -> Neutral
            }
        }
    }
}

private val TempoSnackbarShape = RoundedCornerShape(16.dp)

/**
 * Unified in-app notification for Tempo. Same token family as
 * [TempoDialogSurface]: solid TempoSurfaceDialog, hairline border, soft
 * shadow. Severity-tinted icon badge, TextPrimary message, accent action.
 * Floats with 16dp side / 12dp bottom margins.
 */
@Composable
fun TempoSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
    variant: TempoSnackbarVariant = TempoSnackbarVariant.fromMessage(data.visuals.message)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .shadow(
                elevation = 16.dp,
                shape = TempoSnackbarShape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.6f)
            )
            .clip(TempoSnackbarShape)
            .background(
                Brush.verticalGradient(
                    listOf(TempoSurfaceRaised, TempoSurfaceDialog)
                )
            )
            .border(
                width = 1.dp,
                color = GlassBorderSoft,
                shape = TempoSnackbarShape
            )
    ) {
        // Specular top hairline edge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clean single-tile icon badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(variant.tint.copy(alpha = 0.10f))
                    .border(0.5.dp, variant.tint.copy(alpha = 0.20f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (variant) {
                        TempoSnackbarVariant.Success -> TempoIcons.CheckCircle
                        TempoSnackbarVariant.Error -> TempoIcons.ErrorCircle
                        TempoSnackbarVariant.Warning -> TempoIcons.AlertCircle
                        TempoSnackbarVariant.Neutral -> TempoIcons.InfoCircle
                    },
                    contentDescription = null,
                    tint = variant.tint,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f, fill = false)
            )

            data.visuals.actionLabel?.let { actionLabel ->
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(variant.tint.copy(alpha = 0.08f))
                        .border(0.5.dp, variant.tint.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) {
                            data.performAction()
                            data.dismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = variant.tint
                    )
                }
            }
        }
    }
}
