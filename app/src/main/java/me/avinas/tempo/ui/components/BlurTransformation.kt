package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation
import me.avinas.tempo.utils.scaledWithinMaxEdge

/**
 * A Coil [Transformation] that applies a blur by downscaling the source bitmap
 * and then upscaling it back with bilinear filtering.
 *
 * This is intentionally a pure software implementation (no [android.graphics.RenderEffect])
 * so the resulting bitmap renders identically on both hardware-accelerated previews and
 * software (bitmap-backed) canvases — the latter is what share-card capture uses
 * (`View.draw(Canvas)` on a software Canvas silently drops `Modifier.blur`'s RenderEffect).
 *
 * The heavy dark overlays used on top of blurred share-card backgrounds hide the small
 * visual difference between this downscale-upscale blur and a true gaussian blur.
 *
 * @param radiusPx Blur radius in pixels. Larger values produce a stronger blur.
 *                  Values <= 0 return the input untouched (unless it exceeds the bitmap ceiling,
 *                  in which case it is downscaled to it — the output is never larger than the
 *                  input, and never above [me.avinas.tempo.utils.MAX_SAFE_BITMAP_EDGE_PX]).
 */
class BlurTransformation(private val radiusPx: Float) : Transformation() {

    override val cacheKey: String = "blur(${radiusPx.toInt()})"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // This transformation allocates its output at the *input's* dimensions, so an oversized
        // input would come straight back out as an oversized bitmap (and then be drawn by
        // BitmapPainter: "Canvas: trying to draw too large(Nbytes) bitmap."). Clamp the source
        // first, on every path: for inputs already inside the ceiling this is the input itself,
        // so the visual result is unchanged.
        val source = input.scaledWithinMaxEdge()
        if (radiusPx <= 0f || source.width == 0 || source.height == 0) return source

        if (source !== input) {
            val result = blur(source)
            source.recycle()
            return result
        }
        return blur(source)
    }

    private fun blur(input: Bitmap): Bitmap {
        val downscale = (radiusPx / 6f).coerceIn(2f, 32f)
        val smallW = (input.width / downscale).toInt().coerceAtLeast(1)
        val smallH = (input.height / downscale).toInt().coerceAtLeast(1)

        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Downscale the source into a tiny intermediate bitmap (bilinear smoothing).
        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(
            input,
            Rect(0, 0, input.width, input.height),
            Rect(0, 0, smallW, smallH),
            filterPaint
        )

        // Draw the tiny bitmap back up to a NEW bitmap at the original size —
        // the bilinear upscale produces the soft, blurred look. Never mutate
        // or recycle `input`: Coil may pool/share it, so touching it corrupts
        // other requests and crashes on draw with "trying to use a recycled
        // bitmap" (DisplayListCanvas.throwIfCannotDraw via BitmapPainter).
        // Coil owns the input's lifecycle; only `small` is ours to recycle.
        val target = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Canvas(target).drawBitmap(
            small,
            Rect(0, 0, smallW, smallH),
            Rect(0, 0, target.width, target.height),
            filterPaint
        )

        small.recycle()
        return target
    }
}
