package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import me.avinas.tempo.ui.clay.ClayTokens
import me.avinas.tempo.ui.clay.clayLine
import me.avinas.tempo.ui.clay.clayOval
import me.avinas.tempo.ui.clay.clayRoundRect
import me.avinas.tempo.ui.clay.clayShape
import me.avinas.tempo.ui.clay.mix

/**
 * Bespoke clay objects for onboarding — one per copy slot.
 *
 * Clay discipline (read ClayDraw before touching this file):
 * - Every clayShape/clayLine call paints an OUTSET halo. A halo drawn on top
 *   of another shape's face prints a dark seam loop and the icon reads broken.
 * - So: ONE clayShape per connected same-color region, with all overlapping
 *   parts merged into a single Path. Interior halo rings are covered by the
 *   fill drawn after them; only the true outer silhouette keeps its shadow,
 *   and the modeling pass runs once over the whole lump.
 * - Interior details are PAINT: flat fills, no halo, no second modeling pass.
 *   A painted edge is honest; a shadowed edge on a flat face is a sticker.
 * - Only air-separated objects (bell arcs, code strokes, app tiles) each get
 *   their own lump call — their halos fall on background, never on a face.
 *
 * Grid: 100-unit space, center 50,50, masses inside 16..84. Nothing raised is
 * thinner than 6.5 units (~2.3dp at a 36dp badge).
 */
enum class ClayKind {
    Music, Bell, Chart, Apps, Lock, Bolt, Shield, Phone, CloudOff, NotifCard, Code
}

private fun line(a: Offset, b: Offset): Path =
    Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
    }

private fun ellipse(center: Offset, rx: Float, ry: Float): Path =
    Path().apply {
        addOval(Rect(Offset(center.x - rx, center.y - ry), Size(rx * 2f, ry * 2f)))
    }

/** Painted stroke: flat fill, no halo — for details sitting on a clay face. */
private fun DrawScope.paintStroke(path: Path, color: Color, w: Float) {
    drawPath(
        path,
        color,
        style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.music(base: Color) {
    // One lump: oval heads + filled stems + beam in a single path.
    val lump = Path().apply {
        addPath(ellipse(Offset(31f, 71f), 13f, 10f))
        addPath(ellipse(Offset(69f, 64f), 13f, 10f))
        addPath(clayRoundRect(Offset(37f, 29f), Size(8f, 40f), 4f))
        addPath(clayRoundRect(Offset(75f, 22f), Size(8f, 40f), 4f))
        addPath(
            Path().apply {
                moveTo(37f, 24f)
                lineTo(37f, 34f)
                lineTo(83f, 27f)
                lineTo(83f, 17f)
                close()
            }
        )
    }
    clayShape(lump, base, puff = 4f)
}

private fun DrawScope.bell(base: Color) {
    // One lump: knob + flared handbell dome + lip merged; arcs ring free
    // in air, pulled clear of the dome so their halos never kiss its face.
    val lump = Path().apply {
        moveTo(26f, 68f)
        lineTo(31f, 52f)
        arcTo(Rect(Offset(31f, 33f), Size(38f, 38f)), 180f, 180f, forceMoveTo = false)
        lineTo(69f, 52f)
        lineTo(74f, 68f)
        close()
        addPath(clayOval(Offset(50f, 28f), 7.5f))
        addPath(clayRoundRect(Offset(20f, 64f), Size(60f, 13f), 6.5f))
    }
    clayShape(lump, base, puff = 4f)
    drawCircle(mix(base, Color.Black, 0.55f), 6.5f, Offset(50f, 81.5f))
    val left = Path().apply {
        moveTo(23f, 40f)
        quadraticTo(14f, 50f, 23f, 60f)
    }
    val right = Path().apply {
        moveTo(77f, 40f)
        quadraticTo(86f, 50f, 77f, 60f)
    }
    clayLine(left, base, 7f)
    clayLine(right, base, 7f)
}

private fun DrawScope.chart(base: Color) {
    // One lump: bars + ground bar; the value dot is paint.
    val lump = Path().apply {
        addPath(clayRoundRect(Offset(17f, 50f), Size(15f, 26f), 7.5f))
        addPath(clayRoundRect(Offset(42f, 34f), Size(15f, 42f), 7.5f))
        addPath(clayRoundRect(Offset(67f, 18f), Size(15f, 58f), 7.5f))
        addPath(clayRoundRect(Offset(16f, 74f), Size(68f, 8f), 4f))
    }
    clayShape(lump, base, puff = 2f)
    drawCircle(ClayTokens.Cream, 4.5f, Offset(74.5f, 30f))
}

private fun DrawScope.apps(base: Color) {
    // Four separate tiles in air — no overlaps, so no seams; each its own lump.
    clayShape(clayRoundRect(Offset(18f, 18f), Size(28f, 28f), 10f), base, puff = 3f)
    clayShape(clayRoundRect(Offset(54f, 18f), Size(28f, 28f), 10f), base, puff = 3f)
    clayShape(clayRoundRect(Offset(18f, 54f), Size(28f, 28f), 10f), base, puff = 3f)
    clayShape(clayRoundRect(Offset(54f, 54f), Size(28f, 28f), 10f), ClayTokens.Cream, puff = 3f)
}

private fun DrawScope.lock_(base: Color) {
    // One lump: shackle ring (even-odd) fused into the body. Legs used to be
    // a stroked line whose halo printed seams across the body face.
    val lump = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(27f, 52f)
        lineTo(27f, 40f)
        cubicTo(27f, 24f, 36f, 17f, 50f, 17f)
        cubicTo(64f, 17f, 73f, 24f, 73f, 40f)
        lineTo(73f, 52f)
        lineTo(27f, 52f)
        close()
        moveTo(39f, 52f)
        lineTo(39f, 40f)
        cubicTo(39f, 30f, 43f, 29f, 50f, 29f)
        cubicTo(57f, 29f, 61f, 30f, 61f, 40f)
        lineTo(61f, 52f)
        close()
        addPath(clayRoundRect(Offset(24f, 48f), Size(52f, 36f), 11f))
    }
    clayShape(lump, base, puff = 3.5f)
    val deep = mix(base, Color.Black, 0.55f)
    drawCircle(deep, 6f, Offset(50f, 61f))
    drawLine(deep, Offset(50f, 63f), Offset(50f, 73f), 7f, StrokeCap.Round)
}

private fun DrawScope.bolt(base: Color) {
    // Already a single silhouette — untouched.
    val bolt = Path().apply {
        moveTo(57f, 18f)
        lineTo(32f, 54f)
        lineTo(47f, 54f)
        lineTo(43f, 82f)
        lineTo(68f, 46f)
        lineTo(53f, 46f)
        close()
    }
    clayShape(bolt, base, puff = 5f)
}

private fun DrawScope.shield(base: Color) {
    // Enameled badge: one shell lump + flat paint layers (dark rim, light
    // field, ink check). Paint has no halo, so no seams — richness comes
    // from the layering. The check is ink, not cream: cream on mint is ~1.8:1
    // and washes out at hero size; ink is ~4:1 and matches the keyhole/clapper
    // stamped-detail language.
    val body = Path().apply {
        moveTo(20f, 18f)
        lineTo(80f, 18f)
        lineTo(75f, 52f)
        cubicTo(75f, 69f, 63f, 81f, 50f, 86f)
        cubicTo(37f, 81f, 25f, 69f, 25f, 52f)
        close()
    }
    clayShape(body, base, puff = 5f)
    val rim = Path().apply {
        moveTo(25f, 23f)
        lineTo(75f, 23f)
        lineTo(71f, 51f)
        cubicTo(71f, 66f, 61f, 78f, 50f, 82f)
        cubicTo(39f, 78f, 29f, 66f, 29f, 51f)
        close()
    }
    drawPath(rim, mix(base, Color.Black, 0.18f))
    val field = Path().apply {
        moveTo(31f, 29f)
        lineTo(69f, 29f)
        lineTo(66f, 50f)
        cubicTo(66f, 63f, 59f, 72f, 50f, 75f)
        cubicTo(41f, 72f, 34f, 63f, 34f, 50f)
        close()
    }
    drawPath(field, mix(base, Color.White, 0.15f))
    val check = Path().apply {
        moveTo(39f, 51f)
        lineTo(47f, 59f)
        lineTo(61f, 38f)
    }
    paintStroke(check, mix(base, Color.Black, 0.55f), 12f)
}

private fun DrawScope.phone(base: Color) {
    // Already correct: one lump + flat paint. Untouched.
    clayShape(clayRoundRect(Offset(30f, 10f), Size(40f, 76f), 15f), base, puff = 3.5f)
    drawRoundRect(
        color = mix(base, Color.Black, 0.38f),
        topLeft = Offset(36f, 24f),
        size = Size(28f, 42f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        color = ClayTokens.Cream,
        topLeft = Offset(39.5f, 49f),
        size = Size(5f, 12f),
        cornerRadius = CornerRadius(2.5f, 2.5f)
    )
    drawRoundRect(
        color = ClayTokens.Cream,
        topLeft = Offset(47.5f, 42f),
        size = Size(5f, 19f),
        cornerRadius = CornerRadius(2.5f, 2.5f)
    )
    drawRoundRect(
        color = ClayTokens.Cream,
        topLeft = Offset(55.5f, 35f),
        size = Size(5f, 26f),
        cornerRadius = CornerRadius(2.5f, 2.5f)
    )
    val deep = mix(base, Color.Black, 0.55f)
    drawLine(deep, Offset(45f, 16.5f), Offset(55f, 16.5f), 4.5f, StrokeCap.Round)
    drawCircle(deep, 3.5f, Offset(50f, 78f))
}

private fun DrawScope.cloudOff(base: Color) {
    // One lump for the cloud; the slash is paint, not a raised bar — a raised
    // slash drew its halo as a band across the puffs.
    val lump = Path().apply {
        addPath(clayOval(Offset(36f, 56f), 13f))
        addPath(clayOval(Offset(50f, 45f), 17f))
        addPath(clayOval(Offset(64f, 56f), 13f))
        addPath(clayRoundRect(Offset(26f, 58f), Size(48f, 13f), 6.5f))
    }
    clayShape(lump, base, puff = 3f)
    paintStroke(line(Offset(21f, 24f), Offset(79f, 76f)), ClayTokens.Cream, 12f)
}

private fun DrawScope.notifCard(base: Color) {
    // One lump for the card; the note is paint sweeping off it. The old
    // haloed note head + stem printed seam rings across the card face.
    clayShape(clayRoundRect(Offset(16f, 36f), Size(56f, 36f), 12f), base, puff = 3f)
    drawRoundRect(
        color = ClayTokens.Cream,
        topLeft = Offset(26f, 46f),
        size = Size(22f, 6.5f),
        cornerRadius = CornerRadius(3.25f, 3.25f)
    )
    drawRoundRect(
        color = ClayTokens.Cream,
        topLeft = Offset(26f, 56f),
        size = Size(14f, 6.5f),
        cornerRadius = CornerRadius(3.25f, 3.25f)
    )
    drawPath(ellipse(Offset(62f, 67f), 11f, 8.5f), ClayTokens.Cream)
    val stem = Path().apply {
        moveTo(71f, 65f)
        lineTo(71f, 28f)
        quadraticTo(63f, 28f, 60f, 36f)
    }
    paintStroke(stem, ClayTokens.Cream, 7.5f)
}

private fun DrawScope.code(base: Color) {
    // Three strokes free in air with clear gaps — halos never touch a face.
    val left = Path().apply {
        moveTo(46f, 32f)
        lineTo(27f, 50f)
        lineTo(46f, 68f)
    }
    val right = Path().apply {
        moveTo(54f, 32f)
        lineTo(73f, 50f)
        lineTo(54f, 68f)
    }
    clayLine(left, base, 12f)
    clayLine(right, base, 12f)
    clayLine(line(Offset(57f, 30f), Offset(43f, 70f)), base, 8f)
}

private fun DrawScope.drawObject(kind: ClayKind, base: Color) {
    when (kind) {
        ClayKind.Music -> music(base)
        ClayKind.Bell -> bell(base)
        ClayKind.Chart -> chart(base)
        ClayKind.Apps -> apps(base)
        ClayKind.Lock -> lock_(base)
        ClayKind.Bolt -> bolt(base)
        ClayKind.Shield -> shield(base)
        ClayKind.Phone -> phone(base)
        ClayKind.CloudOff -> cloudOff(base)
        ClayKind.NotifCard -> notifCard(base)
        ClayKind.Code -> code(base)
    }
}

/**
 * A real clay object, not a glyph on a tile: chunky hand-built silhouette,
 * soft outset, inset modeling, flat face. Drawn in 100-unit space, scaled
 * to [size] — crisp at any density.
 */
@Composable
fun ClayObject(
    kind: ClayKind,
    base: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 100f
        scale(u, u, Offset.Zero) { drawObject(kind, base) }
    }
}
