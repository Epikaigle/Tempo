package me.avinas.tempo.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────
// App color palette — anchor hue 168 (teal). Neutrals tinted
// toward anchor; no pure black/white; one accent.
// ──────────────────────────────────────────────────────────────

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

val PrimaryPurple = Color(0xFF8B5CF6)
val SecondaryPurple = Color(0xFF7C3AED)
val TertiaryPurple = Color(0xFF5B21B6)
val AccentPurple = Color(0xFFA78BFA)

// ── Studio surfaces — neutral near-black, not teal-tinted ──
val TempoDarkBackground = Color(0xFF0A0E0E)
val TempoDarkSurface = Color(0xFF101414)
val TempoDarkSurfaceElevated = Color(0xFF141818)
val TempoDarkSurfaceSunken = Color(0xFF080A0A)

val TempoPrimary = Color(0xFF2FDBB8)
val TempoSecondary = Color(0xFF1FA88C)
val TempoAccent = Color(0xFF5EEAD4)
val TempoAccentBright = Color(0xFF6DE8CC)
val TempoPrimaryMuted = Color(0xFF1FA88C)
val TempoPrimaryDeep = Color(0xFF167A66)
val TempoPrimaryDim = Color(0xFF1A9A80)
val TempoError = Color(0xFFF43F5E)
val TempoErrorAlt = Color(0xFFEF4444)
val TempoErrorSoft = Color(0xFFFCA5A5)
val TempoErrorDeep = Color(0xFFB91C1C)
val TempoSuccess = Color(0xFF22C55E)
val TempoSuccessBright = Color(0xFF4ADE80)
val TempoSuccessDeep = Color(0xFF10B981)
val TempoWarning = Color(0xFFF59E0B)
val TempoWarningBright = Color(0xFFFBBF24)
val TempoWarningSoft = Color(0xFFFDBA74)
val TempoInfo = Color(0xFF3B82F6)
val TempoInfoSoft = Color(0xFF93C5FD)
val TempoCyan = Color(0xFF06B6D4)
val TempoSky = Color(0xFF0EA5E9)

val TextPrimary = Color(0xFFE6EAEA)
val TextSecondary = Color(0xFF9AA2A2)
val TextTertiary = Color(0xFF707878)
val TextQuaternary = Color(0xFF414848)
val Divider = Color(0xFF1C2020)
val TextOnAccent = Color(0xFF0A0E0E)

val StatPlays = TempoError
val StatTime = TempoInfo
val StatTracks = TempoPrimary
val StatAlbums = TempoWarning

val SpotifyGreen = Color(0xFF1DB954)
val LastFmRed = Color(0xFFFC3C44)

val GoldPrimary = Color(0xFFFFD700)
val GoldLight = Color(0xFFFBBF24)
val GoldDark = Color(0xFFF59E0B)
val SilverLight = Color(0xFFE2E8F0)
val SilverDark = Color(0xFF94A3B8)
val BronzeLight = Color(0xFFCD7F32)
val BronzeDark = Color(0xFFB45309)

val MeshGradient1 = Color(0xFF141818)
val MeshGradient2 = Color(0xFF0A0E0E)
val MeshGradient3 = Color(0xFF111616)

// Legacy support — keep symbol so older callsites compile
val TempoRed = Color(0xFF2FDBB8)
val TempoBackground = TempoDarkBackground
val TempoSurface = TempoDarkSurface
val TempoSurfaceElevated = TempoDarkSurfaceElevated
val TempoSurfaceSunken = TempoDarkSurfaceSunken
val TempoSurfaceChip = Color(0xFF101414)
val TempoSurfacePopup = Color(0xFF131717)
val TempoSurfaceCard = Color(0xFF111515)
val TempoSurfaceDialog = Color(0xFF151919)
val TempoSurfaceRaised = Color(0xFF181C1C)
val TempoWarningDeep = Color(0xFFD97706)
val TempoSecondaryAlias = TempoSecondary

val GlassWhite = Color(0x12E6EAEA)
val SubtlerGlass = Color(0x0DE6EAEA)

val NeonRed = Color(0xFF2FDBB8)
val ElectricBlue = Color(0xFF3B82F6)
val GoldenAmber = Color(0xFFF59E0B)

val SurfaceVariantDark = Color(0xFF49454F)
val OnSurfaceVariantDark = Color(0xFFCAC4D0)
val OutlineDark = Color(0xFF938F99)

val SurfaceVariantLight = Color(0xFFE7E0EC)
val OnSurfaceVariantLight = Color(0xFF49454F)
val OutlineLight = Color(0xFF79747E)

// ── Insight palette — token discipline (no mid-render hex) ──
val InsightMood = Color(0xFF8B5CF6)
val InsightPeakTime = Color(0xFFF59E0B)
val InsightBinge = Color(0xFFEC4899)
val InsightDiscovery = Color(0xFF10B981)
val InsightEnergy = Color(0xFFEF4444)
val InsightDanceability = Color(0xFFA855F7)
val InsightTempo = Color(0xFF06B6D4)
val InsightAcousticness = Color(0xFF22C55E)
val InsightStreak = Color(0xFFF97316)
val InsightGenre = Color(0xFFE11D48)
val InsightEngagement = Color(0xFFDB2777)

val VibeEnergyLow = Color(0xFF1E103C)
val VibeEnergyHigh = Color(0xFF5B21B6)
val VibeValenceLow = Color(0xFF0F172A)
val VibeValenceHigh = Color(0xFF4C1D95)
val LevelRingSweepStart = Color(0xFFEC4899)
val LevelRingSweepMid = Color(0xFFA855F7)
val LevelRingSweepEnd = Color(0xFF6366F1)

// Pill surface — warm frost, not pure white; tints toward paper
val PillSurface = Color(0xFFF6FAF8)
val PillBorder = Color(0xFFE2E8F0)
val PillInnerSurface = Color(0xFFF1F5F9)
val PillTextPrimary = Color(0xFF111827)

// ──────────────────────────────────────────────────────────────
// Glass system — neutral studio (value = depth, teal = accent ≤7%)
// ──────────────────────────────────────────────────────────────
val GlassTintTeal = Color(0xFF101414)
val GlassTintTealDeep = Color(0xFF0A0E0E)
val GlassFrostSoft = Color(0x0FE6EAEA)
val GlassFrostMedium = Color(0x12E6EAEA)
val GlassFrostStrong = Color(0x1AE6EAEA)
val GlassBorderSoft = Color(0x0FE6EAEA)
val GlassBorderMedium = Color(0x14E6EAEA)
val GlassBorderStrong = Color(0x1FE6EAEA)
val GlassBorderTint = Color(0x142FDBB8)
// Frost for glass cards sitting on the blurred art canvas — over the darkened
// canvas the standard 7% frost reads as invisible; ~18% keeps card boundaries
// real (measured: card ~65 vs canvas ~30).
val GlassCardOnArt = Color(0x2EE6EAEA)
val GlassHighlightTop = Color(0x0CE6EAEA)
val GlassShadowTeal = Color(0x4D080A0A)
