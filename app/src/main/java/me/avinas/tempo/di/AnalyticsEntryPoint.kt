package me.avinas.tempo.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.analytics.AnalyticsHealthReporter
import me.avinas.tempo.data.analytics.AnalyticsTracker

/**
 * Reaches analytics collaborators from places that have no injection point.
 *
 * Sharing happens inside plain `@Composable` functions and a stateless utility object, and the
 * boot receiver is a `BroadcastReceiver` — none of which can take a constructor dependency.
 * Rather than thread a tracker through every composable, the few call sites that need one
 * resolve it through this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnalyticsEntryPoint {
    fun analyticsTracker(): AnalyticsTracker

    /**
     * Used by the boot receiver, which needs the once-a-day throttle rather than the raw
     * tracker: a device that re-enables its listener on every boot would otherwise report on
     * every boot.
     */
    fun analyticsHealthReporter(): AnalyticsHealthReporter
}
