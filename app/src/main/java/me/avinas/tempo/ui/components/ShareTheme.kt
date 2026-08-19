package me.avinas.tempo.ui.components

import me.avinas.tempo.ui.theme.TempoDarkBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared theme system for all share cards (stats, song details, artist
 * details). A theme defines color tone, shape language, and contrast rules;
 * every card derives text, surfaces, badges, and glows from it so stats stay
 * readable on both dark and light backdrops.
 */
data class ShareThemePalette(
    val gradient: List<Color>,
    val overlay: List<Color>,
    val accent: Color,
    val glowTop: Color,
    val glowBottom: Color,
    val rank1Tint: Color,
    val isDark: Boolean = true,
    // Shape language — surfaces, thumbnails, and rank badges follow the theme;
    // winner rings (hero/podium avatars) stay circular by design.
    val cardShape: RoundedCornerShape = RoundedCornerShape(20.dp),
    val thumbShape: RoundedCornerShape = RoundedCornerShape(8.dp),
    val badgeShape: Shape = CircleShape
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
    val glowAlpha: Float get() = if (isDark) 0.15f else 0.3f
}

enum class ShareTheme(val palette: ShareThemePalette) {
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
            rank1Tint = Color(0xFFF59E0B)
        )
    ),
    ROSE(
        ShareThemePalette(
            gradient = listOf(Color(0xFF140A12), Color(0xFF4A1D3F), Color(0xFF831843)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.55f),
                Color(0xFF12080F).copy(alpha = 0.8f),
                Color(0xFF0B0509).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFF9A8D4),
            glowTop = Color(0xFFEC4899),
            glowBottom = Color(0xFFA855F7),
            rank1Tint = Color(0xFFF472B6),
            cardShape = RoundedCornerShape(24.dp),
            thumbShape = RoundedCornerShape(12.dp)
        )
    ),
    AURORA(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0A0F0D), Color(0xFF064E3B), Color(0xFF1E3A8A)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF0A0F12).copy(alpha = 0.8f),
                Color(0xFF050D10).copy(alpha = 0.95f)
            ),
            accent = Color(0xFF34D399),
            glowTop = Color(0xFF10B981),
            glowBottom = Color(0xFF3B82F6),
            rank1Tint = Color(0xFF34D399),
            cardShape = RoundedCornerShape(16.dp),
            thumbShape = RoundedCornerShape(10.dp)
        )
    ),
    MONO(
        ShareThemePalette(
            gradient = listOf(Color(0xFF0A0A0A), Color(0xFF1F1F1F), Color(0xFF2A2A2A)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF111111).copy(alpha = 0.8f),
                Color(0xFF0A0A0A).copy(alpha = 0.95f)
            ),
            accent = Color(0xFFE5E7EB),
            glowTop = Color(0xFF9CA3AF),
            glowBottom = Color(0xFFD1D5DB),
            rank1Tint = Color(0xFFE5E7EB),
            cardShape = RoundedCornerShape(2.dp),
            thumbShape = RoundedCornerShape(2.dp),
            badgeShape = RoundedCornerShape(3.dp)
        )
    ),
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
            cardShape = RoundedCornerShape(12.dp),
            thumbShape = RoundedCornerShape(6.dp)
        )
    ),
    OCEAN(
        ShareThemePalette(
            gradient = listOf(Color(0xFF050D14), Color(0xFF0C4A6E), Color(0xFF155E75)),
            overlay = listOf(
                Color.Black.copy(alpha = 0.5f),
                Color(0xFF06131C).copy(alpha = 0.8f),
                Color(0xFF040D13).copy(alpha = 0.95f)
            ),
            accent = Color(0xFF22D3EE),
            glowTop = Color(0xFF0EA5E9),
            glowBottom = Color(0xFF2DD4BF),
            rank1Tint = Color(0xFF22D3EE),
            cardShape = RoundedCornerShape(22.dp),
            thumbShape = RoundedCornerShape(10.dp)
        )
    )
}

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
