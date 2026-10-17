package me.avinas.tempo.data.analytics

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.avinas.tempo.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports that music detection died and was revived, **at most once per device per day**.
 *
 * The health worker retries every fifteen minutes, and a device whose listener has been killed
 * stays killed — so a single broken phone used to emit a `tracking_gap` and a `service_revived`
 * every quarter hour, forever. That is 192 events a day from one install, and because it was
 * indistinguishable from 192 users having a bad day, it filled the dashboard with a fleet-wide
 * reliability story that was really about a handful of devices.
 *
 * One report per device per day fixes the volume and, more importantly, fixes the reading: the
 * number now counts *devices with a problem*, which is the number worth acting on. Persistence
 * — "is this one phone flapping every day" — is answered from the identity on the events rather
 * than from a count in the payload.
 *
 * The first incident of the day is the one reported. It carries the gap length that was
 * actually observed, and a device in a loop will report again tomorrow, so nothing is hidden
 * by the throttle.
 */
@Singleton
class AnalyticsHealthReporter
    @Inject
    constructor(
        private val tracker: AnalyticsTracker,
        private val ledger: AnalyticsDailyLedger,
    ) {
        private val reportedDay = AtomicLong(NEVER)

        /** Serialises the slow path so two worker runs cannot both decide to send. */
        private val lock = Mutex()

        /**
         * Records one observed recovery, reporting it if today's has not gone out yet.
         *
         * @param gapMillis how long detection had been dead, or null when that cannot be
         *   measured — which is honest rather than optimistic.
         */
        suspend fun reportIfDue(
            gapMillis: Long?,
            by: RevivedBy,
            recovery: RecoveryAction,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            if (!isConfigured()) return

            val today = AnalyticsDaily.dayOf(nowMillis)
            if (reportedDay.get() == today) return

            // NonCancellable for the same reason as the presence marker: `track` dispatches
            // onto a scope that outlives this caller, so a cancellation between sending and
            // marking could report the same recovery twice on the next run.
            withContext(NonCancellable) {
                runCatching {
                    lock.withLock {
                        // Re-checked inside the lock: another run may have just finished.
                        if (reportedDay.get() == today) return@withLock

                        if (!AnalyticsDaily.isUnreported(ledger.healthDay(), today)) {
                            // Already sent earlier today, by this process or a previous one.
                            reportedDay.set(today)
                            return@withLock
                        }

                        tracker.track(
                            TrackingHealth(
                                severity = AnalyticsBucket.gap(gapMillis),
                                by = by,
                                recovery = recovery,
                            ),
                        )
                        ledger.markHealthReported(today)
                        reportedDay.set(today)
                    }
                }
            }
        }

        /** Source builds and debug builds report nothing, so they should not track state. */
        private fun isConfigured(): Boolean =
            AnalyticsGate.isBuildConfigured(
                endpoint = BuildConfig.ANALYTICS_ENDPOINT,
                isDebug = BuildConfig.DEBUG,
                debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
            )

        private companion object {
            const val NEVER = Long.MIN_VALUE
        }
    }
