package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation

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
 *                  Values <= 0 return the input untouched.
 */
class BlurTransformation(private val radiusPx: Float) : Transformation() {

    override val cacheKey: String = "blur(${radiusPx.toInt()})"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (radiusPx <= 0f || input.width == 0 || input.height == 0) return input

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

        // Draw the tiny bitmap back up to the original size — the bilinear upscale
        // produces the soft, blurred look. Reuse the input when it is mutable so we
        // don't allocate an extra full-size bitmap.
        val target = if (input.isMutable) input else input.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(target).drawBitmap(
            small,
            Rect(0, 0, smallW, smallH),
            Rect(0, 0, target.width, target.height),
            filterPaint
        )

        small.recycle()
        if (target !== input) {
            input.recycle()
        }
        return target
    }
}
