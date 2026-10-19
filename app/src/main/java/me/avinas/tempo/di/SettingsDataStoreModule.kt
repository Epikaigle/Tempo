package me.avinas.tempo.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.InstallIn
import me.avinas.tempo.ui.onboarding.dataStore
import javax.inject.Singleton

/**
 * The app-wide `settings` DataStore, exposed as a binding rather than reached through the
 * `Context` extension at each use.
 *
 * The analytics state classes ([me.avinas.tempo.data.analytics.AnalyticsConsent] and friends)
 * take this instead of a `Context`, which is what lets a plain JVM test drive them against a
 * real file-backed store — the only way to test that a flow over DataStore and a writer to it
 * do not feed each other.
 *
 * It is the same instance the `dataStore` extension property returns, so code that still reads
 * it directly (`SettingsViewModel`, for one) observes the same preferences.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsDataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.dataStore
}
