package me.avinas.tempo.utils

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Longest-edge ceiling, in pixels, for any bitmap this app hands to a Compose painter or a
 * hardware-accelerated Canvas.
 *
 * A hardware Canvas rejects `drawBitmap` once the bitmap's byte count passes its own
 * `MAX_BITMAP_SIZE` (100 MiB), which surfaces as
 * `RuntimeException: Canvas: trying to draw too large(Nbytes) bitmap.` A 4096x4096 ARGB_8888
 * bitmap is 64 MiB — the largest square that stays inside that budget — while still covering
 * every size this app actually draws at (the biggest on-screen request is a 320.dp share card
 * thumbnail, ~1280 px on a 640 dpi device).
 *
 * Every decode, transformation and procedural render clamps to this one value so an oversized
 * source (a large shipped asset, a user-picked photo, a remote original) can never reach a
 * painter.
 */
const val MAX_SAFE_BITMAP_EDGE_PX = 4096

/** Clamps a requested bitmap edge into `1..`[MAX_SAFE_BITMAP_EDGE_PX]. */
fun safeBitmapEdge(edge: Int): Int = edge.coerceIn(1, MAX_SAFE_BITMAP_EDGE_PX)

/**
 * Returns this bitmap downscaled so neither edge exceeds [maxEdge], or the receiver itself when
 * it is already within the ceiling — so callers that are already bounded pay nothing and keep
 * their exact pixels.
 *
 * The receiver is never recycled: ownership stays with the caller (Coil pools and shares decoded
 * bitmaps). A returned bitmap that is not the receiver is a fresh allocation owned by the caller.
 */
fun Bitmap.scaledWithinMaxEdge(maxEdge: Int = MAX_SAFE_BITMAP_EDGE_PX): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxEdge || longestEdge <= 0) return this
    val ratio = maxEdge.toFloat() / longestEdge
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(1),
        (height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}
