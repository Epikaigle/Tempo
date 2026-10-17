package me.avinas.tempo.data.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the wire format of what Tempo transmits.
 *
 * These assertions are a privacy control, not just a formatting check: they are what stops
 * someone later adding an identifier to `properties`, dropping the anonymous flag, or
 * reverting the timestamp to a numeric epoch — the last of which fails silently, filing every
 * event at ingestion time and quietly shifting the daily cohorts.
 */
class PostHogPayloadTest {

    private val system =
        AnalyticsSystemContext(
            locale = "en-US",
            osVersion = "34",
            deviceModel = "Pixel 8",
            appVersion = "4.8.11",
        )

    /** 2023-11-14T22:13:20Z, chosen only so the rendered timestamp is deterministic. */
    private val fixedMillis = 1_700_000_000_000L

    private fun payloadOf(vararg events: AnalyticsEvent, installId: String = INSTALL_ID): String =
        PostHogPayload.build(
            events = events.map { it.toQueued(fixedMillis) },
            installId = installId,
            system = system,
            apiKey = "ph_test_token",
        )

    @Test
    fun `the request is a batch envelope carrying the api key`() {
        val json = payloadOf(DailyActivity(listenerDown = false))

        assertTrue(json.contains("\"api_key\":\"ph_test_token\""))
        assertTrue(json.contains("\"batch\":["))
        assertTrue(json.contains("\"historical_migration\":false"))
        assertTrue(json.contains("\"event\":\"daily_activity\""))
    }

    /** Without this every event would create a person profile at PostHog. */
    @Test
    fun `every event is marked anonymous`() {
        val json = payloadOf(DailyActivity(listenerDown = true), DbMigration(51, 52))

        assertTrue(json.contains("\"\$process_person_profile\":false"))
        // Twice: one flag per event, never once for the batch.
        assertTrue(
            "the anonymous flag must be on every event",
            json.split("\"\$process_person_profile\":false").size == 3,
        )
    }

    /**
     * The install id is what makes DAU and MAU countable, and it belongs on the transport
     * field only. Emitting it as a property would breach the schema's ban on identifier keys.
     */
    @Test
    fun `the install id is the distinct id and never a property`() {
        val json = payloadOf(DailyActivity(listenerDown = false))

        assertTrue(json.contains("\"distinct_id\":\"$INSTALL_ID\""))
        assertFalse(json.contains("\"install_id\""))
        assertFalse(json.contains("\"distinct_id\":\"$INSTALL_ID\",\"properties\":{\"distinct_id\""))
    }

    /** PostHog documents ISO 8601 and silently ignores a numeric epoch. */
    @Test
    fun `the timestamp is ISO 8601 rather than an epoch`() {
        val json = payloadOf(DailyActivity(listenerDown = false))

        // The `XX` pattern renders a zero offset as `Z` rather than `+00:00`; both are ISO 8601
        // and PostHog accepts either, so the exact suffix is pinned only to catch a change.
        assertTrue(json.contains("\"timestamp\":\"2023-11-14T22:13:20.000Z\""))
        assertFalse(json.contains("\"timestamp\":1700000000000"))
        assertFalse(json.contains("\"timestamp\":\"1700000000"))
    }

    /**
     * Device model is the highest-entropy quasi-identifier Tempo holds. It is retained only
     * where it earns its place — crash triage — because paired with a stable install id it is
     * what would make a small user base re-identifiable.
     */
    @Test
    fun `device model travels only with a crash`() {
        assertFalse(payloadOf(DailyActivity(listenerDown = false)).contains("\$device_model"))
        assertFalse(payloadOf(DbMigration(51, 52)).contains("\$device_model"))
        assertTrue(payloadOf(Crash("a.b.c", "d(SourceFile:412)", "4.8.11")).contains("\"\$device_model\":\"Pixel 8\""))
    }

    @Test
    fun `system context is attached to every event`() {
        val json = payloadOf(DailyActivity(listenerDown = false))

        assertTrue(json.contains("\"\$os\":\"Android\""))
        assertTrue(json.contains("\"\$os_version\":\"34\""))
        assertTrue(json.contains("\"\$app_version\":\"4.8.11\""))
        assertTrue(json.contains("\"\$locale\":\"en-US\""))
        assertTrue(json.contains("\"\$lib\":\"${PostHogPayload.LIB}\""))
    }

    /** Counts must leave the device as ranges, never as exact figures. */
    @Test
    fun `counts are bucketed rather than exact`() {
        val json = payloadOf(ListeningActivity(listens = 37, distinctApps = 3, uiOpened = true))

        assertTrue(json.contains("\"listens\":\"26-100\""))
        assertTrue(json.contains("\"apps\":\"1-5\""))
        assertFalse(json.contains("\"listens\":37"))
    }

    @Test
    fun `a batch carries every event and each keeps its own name`() {
        val json =
            payloadOf(
                DailyActivity(listenerDown = true),
                TrackingHealth(TrackingGapSeverity.OVER_24H, RevivedBy.BOOT, RecoveryAction.REBIND),
            )

        assertTrue(json.contains("\"event\":\"daily_activity\""))
        assertTrue(json.contains("\"event\":\"tracking_health\""))
        assertTrue(json.contains("\"severity\":\"OVER_24H\""))
    }

    private companion object {
        const val INSTALL_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
