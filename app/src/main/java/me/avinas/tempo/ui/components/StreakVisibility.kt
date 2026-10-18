package me.avinas.tempo.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the user has asked not to see listening streaks.
 *
 * Streak surfaces are scattered — the profile masthead, home insight cards, a Spotlight page,
 * artist milestones and shared cards — and most of them are leaf composables that would otherwise
 * each need the preference threaded down through their callers. Providing it once at the
 * navigation root keeps that decision in a single place.
 *
 * Read this instead of the stored preference so a toggle takes effect on the next recomposition:
 *
 * ```
 * if (!LocalHideStreaks.current) { StreakStat(...) }
 * ```
 *
 * Defaults to false, so previews and captures that compose a screen in isolation keep streaks.
 */
val LocalHideStreaks = staticCompositionLocalOf { false }
