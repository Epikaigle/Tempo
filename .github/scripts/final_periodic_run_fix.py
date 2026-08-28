from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"pattern not found: {label}")
    return text.replace(old, new, 1)


# --- DriveBackupWorker: unique logical run id per periodic period, stable across retries ---
path = Path("app/src/main/java/me/avinas/tempo/worker/DriveBackupWorker.kt")
text = path.read_text()
text = replace_once(
    text,
    "import me.avinas.tempo.data.drive.BackupSettingsManager\n",
    "import me.avinas.tempo.data.drive.BackupInterval\nimport me.avinas.tempo.data.drive.BackupSettingsManager\n",
    "drive BackupInterval import",
)
text = replace_once(
    text,
    "import java.io.File\nimport java.util.concurrent.TimeUnit\n",
    "import java.io.File\nimport java.util.UUID\nimport java.util.concurrent.TimeUnit\n",
    "drive UUID import",
)
text = replace_once(
    text,
    '''        internal fun isArchiveReusable(\n            archiveExists: Boolean,\n            archiveLength: Long,\n            readyMarkerExists: Boolean\n        ): Boolean = archiveExists && archiveLength > 0L && readyMarkerExists\n''',
    '''        internal fun isArchiveReusable(\n            archiveExists: Boolean,\n            archiveLength: Long,\n            readyMarkerRunId: String?,\n            backupRunId: String\n        ): Boolean =\n            archiveExists && archiveLength > 0L && readyMarkerRunId == backupRunId\n''',
    "drive archive helper",
)
text = replace_once(
    text,
    '''        // A worker already running/retrying may observe a sign-out before the\n        // scheduler cancellation reaches it. Finish quietly instead of touching\n        // Drive with stale credentials.\n        if (!settings.isGoogleDriveEnabled) {\n            cleanupRunState()\n            return@withContext Result.success()\n        }\n''',
    '''        // A worker already running/retrying may observe Manual/sign-out before\n        // scheduler cancellation reaches it. Finish quietly instead of creating\n        // another automatic backup with stale settings.\n        if (settings.backupInterval == BackupInterval.MANUAL ||\n            !settings.isGoogleDriveEnabled\n        ) {\n            cleanupRunState()\n            return@withContext Result.success()\n        }\n''',
    "drive stale worker guard",
)
text = replace_once(
    text,
    '''        val runStateDir = runStateDir()\n        val tempFile = File(runStateDir, "backup.tempo")\n        val archiveReadyMarker = File(runStateDir, "archive.ready")\n''',
    '''        val runStateDir = runStateDir()\n        // A PeriodicWorkRequest keeps the same WorkRequest UUID across periods.\n        // runAttemptCount, however, resets between periods. Generate a fresh\n        // logical backup id on the first attempt of each period, then persist and\n        // reuse it for every retry of that same period.\n        val backupRunId = getOrCreateBackupRunId(runStateDir)\n        val tempFile = File(runStateDir, "backup.tempo")\n        val archiveReadyMarker = File(runStateDir, "archive.ready")\n''',
    "drive run id setup",
)
text = replace_once(
    text,
    '''            if (!isArchiveReusable(tempFile.exists(), tempFile.length(), archiveReadyMarker.exists())) {\n                tempFile.delete()\n                archiveReadyMarker.delete()\n''',
    '''            val readyMarkerRunId = runCatching {\n                archiveReadyMarker.takeIf { it.exists() }?.readText()?.trim()\n            }.getOrNull()\n            if (!isArchiveReusable(\n                    tempFile.exists(),\n                    tempFile.length(),\n                    readyMarkerRunId,\n                    backupRunId\n                )\n            ) {\n                tempFile.delete()\n                archiveReadyMarker.delete()\n''',
    "drive archive reuse",
)
text = replace_once(
    text,
    '                archiveReadyMarker.writeText("ready")\n',
    '                archiveReadyMarker.writeText(backupRunId)\n',
    "drive archive marker",
)
text = replace_once(
    text,
    '                idempotencyKey = id.toString()\n',
    '                idempotencyKey = backupRunId\n',
    "drive idempotency key",
)
text = replace_once(
    text,
    '''    private fun runStateDir(): File =\n        File(context.cacheDir, "tempo_drive_backup_runs/$id").apply { mkdirs() }\n''',
    '''    private fun getOrCreateBackupRunId(runStateDir: File): String {\n        val marker = File(runStateDir, "backup_run_id")\n\n        // WorkManager documents that runAttemptCount resets between periods. On\n        // retries (> 0), keep the marker written by this period's first attempt.\n        if (runAttemptCount > 0) {\n            val existing = runCatching { marker.readText().trim() }.getOrNull()\n            if (!existing.isNullOrBlank()) return existing\n        }\n\n        val fresh = UUID.randomUUID().toString()\n        marker.writeText(fresh)\n        return fresh\n    }\n\n    private fun runStateDir(): File =\n        File(context.cacheDir, "tempo_drive_backup_runs/$id").apply { mkdirs() }\n''',
    "drive run id helper",
)
path.write_text(text)


# --- LocalBackupWorker: fresh period archive + stable idempotent destination on retries ---
path = Path("app/src/main/java/me/avinas/tempo/worker/LocalBackupWorker.kt")
text = path.read_text()
text = replace_once(
    text,
    "import me.avinas.tempo.data.drive.BackupSettingsManager\n",
    "import me.avinas.tempo.data.drive.BackupInterval\nimport me.avinas.tempo.data.drive.BackupSettingsManager\n",
    "local BackupInterval import",
)
text = replace_once(
    text,
    "import java.io.File\nimport java.util.concurrent.TimeUnit\n",
    "import java.io.File\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale\nimport java.util.UUID\nimport java.util.concurrent.TimeUnit\n",
    "local run id imports",
)
text = replace_once(
    text,
    '''        internal fun isArchiveReusable(\n            archiveExists: Boolean,\n            archiveLength: Long,\n            readyMarkerExists: Boolean\n        ): Boolean = archiveExists && archiveLength > 0L && readyMarkerExists\n''',
    '''        internal fun isArchiveReusable(\n            archiveExists: Boolean,\n            archiveLength: Long,\n            readyMarkerRunId: String?,\n            backupRunId: String\n        ): Boolean =\n            archiveExists && archiveLength > 0L && readyMarkerRunId == backupRunId\n''',
    "local archive helper",
)
text = replace_once(
    text,
    '''        val settings = settingsManager.settings.first()\n\n        if (!LocalBackupStorage.hasSelectedDirectory(context)) {\n''',
    '''        val settings = settingsManager.settings.first()\n\n        if (settings.backupInterval == BackupInterval.MANUAL) {\n            cleanupRunState()\n            return@withContext Result.success()\n        }\n\n        if (!LocalBackupStorage.hasSelectedDirectory(context)) {\n''',
    "local manual guard",
)
text = replace_once(
    text,
    '''        val runStateDir = runStateDir()\n        val tempFile = File(runStateDir, "backup.tempo")\n        val archiveReadyMarker = File(runStateDir, "archive.ready")\n''',
    '''        val runStateDir = runStateDir()\n        val backupRunId = getOrCreateBackupRunId(runStateDir)\n        val tempFile = File(runStateDir, "backup.tempo")\n        val archiveReadyMarker = File(runStateDir, "archive.ready")\n''',
    "local run id setup",
)
text = replace_once(
    text,
    '''            if (!isArchiveReusable(\n                    tempFile.exists(),\n                    tempFile.length(),\n                    archiveReadyMarker.exists()\n                )\n            ) {\n''',
    '''            val readyMarkerRunId = runCatching {\n                archiveReadyMarker.takeIf { it.exists() }?.readText()?.trim()\n            }.getOrNull()\n            if (!isArchiveReusable(\n                    tempFile.exists(),\n                    tempFile.length(),\n                    readyMarkerRunId,\n                    backupRunId\n                )\n            ) {\n''',
    "local archive reuse",
)
text = replace_once(
    text,
    '                archiveReadyMarker.writeText("ready")\n',
    '                archiveReadyMarker.writeText(backupRunId)\n',
    "local archive marker",
)
text = replace_once(
    text,
    '            val location = LocalBackupStorage.persist(context, tempFile)\n',
    '''            val location = LocalBackupStorage.persist(\n                context = context,\n                sourceFile = tempFile,\n                idempotencyKey = backupRunId\n            )\n''',
    "local idempotent persist",
)
text = replace_once(
    text,
    '''    private fun runStateDir(): File =\n        File(context.cacheDir, "tempo_local_backup_runs/$id").apply { mkdirs() }\n''',
    '''    private fun getOrCreateBackupRunId(runStateDir: File): String {\n        val marker = File(runStateDir, "backup_run_id")\n        if (runAttemptCount > 0) {\n            val existing = runCatching { marker.readText().trim() }.getOrNull()\n            if (!existing.isNullOrBlank()) return existing\n        }\n\n        // Prefix with an ISO-like timestamp so LocalBackupStorage's lexical\n        // retention order remains newest-first, then add UUID entropy.\n        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())\n        val fresh = "${timestamp}_${UUID.randomUUID()}"\n        marker.writeText(fresh)\n        return fresh\n    }\n\n    private fun runStateDir(): File =\n        File(context.cacheDir, "tempo_local_backup_runs/$id").apply { mkdirs() }\n''',
    "local run id helper",
)
path.write_text(text)


# --- LocalBackupStorage: retry-safe deterministic file name ---
path = Path("app/src/main/java/me/avinas/tempo/data/drive/LocalBackupStorage.kt")
text = path.read_text()
text = replace_once(
    text,
    '''    fun persist(context: Context, sourceFile: File): String {\n''',
    '''    fun persist(\n        context: Context,\n        sourceFile: File,\n        idempotencyKey: String? = null\n    ): String {\n''',
    "local storage persist signature",
)
text = replace_once(
    text,
    '''        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())\n        val fileName = "$FILE_PREFIX$timestamp.tempo"\n\n        return persistToTree(context, treeUri, sourceFile, fileName)\n''',
    '''        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())\n        val fileName = buildAutomaticBackupFileName(timestamp, idempotencyKey)\n\n        return persistToTree(context, treeUri, sourceFile, fileName)\n''',
    "local storage file name",
)
insert_after = '''    fun setSelectedDirectory(context: Context, uri: Uri) {\n        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n            .edit()\n            .putString(DIRECTORY_URI_KEY, uri.toString())\n            .apply()\n    }\n\n'''
addition = '''    internal fun buildAutomaticBackupFileName(\n        timestamp: String,\n        idempotencyKey: String?\n    ): String {\n        val stableKey = idempotencyKey\n            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")\n            ?.take(180)\n            ?.takeIf { it.isNotBlank() }\n        return "$FILE_PREFIX${stableKey ?: timestamp}.tempo"\n    }\n\n'''
if addition not in text:
    text = replace_once(text, insert_after, insert_after + addition, "local storage name helper")

old_create = '''        val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)\n        val outputUri = DocumentsContract.createDocument(\n            resolver,\n            directoryUri,\n            MIME_TYPE_ZIP,\n            fileName\n        ) ?: throw IOException("Unable to create a backup file in the selected folder")\n'''
new_create = '''        val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)\n\n        // A worker retry must not create a second file if the previous process was\n        // killed after the copy completed but before WorkManager recorded success.\n        // The worker supplies a stable per-period file name, so exact-name lookup\n        // gives the local destination the same idempotency guarantee as Drive.\n        val existing = findExistingBackup(context, treeUri, treeDocumentId, fileName)\n        if (existing != null) {\n            val (existingUri, existingSize) = existing\n            if (existingSize == sourceFile.length()) {\n                cleanupOldBackups(context, treeUri, treeDocumentId)\n                Log.i(TAG, "Reusing already-saved automatic local backup: $existingUri")\n                return existingUri.toString()\n            }\n\n            val deleted = runCatching {\n                DocumentsContract.deleteDocument(resolver, existingUri)\n            }.getOrDefault(false)\n            if (!deleted) {\n                throw IOException("Unable to replace an incomplete automatic backup file")\n            }\n        }\n\n        val outputUri = DocumentsContract.createDocument(\n            resolver,\n            directoryUri,\n            MIME_TYPE_ZIP,\n            fileName\n        ) ?: throw IOException("Unable to create a backup file in the selected folder")\n'''
text = replace_once(text, old_create, new_create, "local storage existing retry file")

marker = '''    private fun cleanupOldBackups(context: Context, treeUri: Uri, treeDocumentId: String) {\n'''
helper = '''    private fun findExistingBackup(\n        context: Context,\n        treeUri: Uri,\n        treeDocumentId: String,\n        fileName: String\n    ): Pair<Uri, Long?>? {\n        val resolver = context.contentResolver\n        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)\n        val projection = arrayOf(\n            DocumentsContract.Document.COLUMN_DOCUMENT_ID,\n            DocumentsContract.Document.COLUMN_DISPLAY_NAME,\n            DocumentsContract.Document.COLUMN_SIZE\n        )\n\n        return try {\n            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->\n                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)\n                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)\n                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)\n\n                while (cursor.moveToNext()) {\n                    if (cursor.getString(nameColumn) != fileName) continue\n                    val documentId = cursor.getString(idColumn)\n                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {\n                        cursor.getLong(sizeColumn)\n                    } else {\n                        null\n                    }\n                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)\n                    return@use uri to size\n                }\n                null\n            }\n        } catch (e: Exception) {\n            Log.w(TAG, "Unable to check for an existing retry-safe local backup", e)\n            null\n        }\n    }\n\n'''
if helper not in text:
    text = replace_once(text, marker, helper + marker, "local storage existing lookup helper")
path.write_text(text)


# --- Regression tests ---
path = Path("app/src/test/java/me/avinas/tempo/worker/DriveBackupWorkerStateTest.kt")
text = path.read_text()
text = replace_once(
    text,
    "import me.avinas.tempo.data.drive.GoogleDriveService\n",
    "import me.avinas.tempo.data.drive.GoogleDriveService\nimport me.avinas.tempo.data.drive.LocalBackupStorage\n",
    "test local storage import",
)
text = replace_once(
    text,
    "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\n",
    "import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNotEquals\nimport org.junit.Assert.assertTrue\n",
    "test asserts",
)
text = replace_once(
    text,
    '''    fun driveArchiveReuseRequiresCompletedNonEmptyArchive() {\n        assertTrue(DriveBackupWorker.isArchiveReusable(true, 42L, true))\n        assertFalse(DriveBackupWorker.isArchiveReusable(false, 42L, true))\n        assertFalse(DriveBackupWorker.isArchiveReusable(true, 0L, true))\n        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, false))\n    }\n\n    @Test\n    fun localArchiveReuseRequiresCompletedNonEmptyArchive() {\n        assertTrue(LocalBackupWorker.isArchiveReusable(true, 42L, true))\n        assertFalse(LocalBackupWorker.isArchiveReusable(false, 42L, true))\n        assertFalse(LocalBackupWorker.isArchiveReusable(true, 0L, true))\n        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, false))\n    }\n''',
    '''    fun driveArchiveReuseRequiresSameLogicalRun() {\n        assertTrue(DriveBackupWorker.isArchiveReusable(true, 42L, "run-a", "run-a"))\n        assertFalse(DriveBackupWorker.isArchiveReusable(false, 42L, "run-a", "run-a"))\n        assertFalse(DriveBackupWorker.isArchiveReusable(true, 0L, "run-a", "run-a"))\n        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, null, "run-a"))\n        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, "old-period", "new-period"))\n    }\n\n    @Test\n    fun localArchiveReuseRequiresSameLogicalRun() {\n        assertTrue(LocalBackupWorker.isArchiveReusable(true, 42L, "run-a", "run-a"))\n        assertFalse(LocalBackupWorker.isArchiveReusable(false, 42L, "run-a", "run-a"))\n        assertFalse(LocalBackupWorker.isArchiveReusable(true, 0L, "run-a", "run-a"))\n        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, null, "run-a"))\n        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, "old-period", "new-period"))\n    }\n\n    @Test\n    fun localRetryFileNameIsStableForOnePeriodAndChangesForTheNext() {\n        val first = LocalBackupStorage.buildAutomaticBackupFileName(\n            timestamp = "2099-01-01_00-00-00-000",\n            idempotencyKey = "2026-08-28_23-00-00-000_run-a"\n        )\n        val retry = LocalBackupStorage.buildAutomaticBackupFileName(\n            timestamp = "2099-12-31_23-59-59-999",\n            idempotencyKey = "2026-08-28_23-00-00-000_run-a"\n        )\n        val nextPeriod = LocalBackupStorage.buildAutomaticBackupFileName(\n            timestamp = "2099-12-31_23-59-59-999",\n            idempotencyKey = "2026-08-29_23-00-00-000_run-b"\n        )\n\n        assertEquals(first, retry)\n        assertNotEquals(first, nextPeriod)\n    }\n''',
    "test archive/idempotency regressions",
)
path.write_text(text)


# Hard fail if the original periodic-UUID bug is still present anywhere.
all_text = "\n".join(
    Path(p).read_text()
    for p in [
        "app/src/main/java/me/avinas/tempo/worker/DriveBackupWorker.kt",
        "app/src/main/java/me/avinas/tempo/worker/LocalBackupWorker.kt",
        "app/src/main/java/me/avinas/tempo/data/drive/LocalBackupStorage.kt",
    ]
)
if "idempotencyKey = id.toString()" in all_text:
    raise SystemExit("periodic WorkRequest UUID is still being used as a backup idempotency key")
