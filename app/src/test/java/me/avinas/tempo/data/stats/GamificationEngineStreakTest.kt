package me.avinas.tempo.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Streak behaviour across timezones, in the terms the repositories now pass in.
 *
 * Day lists come from the SQL fragments in [me.avinas.tempo.data.local.LocalTimeSql], which freeze
 * each play in the zone it was recorded in; `today` is the current date in the most recent play's
 * zone ([me.avinas.tempo.data.local.DeviceTimeZone.todayIn]). These tests therefore describe the
 * exact pair the app computes, without a database or a clock.
 */
class GamificationEngineStreakTest {
    @Test
    fun `a trip cannot re-bucket history and shorten a streak`() {
        // The report: 110 days in the UK, read as 40 on landing in Thailand. With every day frozen
        // in the zone it was played in, the count no longer depends on where the phone is.
        val dates = consecutiveDaysEndingOn("2024-03-01", 110)

        val streak = GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-01"))

        assertEquals(110, streak.currentStreak)
        assertEquals(110, streak.longestStreak)
    }

    @Test
    fun `the newest day still counts when a westward trip puts it ahead of the phone`() {
        // Flying west: the play is recorded on 2024-03-02 in the zone it happened in, while the
        // phone (now behind that zone) still reads 2024-03-01. Passing the phone's date used to
        // pass the row over as "in the future" and drop a day off the streak.
        val dates = listOf("2024-03-02", "2024-03-01")

        assertEquals(2, GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-01")).currentStreak)
    }

    @Test
    fun `an eastward flight that loses a calendar day keeps the streak alive`() {
        // Last play at home on 2024-03-01; a +12 hop skips 2024-03-02 entirely, so the phone reads
        // 2024-03-03. The anchor is the last play's zone, where it is still 2024-03-02 -- yesterday
        // -- and the streak survives.
        val dates = listOf("2024-03-01")

        assertEquals(1, GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-02")).currentStreak)
    }

    @Test
    fun `days counted twice by a westbound trip are still one listening day`() {
        // Crossing a boundary west repeats a calendar date; the duplicate must not inflate the run.
        val dates = listOf("2024-03-03", "2024-03-03", "2024-03-02", "2024-03-01")

        assertEquals(3, GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-03")).currentStreak)
    }

    @Test
    fun `not having listened yet today does not break the streak`() {
        val dates = listOf("2024-03-02", "2024-03-01")

        assertEquals(2, GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-03")).currentStreak)
    }

    @Test
    fun `two silent days end the streak`() {
        val dates = listOf("2024-03-01", "2024-02-28")

        val streak = GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-03"))

        assertEquals(0, streak.currentStreak)
        assertEquals(1, streak.longestStreak)
    }

    @Test
    fun `longest streak spans the whole history regardless of the current zone`() {
        val oldRun = consecutiveDaysEndingOn("2023-11-30", 110)
        val recentRun = consecutiveDaysEndingOn("2024-03-03", 3)
        val dates = (recentRun + oldRun).sortedDescending()

        val streak = GamificationEngine.calculateStreak(dates, LocalDate.parse("2024-03-03"))

        assertEquals(3, streak.currentStreak)
        assertEquals(110, streak.longestStreak)
    }

    @Test
    fun `empty and unparseable histories are tolerated`() {
        assertEquals(0, GamificationEngine.calculateStreak(emptyList(), LocalDate.parse("2024-03-03")).currentStreak)
        assertEquals(0, GamificationEngine.calculateStreak(listOf("not-a-date"), LocalDate.parse("2024-03-03")).currentStreak)
    }

    private fun consecutiveDaysEndingOn(
        end: String,
        count: Int,
    ): List<String> {
        val last = LocalDate.parse(end)
        return (0 until count).map { last.minusDays(it.toLong()).toString() }.sortedDescending()
    }
}
