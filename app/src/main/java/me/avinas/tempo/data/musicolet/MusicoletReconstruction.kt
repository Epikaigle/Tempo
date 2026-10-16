package me.avinas.tempo.data.musicolet

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Decides which plays to create from a parsed Musicolet backup, and when.
 *
 * Musicolet's coverage is layered, and the layers overlap: a month's plays are also
 * inside that month's year, and inside each of its weeks. Summing every file would
 * multiply the listener's history, so exactly one layer is used as the source of
 * truth per song:
 *
 *  1. **Months** (`PCs_M_<year>.<month>`) — the finest granularity that never
 *     overlaps, and therefore the spine of the reconstruction.
 *  2. **Years** (`PCs_Y_<year>`, and `PCs_M_<year>` with no month in its name) — used
 *     only for years that have no monthly file at all.
 *  3. **Weeks** (`PCs_W_<year>.<week>`) — used only for a song that has neither
 *     month nor year coverage, so a backup holding nothing else still imports.
 *
 * `COL_LAST_PLAYED` is the one exact timestamp Musicolet stores, and it is treated as
 * one: when the song's newest period already covers that moment, the estimate for the
 * final play in that period is replaced by the real time, and no play is added; when
 * it does not (a play since the last period file was written), it is added as a play
 * of its own. `COL_NUM_PLAYED` counts plays from before Musicolet began keeping period
 * files; those are reported as [Plan.undatedPlays] and only placed when the caller
 * asks for them, in which case they are anchored to the song's earliest known period.
 */
internal object MusicoletReconstruction {
    /** Ceiling on created events across an import; bounds work on a hostile backup. */
    const val MAX_TOTAL_EVENTS = 500_000

    data class Options(
        /**
         * When true, plays Musicolet counted but never dated are placed in the song's
         * earliest known period so lifetime totals match Musicolet. When false they are
         * left out of the timeline and only reported.
         */
        val includeUndatedPlays: Boolean = false,
    )

    /** Every play to create for one song, ascending. */
    data class SongPlays(
        val songIndex: Int,
        val timestamps: List<Long>,
    )

    data class Plan(
        val songPlays: List<SongPlays>,
        /** Plays placed from period files. */
        val datedPlays: Long,
        /** Plays Musicolet counted but never dated (before its oldest period file). */
        val undatedPlays: Long,
        /** How many of [undatedPlays] were placed anyway, by anchoring them. */
        val anchoredUndatedPlays: Long,
        /** Plays that could not be placed without colliding or landing in the future. */
        val droppedPlays: Long,
        /** Plays added from an exact `COL_LAST_PLAYED` outside the reconstructed periods. */
        val lastPlayedAnchors: Long,
        /** True when [MAX_TOTAL_EVENTS] was reached and later songs were skipped. */
        val truncated: Boolean,
    ) {
        val totalPlays: Long get() = songPlays.sumOf { it.timestamps.size.toLong() }
    }

    fun plan(
        backup: MusicoletParsedBackup,
        options: Options,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val bySong = backup.periodPlays.groupBy { it.songIndex }
        val songPlays = ArrayList<SongPlays>(backup.songs.size)
        var dated = 0L
        var undated = 0L
        var anchored = 0L
        var dropped = 0L
        var lastPlayedAnchors = 0L
        var truncated = false
        var budget = MAX_TOTAL_EVENTS.toLong()

        for (songIndex in backup.songs.indices) {
            if (budget <= 0) {
                truncated = true
                break
            }
            val song = backup.songs[songIndex]
            val periods = bySong[songIndex].orEmpty()

            val chosen = choosePeriods(periods, zone)
            val bucketTotal = chosen.sumOf { it.plays }

            // Plays Musicolet counted that no period file accounts for.
            val residual = (song.totalPlays - bucketTotal).coerceAtLeast(0L)
            undated += residual
            if (options.includeUndatedPlays && residual > 0 && chosen.isNotEmpty()) {
                // Anchor to the earliest period this song has: the plays clearly
                // predate everything Musicolet still keeps, so they belong at the
                // start of its history rather than spread across it.
                chosen[0] = chosen[0].copy(plays = chosen[0].plays + residual)
                anchored += residual
            }

            val perBucket = ArrayList<MutableList<Long>>(chosen.size)
            for (period in chosen) {
                val placement =
                    MusicoletPlaySynthesizer.spread(
                        bucket = period.window,
                        requested = period.plays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        notAfterMs = nowMs,
                        zone = zone,
                    )
                dropped += placement.dropped
                perBucket.add(placement.timestamps.toMutableList())
            }

            // The one exact timestamp Musicolet keeps. A stamp ahead of this device's own
            // clock is not a play that happened, so it is not used to pin anything.
            val lastPlayed = song.lastPlayedMs?.takeIf { it <= nowMs }
            if (lastPlayed != null) {
                val owner = chosen.indexOfFirst { lastPlayed >= it.window.startMs && lastPlayed < it.window.endMs }
                if (owner >= 0) {
                    pinLatest(perBucket[owner], lastPlayed)
                } else {
                    perBucket.add(mutableListOf(lastPlayed))
                    lastPlayedAnchors++
                }
            }

            val normalized = normalize(perBucket, nowMs)
            val timestamps = normalized.first
            dropped += normalized.second
            if (timestamps.isEmpty()) continue

            val take = minOf(timestamps.size.toLong(), budget)
            if (take < timestamps.size) truncated = true
            val kept = if (take == timestamps.size.toLong()) timestamps else timestamps.subList(0, take.toInt())
            budget -= kept.size
            dated += kept.size
            songPlays.add(SongPlays(songIndex, kept.toList()))
        }

        return Plan(
            songPlays = songPlays,
            datedPlays = dated,
            undatedPlays = undated,
            anchoredUndatedPlays = anchored,
            droppedPlays = dropped,
            lastPlayedAnchors = lastPlayedAnchors,
            truncated = truncated,
        )
    }

    /** One period's window together with the plays Musicolet counted in it. */
    private data class Period(
        val window: MusicoletPlaySynthesizer.Bucket,
        val plays: Long,
    )

    /**
     * Picks exactly one coverage layer per song, finest first, and never mixes layers
     * that describe the same plays.
     */
    private fun choosePeriods(
        periods: List<MusicoletPeriodPlays>,
        zone: ZoneId,
    ): MutableList<Period> {
        val months = LinkedHashMap<YearMonth, Long>()
        val years = LinkedHashMap<Int, Long>()
        val weeks = LinkedHashMap<Pair<Int, Int>, Long>()

        for (period in periods) {
            if (period.plays <= 0) continue
            when {
                period.kind == MusicoletPeriodKind.MONTH && period.bucket != null &&
                    period.bucket in 1..12 && period.year in PLAUSIBLE_YEARS ->
                    months.merge(YearMonth.of(period.year, period.bucket), period.plays, Long::plus)

                period.kind == MusicoletPeriodKind.YEAR && period.year in PLAUSIBLE_YEARS ->
                    years.merge(period.year, period.plays, Long::plus)

                // `PCs_M_2026` with no month in the name: monthly counts for a whole
                // year, which is only placeable at year granularity.
                period.kind == MusicoletPeriodKind.MONTH && period.bucket == null &&
                    period.year in PLAUSIBLE_YEARS ->
                    years.merge(period.year, period.plays, Long::plus)

                period.kind == MusicoletPeriodKind.WEEK && period.bucket != null &&
                    period.year in PLAUSIBLE_YEARS ->
                    weeks.merge(period.year to period.bucket, period.plays, Long::plus)

                else -> Unit
            }
        }

        val chosen = mutableListOf<Period>()
        months.forEach { (month, plays) ->
            chosen.add(Period(monthWindow(month, zone), plays))
        }
        // A year file only speaks for years no month file covers; otherwise it is the
        // same plays counted twice.
        val yearsWithMonths = months.keys.map { it.year }.toSet()
        years.forEach { (year, plays) ->
            if (year !in yearsWithMonths) chosen.add(Period(yearWindow(year, zone), plays))
        }
        if (chosen.isEmpty()) {
            weeks.forEach { (yearAndWeek, plays) ->
                chosen.add(Period(weekWindow(yearAndWeek.first, yearAndWeek.second, zone), plays))
            }
        }

        chosen.sortBy { it.window.startMs }
        return chosen
    }

    /**
     * Replaces the last estimate in [timestamps] with the exact [lastPlayed] time, when
     * that keeps the sequence ascending. Otherwise the estimate already stands.
     */
    private fun pinLatest(
        timestamps: MutableList<Long>,
        lastPlayed: Long,
    ) {
        if (timestamps.isEmpty()) {
            timestamps.add(lastPlayed)
            return
        }
        val secondLast = if (timestamps.size >= 2) timestamps[timestamps.size - 2] else Long.MIN_VALUE
        if (lastPlayed > secondLast + MusicoletPlaySynthesizer.MIN_SPACING_MS) {
            timestamps[timestamps.size - 1] = lastPlayed
        }
    }

    /**
     * Flattens the per-period lists into one ascending, collision-free sequence. Two
     * plays closer together than the DAO's same-source tolerance would silently collapse
     * into one on insert, so they are separated here and the overflow is reported.
     */
    private fun normalize(
        perBucket: List<MutableList<Long>>,
        nowMs: Long,
    ): Pair<List<Long>, Long> {
        val all = perBucket.flatten().sorted()
        val kept = ArrayList<Long>(all.size)
        var previous = Long.MIN_VALUE
        var dropped = 0L
        for (timestamp in all) {
            if (timestamp <= previous + MusicoletPlaySynthesizer.MIN_SPACING_MS || timestamp > nowMs) {
                dropped++
                continue
            }
            kept.add(timestamp)
            previous = timestamp
        }
        return kept to dropped
    }

    private val PLAUSIBLE_YEARS = 2000..2100

    private fun monthWindow(
        month: YearMonth,
        zone: ZoneId,
    ): MusicoletPlaySynthesizer.Bucket =
        MusicoletPlaySynthesizer.Bucket(
            startMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    private fun yearWindow(
        year: Int,
        zone: ZoneId,
    ): MusicoletPlaySynthesizer.Bucket =
        MusicoletPlaySynthesizer.Bucket(
            startMs = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    /** Weeks are bucketed by ISO week number, the convention `PCs_W_<year>.<week>` uses. */
    private fun weekWindow(
        year: Int,
        week: Int,
        zone: ZoneId,
    ): MusicoletPlaySynthesizer.Bucket {
        val monday =
            LocalDate
                .of(year, 1, 4)
                .with(WeekFields.ISO.dayOfWeek(), 1)
                .plusWeeks((week - 1).toLong())
        return MusicoletPlaySynthesizer.Bucket(
            startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMs = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}
