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
import me.avinas.tempo.data.analytics.AnalyticsDaily
import me.avinas.tempo.data.analytics.AnalyticsDailyLedger
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.ListeningActivity
import me.avinas.tempo.data.local.dao.ListeningEventDao
import java.util.concurrent.TimeUnit

/**
 * Emits one aggregate of how much Tempo was used on the previous calendar day.
 *
 * This exists because the alternative — a per-listen event — would be both wasteful and a
 * fingerprinting risk. One event a day carrying two bucketed ranges answers "how much is
 * Tempo being used" without documenting anyone's listening.
 *
 * Three deliberate properties, each of which the first version got wrong:
 *
 *  - **A bounded calendar day, not a rolling 24 hours.** The window used to be `now - 24h`,
 *    so a run at 09:00 described 09:00-to-09:00: it mixed two afternoons, double-counted its
 *    overlap with the previous run, and filed the result under a day it did not describe.
 *    The bounds are now local midnight to local midnight.
 *  - **"Used" means listened *or* opened.** Reporting only days with listens made a user who
 *    browsed their library without playing anything completely invisible.
 *  - **It does not send the presence marker.** Presence has to carry the date the user was
 *    actually active, and this worker only ever runs the following day. Emitting it here
 *    would attribute every background-only user to the wrong day — and, because the worker
 *    also runs on dormant installs, would report inactive devices as active. Presence
 *    belongs to `AnalyticsDailyReporter`, which is called when the activity happens.
 *
 * A day with no listens and no app open emits nothing, so a dormant install stays silent
 * rather than reporting a run of zeroes every day.
 */
@HiltWorker
class ListeningActivityWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val listeningEventDao: ListeningEventDao,
        private val tracker: AnalyticsTracker,
        private val ledger: AnalyticsDailyLedger,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "ListeningActivityWorker"
            const val WORK_NAME = "listening_activity"

            fun schedule(context: Context) {
                val request =
                    PeriodicWorkRequestBuilder<ListeningActivityWorker>(
                        24,
                        TimeUnit.HOURS,
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).addTag(WORK_NAME)
                        .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // UPDATE so a missing registration after a process or app restart is repaired.
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
                Log.i(TAG, "Daily listening-activity aggregate scheduled")
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }

        override suspend fun doWork(): Result {
            val today = AnalyticsDaily.dayOf(System.currentTimeMillis())
            val day = AnalyticsDaily.lastCompleteDay(today)

            runCatching {
                // Guards against two runs in quick succession: WorkManager may deliver a
                // deferred run immediately after a scheduled one, and counting the same day
                // twice would inflate every total built on this figure.
                if (!AnalyticsDaily.shouldReportUsageFor(day, ledger.usageDay())) return@runCatching

                val from = AnalyticsDaily.startOfDayMillis(day)
                val to = AnalyticsDaily.startOfDayMillis(day + 1)

                val listens = listeningEventDao.getTrackedPlayCountBetween(from, to)
                val uiOpened = ledger.uiOpenedOn(day)

                if (!AnalyticsDaily.shouldReportUsage(listens, uiOpened)) return@runCatching

                tracker.track(
                    ListeningActivity(
                        listens = listens,
                        distinctApps = listeningEventDao.getDistinctTrackedSourcesBetween(from, to),
                        uiOpened = uiOpened,
                    ),
                )
                ledger.markUsageReported(day)
            }.onFailure { error ->
                // A reporting read must never leave a failed periodic run behind.
                Log.w(TAG, "Unable to build the daily listening aggregate", error)
            }

            return Result.success()
        }
    }
