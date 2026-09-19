package me.avinas.tempo.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.annotation.DrawableRes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.clay.ClayTokens
import me.avinas.tempo.ui.clay.clay
import me.avinas.tempo.ui.clay.mix
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.premiumClickable
import me.avinas.tempo.ui.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.sin

// ──────────────────────────────────────────────────────────────
// Full-clay onboarding mockups — pure Compose, zero image assets.
//
// Both cards read as the real Android surfaces they preview
// (notification-access list / background-run dialog) but every
// element is molded clay: card lump, inset search, icon lumps,
// clay toggles, carved groove dividers, puffy Allow pill. No
// Material3 Switch / divider / flat tiles left inside — that mix
// was the patchwork. One rim + light system throughout:
// top-left light, down-right outset, flat faces.
// Radii scale: 12 icon / 28 card+dialog — chunky, like the
// CSS default 32px. Tight radii + shadow = flat Material; big radii
// + directional insets = inflated clay.
// Motion: one cue each, frozen when reduced motion is on.
// ──────────────────────────────────────────────────────────────

// Warm-clay surface tokens: one warm off-white face, everything else
// derived from it — no cool grays dirtying the clay.
private val LightCard = ClayTokens.Cream
private val LightSearchBg = mix(LightCard, Color.Black, 0.05f)
private val LightDivider = mix(LightCard, Color.Black, 0.10f)
private val LightTitle = Color(0xFF191C1C)
private val LightBody = Color(0xFF444747)
private val LightFaint = Color(0xFF757A7A)

/** Frameless light card floating on the dark background — molded in clay. */
@Composable
private fun LightMockCard(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clay(
                    background = LightCard,
                    cornerRadius = 28.dp,
                    outset = Color.Black.copy(alpha = 0.45f),
                    outsetElevation = 14.dp,
                    // Shallow inset: wide card, so even the default depth
                    // would band — keep the blur to the edges.
                    rimFraction = 0.015f,
                ).clip(RoundedCornerShape(28.dp))
                .padding(vertical = 8.dp),
    ) {
        content()
    }
}

private val TempoIconBg = Color(0xFF0E7C6B)

/** Flat brand tile: real mark on its brand color, plain rounded clip like the
 *  system list — icons stay unmolded. */
@Composable
private fun MockBrandTile(
    @DrawableRes markRes: Int,
    brand: Color,
) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .size(34.dp)
                .background(brand, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = markRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Real Tempo launcher art, flat full-bleed tile. The adaptive foreground
 *  carries 1/6 transparent safe-zone padding per side, so zoom 1.5x. */
@Composable
private fun MockTempoIcon() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f),
            contentScale = ContentScale.Fit,
        )
    }
}

// Dark product tokens (Tempo itself is a dark app)
private val MockRowBg = Color(0xFF1B1F1F)
private val MockTextDim = Color(0xFF9AA2A2)
private val MockTextFaint = Color(0xFF6B7373)

/**
 * Smooth demo loop for the guided switch: slides OFF → ON → OFF…
 * with easing so it reads as a touch demo. 1f = fully ON.
 */
@Composable
private fun rememberDemoSwitch(): Boolean {
    if (rememberReducedMotion()) return true
    val t = rememberInfiniteTransition(label = "demoSwitch")
    val frac by t.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "demoFrac",
    )
    return frac > 0.5f
}

/**
 * Single guide pulse for the dialog's Allow button. Returns the State unread —
 * only the leaves that draw the pulse subscribe, so the static dialog text
 * stops recomposing 60fps. No loop at all under reduced motion.
 */
@Composable
private fun rememberGuidePulseState(): State<Float> {
    if (rememberReducedMotion()) return remember { mutableFloatStateOf(0.55f) }
    val t = rememberInfiniteTransition(label = "guide")
    return t.animateFloat(
        0.3f,
        0.75f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
}

@Composable
private fun TempoSwitch(
    checked: Boolean,
) {
    // Clay toggle: the TRACK is the one molded lump; the knob sits on
    // its face so it stays flat paint (a halo there would print a seam
    // ring across the track). Knob = white disc + bottom-end shade
    // crescent, opposite the top-left light.
    val track = if (checked) TempoPrimary else Color(0xFFDDE3E3)
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .size(width = 46.dp, height = 28.dp)
                .clay(
                    background = track,
                    cornerRadius = 14.dp,
                    outsetElevation = 3.dp,
                    rimFraction = 0.10f,
                ).clip(RoundedCornerShape(14.dp)),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            contentAlignment = Alignment.BottomEnd,
        ) {
            // Shaded crescent bottom-end — light pools top-left, so the
            // roundness reads from the shade opposite. Sells the puff.
            androidx.compose.foundation.layout.Box(
                modifier =
                    Modifier
                        .padding(end = 4.dp, bottom = 3.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFB9C2C2).copy(alpha = 0.55f)),
            )
        }
    }
}

/** Carved groove divider: dark cut + light lip = chiseled, not printed. */
@Composable
private fun GrooveDivider() {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(1.dp).background(LightDivider),
        )
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(1.dp).background(LightCard),
        )
    }
}

/**
 * Stages a mockup like a product shot: ambient glow tying the light
 * card to the dark background, and optional tap-to-open so the
 * preview feels alive instead of pasted on.
 */
@Composable
fun MockupStage(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            // Ambient glow — what visually anchors the light card to the dark stage.
            androidx.compose.foundation.layout.Box(
                Modifier.size(280.dp).background(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(TempoPrimary.copy(alpha = 0.13f), Color.Transparent),
                        ),
                    shape = CircleShape,
                ),
            )
            androidx.compose.foundation.layout.Box(
                Modifier.then(if (onClick != null) Modifier.premiumClickable(onClick) else Modifier),
            ) {
                content()
            }
        }
    }
}

// ── 1 · Notification access ────────────────────────────────────
// Exactly what "Enable Notification Access" opens.

@Composable
fun NotificationAccessMockup(modifier: Modifier = Modifier) {
    val tempoOn = rememberDemoSwitch()
    Column(modifier = modifier.fillMaxWidth()) {
        LightMockCard {
            // Inset search pill — recessed into the card face: darker
            // fill + carved top hairline, content painted flat on top.
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(LightSearchBg),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().height(1.dp).background(LightDivider),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = LightFaint, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search settings", color = LightFaint, fontSize = 11.5.sp)
                }
            }
            Text(
                "Notification access",
                color = LightTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            // ★ Guided target — no container: same flat row as the rest,
            // guidance comes from the animated toggle + status line below.
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MockTempoIcon()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tempo", color = LightTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (tempoOn) "Allowed" else "Tap to allow",
                        color = if (tempoOn) Color(0xFF0E7C6B) else LightFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                TempoSwitch(checked = tempoOn)
            }
            GrooveDivider()
            listOf(
                Triple("Spotify", R.drawable.ic_spotify_mark, Color(0xFF1DB954)),
                Triple("YouTube Music", R.drawable.ic_ytmusic_mark, Color(0xFFE53935)),
            ).forEach { (name, markRes, brand) ->
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MockBrandTile(markRes, brand)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = LightBody, fontSize = 12.5.sp)
                        Text("Not allowed", color = LightFaint, fontSize = 10.5.sp)
                    }
                    TempoSwitch(checked = false)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(TempoPrimary.copy(alpha = if (tempoOn) 0.9f else 0.4f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Find Tempo → turn it on → tap Allow",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── 2 · Battery dialog ─────────────────────────────────────────
// Exactly what "Allow Background Running" opens: the system dialog.

@Composable
fun BatteryUsageMockup(modifier: Modifier = Modifier) {
    // Held unread — AllowPill + GuidePulseDot subscribe alone.
    val pulseState = rememberGuidePulseState()
    Column(modifier = modifier.fillMaxWidth()) {
        // The dialog itself, floating directly on the stage glow —
        // molded in clay, same content.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clay(
                        background = LightCard,
                        cornerRadius = 28.dp,
                        outset = Color.Black.copy(alpha = 0.35f),
                        outsetElevation = 8.dp,
                        rimFraction = 0.015f,
                    ).clip(RoundedCornerShape(28.dp))
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MockTempoIcon()
                Spacer(Modifier.width(10.dp))
                Text("Tempo", color = LightTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Allow Tempo to always run in background?",
                color = LightTitle,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tempo tracks music while your screen is off. Uses less than 1% battery daily.",
                color = LightBody,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    "Don't allow",
                    color = LightFaint,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(4.dp))
                // ★ Guided target — puffy clay pill on its own pulse halo.
                // The halo is paint behind the lump (one molded lump only).
                AllowPill(pulseState)
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuidePulseDot(pulseState)
            Spacer(Modifier.width(8.dp))
            Text(
                "Tap Allow in the dialog",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// Only these two nodes re-enter composition on every pulse frame — the dialog
// copy around them composes once.
@Composable
private fun AllowPill(pulse: State<Float>) {
    val p = pulse.value
    Text(
        "Allow",
        color = Color.White,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .drawBehind {
                    drawRoundRect(
                        color = TempoIconBg.copy(alpha = p * 0.30f),
                        topLeft = Offset(-6f, -4f),
                        size = Size(size.width + 12f, size.height + 8f),
                        cornerRadius = CornerRadius(size.height / 2f + 4f, size.height / 2f + 4f),
                    )
                }.clay(
                    background = TempoIconBg.copy(alpha = 0.55f + p * 0.45f),
                    cornerRadius = 20.dp,
                    outsetElevation = 6.dp,
                ).clip(CircleShape)
                .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
private fun GuidePulseDot(pulse: State<Float>) {
    val p = pulse.value
    androidx.compose.foundation.layout.Box(
        Modifier.size(7.dp).clip(CircleShape).background(TempoPrimary.copy(alpha = p + 0.25f)),
    )
}

// ── 3 · Welcome: the real product ──────────────────────────────
// Mini Tempo dashboard (dark — that IS the app theme). Static;
// the parent owns entry animation + glow.

@Composable
fun TempoProductMockup(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(
                    14.dp,
                    RoundedCornerShape(22.dp),
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                ).clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF121414))
                .padding(14.dp),
    ) {
        Text("Your week in music", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
        Text("12h 40m · 214 plays", color = MockTextDim, fontSize = 10.5.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Top artist" to "Tame Impala", "Top song" to "Borderline", "Streak" to "6 days").forEach { (k, v) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MockRowBg)
                        .padding(9.dp),
                ) {
                    Text(k.uppercase(), color = MockTextFaint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(v, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MockRowBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(0.38f, 0.62f, 0.5f, 0.82f, 0.58f, 1f, 0.72f).forEachIndexed { i, f ->
                val barBackground =
                    if (i == 5) {
                        Modifier.background(Brush.verticalGradient(listOf(TempoPrimary, TempoCyan)))
                    } else {
                        Modifier.background(Color.White.copy(alpha = 0.16f))
                    }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(15.dp)
                        .height((52 * f).dp)
                        .clip(RoundedCornerShape(5.dp))
                        .then(barBackground),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MockRowBg)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(TempoPrimary.copy(alpha = 0.85f), TempoCyan.copy(alpha = 0.7f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF0A0E0E), modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Midnight Drive", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Text("Glass Animals", color = MockTextDim, fontSize = 10.sp)
                Spacer(Modifier.height(5.dp))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(TempoPrimary),
                    )
                }
            }
        }
    }
}

// ── 4 · Welcome backdrop ─────────────────────────────────────────
// One composable owns both layers so they share geometry:
//   mid  · welcome.png fitted without zoom (Fit + BottomCenter — the
//          full image is always visible, never cropped, flush with the
//          bottom so copy sits on the artwork);
//   back · native revolving notes on a flat circle centered on the
//          head, drawn BEHIND the art: the transparent top lets them
//          show around the head while the character occludes the
//          loop's lower half, so notes pass behind and the face stays
//          clear.
// Top copy (headline + CTA) lives in WelcomeScreen, overlaid on the art.
// Subtle: small mid-alpha notes, no glow or shadow. One slow linear
// revolve; frozen under reduced motion.
// ponytail: one infinite angle drives every note; positions are pure trig.

// welcome.png is 941×1672.
private const val WelcomeArtAspect = 941f / 1672f

private enum class NoteKind {
    Quarter, // ♩
    Eighth, // ♪
    Beamed, // ♫
    Beamed16, // ♬
    Sharp, // ♯
    Flat, // ♭
    Natural, // ♮
}

private data class OrbitNote(
    val phaseDeg: Float,
    val size: Dp,
    val alpha: Float,
    val kind: NoteKind,
    // Per-note radial factor: breaks the perfect-circle read so the loop
    // hugs the non-circular head, while staying round overall (not oval).
    val radiusScale: Float,
)

// Sizes measured against welcomemusicalnote.png reference (note heights
// ≈ 16–29dp on a 360dp-wide screen); head width ≈ total height / 3.3.
// Curated set: durations (♩ ♪ ♫ ♬) + accidentals (♯ ♭ ♮) — symbols that
// naturally float around music. Clefs/staves/barlines skipped: they only
// read sitting on a staff, and a treble clef at 7dp is a blob. Exotic
// noteheads (X/diamond/triangle) skipped: percussion marks, wrong mood.
// All 7 slots distinct, neighbours always differ.
private val WelcomeOrbitNotes =
    listOf(
        OrbitNote(0f, 8.dp, 0.60f, NoteKind.Eighth, 1.0f),
        OrbitNote(52f, 7.dp, 0.55f, NoteKind.Sharp, 0.96f),
        OrbitNote(104f, 8.5.dp, 0.65f, NoteKind.Beamed, 1.03f),
        OrbitNote(160f, 7.dp, 0.52f, NoteKind.Quarter, 0.98f),
        OrbitNote(214f, 7.dp, 0.55f, NoteKind.Flat, 1.04f),
        OrbitNote(268f, 7.5.dp, 0.58f, NoteKind.Beamed16, 0.95f),
        OrbitNote(322f, 7.dp, 0.55f, NoteKind.Natural, 1.01f),
    )

// Unit-space note (1 unit = head width): caller wraps in scale(sPx).
// The eighth-note flag path is remembered once per kind at the call
// site, so steady-state frames allocate nothing.
private fun DrawScope.drawOrbitNoteUnit(
    center: Offset,
    color: Color,
    kind: NoteKind,
    flag: Path,
) {
    // Accidentals have no head/stem — line-built in the same unit space.
    // All primitives, no allocation per frame.
    when (kind) {
        NoteKind.Sharp -> {
            drawLine(color, center + Offset(-0.25f, 0.1f), center + Offset(-0.25f, -2.3f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            drawLine(color, center + Offset(0.35f, -0.1f), center + Offset(0.35f, -2.5f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            drawLine(color, center + Offset(-0.65f, -0.7f), center + Offset(0.75f, -1.0f), strokeWidth = 0.34f, cap = StrokeCap.Butt)
            drawLine(color, center + Offset(-0.65f, -1.5f), center + Offset(0.75f, -1.8f), strokeWidth = 0.34f, cap = StrokeCap.Butt)
            return
        }
        NoteKind.Flat -> {
            drawLine(color, center + Offset(-0.3f, 0.3f), center + Offset(-0.3f, -2.2f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            drawArc(
                color,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = center + Offset(-0.3f, -0.7f),
                size = Size(0.9f, 1.0f),
                style = Stroke(width = 0.2f, cap = StrokeCap.Round),
            )
            return
        }
        NoteKind.Natural -> {
            drawLine(color, center + Offset(-0.3f, 0.3f), center + Offset(-0.3f, -1.7f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            drawLine(color, center + Offset(0.3f, -0.5f), center + Offset(0.3f, -2.3f), strokeWidth = 0.18f, cap = StrokeCap.Round)
            drawLine(color, center + Offset(-0.3f, -0.85f), center + Offset(0.3f, -0.85f), strokeWidth = 0.3f, cap = StrokeCap.Butt)
            drawLine(color, center + Offset(-0.3f, -1.45f), center + Offset(0.3f, -1.45f), strokeWidth = 0.3f, cap = StrokeCap.Butt)
            return
        }
        else -> Unit
    }
    drawOval(color, topLeft = center + Offset(-0.5f, -0.36f), size = Size(1f, 0.72f))
    val stemBase = center + Offset(0.38f, -0.1f)
    val stemTop = center + Offset(0.42f, -2.6f)
    drawLine(color, stemBase, stemTop, strokeWidth = 0.18f, cap = StrokeCap.Round)
    when (kind) {
        NoteKind.Quarter -> Unit
        NoteKind.Eighth -> drawPath(flag, color)
        NoteKind.Beamed, NoteKind.Beamed16 -> {
            val second = center + Offset(1.5f, 0f)
            drawOval(color, topLeft = second + Offset(-0.5f, -0.36f), size = Size(1f, 0.72f))
            val stemTop2 = second + Offset(0.42f, -2.6f)
            drawLine(
                color,
                second + Offset(0.38f, -0.1f),
                stemTop2,
                strokeWidth = 0.18f,
                cap = StrokeCap.Round,
            )
            drawLine(color, stemTop, stemTop2, strokeWidth = 0.45f, cap = StrokeCap.Butt)
            // ponytail: ♬ reuses the ♫ block, one extra beam line.
            if (kind == NoteKind.Beamed16) {
                drawLine(
                    color,
                    stemTop + Offset(0f, 0.55f),
                    stemTop2 + Offset(0f, 0.55f),
                    strokeWidth = 0.35f,
                    cap = StrokeCap.Butt,
                )
            }
        }
        else -> Unit
    }
}

// Only this small canvas owns the frame-clock — the parent BoxWithConstraints
// + art Image compose once and never recompose per frame (the Image already
// skipped via unchanged inputs; now the parent doesn't even re-enter
// composition). The canvas covers just the orbit square to cut overdraw.
@Composable
private fun OrbitNotesCanvas(
    cx: Dp,
    cy: Dp,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    // Frame-clock drive — the angle derives from monotonic display time,
    // so motion has no iteration restart and runs identically on
    // 60/90/120Hz screens. Static frame when reduced motion is on.
    var elapsedNs by remember { mutableLongStateOf(0L) }
    if (!reducedMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                elapsedNs = withFrameNanos { it } - start
            }
        }
    }
    val revolve =
        if (reducedMotion) {
            20f
        } else {
            ((elapsedNs / 1_000_000_000.0 * (360.0 / 20.0)) % 360.0).toFloat()
        }
    val flag =
        remember {
            // Solid filled flag so the eighth reads vs the hollow quarter at 6–8dp.
            Path().apply {
                moveTo(0.42f, -2.6f)
                quadraticTo(1.45f, -2.4f, 1.2f, -1.25f)
                quadraticTo(1.0f, -1.9f, 0.42f, -1.75f)
                close()
            }
        }
    val box = (radius + 32.dp) * 2
    Canvas(modifier.offset(x = cx - box / 2, y = cy - box / 2).size(box)) {
        val rPx = radius.toPx()
        val origin = Offset(size.width, size.height) / 2f
        WelcomeOrbitNotes.forEach { note ->
            val deg = revolve + note.phaseDeg
            val rad = Math.toRadians(deg.toDouble())
            val rr = rPx * note.radiusScale
            val center =
                origin +
                    Offset(
                        rr * cos(rad).toFloat(),
                        rr * sin(rad).toFloat(),
                    )
            rotate(deg.toFloat() + 90f, center) {
                scale(note.size.toPx(), note.size.toPx(), center) {
                    drawOrbitNoteUnit(center, Color.White.copy(alpha = note.alpha), note.kind, flag)
                }
            }
        }
    }
}

@Composable
fun WelcomeBackdrop(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        // Fit + BottomCenter rect in this box — full art visible, never
        // zoomed, flush with the bottom edge (no hollow space below).
        // The transparent top melts into the dark background above.
        // Cached: pure function of the constraints, not the frame angle.
        val dims =
            remember(maxWidth, maxHeight) {
                if (maxWidth / maxHeight < WelcomeArtAspect) {
                    val w = maxWidth
                    w to w / WelcomeArtAspect
                } else {
                    val h = maxHeight
                    (h * WelcomeArtAspect) to h
                }
            }
        val (imgW, imgH) = dims
        val offX = (maxWidth - imgW) / 2
        val offY = maxHeight - imgH
        // Loop balanced around the measured head (face centroid ≈
        // 0.51W/0.29H, hair x 0.29–0.73W / y 0.10–0.37H): center sits on
        // the face, radius clears the hair with ~30–60px gaps on every
        // side — round, not oval, hugging the non-circular head. Matches
        // the welcomemusicalnote.png reference orbit (≈0.52W/0.23H,
        // r≈0.16H).
        val cx = offX + imgW * 0.52f
        val cy = offY + imgH * 0.232f
        val radius = imgH * 0.158f
        // BACK first so the art occludes the loop's lower half.
        OrbitNotesCanvas(cx = cx, cy = cy, radius = radius)
        Image(
            painter = painterResource(id = R.drawable.welcome_hero),
            contentDescription = null,
            modifier = Modifier.offset(x = offX, y = offY).size(width = imgW, height = imgH),
            contentScale = ContentScale.Fit,
        )
    }
}
