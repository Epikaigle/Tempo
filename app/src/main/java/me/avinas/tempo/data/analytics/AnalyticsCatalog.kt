package me.avinas.tempo.data.analytics

/**
 * The user-facing data dictionary: exactly what Tempo reports, rendered by
 * `YourDataScreen` and mirrored in `docs/ANALYTICS.md`.
 *
 * It lives in code next to [AnalyticsEvent] on purpose. `AnalyticsCatalogTest` asserts this
 * list matches the events that actually exist — same names, same property keys — so the
 * published list cannot drift from reality.
 *
 * These strings are not translated: they are technical identifiers that must match the event
 * names exactly, so a translated copy would be worse than an English one.
 */
data class AnalyticsCatalogEntry(
    val event: String,
    val what: String,
    val properties: List<String>,
)

object AnalyticsCatalog {
    val entries: List<AnalyticsCatalogEntry> =
        listOf(
            AnalyticsCatalogEntry(
                event = "startup_slow",
                what = "That Tempo took an unusually long time to start. Sent only when a start is slow, so a normal launch reports nothing at all.",
                properties = listOf("startup"),
            ),
            AnalyticsCatalogEntry(
                event = "screen_viewed",
                what = "Which detail screens you open (song, artist, album). Only these three are reported.",
                properties = listOf("screen"),
            ),
            AnalyticsCatalogEntry(
                event = "feature_first_used",
                what = "The first time each feature is used, once ever, so unused ones can be improved or removed. Never sent again after that.",
                properties = listOf("feature"),
            ),
            AnalyticsCatalogEntry(
                event = "onboarding_step",
                what = "Where people get stuck during setup.",
                properties = listOf("step", "action", "dur"),
            ),
            AnalyticsCatalogEntry(
                event = "onboarding_completed",
                what = "That setup was finished, and how long it took.",
                properties = listOf("skipped", "total_dur"),
            ),
            AnalyticsCatalogEntry(
                event = "notif_access",
                what = "Whether notification access was granted, so failures can be diagnosed.",
                properties = listOf("result"),
            ),
            AnalyticsCatalogEntry(
                event = "battery_exemption",
                what = "Whether battery optimisation was exempted.",
                properties = listOf("result"),
            ),
            AnalyticsCatalogEntry(
                event = "tracking_health",
                what = "That music detection stopped and Tempo brought it back — once a day at most, with how long it had been down.",
                properties = listOf("severity", "by", "recovery"),
            ),
            AnalyticsCatalogEntry(
                event = "daily_activity",
                what = "That Tempo was used at all today — once a day, and it is what \"daily active users\" is counted from. Says nothing about what you did.",
                properties = listOf("listener_down"),
            ),
            AnalyticsCatalogEntry(
                event = "listening_activity",
                what = "A daily count of listens — a range, once a day. Never what you listened to.",
                properties = listOf("listens", "apps", "ui_opened"),
            ),
            AnalyticsCatalogEntry(
                event = "db_migration",
                what = "That the on-disk database schema advanced, and from which version.",
                properties = listOf("from_v", "to_v"),
            ),
            AnalyticsCatalogEntry(
                event = "import_run",
                what = "Whether a history import worked, and the category of failure if not.",
                properties = listOf("provider", "phase", "records", "dur", "error_class"),
            ),
            AnalyticsCatalogEntry(
                event = "enrichment_run",
                what = "Whether a metadata enrichment sweep worked — once per run, not per source tried: what started it, how much of the backlog it cleared, and the failure category if it failed.",
                properties = listOf("trigger", "phase", "backlog", "cleared", "dur", "error_class"),
            ),
            AnalyticsCatalogEntry(
                event = "backup_run",
                what = "Whether backups succeed.",
                properties = listOf("target", "ok", "size", "dur"),
            ),
            AnalyticsCatalogEntry(
                event = "crash",
                what = "That the app crashed, and where — an obfuscated class and line, never the error message.",
                properties = listOf("crash_class", "top_frame", "app_version"),
            ),
        )
}
