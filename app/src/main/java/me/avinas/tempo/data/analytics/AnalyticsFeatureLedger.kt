package me.avinas.tempo.data.analytics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.avinas.tempo.ui.onboarding.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which features this install has already reported using.
 *
 * This is what turns a per-use count into an adoption signal. Without it, "which features get
 * used" costs one event every time a feature is tapped, which for something like Spotlight or a
 * share card is unbounded — and it answers the same question the first use already answered.
 *
 * The dedupe lives here rather than at the twenty-odd call sites because only the reporting
 * layer knows whether this is the first time, and because a caller forgetting to check would
 * silently reintroduce the volume it was added to remove.
 *
 * Only the fact that a feature was used is stored — a set of closed enum names, never anything
 * about what was done with it. Cleared when the analytics id is reset, since the new id has no
 * history to inherit.
 */
@Singleton
class AnalyticsFeatureLedger
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Claims the first use of [feature], returning true exactly once per install.
         *
         * Read and write both happen inside `edit`, which DataStore serialises, so two
         * concurrent call sites cannot both be told they were first.
         */
        suspend fun claimFirstUse(feature: String): Boolean {
            var first = false
            context.dataStore.edit { prefs ->
                val seen = prefs[reportedKey] ?: emptySet()
                if (feature !in seen) {
                    prefs[reportedKey] = seen + feature
                    first = true
                }
            }
            return first
        }

        /** True when nothing has been reported yet, so a pointless write can be skipped. */
        suspend fun isEmpty(): Boolean = context.dataStore.data.first()[reportedKey].isNullOrEmpty()

        suspend fun clear() {
            context.dataStore.edit { it.remove(reportedKey) }
        }

        private companion object {
            val reportedKey = stringSetPreferencesKey("analytics_features_reported")
        }
    }
