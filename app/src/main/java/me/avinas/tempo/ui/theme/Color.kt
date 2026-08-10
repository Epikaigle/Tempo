package me.avinas.tempo.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────
// Tempo design tokens — single source of truth for every color.
// No inline hex outside this file. Surfaces are tinted toward the
// teal anchor hue (never pure black); neutrals carry a faint teal
// cast so they sit cohesively on the tinted-dark palette.
// ──────────────────────────────────────────────────────────────

// ── Surface ramp (teal-tinted near-blacks, OLED-friendly) ──
val TempoBackground      = Color(0xFF03100D)
val TempoSurface         = Color(0xFF071814)
val TempoSurfaceElevated = Color(0xFF0C211B)
val TempoSurfaceDialog   = Color(0xFF0E1E1A)
val TempoSurfaceSunken   = Color(0xFF061311)
val TempoSurfaceChip     = Color(0xFF0A1714)
val TempoSurfacePopup    = Color(0xFF0C1A17)
val TempoSurfaceCard     = Color(0xFF0B1A16)
val TempoSurfaceRaised   = Color(0xFF102620)

// ── Accent ramp (the teal brand) ──
val TempoPrimary       = Color(0xFF2FDBB8)
val TempoPrimaryMuted  = Color(0xFF1FA88C)
val TempoPrimaryDeep   = Color(0xFF167A66)
val TempoPrimaryDim    = Color(0xFF1A9A80)
val TempoAccent        = Color(0xFF5EEAD4)
val TempoAccentBright  = Color(0xFF6DE8CC)

// ── Text ramp (teal-tinted neutrals) ──
val TextPrimary     = Color(0xFFEFF4F2)
val TextSecondary   = Color(0xFFA8B8B2)
val TextTertiary    = Color(0xFF6B7B76)
val TextQuaternary  = Color(0xFF3A4A45)
val Divider         = Color(0xFF1A2A26)
val TextOnAccent    = Color(0xFF03100D)

// ── Semantic: success (tracking active, connected) ──
val TempoSuccess        = Color(0xFF22C55E)
val TempoSuccessBright  = Color(0xFF4ADE80)
val TempoSuccessDeep    = Color(0xFF10B981)

// ── Semantic: error (failures, destructive) ──
val TempoError      = Color(0xFFEF4444)
val TempoErrorAlt   = Color(0xFFF43F5E)
val TempoErrorSoft  = Color(0xFFFCA5A5)
val TempoErrorDeep  = Color(0xFFB91C1C)

// ── Semantic: warning (paused, caution) ──
val TempoWarning        = Color(0xFFF59E0B)
val TempoWarningBright  = Color(0xFFFBBF24)
val TempoWarningSoft    = Color(0xFFFDBA74)
val TempoWarningDeep    = Color(0xFFD97706)

// ── Semantic: info (listening time, secondary data) ──
val TempoInfo    = Color(0xFF3B82F6)
val TempoInfoSoft = Color(0xFF93C5FD)
val TempoCyan    = Color(0xFF06B6D4)
val TempoSky     = Color(0xFF0EA5E9)

// ── Stat-type tints (used on icons/labels, NOT on values) ──
val StatPlays   = TempoError
val StatTime    = TempoInfo
val StatTracks  = TempoPrimary
val StatAlbums  = TempoWarning

// ── External service brand colors (music links) ──
val SpotifyGreen = Color(0xFF1DB954)
val LastFmRed    = Color(0xFFFC3C44)

// ── Medal colors (share-card rank — semantic, not decorative) ──
val GoldPrimary    = Color(0xFFFFD700)
val GoldLight      = Color(0xFFFBBF24)
val GoldDark       = Color(0xFFF59E0B)
val SilverLight    = Color(0xFFE2E8F0)
val SilverDark     = Color(0xFF94A3B8)
val BronzeLight    = Color(0xFFCD7F32)
val BronzeDark     = Color(0xFFB45309)

// ── Temporary aliases (removed after all callsites migrate to new tokens) ──
val TempoSecondary = TempoPrimaryMuted
val GlassWhite = TextPrimary.copy(alpha = 0.08f)
val SubtlerGlass = TextPrimary.copy(alpha = 0.05f)
val MeshGradient1 = TempoSurfaceElevated
val MeshGradient2 = TempoSurface
val MeshGradient3 = TempoSurfaceElevated
