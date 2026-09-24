package me.avinas.tempo.data.remote.interceptors

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate-limits OkHttp requests to comply with API quotas (e.g. MusicBrainz 1 req/sec).
 */
@Singleton
class RateLimitInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "RateLimitInterceptor"
        private const val MIN_REQUEST_INTERVAL_MS = 1100L // 1.1 seconds to be safe
    }

    private val lastRequestTime = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val lastRequest = lastRequestTime.get()
            val timeSinceLastRequest = now - lastRequest

            if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
                val sleepTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest
                Log.d(TAG, "Rate limiting: sleeping for ${sleepTime}ms")
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            lastRequestTime.set(System.currentTimeMillis())
        }

        return chain.proceed(chain.request())
    }
}
