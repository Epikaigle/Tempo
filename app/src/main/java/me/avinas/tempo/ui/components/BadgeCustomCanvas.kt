package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.stats.GamificationEngine
import me.avinas.tempo.ui.profile.BadgeSilhouettes
import me.avinas.tempo.ui.profile.getLevelTierAccent
import me.avinas.tempo.ui.profile.getRarityColor
import me.avinas.tempo.ui.profile.getRarityMetal
import me.avinas.tempo.ui.profile.getUniqueBadgeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural canvas rendering the artwork backdrop for badge share cards.
 * Provides a source image for share theme effects such as fluted glass and motion blur.
 */
@Composable
fun BadgeCustomCanvas(
    badge: Badge,
    modifier: Modifier = Modifier
) {
    val intrinsicColor = remember(badge.badgeId) { getUniqueBadgeColor(badge.badgeId) }
    val rarity = remember(badge.badgeId) { GamificationEngine.getRarity(badge.badgeId) }
    val rarityColor = remember(rarity) { getRarityColor(rarity) }
    val metalColors = remember(rarity) { getRarityMetal(rarity) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val trophyCenter = Offset(w * 0.5f, h * 0.285f)

        // 1. Radiant Atmospheric Trophy Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    intrinsicColor.copy(alpha = 0.50f),
                    rarityColor.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                center = trophyCenter,
                radius = w * 0.88f
            ),
            radius = w * 0.88f,
            center = trophyCenter
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    intrinsicColor.copy(alpha = 0.60f),
                    Color.Transparent
                ),
                center = trophyCenter,
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = trophyCenter
        )

        // Bottom ambient warmth pool
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    intrinsicColor.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, h * 1.02f),
                radius = w * 0.70f
            ),
            radius = w * 0.70f,
            center = Offset(w * 0.5f, h * 1.02f)
        )

        // 2. Large Silhouette Watermark Echo (Unique contour per badge)
        runCatching {
            val watermarkSize = Size(w * 0.88f, w * 0.88f)
            val silhouettePath = BadgeSilhouettes.getPath(badge.badgeId, watermarkSize, insetScale = 1.0f)

            drawPath(
                path = silhouettePath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        intrinsicColor.copy(alpha = 0.12f),
                        rarityColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = trophyCenter,
                    radius = watermarkSize.width * 0.65f
                )
            )

            val rimMetal = metalColors.firstOrNull() ?: rarityColor
            drawPath(
                path = silhouettePath,
                color = rimMetal.copy(alpha = 0.20f),
                style = Stroke(width = 1.2.dp.toPx())
            )
        }

        // 3. Concentric Astrolabe / Achievement Orbit Rings
        drawCircle(
            color = rarityColor.copy(alpha = 0.28f),
            radius = w * 0.32f,
            center = trophyCenter,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            )
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.14f),
            radius = w * 0.48f,
            center = trophyCenter,
            style = Stroke(width = 0.8.dp.toPx())
        )

        drawCircle(
            color = intrinsicColor.copy(alpha = 0.22f),
            radius = w * 0.65f,
            center = trophyCenter,
            style = Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 16f), 0f)
            )
        )

        // Precision cardinal tick marks at cardinal degrees
        val tickRadius = w * 0.48f
        val tickLen = 6.dp.toPx()
        for (angleDeg in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) {
            val rad = angleDeg * (PI / 180f).toFloat()
            val cosA = cos(rad)
            val sinA = sin(rad)
            val start = Offset(trophyCenter.x + (tickRadius - tickLen / 2) * cosA, trophyCenter.y + (tickRadius - tickLen / 2) * sinA)
            val end = Offset(trophyCenter.x + (tickRadius + tickLen / 2) * cosA, trophyCenter.y + (tickRadius + tickLen / 2) * sinA)
            drawLine(
                color = rarityColor.copy(alpha = 0.35f),
                start = start,
                end = end,
                strokeWidth = 1.dp.toPx()
            )
        }

        // 4. Subtle Radial Light Rays
        val rayCount = 12
        for (i in 0 until rayCount) {
            val angleDeg = (i * (360f / rayCount))
            val rad = angleDeg * (PI / 180f).toFloat()
            val rayStart = Offset(trophyCenter.x + (w * 0.28f) * cos(rad), trophyCenter.y + (w * 0.28f) * sin(rad))
            val rayEnd = Offset(trophyCenter.x + (w * 0.72f) * cos(rad), trophyCenter.y + (w * 0.72f) * sin(rad))
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        intrinsicColor.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    start = rayStart,
                    end = rayEnd
                ),
                start = rayStart,
                end = rayEnd,
                strokeWidth = 1.dp.toPx()
            )
        }

        // 5. Seeded Constellation Starlight Dust
        val rng = Random(badge.badgeId.hashCode())
        val particleCount = 36
        repeat(particleCount) {
            val px = w * rng.nextFloat()
            val py = h * (0.08f + rng.nextFloat() * 0.82f)
            val pRadius = (0.8f + rng.nextFloat() * 2.2f).dp.toPx()
            val pAlpha = 0.18f + rng.nextFloat() * 0.50f
            val pColor = when (rng.nextInt(4)) {
                0 -> intrinsicColor
                1 -> rarityColor
                2 -> Color.White
                else -> metalColors.firstOrNull() ?: intrinsicColor
            }

            drawCircle(
                color = pColor.copy(alpha = pAlpha),
                radius = pRadius,
                center = Offset(px, py)
            )

            if (pRadius > 1.8f.dp.toPx()) {
                drawCircle(
                    color = pColor.copy(alpha = pAlpha * 0.35f),
                    radius = pRadius * 2.4f,
                    center = Offset(px, py)
                )
            }
        }
    }
}

/**
 * Single procedural canvas acting as the artwork / image source for Level Milestone share cards.
 */
@Composable
fun MilestoneCustomCanvas(
    level: Int,
    modifier: Modifier = Modifier
) {
    val tierAccent = remember(level) { getLevelTierAccent(level) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val crestCenter = Offset(w * 0.5f, h * 0.285f)

        // 1. Radiant Tier Halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tierAccent.copy(alpha = 0.55f),
                    tierAccent.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                center = crestCenter,
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = crestCenter
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.35f),
                    tierAccent.copy(alpha = 0.60f),
                    Color.Transparent
                ),
                center = crestCenter,
                radius = w * 0.42f
            ),
            radius = w * 0.42f,
            center = crestCenter
        )

        // 2. Concentric Milestone Level Rings
        drawCircle(
            color = tierAccent.copy(alpha = 0.30f),
            radius = w * 0.30f,
            center = crestCenter,
            style = Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            )
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = w * 0.46f,
            center = crestCenter,
            style = Stroke(width = 0.8.dp.toPx())
        )

        drawCircle(
            color = tierAccent.copy(alpha = 0.22f),
            radius = w * 0.62f,
            center = crestCenter,
            style = Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 14f), 0f)
            )
        )

        // 3. Cardinal Degree Notches
        val notchRadius = w * 0.46f
        val notchLen = 6.dp.toPx()
        for (angleDeg in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) {
            val rad = angleDeg * (PI / 180f).toFloat()
            val cosA = cos(rad)
            val sinA = sin(rad)
            val start = Offset(crestCenter.x + (notchRadius - notchLen / 2) * cosA, crestCenter.y + (notchRadius - notchLen / 2) * sinA)
            val end = Offset(crestCenter.x + (notchRadius + notchLen / 2) * cosA, crestCenter.y + (notchRadius + notchLen / 2) * sinA)
            drawLine(
                color = tierAccent.copy(alpha = 0.35f),
                start = start,
                end = end,
                strokeWidth = 1.dp.toPx()
            )
        }

        // 4. Seeded Level Star Dust
        val rng = Random(level * 37 + 101)
        repeat(32) {
            val px = w * rng.nextFloat()
            val py = h * (0.08f + rng.nextFloat() * 0.82f)
            val pRadius = (0.8f + rng.nextFloat() * 2.0f).dp.toPx()
            val pAlpha = 0.18f + rng.nextFloat() * 0.45f
            val pColor = if (rng.nextBoolean()) tierAccent else Color.White

            drawCircle(
                color = pColor.copy(alpha = pAlpha),
                radius = pRadius,
                center = Offset(px, py)
            )
        }
    }
}

/**
 * Renders the badge's procedural artwork backdrop to a [Bitmap] for use by share card shaders.
 */
fun createBadgeBackdropBitmap(badge: Badge, width: Int = 576, height: Int = 1024): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val intrinsic = getUniqueBadgeColor(badge.badgeId)
    val intrinsicColor = intrinsic.toArgb()
    val rarity = GamificationEngine.getRarity(badge.badgeId)
    val rarityColor = getRarityColor(rarity).toArgb()
    val metalColors = getRarityMetal(rarity).map { it.toArgb() }
    val w = width.toFloat()
    val h = height.toFloat()
    val trophyCenter = Offset(w * 0.5f, h * 0.285f)

    // 1. Dark Gradient Canvas Base
    val bgPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0F172A.toInt(), 0xFF020617.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w, h, bgPaint)

    // 2. Radiant Atmospheric Trophy Bloom
    val outerGlowPaint = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            trophyCenter.x, trophyCenter.y, w * 0.88f,
            intArrayOf(
                intrinsic.copy(alpha = 0.55f).toArgb(),
                getRarityColor(rarity).copy(alpha = 0.28f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(trophyCenter.x, trophyCenter.y, w * 0.88f, outerGlowPaint)

    val innerGlowPaint = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            trophyCenter.x, trophyCenter.y, w * 0.45f,
            intArrayOf(
                Color.White.copy(alpha = 0.40f).toArgb(),
                intrinsic.copy(alpha = 0.65f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(trophyCenter.x, trophyCenter.y, w * 0.45f, innerGlowPaint)

    // Bottom ambient pool
    val bottomGlowPaint = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            w * 0.5f, h * 1.02f, w * 0.70f,
            intArrayOf(intrinsic.copy(alpha = 0.25f).toArgb(), 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(w * 0.5f, h * 1.02f, w * 0.70f, bottomGlowPaint)

    // 3. Silhouette Watermark
    runCatching {
        val watermarkSize = Size(w * 0.88f, w * 0.88f)
        val silhouettePath = BadgeSilhouettes.getPath(badge.badgeId, watermarkSize, insetScale = 1.0f)
        val androidPath = silhouettePath.asAndroidPath()
        val matrix = Matrix()
        matrix.postTranslate(trophyCenter.x - watermarkSize.width * 0.5f, trophyCenter.y - watermarkSize.height * 0.5f)
        val translatedPath = AndroidPath()
        androidPath.transform(matrix, translatedPath)

        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = RadialGradient(
                trophyCenter.x, trophyCenter.y, watermarkSize.width * 0.65f,
                intArrayOf(
                    intrinsic.copy(alpha = 0.14f).toArgb(),
                    getRarityColor(rarity).copy(alpha = 0.06f).toArgb(),
                    0x00000000
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(translatedPath, fillPaint)

        val strokePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = (metalColors.firstOrNull() ?: rarityColor)
            alpha = 140
        }
        canvas.drawPath(translatedPath, strokePaint)
    }

    // 4. Concentric Astrolabe Rings
    val dashedRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = rarityColor
        alpha = 90
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    canvas.drawCircle(trophyCenter.x, trophyCenter.y, w * 0.32f, dashedRingPaint)

    val solidRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = 0x44FFFFFF.toInt()
    }
    canvas.drawCircle(trophyCenter.x, trophyCenter.y, w * 0.48f, solidRingPaint)

    val outerDashedRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = intrinsicColor
        alpha = 80
        pathEffect = DashPathEffect(floatArrayOf(12f, 26f), 0f)
    }
    canvas.drawCircle(trophyCenter.x, trophyCenter.y, w * 0.65f, outerDashedRing)

    // 5. Cardinal degree tick marks
    val tickPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2.5f
        color = rarityColor
        alpha = 110
    }
    val tickRadius = w * 0.48f
    val tickLen = 14f
    for (angleDeg in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) {
        val rad = angleDeg * (PI / 180f).toFloat()
        val cosA = cos(rad)
        val sinA = sin(rad)
        val startX = trophyCenter.x + (tickRadius - tickLen / 2) * cosA
        val startY = trophyCenter.y + (tickRadius - tickLen / 2) * sinA
        val endX = trophyCenter.x + (tickRadius + tickLen / 2) * cosA
        val endY = trophyCenter.y + (tickRadius + tickLen / 2) * sinA
        canvas.drawLine(startX, startY, endX, endY, tickPaint)
    }

    // 6. Constellation Star Dust
    val rng = Random(badge.badgeId.hashCode())
    val starPaint = Paint().apply { isAntiAlias = true }
    repeat(48) {
        val px = w * rng.nextFloat()
        val py = h * (0.08f + rng.nextFloat() * 0.82f)
        val pRadius = 2f + rng.nextFloat() * 5f
        val pColor = when (rng.nextInt(4)) {
            0 -> intrinsicColor
            1 -> rarityColor
            2 -> 0xFFFFFFFF.toInt()
            else -> metalColors.firstOrNull() ?: intrinsicColor
        }
        starPaint.color = pColor
        starPaint.alpha = 70 + rng.nextInt(120)
        canvas.drawCircle(px, py, pRadius, starPaint)
    }

    return bitmap
}

/**
 * Creates a high-definition [Bitmap] representing the milestone's procedural artwork backdrop.
 */
fun createMilestoneBackdropBitmap(level: Int, width: Int = 576, height: Int = 1024): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val tier = getLevelTierAccent(level)
    val tierAccent = tier.toArgb()
    val w = width.toFloat()
    val h = height.toFloat()
    val crestCenter = Offset(w * 0.5f, h * 0.285f)

    // 1. Dark Gradient Base
    val bgPaint = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0F172A.toInt(), 0xFF020617.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w, h, bgPaint)

    // 2. Radiant Tier Halo
    val outerHalo = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            crestCenter.x, crestCenter.y, w * 0.85f,
            intArrayOf(
                tier.copy(alpha = 0.55f).toArgb(),
                tier.copy(alpha = 0.25f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(crestCenter.x, crestCenter.y, w * 0.85f, outerHalo)

    val innerHalo = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            crestCenter.x, crestCenter.y, w * 0.42f,
            intArrayOf(
                Color.White.copy(alpha = 0.35f).toArgb(),
                tier.copy(alpha = 0.60f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(crestCenter.x, crestCenter.y, w * 0.42f, innerHalo)

    // 3. Concentric Rings
    val dashedRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = tierAccent
        alpha = 90
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }
    canvas.drawCircle(crestCenter.x, crestCenter.y, w * 0.30f, dashedRing)

    val solidRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = 0x44FFFFFF.toInt()
    }
    canvas.drawCircle(crestCenter.x, crestCenter.y, w * 0.46f, solidRing)

    // 4. Star Dust
    val rng = Random(level * 37 + 101)
    val starPaint = Paint().apply { isAntiAlias = true }
    repeat(40) {
        val px = w * rng.nextFloat()
        val py = h * (0.08f + rng.nextFloat() * 0.82f)
        val pRadius = 2f + rng.nextFloat() * 4.5f
        val pColor = if (rng.nextBoolean()) tierAccent else 0xFFFFFFFF.toInt()
        starPaint.color = pColor
        starPaint.alpha = 70 + rng.nextInt(110)
        canvas.drawCircle(px, py, pRadius, starPaint)
    }

    return bitmap
}
