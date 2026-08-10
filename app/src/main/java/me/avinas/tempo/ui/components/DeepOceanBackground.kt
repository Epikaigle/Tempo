package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DeepOceanBackground(
    modifier: Modifier = Modifier,
    enableAnimations: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TempoBackground)
    ) {
        content()
    }
}
