package me.avinas.tempo.ui.clay

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Soft clay pipeline, faithful to clay.css: one OUTSET shadow (soft, biased
 * down-right) + two INSET rims (light top-left, dark bottom-right) over a
 * FLAT face. No hard offset slabs, no ground ellipses, no gloss dots — those
 * read as stickers, not clay. Softness comes from layered translucent rings
 * (fake blur, fully predictable) and bounds-mapped gradients clipped inside
 * each silhouette.
 */

// Outset rings: stroke pad + alpha. Drawn biased down-right like the outset.
private val HaloRings = listOf(5f to 0.12f, 10f to 0.06f, 15f to 0.03f)
private const val HaloDx = 2.5f
private const val HaloDy = 4f

fun DrawScope.clayHalo(path: Path, extra: Float = 0f) {
    translate(left = HaloDx, top = HaloDy) {
        HaloRings.forEach { (pad, alpha) ->
            drawPath(
                path,
                Color.Black.copy(alpha = alpha),
                style = Stroke(
                    width = pad + extra,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

/** Inset modeling: light pools top-left, depth pools bottom-right, face untouched. */
fun DrawScope.clayInner(path: Path, light: Float = 0.22f, dark: Float = 0.20f) {
    val b = path.getBounds()
    if (b.width <= 0f || b.height <= 0f) return
    val origin = Offset(b.left, b.top)
    val size = Size(b.width, b.height)
    clipPath(path) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = light), Color.Transparent),
                start = origin,
                end = origin + Offset(b.width, b.height)
            ),
            topLeft = origin,
            size = size
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = dark)),
                start = origin,
                end = origin + Offset(b.width, b.height)
            ),
            topLeft = origin,
            size = size
        )
    }
}

/**
 * One molded lump: soft outset + optional inflated edge + flat face +
 * inset modeling. [puff] rounds the silhouette like pressed clay.
 */
fun DrawScope.clayShape(path: Path, base: Color, puff: Float = 0f) {
    if (puff > 0f) {
        val fat = Stroke(width = puff * 2, join = StrokeJoin.Round, cap = StrokeCap.Round)
        clayHalo(path, puff * 2)
        drawPath(path, base, style = fat)
    } else {
        clayHalo(path)
    }
    drawPath(path, base)
    clayInner(path)
}

/** Thin molded piece (brackets, checks, shackles, stems): lift, no insets. */
fun DrawScope.clayLine(path: Path, base: Color, w: Float) {
    clayHalo(path, w)
    drawPath(
        path,
        base,
        style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * One object from overlapping same-color parts (cloud puffs): halo + faces
 * per part, but a SINGLE inset pass over the union — otherwise the overlaps
 * print dark lens bands and the lump falls apart.
 */
fun DrawScope.clayGroup(parts: List<Path>, base: Color) {
    val union = Path().apply { parts.forEach { addPath(it) } }
    parts.forEach { clayHalo(it) }
    parts.forEach { drawPath(it, base) }
    clayInner(union)
}

/** Oval path without hand-building one. */
fun clayOval(center: Offset, r: Float): Path =
    Path().apply { addOval(Rect(center - Offset(r, r), Size(r * 2, r * 2))) }

/** Rounded-rect path without hand-building one. */
fun clayRoundRect(tl: Offset, size: Size, r: Float): Path =
    Path().apply { addRoundRect(RoundRect(Rect(tl, size), r, r)) }

/** Rounded-rect lump without hand-building a path. */
fun DrawScope.clayRect(tl: Offset, size: Size, r: Float, base: Color, puff: Float = 0f) {
    clayShape(
        Path().apply { addRoundRect(RoundRect(Rect(tl, size), r, r)) },
        base,
        puff
    )
}

/** Round lump without hand-building a path. */
fun DrawScope.clayCircle(center: Offset, r: Float, base: Color, puff: Float = 0f) {
    clayShape(
        Path().apply { addOval(Rect(center - Offset(r, r), Size(r * 2, r * 2))) },
        base,
        puff
    )
}
