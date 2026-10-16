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
         * Reads the current id, minting one on first use.
         *
         * The read and the mint both happen inside `edit`, which is atomic and serialized by
         * DataStore. Reading first and writing only on a miss would race two concurrent
         * callers into minting two different ids for the same install.
         */
        suspend fun current(): String {
            var resolved = ""
            context.dataStore.edit { prefs ->
                val existing = prefs[installIdKey]
                if (existing != null) {
                    resolved = existing
                } else {
                    resolved = randomId()
                    prefs[installIdKey] = resolved
                }
            }
            return resolved
        }

        /** Mints a new id, making everything already recorded unlinkable to what follows. */
        suspend fun reset(): String {
            val fresh = randomId()
            context.dataStore.edit { it[installIdKey] = fresh }
            return fresh
        }

        /** True when an id has been minted, so opting out can skip a pointless write. */
        suspend fun exists(): Boolean = context.dataStore.data.first()[installIdKey] != null

        private companion object {
            val installIdKey = stringPreferencesKey("analytics_install_id")

            fun randomId(): String = UUID.randomUUID().toString()
        }
    }
