package me.avinas.tempo.ui.clay

import androidx.compose.ui.graphics.Color

/**
 * Candy palette sampled from welcome.png (marigold / hibiscus / leaf).
 * One token per meaning; add here instead of a raw hex at call sites.
 */
object ClayTokens {
    val Marigold = Color(0xFFFBBF24) // music / joy
    val Sky = Color(0xFF60A5FA) // info / calm
    val Mint = Color(0xFF34D399) // safe / local
    val Lavender = Color(0xFFB8A6FF) // playful
    val Lemon = Color(0xFFF5D77A) // energy
    val Peach = Color(0xFFFFB59E) // warm muted
    val Pink = Color(0xFFF5B8D0) // builder

    /** Detail tone for insets on clay, per clay.css light content. */
    val Cream = Color(0xFFFFF7ED)
}

/** Linear blend between two colors. The workhorse behind rims and edges. */
fun mix(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )

/** Attached slab-side tone: the base pulled toward black, hue kept. */
fun clayEdge(base: Color): Color = mix(base, Color.Black, 0.45f)
