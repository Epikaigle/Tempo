package me.avinas.tempo.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the incremental-sync cursor watermark.
 *
 * Background: the sync used to set `syncCursor = now` unconditionally after the page loop, even
 * when the loop had broken out on a failed page or on the 50-page cap. Anything between the old
 * cursor and "now" was then never requested again, so those scrobbles were lost permanently.
 *
 * The rule these tests pin down: advance only as far as the run actually got.
 */
class LastFmSyncCursorTest {
    private val cursorSec = 1_700_000_000L

    @Test
    fun `clean full scan advances to the newest scrobble seen`() {
        val newest = (cursorSec + 500) * 1000
        val result =
            SyncWatermark.nextCursor(
                currentCursor = cursorSec,
                minProcessedMs = (cursorSec + 100) * 1000,
                maxProcessedMs = newest,
                fullyScanned = true,
            )

        assertEquals(newest / 1000, result)
    }

    @Test
    fun `early stop advances only to the oldest scrobble seen`() {
        // Pages 1..3 were processed, then page 4 failed. Advancing to the newest scrobble would
        // skip page 4 forever; advancing to the oldest only re-reads what we already have.
        val result =
            SyncWatermark.nextCursor(
                currentCursor = cursorSec,
                minProcessedMs = (cursorSec + 100) * 1000,
                maxProcessedMs = (cursorSec + 500) * 1000,
                fullyScanned = false,
            )

        assertEquals(cursorSec + 100, result)
    }

    @Test
    fun `cursor never moves backwards`() {
        // A stale `from` window can return scrobbles older than the cursor (Last.fm's `from` is
        // inclusive). Rewinding would re-fetch history on every sync.
        val result =
            SyncWatermark.nextCursor(
                currentCursor = cursorSec,
                minProcessedMs = (cursorSec - 9_000) * 1000,
                maxProcessedMs = (cursorSec - 8_000) * 1000,
                fullyScanned = false,
            )

        assertEquals(cursorSec, result)
    }

    @Test
    fun `milliseconds truncate down to whole seconds`() {
        val result =
            SyncWatermark.nextCursor(
                currentCursor = cursorSec,
                minProcessedMs = 0,
                maxProcessedMs = (cursorSec + 7) * 1000 + 999,
                fullyScanned = true,
            )

        assertEquals(cursorSec + 7, result)
    }
}
