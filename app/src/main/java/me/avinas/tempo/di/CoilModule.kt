package me.avinas.tempo.di

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.util.DebugLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.BuildConfig
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

private const val TAG = "CoilModule"

/**
 * Qualifier for the image-specific OkHttpClient.
 * This client has aggressive caching configured specifically for album art.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageClient

/**
 * Qualifier for the image-specific OkHttp Cache.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageCache

/**
 * Configures Coil ImageLoader with multi-tier caching:
 * - Memory cache (10-15% of RAM, adapting to low-RAM devices)
 * - Coil disk cache for decoded images (50-100MB)
 * - OkHttp disk cache (50MB) with 30-day response header overrides for CDNs
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    @Provides
    @Singleton
    @ImageCache
    fun provideImageOkHttpCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, "okhttp_image_cache")
        return Cache(cacheDir, 50L * 1024 * 1024)
    }

    private fun createForceCacheInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Image request: ${request.url}")
            }
            
            val response = chain.proceed(request)
            
            // Override upstream CDN cache-control headers to keep album art for 30 days
            val cacheControl = CacheControl.Builder()
                .maxAge(30, TimeUnit.DAYS)
                .maxStale(365, TimeUnit.DAYS)
                .build()
            
            response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Cache-Control")
                .removeHeader("Expires")
                .header("Cache-Control", cacheControl.toString())
                .build()
        }
    }

    @Provides
    @Singleton
    @ImageClient
    fun provideImageOkHttpClient(
        @ImageCache cache: Cache
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(cache)
            .addNetworkInterceptor(createForceCacheInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @ImageClient okHttpClient: OkHttpClient
    ): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRamDevice = activityManager.isLowRamDevice
        
        val memoryCachePercent = if (isLowRamDevice) 0.10 else 0.15
        val bitmapConfig = if (isLowRamDevice) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        
        Log.d(TAG, "Coil configured: isLowRam=$isLowRamDevice, memCache=${(memoryCachePercent * 100).toInt()}%, bitmapConfig=$bitmapConfig")
        
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { okHttpClient }
                ))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, memoryCachePercent)
                    .build()
            }
            .diskCache {
                val diskCacheSize = if (isLowRamDevice) 50L * 1024 * 1024 else 100L * 1024 * 1024
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_cache").toOkioPath())
                    .maxSizeBytes(diskCacheSize)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .build()
    }
}

