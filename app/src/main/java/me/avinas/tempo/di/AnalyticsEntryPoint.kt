package me.avinas.tempo.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.analytics.AnalyticsTracker

/**
 * Reaches [AnalyticsTracker] from places that have no injection point.
 *
 * Sharing happens inside plain `@Composable` functions and a stateless utility object, none of
 * which can take a constructor dependency. Rather than thread a tracker through every
 * composable — or report from a screen that cannot tell which surface the user shared from —
 * the few call sites that need it resolve the tracker through this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnalyticsEntryPoint {
    fun analyticsTracker(): AnalyticsTracker
}
