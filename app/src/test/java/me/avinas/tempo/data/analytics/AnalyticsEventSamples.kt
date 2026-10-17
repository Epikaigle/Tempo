package me.avinas.tempo.data.analytics

/**
 * One representative instance of every event variant.
 *
 * Kept as a shared fixture so `AnalyticsSchemaTest` and `AnalyticsCatalogTest` cannot
 * disagree: adding a new event without adding it here leaves it unchecked, and the two
 * tests will fail together rather than silently drifting.
 */
object AnalyticsEventSamples {

    val all: List<AnalyticsEvent> = listOf(
        StartupSlow(2_500L),
        ScreenViewed(AnalyticsScreen.SONG_DETAILS),
        FeatureFirstUsed(TempoFeature.SPOTLIGHT),
        FeatureFirstUsed(TempoFeature.WIDGET),
        OnboardingStep(OnboardingStepName.PRIVACY, OnboardingAction.SKIP, 4_000L),
        OnboardingCompleted(skippedCount = 3, totalMillis = 42_000L),
        NotifAccessResult(AccessResult.GRANTED, GrantVia.ONBOARDING),
        BatteryExemptionResult(ExemptionResult.SKIPPED),
        TrackingHealth(TrackingGapSeverity.OVER_24H, RevivedBy.HEALTH_WORKER, RecoveryAction.FORCE_RESTART),
        TrackingHealth(TrackingGapSeverity.UNKNOWN, RevivedBy.BOOT, RecoveryAction.COMPONENT_REENABLE),
        DailyActivity(listenerDown = true),
        DailyActivity(listenerDown = false),
        ListeningActivity(listens = 37, distinctApps = 3, uiOpened = true),
        ListeningActivity(listens = 0, distinctApps = 0, uiOpened = true),
        DbMigration(fromVersion = 51, toVersion = 52),
        ImportRun(
            provider = ImportProvider.YOUTUBE_MUSIC,
            phase = ImportPhase.FAILED,
            records = 120,
            failure = FailureClass.PARSE,
            durationMillis = 9_000L
        ),
        ImportRun(
            provider = ImportProvider.LASTFM,
            phase = ImportPhase.COMPLETED,
            records = 900,
            failure = null,
            durationMillis = 60_000L
        ),
        BackupRun(
            target = BackupTarget.DRIVE,
            success = true,
            sizeBytes = 5L * 1024 * 1024,
            durationMillis = 12_000L
        ),
        Crash("a.b.c", "d(SourceFile:412)", "4.8.8")
    )
}
