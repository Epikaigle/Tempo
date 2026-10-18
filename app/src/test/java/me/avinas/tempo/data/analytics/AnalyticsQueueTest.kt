package me.avinas.tempo.data.analytics

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsQueueTest {

    private val now = 1_700_000_000_000L

    private fun event(ageMillis: Long = 0L, name: String = "feature_used") = QueuedAnalyticsEvent(
        timestampMillis = now - ageMillis,
        name = name,
        stringProps = mapOf("feature" to "SPOTLIGHT"),
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

    /**
     * Each queued event needs its own key: two identical events are still two events, and one
     * shared key would have PostHog discard the second as a duplicate.
     */
    @Test
    fun `every queued event is minted its own insert id`() {
        val first = DailyActivity(listenerDown = false).toQueued(now)
        val second = DailyActivity(listenerDown = false).toQueued(now)

        assertNotNull(first.insertId)
        assertNotEquals(first.insertId, second.insertId)
    }

    /** A retry re-reads the same event, so its key must survive the queue round-trip. */
    @Test
    fun `an insert id given at enqueue time is the one stored`() {
        val queued = DailyActivity(listenerDown = false).toQueued(now, insertId = "insert-1")

        assertEquals("insert-1", queued.insertId)
    }

    /**
     * The queue file outlives an app update, so the format may only ever gain a field.
     *
     * A file written before `insertId` existed must still parse — those events simply go out
     * without a de-duplication key and expire within the day. This pins that: a required field
     * would make `AnalyticsQueueStore.read` discard the whole file, silently dropping whatever
     * the user had queued at the moment they updated.
     */
    @Test
    fun `a queue file written before insert ids existed still parses`() {
        val adapter =
            Moshi.Builder().build().adapter<List<QueuedAnalyticsEvent>>(
                Types.newParameterizedType(List::class.java, QueuedAnalyticsEvent::class.java),
            )
        val legacy =
            """[{"timestampMillis":1700000000000,"name":"daily_activity",""" +
                """"stringProps":{},"intProps":{"listener_down":0}}]"""

        val parsed = adapter.fromJson(legacy)

        assertEquals(1, parsed?.size)
        assertEquals("daily_activity", parsed?.first()?.name)
        assertNull("an absent key must read as null, not fail the parse", parsed?.first()?.insertId)
    }
}
