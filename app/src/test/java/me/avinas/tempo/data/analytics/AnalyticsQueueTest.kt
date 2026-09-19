package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsQueueTest {

    private val now = 1_700_000_000_000L

    private fun event(ageMillis: Long = 0L, name: String = "screen_viewed") = QueuedAnalyticsEvent(
        timestampMillis = now - ageMillis,
        name = name,
        stringProps = mapOf("screen" to "HOME"),
        intProps = emptyMap()
    )

    /**
     * The ingest API rejects anything older than 24h, so replaying it is pure waste. Dropping
     * at the source is also what keeps a long-offline device from accumulating a backlog.
     */
    @Test
    fun `events older than the server window are dropped`() {
        val fresh = event(ageMillis = 60_000L)
        val stale = event(ageMillis = AnalyticsQueue.MAX_AGE_MILLIS + 1)

        assertEquals(listOf(fresh), AnalyticsQueue.prune(listOf(stale, fresh), now))
    }

    @Test
    fun `an event exactly at the window boundary is still kept`() {
        val boundary = event(ageMillis = AnalyticsQueue.MAX_AGE_MILLIS)

        assertEquals(listOf(boundary), AnalyticsQueue.prune(listOf(boundary), now))
    }

    @Test
    fun `expiry check agrees with prune`() {
        assertTrue(AnalyticsQueue.isExpired(event(ageMillis = AnalyticsQueue.MAX_AGE_MILLIS + 1), now))
        assertFalse(AnalyticsQueue.isExpired(event(ageMillis = 1_000L), now))
    }

    @Test
    fun `queue growth is capped, keeping the newest events`() {
        val events = (1..AnalyticsQueue.MAX_STORED + 50).map { index -> event(name = "event_$index") }

        val pruned = AnalyticsQueue.prune(events, now)

        assertEquals(AnalyticsQueue.MAX_STORED, pruned.size)
        assertEquals("event_${AnalyticsQueue.MAX_STORED + 50}", pruned.last().name)
    }

    @Test
    fun `a batch never exceeds the server limit and takes the oldest first`() {
        val events = (1..AnalyticsQueue.MAX_BATCH + 10).map { index -> event(name = "event_$index") }

        val batch = AnalyticsQueue.nextBatch(events, now)

        assertEquals(AnalyticsQueue.MAX_BATCH, batch.size)
        assertEquals("event_1", batch.first().name)
    }

    @Test
    fun `batching an empty or fully expired queue yields nothing`() {
        assertTrue(AnalyticsQueue.nextBatch(emptyList(), now).isEmpty())
        assertTrue(
            AnalyticsQueue.nextBatch(listOf(event(ageMillis = AnalyticsQueue.MAX_AGE_MILLIS + 1)), now)
                .isEmpty()
        )
    }
}
