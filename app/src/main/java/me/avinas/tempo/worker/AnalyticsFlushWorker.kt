package me.avinas.tempo.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.avinas.tempo.data.analytics.AnalyticsTracker
import java.util.concurrent.TimeUnit

/**
 * Uploads buffered anonymous app-health events.
 *
 * The `UNMETERED` constraint is the mechanism behind the promise that analytics never spends
 * your mobile data — WorkManager simply will not run this on a metered connection, so there
 * is no code path that can accidentally do so.
 *
 * Events older than 24h are rejected by the ingest API and dropped by the queue, so this
 * running rarely costs nothing but latency.
 */
@HiltWorker
class AnalyticsFlushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val tracker: AnalyticsTracker
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AnalyticsFlushWorker"
        private const val WORK_NAME = "analytics_flush"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(
                6, TimeUnit.HOURS,
                1, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE so a changed constraint or cadence repairs existing registrations.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Log.i(TAG, "Analytics flush scheduled (unmetered only)")
        }

        /** Called when the user opts out, so no scheduled work remains. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Analytics flush cancelled")
        }
    }

    override suspend fun doWork(): Result {
        tracker.flush()
        return Result.success()
    }
}
