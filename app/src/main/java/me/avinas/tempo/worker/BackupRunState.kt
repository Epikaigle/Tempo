package me.avinas.tempo.worker

import java.io.File

/**
 * Cleanup policy for an interrupted automatic-backup attempt.
 *
 * WorkManager re-enqueues stopped work (for example when a constraint becomes
 * unavailable) and increments the attempt count without changing the work ID.
 * The archive itself contains private user data and must be removed immediately,
 * but the non-sensitive logical run ID must survive so the next attempt can find
 * a destination copy that may already have completed before cancellation arrived.
 */
internal object BackupRunState {
    private const val RUN_ID_FILE_NAME = "backup_run_id"

    fun discardSnapshotPreservingRunId(runStateDir: File) {
        if (!runStateDir.exists()) return

        val runIdFile = File(runStateDir, RUN_ID_FILE_NAME)
        val hasValidRunId = runCatching {
            runIdFile.isFile && runIdFile.readText().isNotBlank()
        }.getOrDefault(false)

        runStateDir.listFiles().orEmpty()
            .filterNot { hasValidRunId && it == runIdFile }
            .forEach { it.deleteRecursively() }

        if (!hasValidRunId) {
            runIdFile.delete()
        }
        if (runStateDir.listFiles().isNullOrEmpty()) {
            runStateDir.delete()
        }
    }
}
