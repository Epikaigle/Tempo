package me.avinas.tempo.data.analytics

/**
 * The complete analytics vocabulary — everything Tempo is ever allowed to report.
 *
 * Design rules, enforced by `AnalyticsSchemaTest` rather than by review discipline:
 *  - Properties are enum names or bucketed values. There are no free-form string
 *    properties, so a caller physically cannot send a track title, artist name, search
 *    query, notification text or file path.
 *  - Numbers are bucketed. This avoids fingerprinting and keeps dashboards stable when
 *    a user's library grows. Bucketing is why small enum-like counts must be enums
 *    instead — a value of 1, 2 or 3 all collapse into `1-5`, carrying no information.
 *  - Keys stay under Aptabase's 40-character property-key limit.
 *  - Aptabase accepts only String and Int property values, at most 25 events per request,
 *    and rejects events older than 24 hours.
 *
 * Never add an identifier here. `sessionId` is owned by the client, is random per session,
 * and is deliberately never persisted — see `AnalyticsTracker`.
 */

/** Bucketing helpers. Raw counts and durations are never transmitted. */
object AnalyticsBucket {
    fun count(value: Int): String =
        when {
            value <= 0 -> "0"
            value <= 5 -> "1-5"
            value <= 25 -> "6-25"
            value <= 100 -> "26-100"
            else -> "100+"
        }

    fun duration(millis: Long): String =
        when {
            millis < 1_000L -> "<1s"
            millis < 5_000L -> "1-5s"
            millis < 30_000L -> "5-30s"
            else -> "30s+"
        }

    fun bytes(value: Long): String =
        when {
            value <= 0L -> "0"
            value < 1L shl 20 -> "<1MB"
            value < 10L shl 20 -> "1-10MB"
            value < 100L shl 20 -> "10-100MB"
            else -> "100MB+"
        }

    /**
     * How long music detection was dead, bucketed for the range gaps actually occur in.
     *
     * A dead listener cannot be noticed in under the health worker's 15-minute retry, so
     * gaps run from tens of minutes to days. The generic [duration] buckets top out at
     * `30s+`, which meant every gap ever reported landed in the same bucket — a thirty-second
     * blip and a three-day silence were indistinguishable, which is precisely the distinction
     * the metric exists to draw.
     */
    fun gap(gapMillis: Long?): TrackingGapSeverity =
        when {
            gapMillis == null -> TrackingGapSeverity.UNKNOWN
            gapMillis < 30L * 60L * 1000L -> TrackingGapSeverity.UNDER_30M
            gapMillis < 2L * 60L * 60L * 1000L -> TrackingGapSeverity.UNDER_2H
            gapMillis < 6L * 60L * 60L * 1000L -> TrackingGapSeverity.UNDER_6H
            gapMillis < 24L * 60L * 60L * 1000L -> TrackingGapSeverity.UNDER_24H
            else -> TrackingGapSeverity.OVER_24H
        }
}

enum class AnalyticsScreen {
    HOME,
    STATS,
    HISTORY,
    SETTINGS,
    SPOTLIGHT,
    SONG_DETAILS,
    ARTIST_DETAILS,
    ALBUM_DETAILS,
    BACKUP_RESTORE,
    SUPPORTED_APPS,
    BACKGROUND_PROTECTION,
    LASTFM_IMPORT,
    SPOTIFY_JSON_IMPORT,
    DEEZER_IMPORT,
    YOUTUBE_MUSIC_IMPORT,
    DESKTOP_LINK,
    ENRICHMENT_REPORT,
    SHARE_CANVAS,
    PROFILE,
    YOUR_DATA,
    UNKNOWN,
}

/** Discrete, user-invocable capabilities. Powers the "which features are unused" question. */
enum class TempoFeature {
    SPOTLIGHT,
    SHARE_CARD,
    SHARE_CANVAS,
    DESKTOP_LINK,
    WIDGET,
    GAMIFICATION_PROFILE,
    MOOD_ANALYSIS,
    ARTIST_SPLIT,
    ARTIST_MERGE,
    TRACK_MERGE,
    ALIAS_RENAME,
    ARTIST_RENAME,
    MANUAL_CONTENT_MARK,
    LASTFM_IMPORT,
    SPOTIFY_JSON_IMPORT,
    DEEZER_IMPORT,
    YTMUSIC_IMPORT,

    /** Importing plays from a file exported by the Tempo Stats browser extension. */
    EXTENSION_PLAYS_IMPORT,
    BACKUP_RESTORE,
    LOCAL_BACKUP,
    DRIVE_BACKUP,
    ENRICHMENT_REPORT,
    HISTORY_FILTER,
    SUPPORTED_APPS,
}

enum class OnboardingStepName { WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, RESTORE }

enum class OnboardingAction { NEXT, SKIP, BACK }

enum class GrantVia { ONBOARDING, SETTINGS }

enum class AccessResult { GRANTED, DENIED, REVOKED, DEFERRED }

enum class ExemptionResult { GRANTED, DENIED, SKIPPED }

/**
 * How long music detection was dead.
 *
 * [UNKNOWN] exists because one recovery path — finding the listener component outright
 * disabled — has no way to measure the gap, and reporting that as the mildest bucket would
 * understate a case that is usually severe. An honest "we do not know" beats a wrong number.
 *
 * The previous encoding reused the generic `<1s / 1-5s / 5-30s / 30s+` duration buckets, and
 * since a gap can never be detected in under the health worker's 15-minute retry, every value
 * it ever produced was `30s+` — the one distinction the metric existed to draw, erased by the
 * bucket it was squeezed into.
 */
enum class TrackingGapSeverity { UNKNOWN, UNDER_30M, UNDER_2H, UNDER_6H, UNDER_24H, OVER_24H }

enum class RevivedBy { HEALTH_WORKER, BOOT }

/**
 * How strongly recovery had to intervene. An enum rather than a count because a count would
 * be bucketed to `1-5` and every value would collapse together.
 */
enum class RecoveryAction { REBIND, FORCE_RESTART, COMPONENT_REENABLE }

enum class ImportProvider { LASTFM, SPOTIFY_JSON, DEEZER, YOUTUBE_MUSIC, TEMPO_BACKUP, BROWSER_EXTENSION }

enum class ImportPhase { STARTED, COMPLETED, FAILED }

/** Closed failure taxonomy. Never a message or a stack trace. */
enum class FailureClass { IO, PARSE, NETWORK, DATABASE, PERMISSION, OUT_OF_MEMORY, CANCELLED, UNKNOWN }

enum class BackupTarget { LOCAL, DRIVE }

enum class AppStartType { COLD, WARM }

sealed interface AnalyticsEvent {
    /** Event name as it appears in the dashboard. */
    val name: String

    /** String enum names and bucketed values only. */
    val props: Map<String, Any>
}

data class AppStarted(
    val startType: AppStartType,
    val startupMillis: Long,
    val listenerReady: Boolean,
) : AnalyticsEvent {
    override val name = "app_started"
    override val props: Map<String, Any> =
        mapOf(
            "start_type" to startType.name,
            "startup" to AnalyticsBucket.duration(startupMillis),
            "listener_ready" to if (listenerReady) 1 else 0,
        )
}

data class ScreenViewed(
    val screen: AnalyticsScreen,
) : AnalyticsEvent {
    override val name = "screen_viewed"
    override val props: Map<String, Any> = mapOf("screen" to screen.name)
}

data class FeatureUsed(
    val feature: TempoFeature,
) : AnalyticsEvent {
    override val name = "feature_used"
    override val props: Map<String, Any> = mapOf("feature" to feature.name)
}

data class OnboardingStep(
    val step: OnboardingStepName,
    val action: OnboardingAction,
    val stepMillis: Long,
) : AnalyticsEvent {
    override val name = "onboarding_step"
    override val props: Map<String, Any> =
        mapOf(
            "step" to step.name,
            "action" to action.name,
            "dur" to AnalyticsBucket.duration(stepMillis),
        )
}

data class OnboardingCompleted(
    val skippedCount: Int,
    val totalMillis: Long,
) : AnalyticsEvent {
    override val name = "onboarding_completed"
    override val props: Map<String, Any> =
        mapOf(
            "skipped" to AnalyticsBucket.count(skippedCount),
            "total_dur" to AnalyticsBucket.duration(totalMillis),
        )
}

data class NotifAccessResult(
    val result: AccessResult,
    val via: GrantVia,
) : AnalyticsEvent {
    override val name = "notif_access"
    override val props: Map<String, Any> =
        mapOf(
            "result" to result.name,
            "via" to via.name,
        )
}

data class BatteryExemptionResult(
    val result: ExemptionResult,
) : AnalyticsEvent {
    override val name = "battery_exemption"
    override val props: Map<String, Any> = mapOf("result" to result.name)
}

/**
 * Music detection died and had to be revived — **at most once per device per day**.
 *
 * The old pair (`tracking_gap` and `service_revived`) fired together for every single
 * incident, so one broken device produced 192 events a day and half of them duplicated the
 * other half. Because the health worker retries every 15 minutes and a device whose listener
 * is dead stays dead, a few dozen devices could consume an entire monthly quota while the
 * dashboard claimed the whole fleet was failing.
 *
 * Repetition was not merely expensive, it was misleading: ninety-six identical rows read as
 * ninety-six unlucky users. One row per device per day makes "how many devices had a problem"
 * the number you actually read.
 *
 * There is deliberately no per-day incident count. Now that events carry a stable identity,
 * "is this one device flapping every day" is a question for the query layer — count the days
 * on which a device reported this event — rather than a field that would need a second
 * accumulator and a two-day slot to survive the day boundary. The payload stays smaller and
 * the analysis moves to where it belongs.
 */
data class TrackingHealth(
    val severity: TrackingGapSeverity,
    val by: RevivedBy,
    val recovery: RecoveryAction,
) : AnalyticsEvent {
    override val name = "tracking_health"
    override val props: Map<String, Any> =
        mapOf(
            "severity" to severity.name,
            "by" to by.name,
            "recovery" to recovery.name,
        )
}

/**
 * Daily aggregate of listening activity. There is deliberately no per-listen event —
 * that would be both wasteful and a fingerprinting risk.
 *
 * Sent once for the **previous calendar day**, so [uiOpened] describes a whole day rather
 * than whatever was true at the moment the app happened to be opened. This event answers
 * "how much was Tempo used"; [DailyActivity] answers "was it used at all".
 */
data class ListeningActivity(
    val listens: Int,
    val distinctApps: Int,
    val uiOpened: Boolean,
) : AnalyticsEvent {
    override val name = "listening_activity"
    override val props: Map<String, Any> =
        mapOf(
            "listens" to AnalyticsBucket.count(listens),
            "apps" to AnalyticsBucket.count(distinctApps),
            "ui_opened" to if (uiOpened) 1 else 0,
        )
}

/**
 * The daily active-use marker — the event every DAU/WAU/MAU figure is counted from.
 *
 * Deliberately tiny, and deliberately separate from [ListeningActivity]. Two reasons:
 *
 *  - **It must never fail for someone else's reason.** [ListeningActivity] needs a database
 *    read before it can be built, so it is emitted inside a `runCatching`. If that read ever
 *    throws, the day's usage figure is lost — and if presence rode on the same event, so was
 *    the day's active-user count. Presence is attempted unconditionally instead.
 *  - **It has to carry the right date.** Presence is emitted at the day's *first* activity,
 *    so the event's timestamp falls on the day the user was actually active. An end-of-day
 *    aggregate sent the next morning would attribute every user to the wrong day and skew
 *    every retention cohort by one.
 *
 * At most one is sent per calendar day; see `AnalyticsDailyLedger`.
 */
data class DailyActivity(
    val listenerDown: Boolean,
) : AnalyticsEvent {
    override val name = "daily_activity"
    override val props: Map<String, Any> =
        mapOf("listener_down" to if (listenerDown) 1 else 0)
}

data class DbMigration(
    val fromVersion: Int,
    val toVersion: Int,
) : AnalyticsEvent {
    override val name = "db_migration"
    override val props: Map<String, Any> =
        mapOf(
            "from_v" to fromVersion,
            "to_v" to toVersion,
        )
}

data class ImportRun(
    val provider: ImportProvider,
    val phase: ImportPhase,
    val records: Int,
    val failure: FailureClass?,
    val durationMillis: Long,
) : AnalyticsEvent {
    override val name = "import_run"
    override val props: Map<String, Any> =
        buildMap {
            put("provider", provider.name)
            put("phase", phase.name)
            put("records", AnalyticsBucket.count(records))
            put("dur", AnalyticsBucket.duration(durationMillis))
            failure?.let { put("error_class", it.name) }
        }
}

data class BackupRun(
    val target: BackupTarget,
    val success: Boolean,
    val sizeBytes: Long,
    val durationMillis: Long,
) : AnalyticsEvent {
    override val name = "backup_run"
    override val props: Map<String, Any> =
        mapOf(
            "target" to target.name,
            "ok" to if (success) 1 else 0,
            "size" to AnalyticsBucket.bytes(sizeBytes),
            "dur" to AnalyticsBucket.duration(durationMillis),
        )
}

/**
 * A crash reduced to a signature with no readable content.
 *
 * Exception messages are deliberately omitted: they routinely embed parsed notification
 * text, which is exactly the listening data we promise never to collect. Only the
 * obfuscated class name and a single obfuscated frame are sent, which are retraceable
 * locally with the `mapping.txt` archived for that version.
 *
 * The `require` guards make it structurally impossible to smuggle arbitrary text
 * (e.g. a song title) through this event.
 */
data class Crash(
    val exceptionClass: String,
    val topFrame: String,
    val appVersion: String,
) : AnalyticsEvent {
    init {
        require(SAFE_CLASS.matches(exceptionClass)) {
            "exceptionClass must be a plain obfuscated class name"
        }
        require(SAFE_FRAME.matches(topFrame)) {
            "topFrame must be '<method>(<source>:<line>)' with no free text"
        }
        require(exceptionClass.length <= MAX_CLASS_CHARS) { "exceptionClass too long" }
    }

    override val name = "crash"
    override val props: Map<String, Any> =
        mapOf(
            "crash_class" to exceptionClass,
            "top_frame" to topFrame,
            "app_version" to appVersion,
        )

    companion object {
        const val MAX_CLASS_CHARS = 64
        private val SAFE_CLASS = Regex("^[A-Za-z0-9_.$]{1,$MAX_CLASS_CHARS}$")
        private val SAFE_FRAME = Regex("^[A-Za-z0-9_.$<>]{1,64}\\([A-Za-z0-9_.$]{0,32}:\\d{1,7}\\)$")
    }
}
