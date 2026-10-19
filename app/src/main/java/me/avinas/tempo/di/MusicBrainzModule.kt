package me.avinas.tempo.di

import com.squareup.moshi.Moshi
import me.avinas.tempo.data.remote.interceptors.RetryInterceptor
import me.avinas.tempo.data.remote.interceptors.UserAgentInterceptor
import me.avinas.tempo.data.remote.musicbrainz.CoverArtArchiveApi
import me.avinas.tempo.data.remote.musicbrainz.MusicBrainzApi
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

@Module
@InstallIn(SingletonComponent::class)
object MusicBrainzModule {

    /**
     * MusicBrainz asks clients for at most one request per second. That throttle is enforced in
     * `MusicBrainzEnrichmentService.withMusicBrainzRateLimit`, which wraps every call on this API
     * and suspends rather than blocking; it is the only consumer of [MusicBrainzApi].
     *
     * A blocking `RateLimitInterceptor` used to sit here as well. It was removed because it
     * duplicated that limiter on a *different* interval (1100 ms vs the service's 1000 ms), so it
     * added ~100 ms of pointless delay to nearly every call, and because it parked an OkHttp
     * dispatcher thread in `Thread.sleep` for the whole wait.
     */
    @Provides
    @Singleton
    @Named("musicbrainz")
    fun provideMusicBrainzOkHttpClient(
        retryInterceptor: RetryInterceptor,
        userAgentInterceptor: UserAgentInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("musicbrainz")
    fun provideMusicBrainzRetrofit(
        @Named("musicbrainz") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(MusicBrainzApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideMusicBrainzApi(
        @Named("musicbrainz") retrofit: Retrofit
    ): MusicBrainzApi {
        return retrofit.create(MusicBrainzApi::class.java)
    }

    /**
     * OkHttpClient for Cover Art Archive (no rate limiting needed).
     */
    @Provides
    @Singleton
    @Named("coverart")
    fun provideCoverArtOkHttpClient(
        userAgentInterceptor: UserAgentInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    @Named("coverart")
    fun provideCoverArtRetrofit(
        @Named("coverart") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(CoverArtArchiveApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideCoverArtArchiveApi(
        @Named("coverart") retrofit: Retrofit
    ): CoverArtArchiveApi {
        return retrofit.create(CoverArtArchiveApi::class.java)
    }
}
