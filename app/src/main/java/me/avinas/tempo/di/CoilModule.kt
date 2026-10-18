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
 * - OkHttp disk cache (50MB) with a 30-day fallback for responses whose CDN
 *   supplied no usable cache headers (origin directives are preserved otherwise)
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

            // Album art is immutable, so the intent is still to cache it aggressively —
            // but only as a *fallback*. This is a network interceptor, so whatever we
            // return here is what OkHttp's CacheInterceptor (which sits outside us)
            // writes to the disk cache, and rewriting the origin's headers is not free:
            //   * it discarded the origin's own policy — a CDN's
            //     `max-age=31536000, immutable` was *shortened* to 30 days, while
            //     `no-store`/`no-cache`/`must-revalidate` were overridden, and
            //   * forcing `max-age=30d` made every stored entry unconditionally fresh,
            //     so OkHttp never issued a conditional request: the stored ETag /
            //     Last-Modified were dead weight and images never got a cheap 304.
            // So if the origin told us how to cache — it has a freshness lifetime, or an
            // explicit do-not-cache directive — pass its response through untouched and
            // let its directives (and validators) drive freshness and revalidation.
            // Only synthesise a policy when there is no usable cache metadata at all.
            val originCacheControl = response.cacheControl
            val originHasCachePolicy =
                originCacheControl.noStore ||
                    originCacheControl.noCache ||
                    originCacheControl.mustRevalidate ||
                    originCacheControl.maxAgeSeconds != -1 ||
                    originCacheControl.sMaxAgeSeconds != -1 ||
                    response.header("Expires") != null

            if (originHasCachePolicy) {
                response
            } else {
                val cacheControl = CacheControl.Builder()
                    .maxAge(30, TimeUnit.DAYS)
                    .build()

                response.newBuilder()
                    // The origin supplied nothing usable, so its (legacy) directives are
                    // replaced by our fallback. Note `max-stale` was dropped: OkHttp only
                    // honours it on requests, so it never extended a response's lifetime.
                    .removeHeader("Pragma")
                    .removeHeader("Cache-Control")
                    .removeHeader("Expires")
                    .header("Cache-Control", cacheControl.toString())
                    .build()
            }
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

