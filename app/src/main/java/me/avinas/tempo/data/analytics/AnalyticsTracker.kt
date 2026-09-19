package me.avinas.tempo.data.analytics

/**
 * Single entry point for anonymous app-health reporting.
 *
 * The implementation is chosen once in `AnalyticsModule`, so call sites never change when
 * the backend does (or when analytics are off entirely).
 *
 * Contract:
 *  - [track] must never throw and must never block the calling thread.
 *  - [flush] must never throw; failures leave events queued for a later attempt.
 *  - [purge] must delete anything buffered but not yet transmitted, so opting out takes
 *    effect immediately rather than at the next flush.
 */
interface AnalyticsTracker {

    fun track(event: AnalyticsEvent)

    /** Best-effort send of anything buffered. Safe to call from a background worker. */
    suspend fun flush()

    /** Drops all buffered-but-unsent events. Called when the user opts out. */
    suspend fun purge()
}
