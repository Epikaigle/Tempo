package me.avinas.tempo.data.analytics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.avinas.tempo.ui.onboarding.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which calendar days have already been reported, so the once-a-day events stay
 * once a day across process deaths.
 *
 * This has to be on disk rather than in memory: the reporter is triggered from the app
 * process, the tracking service and a periodic worker, any of which can be killed and
 * restarted between two activities on the same day. An in-memory guard would let each
 * restart report again, and duplicate presence markers inflate every active-user count.
 *
 * Only day numbers are stored — no timestamps of anything the user did, and nothing that
 * identifies them. Cleared when reporting is turned off, for the same reason the event queue
 * is: nothing should outlive the user's decision to stop.
 */
@Singleton
class AnalyticsDailyLedger
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** The last day a presence marker was sent for, or null if none ever was. */
        suspend fun presenceDay(): Long? = context.dataStore.data.first()[presenceDayKey]

        /** The last day a usage aggregate was sent for, or null if none ever was. */
        suspend fun usageDay(): Long? = context.dataStore.data.first()[usageDayKey]

        /** The last day a tracking-health report was sent for, or null if none ever was. */
        suspend fun healthDay(): Long? = context.dataStore.data.first()[healthDayKey]

        /**
         * The last day the app's own UI was opened, or null if it never was.
         *
         * Only for diagnostics and tests — reporting reads [uiOpenedOn], because a single
         * "latest day" value is not enough to answer the question being asked.
         */
        suspend fun uiOpenedDay(): Long? = context.dataStore.data.first()[uiOpenedDayKey]

        /**
         * Whether the UI was opened on [day].
         *
         * Two days are retained, not one, because of the order the worker actually runs in:
         * day D is reported during day D+1, and the user will very often open the app on
         * D+1 *before* the worker gets its turn. Storing only the latest day would have that
         * morning's open overwrite D's, and every day would report `ui_opened = 0` for the
         * users who open the app most — the exact inverse of the truth.
         */
        suspend fun uiOpenedOn(day: Long): Boolean {
            val prefs = context.dataStore.data.first()
            return prefs[uiOpenedDayKey] == day || prefs[uiOpenedPrevDayKey] == day
        }

        suspend fun markPresenceReported(day: Long) {
            context.dataStore.edit { it[presenceDayKey] = day }
        }

        suspend fun markUsageReported(day: Long) {
            context.dataStore.edit { it[usageDayKey] = day }
        }

        suspend fun markHealthReported(day: Long) {
            context.dataStore.edit { it[healthDayKey] = day }
        }

        /**
         * Records that the UI was opened on [day], keeping the previous day alongside it.
         *
         * Writes never move backwards. A late or out-of-order call can only fill the older
         * slot if that slot is empty or older still, so a stale write cannot evict the day
         * the worker is about to report.
         */
        suspend fun markUiOpened(day: Long) {
            context.dataStore.edit { prefs ->
                val latest = prefs[uiOpenedDayKey]
                when {
                    latest == null -> prefs[uiOpenedDayKey] = day
                    day == latest -> Unit
                    day > latest -> {
                        prefs[uiOpenedPrevDayKey] = latest
                        prefs[uiOpenedDayKey] = day
                    }
                    else -> {
                        val previous = prefs[uiOpenedPrevDayKey]
                        if (previous == null || day > previous) prefs[uiOpenedPrevDayKey] = day
                    }
                }
            }
        }

        suspend fun clear() {
            context.dataStore.edit {
                it.remove(presenceDayKey)
                it.remove(usageDayKey)
                it.remove(uiOpenedDayKey)
                it.remove(uiOpenedPrevDayKey)
                it.remove(healthDayKey)
            }
        }

        private companion object {
            val presenceDayKey = longPreferencesKey("analytics_presence_day")
            val usageDayKey = longPreferencesKey("analytics_usage_day")
            val uiOpenedDayKey = longPreferencesKey("analytics_ui_opened_day")
            val uiOpenedPrevDayKey = longPreferencesKey("analytics_ui_opened_prev_day")
            val healthDayKey = longPreferencesKey("analytics_health_day")
        }
    }
