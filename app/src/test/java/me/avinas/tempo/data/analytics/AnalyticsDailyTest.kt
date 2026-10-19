package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The daily reporting cadence is the thing every DAU/MAU figure depends on, so the rules are
 * pinned here rather than left to the worker's integration behaviour.
 */
class AnalyticsDailyTest {

    private val utc = ZoneId.of("UTC")

    /**
     * The single most consequential rule: a day belongs to the device's own timezone.
     *
     * Filing by UTC would move a listen at 23:30 local into the next date for anyone east of
     * Greenwich, which quietly shifts a slice of every user's activity into the wrong day and
     * corrupts the cohort boundaries retention is built on.
     */
    @Test
    fun `a day is a local calendar day, not a UTC one`() {
        val lateEvening = Instant.parse("2026-10-16T23:30:00Z").toEpochMilli()
        val berlin = ZoneId.of("Europe/Berlin")

        assertEquals(
            LocalDate.of(2026, 10, 16).toEpochDay(),
            AnalyticsDaily.dayOf(lateEvening, utc),
        )
        assertEquals(
            LocalDate.of(2026, 10, 17).toEpochDay(),
            AnalyticsDaily.dayOf(lateEvening, berlin),
        )
    }

    @Test
    fun `a day starts at local midnight`() {
        val day = LocalDate.of(2026, 10, 16).toEpochDay()

        assertEquals(
            Instant.parse("2026-10-16T00:00:00Z").toEpochMilli(),
            AnalyticsDaily.startOfDayMillis(day, utc),
        )
    }

    /** Consecutive days must tile without a gap or an overlap, or a day's listens are lost. */
    @Test
    fun `consecutive day bounds tile exactly`() {
        val day = LocalDate.of(2026, 10, 16).toEpochDay()

        assertEquals(
            AnalyticsDaily.startOfDayMillis(day + 1, utc),
            AnalyticsDaily.startOfDayMillis(day, utc) + 24L * 60L * 60L * 1000L,
        )
    }

    @Test
    fun `presence is due once per calendar day`() {
        val today = 20_000L

        assertTrue(AnalyticsDaily.shouldReportPresence(lastReportedDay = null, today = today))
        assertTrue(AnalyticsDaily.shouldReportPresence(lastReportedDay = today - 1, today = today))
        assertFalse(AnalyticsDaily.shouldReportPresence(lastReportedDay = today, today = today))
    }

    @Test
    fun `a dormant day reports no usage`() {
        assertFalse(AnalyticsDaily.shouldReportUsage(listens = 0, uiOpened = false))
    }

    /** Browsing without playing anything is still using Tempo. */
    @Test
    fun `opening the app counts as use with nothing played`() {
        assertTrue(AnalyticsDaily.shouldReportUsage(listens = 0, uiOpened = true))
    }

    /** Background tracking is still using Tempo, with the app never opened. */
    @Test
    fun `listening counts as use without opening the app`() {
        assertTrue(AnalyticsDaily.shouldReportUsage(listens = 4, uiOpened = false))
    }

    @Test
    fun `a day already sent is never sent again`() {
        val day = 20_000L

        assertTrue(AnalyticsDaily.shouldReportUsageFor(day, lastReportedDay = null))
        assertTrue(AnalyticsDaily.shouldReportUsageFor(day, lastReportedDay = day - 1))
        assertFalse(AnalyticsDaily.shouldReportUsageFor(day, lastReportedDay = day))
        assertFalse(AnalyticsDaily.shouldReportUsageFor(day, lastReportedDay = day + 1))
    }

    @Test
    fun `only a finished day can be reported`() {
        val today = 20_000L

        assertEquals(today - 1, AnalyticsDaily.lastCompleteDay(today))
    }
}
