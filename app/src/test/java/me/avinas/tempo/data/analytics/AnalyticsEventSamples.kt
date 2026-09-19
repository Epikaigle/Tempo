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
        AppStarted(AppStartType.COLD, 1_500L, listenerReady = true),
        AppStarted(AppStartType.WARM, 200L, listenerReady = false),
        ScreenViewed(AnalyticsScreen.HOME),
        FeatureUsed(TempoFeature.SPOTLIGHT),
        OnboardingStep(OnboardingStepName.PRIVACY, OnboardingAction.SKIP, 4_000L),
        OnboardingCompleted(skippedCount = 3, totalMillis = 42_000L),
        NotifAccessResult(AccessResult.GRANTED, GrantVia.ONBOARDING),
        BatteryExemptionResult(ExemptionResult.SKIPPED),
        TrackingSourceActive(TrackingSource.NOTIFICATION),
        TrackingGap(TrackingGapReason.OEM_KILL, 90_000L),
        ServiceRevived(RevivedBy.HEALTH_WORKER, RecoveryAction.FORCE_RESTART),
        ListeningActivity(listens = 37, distinctApps = 3),
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
        EnrichmentRun(
            provider = EnrichmentProvider.RECCOBEATS,
            attempts = 13,
            successes = 4
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
