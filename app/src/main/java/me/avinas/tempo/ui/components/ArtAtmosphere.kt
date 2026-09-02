package me.avinas.tempo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.ui.theme.TempoDarkBackground
import me.avinas.tempo.ui.theme.rememberReducedMotion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Full-screen blurred backdrop layer rendered from track artwork.
 *
 * Paints artwork, dusk gradient, color tint, vignette, and grain in a single
 * Canvas draw pass to avoid multiple compositing layers. Blur is precomputed
 * on a 64x64 bitmap on a background dispatcher and scaled with bilinear filtering.
 */

/** Initial overscale fraction for the incoming artwork transition. */
private const val ARRIVAL_ZOOM = 0.05f

/** Fade-in transition duration in milliseconds. */
private const val ARRIVAL_DURATION_MS = 1100

/** Fade-out duration when artwork is cleared. */
private const val DISSOLVE_DURATION_MS = 450

@Composable
fun ArtAtmosphereLayer(
    artUrl: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()

    // Keep previous artwork visible during decoding to avoid blank frames
    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    val reveal = remember { Animatable(1f) }

    LaunchedEffect(artUrl) {
        if (artUrl.isNullOrBlank()) {
            if (overlay == null && base == null) return@LaunchedEffect
            overlay = overlay ?: base
            base = null
            reveal.animateTo(
                0f,
                tween(if (reducedMotion) 0 else DISSOLVE_DURATION_MS, easing = FastOutLinearInEasing),
            )
            overlay = null
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(artUrl)
                .size(64, 64)
                .build(),
        )
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@LaunchedEffect
        val software = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
            bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val washed = withContext(Dispatchers.Default) {
            gaussianWash(software).asImageBitmap()
        }
        // Crossfade to new artwork or update current transition in flight
        if (overlay != null && base == null) {
            overlay = washed
        } else {
            base = overlay ?: base
            overlay = washed
            reveal.snapTo(0f)
        }
        if (reducedMotion) {
            reveal.snapTo(1f)
        } else {
            reveal.animateTo(1f, tween(ARRIVAL_DURATION_MS, easing = FastOutSlowInEasing))
        }
        base = null
    }

    val grainPaint = remember { GrainNoise.paint(alpha = 0.028f) }
    val saturationFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.12f) })
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val hasArt = base != null || overlay != null

            if (hasArt) {
                val progress = reveal.value
                val roomAlpha = if (base == null) progress else 1f

                base?.let { art ->
                    drawWashedArt(art, saturationFilter, alpha = 1f, extraZoom = 0f)
                }
                overlay?.let { art ->
                    drawWashedArt(
                        art,
                        saturationFilter,
                        alpha = progress,
                        extraZoom = ARRIVAL_ZOOM * (1f - progress),
                    )
                }

                // Vertical gradient scrim for content readability
                drawRect(
                    alpha = roomAlpha,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            TempoDarkBackground.copy(alpha = 0.80f),
                            TempoDarkBackground.copy(alpha = 0.55f),
                            TempoDarkBackground.copy(alpha = 0.74f),
                            TempoDarkBackground.copy(alpha = 0.92f),
                        ),
                        startY = 0f,
                        endY = h,
                    )
                )

                // Dominant color tint wash
                drawRect(
                    alpha = roomAlpha,
                    brush = Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(w / 2f, h * 0.32f),
                        radius = max(w, h),
                    )
                )

                // Edge vignette
                drawRect(
                    alpha = roomAlpha,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            TempoDarkBackground.copy(alpha = 0.55f),
                        ),
                        center = Offset(w / 2f, h / 2f),
                        radius = max(w, h) * 0.85f,
                    )
                )

                // Monochrome film grain
                val grainAlpha = grainPaint.alpha
                grainPaint.alpha = (grainAlpha * roomAlpha).roundToInt().coerceIn(0, 255)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPaint(grainPaint)
                }
                grainPaint.alpha = grainAlpha
            }
        }
    }
}

/**
 * Draws bitmap centered and cropped across the canvas with an overscale buffer.
 */
private fun DrawScope.drawWashedArt(
    image: ImageBitmap,
    saturationFilter: ColorFilter?,
    alpha: Float,
    extraZoom: Float,
) {
    val w = size.width
    val h = size.height
    val scale = max(w / image.width, h / image.height) * 1.25f * (1f + extraZoom)
    val dstW = image.width * scale
    val dstH = image.height * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            ((w - dstW) / 2f).roundToInt(),
            ((h - dstH) / 2f).roundToInt(),
        ),
        dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
        alpha = alpha,
        colorFilter = saturationFilter,
    )
}

/**
 * Approximates a Gaussian blur using two passes of a separable box blur.
 */
private fun gaussianWash(source: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = source.width
    val h = source.height
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val radius = max(2, min(w, h) / 6)
    repeat(2) {
        boxBlurHorizontal(pixels, w, h, radius)
        boxBlurVertical(pixels, w, h, radius)
    }
    return android.graphics.Bitmap.createBitmap(
        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
    )
}

private fun boxBlurHorizontal(p: IntArray, w: Int, h: Int, r: Int) {
    val out = IntArray(p.size)
    val window = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val px = p[row + i.coerceIn(0, w - 1)]
            sr += (px shr 16) and 0xFF
            sg += (px shr 8) and 0xFF
            sb += px and 0xFF
        }
        for (x in 0 until w) {
            out[row + x] =
                (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val add = p[row + (x + r + 1).coerceAtMost(w - 1)]
            val sub = p[row + (x - r).coerceAtLeast(0)]
            sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            sb += (add and 0xFF) - (sub and 0xFF)
        }
    }
    System.arraycopy(out, 0, p, 0, p.size)
}

private fun boxBlurVertical(p: IntArray, w: Int, h: Int, r: Int) {
    val out = IntArray(p.size)
    val window = 2 * r + 1
    for (x in 0 until w) {
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val px = p[i.coerceIn(0, h - 1) * w + x]
            sr += (px shr 16) and 0xFF
            sg += (px shr 8) and 0xFF
            sb += px and 0xFF
        }
        for (y in 0 until h) {
            out[y * w + x] =
                (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val add = p[(y + r + 1).coerceAtMost(h - 1) * w + x]
            val sub = p[(y - r).coerceAtLeast(0) * w + x]
            sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            sb += (add and 0xFF) - (sub and 0xFF)
        }
    }
    System.arraycopy(out, 0, p, 0, p.size)
}

/** Fixed-seed monochrome noise tile, generated once per process. */
private object GrainNoise {
    fun paint(alpha: Float): android.graphics.Paint {
        val size = 128
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val pixels = IntArray(size * size)
        val random = Random(seed = 7)
        for (index in pixels.indices) {
            pixels[index] = (random.nextInt(256) shl 24) or 0x00E6EAEA
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return android.graphics.Paint().apply {
            this.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            shader = android.graphics.BitmapShader(
                bitmap,
                android.graphics.Shader.TileMode.REPEAT,
                android.graphics.Shader.TileMode.REPEAT,
            )
        }
    }
}
