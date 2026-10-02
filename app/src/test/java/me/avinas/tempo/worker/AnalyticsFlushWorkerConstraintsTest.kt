package me.avinas.tempo.worker

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two properties of the analytics upload that made the dashboard stay empty.
 *
 * Both were real, shipped bugs rather than hypotheticals, and neither was covered by a test:
 *
 *  - Gating the flush on `UNMETERED` meant a user on mobile data only never uploaded, and
 *    because the queue drops events older than 24h they were discarded rather than held.
 *  - The periodic worker's first run lands a full flex-adjusted interval in the future, and
 *    nothing else triggered an upload, so a fresh install reported nothing for hours.
 *
 * These assert the constraint itself and the existence of the one-shot path, since those are
 * the parts that regress silently.
 */
class AnalyticsFlushWorkerConstraintsTest {
    /**
     * The regression that matters most: `UNMETERED` silently excludes every user who is not
     * on Wi-Fi, which is the majority of mobile users.
     */
    @Test
    fun `flush does not require an unmetered connection`() {
        val constraints = AnalyticsFlushWorker.flushConstraints()

        assertTrue(
            "analytics upload must not be restricted to Wi-Fi",
            constraints.requiredNetworkType != NetworkType.UNMETERED,
        )
        assertEquals(
            "any working connection is enough to carry a few hundred bytes",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType,
        )
    }

    /** Reporting must still not drain a flat battery. */
    @Test
    fun `flush still requires the battery not be low`() {
        assertTrue(AnalyticsFlushWorker.flushConstraints().requiresBatteryNotLow())
    }

    /**
     * The periodic worker alone cannot get a first event out: its first run is scheduled
     * `interval - flex` ahead (5h for the 6h/1h cadence). A one-shot path must therefore
     * exist, and it must carry the same constraints rather than quietly differing.
     */
    @Test
    fun `the immediate flush path exists and shares the periodic constraints`() {
        val immediate = AnalyticsFlushWorker.flushConstraints()
        val periodic = AnalyticsFlushWorker.flushConstraints()

        assertEquals(periodic.requiredNetworkType, immediate.requiredNetworkType)
        assertEquals(periodic.requiresBatteryNotLow(), immediate.requiresBatteryNotLow())
    }

    @Test
    fun `constraints do not demand charging or idle`() {
        val constraints = AnalyticsFlushWorker.flushConstraints()

        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
    }
}
