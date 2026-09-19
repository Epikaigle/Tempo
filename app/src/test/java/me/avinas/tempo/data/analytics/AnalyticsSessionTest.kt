package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The session id is the only client-side value Tempo attaches to an event, so these tests
 * pin the two properties that make it safe: the format the ingest API requires, and the
 * fact that it rotates rather than acting as a device identifier.
 */
class AnalyticsSessionTest {

    private val baseTime = 1_700_000_000_000L

    @Test
    fun `session id is epoch seconds followed by eight random digits`() {
        val id = AnalyticsSession.format(baseTime, Random(1))

        assertTrue("'$id' is not 18 digits", Regex("^\\d{18}$").matches(id))
        assertEquals(baseTime / 1000L, id.substring(0, 10).toLong())
    }

    @Test
    fun `session ids differ when the random digits differ`() {
        val first = AnalyticsSession.format(baseTime, Random(1))
        val second = AnalyticsSession.format(baseTime, Random(2))

        assertNotEquals(first, second)
    }

    @Test
    fun `the same session is reused while it stays active`() {
        var clock = baseTime
        val session = AnalyticsSession(clockMillis = { clock }, random = Random(7))

        val first = session.id()
        clock += 5 * 60 * 1000L
        val second = session.id()

        assertEquals(first, second)
    }

    /** Rotation is what prevents the id from becoming a durable device identifier. */
    @Test
    fun `the session rotates after the idle timeout`() {
        var clock = baseTime
        val session = AnalyticsSession(clockMillis = { clock }, random = Random(7))

        val first = session.id()
        clock += AnalyticsSession.IDLE_TIMEOUT_MS + 1
        val second = session.id()

        assertNotEquals(first, second)
    }

    @Test
    fun `activity keeps the session alive across a long run`() {
        var clock = baseTime
        val session = AnalyticsSession(clockMillis = { clock }, random = Random(7))

        val first = session.id()
        // Step forward in increments below the timeout, as a user browsing would.
        repeat(20) {
            clock += AnalyticsSession.IDLE_TIMEOUT_MS / 2
            session.id()
        }

        assertEquals(first, session.id())
    }
}
