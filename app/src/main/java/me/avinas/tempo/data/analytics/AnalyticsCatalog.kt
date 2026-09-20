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
                event = "app_started",
                what = "That the app opened, how long Tempo's own start-up work took, and whether music detection was running.",
                properties = listOf("start_type", "startup", "listener_ready"),
            ),
            AnalyticsCatalogEntry(
                event = "screen_viewed",
                what = "Which screens you open. Powers knowing which parts of the app actually get used.",
                properties = listOf("screen"),
            ),
            AnalyticsCatalogEntry(
                event = "feature_used",
                what = "Which features you use, so the unused ones can be improved or removed.",
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
                properties = listOf("result", "via"),
            ),
            AnalyticsCatalogEntry(
                event = "battery_exemption",
                what = "Whether battery optimisation was exempted.",
                properties = listOf("result"),
            ),
            AnalyticsCatalogEntry(
                event = "tracking_source",
                what = "Which detection method is active.",
                properties = listOf("source"),
            ),
            AnalyticsCatalogEntry(
                event = "tracking_gap",
                what = "When and why music detection stopped, so listening gaps can be fixed.",
                properties = listOf("reason", "gap"),
            ),
            AnalyticsCatalogEntry(
                event = "service_revived",
                what = "That Tempo recovered after the system stopped it, and how forcefully.",
                properties = listOf("by", "recovery"),
            ),
            AnalyticsCatalogEntry(
                event = "listening_activity",
                what = "A daily count of listens — a range, once a day. Never what you listened to.",
                properties = listOf("listens", "apps"),
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
                what = "Whether album-art and metadata lookups are failing.",
                properties = listOf("provider", "ok", "failed"),
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
