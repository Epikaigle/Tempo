package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.BackupStatus
import me.avinas.tempo.data.drive.DriveBackupResult
import me.avinas.tempo.data.drive.GoogleAuthManager
import me.avinas.tempo.data.drive.GoogleDriveService
import me.avinas.tempo.data.importexport.ImportExportManager
import me.avinas.tempo.data.importexport.ImportExportResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic Google Drive backup worker.
 *
 * Device backups intentionally use [LocalBackupWorker] instead. Keeping the two
 * schedules independent means a Drive retry (no Wi-Fi, expired authorization,
 * server outage, etc.) can never postpone the next Daily/Weekly/Monthly device
 * backup.
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val importExportManager: ImportExportManager,
    private val driveService: GoogleDriveService,
    private val authManager: GoogleAuthManager,
    private val settingsManager: BackupSettingsManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DriveBackupWorker"
        const val WORK_NAME = "drive_backup"
        const val MANUAL_WORK_NAME = "${WORK_NAME}_manual"
        private const val CHANNEL_ID = "backup_notifications"
        private const val NOTIFICATION_ID = 2001

        internal fun isArchiveReusable(
            archiveExists: Boolean,
            archiveLength: Long,
            readyMarkerExists: Boolean
        ): Boolean = archiveExists && archiveLength > 0L && readyMarkerExists

        internal fun networkSatisfiesDrivePolicy(
            hasValidatedInternet: Boolean,
            wifiOnly: Boolean,
            isWifiTransport: Boolean
        ): Boolean = hasValidatedInternet && (!wifiOnly || isWifiTransport)

        fun schedule(
            context: Context,
            intervalHours: Long,
            wifiOnly: Boolean = true
        ) {
            if (intervalHours <= 0L) {
                cancel(context)
                return
            }

            // CONNECTED prevents pointless executions while fully offline. Exact
            // Wi-Fi-only behavior is checked in doWork because NetworkType.UNMETERED
            // is not equivalent to Wi-Fi.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(
                intervalHours,
                TimeUnit.HOURS,
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
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

            Log.i(TAG, "Scheduled Drive backup every $intervalHours hours (Wi-Fi only=$wifiOnly)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic Drive backups")
        }

        fun scheduleOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("drive_backup_manual")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = settingsManager.settings.first()

        // A worker already running/retrying may observe a sign-out before the
        // scheduler cancellation reaches it. Finish quietly instead of touching
        // Drive with stale credentials.
        if (!settings.isGoogleDriveEnabled) {
            cleanupRunState()
            return@withContext Result.success()
        }

        if (!isDriveNetworkAvailable(settings.wifiOnly)) {
            Log.i(TAG, "Configured Drive network is not available yet; retrying later")
            return@withContext Result.retry()
        }

        val runStateDir = runStateDir()
        val tempFile = File(runStateDir, "backup.tempo")
        val archiveReadyMarker = File(runStateDir, "archive.ready")

        try {
            setForeground(getForegroundInfo())
            settingsManager.updateLastBackup(BackupStatus.IN_PROGRESS)

            if (!authManager.isSignedIn.value) {
                Log.i(TAG, "Restoring Google session silently for scheduled Drive backup")
                if (!authManager.restoreSessionSilently()) {
                    settingsManager.updateLastBackup(BackupStatus.FAILED)
                    notifyFirstAttempt(
                        "Drive Backup Failed",
                        "Open Tempo and sign in to Google again to resume automatic Drive backups"
                    )
                    return@withContext Result.retry()
                }
            }

            if (authManager.getAccessToken() == null) {
                settingsManager.updateLastBackup(BackupStatus.FAILED)
                notifyFirstAttempt(
                    "Drive Backup Failed",
                    "Google authorization expired. Open Tempo and sign in again."
                )
                return@withContext Result.retry()
            }

            if (!isArchiveReusable(tempFile.exists(), tempFile.length(), archiveReadyMarker.exists())) {
                tempFile.delete()
                archiveReadyMarker.delete()
                setProgress(workDataOf("phase" to "Creating Drive backup..."))

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
                    settingsManager.updateLastBackup(BackupStatus.FAILED)
                    notifyFirstAttempt("Drive Backup Failed", message)
                    return@withContext Result.retry()
                }
                archiveReadyMarker.writeText("ready")
            } else {
                Log.i(TAG, "Reusing Drive archive from an earlier attempt of this WorkManager run")
            }

            setProgress(workDataOf("phase" to "Uploading to Google Drive..."))
            when (val uploadResult = driveService.uploadBackup(
                tempFile,
                idempotencyKey = id.toString()
            ) { progress ->
                setProgressAsync(
                    workDataOf(
                        "phase" to "Uploading...",
                        "progress" to progress
                    )
                )
            }) {
                is DriveBackupResult.Success -> {
                    settingsManager.updateLastBackup(BackupStatus.SUCCESS)
                    showNotification(
                        "Drive Backup Complete",
                        "Your Tempo data has been backed up to Google Drive"
                    )
                    runStateDir.deleteRecursively()
                    Result.success()
                }

                is DriveBackupResult.Error -> {
                    settingsManager.updateLastBackup(BackupStatus.FAILED)
                    notifyFirstAttempt("Drive Backup Failed", uploadResult.message)
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled Drive backup failed", e)
            settingsManager.updateLastBackup(BackupStatus.FAILED)
            notifyFirstAttempt("Drive Backup Failed", "An unexpected error occurred")
            Result.retry()
        }
    }

    private fun runStateDir(): File =
        File(context.cacheDir, "tempo_drive_backup_runs/$id").apply { mkdirs() }

    private fun cleanupRunState() {
        File(context.cacheDir, "tempo_drive_backup_runs/$id").deleteRecursively()
    }

    /**
     * Enforce the user's network policy against Android's active route.
     *
     * Looking for any Wi-Fi network in ConnectivityManager.allNetworks can report
     * a secondary Wi-Fi transport while Android is actually routing app traffic
     * over cellular. For "Wi-Fi only" the safer rule is therefore: the validated
     * active network itself must advertise TRANSPORT_WIFI. This intentionally
     * prefers never using cellular over trying to infer a VPN's underlying route.
     */
    private fun isDriveNetworkAvailable(wifiOnly: Boolean): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        val hasValidatedInternet =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isWifiTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        return networkSatisfiesDrivePolicy(
            hasValidatedInternet = hasValidatedInternet,
            wifiOnly = wifiOnly,
            isWifiTransport = isWifiTransport
        )
    }

    private fun notifyFirstAttempt(title: String, message: String) {
        if (runAttemptCount == 0) {
            showNotification(title, message)
        }
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
            .setContentTitle("Backing up Tempo to Google Drive")
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
