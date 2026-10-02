package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the privacy contract of the analytics schema.
 *
 * These assertions are the reason the "we never collect your listening data" promise can be
 * trusted: they fail the build if anyone ever adds a property that could carry a track
 * title, an artist name, a search query, a file path or an identifier.
 */
class AnalyticsSchemaTest {

    private val allEvents: List<AnalyticsEvent> = AnalyticsEventSamples.all

    /**
     * Aptabase rejects property keys longer than 40 characters, and a rejected event is
     * silently lost rather than surfaced as an error.
     */
    @Test
    fun `property keys stay within the server limit`() {
        allEvents.forEach { event ->
            event.props.keys.forEach { key ->
                assertTrue(
                    "key '$key' on '${event.name}' exceeds 40 chars",
                    key.length <= 40
                )
            }
        }
    }

    /** The ingest API only accepts String and Int values. */
    @Test
    fun `property values are only strings or ints`() {
        allEvents.forEach { event ->
            event.props.forEach { (key, value) ->
                assertTrue(
                    "'$key' on '${event.name}' is ${value::class.java.simpleName}",
                    value is String || value is Int
                )
            }
        }
    }

    /**
     * No property key may name anything a user listened to, or identify them or their files.
     * Matching is per `_`-separated token so legitimate words containing a banned substring
     * (e.g. "provider" contains "id") are not flagged.
     */
    @Test
    fun `no property key names listening content or an identifier`() {
        val bannedTokens = setOf(
            "track", "artist", "album", "song", "query", "search", "title", "name",
            "text", "content", "path", "file", "uri", "url", "email", "token", "id",
            "uuid", "device", "notification", "imei", "mac", "phone", "account"
        )

        allEvents.forEach { event ->
            event.props.keys.forEach { key ->
                val tokens = key.split('_').map { it.lowercase() }
                bannedTokens.forEach { banned ->
                    assertFalse(
                        "key '$key' on '${event.name}' contains the banned token '$banned'",
                        tokens.contains(banned)
                    )
                }
            }
        }
    }

    /** Event names are also part of the public data dictionary — keep them clean too. */
    @Test
    fun `event names are stable snake case under the key limit`() {
        allEvents.map { it.name }.distinct().forEach { name ->
            assertTrue("'$name' is not snake_case", Regex("^[a-z][a-z0-9_]*$").matches(name))
            assertTrue("'$name' exceeds 40 chars", name.length <= 40)
        }
    }

    /**
     * Exception messages routinely embed parsed notification text, so a crash must never be
     * representable as free text. These guards make smuggling a song title impossible.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `crash rejects a free-text class name`() {
        Crash("Never Gonna Give You Up", "d(SourceFile:412)", "4.8.8")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `crash rejects a message smuggled into the frame`() {
        Crash("a.b.C", "onCreate(SourceFile:1) Rick Astley", "4.8.8")
    }

    @Test
    fun `crash accepts an obfuscated class and frame`() {
        val event = Crash("a.b.c\$d", "e(SourceFile:412)", "4.8.8")

        assertEquals("crash", event.name)
        assertEquals("a.b.c\$d", event.props["crash_class"])
        assertEquals("e(SourceFile:412)", event.props["top_frame"])
    }

    @Test
    fun `crash class name is truncated at the documented cap`() {
        val tooLong = "a".repeat(Crash.MAX_CLASS_CHARS + 1)

        val thrown = runCatching { Crash(tooLong, "d(SourceFile:1)", "4.8.8") }

        assertTrue(thrown.isFailure)
    }

    /** Every event must survive the trip into its queue form without dropping properties. */
    @Test
    fun `events convert to queue form without losing properties`() {
        allEvents.forEach { event ->
            val queued = event.toQueued(timestampMillis = 1_000L)

            assertEquals(event.name, queued.name)
            assertEquals(event.props.size, queued.stringProps.size + queued.intProps.size)
        }
    }
}
