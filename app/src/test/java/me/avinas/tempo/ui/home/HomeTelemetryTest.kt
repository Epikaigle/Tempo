package me.avinas.tempo.ui.home

import me.avinas.tempo.data.stats.DailyListening
import me.avinas.tempo.data.stats.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class HomeTelemetryTest {

    @Test
    fun `test HomeUiState default telemetry values`() {
        val state = HomeUiState()
        assertEquals(0L, state.dailyAvgMinutes)
        assertEquals(0, state.activeDaysCount)
        assertEquals(0, state.totalDaysCount)
        assertEquals(0L, state.peakDayMinutes)
    }

    @Test
    fun `test daily avg calculation with active days`() {
        val totalListeningMs = 120 * 60 * 1000L // 120 minutes = 2 hours
        val totalMinutes = totalListeningMs / 1000 / 60
        val activeDaysCount = 4

        val dailyAvg = if (activeDaysCount > 0) totalMinutes / activeDaysCount else 0L
        assertEquals(30L, dailyAvg)
    }

    @Test
    fun `test daily avg calculation with zero active days`() {
        val totalListeningMs = 0L
        val totalMinutes = totalListeningMs / 1000 / 60
        val activeDaysCount = 0

        val dailyAvg = if (activeDaysCount > 0) totalMinutes / activeDaysCount else 0L
        assertEquals(0L, dailyAvg)
    }

    @Test
    fun `test elapsed days calculation for time ranges`() {
        val today = LocalDate.of(2026, 9, 3) // Thursday (dayOfWeek = 4), Sep 3 (dayOfMonth = 3), dayOfYear = 246

        val todayElapsed = 1
        assertEquals(1, todayElapsed)

        val weekElapsed = today.dayOfWeek.value
        assertEquals(4, weekElapsed) // Mon, Tue, Wed, Thu

        val monthElapsed = today.dayOfMonth
        assertEquals(3, monthElapsed)

        val yearElapsed = today.dayOfYear
        assertEquals(246, yearElapsed)

        // Earliest timestamp 100 days ago
        val earliestDate = today.minusDays(99)
        val allTimeElapsed = java.time.temporal.ChronoUnit.DAYS.between(earliestDate, today).toInt() + 1
        assertEquals(100, allTimeElapsed)
    }

    @Test
    fun `test telemetry formatting helper logic`() {
        fun formatDuration(minutes: Long): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        assertEquals("0m", formatDuration(0L))
        assertEquals("45m", formatDuration(45L))
        assertEquals("1h 0m", formatDuration(60L))
        assertEquals("2h 15m", formatDuration(135L))
    }

    @Test
    fun `test this week labels match days from Monday to today`() {
        val today = LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value.toLong() - 1)
        val daysInWeek = (0 until today.dayOfWeek.value).map { monday.plusDays(it.toLong()) }
        val labels = daysInWeek.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

        assertEquals(today.dayOfWeek.value, labels.size)
        assertEquals("Mon", labels.first())
        assertEquals(today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), labels.last())
    }
}
