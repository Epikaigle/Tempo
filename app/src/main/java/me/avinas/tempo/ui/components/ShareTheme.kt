package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Backdrop construction. This is what makes themes read as different places,
 * not hue swaps of one backdrop: each style builds its own scene from
 * different geometry — scattered bokeh lights, aurora curtains, editorial
 * rings, a sun disc, underwater rays. Only [PHOTO_GLOW] uses the blurred
 * artwork as its backdrop source.
 *
 * Everything is drawn with plain brushes/paths on Canvas so it renders
 * identically on-screen and in the software-canvas share capture.
 */
enum class ShareBackdropStyle {
    /** Blurred artwork backdrop with ambient orbs bleeding from two corners. */
    PHOTO_GLOW,
    /** Out-of-focus stage lights: scattered glowing discs of varying size. */
    BOKEH,
    /** Northern lights: tilted vertical curtains plus a faint star field. */
    AURORA_CURTAINS,
    /** Editorial: flat field, hard vignette, two thin offset ring outlines. */
    RINGS_VIGNETTE,
    /** Morning paper: a visible sun disc with halo sinking in from above. */
    SUN_WASH,
    /** Underwater: light shafts falling from the surface plus rising bubbles. */
    RAYS_BUBBLES
}

/**
 * Shared theme system for all share cards (stats, song details, artist
 * details). A theme declares color tone, backdrop construction, shape
 * language, and contrast rules; every card derives text, surfaces, badges,
 * and decorations from it so stats stay readable on dark and light backdrops.
 */
data class ShareThemePalette(
    val gradient: List<Color>,
    val overlay: List<Color>,
    val accent: Color,
    val glowTop: Color,
    val glowBottom: Color,
    val rank1Tint: Color,
    val isDark: Boolean = true,
    // Backdrop identity — which scene the theme builds, how strongly, and
    // whether the blurred artwork is the backdrop source at all. Themes with
    // usesArtwork = false ignore the item art and draw their own background.
    val backdrop: ShareBackdropStyle = ShareBackdropStyle.PHOTO_GLOW,
    val decorationAlpha: Float = 0.15f,
    val usesArtwork: Boolean = false,
    // Shape language — surfaces, thumbnails, and rank badges follow the theme;
    // winner rings (hero/podium avatars) stay circular by design.
    val cardShape: RoundedCornerShape = RoundedCornerShape(20.dp),
    val thumbShape: RoundedCornerShape = RoundedCornerShape(8.dp),
    val badgeShape: Shape = CircleShape
) {
    // Tone-derived slots so every layout stays readable on light and dark themes.
    val textPrimary: Color get() = if (isDark) Color.White else Color(0xFF241C10)
    val textSecondary: Color get() = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF241C10).copy(alpha = 0.62f)
    // High-emphasis text; also used where semantic colors (fan badges, stat
    // icons) would lack contrast on a light backdrop.
    val textStrong: Color get() = if (isDark) Color.White else Color(0xFF241C10)
    val surface: Color get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val surfaceStrong: Color get() = if (isDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.5f)
    val divider: Color get() = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f)
    val cellBackground: Color get() = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF0E6D2)
    val cellPlaceholder: Color get() = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE6D7BC)
    val branding: Color get() = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF241C10).copy(alpha = 0.55f)
    val heroGlow: Color get() = if (isDark) Color.White.copy(alpha = 0.25f) else Color(0xFFB45309).copy(alpha = 0.2f)
}

enum class ShareTheme(val palette: ShareThemePalette) {
    // The photo theme: blurred artwork under corner glow orbs. The only theme
    // whose backdrop is the listener's own art.
    MIDNIGHT(
        ShareThemePalette(
            gradient = listOf(TempoDarkBackground, Color(0xFF1E1B4B), Color(0xFF312E81)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.55f),
                Color(0xFF0F0F12).copy(alpha = 0.8f),
                Color(0xFF0D0D10).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFFBBF24),
            glowTop = Color(0xFFA855F7),
            glowBottom = Color(0xFFEC4899),
            rank1Tint = Color(0xFFF59E0B),
            backdrop = ShareBackdropStyle.PHOTO_GLOW,
            decorationAlpha = 0.15f,
            usesArtwork = true
        )
    ),
    // Velvet stage: plum dark scattered with out-of-focus stage lights.
    ROSE(
        ShareThemePalette(
            gradient = listOf(Color(0xFF140A12), Color(0xFF3B1533), Color(0xFF1C0A18)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.55f),
                Color(0xFF12080F).copy(alpha = 0.8f),
                Color(0xFF0B0509).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFF9A8D4),
            glowTop = Color(0xFFEC4899),
            glowBottom = Color(0xFFA855F7),
            rank1Tint = Color(0xFFF472B6),
            backdrop = ShareBackdropStyle.BOKEH,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(24.dp),
            thumbShape = RoundedCornerShape(12.dp)
        )
    ),
    // Northern lights: indigo night sky with tilted emerald/blue curtains.
    AURORA(
        ShareThemePalette(
            gradient = listOf(Color(0xFF070714), Color(0xFF161638), Color(0xFF0A0A20)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF0A0F12).copy(alpha = 0.8f),
                Color(0xFF050D10).copy(alpha = 0.95f)
            ),
            accent = Color(0xFF34D399),
            glowTop = Color(0xFF10B981),
            glowBottom = Color(0xFF3B82F6),
            rank1Tint = Color(0xFF34D399),
            backdrop = ShareBackdropStyle.AURORA_CURTAINS,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(16.dp),
            thumbShape = RoundedCornerShape(10.dp)
        )
    ),
    // Editorial monochrome: flat charcoal, sharp corners, rings and vignette.
    MONO(
        ShareThemePalette(
            gradient = listOf(Color(0xFF161616), Color(0xFF0E0E0E), Color(0xFF1A1A1A)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF111111).copy(alpha = 0.8f),
                Color(0xFF0A0A0A).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFE5E7EB),
            glowTop = Color(0xFF9CA3AF),
            glowBottom = Color(0xFFD1D5DB),
            rank1Tint = Color(0xFFE5E7EB),
            backdrop = ShareBackdropStyle.RINGS_VIGNETTE,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(2.dp),
            thumbShape = RoundedCornerShape(2.dp),
            badgeShape = RoundedCornerShape(3.dp)
        )
    ),
    // Morning paper: warm cream under a visible sun disc.
    DAYLIGHT(
        ShareThemePalette(
            gradient = listOf(Color(0xFFFDF6EC), Color(0xFFFDE68A), Color(0xFFFDBA74)),
            overlay = listOf(
                Color.White.copy(alpha = 0.62f),
                Color(0xFFFFFBEB).copy(alpha = 0.85f),
                Color(0xFFFFF7ED).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFB45309),
            glowTop = Color(0xFFF59E0B),
            glowBottom = Color(0xFFFB923C),
            rank1Tint = Color(0xFFC2410C),
            isDark = false,
            backdrop = ShareBackdropStyle.SUN_WASH,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(12.dp),
            thumbShape = RoundedCornerShape(6.dp)
        )
    ),
    // Deep water: teal depths with light shafts and rising bubbles.
    OCEAN(
        ShareThemePalette(
            gradient = listOf(Color(0xFF02131A), Color(0xFF0A4A5C), Color(0xFF032530)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF06131C).copy(alpha = 0.8f),
                Color(0xFF040D13).copy(alpha = 0.95f)
            ),
            accent = Color(0xFF22D3EE),
            glowTop = Color(0xFF22D3EE),
            glowBottom = Color(0xFF2DD4BF),
            rank1Tint = Color(0xFF22D3EE),
            backdrop = ShareBackdropStyle.RAYS_BUBBLES,
            decorationAlpha = 1f,
            cardShape = RoundedCornerShape(22.dp),
            thumbShape = RoundedCornerShape(10.dp)
        )
    )
}

/**
 * Builds the theme's backdrop scene. Placed above the base gradient (and the
 * blurred artwork overlay when the theme uses artwork) and below the card
 * content. Clipped to its own bounds so light never leaks past the card edge
 * in preview dialogs.
 */
@Composable
fun ShareThemeDecorations(palette: ShareThemePalette, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        when (palette.backdrop) {
            ShareBackdropStyle.PHOTO_GLOW -> {
                GlowOrb(this, Alignment.TopEnd, x = 50.dp, y = (-50).dp, size = 300.dp,
                    color = palette.glowTop.copy(alpha = palette.decorationAlpha))
                GlowOrb(this, Alignment.BottomStart, x = (-50).dp, y = 50.dp, size = 300.dp,
                    color = palette.glowBottom.copy(alpha = palette.decorationAlpha))
            }
            ShareBackdropStyle.BOKEH -> Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val colors = listOf(palette.glowTop, palette.glowBottom, palette.accent)
                RoseBokeh.forEachIndexed { i, dot ->
                    val color = colors[i % colors.size]
                    val r = dot.r * w
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = dot.alpha * palette.decorationAlpha),
                                color.copy(alpha = dot.alpha * palette.decorationAlpha * 0.35f),
                                Color.Transparent
                            ),
                            center = Offset(dot.x * w, dot.y * h),
                            radius = r
                        ),
                        radius = r,
                        center = Offset(dot.x * w, dot.y * h)
                    )
                }
            }
            ShareBackdropStyle.AURORA_CURTAINS -> Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val colors = listOf(palette.glowTop, palette.glowBottom, palette.accent)
                // Sky glow the curtains hang in.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.glowTop.copy(alpha = 0.20f * palette.decorationAlpha),
                            palette.glowBottom.copy(alpha = 0.10f * palette.decorationAlpha),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.5f, h * 0.28f),
                        radius = w * 0.9f
                    ),
                    radius = w * 0.9f,
                    center = Offset(w * 0.5f, h * 0.28f)
                )
                // Curved curtain ribbons: bright lower edge fading upward,
                // like real aurora borealis sheets.
                AuroraRibbons.forEach { ribbon ->
                    val color = colors[ribbon.colorIndex % colors.size]
                    val topY = h * ribbon.baseY
                    val y1 = h * (ribbon.baseY + ribbon.bow1)
                    val y2 = h * (ribbon.baseY + ribbon.bow2)
                    val y3 = h * (ribbon.baseY + ribbon.bow3)
                    val t = h * ribbon.thickness
                    val body = Path().apply {
                        moveTo(-w * 0.05f, topY)
                        cubicTo(w * 0.30f, y1, w * 0.65f, y2, w * 1.05f, y3)
                        lineTo(w * 1.05f, y3 + t)
                        cubicTo(w * 0.65f, y2 + t * 1.25f, w * 0.30f, y1 + t * 1.25f, -w * 0.05f, topY + t)
                        close()
                    }
                    drawPath(
                        path = body,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                color.copy(alpha = ribbon.alpha * 0.45f * palette.decorationAlpha),
                                color.copy(alpha = ribbon.alpha * palette.decorationAlpha)
                            ),
                            startY = topY - h * 0.06f,
                            endY = topY + t * 1.2f
                        )
                    )
                    // Sharp bright edge along the ribbon's lower boundary.
                    val edge = Path().apply {
                        moveTo(-w * 0.05f, topY + t)
                        cubicTo(w * 0.30f, y1 + t * 1.25f, w * 0.65f, y2 + t * 1.25f, w * 1.05f, y3 + t)
                    }
                    drawPath(
                        path = edge,
                        color = color.copy(alpha = (ribbon.alpha * 1.15f).coerceAtMost(0.5f) * palette.decorationAlpha),
                        style = Stroke(width = w * 0.005f)
                    )
                }
                // Star field across the sky.
                AuroraStars.forEach { star ->
                    drawCircle(
                        color = Color.White.copy(alpha = star.alpha * palette.decorationAlpha),
                        radius = star.r * w,
                        center = Offset(star.x * w, star.y * h)
                    )
                }
            }
            ShareBackdropStyle.RINGS_VIGNETTE -> Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Hard vignette pulling the corners to black.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f * palette.decorationAlpha)),
                        center = center,
                        radius = maxOf(w, h) * 0.72f
                    )
                )
                // Two thin offset rings — the editorial mark.
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
                    radius = w * 0.42f,
                    center = Offset(w * 0.84f, h * 0.14f),
                    style = Stroke(width = w * 0.004f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f * palette.decorationAlpha),
                    radius = w * 0.30f,
                    center = Offset(w * 0.08f, h * 0.92f),
                    style = Stroke(width = w * 0.003f)
                )
            }
            ShareBackdropStyle.SUN_WASH -> Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val sunCenter = Offset(w * 0.78f, h * 0.10f)
                // Halo.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFF7E0).copy(alpha = 0.75f * palette.decorationAlpha),
                            palette.glowTop.copy(alpha = 0.30f * palette.decorationAlpha),
                            Color.Transparent
                        ),
                        center = sunCenter,
                        radius = w * 0.62f
                    ),
                    radius = w * 0.62f,
                    center = sunCenter
                )
                // Warmth pooling at the bottom edge.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.glowBottom.copy(alpha = 0.22f * palette.decorationAlpha), Color.Transparent),
                        center = Offset(w * 0.1f, h * 1.02f),
                        radius = w * 0.55f
                    ),
                    radius = w * 0.55f,
                    center = Offset(w * 0.1f, h * 1.02f)
                )
            }
            ShareBackdropStyle.RAYS_BUBBLES -> Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Bright water-surface band the light pours in from.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            palette.glowTop.copy(alpha = 0.30f * palette.decorationAlpha),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.22f
                    )
                )
                // Surface glow the rays fall out of.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.glowTop.copy(alpha = 0.35f * palette.decorationAlpha), Color.Transparent),
                        center = Offset(w * 0.5f, -h * 0.10f),
                        radius = w * 0.85f
                    ),
                    radius = w * 0.85f,
                    center = Offset(w * 0.5f, -h * 0.10f)
                )
                // Light shafts: narrow at the surface, widening and fading as they fall.
                OceanRays.forEach { ray ->
                    val path = Path().apply {
                        moveTo(w * ray.x - w * ray.spread * 0.12f, -h * 0.02f)
                        lineTo(w * ray.x - w * ray.spread, h * 1.02f)
                        lineTo(w * ray.x + w * ray.spread, h * 1.02f)
                        lineTo(w * ray.x + w * ray.spread * 0.12f, -h * 0.02f)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                palette.glowTop.copy(alpha = ray.alpha * palette.decorationAlpha),
                                palette.glowTop.copy(alpha = ray.alpha * 0.5f * palette.decorationAlpha),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = h * 0.95f
                        )
                    )
                }
                // Caustic ripples drifting just under the surface.
                OceanCaustics.forEach { caustic ->
                    val y = h * caustic.y
                    val amp = h * caustic.amp
                    val path = Path().apply {
                        moveTo(w * caustic.x0, y)
                        cubicTo(
                            w * (caustic.x0 + (caustic.x1 - caustic.x0) * 0.33f), y - amp,
                            w * (caustic.x0 + (caustic.x1 - caustic.x0) * 0.66f), y + amp,
                            w * caustic.x1, y
                        )
                    }
                    drawPath(
                        path = path,
                        color = palette.glowTop.copy(alpha = caustic.alpha * palette.decorationAlpha),
                        style = Stroke(width = w * 0.004f)
                    )
                }
                // Rising bubbles: soft rim glow + a highlight glint.
                OceanBubbles.forEach { bubble ->
                    val center = Offset(bubble.x * w, bubble.y * h)
                    val radius = bubble.r * w
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                palette.glowBottom.copy(alpha = bubble.alpha * 0.5f * palette.decorationAlpha),
                                palette.glowBottom.copy(alpha = bubble.alpha * palette.decorationAlpha)
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = bubble.alpha * 0.9f * palette.decorationAlpha),
                        radius = radius * 0.22f,
                        center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f)
                    )
                }
            }
        }
    }
}

/**
 * Radial decoration inside a fixed-size circle: fades from [color] at its
 * centre to transparent (or to [edge] when given).
 */
@Composable
private fun GlowOrb(
    scope: BoxScope,
    alignment: Alignment,
    x: Dp,
    y: Dp,
    size: Dp,
    color: Color,
    edge: Color = Color.Transparent
) {
    with(scope) {
        Box(
            modifier = Modifier
                .align(alignment)
                .offset(x = x, y = y)
                .size(size)
                .background(
                    brush = Brush.radialGradient(colors = listOf(color, edge)),
                    shape = CircleShape
                )
        )
    }
}

// Scene geometry — fixed layouts so the backdrop is identical on every render
// and in the share capture. Coordinates are fractions of card width/height.

private data class BokehDot(val x: Float, val y: Float, val r: Float, val alpha: Float)

private val RoseBokeh = listOf(
    BokehDot(0.14f, 0.10f, 0.11f, 0.50f),
    BokehDot(0.76f, 0.06f, 0.06f, 0.40f),
    BokehDot(0.94f, 0.28f, 0.13f, 0.35f),
    BokehDot(0.32f, 0.30f, 0.05f, 0.45f),
    BokehDot(0.60f, 0.20f, 0.08f, 0.30f),
    BokehDot(0.06f, 0.52f, 0.07f, 0.30f),
    BokehDot(0.90f, 0.60f, 0.09f, 0.40f),
    BokehDot(0.44f, 0.70f, 0.06f, 0.35f),
    BokehDot(0.18f, 0.86f, 0.12f, 0.40f),
    BokehDot(0.72f, 0.92f, 0.07f, 0.45f),
    BokehDot(0.52f, 0.46f, 0.04f, 0.50f),
    BokehDot(0.36f, 0.56f, 0.09f, 0.22f)
)


private data class AuroraRibbon(
    val baseY: Float,
    val bow1: Float,
    val bow2: Float,
    val bow3: Float,
    val thickness: Float,
    val alpha: Float,
    val colorIndex: Int
)

private val AuroraRibbons = listOf(
    AuroraRibbon(baseY = 0.08f, bow1 = 0.10f, bow2 = 0.22f, bow3 = 0.12f, thickness = 0.17f, alpha = 0.36f, colorIndex = 0),
    AuroraRibbon(baseY = 0.24f, bow1 = 0.06f, bow2 = 0.16f, bow3 = 0.26f, thickness = 0.13f, alpha = 0.28f, colorIndex = 1),
    AuroraRibbon(baseY = 0.42f, bow1 = 0.12f, bow2 = 0.02f, bow3 = 0.14f, thickness = 0.10f, alpha = 0.22f, colorIndex = 2)
)

private data class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)

private val AuroraStars = listOf(
    Star(0.08f, 0.05f, 0.004f, 0.75f),
    Star(0.24f, 0.12f, 0.003f, 0.55f),
    Star(0.46f, 0.04f, 0.004f, 0.65f),
    Star(0.58f, 0.15f, 0.002f, 0.45f),
    Star(0.72f, 0.08f, 0.003f, 0.65f),
    Star(0.90f, 0.13f, 0.004f, 0.55f),
    Star(0.34f, 0.20f, 0.002f, 0.45f),
    Star(0.82f, 0.22f, 0.003f, 0.50f),
    Star(0.15f, 0.28f, 0.002f, 0.40f),
    Star(0.52f, 0.26f, 0.003f, 0.45f),
    Star(0.66f, 0.32f, 0.002f, 0.35f),
    Star(0.94f, 0.30f, 0.002f, 0.40f),
    Star(0.05f, 0.18f, 0.002f, 0.50f),
    Star(0.40f, 0.10f, 0.002f, 0.55f)
)

private data class OceanRay(val x: Float, val spread: Float, val alpha: Float)

private val OceanRays = listOf(
    OceanRay(x = 0.20f, spread = 0.09f, alpha = 0.14f),
    OceanRay(x = 0.44f, spread = 0.15f, alpha = 0.18f),
    OceanRay(x = 0.68f, spread = 0.11f, alpha = 0.13f),
    OceanRay(x = 0.88f, spread = 0.07f, alpha = 0.10f)
)

private data class Bubble(val x: Float, val y: Float, val r: Float, val alpha: Float)

private val OceanBubbles = listOf(
    Bubble(0.12f, 0.62f, 0.016f, 0.32f),
    Bubble(0.20f, 0.78f, 0.026f, 0.26f),
    Bubble(0.34f, 0.88f, 0.012f, 0.30f),
    Bubble(0.58f, 0.72f, 0.020f, 0.24f),
    Bubble(0.70f, 0.86f, 0.030f, 0.26f),
    Bubble(0.84f, 0.66f, 0.014f, 0.30f),
    Bubble(0.92f, 0.82f, 0.022f, 0.24f),
    Bubble(0.46f, 0.94f, 0.016f, 0.28f),
    Bubble(0.06f, 0.90f, 0.010f, 0.26f),
    Bubble(0.28f, 0.70f, 0.008f, 0.30f),
    Bubble(0.64f, 0.94f, 0.012f, 0.24f),
    Bubble(0.78f, 0.74f, 0.009f, 0.28f)
)

private data class Caustic(val x0: Float, val x1: Float, val y: Float, val amp: Float, val alpha: Float)

private val OceanCaustics = listOf(
    Caustic(x0 = 0.05f, x1 = 0.42f, y = 0.10f, amp = 0.012f, alpha = 0.22f),
    Caustic(x0 = 0.50f, x1 = 0.95f, y = 0.14f, amp = 0.010f, alpha = 0.18f),
    Caustic(x0 = 0.18f, x1 = 0.70f, y = 0.20f, amp = 0.014f, alpha = 0.14f),
    Caustic(x0 = 0.60f, x1 = 1.00f, y = 0.26f, amp = 0.010f, alpha = 0.10f)
)

/**
 * Text color for rank badges, pedestal numbers, and swatch selection dots.
 * Pure luminance pick: bright chips (gold/silver metals on dark themes) get
 * black text, dark chips (light-theme accents) get white. Threshold 0.5 is
 * the crossover that keeps the original black-on-metal look while making
 * dark chips on the Daylight theme readable.
 */
internal fun ShareThemePalette.contrastingText(chip: Color): Color =
    if (0.299f * chip.red + 0.587f * chip.green + 0.114f * chip.blue > 0.5f) Color.Black else Color.White

/**
 * Circular gradient swatch used by share dialogs to pick a [ShareTheme].
 */
@Composable
fun ThemeSwatch(theme: ShareTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = theme.palette
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(palette.gradient))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.15f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(modifier = Modifier.size(6.dp).background(palette.contrastingText(palette.gradient.first()), CircleShape))
        }
    }
}
