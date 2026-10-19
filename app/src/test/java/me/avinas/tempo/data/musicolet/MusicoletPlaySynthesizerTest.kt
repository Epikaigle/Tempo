package me.avinas.tempo.data.musicolet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Every timestamp an import creates is an estimate, so the properties that make the
 * estimate trustworthy are what matter: it is reproducible, ordered, never in the
 * future, and never so dense that two plays collapse into one on insert.
 */
class MusicoletPlaySynthesizerTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun instant(text: String): Long = Instant.parse(text).toEpochMilli()

    private val june =
        MusicoletPlaySynthesizer.Bucket(
            startMs = instant("2026-06-01T00:00:00Z"),
            endMs = instant("2026-07-01T00:00:00Z"),
        )

    private val now = instant("2026-10-15T12:00:00Z")

    @Test
    fun `the same period always yields the same plays`() {
        val first = MusicoletPlaySynthesizer.spread(june, requested = 40, notAfterMs = now, zone = zone)
        val second = MusicoletPlaySynthesizer.spread(june, requested = 40, notAfterMs = now, zone = zone)

        assertEquals(40, first.timestamps.size)
        assertEquals(first.timestamps, second.timestamps)
    }

    @Test
    fun `plays are ordered and at least the dedup tolerance apart`() {
        val placed = MusicoletPlaySynthesizer.spread(june, requested = 500, notAfterMs = now, zone = zone)

        assertEquals(500, placed.timestamps.size)
        placed.timestamps.zipWithNext { earlier, later ->
            assertTrue("$later must follow $earlier", later > earlier)
            assertTrue(
                "gap ${later - earlier}ms is too small to survive same-source dedup",
                later - earlier >= MusicoletPlaySynthesizer.MIN_SPACING_MS,
            )
        }
    }

    @Test
    fun `no play is placed after the not-after instant`() {
        // The current month is still in progress: plays must not be dated into its future.
        val currentMonth =
            MusicoletPlaySynthesizer.Bucket(
                startMs = instant("2026-10-01T00:00:00Z"),
                endMs = instant("2026-11-01T00:00:00Z"),
            )
        val notAfter = instant("2026-10-05T09:00:00Z")

        val placed = MusicoletPlaySynthesizer.spread(currentMonth, requested = 30, notAfterMs = notAfter, zone = zone)

        assertTrue(placed.timestamps.isNotEmpty())
        for (timestamp in placed.timestamps) {
            assertTrue("$timestamp is after $notAfter", timestamp <= notAfter)
        }
        assertEquals((30 - placed.timestamps.size).toLong(), placed.dropped)
    }

    /** Musicolet has no hour for a play, so none is invented overnight. */
    @Test
    fun `plays fall inside waking hours`() {
        val placed = MusicoletPlaySynthesizer.spread(june, requested = 90, notAfterMs = now, zone = zone)
        val hours =
            placed.timestamps.map {
                Instant.ofEpochMilli(it).atZone(zone).hour
            }
        for (hour in hours) {
            assertTrue("hour $hour is outside 07:00-23:59", hour in 7..23)
        }
    }

    @Test
    fun `plays spread across the whole period`() {
        val placed = MusicoletPlaySynthesizer.spread(june, requested = 30, notAfterMs = now, zone = zone)
        val first = placed.timestamps.first()
        val last = placed.timestamps.last()

        assertTrue("first play should be early in the month", first - june.startMs < 3L * 24 * 3_600_000)
        assertTrue("last play should be late in the month", june.endMs - last < 3L * 24 * 3_600_000)
    }

    /**
     * A count beyond any real listening is capped rather than materialised, and the
     * shortfall is reported so the import summary can say plays were left out instead of
     * quietly losing them.
     */
    @Test
    fun `a period is never given more plays than the cap allows`() {
        val requested = MusicoletPlaySynthesizer.MAX_PLAYS_PER_BUCKET + 5_000
        val placed = MusicoletPlaySynthesizer.spread(june, requested = requested, notAfterMs = now, zone = zone)

        assertTrue(placed.timestamps.size <= MusicoletPlaySynthesizer.MAX_PLAYS_PER_BUCKET)
        assertEquals((requested - placed.timestamps.size).toLong(), placed.dropped)
        placed.timestamps.zipWithNext { earlier, later ->
            assertTrue(later - earlier >= MusicoletPlaySynthesizer.MIN_SPACING_MS)
        }
    }

    @Test
    fun `a window too small for two plays places none`() {
        val tiny =
            MusicoletPlaySynthesizer.Bucket(
                startMs = instant("2026-06-01T08:00:00Z"),
                endMs = instant("2026-06-01T08:00:04Z"),
            )
        val placed = MusicoletPlaySynthesizer.spread(tiny, requested = 3, notAfterMs = now, zone = zone)

        assertTrue(placed.timestamps.isEmpty())
        assertEquals(3L, placed.dropped)
    }

    @Test
    fun `a period with no plays places nothing`() {
        val placed = MusicoletPlaySynthesizer.spread(june, requested = 0, notAfterMs = now, zone = zone)
        assertTrue(placed.timestamps.isEmpty())
        assertEquals(0L, placed.dropped)
    }

    /** A period entirely in the future cannot happen, and must not place plays there. */
    @Test
    fun `a period after the not-after instant places nothing`() {
        val future =
            MusicoletPlaySynthesizer.Bucket(
                startMs = instant("2027-01-01T00:00:00Z"),
                endMs = instant("2027-02-01T00:00:00Z"),
            )
        val placed = MusicoletPlaySynthesizer.spread(future, requested = 5, notAfterMs = now, zone = zone)

        assertTrue(placed.timestamps.isEmpty())
        assertEquals(5L, placed.dropped)
    }
}
