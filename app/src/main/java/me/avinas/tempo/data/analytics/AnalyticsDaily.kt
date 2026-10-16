package me.avinas.tempo.data.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure rules for the daily reporting cadence, kept free of Android and storage so they are
 * unit-testable — the same split as [AnalyticsQueue] and [AnalyticsGate].
 *
 * A "day" here is an epoch day in the device's own zone, not a UTC day. The alternative
 * would file a listen at 23:00 local under the following date for anyone east of UTC, and
 * the day boundary is the one thing these rules exist to get right.
 */
object AnalyticsDaily {

    /** The calendar day [epochMillis] falls on, as an epoch day. */
    fun dayOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** The instant a calendar day begins, for use as a query bound. */
    fun startOfDayMillis(day: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Whether [day] has already been reported, for any once-a-day event.
     *
     * Shared by the presence marker and the tracking-health report so both mean the same
     * thing by "once a day", and so a new once-daily event cannot accidentally invent its own
     * subtly different rule.
     */
    fun isUnreported(lastReportedDay: Long?, day: Long): Boolean =
        lastReportedDay == null || lastReportedDay != day

    /**
     * The presence marker is due whenever today's has not been sent yet.
     *
     * A device whose clock jumps backwards would look like a new day; that is accepted
     * rather than guarded, because the cost is one extra event and the alternative is
     * suppressing a real day's presence.
     */
    fun shouldReportPresence(lastReportedDay: Long?, today: Long): Boolean =
        isUnreported(lastReportedDay, today)

    /**
     * Usage is worth a row when the user did one of the two things that count as using Tempo:
     * listened to something, or opened the app.
     *
     * A day with neither is a dormant install. Those stay silent rather than sending a run of
     * zeroes, which is both the original intent of this aggregate and the reason it cannot be
     * the DAU marker on its own — a dormant install and a broken reporter look identical here.
     */
    fun shouldReportUsage(listens: Int, uiOpened: Boolean): Boolean = listens > 0 || uiOpened

    /** Usage for a day can only be reported once that day is over. */
    fun lastCompleteDay(today: Long): Long = today - 1

    /**
     * Guards against re-reporting a day that was already sent.
     *
     * The worker runs on a periodic schedule that WorkManager may defer, so it can fire twice
     * in quick succession after a gap. Without this, a deferred run would double-count a day's
     * listening in every dashboard that sums the figure.
     */
    fun shouldReportUsageFor(day: Long, lastReportedDay: Long?): Boolean =
        lastReportedDay == null || day > lastReportedDay
}
