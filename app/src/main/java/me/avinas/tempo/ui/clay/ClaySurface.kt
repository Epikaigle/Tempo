package me.avinas.tempo.ui.clay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * clay.css as a modifier. Faithful to the source
 * (codeAdrian/clay.css `dist/clay.css`):
 *
 *   box-shadow: 8px 8px 16px rgba(0,0,0,.25),                 // outset, down-right
 *     inset -8px -8px 16px rgba(0,0,0,.25),                   // inset dark, top-left
 *     inset 8px 8px 16px hsla(0,0%,100%,.2);                  // inset light, bottom-right
 *
 * Two things the naive port gets wrong and this one doesn't:
 *  1. Insets OVERLAY the face in CSS — they never shrink it. Drawing the
 *     face back smaller leaves a border ring (recessed, cheap); here the
 *     face is full-bleed and the insets wash over its edges.
 *  2. Insets hug the edges they fall from (dark top + left, light bottom
 *     + right), they are not a diagonal wash across the whole face. Compose
 *     has no blurred inset shadow, so each side gets a short gradient strip
 *     clipped to the silhouette — same optics at a glance.
 *
 * [rimFraction] is the inset depth as a fraction of the smallest side,
 * clamped to 3..16dp so it reads as an edge blur, never a band.
 *
 * Bring this out anywhere a card, button, chip or tile should read as one
 * lump of clay. For hand-built things (bells, shields, …) use [ClayDraw]
 * primitives + [ClayTokens] instead.
 */
fun Modifier.clay(
    background: Color,
    cornerRadius: Dp = 28.dp,
    outset: Color = Color.Black.copy(alpha = 0.25f),
    outsetElevation: Dp = 8.dp,
    insetDark: Color = Color.Black.copy(alpha = 0.25f),
    insetLight: Color = Color.White.copy(alpha = 0.2f),
    rimFraction: Float = 0.13f,
): Modifier = this
    .shadow(outsetElevation, RoundedCornerShape(cornerRadius), ambientColor = outset, spotColor = outset)
    .drawBehind {
        val cr = cornerRadius.toPx()
        // Face first, full-bleed — insets overlay it, never shrink it.
        drawRoundRect(color = background, cornerRadius = CornerRadius(cr, cr))
        // Inset depth: a blur-like edge, not a band.
        val edge = (size.minDimension * rimFraction).coerceIn(3.dp.toPx(), 16.dp.toPx())
        val silhouette = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), cr, cr)) }
        val clear = Color.Transparent
        clipPath(silhouette) {
            // Inset primary (dark): top + left, fading inward.
            drawRect(
                brush = Brush.verticalGradient(listOf(insetDark, clear), endY = edge),
                size = Size(size.width, edge),
            )
            drawRect(
                brush = Brush.horizontalGradient(listOf(insetDark, clear), endX = edge),
                size = Size(edge, size.height),
            )
            // Inset secondary (light): bottom + right, fading inward.
            drawRect(
                brush = Brush.verticalGradient(listOf(clear, insetLight), startY = size.height - edge),
                topLeft = Offset(0f, size.height - edge),
                size = Size(size.width, edge),
            )
            drawRect(
                brush = Brush.horizontalGradient(listOf(clear, insetLight), startX = size.width - edge),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
            )
        }
    }

/**
 * Anything that should feel like one molded piece: cards, buttons, chips.
 * Content sits in clay.css light-content tone by default.
 */
@Composable
fun ClayCard(
    background: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.clay(background, cornerRadius),
        contentAlignment = contentAlignment,
        content = content
    )
}
