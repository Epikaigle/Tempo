package me.avinas.tempo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoErrorAlt
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoSurface
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.TextSecondary

/** Structural style of a share card backdrop. CLASSIC = the original radial-glow look.
 *  Each non-classic style draws a premium design-aesthetic pattern (mesh gradient,
 *  topographic contours, bokeh, light prism, color sweep, halftone) that complements
 *  the data layered on top — not music-literal, just beautiful design. */
enum class ShareBgStyle { CLASSIC, MESH, CONTOUR, BOKEH, PRISM, SWEEP, HALFTONE }

/** Color + structure spec for a share card background. */
data class ShareThemePalette(
    val gradient: List<Color>,
    val overlay: List<Color>,
    val accent: Color,
    val glowTop: Color,
    val glowBottom: Color,
    val rank1Tint: Color,
    val bgStyle: ShareBgStyle = ShareBgStyle.CLASSIC
)

/** A selectable theme for any share card. The palette drives both color and backdrop structure.
 *  Classic themes use a refined radial-glow; structural themes draw a music-themed canvas
 *  pattern (vinyl grooves, spectrum bars, EQ, stage light, waveform, neon glow) that
 *  complements the album art and listening data layered on top. */
enum class ShareTheme(val palette: ShareThemePalette) {
    // --- Classic color themes (refined radial-glow backdrop) ---
    MIDNIGHT(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0A0A14), Color(0xFF15132E), TempoSurfaceDialog),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF0A0A14).copy(alpha = 0.75f), Color(0xFF050508).copy(alpha = 0.92f)),
            accent = TempoWarningBright,
            glowTop = TempoAccent,
            glowBottom = TempoPrimary,
            rank1Tint = TempoWarning
        )
    ),
    SUNSET(
        ShareThemePalette(
            gradient = listOf(Color(0xFF150806), Color(0xFF7C2D12), Color(0xFF9F1239)),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF1A0A08).copy(alpha = 0.75f), Color(0xFF0D0404).copy(alpha = 0.92f)),
            accent = TempoWarningBright,
            glowTop = TempoWarning,
            glowBottom = TempoError,
            rank1Tint = TempoWarning
        )
    ),
    AURORA(
        ShareThemePalette(
            gradient = listOf(Color(0xFF06120E), Color(0xFF064E3B), Color(0xFF1E3A8A)),
            overlay = listOf(Color.Black.copy(alpha = 0.45f), Color(0xFF06121A).copy(alpha = 0.75f), Color(0xFF030608).copy(alpha = 0.92f)),
            accent = TempoSuccessDeep,
            glowTop = Color(0xFF2DD4BF),
            glowBottom = TempoInfo,
            rank1Tint = TempoSuccessDeep
        )
    ),
    MONO(
        ShareThemePalette(
            gradient = listOf(Color(0xFF080808), Color(0xFF161616), Color(0xFF222222)),
            overlay = listOf(Color.Black.copy(alpha = 0.45f), Color(0xFF0C0C0C).copy(alpha = 0.75f), Color(0xFF060606).copy(alpha = 0.92f)),
            accent = TextSecondary,
            glowTop = Color(0xFF9CA3AF),
            glowBottom = Color(0xFFD1D5DB),
            rank1Tint = Color(0xFFF3F4F6)
        )
    ),
    // --- Structural backdrop themes (premium design aesthetics, not music-literal) ---
    MESH(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0C0A10), Color(0xFF14101A), Color(0xFF08060C)),
            overlay = listOf(Color.Black.copy(alpha = 0.55f), Color(0xFF08060C).copy(alpha = 0.78f), Color(0xFF040308).copy(alpha = 0.92f)),
            accent = Color(0xFF8B5CF6),
            glowTop = Color(0xFF22D3EE),
            glowBottom = TempoErrorAlt,
            rank1Tint = Color(0xFFFACC15),
            bgStyle = ShareBgStyle.MESH
        )
    ),
    CONTOUR(
        ShareThemePalette(
            gradient = listOf(Color(0xFF060608), Color(0xFF0E0E14), Color(0xFF040406)),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF060608).copy(alpha = 0.78f), Color(0xFF030303).copy(alpha = 0.92f)),
            accent = TempoWarning,
            glowTop = Color(0xFF2DD4BF),
            glowBottom = TempoInfo,
            rank1Tint = TempoWarning,
            bgStyle = ShareBgStyle.CONTOUR
        )
    ),
    BOKEH(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0A0808), Color(0xFF12100E), Color(0xFF060404)),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF0A0806).copy(alpha = 0.75f), Color(0xFF050302).copy(alpha = 0.9f)),
            accent = TempoWarningBright,
            glowTop = TempoPrimary,
            glowBottom = Color(0xFFA78BFA),
            rank1Tint = TempoWarningBright,
            bgStyle = ShareBgStyle.BOKEH
        )
    ),
    PRISM(
        ShareThemePalette(
            gradient = listOf(Color(0xFF08060C), Color(0xFF0E0A16), Color(0xFF040308)),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF06040A).copy(alpha = 0.78f), Color(0xFF030206).copy(alpha = 0.92f)),
            accent = Color(0xFF22D3EE),
            glowTop = Color(0xFF8B5CF6),
            glowBottom = TempoWarningBright,
            rank1Tint = Color(0xFF22D3EE),
            bgStyle = ShareBgStyle.PRISM
        )
    ),
    SWEEP(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0A0810), Color(0xFF121018), Color(0xFF060408)),
            overlay = listOf(Color.Black.copy(alpha = 0.5f), Color(0xFF080610).copy(alpha = 0.78f), Color(0xFF040306).copy(alpha = 0.92f)),
            accent = TempoErrorAlt,
            glowTop = Color(0xFF22D3EE),
            glowBottom = Color(0xFF8B5CF6),
            rank1Tint = TempoWarningBright,
            bgStyle = ShareBgStyle.SWEEP
        )
    ),
    HALFTONE(
        ShareThemePalette(
            gradient = listOf(TempoSurface, Color(0xFF0C0C0C), Color(0xFF020202)),
            overlay = listOf(Color.Black.copy(alpha = 0.55f), Color(0xFF080808).copy(alpha = 0.8f), Color(0xFF040404).copy(alpha = 0.92f)),
            accent = Color(0xFFF3F4F6),
            glowTop = Color(0xFF9CA3AF),
            glowBottom = Color(0xFFD1D5DB),
            rank1Tint = Color(0xFFF3F4F6),
            bgStyle = ShareBgStyle.HALFTONE
        )
    )
}

/**
 * Single shared backdrop renderer used by every share card. Switches on the theme's bgStyle
 * so each structural theme draws a genuinely different background, not just a recolor.
 * [content] is rendered on top, untouched — only the backdrop changes.
 */
@Composable
fun ShareBackdrop(
    theme: ShareTheme,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val p = theme.palette
    Box(modifier = modifier.background(Brush.verticalGradient(p.gradient))) {
        when (p.bgStyle) {
            ShareBgStyle.CLASSIC -> ClassicBackdrop(p, imageUrl)
            ShareBgStyle.MESH -> MeshBackdrop(p, imageUrl)
            ShareBgStyle.CONTOUR -> ContourBackdrop(p, imageUrl)
            ShareBgStyle.BOKEH -> BokehBackdrop(p, imageUrl)
            ShareBgStyle.PRISM -> PrismBackdrop(p, imageUrl)
            ShareBgStyle.SWEEP -> SweepBackdrop(p, imageUrl)
            ShareBgStyle.HALFTONE -> HalftoneBackdrop(p, imageUrl)
        }
        content()
    }
}

// ----------------------------------------------------------------------------------
// Common helpers
// ----------------------------------------------------------------------------------

/** Blurred album art behind the structure, dimmed by [overlay] so the backdrop reads. */
@Composable
private fun BlurredArt(imageUrl: String?, overlay: List<Color>, alpha: Float = 1f) {
    if (imageUrl.isNullOrBlank()) return
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        CachedAsyncImage(
            imageUrl = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(alpha),
            allowHardware = false,
            blurRadius = 48.dp
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(overlay))
        )
    }
}

// ----------------------------------------------------------------------------------
// CLASSIC — refined radial-glow over blurred album art + soft vignette
// ----------------------------------------------------------------------------------

@Composable
private fun ClassicBackdrop(p: ShareThemePalette, imageUrl: String?) {
    Box(Modifier.fillMaxSize()) {
        BlurredArt(imageUrl, p.overlay)
        // Top-right glow — soft, large, premium
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-60).dp)
                .size(340.dp)
                .background(
                    Brush.radialGradient(listOf(p.glowTop.copy(alpha = 0.13f), Color.Transparent)),
                    CircleShape
                )
        )
        // Bottom-left glow — soft, large
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-40).dp, y = 60.dp)
                .size(340.dp)
                .background(
                    Brush.radialGradient(listOf(p.glowBottom.copy(alpha = 0.13f), Color.Transparent)),
                    CircleShape
                )
        )
        // Subtle vignette for depth + text legibility
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f))
                )
            )
        )
    }
}

// ----------------------------------------------------------------------------------
// MESH — multi-point color gradient blobs blending at different positions
// ----------------------------------------------------------------------------------

@Composable
private fun MeshBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.35f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            onDrawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.5f
                // 4 color blobs at corner positions — mesh gradient effect
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.glowTop.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.12f, h * 0.18f), radius = r
                    ),
                    center = Offset(w * 0.12f, h * 0.18f), radius = r
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.glowBottom.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * 0.88f, h * 0.12f), radius = r
                    ),
                    center = Offset(w * 0.88f, h * 0.12f), radius = r
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.accent.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(w * 0.18f, h * 0.85f), radius = r
                    ),
                    center = Offset(w * 0.18f, h * 0.85f), radius = r
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.glowTop.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(w * 0.82f, h * 0.82f), radius = r
                    ),
                    center = Offset(w * 0.82f, h * 0.82f), radius = r
                )
            }
        }
    )
}

// ----------------------------------------------------------------------------------
// CONTOUR — undulating topographic contour lines across the card
// ----------------------------------------------------------------------------------

@Composable
private fun ContourBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.5f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val numLines = 18
            val steps = 60
            onDrawWithContent {
                drawContent()
                for (line in 0 until numLines) {
                    val baseY = h * (line.toFloat() / (numLines - 1))
                    val path = Path()
                    for (i in 0..steps) {
                        val t = i.toDouble() / steps
                        val x = (t * w).toFloat()
                        val undulation = (sin(t * PI * 3.0 + line * 0.7) * 10 +
                                          sin(t * PI * 6.0 + line * 1.2) * 5).toFloat()
                        val y = baseY + undulation
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, p.glowTop.copy(alpha = 0.07f), style = Stroke(width = 1f))
                }
            }
        }
    )
}

// ----------------------------------------------------------------------------------
// BOKEH — soft out-of-focus light circles scattered across the card
// ----------------------------------------------------------------------------------

@Composable
private fun BokehBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.4f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val circles = listOf(
                Triple(0.12f, 0.15f, 60f) to p.glowTop,
                Triple(0.75f, 0.1f, 45f) to p.accent,
                Triple(0.9f, 0.4f, 70f) to p.glowBottom,
                Triple(0.3f, 0.35f, 35f) to p.glowTop,
                Triple(0.6f, 0.55f, 55f) to p.accent,
                Triple(0.15f, 0.7f, 50f) to p.glowBottom,
                Triple(0.85f, 0.75f, 40f) to p.glowTop,
                Triple(0.5f, 0.25f, 30f) to p.accent,
                Triple(0.4f, 0.85f, 45f) to p.glowBottom,
                Triple(0.7f, 0.9f, 35f) to p.glowTop
            )
            onDrawWithContent {
                drawContent()
                circles.forEach { (pos, col) ->
                    val cx = w * pos.first
                    val cy = h * pos.second
                    val r = pos.third
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(col.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = r
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    )
                }
            }
        }
    )
}

// ----------------------------------------------------------------------------------
// PRISM — diagonal light beams with gradient falloff across the card
// ----------------------------------------------------------------------------------

@Composable
private fun PrismBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.45f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            onDrawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val skew = h * 0.18f
                val rayW = w * 0.14f
                for (i in 0 until 5) {
                    val baseX = w * (0.1f + i * 0.22f)
                    val path = Path().apply {
                        moveTo(baseX, 0f)
                        lineTo(baseX + rayW, 0f)
                        lineTo(baseX + rayW + skew, h)
                        lineTo(baseX + skew, h)
                        close()
                    }
                    val col = if (i % 2 == 0) p.glowTop else p.glowBottom
                    drawPath(
                        path = path,
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, col.copy(alpha = 0.12f), Color.Transparent),
                            start = Offset(baseX + rayW / 2f, 0f),
                            end = Offset(baseX + rayW / 2f + skew, h)
                        )
                    )
                }
            }
        }
    )
}

// ----------------------------------------------------------------------------------
// SWEEP — angular conic color sweep around center
// ----------------------------------------------------------------------------------

@Composable
private fun SweepBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.4f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = maxOf(size.width, size.height)
            onDrawWithContent {
                drawContent()
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            p.glowTop.copy(alpha = 0.18f),
                            p.accent.copy(alpha = 0.08f),
                            p.glowBottom.copy(alpha = 0.18f),
                            p.glowTop.copy(alpha = 0.0f),
                            p.glowTop.copy(alpha = 0.18f)
                        ),
                        center = center
                    ),
                    center = center,
                    radius = maxR
                )
                // Soft center bloom to anchor the sweep
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(p.accent.copy(alpha = 0.1f), Color.Transparent),
                        center = center,
                        radius = 100f
                    ),
                    center = center,
                    radius = 100f
                )
            }
        }
    )
}

// ----------------------------------------------------------------------------------
// HALFTONE — dot grid with varying sizes (print-art aesthetic)
// ----------------------------------------------------------------------------------

@Composable
private fun HalftoneBackdrop(p: ShareThemePalette, imageUrl: String?) {
    BlurredArt(imageUrl, p.overlay, alpha = 0.55f)
    Box(
        modifier = Modifier.fillMaxSize().drawWithCache {
            val cellSize = 16.dp.toPx()
            val w = size.width
            val h = size.height
            val cols = (w / cellSize).toInt() + 2
            val rows = (h / cellSize).toInt() + 2
            val halfW = w / 2f
            val halfH = h / 2f
            onDrawWithContent {
                drawContent()
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val cx = col * cellSize - cellSize / 2f
                        val cy = row * cellSize - cellSize / 2f
                        // Distance factor — 1 at center, 0 at corners
                        val dx = (cx - halfW) / halfW
                        val dy = (cy - halfH) / halfH
                        val distSq = (dx * dx + dy * dy) * 0.5f
                        val factor = (1f - distSq).coerceIn(0f, 1f)
                        val dotR = cellSize * 0.4f * factor
                        if (dotR > 0.5f) {
                            drawCircle(
                                color = p.glowTop.copy(alpha = 0.1f * factor),
                                radius = dotR,
                                center = Offset(cx, cy)
                            )
                        }
                    }
                }
            }
        }
    )
}

// ponytail: structural backdrops use drawWithCache + canvas patterns; keep alpha low so
// white foreground content stays legible. If a theme reads too busy on a given image,
// dial overlay alpha up in ShareThemePalette above.