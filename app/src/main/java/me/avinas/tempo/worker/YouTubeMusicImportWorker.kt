package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.avinas.tempo.R
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import java.util.concurrent.TimeUnit

@HiltWorker
class YouTubeMusicImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val youTubeMusicImportService: YouTubeMusicImportService
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "YouTubeMusicImportWorker"

        private const val NOTIFICATION_CHANNEL_ID = "youtube_music_import_channel"
        private const val NOTIFICATION_ID = 9200
        private const val NOTIFICATION_COMPLETION_ID = 9201

        private const val WORK_NAME = "youtube_music_import"

        const val KEY_FILE_URIS = "file_uris"
        const val KEY_SUCCESS = "success"
        const val KEY_TRACKS_IMPORTED = "tracks_imported"
        const val KEY_EVENTS_CREATED = "events_created"
        const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
        const val KEY_PODCASTS_SKIPPED = "podcasts_skipped"
        const val KEY_FILES_PROCESSED = "files_processed"
        const val KEY_TOTAL_ENTRIES = "total_entries"
        const val KEY_ERROR_MESSAGE = "error_message"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "YouTube Music Data Import",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress notifications for YouTube Music data import"
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun enqueueImport(
            context: Context,
            fileUris: List<String>
        ): java.util.UUID {
            val inputData = workDataOf(
                KEY_FILE_URIS to fileUris.toTypedArray()
            )

            val workRequest = OneTimeWorkRequestBuilder<YouTubeMusicImportWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("youtube_music_import")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )

            Log.i(TAG, "Enqueued YouTube Music import (${fileUris.size} files)")
            return workRequest.id
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled YouTube Music import")
        }

        fun isRunning(context: Context): Boolean {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
            return workInfos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting YouTube Music import worker")

        showProgressNotification("Preparing import...", 0, 100)

        val uriStrings = inputData.getStringArray(KEY_FILE_URIS)
        if (uriStrings.isNullOrEmpty()) {
            Log.e(TAG, "No file URIs provided")
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No files selected"))
        }

        val uris = uriStrings.mapNotNull {
            try {
                android.net.Uri.parse(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse URI: $it", e)
                null
            }
        }

        if (uris.isEmpty()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No valid files found"))
        }

        try {
            val result = youTubeMusicImportService.importFromUris(applicationContext, uris)

            if (result.isSuccess) {
                showCompletionNotification(result)
                Result.success(workDataOf(
                    KEY_SUCCESS to true,
                    KEY_TRACKS_IMPORTED to result.tracksImported,
                    KEY_EVENTS_CREATED to result.eventsCreated,
                    KEY_DUPLICATES_SKIPPED to result.duplicatesSkipped,
                    KEY_PODCASTS_SKIPPED to result.podcastsSkipped,
                    KEY_FILES_PROCESSED to result.filesProcessed,
                    KEY_TOTAL_ENTRIES to result.totalEntries
                ))
            } else {
                val errorMsg = result.errors.joinToString("; ")
                showFailureNotification(errorMsg)
                Result.failure(workDataOf(
                    KEY_SUCCESS to false,
                    KEY_ERROR_MESSAGE to errorMsg
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed with exception", e)
            showFailureNotification(e.message ?: "Unknown error")
            Result.failure(workDataOf(
                KEY_SUCCESS to false,
                KEY_ERROR_MESSAGE to (e.message ?: "Import failed")
            ))
        } finally {
            cancelProgressNotification()
        }
    }

    private fun showProgressNotification(message: String, current: Int, total: Int) {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Importing YouTube Music Data")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(total, current, current == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(result: YouTubeMusicImportService.ImportResult) {
        val message = buildString {
            append("${result.tracksImported} tracks imported, ")
            append("${result.eventsCreated} listening events created")
            if (result.podcastsSkipped > 0) {
                append(", ${result.podcastsSkipped} podcasts skipped")
            }
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("YouTube Music Import Complete")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
    }

    private fun showFailureNotification(error: String) {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("YouTube Music Import Failed")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.notify(NOTIFICATION_COMPLETION_ID, notification)
    }

    private fun cancelProgressNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
