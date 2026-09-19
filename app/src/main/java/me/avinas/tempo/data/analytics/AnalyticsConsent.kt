package me.avinas.tempo.data.analytics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.avinas.tempo.ui.onboarding.dataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The consent model lives in exactly one place so it can be changed without touching
 * any call site.
 */
object AnalyticsDefaults {

    /**
     * Tempo ships with anonymous app-health reporting enabled, and the user turns it off
     * in Settings. Set to `false` to require an explicit opt-in instead — that is the only
     * edit needed, and [AnalyticsGate] plus the UI already handle both cases.
     */
    const val ENABLED = true
}

/**
 * Pure consent logic, kept free of Android types so it is unit-testable.
 */
object AnalyticsGate {

    /**
     * Collection requires the user to be opted in AND to have actually seen the notice on
     * Home. Requiring the notice is what makes a default-on model honest, and it fails
     * safe: if the notice never renders, nothing is ever collected.
     */
    fun isCollectionAllowed(enabled: Boolean?, disclosureSeen: Boolean?): Boolean =
        (enabled ?: AnalyticsDefaults.ENABLED) && (disclosureSeen ?: false)

    /**
     * True when this build could report at all. The Aptabase key lives in the uncommitted
     * `local.properties`, so a build from source has a blank key and stays tracker-free
     * with no extra build flags. Debug builds never report either.
     */
    fun isBuildConfigured(appKey: String, isDebug: Boolean): Boolean =
        appKey.isNotBlank() && !isDebug
}

/**
 * Reads and writes the two analytics consent flags.
 *
 * These live in the app-wide `settings` DataStore alongside `onboarding_completed`, which
 * is why the extension property is imported from the onboarding package — the same way
 * `SettingsViewModel` and several workers already use it.
 */
@Singleton
class AnalyticsConsent @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val enabledKey = booleanPreferencesKey("analytics_enabled")
    private val disclosureSeenKey = booleanPreferencesKey("analytics_disclosure_seen")

    val isEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[enabledKey] ?: AnalyticsDefaults.ENABLED }

    val isDisclosureSeen: Flow<Boolean> =
        context.dataStore.data.map { it[disclosureSeenKey] ?: false }

    suspend fun isCollectionAllowed(): Boolean {
        val prefs = context.dataStore.data.first()
        return AnalyticsGate.isCollectionAllowed(
            enabled = prefs[enabledKey],
            disclosureSeen = prefs[disclosureSeenKey]
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { it[enabledKey] = enabled }
    }

    /** Called the moment the Home disclosure card is rendered. */
    suspend fun markDisclosureSeen() {
        context.dataStore.edit { it[disclosureSeenKey] = true }
    }
}
