package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsGateTest {

    // --- Consent gate -------------------------------------------------------------------

    @Test
    fun `collection is blocked until the notice has been shown`() {
        assertFalse(AnalyticsGate.isCollectionAllowed(enabled = true, disclosureSeen = false))
    }

    /**
     * The fail-safe that makes a default-on model honest: if the Home notice never renders
     * (unusual navigation state, or a crash before Home), the preference being unset must
     * mean "do not collect" rather than "collect silently".
     */
    @Test
    fun `collection is blocked when the notice flag is unset`() {
        assertFalse(AnalyticsGate.isCollectionAllowed(enabled = true, disclosureSeen = null))
    }

    @Test
    fun `collection is allowed once the user is opted in and has seen the notice`() {
        assertTrue(AnalyticsGate.isCollectionAllowed(enabled = true, disclosureSeen = true))
    }

    @Test
    fun `collection is blocked after the user opts out`() {
        assertFalse(AnalyticsGate.isCollectionAllowed(enabled = false, disclosureSeen = true))
    }

    @Test
    fun `an unset preference follows the shipped default`() {
        assertEquals(
            AnalyticsDefaults.ENABLED,
            AnalyticsGate.isCollectionAllowed(enabled = null, disclosureSeen = true)
        )
    }

    // --- Build gate ---------------------------------------------------------------------

    /**
     * local.properties is not committed, so a build from source has a blank key and must
     * never report — which is what keeps the project's "auditable, no tracking" promise
     * true for anyone compiling it themselves.
     */
    @Test
    fun `a build without a key is not configured`() {
        assertFalse(AnalyticsGate.isBuildConfigured(appKey = "", isDebug = false))
    }

    @Test
    fun `a debug build is never configured even with a key`() {
        assertFalse(AnalyticsGate.isBuildConfigured(appKey = "A-EU-0000000000", isDebug = true))
    }

    @Test
    fun `a release build with a key is configured`() {
        assertTrue(AnalyticsGate.isBuildConfigured(appKey = "A-EU-0000000000", isDebug = false))
    }

    // --- Bucketing ----------------------------------------------------------------------

    @Test
    fun `counts are bucketed and the boundaries are stable`() {
        assertEquals("0", AnalyticsBucket.count(0))
        assertEquals("0", AnalyticsBucket.count(-3))
        assertEquals("1-5", AnalyticsBucket.count(1))
        assertEquals("1-5", AnalyticsBucket.count(5))
        assertEquals("6-25", AnalyticsBucket.count(6))
        assertEquals("6-25", AnalyticsBucket.count(25))
        assertEquals("26-100", AnalyticsBucket.count(26))
        assertEquals("26-100", AnalyticsBucket.count(100))
        assertEquals("100+", AnalyticsBucket.count(101))
    }

    @Test
    fun `durations are bucketed and the boundaries are stable`() {
        assertEquals("<1s", AnalyticsBucket.duration(0L))
        assertEquals("<1s", AnalyticsBucket.duration(999L))
        assertEquals("1-5s", AnalyticsBucket.duration(1_000L))
        assertEquals("1-5s", AnalyticsBucket.duration(4_999L))
        assertEquals("5-30s", AnalyticsBucket.duration(5_000L))
        assertEquals("5-30s", AnalyticsBucket.duration(29_999L))
        assertEquals("30s+", AnalyticsBucket.duration(30_000L))
    }

    @Test
    fun `byte sizes are bucketed`() {
        assertEquals("0", AnalyticsBucket.bytes(0L))
        assertEquals("<1MB", AnalyticsBucket.bytes(512L * 1024))
        assertEquals("1-10MB", AnalyticsBucket.bytes(5L * 1024 * 1024))
        assertEquals("10-100MB", AnalyticsBucket.bytes(50L * 1024 * 1024))
        assertEquals("100MB+", AnalyticsBucket.bytes(500L * 1024 * 1024))
    }
}
