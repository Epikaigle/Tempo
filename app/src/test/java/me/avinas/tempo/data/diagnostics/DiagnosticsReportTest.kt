package me.avinas.tempo.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private val input = DiagnosticsInput(
        appVersion = "4.8.8",
        versionCode = 488,
        androidApi = 34,
        deviceModel = "Pixel 7",
        databaseVersion = 52,
        trackCount = 1234,
        artistCount = 321,
        albumCount = 210,
        eventCount = 45_678,
        enrichmentCounts = mapOf("ENRICHED" to 1100, "PENDING" to 12, "FAILED" to 4),
        notificationAccessGranted = true,
        listenerConnected = true,
        listenerAliveAgoMillis = 5 * 60_000L,
        batteryOptimisationExempt = false,
        analyticsEnabled = true,
        analyticsDisclosureSeen = true,
        trackedAppCount = 9,
        enabledAppCount = 7,
        workerStates = mapOf("service_health_check" to "ENQUEUED", "local_backup" to "RUNNING")
    )

    @Test
    fun `report carries the facts an issue needs`() {
        val report = DiagnosticsReport.build(input)

        assertTrue(report.contains("4.8.8 (488)"))
        assertTrue(report.contains("Database version : 52"))
        assertTrue(report.contains("Listening events : 45678"))
        assertTrue(report.contains("Notification access   : yes"))
        assertTrue(report.contains("Tracked apps          : 9 (7 enabled)"))
        assertTrue(report.contains("service_health_check"))
    }

    /**
     * The report is user-shared, so it must state plainly that it contains no listening
     * content — that claim is the reason a user can safely paste it into a public issue.
     */
    @Test
    fun `report says it contains no listening content`() {
        val report = DiagnosticsReport.build(input)

        assertTrue(report.contains("no track, artist or", ignoreCase = true))
        assertTrue(report.contains("Nothing was", ignoreCase = true))
    }

    @Test
    fun `transparency about reporting state is included`() {
        val report = DiagnosticsReport.build(input)

        assertTrue(report.contains("Anonymous app-health stats"))
        assertTrue(report.contains("Enabled          : yes"))
    }

    @Test
    fun `a fresh install with nothing recorded still produces a usable report`() {
        val empty = input.copy(
            trackCount = 0,
            artistCount = 0,
            albumCount = 0,
            eventCount = 0,
            enrichmentCounts = emptyMap(),
            listenerConnected = false,
            listenerAliveAgoMillis = null,
            workerStates = emptyMap()
        )

        val report = DiagnosticsReport.build(empty)

        assertTrue(report.contains("No enrichment data recorded yet."))
        assertTrue(report.contains("Last heartbeat        : never"))
        assertTrue(report.contains("No scheduled work found."))
    }

    @Test
    fun `staleness is described in human units`() {
        assertTrue(DiagnosticsReport.build(input.copy(listenerAliveAgoMillis = 30_000L))
            .contains("just now"))
        assertTrue(DiagnosticsReport.build(input.copy(listenerAliveAgoMillis = 3 * 60L * 60_000L))
            .contains("3 h ago"))
        assertTrue(DiagnosticsReport.build(input.copy(listenerAliveAgoMillis = 2 * 24L * 60L * 60_000L))
            .contains("2 d ago"))
    }

    /** A record with no tracks must not read as if it had some. */
    @Test
    fun `works with a single track and no events`() {
        val one = input.copy(trackCount = 1, eventCount = 0, enrichmentCounts = emptyMap())

        val report = DiagnosticsReport.build(one)

        assertTrue(report.contains("Tracks           : 1"))
        assertTrue(report.contains("Listening events : 0"))
        assertFalse(report.contains("Tracks           : 0"))
    }
}
