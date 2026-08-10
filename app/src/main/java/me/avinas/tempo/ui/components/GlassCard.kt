package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GlassCardVariant {
    HighProminence,
    LowProminence
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    backgroundColor: Color = SurfaceCardDefault,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    variant: GlassCardVariant = GlassCardVariant.HighProminence,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.CenterStart,
    borderColor: Color? = null,
    borderWidth: Dp? = null,
    shadowElevation: Dp = 0.dp,
    shadowSpotColor: Color = Color.Transparent,
    fillMaxWidth: Boolean = true,
    content: @Composable () -> Unit
) {
    val surface = remember(backgroundColor) {
        backgroundColor
    }
    val border = remember(variant, borderColor, borderWidth) {
        val width = borderWidth ?: (if (variant == GlassCardVariant.HighProminence) 1.dp else 0.5.dp)
        val color = borderColor ?: if (variant == GlassCardVariant.HighProminence) BorderHairline else BorderSubtle
        BorderStroke(width, color)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(surface, shape)
            .border(border, shape = shape),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

private val SurfaceCardDefault = Color(0x0DFFFFFF)
private val BorderHairline = Color(0x1AFFFFFF)
private val BorderSubtle = Color(0x0DFFFFFF)
