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
    fun isCollectionAllowed(
        enabled: Boolean?,
        disclosureSeen: Boolean?,
    ): Boolean = (enabled ?: AnalyticsDefaults.ENABLED) && (disclosureSeen ?: false)

    /**
     * Whether the Home notice should be on screen.
     *
     * Driven by the dismissal flag, deliberately *not* by `disclosureSeen`. The card is what
     * marks the notice as seen, so gating its visibility on that same flag made it remove
     * itself in the frame it appeared — the user never got to read it, and the opt-out beside
     * it was unreachable. Rendering the notice opens the collection gate; acknowledging it is
     * what closes the card.
     */
    fun isNoticeVisible(
        isConfigured: Boolean,
        noticeDismissed: Boolean,
    ): Boolean = isConfigured && !noticeDismissed

    /**
     * True when this build could report at all. The Aptabase key lives in the uncommitted
     * `local.properties`, so a build from source has a blank key and stays tracker-free
     * with no extra build flags. Debug builds never report either, unless the
     * `APTABASE_DEBUG_PREVIEW` override is on — that is only for locally previewing the
     * Home notice and Settings toggle, and must stay `false` in committed code.
     */
    fun isBuildConfigured(
        appKey: String,
        isDebug: Boolean,
        debugPreview: Boolean = false,
    ): Boolean = appKey.isNotBlank() && (!isDebug || debugPreview)
}

/**
 * Reads and writes the three analytics consent flags.
 *
 * These live in the app-wide `settings` DataStore alongside `onboarding_completed`, which
 * is why the extension property is imported from the onboarding package — the same way
 * `SettingsViewModel` and several workers already use it.
 */
@Singleton
class AnalyticsConsent
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val enabledKey = booleanPreferencesKey("analytics_enabled")
        private val disclosureSeenKey = booleanPreferencesKey("analytics_disclosure_seen")
        private val noticeDismissedKey = booleanPreferencesKey("analytics_notice_dismissed")

        val isEnabled: Flow<Boolean> =
            context.dataStore.data.map { it[enabledKey] ?: AnalyticsDefaults.ENABLED }

        val isDisclosureSeen: Flow<Boolean> =
            context.dataStore.data.map { it[disclosureSeenKey] ?: false }

        /**
         * True once the user has acknowledged the Home notice.
         *
         * Deliberately separate from [isDisclosureSeen]. Rendering the notice is what opens
         * the collection gate, but the card itself must stay on screen until it is actually
         * acknowledged — driving visibility off `disclosureSeen` made the card remove itself
         * in the same frame it appeared, so nobody could read it or reach the opt-out beside
         * it. Two flags let the notice be both genuinely shown and non-blocking.
         */
        val isNoticeDismissed: Flow<Boolean> =
            context.dataStore.data.map { it[noticeDismissedKey] ?: false }

        /**
         * The single observable that actually decides whether reporting may happen: opted in AND
         * the Home notice has been rendered. Callers that own the uploader's lifecycle should
         * collect this rather than [isEnabled], so the worker is not registered for a build that
         * still cannot send — and so it is registered the moment the notice makes collection
         * legal, which is what lets the first event leave the device immediately instead of
         * waiting for the next periodic run.
         */
        val collectionAllowed: Flow<Boolean> =
            context.dataStore.data.map {
                AnalyticsGate.isCollectionAllowed(
                    enabled = it[enabledKey],
                    disclosureSeen = it[disclosureSeenKey],
                )
            }

        suspend fun isCollectionAllowed(): Boolean {
            val prefs = context.dataStore.data.first()
            return AnalyticsGate.isCollectionAllowed(
                enabled = prefs[enabledKey],
                disclosureSeen = prefs[disclosureSeenKey],
            )
        }

        suspend fun setEnabled(enabled: Boolean) {
            context.dataStore.edit { it[enabledKey] = enabled }
        }

        /** Called the moment the Home disclosure card is rendered. */
        suspend fun markDisclosureSeen() {
            context.dataStore.edit { it[disclosureSeenKey] = true }
        }

        /** Called when the user acknowledges or turns off reporting on the Home card. */
        suspend fun dismissNotice() {
            context.dataStore.edit { it[noticeDismissedKey] = true }
        }
    }
