package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.interceptors.RetryInterceptor
import me.avinas.tempo.data.remote.reccobeats.ReccoBeatsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides ReccoBeats API client for audio features when Spotify audio features
 * endpoint is unavailable.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReccoBeatsModule {

    @Provides
    @Singleton
    @Named("reccobeats")
    fun provideReccoBeatsOkHttpClient(
        retryInterceptor: RetryInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // Longer timeout for audio file uploads
            .build()
    }

    /**
     * Retrofit client for ReccoBeats API.
     */
    @Provides
    @Singleton
    @Named("reccobeats")
    fun provideReccoBeatsRetrofit(
        @Named("reccobeats") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ReccoBeatsApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    /**
     * ReccoBeats API interface.
     */
    @Provides
    @Singleton
    fun provideReccoBeatsApi(
        @Named("reccobeats") retrofit: Retrofit
    ): ReccoBeatsApi {
        return retrofit.create(ReccoBeatsApi::class.java)
    }
}
