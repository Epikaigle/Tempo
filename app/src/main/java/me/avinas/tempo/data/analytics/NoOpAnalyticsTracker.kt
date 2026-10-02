package me.avinas.tempo.data.analytics

/**
 * Discards everything. Selected when the build cannot report at all (no Aptabase key, or a
 * debug build), and whenever the user is not opted in.
 *
 * A class rather than an object so the DI graph and tests can distinguish instances.
 */
class NoOpAnalyticsTracker : AnalyticsTracker {

    override fun track(event: AnalyticsEvent) = Unit

    override suspend fun flush() = Unit

    override suspend fun purge() = Unit
}
