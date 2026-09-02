package me.avinas.tempo.worker

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRunStateTest {

    @Test
    fun `cancellation removes private snapshot but preserves valid run id`() {
        val runStateDir = Files.createTempDirectory("tempo-backup-run").toFile()
        try {
            val runId = java.io.File(runStateDir, "backup_run_id").apply {
                writeText("stable-run-id")
            }
            java.io.File(runStateDir, "backup.tempo").writeText("private backup data")
            java.io.File(runStateDir, "archive.ready").writeText("stable-run-id")
            java.io.File(runStateDir, "temporary").apply {
                mkdir()
                resolve("part").writeText("partial data")
            }

            BackupRunState.discardSnapshotPreservingRunId(runStateDir)

            assertTrue(runStateDir.isDirectory)
            assertTrue(runId.isFile)
            assertEquals("stable-run-id", runId.readText())
            assertEquals(listOf("backup_run_id"), runStateDir.listFiles()!!.map { it.name })
        } finally {
            runStateDir.deleteRecursively()
        }
    }

    @Test
    fun `blank run id is not retained after cancellation`() {
        val runStateDir = Files.createTempDirectory("tempo-backup-run").toFile()
        try {
            java.io.File(runStateDir, "backup_run_id").writeText("   ")
            java.io.File(runStateDir, "backup.tempo").writeText("private backup data")

            BackupRunState.discardSnapshotPreservingRunId(runStateDir)

            assertFalse(runStateDir.exists())
        } finally {
            runStateDir.deleteRecursively()
        }
    }
}
