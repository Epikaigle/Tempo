package me.avinas.tempo.data.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Home notice's lifecycle, which is a privacy contract rather than a UI detail.
 *
 * The original bug: the card marked the notice as seen on first composition, and its
 * visibility was driven by that same flag — so it removed itself in the frame it appeared.
 * The user could not read it and could not reach the opt-out sitting beside it, while the
 * collection gate had already opened.
 *
 * Notice *rendering* and notice *dismissal* are now separate, and visibility follows the
 * latter. These tests pin that separation.
 */
class AnalyticsNoticeVisibilityTest {
    @Test
    fun `a configured build shows the notice until it is dismissed`() {
        assertTrue(
            AnalyticsGate.isNoticeVisible(isConfigured = true, noticeDismissed = false),
        )
    }

    /** The regression: rendering the notice must not be what hides it. */
    @Test
    fun `the notice stays visible while it is merely unacknowledged`() {
        // disclosureSeen becomes true the moment the card composes; visibility must not
        // follow it, or the card vanishes before it can be read.
        assertTrue(AnalyticsGate.isNoticeVisible(isConfigured = true, noticeDismissed = false))
    }

    @Test
    fun `acknowledging the notice hides it`() {
        assertFalse(
            AnalyticsGate.isNoticeVisible(isConfigured = true, noticeDismissed = true),
        )
    }

    /** Builds that cannot report must never show the control — a switch for nothing. */
    @Test
    fun `an unconfigured build never shows the notice`() {
        assertFalse(AnalyticsGate.isNoticeVisible(isConfigured = false, noticeDismissed = false))
        assertFalse(AnalyticsGate.isNoticeVisible(isConfigured = false, noticeDismissed = true))
    }

    /**
     * The two flags are independent by design: rendering opens the gate, dismissing closes
     * the card. Collapsing them is exactly the bug this file exists to prevent.
     */
    @Test
    fun `rendering the notice opens collection before the user dismisses it`() {
        assertTrue(AnalyticsGate.isCollectionAllowed(enabled = true, disclosureSeen = true))
        assertTrue(AnalyticsGate.isNoticeVisible(isConfigured = true, noticeDismissed = false))
    }
}
