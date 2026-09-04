package me.avinas.tempo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoWarningBright
import me.avinas.tempo.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private enum class ParticleType {
    RIBBON,
    STAR,
    MUSIC_NOTE,
    SPARKLE
}

private data class CelebrationParticle(
    val initialAngleRad: Float,
    val initialSpeed: Float,
    val gravity: Float,
    val drag: Float,
    val size: Float,
    val color: Color,
    val type: ParticleType,
    val spinSpeed: Float,
    val flutterFrequency: Float,
    val flutterPhase: Float,
    val alphaDecayStart: Float,
    val wobbleAmplitude: Float,
    val isDoubleNote: Boolean = false
)

/**
 * High-performance celebration canvas that simulates physics-based confetti bursts,
 * 3D-tumbling ribbons, twinkling stars, ascending musical notes, and expanding shockwave rings.
 *
 * Automatically respects system reduced motion preferences.
 */
@Composable
fun CelebrationParticlesCanvas(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        TempoWarning,
        TempoPrimary,
        TempoAccent,
        TempoWarningBright,
        Color(0xFFEC4899),
        Color(0xFFA855F7),
        Color.White
    ),
    particleCount: Int = 65,
    includeNotes: Boolean = true,
    includeShockwaves: Boolean = true,
    triggerKey: Any? = Unit
) {
    val reducedMotion = rememberReducedMotion()
    val progress = remember(triggerKey) { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (reducedMotion) 1200 else 2800,
                easing = if (reducedMotion) FastOutSlowInEasing else LinearEasing
            )
        )
    }

    if (reducedMotion) {
        // Serene breathing ambient aura for reduced motion
        val p = progress.value
        val auraAlpha = (1f - p) * 0.45f
        Canvas(modifier = modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.38f)
            val maxRadius = size.minDimension * 0.7f * (0.6f + p * 0.4f)
            val primaryColor = colors.firstOrNull() ?: TempoWarning
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = auraAlpha),
                        primaryColor.copy(alpha = auraAlpha * 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius
                ),
                center = center,
                radius = maxRadius
            )
        }
        return
    }

    // Seeded physics particle state
    val particles = remember(triggerKey, particleCount) {
        val rand = Random(System.currentTimeMillis())
        List(particleCount) { index ->
            // Distribute angles predominantly upwards and outward
            val baseAngle = (index.toFloat() / particleCount) * 2f * PI.toFloat()
            // Add slight randomness to burst angle
            val angle = baseAngle + (rand.nextFloat() - 0.5f) * 0.5f
            val speed = 380f + rand.nextFloat() * 650f
            val gravity = 480f + rand.nextFloat() * 320f
            val drag = 0.94f + rand.nextFloat() * 0.04f

            val type = when {
                includeNotes && index % 7 == 0 -> ParticleType.MUSIC_NOTE
                index % 4 == 0 -> ParticleType.STAR
                index % 3 == 0 -> ParticleType.SPARKLE
                else -> ParticleType.RIBBON
            }

            CelebrationParticle(
                initialAngleRad = angle,
                initialSpeed = speed,
                gravity = gravity,
                drag = drag,
                size = when (type) {
                    ParticleType.RIBBON -> 8f + rand.nextFloat() * 7f
                    ParticleType.STAR -> 10f + rand.nextFloat() * 8f
                    ParticleType.MUSIC_NOTE -> 14f + rand.nextFloat() * 6f
                    ParticleType.SPARKLE -> 4f + rand.nextFloat() * 5f
                },
                color = colors[rand.nextInt(colors.size)],
                type = type,
                spinSpeed = (rand.nextFloat() - 0.5f) * 720f,
                flutterFrequency = 4f + rand.nextFloat() * 8f,
                flutterPhase = rand.nextFloat() * 2f * PI.toFloat(),
                alphaDecayStart = 0.65f + rand.nextFloat() * 0.2f,
                wobbleAmplitude = 18f + rand.nextFloat() * 30f,
                isDoubleNote = rand.nextBoolean()
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas

        val burstOrigin = Offset(size.width / 2f, size.height * 0.38f)
        val timeSeconds = p * 2.8f

        // Draw expanding shockwave rings if enabled
        if (includeShockwaves) {
            drawShockwaveRings(burstOrigin, p, colors)
        }

        // Pre-allocate paths or reuse primitives
        particles.forEach { particle ->
            // Physics trajectory calculation
            // v(t) with exponential drag damping
            val radialDist = particle.initialSpeed * (1f - (1f - p * 0.95f) * (1f - p * 0.95f)) * 0.75f

            // Gravity effect pulls downward increasingly as time progresses
            val dropY = 0.5f * particle.gravity * timeSeconds * timeSeconds

            // Wobble across horizontal axis
            val wobble = sin(timeSeconds * particle.flutterFrequency + particle.flutterPhase) * particle.wobbleAmplitude

            val posX = burstOrigin.x + cos(particle.initialAngleRad) * radialDist + wobble
            val posY = burstOrigin.y + sin(particle.initialAngleRad) * radialDist * 0.75f + dropY

            // Fade calculation
            val alpha = if (p > particle.alphaDecayStart) {
                ((1f - p) / (1f - particle.alphaDecayStart)).coerceIn(0f, 1f)
            } else {
                (p / 0.08f).coerceIn(0f, 1f)
            }

            if (alpha <= 0.01f || posX < -40f || posX > size.width + 40f || posY > size.height + 40f) {
                return@forEach
            }

            val currentRotation = particle.spinSpeed * timeSeconds
            // 3D tumble flip factor via cos
            val flutterScaleX = cos(timeSeconds * particle.flutterFrequency + particle.flutterPhase)

            when (particle.type) {
                ParticleType.RIBBON -> {
                    drawRibbonParticle(
                        posX = posX,
                        posY = posY,
                        particle = particle,
                        rotation = currentRotation,
                        scaleX = flutterScaleX,
                        alpha = alpha
                    )
                }
                ParticleType.STAR -> {
                    drawStarParticle(
                        posX = posX,
                        posY = posY,
                        particle = particle,
                        rotation = currentRotation,
                        alpha = alpha
                    )
                }
                ParticleType.MUSIC_NOTE -> {
                    drawMusicNoteParticle(
                        posX = posX,
                        posY = posY,
                        particle = particle,
                        rotation = currentRotation * 0.3f,
                        alpha = alpha
                    )
                }
                ParticleType.SPARKLE -> {
                    drawSparkleParticle(
                        posX = posX,
                        posY = posY,
                        particle = particle,
                        time = timeSeconds,
                        alpha = alpha
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawShockwaveRings(
    center: Offset,
    progress: Float,
    colors: List<Color>
) {
    val primary = colors.firstOrNull() ?: TempoWarning
    val secondary = colors.getOrNull(1) ?: TempoPrimary

    // Ring 1 (fast expansion)
    val ring1Progress = (progress / 0.55f).coerceIn(0f, 1f)
    if (ring1Progress in 0.01f..0.99f) {
        val ring1Radius = size.minDimension * 0.75f * ring1Progress
        val ring1Alpha = (1f - ring1Progress) * 0.55f
        drawCircle(
            color = primary.copy(alpha = ring1Alpha),
            radius = ring1Radius,
            center = center,
            style = Stroke(width = (4f * (1f - ring1Progress)).coerceAtLeast(1.2f))
        )
    }

    // Ring 2 (secondary trailing delayed expansion)
    val ring2Progress = ((progress - 0.08f) / 0.60f).coerceIn(0f, 1f)
    if (ring2Progress in 0.01f..0.99f) {
        val ring2Radius = size.minDimension * 0.60f * ring2Progress
        val ring2Alpha = (1f - ring2Progress) * 0.40f
        drawCircle(
            color = secondary.copy(alpha = ring2Alpha),
            radius = ring2Radius,
            center = center,
            style = Stroke(width = (3f * (1f - ring2Progress)).coerceAtLeast(1f))
        )
    }
}

private fun DrawScope.drawRibbonParticle(
    posX: Float,
    posY: Float,
    particle: CelebrationParticle,
    rotation: Float,
    scaleX: Float,
    alpha: Float
) {
    rotate(degrees = rotation, pivot = Offset(posX, posY)) {
        scale(scaleX = scaleX, scaleY = 1f, pivot = Offset(posX, posY)) {
            val width = particle.size
            val height = particle.size * 1.8f
            drawRoundRect(
                color = particle.color.copy(alpha = alpha * 0.92f),
                topLeft = Offset(posX - width / 2f, posY - height / 2f),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f)
            )
        }
    }
}

private fun DrawScope.drawStarParticle(
    posX: Float,
    posY: Float,
    particle: CelebrationParticle,
    rotation: Float,
    alpha: Float
) {
    rotate(degrees = rotation, pivot = Offset(posX, posY)) {
        val r = particle.size
        val path = Path().apply {
            val points = 4
            val innerR = r * 0.36f
            for (i in 0 until points * 2) {
                val radius = if (i % 2 == 0) r else innerR
                val angle = (i * PI / points).toFloat() - (PI / 2).toFloat()
                val x = posX + radius * cos(angle)
                val y = posY + radius * sin(angle)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path = path, color = particle.color.copy(alpha = alpha))
    }
}

private fun DrawScope.drawMusicNoteParticle(
    posX: Float,
    posY: Float,
    particle: CelebrationParticle,
    rotation: Float,
    alpha: Float
) {
    rotate(degrees = rotation, pivot = Offset(posX, posY)) {
        val scale = particle.size / 16f
        translate(left = posX - 6f * scale, top = posY - 8f * scale) {
            val noteColor = particle.color.copy(alpha = alpha * 0.90f)
            val strokeW = 2f * scale

            if (!particle.isDoubleNote) {
                // Single musical note (♪)
                rotate(degrees = -20f, pivot = Offset(4f * scale, 12f * scale)) {
                    drawOval(
                        color = noteColor,
                        topLeft = Offset(1.5f * scale, 9.5f * scale),
                        size = Size(5.5f * scale, 4.5f * scale)
                    )
                }
                drawLine(
                    color = noteColor,
                    start = Offset(6.5f * scale, 11f * scale),
                    end = Offset(6.5f * scale, 1.5f * scale),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                val flagPath = Path().apply {
                    moveTo(6.5f * scale, 1.5f * scale)
                    cubicTo(
                        9.5f * scale, 2f * scale,
                        11f * scale, 4.5f * scale,
                        10.5f * scale, 7f * scale
                    )
                }
                drawPath(path = flagPath, color = noteColor, style = Stroke(width = strokeW, cap = StrokeCap.Round))
            } else {
                // Paired musical note (♫)
                drawOval(
                    color = noteColor,
                    topLeft = Offset(0f, 9.5f * scale),
                    size = Size(4.5f * scale, 3.5f * scale)
                )
                drawOval(
                    color = noteColor,
                    topLeft = Offset(8f * scale, 8.5f * scale),
                    size = Size(4.5f * scale, 3.5f * scale)
                )
                drawLine(
                    color = noteColor,
                    start = Offset(4f * scale, 10.5f * scale),
                    end = Offset(4f * scale, 2f * scale),
                    strokeWidth = strokeW
                )
                drawLine(
                    color = noteColor,
                    start = Offset(12f * scale, 9.5f * scale),
                    end = Offset(12f * scale, 1f * scale),
                    strokeWidth = strokeW
                )
                drawLine(
                    color = noteColor,
                    start = Offset(3.5f * scale, 2f * scale),
                    end = Offset(12.5f * scale, 1f * scale),
                    strokeWidth = strokeW * 1.5f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun DrawScope.drawSparkleParticle(
    posX: Float,
    posY: Float,
    particle: CelebrationParticle,
    time: Float,
    alpha: Float
) {
    val twinkle = (0.6f + 0.4f * sin(time * 12f + particle.flutterPhase)).coerceIn(0f, 1f)
    drawCircle(
        color = particle.color.copy(alpha = alpha * twinkle),
        radius = particle.size * 0.7f,
        center = Offset(posX, posY)
    )
    val glint = particle.size * 1.4f
    drawLine(
        color = particle.color.copy(alpha = alpha * twinkle * 0.6f),
        start = Offset(posX - glint, posY),
        end = Offset(posX + glint, posY),
        strokeWidth = 1f
    )
    drawLine(
        color = particle.color.copy(alpha = alpha * twinkle * 0.6f),
        start = Offset(posX, posY - glint),
        end = Offset(posX, posY + glint),
        strokeWidth = 1f
    )
}
