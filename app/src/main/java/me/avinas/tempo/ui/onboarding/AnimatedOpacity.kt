package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun AnimatedOpacity(
    delay: Int,
    visible: Boolean,
    content: @Composable () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500, delayMillis = delay),
        label = "alpha"
    )

    // ponytail: rise + fade reads as arrival, alpha-only reads as ghost.
    // One shared language: 24px drift, same as transitions + Welcome.
    Box(
        modifier = Modifier
            .alpha(alpha)
            .graphicsLayer { translationY = (1f - alpha) * 24f }
    ) {
        content()
    }
}
