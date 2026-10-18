package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.FailureClass
import me.avinas.tempo.data.analytics.FailureClassifier
import me.avinas.tempo.data.analytics.ImportProvider
import me.avinas.tempo.data.analytics.ImportRun
import me.avinas.tempo.data.analytics.RunPhase
import me.avinas.tempo.data.musicolet.MusicoletImportOptions
import me.avinas.tempo.data.musicolet.MusicoletImportService
import java.util.concurrent.TimeUnit

/**
 * Runs a Musicolet import in the foreground so it survives the user navigating away
 * or the screen turning off, and reports its progress through the service's shared
 * state flow.
 */
@HiltWorker
class MusicoletImportWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val musicoletImportService: MusicoletImportService,
        private val tracker: AnalyticsTracker,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "MusicoletImportWorker"

            private const val NOTIFICATION_CHANNEL_ID = "musicolet_import_channel"
            private const val NOTIFICATION_ID = 9300
            private const val NOTIFICATION_COMPLETION_ID = 9301
            private const val WORK_NAME = "musicolet_import"

            const val KEY_FILE_URI = "file_uri"
            const val KEY_INCLUDE_UNDATED = "include_undated"
            const val KEY_SUCCESS = "success"
            const val KEY_SONGS_FOUND = "songs_found"
            const val KEY_SONGS_IMPORTED = "songs_imported"
            const val KEY_TRACKS_IMPORTED = "tracks_imported"
            const val KEY_EVENTS_CREATED = "events_created"
            const val KEY_DUPLICATES_SKIPPED = "duplicates_skipped"
            const val KEY_UNDATED_PLAYS = "undated_plays"
            const val KEY_PERIOD_FILES = "period_files"
            const val KEY_ERROR_MESSAGE = "error_message"

            /** WorkManager result Data is capped at 10KB; stay well under it. */
            private const val MAX_ERROR_MESSAGE_CHARS = 2_000

            fun createNotificationChannel(context: Context) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            "Musicolet Import",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = "Progress notifications for the Musicolet history import"
                        }
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.createNotificationChannel(channel)
                }
            }

            fun enqueueImport(
                context: Context,
                fileUri: String,
                options: MusicoletImportOptions,
            ): java.util.UUID {
                val inputData =
                    workDataOf(
                        KEY_FILE_URI to fileUri,
                        KEY_INCLUDE_UNDATED to options.includeUndatedPlays,
                    )

                val request =
                    OneTimeWorkRequestBuilder<MusicoletImportWorker>()
                        .setInputData(inputData)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(WORK_NAME)
                        .build()

                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
                Log.i(TAG, "Enqueued Musicolet import")
                return request.id
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Cancelled Musicolet import")
            }

            fun isRunning(context: Context): Boolean =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }

        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                Log.i(TAG, "Starting Musicolet import worker")
                val startedAt = System.currentTimeMillis()

                try {
                    setForeground(createForegroundInfo("Preparing import...", 0))
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException on a WorkManager restart
                    // while the app is in the background: continue as background work.
                    // Deterministic reconstruction plus fingerprint dedup make a re-run
                    // safe, so being killed here costs time and nothing else.
                    Log.w(TAG, "Foreground start not allowed; importing in background", e)
                }

                // Mirror the service's shared flow into the notification so a long import
                // shows live progress rather than a frozen "Preparing...". The same flow
                // drives the screen.
                val progressJob =
                    launch {
                        var lastNotifyAt = 0L
                        musicoletImportService.importState.collect { state ->
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyAt < 1_000L) return@collect
                            when (state) {
                                is MusicoletImportService.ImportState.Parsing -> {
                                    showProgress(
                                        "Reading ${state.fileName.ifBlank { "backup" }}",
                                        state.filesProcessed,
                                        state.totalFiles,
                                    )
                                }

                                is MusicoletImportService.ImportState.Importing -> {
                                    showProgress(
                                        "Importing ${state.current}/${state.total} plays",
                                        state.current.toInt(),
                                        state.total.toInt(),
                                    )
                                }

                                else -> return@collect
                            }
                            lastNotifyAt = now
                        }
                    }

                val uriString = inputData.getString(KEY_FILE_URI)
                if (uriString.isNullOrBlank()) {
                    Log.e(TAG, "No file URI provided")
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "No file selected"))
                }

                val uri =
                    try {
                        Uri.parse(uriString)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse URI: $uriString", e)
                        null
                    }
                if (uri == null) {
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.UNKNOWN, startedAt = startedAt)
                    return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Invalid file"))
                }

                val options =
                    MusicoletImportOptions(
                        includeUndatedPlays = inputData.getBoolean(KEY_INCLUDE_UNDATED, false),
                    )

                try {
                    val result = musicoletImportService.importFromUris(applicationContext, listOf(uri), options)

                    if (result.isSuccess) {
                        reportImport(
                            RunPhase.COMPLETED,
                            records = result.eventsCreated,
                            failure = null,
                            startedAt = startedAt,
                        )
                        showCompletion(result)
                        Result.success(
                            workDataOf(
                                KEY_SUCCESS to true,
                                KEY_SONGS_FOUND to result.songsFound,
                                KEY_SONGS_IMPORTED to result.songsImported,
                                KEY_TRACKS_IMPORTED to result.tracksImported,
                                KEY_EVENTS_CREATED to result.eventsCreated,
                                KEY_DUPLICATES_SKIPPED to result.duplicatesSkipped,
                                KEY_UNDATED_PLAYS to result.undatedPlays,
                                KEY_PERIOD_FILES to result.periodFilesRead,
                            ),
                        )
                    } else {
                        val message =
                            result.errors.joinToString("; ").take(MAX_ERROR_MESSAGE_CHARS)
                                .ifBlank { "No Musicolet listening data found." }
                        reportImport(
                            RunPhase.FAILED,
                            records = result.eventsCreated,
                            failure = FailureClass.PARSE,
                            startedAt = startedAt,
                        )
                        showFailure(message)
                        Result.failure(
                            workDataOf(KEY_SUCCESS to false, KEY_ERROR_MESSAGE to message),
                        )
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Import ran out of memory", e)
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClass.OUT_OF_MEMORY, startedAt = startedAt)
                    val message = "Not enough memory to read this backup."
                    showFailure(message)
                    Result.failure(workDataOf(KEY_SUCCESS to false, KEY_ERROR_MESSAGE to message))
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed with exception", e)
                    reportImport(RunPhase.FAILED, records = 0, failure = FailureClassifier.of(e), startedAt = startedAt)
                    val message = e.message ?: "Import failed"
                    showFailure(message)
                    Result.failure(
                        workDataOf(KEY_SUCCESS to false, KEY_ERROR_MESSAGE to message.take(MAX_ERROR_MESSAGE_CHARS)),
                    )
                } finally {
                    progressJob.cancel()
                    cancelProgressNotification()
                }
            }

        override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Importing Musicolet history...", 0)

        private fun createForegroundInfo(
            message: String,
            progress: Int,
        ): ForegroundInfo {
            // The channel must exist before every promotion: WorkManager calls this for
            // expedited work, and a missing channel is answered by killing the process.
            createNotificationChannel(applicationContext)

            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Importing Musicolet History")
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setProgress(100, progress, progress == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        /** Never carries a message — only the closed [FailureClass] category. */
        private fun reportImport(
            phase: RunPhase,
            records: Int,
            failure: FailureClass?,
            startedAt: Long,
        ) {
            tracker.track(
                ImportRun(
                    provider = ImportProvider.MUSICOLET,
                    phase = phase,
                    records = records,
                    failure = failure,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }

        private fun showProgress(
            message: String,
            current: Int,
            total: Int,
        ) {
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Importing Musicolet History")
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setProgress(total.coerceAtLeast(1), current, current == 0)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }

        private fun showCompletion(result: MusicoletImportService.ImportResult) {
            val message =
                buildString {
                    append("${result.eventsCreated} plays imported")
                    if (result.undatedPlays > 0 && result.anchoredUndatedPlays == 0L) {
                        append(", ${result.undatedPlays} undated left out")
                    }
                }
            showFinal("Musicolet Import Complete", message)
        }

        private fun showFailure(error: String) {
            showFinal("Musicolet Import Failed", error)
        }

        private fun showFinal(
            title: String,
            message: String,
        ) {
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            manager.notify(NOTIFICATION_COMPLETION_ID, notification)
        }

        private fun cancelProgressNotification() {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }
