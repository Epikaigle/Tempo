package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the timestamp format, batch shape and exact bytes of the ingest body.
 *
 * Assertions are made against the raw JSON rather than a parsed form on purpose: this is
 * testing what actually leaves the device, not what a round-trip says it should.
 */
class AptabasePayloadTest {

    private val system = AnalyticsSystemContext(
        locale = "en-GB",
        osVersion = "34",
        deviceModel = "Pixel 7",
        appVersion = "4.8.8"
    )

    private fun payloadOf(vararg events: AnalyticsEvent, sessionId: String = "171351624706652714") =
        AptabasePayload.build(
            events = events.mapIndexed { index, event -> event.toQueued(1_700_000_000_123L + index) },
            sessionId = sessionId,
            system = system
        )

    @Test
    fun `body is a json array`() {
        val json = payloadOf(ScreenViewed(AnalyticsScreen.HOME))

        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }

    @Test
    fun `event name, props and session id are present`() {
        val json = payloadOf(FeatureUsed(TempoFeature.SPOTLIGHT))

        assertTrue(json.contains("\"eventName\":\"feature_used\""))
        assertTrue(json.contains("\"feature\":\"SPOTLIGHT\""))
        assertTrue(json.contains("\"sessionId\":\"171351624706652714\""))
    }

    /**
     * The moment a counter is serialized as a string the ingest API rejects the event, so
     * this is worth pinning explicitly. DbMigration is used because its version numbers are
     * the only raw numbers in the schema.
     */
    @Test
    fun `int properties serialize as numbers not strings`() {
        val json = payloadOf(
            DbMigration(fromVersion = 51, toVersion = 52)
        )

        assertTrue(json.contains("\"from_v\":51"))
        assertTrue(json.contains("\"to_v\":52"))
        assertFalse(json.contains("\"from_v\":\"51\""))
    }

    /** Counts leave the device as ranges, never as exact figures. */
    @Test
    fun `counts are bucketed rather than exact`() {
        val json = payloadOf(ListeningActivity(listens = 37, distinctApps = 3))

        assertTrue(json.contains("\"listens\":\"26-100\""))
        assertTrue(json.contains("\"apps\":\"1-5\""))
        assertFalse(json.contains("\"listens\":37"))
    }

    @Test
    fun `timestamp is iso8601 utc with millis`() {
        val json = payloadOf(ScreenViewed(AnalyticsScreen.STATS))

        val match = Regex("\"timestamp\":\"([^\"]+)\"").find(json)
        assertTrue("no timestamp in $json", match != null)
        assertTrue(
            "unexpected timestamp format: ${match!!.groupValues[1]}",
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(match.groupValues[1])
        )
    }

    /** A millisecond value of exactly zero must not shorten the format. */
    @Test
    fun `timestamp keeps millis when they are zero`() {
        val event = ScreenViewed(AnalyticsScreen.HOME).toQueued(1_700_000_000_000L)

        val json = AptabasePayload.build(listOf(event), "171351624706652714", system)

        assertTrue(json.contains("\"timestamp\":\"2023-11-14T22:13:20.000Z\""))
    }

    @Test
    fun `system props carry no identifier and are marked not-debug`() {
        val json = payloadOf(ScreenViewed(AnalyticsScreen.HOME))

        assertTrue(json.contains("\"locale\":\"en-GB\""))
        assertTrue(json.contains("\"deviceModel\":\"Pixel 7\""))
        assertTrue(json.contains("\"isDebug\":false"))
        assertTrue(json.contains("\"sdkVersion\":\"${AptabasePayload.SDK_VERSION}\""))
    }

    @Test
    fun `every event in the batch is emitted`() {
        val json = payloadOf(
            ScreenViewed(AnalyticsScreen.HOME),
            ScreenViewed(AnalyticsScreen.STATS),
            ScreenViewed(AnalyticsScreen.HISTORY)
        )

        assertEquals(3, json.split("\"eventName\"").size - 1)
    }

    /**
     * The whole point of the closed schema, asserted end to end: nothing that names listening
     * content or identifies a user can appear in a real payload.
     */
    @Test
    fun `payload contains no listening content or identifiers`() {
        val json = AnalyticsEventSamples.all.joinToString("\n") { event ->
            payloadOf(event)
        }

        val bannedTokens = listOf(
            "track_name", "artist_name", "album_name", "query", "user_id", "device_id",
            "android_id", "advertising_id", "email", "token", "playlist"
        )
        bannedTokens.forEach { banned ->
            assertFalse("payload leaked '$banned'", json.contains(banned, ignoreCase = true))
        }
    }

    @Test
    fun `no user or account field is ever emitted`() {
        val json = payloadOf(ScreenViewed(AnalyticsScreen.HOME))

        assertFalse(json.contains("\"userId\""))
        assertFalse(json.contains("\"distinctId\""))
        assertFalse(json.contains("\"user\""))
    }
}
