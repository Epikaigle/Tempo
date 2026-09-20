package me.avinas.tempo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.BuildConfig
import me.avinas.tempo.data.analytics.AnalyticsGate
import me.avinas.tempo.data.analytics.AnalyticsTracker
import me.avinas.tempo.data.analytics.AptabaseClient
import me.avinas.tempo.data.analytics.NoOpAnalyticsTracker
import javax.inject.Singleton

/**
 * The single swap point for analytics. Call sites inject [AnalyticsTracker] and never care
 * which implementation is bound.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {

    /**
     * A build with no Aptabase key — which is every build compiled from source, since the key
     * lives in the uncommitted `local.properties` — gets the no-op and never constructs a
     * networking client at all. Debug builds are treated the same way.
     *
     * [AptabaseClient] re-checks the same gate on every send, so even a mis-wired graph cannot
     * transmit from a build that should stay silent.
     */
    @Provides
    @Singleton
    fun provideAnalyticsTracker(client: AptabaseClient): AnalyticsTracker =
        if (AnalyticsGate.isBuildConfigured(appKey = BuildConfig.APTABASE_APP_KEY, isDebug = BuildConfig.DEBUG, debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW)) {
            client
        } else {
            NoOpAnalyticsTracker()
        }
}
