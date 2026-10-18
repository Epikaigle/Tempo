package me.avinas.tempo.data.analytics

/**
 * One representative instance of every event variant.
 *
 * Kept as a shared fixture so `AnalyticsSchemaTest` and `AnalyticsCatalogTest` cannot disagree.
 * It is also the *only* input those checks see, so a variant missing here would be inspected by
 * nothing — which is why `AnalyticsSchemaTest` asserts this list against the sealed hierarchy
 * rather than trusting anyone to remember. Add the sample first; the tests will tell you if it
 * is missing.
 */
object AnalyticsEventSamples {

    val all: List<AnalyticsEvent> = listOf(
        StartupSlow(2_500L),
        FeatureFirstUsed(TempoFeature.SPOTLIGHT),
        FeatureFirstUsed(TempoFeature.WIDGET),
        OnboardingStep(OnboardingStepName.PRIVACY, OnboardingAction.SKIP, 4_000L),
        OnboardingCompleted(skippedCount = 3, totalMillis = 42_000L),
        NotifAccessResult(AccessResult.GRANTED),
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
            phase = RunPhase.FAILED,
            records = 120,
            failure = FailureClass.PARSE,
            durationMillis = 9_000L
        ),
        ImportRun(
            provider = ImportProvider.LASTFM,
            phase = RunPhase.COMPLETED,
            records = 900,
            failure = null,
            durationMillis = 60_000L
        ),
        EnrichmentRun(
            trigger = EnrichmentTrigger.ENRICH_ALL,
            phase = RunPhase.COMPLETED,
            backlog = 120,
            cleared = 40,
            durationMillis = 30_000L,
            failure = null,
        ),
        EnrichmentRun(
            trigger = EnrichmentTrigger.BATCH,
            phase = RunPhase.FAILED,
            backlog = 900,
            cleared = 0,
            durationMillis = 2_000L,
            failure = FailureClass.NETWORK,
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
