package me.avinas.tempo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

@Composable
fun FitToHeight(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(
            Constraints(
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity
            )
        )
        if (placeable == null) {
            layout(0, 0) {}
        } else {
            val naturalHeight = placeable.height
            val maxH = constraints.maxHeight
            val scale = if (maxH != Constraints.Infinity && naturalHeight > maxH && naturalHeight > 0) {
                maxH.toFloat() / naturalHeight.toFloat()
            } else 1f
            val width = if (constraints.maxWidth != Constraints.Infinity) constraints.maxWidth else placeable.width
            val height = (naturalHeight * scale).toInt().let {
                if (maxH != Constraints.Infinity) it.coerceAtMost(maxH) else it
            }
            layout(width, height) {
                placeable.placeWithLayer(x = 0, y = 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
            }
        }
    }
}
