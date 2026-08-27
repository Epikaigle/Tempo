package me.avinas.tempo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color


// Dark color scheme mapping
private val DarkColorScheme = darkColorScheme(
    primary = TempoPrimary,
    onPrimary = TextOnAccent,
    primaryContainer = TempoPrimaryDeep,
    onPrimaryContainer = TempoAccent,

    secondary = TempoSecondary,
    onSecondary = TextOnAccent,
    secondaryContainer = Color(0xFF123B32),
    onSecondaryContainer = TempoAccentBright,

    tertiary = TempoPop,
    onTertiary = Color(0xFF28081A),
    tertiaryContainer = TempoPopContainer,
    onTertiaryContainer = OnTempoPopContainer,

    error = TempoError,
    onError = TextOnAccent,
    errorContainer = TempoErrorDeep,
    onErrorContainer = TempoErrorSoft,

    background = TempoDarkBackground,
    onBackground = TextPrimary,
    surface = TempoDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = TempoDarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = TempoDarkSurfaceSunken,
    surfaceContainerLow = TempoSurfaceChip,
    surfaceContainer = TempoSurfaceCard,
    surfaceContainerHigh = TempoDarkSurfaceElevated,
    surfaceContainerHighest = TempoSurfaceRaised,
    surfaceDim = TempoDarkSurfaceSunken,
    surfaceBright = TempoDarkSurfaceElevated,
    inverseSurface = TextPrimary,
    inverseOnSurface = TempoDarkBackground,
    inversePrimary = TempoPrimaryDeep,
    surfaceTint = TempoPrimary,

    outline = GlassBorderMedium,
    outlineVariant = GlassBorderSoft
)

private val LightColorScheme = DarkColorScheme

@Composable
fun TempoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled to enforce Premium Brand
    content: @Composable () -> Unit
) {
    // Force Dark Theme for Premium Feel
    val colorScheme = DarkColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false // Always light text (for dark bg)
            insetsController.isAppearanceLightNavigationBars = false // Always light icons (for dark bg)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
