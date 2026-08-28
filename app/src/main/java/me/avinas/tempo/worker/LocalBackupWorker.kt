package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.drive.BackupInterval
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.LocalBackupStorage
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Periodic device backup worker.
 *
 * It has deliberately no network constraint and never waits for Google Drive.
 * A failure is retried a few times within the current period, then the run is
 * completed so a broken destination cannot prevent the next scheduled period
 * from trying again.
 */
@HiltWorker
class LocalBackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val importExportManager: ImportExportManager,
    private val settingsManager: BackupSettingsManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocalBackupWorker"
        const val WORK_NAME = "local_backup"
        private const val CHANNEL_ID = "backup_notifications"
        private const val NOTIFICATION_ID = 2002
        private const val MAX_RUN_RETRIES = 3

        internal fun shouldRetryFailure(runAttemptCount: Int): Boolean =
            runAttemptCount < MAX_RUN_RETRIES

        internal fun isArchiveReusable(
            archiveExists: Boolean,
            archiveLength: Long,
            readyMarkerRunId: String?,
            backupRunId: String
        ): Boolean =
            archiveExists && archiveLength > 0L && readyMarkerRunId == backupRunId

        fun schedule(context: Context, intervalHours: Long) {
            if (intervalHours <= 0L) {
                cancel(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<LocalBackupWorker>(
                intervalHours,
                TimeUnit.HOURS,
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Scheduled device backup every $intervalHours hours")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic device backups")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = settingsManager.settings.first()

        if (settings.backupInterval == BackupInterval.MANUAL) {
            cleanupRunState()
            return@withContext Result.success()
        }

        if (!LocalBackupStorage.hasSelectedDirectory(context)) {
            // If Android revoked the persisted grant, remove only the stale URI
            // preference. A provider that is temporarily unavailable but still has
            // a valid persisted grant is handled below with bounded retries instead.
            if (LocalBackupStorage.getSelectedDirectoryUri(context) != null) {
                LocalBackupStorage.clearSelectedDirectory(context)
            }
            notifyTerminalFailure(
                "Device Backup Folder Needed",
                "Open Tempo and choose an automatic backup folder"
            )
            cleanupRunState()
            return@withContext Result.success()
        }

        val runStateDir = runStateDir()
        val backupRunId = getOrCreateBackupRunId(runStateDir)
        val tempFile = File(runStateDir, "backup.tempo")
        val archiveReadyMarker = File(runStateDir, "archive.ready")

        try {
            setForeground(getForegroundInfo())

            val readyMarkerRunId = runCatching {
                archiveReadyMarker.takeIf { it.exists() }?.readText()?.trim()
            }.getOrNull()
            if (!isArchiveReusable(
                    tempFile.exists(),
                    tempFile.length(),
                    readyMarkerRunId,
                    backupRunId
                )
            ) {
                tempFile.delete()
                archiveReadyMarker.delete()

                val exportResult = importExportManager.exportToFile(
                    tempFile,
                    settings.includeLocalImages
                )
                if (exportResult is ImportExportResult.Error ||
                    !tempFile.exists() || tempFile.length() <= 0L
                ) {
                    val message = if (exportResult is ImportExportResult.Error) {
                        exportResult.message
                    } else {
                        "Backup archive was not created correctly"
                    }
                    return@withContext retryOrFinish(runStateDir, message)
                }

                // ImportExportManager validates the ZIP before returning success.
                // Mark it reusable only after that validation completed.
                archiveReadyMarker.writeText(backupRunId)
            } else {
                Log.i(TAG, "Reusing device archive from an earlier attempt of this WorkManager run")
            }

            val location = LocalBackupStorage.persist(
                context = context,
                sourceFile = tempFile,
                idempotencyKey = backupRunId
            )
            Log.i(TAG, "Automatic device backup saved: $location")
            showNotification(
                "Device Backup Complete",
                "Your Tempo data has been saved to the selected device folder"
            )
            runStateDir.deleteRecursively()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Automatic device backup failed", e)
            retryOrFinish(
                runStateDir,
                e.message ?: "Could not save the automatic device backup"
            )
        }
    }

    private fun getOrCreateBackupRunId(runStateDir: File): String {
        val marker = File(runStateDir, "backup_run_id")
        if (runAttemptCount > 0) {
            val existing = runCatching { marker.readText().trim() }.getOrNull()
            if (!existing.isNullOrBlank()) return existing
        }

        // Prefix with an ISO-like timestamp so LocalBackupStorage's lexical
        // retention order remains newest-first, then add UUID entropy.
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val fresh = "${timestamp}_${UUID.randomUUID()}"
        marker.writeText(fresh)
        return fresh
    }

    private fun runStateDir(): File =
        File(context.cacheDir, "tempo_local_backup_runs/$id").apply { mkdirs() }

    private fun cleanupRunState() {
        File(context.cacheDir, "tempo_local_backup_runs/$id").deleteRecursively()
    }

    private fun retryOrFinish(runStateDir: File, message: String): Result {
        return if (shouldRetryFailure(runAttemptCount)) {
            Log.w(TAG, "Retrying device backup after failure (attempt=$runAttemptCount): $message")
            Result.retry()
        } else {
            // Returning success here is intentional for PeriodicWorkRequest: it
            // preserves the next Daily/Weekly/Monthly occurrence instead of leaving
            // this one broken run in an endless retry state.
            notifyTerminalFailure("Device Backup Failed", message)
            runStateDir.deleteRecursively()
            Result.success()
        }
    }

    private fun notifyTerminalFailure(title: String, message: String) {
        showNotification(title, message)
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backup Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Backup Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backing up Tempo on this device")
            .setContentText("Creating backup...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
