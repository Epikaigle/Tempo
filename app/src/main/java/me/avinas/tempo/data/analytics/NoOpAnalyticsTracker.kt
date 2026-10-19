package me.avinas.tempo.data.analytics

/**
 * Discards everything. Selected when the build cannot report at all (no analytics endpoint,
 * or a debug build), and whenever the user is not opted in.
 *
 * A class rather than an object so the DI graph and tests can distinguish instances.
 */
class NoOpAnalyticsTracker : AnalyticsTracker {

    override fun track(event: AnalyticsEvent) = Unit

    /** Always false: this is the implementation bound when nothing can ever be sent. */
    override suspend fun canReport(): Boolean = false

    override suspend fun flush() = Unit

    override suspend fun purge() = Unit
}
