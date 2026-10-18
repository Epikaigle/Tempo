package me.avinas.tempo.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads the device's clock offset and turns a stored per-event offset back into a zone.
 *
 * Everything here is arithmetic on timezone rules the system has already resolved: no location
 * permission, no `ACTION_TIMEZONE_CHANGED` receiver, no alarm, no wake lock, no scheduled work.
 * A zone-rules lookup is a table search, so it costs the same as formatting a date and is safe to
 * call once per recorded play -- which is the only time it runs. Nothing polls.
 */
object DeviceTimeZone {
    /**
     * UTC offset, in minutes, that the device clock showed at [epochMillis].
     *
     * This is what SQLite's `'localtime'` modifier used to resolve per row; capturing it at write
     * time freezes the answer so a later trip cannot re-render the play.
     */
    fun offsetMinutesAt(
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds / 60

    /**
     * The [ZoneOffset] for a stored offset, or null when the row has none.
     *
     * Out-of-range values (corrupt or hand-edited rows) are treated as absent rather than allowed
     * to throw inside date formatting.
     */
    fun zoneOffsetOf(offsetMinutes: Int?): ZoneOffset? =
        offsetMinutes?.let { runCatching { ZoneOffset.ofTotalSeconds(it * 60) }.getOrNull() }

    /**
     * The local date *now* in the zone a play was recorded in.
     *
     * Streaks are counted from this day, not the phone's day, so landing in a new timezone neither
     * skips a day (flying east) nor drops the newest one (flying west). If listening stops
     * entirely the anchor still advances with the clock, so the streak expires exactly as it
     * always did.
     */
    fun todayIn(offsetMinutes: Int?): LocalDate {
        val zone = zoneOffsetOf(offsetMinutes) ?: return LocalDate.now()
        return LocalDate.now(zone)
    }
}
