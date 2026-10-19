package me.avinas.tempo.data.lastfm

import me.avinas.tempo.data.local.entities.LastFmImportMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two ways an import used to look like it did nothing.
 *
 * 1. A resume page past the end of the history. Last.fm answers such a page with an empty
 *    `track` list; the loop read that as "history exhausted" and reported a *successful*
 *    import of zero scrobbles, which the UI rendered as "0 active listens" even though the
 *    account had plenty of history the walk never reached.
 *
 * 2. A walk that fetched scrobbles but inserted none, because every one of them was already in
 *    the library (the device tracks listens live, and re-runs are common). That is a normal,
 *    successful no-op - but it must be reported as one instead of a hollow zero.
 */
class LastFmImportResultsTest {
    private fun result(
        eventsImported: Long = 0,
        scrobblesArchived: Long = 0,
        duplicatesSkipped: Long = 0,
        totalProcessed: Long = 0,
        success: Boolean = true,
    ) = LastFmImportService.ImportResult(
        success = success,
        eventsImported = eventsImported,
        tracksCreated = 0,
        artistsCreated = 0,
        scrobblesArchived = scrobblesArchived,
        duplicatesSkipped = duplicatesSkipped,
        totalProcessed = totalProcessed,
    )

    // ── Resume pages ─────────────────────────────────────────────────────────

    @Test
    fun `resume page past the last page is detected`() {
        assertTrue(ImportResumePage.isPastEnd(requestedPage = 400, reportedLastPage = 12))
    }

    @Test
    fun `resume page inside the history is kept`() {
        assertFalse(ImportResumePage.isPastEnd(requestedPage = 11, reportedLastPage = 12))
        assertFalse(ImportResumePage.isPastEnd(requestedPage = 12, reportedLastPage = 12))
    }

    @Test
    fun `page one is never out of range`() {
        assertFalse(ImportResumePage.isPastEnd(requestedPage = 1, reportedLastPage = 0))
        assertFalse(ImportResumePage.isPastEnd(requestedPage = 1, reportedLastPage = 5))
    }

    @Test
    fun `missing pagination attributes do not force a restart`() {
        // A response without `@attr` reports 0 pages. That is not evidence that the requested
        // page is out of range, so the walk must not throw away its position.
        assertFalse(ImportResumePage.isPastEnd(requestedPage = 7, reportedLastPage = 0))
    }

    // ── Result semantics ─────────────────────────────────────────────────────

    @Test
    fun `rerun with everything already present is a no-op, not an empty import`() {
        val rerun = result(duplicatesSkipped = 120, totalProcessed = 120)

        assertTrue(rerun.alreadyImported)
        assertEquals(0L, rerun.totalImported)
    }

    @Test
    fun `an import that fetched nothing is not a no-op`() {
        // Nothing came back from the API: this is the failure the service now reports instead
        // of claiming success.
        val empty = result(totalProcessed = 0)
        assertFalse(empty.alreadyImported)

        val failure = empty.copy(success = false, errorMessage = "Last.fm returned no scrobbles")
        assertFalse(failure.alreadyImported)
    }

    @Test
    fun `archived scrobbles count as imported`() {
        val archived = result(scrobblesArchived = 50, totalProcessed = 50)

        assertFalse(archived.alreadyImported)
        assertEquals(50L, archived.totalImported)
    }

    @Test
    fun `active events count as imported`() {
        val active = result(eventsImported = 30, totalProcessed = 30)

        assertFalse(active.alreadyImported)
        assertEquals(30L, active.totalImported)
    }

    // ── Resumed counters ─────────────────────────────────────────────────────

    @Test
    fun `resumed counters carry the previous attempt forward`() {
        val metadata =
            LastFmImportMetadata(
                id = 7,
                lastfmUsername = "someone",
                importTier = "STANDARD",
                activeTrackThreshold = 1_000,
                recentMonthsIncluded = 12,
                totalScrobblesFound = 5_000,
                status = LastFmImportMetadata.STATUS_IN_PROGRESS,
                currentPage = 12,
                scrobblesProcessed = 2_200,
                eventsImported = 1_950,
                tracksCreated = 410,
                artistsCreated = 120,
                scrobblesArchived = 200,
                duplicatesSkipped = 50,
            )

        val totals = LastFmImportService.ImportTotals.from(metadata)

        assertEquals(1_950L, totals.eventsImported)
        assertEquals(410L, totals.tracksCreated)
        assertEquals(120L, totals.artistsCreated)
        assertEquals(200L, totals.scrobblesArchived)
        assertEquals(50L, totals.duplicatesSkipped)
        assertEquals(2_200L, totals.scrobblesProcessed)
    }

    @Test
    fun `a first attempt starts from zero`() {
        val totals = LastFmImportService.ImportTotals()

        assertEquals(0L, totals.eventsImported)
        assertEquals(0L, totals.scrobblesProcessed)
    }
}
