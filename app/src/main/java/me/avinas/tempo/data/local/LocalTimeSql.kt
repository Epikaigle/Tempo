package me.avinas.tempo.data.local

/**
 * SQL fragments that bucket a `listening_events` row by the local wall clock of the place the
 * play happened, instead of by whatever timezone the phone is in at query time.
 *
 * Every streak, timing badge and day/hour aggregate used SQLite's `'localtime'` modifier, which
 * re-renders *all* history through the device's current offset. A trip therefore rewrote the
 * past: a UK evening play landed in 00:00-05:00 Thailand time, listening days collapsed, a
 * 110-day streak read as 40, and night-owl badges appeared on arrival and vanished on the way
 * home. `listening_events.tz_offset_minutes` (schema 57) stores the offset that was in effect
 * when the play was recorded, so a row renders the same wherever the phone is.
 *
 * A row written without an offset -- anything predating the column, or a path that does not set
 * one -- falls back to the device's current offset, which is exactly what `'localtime'` resolved
 * to before. The `COALESCE` is deliberate: without it a NULL offset would poison the arithmetic
 * and silently drop the row out of its day bucket rather than merely approximating it.
 *
 * Replacing only the time argument keeps every `WHERE timestamp >= :startTime` filter intact, so
 * query plans and indices are unchanged; the arithmetic is a constant-time operation on a column
 * that is already being read.
 */
object LocalTimeSql {
    /** Device offset in seconds, derived the same way the `'localtime'` modifier derives it. */
    private const val DEVICE_OFFSET_SECONDS =
        "(strftime('%s', 'now', 'localtime') - strftime('%s', 'now'))"

    /** Offset of a `listening_events` row as UTC seconds: stored value, or the device's own. */
    const val OFFSET_SECONDS = "COALESCE(tz_offset_minutes, $DEVICE_OFFSET_SECONDS / 60) * 60"

    /** `listening_events.timestamp` shifted into the offset the play was recorded in. */
    const val LOCAL_EPOCH = "timestamp / 1000 + $OFFSET_SECONDS"
    const val LOCAL_EPOCH_LE = "le.timestamp / 1000 + $OFFSET_SECONDS"

    /** Local wall-clock rendering of an event, for the two table aliases in use. */
    const val LOCAL_DATETIME = "datetime($LOCAL_EPOCH, 'unixepoch')"
    const val LOCAL_DATETIME_LE = "datetime($LOCAL_EPOCH_LE, 'unixepoch')"

    /** Hour of day (0-23) in the zone the play was recorded in. */
    const val LOCAL_HOUR = "CAST(strftime('%H', $LOCAL_EPOCH, 'unixepoch') AS INTEGER)"

    /** Calendar day (YYYY-MM-DD) in the zone the play was recorded in. */
    const val LOCAL_DAY = "date($LOCAL_EPOCH, 'unixepoch')"

    /**
     * Month of first listen in the discovery-trend subquery.
     *
     * `MIN(first_listen)` picks the row whose stored offset describes that first play; SQLite's
     * bare-column rule takes it from the same row the aggregate came from.
     */
    const val LOCAL_DISCOVERY_MONTH =
        "strftime('%Y-%m', datetime(min_timestamps.first_listen / 1000 + " +
            "COALESCE(min_timestamps.tz_offset_minutes, $DEVICE_OFFSET_SECONDS / 60) * 60, 'unixepoch'))"
}
