package me.avinas.tempo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* Hallmark · DeepOcean — neutral depth.
 * Near-black matte with a single soft value vignette + top sheen.
 * No teal/mint blob. Teal lives in cards as accent, not in the sky.
 *
 * All four gradients paint in ONE drawBehind pass — one node, no stacked
 * full-screen children (it sits under every screen, including the art
 * atmosphere, so overdraw here is paid app-wide).
 */
@Composable
fun DeepOceanBackground(
    modifier: Modifier = Modifier,
    enableAnimations: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val height = size.height
                drawRect(Color(0xFF0A0E0E))
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF111616),
                            Color(0xFF0A0E0E),
                            Color(0xFF080A0A)
                        ),
                        endY = height,
                    )
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.035f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 520f,
                    )
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF0F1414).copy(alpha = 0.55f)
                        ),
                        startY = 900f,
                        endY = height,
                    )
                )
            }
    ) {
        content()
    }
}
