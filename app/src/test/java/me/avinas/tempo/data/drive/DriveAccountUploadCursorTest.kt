package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** Account-scoped upload progress must survive A -> B -> A switching. */
class DriveAccountUploadCursorTest {
    @Test
    fun pendingOldAccountHistorySurvivesRoundTrip() {
        val select = { scoped: Long?, legacy: Long?, switched: Boolean, current: Long, max: Long ->
            DriveHistorySyncManager.chooseAccountUploadCursor(scoped, legacy, switched, current, max)
        }

        // A has only uploaded rows <=90, but has 100 local rows.
        val newB = select(null, null, true, 90L, 100L)
        assertEquals(100L, newB) // B may not publish A's old rows.

        // B records and uploads additional history, but A still needs 91..100.
        val returnA = select(90L, null, true, 110L, 110L)
        assertEquals(90L, returnA)
        assertEquals(110L, select(110L, null, true, 101L, 120L))
    }

    @Test
    fun legacyEmailCursorCanBeRecoveredAfterStableIdMigration() {
        val select = { scoped: Long?, legacy: Long?, switched: Boolean, current: Long, max: Long ->
            DriveHistorySyncManager.chooseAccountUploadCursor(scoped, legacy, switched, current, max)
        }
        assertEquals(90L, select(null, 90L, true, 130L, 135L))
        assertEquals(75L, select(75L, 90L, true, 130L, 135L))
    }

    @Test
    fun firstExplicitOptInDoesNotDiscardExistingLocalHistory() {
        assertEquals(0L, DriveHistorySyncManager.chooseAccountUploadCursor(
            null, null, false, 0L, 5000L,
        ))
    }

    @Test
    fun deletionResetRestartsOnlySelectedAccountsCursor() {
        assertEquals(0L, DriveHistorySyncManager.chooseAccountUploadCursor(
            null, null, false, 0L, 100L,
        ))
    }
}
