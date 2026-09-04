package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// In-memory LRU cache for CPU-rendered backdrops (fluted glass, glitch motion, ascii art)
// Avoids redundant computations across preview and capture view, and speeds up theme toggles.
private val backdropBitmapCache = LruCache<String, Bitmap>(8)

/**
 * Backdrop construction. This is what makes themes read as different places,
 * not hue swaps of one backdrop: each style builds its own scene from
 * different geometry — scattered bokeh lights, fluted-glass refraction of
 * the artwork itself, a monospace ASCII glyph field, editorial rings, a sun
 * disc, datamosh glitch static. [PHOTO_GLOW] builds on the blurred artwork,
 * [FLUTED_GLASS] refracts it through a vertical ribbed pane, and
 * [ASCII_ARTWORK] goes further: it converts the artwork itself into true
 * ASCII art and paints that as the backdrop.
 *
 * Everything is drawn with plain brushes/paths on Canvas so it renders
 * identically on-screen and in the software-canvas share capture.
 */
enum class ShareBackdropStyle {
    /** Blurred artwork backdrop with ambient orbs bleeding from two corners. */
    PHOTO_GLOW,
    /** Fluted glass: the artwork refracted through a vertical ribbed pane. */
    FLUTED_GLASS,
    /** Terminal ASCII: a monospace glyph field shaded by layered waves. */
    ASCII_FIELD,
    /** Album art rendered as true ASCII art — brightness-mapped ramp glyphs
     *  tinted with the artwork's own colors on a terminal-dark field. */
    ASCII_ARTWORK,
    /** Editorial: flat field, hard vignette, two thin offset ring outlines. */
    RINGS_VIGNETTE,
    /** Morning paper: a visible sun disc with halo sinking in from above. */
    SUN_WASH,
    /** Datamosh: motion blur, RGB-split slices, CRT scanlines, and datamosh tear blocks. */
    GLITCH_MOTION
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
    val badgeShape: Shape = CircleShape,
    // Layout text system — lets a theme restyle the typography of every card,
    // not just its backdrop. Null means "use the card's default": only themes
    // that opt in (e.g. MINIMUM, with light headlines and thin, widely
    // tracked labels) change the type, so the other themes stay pixel-identical.
    val headlineWeight: FontWeight? = null,
    val labelWeight: FontWeight? = null,
    val labelTracking: TextUnit? = null
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
    // Fluted glass: the listener's own artwork refracted through vertical
    // glass ribs — the cover is converted into a fluted-glass texture (prism
    // bends, rib shadows, seam highlights) that becomes the card's backdrop,
    // under a smoked overlay that keeps the glass mood and text readable.
    GLASS(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0B0D14), Color(0xFF1A1330), Color(0xFF0F0B18)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.38f),
                Color(0xFF0F0C1A).copy(alpha = 0.56f),
                Color(0xFF0A0812).copy(alpha = 0.84f)
            ),
            accent = Color(0xFFBAE6FD),
            glowTop = Color(0xFF67E8F9),
            glowBottom = Color(0xFFE879F9),
            rank1Tint = Color(0xFF7DD3FC),
            backdrop = ShareBackdropStyle.FLUTED_GLASS,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(24.dp),
            thumbShape = RoundedCornerShape(14.dp)
        )
    ),
    // Terminal: the #1 item's cover art converted into true ASCII art
    // (brightness-mapped ramp glyphs tinted with the artwork's own colors)
    // on near-black, sharp corners, phosphor-green UI accents.
    ASCII(
        ShareThemePalette(
            gradient = listOf(Color(0xFF040704), Color(0xFF08120A), Color(0xFF050A06)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.30f),
                Color.Black.copy(alpha = 0.42f),
                Color(0xFF020503).copy(alpha = 0.75f)
            ),
            accent = Color(0xFF4ADE80),
            glowTop = Color(0xFF22C55E),
            glowBottom = Color(0xFF86EFAC),
            rank1Tint = Color(0xFF4ADE80),
            backdrop = ShareBackdropStyle.ASCII_ARTWORK,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(4.dp),
            thumbShape = RoundedCornerShape(4.dp),
            badgeShape = RoundedCornerShape(4.dp)
        )
    ),
    // Minimum: the editorial monochrome field, extended into the type system —
    // light headlines and thin, widely tracked labels instead of heavy black.
    MINIMUM(
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
            badgeShape = RoundedCornerShape(3.dp),
            headlineWeight = FontWeight.Light,
            labelWeight = FontWeight.Normal,
            labelTracking = 2.4.sp
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
    // Datamosh: the base album artwork transformed into a motion-blurred,
    // datamoshed cyberpunk backdrop — horizontal speed streaks, chromatic
    // RGB channel splits, datamosh tear slices, CRT scanlines, and neon blocks.
    GLITCH(
        ShareThemePalette(
            gradient = listOf(Color(0xFF07060B), Color(0xFF150A20), Color(0xFF050409)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.38f),
                Color(0xFF0A0714).copy(alpha = 0.62f),
                Color(0xFF050310).copy(alpha = 0.88f)
            ),
            accent = Color(0xFFE879F9),
            glowTop = Color(0xFF22D3EE),
            glowBottom = Color(0xFFE879F9),
            rank1Tint = Color(0xFFE879F9),
            backdrop = ShareBackdropStyle.GLITCH_MOTION,
            decorationAlpha = 1f,
            usesArtwork = true,
            cardShape = RoundedCornerShape(10.dp),
            thumbShape = RoundedCornerShape(8.dp),
            badgeShape = RoundedCornerShape(6.dp)
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
fun ShareThemeDecorations(
    palette: ShareThemePalette,
    imageUrl: String? = null,
    backdropBitmap: Bitmap? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        when (palette.backdrop) {
            ShareBackdropStyle.PHOTO_GLOW -> {
                GlowOrb(this, Alignment.TopEnd, x = 50.dp, y = (-50).dp, size = 300.dp,
                    color = palette.glowTop.copy(alpha = palette.decorationAlpha))
                GlowOrb(this, Alignment.BottomStart, x = (-50).dp, y = 50.dp, size = 300.dp,
                    color = palette.glowBottom.copy(alpha = palette.decorationAlpha))
            }
            // Fluted glass: the artwork refracted into a vertical ribbed pane.
            // The fluid pools keep the smoked pane alive while the artwork
            // decodes — or when it is missing entirely.
            ShareBackdropStyle.FLUTED_GLASS ->
                if (imageUrl.isNullOrBlank() && backdropBitmap == null) {
                    Canvas(modifier = Modifier.fillMaxSize()) { drawGlassFluidPools(palette) }
                } else {
                    FlutedGlassBackdrop(
                        palette = palette,
                        imageUrl = imageUrl,
                        sourceBitmap = backdropBitmap
                    )
                }
            ShareBackdropStyle.ASCII_FIELD -> Canvas(modifier = Modifier.fillMaxSize()) {
                drawAsciiWaveField(palette)
            }
            // True ASCII art of the cover; the wave field keeps the card alive
            // while the artwork decodes — or when it is missing entirely.
            ShareBackdropStyle.ASCII_ARTWORK ->
                if (imageUrl.isNullOrBlank()) {
                    Canvas(modifier = Modifier.fillMaxSize()) { drawAsciiWaveField(palette) }
                } else {
                    AsciiArtworkBackdrop(palette = palette, imageUrl = imageUrl)
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
            ShareBackdropStyle.GLITCH_MOTION ->
                if (imageUrl.isNullOrBlank() && backdropBitmap == null) {
                    Canvas(modifier = Modifier.fillMaxSize()) { drawGlitchStaticFallback(palette) }
                } else {
                    GlitchMotionBackdrop(
                        palette = palette,
                        imageUrl = imageUrl,
                        sourceBitmap = backdropBitmap
                    )
                }
        }
    }
}

// ---------------------------------------------------------------------------
// ASCII_ARTWORK backdrop — the cover art converted into true ASCII art,
// learned from ascii-magic's styles: the brightness-mapped ramp of
// "Characters", the mixed letter/punctuation families of "Mixed Glyphs", and
// the ordered-dither texture of "Dither", on top of a per-artwork dynamic
// tone mapping so every cover — dark, hazy or blown-out — fills the ramp.
// ---------------------------------------------------------------------------

/** Glyph advance as a fraction of the monospace text size. */
private const val ASCII_COL_STEP = 0.72f

/** Line height as a fraction of the monospace text size. */
private const val ASCII_ROW_STEP = 1.25f

/** Horizontal resolution of the ASCII conversion, in glyph columns. */
private const val ASCII_COLUMNS = 84

/**
 * Mixed-family density ramp (ascii-magic "Mixed Glyphs"): punctuation flows
 * into letters and symbols from sparse to dense, giving portraits and
 * high-detail covers richer texture than a pure punctuation ramp.
 */
private const val ASCII_RAMP = " .,:;=+*coxm#%@"

/**
 * Ordered-dither strength (ascii-magic "Dither"): how far the Bayer noise may
 * swing a cell's brightness before ramp quantization, in 0..1 units.
 */
private const val ASCII_DITHER = 0.35f

/** Bayer 4x4 ordered-dither matrix, values 0..15. */
private val ASCII_BAYER_4X4 = arrayOf(
    intArrayOf(0, 8, 2, 10),
    intArrayOf(12, 4, 14, 6),
    intArrayOf(3, 11, 1, 9),
    intArrayOf(15, 7, 13, 5)
)

/**
 * Precomputed ASCII conversion of one artwork. All the per-pixel work happens
 * once at build time; the draw pass only scales and paints, so the live
 * preview and the software-canvas share capture render the identical
 * composition.
 */
private class AsciiArtGrid(
    val columns: Int,
    val rows: Int,
    /** Ramp index per cell; 0 (space) marks an empty cell. */
    val rampIndices: IntArray,
    /** Per-cell brightness after the contrast curve, 0..1 — drives alpha. */
    val brightness: FloatArray,
    /** Per-cell glyph colour (opaque ARGB), sampled from the artwork. */
    val colors: IntArray
)

/**
 * Backdrop that renders [imageUrl]'s artwork as ASCII art. The conversion
 * runs once per (url, aspect) on a background dispatcher; until it lands the
 * deterministic wave field keeps the card alive. A readability overlay fades
 * the art behind the card content, same contract as the blurred-artwork
 * themes.
 */
@Composable
private fun AsciiArtworkBackdrop(palette: ShareThemePalette, imageUrl: String) {
    // 9:16 until the first layout reports the real card aspect; the backdrop is
    // rebuilt only if the aspect actually differs.
    var aspect by remember { mutableStateOf(9f / 16f) }
    val context = LocalContext.current
    val backdrop by produceState<Bitmap?>(initialValue = null, imageUrl, aspect) {
        value = runCatching {
            buildAsciiArtBackdrop(context, imageUrl, aspect)
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val measured = size.width.toFloat() / size.height
                    if (measured != aspect) aspect = measured
                }
            }
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val art = backdrop
            if (art != null) {
                // The bitmap is pre-cropped to the card aspect, so a full-size
                // destination is a 1:1 map.
                drawImage(
                    image = art.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            } else {
                drawAsciiWaveField(palette)
            }
        }
        // Readability overlay above the glyph art.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = Brush.verticalGradient(palette.overlay))
        )
    }
}

/**
 * Fluted-glass backdrop: the listener's artwork refracted through a vertical
 * ribbed pane — the paper.design fluted-glass model, tuned for background use
 * behind card content. The effect is computed on the CPU into a bitmap (prism
 * refraction per rib, rib shadows, seam highlight strokes, one-directional
 * soften) so it renders identically on-screen and in the software-canvas
 * share capture. The fluid pools stand in while the artwork decodes; the
 * palette overlay on top keeps card text readable.
 */
@Composable
private fun FlutedGlassBackdrop(
    palette: ShareThemePalette,
    imageUrl: String? = null,
    sourceBitmap: Bitmap? = null
) {
    // 9:16 until the first layout reports the real card aspect; the texture is
    // rebuilt only if the aspect actually differs.
    var aspect by remember { mutableStateOf(9f / 16f) }
    val context = LocalContext.current
    val fluted by produceState<Bitmap?>(initialValue = null, imageUrl, sourceBitmap, aspect) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (sourceBitmap != null) {
                    val safeSource = if (sourceBitmap.config == Bitmap.Config.HARDWARE) {
                        sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        sourceBitmap
                    }
                    val cropped = safeSource.centerCroppedToAspect(aspect)
                    val targetH = (FLUTED_RENDER_WIDTH / aspect).roundToInt().coerceAtLeast(64)
                    cropped.downscaledTo(FLUTED_RENDER_WIDTH, targetH).renderFlutedGlass()
                } else if (!imageUrl.isNullOrBlank()) {
                    buildFlutedGlassBitmap(context, imageUrl, aspect)
                } else null
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val measured = size.width.toFloat() / size.height
                    if (measured != aspect) aspect = measured
                }
            }
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val art = fluted
            if (art != null) {
                // The bitmap is pre-cropped to the card aspect, so a full-size
                // destination is a 1:1 map.
                drawImage(
                    image = art.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            } else {
                drawGlassFluidPools(palette)
            }
        }
        // Readability overlay above the glass.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = Brush.verticalGradient(palette.overlay))
        )
    }
}

/**
 * Loads the artwork through Coil (sharing the app's memory/disk cache) and
 * converts it into a fluted-glass texture at the card's aspect ratio.
 */
private suspend fun buildFlutedGlassBitmap(
    context: Context,
    imageUrl: String,
    aspect: Float
): Bitmap? {
    val cacheKey = "fluted:$imageUrl:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val request = ImageRequest.Builder(context)
        .data(MusicBrainzEnrichmentService.fixHttpUrl(imageUrl))
        .size(720, 720)
        .allowHardware(false)
        .build()
    val source = (context.imageLoader.execute(request).image as? BitmapImage)?.bitmap
        ?: return null
    val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    return withContext(Dispatchers.Default) {
        val cropped = safeSource.centerCroppedToAspect(aspect)
        val targetH = (FLUTED_RENDER_WIDTH / aspect).roundToInt().coerceAtLeast(64)
        val result = cropped.downscaledTo(FLUTED_RENDER_WIDTH, targetH).renderFlutedGlass()
        backdropBitmapCache.put(cacheKey, result)
        result
    }
}

/**
 * CPU fluted-glass pass (the paper.design model), in three stages:
 *
 * 1. Soften — Gaussian-approximating box blur bakes a dreamy base into the
 *    artwork so it reads as glass depth, not a pasted photo.
 * 2. Refract — every rib acts as a cylindrical lens: it compresses a wider
 *    window of the softened art into itself, nudged sideways so the ribs
 *    melt asymmetrically. Near each seam the window crossfades into the
 *    neighbour's, so sampling stays continuous — ribs refract content
 *    without ever snapping into painted-on lines on smooth fields.
 * 3. Shade — a smooth cylindrical rod shading (bright rib centre, darker
 *    seams) plus a faint seam stroke, both scaled by the local luminance so
 *    dark fields never show drawn-on hairlines.
 */
private fun Bitmap.renderFlutedGlass(): Bitmap {
    val w = width
    val h = height
    var src = IntArray(w * h)
    getPixels(src, 0, w, 0, 0, w, h)
    // Stage 1 — soften the art into a glassy base (two h/v box rounds ≈ Gaussian).
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = true)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = false)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = true)
    src = blurBoxPass(src, w, h, FLUTED_SOFTEN_RADIUS, horizontal = false)
    val out = IntArray(w * h)
    val stripeW = w.toFloat() / FLUTED_STRIPE_COUNT
    val compressW = stripeW * FLUTED_COMPRESS
    val shiftPx = stripeW * FLUTED_SHIFT
    val smearPx = stripeW * FLUTED_BLUR
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val s = x / stripeW
            // Rib-local coordinate u ∈ [-1, 1) from the rib centre.
            val rib = (s + 0.5f).toInt()
            val u = (s - rib) * 2f
            // Cylindrical-lens refraction: the rib compresses a wider window
            // of the art into itself, nudged sideways for the melt.
            val ownX = rib * stripeW + u * compressW + shiftPx
            // Neighbour window across the nearest seam; the crossfade keeps
            // sampling continuous so seams flow instead of snapping.
            val neighbourX = if (u >= 0f) {
                (rib + 1) * stripeW + (u - 2f) * compressW + shiftPx
            } else {
                (rib - 1) * stripeW + (u + 2f) * compressW + shiftPx
            }
            val fadeT = ((abs(u) - (1f - FLUTED_SEAM_FADE)) / FLUTED_SEAM_FADE)
                .coerceIn(0f, 1f)
            val fade = fadeT * fadeT * (3f - 2f * fadeT)
            val sampleX = ownX * (1f - fade) + neighbourX * fade
            // Directional smear along the refraction axis.
            val c1 = sampleFlutedColumn(src, w, sampleX - smearPx, y)
            val c2 = sampleFlutedColumn(src, w, sampleX, y)
            val c3 = sampleFlutedColumn(src, w, sampleX + smearPx, y)
            var r = ((c1 shr 16 and 0xFF) + (c2 shr 16 and 0xFF) + (c3 shr 16 and 0xFF)) / 3
            var g = ((c1 shr 8 and 0xFF) + (c2 shr 8 and 0xFF) + (c3 shr 8 and 0xFF)) / 3
            var b = ((c1 and 0xFF) + (c2 and 0xFF) + (c3 and 0xFF)) / 3
            // Cylindrical rod shading, luminance-adaptive: glass only reads
            // where the art is bright, so smooth fields stay hairline-free.
            val edge = abs(u).coerceIn(0f, 1f)
            val lum = (r + g + b) / 765f
            val shade = 1f - FLUTED_SHADOW * edge.pow(1.2f) * lum
            val strokeT = ((edge - 0.86f) / 0.14f).coerceIn(0f, 1f)
            val stroke = FLUTED_HIGHLIGHT * strokeT * strokeT * lum
            r = (r * shade + 255f * stroke).toInt().coerceIn(0, 255)
            g = (g * shade + 255f * stroke).toInt().coerceIn(0, 255)
            b = (b * shade + 255f * stroke).toInt().coerceIn(0, 255)
            out[row + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    return result
}

/**
 * One separable box-blur pass. Alternating horizontal/vertical rounds
 * approximate a Gaussian; the sliding-window sums keep the cost linear in
 * the pixel count regardless of radius.
 */
private fun blurBoxPass(
    src: IntArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean
): IntArray {
    val out = IntArray(src.size)
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = (radius * 2 + 1).toFloat()
    for (line in 0 until lines) {
        var asum = 0
        var rs = 0
        var gs = 0
        var bs = 0
        for (d in -radius..radius) {
            val c = src[blurIndex(line, d.coerceIn(0, len - 1), w, horizontal)]
            asum += (c ushr 24) and 0xFF
            rs += (c shr 16) and 0xFF
            gs += (c shr 8) and 0xFF
            bs += c and 0xFF
        }
        for (i in 0 until len) {
            val a = (asum / div + 0.5f).toInt().coerceIn(0, 255)
            val r = (rs / div + 0.5f).toInt().coerceIn(0, 255)
            val g = (gs / div + 0.5f).toInt().coerceIn(0, 255)
            val b = (bs / div + 0.5f).toInt().coerceIn(0, 255)
            out[blurIndex(line, i, w, horizontal)] =
                (a shl 24) or (r shl 16) or (g shl 8) or b
            val addC = src[blurIndex(line, (i + radius + 1).coerceIn(0, len - 1), w, horizontal)]
            val remC = src[blurIndex(line, (i - radius).coerceIn(0, len - 1), w, horizontal)]
            asum += ((addC ushr 24) and 0xFF) - ((remC ushr 24) and 0xFF)
            rs += ((addC shr 16) and 0xFF) - ((remC shr 16) and 0xFF)
            gs += ((addC shr 8) and 0xFF) - ((remC shr 8) and 0xFF)
            bs += (addC and 0xFF) - (remC and 0xFF)
        }
    }
    return out
}

private fun blurIndex(line: Int, i: Int, w: Int, horizontal: Boolean): Int =
    if (horizontal) line * w + i else i * w + line

/** Horizontal bilinear sample with edge clamping. */
private fun sampleFlutedColumn(src: IntArray, w: Int, xf: Float, y: Int): Int {
    val x0 = xf.toInt()
    val f = (xf - x0).coerceIn(0f, 1f)
    val xa = x0.coerceIn(0, w - 1)
    val xb = (x0 + 1).coerceIn(0, w - 1)
    val ca = src[y * w + xa]
    val cb = src[y * w + xb]
    val r = (ca shr 16 and 0xFF) * (1f - f) + (cb shr 16 and 0xFF) * f
    val g = (ca shr 8 and 0xFF) * (1f - f) + (cb shr 8 and 0xFF) * f
    val b = (ca and 0xFF) * (1f - f) + (cb and 0xFF) * f
    return (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

// Fluted-glass parameters, tuned for background use behind card content:
// rib count across the card width, the base softening baked into the art,
// the cylindrical-lens compression (window width in rib widths) and its
// sideways nudge, the directional smear, the seam crossfade width that
// keeps sampling continuous, and the luminance-adaptive rod shading.
private const val FLUTED_RENDER_WIDTH = 576
private const val FLUTED_STRIPE_COUNT = 26
private const val FLUTED_SOFTEN_RADIUS = 10
private const val FLUTED_COMPRESS = 1.9f
private const val FLUTED_SHIFT = 0.35f
private const val FLUTED_BLUR = 0.35f
private const val FLUTED_SEAM_FADE = 0.35f
private const val FLUTED_SHADOW = 0.12f
private const val FLUTED_HIGHLIGHT = 0.10f

// Glitch motion backdrop parameters: render width of the baked composite
private const val GLITCH_RENDER_WIDTH = 576

private data class GlitchTearSlice(
    val yFraction: Float,
    val hFraction: Float,
    val shiftPx: Int,
    val chromaticShift: Int,
    val sharpness: Float
)

private val GlitchTearSlices = listOf(
    GlitchTearSlice(0.08f, 0.038f, 36, 14, 0.75f),
    GlitchTearSlice(0.19f, 0.026f, -44, 18, 0.60f),
    GlitchTearSlice(0.33f, 0.046f, 48, 22, 0.85f),
    GlitchTearSlice(0.46f, 0.028f, -30, 12, 0.50f),
    GlitchTearSlice(0.59f, 0.052f, 42, 20, 0.80f),
    GlitchTearSlice(0.72f, 0.022f, -50, 16, 0.65f),
    GlitchTearSlice(0.84f, 0.040f, 28, 10, 0.55f)
)

/**
 * Datamosh motion-blur + glitch backdrop: converts the base artwork into a
 * high-speed cybernetic scene — directional horizontal speed blur, RGB
 * channel splits (chromatic aberration), datamoshed tear slices that reveal
 * crisp displaced fragments of the cover art, micro-scanline row jitter, and
 * neon digital corruption blocks.
 *
 * Computed on the CPU into a deterministic ARGB_8888 bitmap so the live Compose
 * preview and the software-canvas share capture stay pixel-identical.
 */
@Composable
private fun GlitchMotionBackdrop(
    palette: ShareThemePalette,
    imageUrl: String? = null,
    sourceBitmap: Bitmap? = null
) {
    var aspect by remember { mutableStateOf(9f / 16f) }
    val context = LocalContext.current
    val glitched by produceState<Bitmap?>(initialValue = null, imageUrl, sourceBitmap, aspect) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (sourceBitmap != null) {
                    val safeSource = if (sourceBitmap.config == Bitmap.Config.HARDWARE) {
                        sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        sourceBitmap
                    }
                    val cropped = safeSource.centerCroppedToAspect(aspect)
                    val targetH = (GLITCH_RENDER_WIDTH / aspect).roundToInt().coerceAtLeast(64)
                    cropped.downscaledTo(GLITCH_RENDER_WIDTH, targetH).renderGlitchMotionEffect(palette)
                } else if (!imageUrl.isNullOrBlank()) {
                    buildGlitchMotionBitmap(context, imageUrl, aspect, palette)
                } else null
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    val measured = size.width.toFloat() / size.height
                    if (measured != aspect) aspect = measured
                }
            }
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val art = glitched
            if (art != null) {
                drawImage(
                    image = art.asImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
            } else {
                drawGlitchStaticFallback(palette)
            }
        }
        // Readability overlay above the glitch artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = Brush.verticalGradient(palette.overlay))
        )
    }
}

/**
 * Loads the artwork through Coil (sharing the app's memory/disk cache) and
 * converts it into a motion-blur + glitch texture at the card's aspect ratio.
 */
private suspend fun buildGlitchMotionBitmap(
    context: Context,
    imageUrl: String,
    aspect: Float,
    palette: ShareThemePalette
): Bitmap? {
    val cacheKey = "glitch:$imageUrl:$aspect:${palette.accent.value}"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val request = ImageRequest.Builder(context)
        .data(MusicBrainzEnrichmentService.fixHttpUrl(imageUrl))
        .size(720, 720)
        .allowHardware(false)
        .build()
    val source = (context.imageLoader.execute(request).image as? BitmapImage)?.bitmap
        ?: return null
    val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    return withContext(Dispatchers.Default) {
        val cropped = safeSource.centerCroppedToAspect(aspect)
        val targetH = (GLITCH_RENDER_WIDTH / aspect).roundToInt().coerceAtLeast(64)
        val result = cropped.downscaledTo(GLITCH_RENDER_WIDTH, targetH).renderGlitchMotionEffect(palette)
        backdropBitmapCache.put(cacheKey, result)
        result
    }
}

/**
 * Transforms the base artwork into a motion-blur + glitch composition:
 * 1. Motion Blur — two-pass horizontal box blur (radius 34 and 22) plus slight
 *    vertical softening (radius 5) smears the artwork's colors into silky speed trails.
 * 2. Chromatic Aberration — base RGB channel split offsets red and blue channels.
 * 3. Datamosh Tear Slices — horizontal bands shift horizontally, interpolating
 *    between motion blur and crisp raw artwork details with exaggerated RGB divergence.
 * 4. Scanline Jitter — deterministic row micro-displacements emulate CRT sync tears.
 * 5. Raster Interlace — periodic scanline dimming provides analog monitor texture.
 * 6. Digital Noise Blocks — deterministic rectangular corruptions (color inversion,
 *    neon cyan/magenta tints, and highlight sparks).
 */
private fun Bitmap.renderGlitchMotionEffect(palette: ShareThemePalette): Bitmap {
    val w = width
    val h = height
    val rawPixels = IntArray(w * h)
    getPixels(rawPixels, 0, w, 0, 0, w, h)

    // Stage 1 — Directional motion blur (2 horizontal rounds + 1 mild vertical round)
    var motionPixels = blurBoxPass(rawPixels, w, h, radius = 34, horizontal = true)
    motionPixels = blurBoxPass(motionPixels, w, h, radius = 22, horizontal = true)
    motionPixels = blurBoxPass(motionPixels, w, h, radius = 5, horizontal = false)

    val outPixels = IntArray(w * h)

    // Stage 2 — Base chromatic aberration on motion blur
    for (y in 0 until h) {
        val rowOff = y * w
        for (x in 0 until w) {
            val rX = (x + 5).coerceAtMost(w - 1)
            val bX = (x - 5).coerceAtLeast(0)
            val rVal = (motionPixels[rowOff + rX] shr 16) and 0xFF
            val gVal = (motionPixels[rowOff + x] shr 8) and 0xFF
            val bVal = motionPixels[rowOff + bX] and 0xFF
            outPixels[rowOff + x] = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
        }
    }

    // Stage 3 — Datamosh horizontal tear slices
    for (slice in GlitchTearSlices) {
        val yStart = (slice.yFraction * h).roundToInt().coerceIn(0, h - 1)
        val yEnd = ((slice.yFraction + slice.hFraction) * h).roundToInt().coerceIn(yStart + 1, h)
        val s = slice.sharpness
        val invS = 1f - s

        for (y in yStart until yEnd) {
            val rowOff = y * w
            for (x in 0 until w) {
                val rSampleX = (((x - slice.shiftPx + slice.chromaticShift) % w) + w) % w
                val gSampleX = (((x - slice.shiftPx) % w) + w) % w
                val bSampleX = (((x - slice.shiftPx - slice.chromaticShift) % w) + w) % w

                val rIdx = rowOff + rSampleX
                val gIdx = rowOff + gSampleX
                val bIdx = rowOff + bSampleX

                val rRaw = (rawPixels[rIdx] shr 16) and 0xFF
                val rMot = (motionPixels[rIdx] shr 16) and 0xFF
                val rVal = (rRaw * s + rMot * invS).toInt().coerceIn(0, 255)

                val gRaw = (rawPixels[gIdx] shr 8) and 0xFF
                val gMot = (motionPixels[gIdx] shr 8) and 0xFF
                val gVal = (gRaw * s + gMot * invS).toInt().coerceIn(0, 255)

                val bRaw = rawPixels[bIdx] and 0xFF
                val bMot = motionPixels[bIdx] and 0xFF
                val bVal = (bRaw * s + bMot * invS).toInt().coerceIn(0, 255)

                outPixels[rowOff + x] = (0xFF shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
            }
        }
    }

    // Stage 4 — Scanline micro-jitter (CRT horizontal sync jitter)
    val rng = Random(GLITCH_SEED)
    val rowCopy = IntArray(w)
    for (y in 0 until h) {
        if (rng.nextFloat() < 0.07f) {
            val dx = rng.nextInt(-14, 15)
            if (dx != 0) {
                val rowOff = y * w
                System.arraycopy(outPixels, rowOff, rowCopy, 0, w)
                for (x in 0 until w) {
                    val sx = ((x - dx) % w + w) % w
                    outPixels[rowOff + x] = rowCopy[sx]
                }
            }
        }
    }

    // Stage 5 — CRT Raster scanline darkening
    for (y in 0 until h step 3) {
        val rowOff = y * w
        for (x in 0 until w) {
            val p = outPixels[rowOff + x]
            val r = (((p shr 16) and 0xFF) * 82) / 100
            val g = (((p shr 8) and 0xFF) * 82) / 100
            val b = ((p and 0xFF) * 82) / 100
            outPixels[rowOff + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    // Stage 6 — Digital noise blocks and neon color corruptions
    val rngBlocks = Random(GLITCH_SEED + 101)
    val cyanArgb = palette.glowTop.toArgb()
    val magentaArgb = palette.glowBottom.toArgb()
    val cyanR = (cyanArgb shr 16) and 0xFF
    val cyanG = (cyanArgb shr 8) and 0xFF
    val cyanB = cyanArgb and 0xFF
    val magR = (magentaArgb shr 16) and 0xFF
    val magG = (magentaArgb shr 8) and 0xFF
    val magB = magentaArgb and 0xFF

    repeat(14) {
        val bw = rngBlocks.nextInt(24, 110)
        val bh = rngBlocks.nextInt(4, 16)
        val bx = rngBlocks.nextInt(0, (w - bw).coerceAtLeast(1))
        val by = rngBlocks.nextInt(0, (h - bh).coerceAtLeast(1))
        val mode = rngBlocks.nextInt(4)
        val yLimit = (by + bh).coerceAtMost(h)
        val xLimit = (bx + bw).coerceAtMost(w)
        for (iy in by until yLimit) {
            val rowOff = iy * w
            for (ix in bx until xLimit) {
                val cur = outPixels[rowOff + ix]
                val cr = (cur shr 16) and 0xFF
                val cg = (cur shr 8) and 0xFF
                val cb = cur and 0xFF
                val nr: Int
                val ng: Int
                val nb: Int
                when (mode) {
                    0 -> {
                        nr = 255 - cr
                        ng = 255 - cg
                        nb = 255 - cb
                    }
                    1 -> {
                        nr = (cr + cyanR) shr 1
                        ng = (cg + cyanG) shr 1
                        nb = (cb + cyanB) shr 1
                    }
                    2 -> {
                        nr = (cr + magR) shr 1
                        ng = (cg + magG) shr 1
                        nb = (cb + magB) shr 1
                    }
                    else -> {
                        nr = (cr * 4 + 255 * 6) / 10
                        ng = (cg * 4 + 255 * 6) / 10
                        nb = (cb * 4 + 255 * 6) / 10
                    }
                }
                outPixels[rowOff + ix] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(outPixels, 0, w, 0, 0, w, h)
    return result
}

// ASCII backdrop parameters: render width of the baked composite, the blurred
// art base that gives the card its colour atmosphere, and the phosphor bloom
// drawn under the crisp glyphs so they read as luminous terminals.
private const val ASCII_RENDER_WIDTH = 576
private const val ASCII_BASE_BLUR_RADIUS = 14
private const val ASCII_BASE_DIM = 0.55f
private const val ASCII_GLOW_BLUR_RADIUS = 7
private const val ASCII_GLOW_ALPHA = 0.85f
private const val ASCII_BLUR_ROUNDS = 2

/**
 * Loads the artwork through Coil (sharing the app's memory/disk cache) and
 * converts it into an [AsciiArtGrid]. The conversion is *dynamic*: the
 * artwork's own luminance histogram is auto-leveled and re-gamma'd per image,
 * so dark, hazy and blown-out covers all fill the ramp instead of collapsing
 * into a black screen.
 */
private suspend fun buildAsciiArtGrid(
    context: Context,
    imageUrl: String,
    aspect: Float
): AsciiArtGrid? {
    val request = ImageRequest.Builder(context)
        .data(MusicBrainzEnrichmentService.fixHttpUrl(imageUrl))
        .size(640, 640)
        .allowHardware(false)
        .build()
    val source = (context.imageLoader.execute(request).image as? BitmapImage)?.bitmap
        ?: return null
    val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    return withContext(Dispatchers.Default) {
        // Row density that keeps the artwork undistorted on glyph cells
        // taller than wide: rows/columns must be (colStep/rowStep)/aspect.
        val rows = (ASCII_COLUMNS * (ASCII_COL_STEP / ASCII_ROW_STEP) / aspect)
            .roundToInt()
            .coerceAtLeast(8)
        val cropped = safeSource.centerCroppedToAspect(aspect)
        val sampled = cropped.downscaledTo(ASCII_COLUMNS, rows)
        val pixels = IntArray(ASCII_COLUMNS * rows)
        sampled.getPixels(pixels, 0, ASCII_COLUMNS, 0, 0, ASCII_COLUMNS, rows)

        // Pass 1 — raw Rec.709 luminance per cell + luminance histogram.
        val count = pixels.size
        val lum = FloatArray(count)
        val hist = IntArray(64)
        for (i in 0 until count) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            lum[i] = l
            hist[(l * 63f).toInt().coerceIn(0, 63)]++
        }

        // Pass 2 — dynamic tone mapping. A fixed contrast curve only works
        // for well-exposed art; dark or hazy covers collapse into empty
        // cells and the card reads as a black screen. Instead, auto-level
        // the artwork's own histogram (5th–98th percentile stretch) and
        // pick a gamma that re-centres the mean, so every artwork fills
        // the full ramp.
        var lo = 0f
        var hi = 1f
        val loTarget = (count * 0.05f).toInt()
        val hiTarget = (count * 0.98f).toInt()
        var acc = 0
        for (bin in 0..63) {
            acc += hist[bin]
            if (acc >= loTarget) {
                lo = bin / 63f
                break
            }
        }
        acc = 0
        for (bin in 0..63) {
            acc += hist[bin]
            if (acc >= hiTarget) {
                hi = bin / 63f
                break
            }
        }
        // Very flat art (near-solid covers): keep the full range so the
        // ordered dither still carries texture instead of one flat glyph.
        if (hi - lo < 0.20f) {
            lo = 0f
            hi = 1f
        }
        val range = (hi - lo).coerceAtLeast(0.05f)
        var sum = 0f
        val stretched = FloatArray(count)
        for (i in 0 until count) {
            val b = ((lum[i] - lo) / range).coerceIn(0f, 1f)
            stretched[i] = b
            sum += b
        }
        val mean = (sum / count).coerceIn(0.02f, 0.95f)
        val gamma = (ln(0.48f) / ln(mean)).coerceIn(0.6f, 1.8f)

        // Pass 3 — ramp selection + colour.
        val rampIndices = IntArray(count)
        val brightness = FloatArray(count)
        val colors = IntArray(count)
        val rampMax = ASCII_RAMP.length - 1
        val hsv = FloatArray(3)
        for (i in 0 until count) {
            val x = i % ASCII_COLUMNS
            val y = i / ASCII_COLUMNS
            val b = stretched[i].pow(gamma)
            brightness[i] = b
            // Ordered (Bayer 4x4) dithering before quantization — the
            // ascii-magic retro trick: smooth gradients cross-hatch into
            // glyph texture instead of banding into flat empty bands.
            val dither = ((ASCII_BAYER_4X4[y and 3][x and 3] + 0.5f) / 16f - 0.5f) *
                ASCII_DITHER
            rampIndices[i] = ((b + dither) * rampMax + 0.5f).toInt().coerceIn(0, rampMax)
            // Richer colour: the artwork's own hue, saturated for the dark
            // field, with the value riding the normalized brightness — but
            // with a floor, so dark covers still paint a clearly legible
            // picture. The brightest cells lean toward white for pop.
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val bl = px and 0xFF
            android.graphics.Color.RGBToHSV(r, g, bl, hsv)
            hsv[1] = (hsv[1] * 1.25f + 0.06f).coerceAtMost(1f)
            hsv[2] = (0.45f + 0.55f * b).coerceAtMost(1f)
            var color = android.graphics.Color.HSVToColor(hsv)
            if (b > 0.85f) {
                color = mixTowardWhite(color, (b - 0.85f) / 0.6f)
            }
            colors[i] = color
        }
        AsciiArtGrid(ASCII_COLUMNS, rows, rampIndices, brightness, colors)
    }
}

/**
 * Builds the full ASCII backdrop as one baked bitmap with real depth:
 *
 * 1. Base — the artwork itself, heavily blurred and dimmed to terminal
 *    darkness, so the card picks up the art's colour atmosphere instead of
 *    floating glyphs on a flat void (the "cheap poster" look).
 * 2. Glow — the glyph layer box-blurred into a phosphor bloom, drawn under
 *    the crisp glyphs so they read as luminous, not painted.
 * 3. Glyphs — the crisp ASCII conversion, same ramp/dither/tone-mapping
 *    conversion as before, on the wave field's monospace geometry.
 *
 * Everything is CPU-rendered into a single bitmap, so the live preview and
 * the software-canvas share capture stay pixel-identical.
 */
private suspend fun buildAsciiArtBackdrop(
    context: Context,
    imageUrl: String,
    aspect: Float
): Bitmap? {
    val cacheKey = "ascii:$imageUrl:$aspect"
    backdropBitmapCache.get(cacheKey)?.let { if (!it.isRecycled) return it }
    val grid = buildAsciiArtGrid(context, imageUrl, aspect) ?: return null
    // Separate (cache-served) decode for the blurred colour base.
    val request = ImageRequest.Builder(context)
        .data(MusicBrainzEnrichmentService.fixHttpUrl(imageUrl))
        .size(720, 720)
        .allowHardware(false)
        .build()
    val source = (context.imageLoader.execute(request).image as? BitmapImage)?.bitmap
    val palette = ShareTheme.ASCII.palette
    return withContext(Dispatchers.Default) {
        val w = ASCII_RENDER_WIDTH
        val h = (w / aspect).roundToInt().coerceAtLeast(64)
        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(composite)

        // 1 — blurred, dimmed artwork as the colour base.
        if (source != null) {
            val safeSource = if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            }
            val base = safeSource.centerCroppedToAspect(aspect)
                .downscaledTo(w, h)
                .boxBlurredCopy(ASCII_BASE_BLUR_RADIUS, ASCII_BLUR_ROUNDS)
                .dimmed(ASCII_BASE_DIM)
            canvas.drawBitmap(base, 0f, 0f, null)
        }

        // 2 — the soft phosphor halo behind the glyph art.
        val halo = Paint().apply { isAntiAlias = true }
        halo.shader = RadialGradient(
            w * 0.5f, h * 0.28f, w * 0.9f,
            intArrayOf(
                palette.glowTop.copy(alpha = 0.10f * palette.decorationAlpha).toArgb(),
                Color.Transparent.toArgb()
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), halo)

        // 3 — glyph layer: phosphor glow underneath, crisp glyphs on top.
        val glyphs = renderAsciiGlyphLayer(grid, w, h, palette)
        val glow = glyphs.boxBlurredCopy(ASCII_GLOW_BLUR_RADIUS, ASCII_BLUR_ROUNDS)
        val glowPaint = Paint().apply { alpha = (ASCII_GLOW_ALPHA * 255f).roundToInt() }
        canvas.drawBitmap(glow, 0f, 0f, glowPaint)
        canvas.drawBitmap(glyphs, 0f, 0f, null)
        backdropBitmapCache.put(cacheKey, composite)
        composite
    }
}

/**
 * Renders the precomputed [AsciiArtGrid] into a transparent bitmap with the
 * monospace geometry of the wave field ([ASCII_COL_STEP] advance,
 * [ASCII_ROW_STEP]-derived line height). The grid was built with matching row
 * density, so the artwork lands on the card undistorted — exactly like
 * ContentScale.Crop would place it.
 */
private fun renderAsciiGlyphLayer(
    grid: AsciiArtGrid,
    w: Int,
    h: Int,
    palette: ShareThemePalette
): Bitmap {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cellW = w.toFloat() / grid.columns
    val cellH = h.toFloat() / grid.rows
    val textSize = cellW / ASCII_COL_STEP
    val paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        this.textSize = textSize
    }
    val ramp = ASCII_RAMP
    // Baseline that visually centres each glyph in its cell.
    var y = cellH * 0.5f + textSize * 0.38f
    var row = 0
    while (row < grid.rows && y - textSize < h) {
        var x = cellW * 0.14f
        var column = 0
        while (column < grid.columns && x < w) {
            val index = row * grid.columns + column
            val rampIndex = grid.rampIndices[index]
            if (rampIndex > 0) {
                val b = grid.brightness[index]
                // High visibility floor: every cell keeps its colour so
                // dark covers still read as a picture, not a void.
                val alpha = ((0.42f + 0.58f * b) * palette.decorationAlpha)
                    .coerceIn(0f, 1f)
                paint.color = (grid.colors[index] and 0x00FFFFFF) or
                    ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)
                canvas.drawText(ramp[rampIndex].toString(), x, y, paint)
            }
            x += cellW
            column++
        }
        y += cellH
        row++
    }
    return bitmap
}

/** Multiplies every pixel's RGB by [factor]; alpha is kept. */
private fun Bitmap.dimmed(factor: Float): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = ((c shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
        pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/** Gaussian-approximating box blur (via [blurBoxPass]) as a new bitmap. */
private fun Bitmap.boxBlurredCopy(radius: Int, rounds: Int): Bitmap {
    var pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(rounds) {
        pixels = blurBoxPass(pixels, width, height, radius, horizontal = true)
        pixels = blurBoxPass(pixels, width, height, radius, horizontal = false)
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/**
 * The liquid-glass pools, kept as the fluted glass loading/missing-art state:
 * iridescent blobs and sheen streaks on the smoked pane.
 */
private fun DrawScope.drawGlassFluidPools(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    val colors = listOf(palette.glowTop, palette.glowBottom, palette.accent)

    // Vertical fluted glass ribs (cylindrical architectural glass pane texture)
    val stripeCount = 36
    val stripeW = w / stripeCount
    for (i in 0 until stripeCount) {
        val x = i * stripeW
        // Soft cylindrical shadow seam on left
        drawLine(
            color = Color.Black.copy(alpha = 0.16f * palette.decorationAlpha),
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 1.dp.toPx()
        )
        // Specular highlight seam on right
        drawLine(
            color = Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
            start = Offset(x + stripeW, 0f),
            end = Offset(x + stripeW, h),
            strokeWidth = 0.8.dp.toPx()
        )
    }

    // Iridescent fluid pools: elongated, tilted radial blobs pooling
    // like molten glass inside a dark smoked pane.
    GlassFluidBlobs.forEach { blob ->
        val color = colors[blob.colorIndex % colors.size]
        val center = Offset(blob.cx * w, blob.cy * h)
        val radius = maxOf(blob.rx, blob.ry) * w
        rotate(degrees = blob.rot, pivot = center) {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = blob.alpha * palette.decorationAlpha),
                        color.copy(alpha = blob.alpha * 0.45f * palette.decorationAlpha),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                topLeft = Offset(center.x - blob.rx * w, center.y - blob.ry * w),
                size = Size(blob.rx * 2 * w, blob.ry * 2 * w)
            )
        }
    }
    // Diagonal sheen streaks — the specular highlights that sell
    // the pane-of-glass read.
    rotate(degrees = 26f, pivot = Offset(w * 0.5f, h * 0.5f)) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
                    Color.Transparent
                )
            ),
            topLeft = Offset(w * 0.16f, -h * 0.45f),
            size = Size(w * 0.10f, h * 1.9f)
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f * palette.decorationAlpha),
                    Color.Transparent
                )
            ),
            topLeft = Offset(w * 0.62f, -h * 0.45f),
            size = Size(w * 0.045f, h * 1.9f)
        )
    }
    // Gloss along the top edge, like light catching the rim.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.10f * palette.decorationAlpha),
                Color.Transparent
            ),
            startY = 0f,
            endY = h * 0.12f
        )
    )
}

/**
 * The deterministic glitch static canvas fallback: scanlines, chromatic
 * edge fringing, RGB-split slices, and noise blocks on void black. Used when
 * artwork is missing or still decoding.
 */
private fun DrawScope.drawGlitchStaticFallback(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    // CRT scanlines sweeping the whole frame.
    val lineStep = h * 0.014f
    var scanY = 0f
    while (scanY < h) {
        drawRect(
            color = Color.Black.copy(alpha = 0.14f * palette.decorationAlpha),
            topLeft = Offset(0f, scanY),
            size = Size(w, lineStep * 0.45f)
        )
        scanY += lineStep
    }
    // Chromatic fringe bleeding in from the edges.
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(palette.glowTop.copy(alpha = 0.10f * palette.decorationAlpha), Color.Transparent)
        ),
        size = Size(w * 0.09f, h)
    )
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, palette.glowBottom.copy(alpha = 0.10f * palette.decorationAlpha)),
            startX = w * 0.91f,
            endX = w
        ),
        topLeft = Offset(w * 0.91f, 0f),
        size = Size(w * 0.09f, h)
    )
    // RGB-split slices: cyan and magenta ghosts flanking a white
    // core, like a datamoshed frame torn mid-render.
    GlitchSlices.forEach { slice ->
        val sx = slice.x * w
        val sy = slice.y * h
        val sw = slice.w * w
        val sh = slice.h * h
        drawRect(
            color = palette.glowTop.copy(alpha = slice.alpha * 0.5f * palette.decorationAlpha),
            topLeft = Offset(sx - sw * 0.035f, sy),
            size = Size(sw, sh)
        )
        drawRect(
            color = palette.glowBottom.copy(alpha = slice.alpha * 0.5f * palette.decorationAlpha),
            topLeft = Offset(sx + sw * 0.035f, sy),
            size = Size(sw, sh)
        )
        drawRect(
            color = Color.White.copy(alpha = slice.alpha * 0.30f * palette.decorationAlpha),
            topLeft = Offset(sx, sy),
            size = Size(sw, sh)
        )
    }
    // Scattered noise blocks — deterministic seeded layout so the
    // preview and the share capture show the same composition.
    val rng = Random(GLITCH_SEED)
    repeat(16) {
        val bw = w * (0.01f + rng.nextFloat() * 0.15f)
        val bh = h * (0.004f + rng.nextFloat() * 0.011f)
        val bx = w * rng.nextFloat()
        val by = h * rng.nextFloat()
        val color = when (rng.nextInt(3)) {
            0 -> palette.glowTop
            1 -> palette.glowBottom
            else -> Color.White
        }
        if (rng.nextBoolean()) {
            drawRect(
                color = color.copy(alpha = 0.10f * palette.decorationAlpha),
                topLeft = Offset(bx, by),
                size = Size(bw, bh)
            )
        } else {
            drawRect(
                color = color.copy(alpha = 0.35f * palette.decorationAlpha),
                topLeft = Offset(bx, by),
                size = Size(bw, bh),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
    // One bright tear band cutting across the upper third.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.08f * palette.decorationAlpha),
                Color.Transparent
            ),
            startY = h * 0.24f,
            endY = h * 0.30f
        ),
        topLeft = Offset(0f, h * 0.24f),
        size = Size(w, h * 0.06f)
    )
}

/** ContentScale.Crop equivalent: centre-crops the bitmap to [aspect] (w/h). */
private fun Bitmap.centerCroppedToAspect(aspect: Float): Bitmap {
    val srcAspect = width.toFloat() / height
    val cropW: Int
    val cropH: Int
    if (srcAspect > aspect) {
        cropH = height
        cropW = (height * aspect).roundToInt().coerceAtMost(width)
    } else {
        cropW = width
        cropH = (width / aspect).roundToInt().coerceAtMost(height)
    }
    val left = (width - cropW) / 2
    val top = (height - cropH) / 2
    return Bitmap.createBitmap(this, left, top, cropW.coerceAtLeast(1), cropH.coerceAtLeast(1))
}

/**
 * High-quality area-average downscale to one pixel per glyph cell: successive
 * halvings keep the cells faithful to the artwork — a single bilinear step
 * this deep would alias most of the detail away.
 */
private fun Bitmap.downscaledTo(targetW: Int, targetH: Int): Bitmap {
    var current = this
    while (current.width > targetW * 2 && current.height > targetH * 2) {
        current = Bitmap.createScaledBitmap(
            current,
            (current.width / 2).coerceAtLeast(targetW),
            (current.height / 2).coerceAtLeast(targetH),
            true
        )
    }
    return Bitmap.createScaledBitmap(current, targetW, targetH, true)
}

/** Linear blend of [color] toward white by [fraction] (0..1). */
private fun mixTowardWhite(color: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val nr = (r + (255 - r) * f).toInt().coerceIn(0, 255)
    val ng = (g + (255 - g) * f).toInt().coerceIn(0, 255)
    val nb = (b + (255 - b) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
}

/**
 * Deterministic wave-shaded monospace glyph field: the [ASCII_FIELD] look,
 * and the no-artwork fallback for [ASCII_ARTWORK]. Brightness is shaped by
 * layered sine waves so the field reads as flowing terrain rather than
 * uniform static, and being a pure function of position keeps the live
 * preview and the share capture pixel-identical.
 */
private fun DrawScope.drawAsciiWaveField(palette: ShareThemePalette) {
    val w = size.width
    val h = size.height
    // Soft phosphor halo behind the glyph field.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.glowTop.copy(alpha = 0.12f * palette.decorationAlpha),
                Color.Transparent
            ),
            center = Offset(w * 0.5f, h * 0.30f),
            radius = w * 0.85f
        ),
        radius = w * 0.85f,
        center = Offset(w * 0.5f, h * 0.30f)
    )
    val paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = w * 0.030f
    }
    val ramp = " .,:;=+*#%@"
    val cell = paint.textSize
    drawIntoCanvas { canvas ->
        var y = cell * 1.15f
        while (y < h) {
            var x = cell * 0.30f
            while (x < w) {
                val nx = x / w
                val ny = y / h
                val wave = sin(nx * 9.4f + sin(ny * 7.3f) * 2.1f) *
                    cos(ny * 6.1f - sin(nx * 4.2f) * 1.7f)
                val b = ((wave + 1f) * 0.5f).coerceIn(0f, 1f)
                val glyph = ramp[(b * (ramp.length - 1)).toInt()]
                if (glyph != ' ') {
                    paint.color = if (b > 0.72f) {
                        palette.accent
                            .copy(alpha = (0.20f + (b - 0.72f) * 1.1f).coerceAtMost(0.55f) * palette.decorationAlpha)
                            .toArgb()
                    } else {
                        Color.White
                            .copy(alpha = (0.05f + b * 0.13f) * palette.decorationAlpha)
                            .toArgb()
                    }
                    canvas.nativeCanvas.drawText(glyph.toString(), x, y, paint)
                }
                x += cell * ASCII_COL_STEP
            }
            y += cell * ASCII_ROW_STEP
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

private data class GlassFluidBlob(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rot: Float,
    val colorIndex: Int,
    val alpha: Float
)

private val GlassFluidBlobs = listOf(
    GlassFluidBlob(0.28f, 0.14f, 0.55f, 0.30f, -24f, 0, 0.34f),
    GlassFluidBlob(0.86f, 0.32f, 0.45f, 0.26f, 18f, 1, 0.30f),
    GlassFluidBlob(0.10f, 0.54f, 0.50f, 0.24f, 30f, 2, 0.26f),
    GlassFluidBlob(0.70f, 0.80f, 0.60f, 0.28f, -14f, 1, 0.28f),
    GlassFluidBlob(0.38f, 0.96f, 0.45f, 0.22f, 10f, 0, 0.24f),
    GlassFluidBlob(0.52f, 0.42f, 0.35f, 0.18f, -32f, 1, 0.16f)
)

private data class GlitchSlice(val x: Float, val y: Float, val w: Float, val h: Float, val alpha: Float)

private val GlitchSlices = listOf(
    GlitchSlice(0.02f, 0.12f, 0.96f, 0.016f, 0.35f),
    GlitchSlice(0.30f, 0.19f, 0.55f, 0.009f, 0.28f),
    GlitchSlice(0.10f, 0.34f, 0.80f, 0.022f, 0.30f),
    GlitchSlice(0.45f, 0.47f, 0.42f, 0.008f, 0.26f),
    GlitchSlice(0.05f, 0.61f, 0.68f, 0.014f, 0.32f),
    GlitchSlice(0.38f, 0.73f, 0.52f, 0.018f, 0.28f),
    GlitchSlice(0.14f, 0.86f, 0.86f, 0.011f, 0.30f)
)

private const val GLITCH_SEED = 0x1E5A

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
