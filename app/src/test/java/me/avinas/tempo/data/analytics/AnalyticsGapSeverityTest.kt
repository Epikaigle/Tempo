package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Gap severity is the field that was silently useless before: it reused the generic duration
 * buckets, whose largest value is `30s+`, while the shortest gap the health worker can detect
 * is thirty minutes. Every value it ever produced was therefore identical.
 */
class AnalyticsGapSeverityTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `an unmeasurable gap is reported as unknown`() {
        assertEquals(TrackingGapSeverity.UNKNOWN, AnalyticsBucket.gap(null))
    }

    @Test
    fun `each boundary falls into the bucket it names`() {
        assertEquals(TrackingGapSeverity.UNDER_30M, AnalyticsBucket.gap(0L))
        assertEquals(TrackingGapSeverity.UNDER_30M, AnalyticsBucket.gap(29 * minute))

        assertEquals(TrackingGapSeverity.UNDER_2H, AnalyticsBucket.gap(30 * minute))
        assertEquals(TrackingGapSeverity.UNDER_2H, AnalyticsBucket.gap(2 * hour - 1))

        assertEquals(TrackingGapSeverity.UNDER_6H, AnalyticsBucket.gap(2 * hour))
        assertEquals(TrackingGapSeverity.UNDER_6H, AnalyticsBucket.gap(6 * hour - 1))

        assertEquals(TrackingGapSeverity.UNDER_24H, AnalyticsBucket.gap(6 * hour))
        assertEquals(TrackingGapSeverity.UNDER_24H, AnalyticsBucket.gap(24 * hour - 1))

        assertEquals(TrackingGapSeverity.OVER_24H, AnalyticsBucket.gap(24 * hour))
        assertEquals(TrackingGapSeverity.OVER_24H, AnalyticsBucket.gap(72 * hour))
    }

    /**
     * The regression this whole change exists for: the shortest gap the health worker can
     * possibly notice and a three-day outage must not report the same value.
     */
    @Test
    fun `the shortest detectable gap and a three-day outage are distinguishable`() {
        val shortestDetectable = 30 * minute

        assertNotEquals(
            AnalyticsBucket.gap(shortestDetectable),
            AnalyticsBucket.gap(72 * hour),
        )
    }
}
