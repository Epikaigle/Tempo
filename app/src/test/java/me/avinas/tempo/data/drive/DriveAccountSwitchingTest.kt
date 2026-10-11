package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAccountSwitchingTest {

    @Test
    fun `rejected batch record serializes and parses properly`() {
        val record = DriveHistorySyncManager.Companion.RejectedBatchRecord(
            fileId = "file-123",
            fileName = "tempo_history_device_1_0.json.gz",
            reason = "Malformed\tpayload with\nnewline",
            recordedAt = 1700000000000L
        )
        val serialized = record.serialize()
        val parsed = DriveHistorySyncManager.Companion.RejectedBatchRecord.parse(serialized)

        assertNotNull(parsed)
        assertEquals("file-123", parsed?.fileId)
        assertEquals("tempo_history_device_1_0.json.gz", parsed?.fileName)
        // Tabs and newlines should be sanitized to single spaces
        assertEquals("Malformed payload with newline", parsed?.reason)
        assertEquals(1700000000000L, parsed?.recordedAt)
    }

    @Test
    fun `rejected batch record handles corrupt raw strings gracefully`() {
        assertNull(DriveHistorySyncManager.Companion.RejectedBatchRecord.parse("invalid"))
        assertNull(DriveHistorySyncManager.Companion.RejectedBatchRecord.parse("id\tname"))
    }

    @Test
    fun `incremental listing never clears older rejected file warnings`() {
        val older = "file-older\tcorrupt.json.gz\tBad metadata\t1700000000000"
        val recent = "file-recent\tbroken.json.gz\tBad payload\t1700100000000"
        val records = setOf(older, recent)

        assertEquals(records, DriveHistorySyncManager.retainedRejectedRecords(
            records, setOf("file-recent"), completeListing = false))
        assertEquals(setOf(recent), DriveHistorySyncManager.retainedRejectedRecords(
            records, setOf("file-recent"), completeListing = true))
        assertTrue(DriveHistorySyncManager.retainedRejectedRecords(
            records, emptySet(), completeListing = true).isEmpty())
    }

    @Test
    fun `per account restore keys include download cursor, accepted disable version, and rejected files`() {
        val keys = listOf(
            "download_created_cursor:google-a",
            "accepted_disable_marker_version:google-a",
            "drive_rejected_files:google-a",
            "upload_cursor:google-a",
            "invalid_drive_export_ids:google-a",
        )
        for (key in keys) {
            assertTrue("Key $key must be recognized as per-account restore state key",
                DriveHistorySyncManager.isPerAccountRestoreStateKey(key))
        }
    }
}
