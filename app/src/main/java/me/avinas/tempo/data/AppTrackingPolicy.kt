package me.avinas.tempo.data

/**
 * The single source of truth for "should Tempo track this app?".
 *
 * ## Why this exists
 *
 * Tempo originally tracked *everything* and subtracted a blocklist. Any app that posted a
 * media-style notification — Viber voice notes, Signal voice messages, voicemail — was
 * therefore journaled as a song and pinned to the foreground notification until the app was
 * force-stopped. The blocklist could never keep up: every new messaging app was another entry
 * to add, and users only found out after their journal was polluted.
 *
 * The model is now default-deny: an app is tracked only if it is on an allowlist. Rejections
 * are not silent — [TrackingDecision.UNKNOWN] tells the caller to surface the app in the
 * detected-apps inbox so the user can allow it in one tap.
 *
 * The precedence ladder is the numbered order in [decide]. Three of its orderings are
 * deliberate.
 *
 * Rule 1 outranks rule 4 on purpose: a voice note is not a song, so an accidental "allow" on
 * Viber must not resurrect the bug this policy exists to prevent.
 *
 * Rule 3 outranks rule 5 so that switching off a curated app (Spotify, say) is actually
 * honoured instead of being re-allowed by the curated list.
 *
 * Rule 6 sits below the user rules so the old "static blocklist is absolute" behaviour is gone:
 * the Manage Apps screen has always offered an Unblock button for YouTube and friends, and it
 * now does what it says.
 *
 * This object has no Android dependencies so the whole ladder is unit-testable.
 */
object AppTrackingPolicy {

    fun decide(
        packageName: String,
        userEnabledPackages: Set<String>,
        userBlockedPackages: Set<String>,
        userDisabledPackages: Set<String>,
        trackUnrecognizedApps: Boolean = false,
    ): TrackingDecision {
        if (packageName.isBlank()) return TrackingDecision.UNKNOWN

        // 1. Never music — voice notes, calls, recorders. Not overridable.
        if (packageName in DefaultMusicApps.NEVER_TRACK_PACKAGES) {
            return TrackingDecision.NEVER_TRACKED
        }

        // 2. User blocked it outright.
        if (packageName in userBlockedPackages) {
            return TrackingDecision.BLOCKED
        }

        // 3. User switched off a curated app. Beats the curated allowlist in rule 5.
        if (packageName in userDisabledPackages) {
            return TrackingDecision.BLOCKED
        }

        // 4. User explicitly allowed it (added manually, or toggled on).
        if (packageName in userEnabledPackages) {
            return TrackingDecision.ALLOW
        }

        // 5. Curated allowlist — the apps Tempo ships support for.
        if (packageName in DefaultMusicApps.CURATED_TRACKABLE_PACKAGES) {
            return TrackingDecision.ALLOW
        }

        // 6. Built-in default blocklist, only when the user has expressed no preference:
        //    curated video/browser apps plus the long-tail OEM list that is never surfaced in
        //    the UI. Below the user rules so an explicit allow (or Unblock) still wins.
        if (packageName in DefaultMusicApps.BLOCKED_PACKAGES ||
            packageName in DefaultMusicApps.EXTENDED_BLOCKED_PACKAGES
        ) {
            return TrackingDecision.BLOCKED
        }

        // 7. Opt-in escape hatch for niche players.
        if (trackUnrecognizedApps) {
            return TrackingDecision.ALLOW
        }

        // 8. Unrecognized — deny now, offer it in the inbox.
        return TrackingDecision.UNKNOWN
    }
}

/**
 * Outcome of [AppTrackingPolicy.decide].
 *
 * @property shouldTrack whether playback from this package may be journaled.
 * @property recordAsDetected whether to add the package to the detected-apps inbox. True only
 *   for unrecognized apps: blocked apps are already visible in Manage Apps, and offering them
 *   as a suggestion would defeat the point of blocking them.
 * @property label short human-readable reason, used in logs and the Manage Apps screen.
 */
enum class TrackingDecision(
    val shouldTrack: Boolean,
    val recordAsDetected: Boolean,
    val label: String,
) {
    ALLOW(shouldTrack = true, recordAsDetected = false, label = "allowed"),
    BLOCKED(shouldTrack = false, recordAsDetected = false, label = "blocked"),
    NEVER_TRACKED(shouldTrack = false, recordAsDetected = false, label = "never tracked"),
    UNKNOWN(shouldTrack = false, recordAsDetected = true, label = "not recognized"),
}
