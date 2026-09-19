package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.ListeningActivity
import me.avinas.tempo.data.local.dao.ListeningEventDao
import java.util.concurrent.TimeUnit

/**
 * Emits one daily aggregate of how much was listened to.
 *
 * This exists because the alternative — a per-listen event — would be both wasteful and a
 * fingerprinting risk. One event a day carrying two bucketed ranges answers "how much is
 * Tempo being used" without documenting anyone's listening.
 *
 * A day with no tracked listens emits nothing, so a dormant install stays silent rather than
 * reporting a run of zeroes every day.
 */
@HiltWorker
class ListeningActivityWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val listeningEventDao: ListeningEventDao,
    private val tracker: AnalyticsTracker
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ListeningActivityWorker"
        const val WORK_NAME = "listening_activity"
        private const val WINDOW_MILLIS = 24L * 60L * 60L * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ListeningActivityWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE so a missing registration after a process or app restart is repaired.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Daily listening-activity aggregate scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val since = System.currentTimeMillis() - WINDOW_MILLIS

        runCatching {
            val listens = listeningEventDao.getTrackedPlayCountSince(since)
            if (listens > 0) {
                tracker.track(
                    ListeningActivity(
                        listens = listens,
                        distinctApps = listeningEventDao.getDistinctTrackedSourcesSince(since)
                    )
                )
            }
        }.onFailure { error ->
            // A reporting read must never leave a failed periodic run behind.
            Log.w(TAG, "Unable to build the listening aggregate", error)
        }

        return Result.success()
    }
}
