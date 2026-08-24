package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .shadow(
                elevation = 12.dp,
                shape = TempoSnackbarShape,
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.5f)
            )
            .clip(TempoSnackbarShape)
            .background(TempoSurfaceDialog)
            .border(
                width = 1.dp,
                color = GlassBorderSoft,
                shape = TempoSnackbarShape
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (variant != TempoSnackbarVariant.Neutral) {
            androidx.compose.material3.Icon(
                imageVector = when (variant) {
                    TempoSnackbarVariant.Success -> Icons.Rounded.CheckCircle
                    TempoSnackbarVariant.Error -> Icons.Rounded.ErrorOutline
                    TempoSnackbarVariant.Warning -> Icons.Rounded.WarningAmber
                    TempoSnackbarVariant.Neutral -> Icons.Rounded.CheckCircle
                },
                contentDescription = null,
                tint = variant.tint,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(variant.tint.copy(alpha = 0.14f))
                    .padding(6.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Text(
            text = data.visuals.message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f, fill = false)
        )

        data.visuals.actionLabel?.let { actionLabel ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = variant.tint,
                modifier = Modifier
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) {
                        data.performAction()
                        data.dismiss()
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            )
        }
    }
}
