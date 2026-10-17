package me.avinas.tempo.data.analytics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.avinas.tempo.ui.onboarding.dataStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pseudonymous install id: one random, app-scoped value used to count daily and monthly
 * active users.
 *
 * This is what makes DAU, WAU, MAU and retention measurable at all. Without a value that
 * survives across days there is no way to tell "one person opened Tempo thirty times" from
 * "thirty people opened Tempo once", and that distinction is the entire point of the metric.
 *
 * What it is **not**, and why that distinction is load-bearing:
 *  - Not an advertising id, and not `ANDROID_ID`. It is random, and it is scoped to this app.
 *  - Not derived from anything about the device, so it cannot be correlated with another app.
 *  - Not an account. Tempo has no accounts, so there is nothing to attach it to.
 *  - Never attached to a listening event. It is sent as the analytics transport's user key,
 *    never as an event property — which is why `AnalyticsSchemaTest`'s ban on keys naming an
 *    identifier still holds unchanged.
 *
 * [reset] mints a fresh value and orphans everything recorded under the old one, so it is
 * both the user-facing "Reset analytics ID" control and part of what opting out does.
 */
@Singleton
class AnalyticsInstallId
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /**
         * Memo of the id resolved in this process.
         *
         * Not state: if it is lost to process death the value is simply re-read from disk, and
         * it is only written where the stored id itself changes — on mint, and on [reset].
         */
        private var cache: String? = null

        /**
         * Reads the current id, minting one on first use.
         *
         * The mint happens inside `edit`, which DataStore serialises, and re-reads inside the
         * lock so two concurrent callers cannot mint two different ids for one install.
         */
        suspend fun current(): String {
            // Cached because the id never changes without `reset`, and `edit` below always
            // rewrites the preferences file whether or not anything changed. Flushing reads
            // this once per batch — up to eight times — and that file is shared with the
            // consent flags, so writing it on a read was churn on a reporting path for no
            // reason. The cache is a memo, not state: it starts empty in every process.
            cache?.let { return it }

            val existing = context.dataStore.data.first()[installIdKey]
            if (existing != null) {
                cache = existing
                return existing
            }

            var minted = ""
            context.dataStore.edit { prefs ->
                // Re-read inside the lock: a concurrent caller may have minted first, and two
                // different ids for one install would split its history in half.
                val raced = prefs[installIdKey]
                if (raced != null) {
                    minted = raced
                } else {
                    minted = randomId()
                    prefs[installIdKey] = minted
                }
            }
            cache = minted
            return minted
        }

        /** Mints a new id, making everything already recorded unlinkable to what follows. */
        suspend fun reset(): String {
            val fresh = randomId()
            context.dataStore.edit { it[installIdKey] = fresh }
            cache = fresh
            return fresh
        }

        /** True when an id has been minted, so opting out can skip a pointless write. */
        suspend fun exists(): Boolean = context.dataStore.data.first()[installIdKey] != null

        private companion object {
            val installIdKey = stringPreferencesKey("analytics_install_id")

            fun randomId(): String = UUID.randomUUID().toString()
        }
    }
