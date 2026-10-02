package me.avinas.tempo.service

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.repository.ListeningRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests database-boundary deduplication for [MusicTrackingManager], ensuring events sharing
 * (session_id, track_id, timestamp) are saved at most once and retain the initial duration.
 */
class MusicTrackingManagerSessionDedupTest {

    private val insertedRows = mutableListOf<ListeningEvent>()

    @Suppress("UNCHECKED_CAST")
    private fun fakeRepository(): ListeningRepository =
        Proxy.newProxyInstance(
            ListeningRepository::class.java.classLoader,
            arrayOf(ListeningRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertAll" -> {
                    val events = args[0] as List<ListeningEvent>
                    insertedRows.addAll(events)
                    events.map { insertedRows.size.toLong() }
                }
                "insert" -> {
                    insertedRows.add(args[0] as ListeningEvent)
                    insertedRows.size.toLong()
                }
                "getEventsBySessionId" -> insertedRows.filter { it.sessionId == args[0] }
                "shouldPersist" -> true
                "deleteById" -> 1
                "toString" -> "FakeListeningRepository"
                "hashCode" -> 42
                "equals" -> false
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as ListeningRepository

    private fun event(
        sessionId: String? = "sess-1",
        trackId: Long = 42L,
        timestamp: Long = 1_700_000_000_000L,
        playDuration: Long = 180_000L,
    ) = ListeningEvent(
        track_id = trackId,
        timestamp = timestamp,
        playDuration = playDuration,
        completionPercentage = 90,
        source = "com.spotify.music",
        wasSkipped = false,
        isReplay = false,
        sessionId = sessionId,
    )

    private fun pending(event: ListeningEvent) =
        PendingEvent(event = event, sessionId = event.sessionId.orEmpty(), queuedAt = 0L, retryCount = 0)

    @Test
    fun `filterSessionDuplicates drops events already persisted for the session`() = runTest {
        val manager = MusicTrackingManager(fakeRepository(), backgroundScope)
        // Simulate a row already persisted before process restart.
        insertedRows.add(event(sessionId = "sess-1"))

        val survivors = manager.filterSessionDuplicates(listOf(pending(event(sessionId = "sess-1"))))

        assertTrue("a session that already wrote a row must not write another", survivors.isEmpty())
    }

    @Test
    fun `filterSessionDuplicates collapses repeats within one batch keeping the first write`() = runTest {
        val manager = MusicTrackingManager(fakeRepository(), backgroundScope)
        val originalSave = event(sessionId = "sess-1", playDuration = 180_000)
        val inflatedResave = event(sessionId = "sess-1", playDuration = 300_000)

        val survivors =
            manager.filterSessionDuplicates(listOf(pending(originalSave), pending(inflatedResave)))

        assertEquals(1, survivors.size)
        assertEquals(
            "the first write is the accurate one; the leaked re-save kept accumulating time",
            180_000L,
            survivors.single().event.playDuration,
        )
    }

    @Test
    fun `filterSessionDuplicates keeps distinct plays`() = runTest {
        val manager = MusicTrackingManager(fakeRepository(), backgroundScope)
        val base = event(sessionId = "sess-1")
        val differentSession = event(sessionId = "sess-2")
        // Same session ID with different timestamps represents distinct plays and should be retained.
        val sameSessionIdDifferentPlay = event(sessionId = "sess-1", timestamp = 1_700_000_060_000L)
        val noSession = event(sessionId = null, timestamp = 1_700_000_120_000L)

        val survivors =
            manager.filterSessionDuplicates(
                listOf(
                    pending(base),
                    pending(differentSession),
                    pending(sameSessionIdDifferentPlay),
                    pending(noSession),
                ),
            )

        assertEquals(4, survivors.size)
    }

    @Test
    fun `saveEventImmediate writes once and skips the same session afterwards`() = runTest {
        val manager = MusicTrackingManager(fakeRepository(), backgroundScope)
        val e = event(sessionId = "sess-1")

        val first = manager.saveEventImmediate(e)
        val second = manager.saveEventImmediate(e.copy())

        assertTrue("first save must be persisted", first.getOrThrow() > 0L)
        assertEquals("second save of the same session must be skipped", 0L, second.getOrThrow())
        assertEquals(1, insertedRows.size)
    }

    @Test
    fun `queued event is written exactly once through the batch pipeline`() = runTest {
        val manager = MusicTrackingManager(fakeRepository(), backgroundScope)

        assertTrue(manager.queueEvent(event(sessionId = "sess-1"), "sess-1"))
        manager.flushAll()

        assertEquals(1, insertedRows.size)
        assertEquals("sess-1", insertedRows.single().sessionId)
    }
}

