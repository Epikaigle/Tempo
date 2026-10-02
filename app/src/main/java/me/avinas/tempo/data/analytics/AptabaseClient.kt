package me.avinas.tempo.data.analytics

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends queued events to an Aptabase-compatible ingest endpoint.
 *
 * The protocol is a plain `POST {host}/api/v0/events` with an `App-Key` header, which is why
 * this is ~150 auditable lines in our own repo rather than a third-party SDK: anyone can read
 * exactly what Tempo transmits, and there is no JitPack dependency in the build.
 *
 * Privacy properties enforced here:
 *  - Reporting requires both a configured build and the user's consent, checked before every
 *    single enqueue *and* every flush.
 *  - The session id is in-memory and rotated; it is never persisted (see [AnalyticsSession]).
 *  - Failures are swallowed. A failed send leaves events queued, and they expire after 24h.
 */
@Singleton
class AptabaseClient
    @Inject
    constructor(
        private val consent: AnalyticsConsent,
        private val queue: AnalyticsQueueStore,
    ) : AnalyticsTracker {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val session = AnalyticsSession()

        /** Suppresses overlapping flushes without holding a lock across a suspension point. */
        private val flushLock = Mutex()

        /**
         * Deliberately NOT the app's shared OkHttp client: the UserAgent, Retry and RateLimit
         * interceptors — and the debug HttpLoggingInterceptor — must never see analytics traffic.
         * Built lazily so a build that cannot report never allocates it at all.
         */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        override fun track(event: AnalyticsEvent) {
            scope.launch {
                runCatching {
                    if (isReportingAllowed()) {
                        queue.enqueue(event.toQueued(System.currentTimeMillis()))
                    }
                }
            }
        }

        override suspend fun flush() {
            runCatching { flushInternal() }
        }

        override suspend fun purge() {
            runCatching { queue.purge() }
        }

        private suspend fun flushInternal() {
            if (!isReportingAllowed()) {
                // Nothing buffered may outlive a not-allowed state, so drop rather than keep.
                queue.purge()
                return
            }
            if (!flushLock.tryLock()) return
            try {
                // Drain the whole queue, not just the first batch: a device whose tracker
                // keeps dying can enqueue far more per day than one 25-event batch covers
                // (the health worker checks every 15 minutes), and the surplus would expire
                // unsent. The cap stops a silently failing drop from looping forever.
                val maxBatches = AnalyticsQueue.MAX_STORED / AnalyticsQueue.MAX_BATCH
                for (round in 0 until maxBatches) {
                    val batch = queue.peekBatch()
                    if (batch.isEmpty()) break

                    val body =
                        AptabasePayload.build(
                            events = batch,
                            sessionId = session.id(),
                            system = systemContext(),
                        )
                    val request =
                        Request
                            .Builder()
                            .url(BuildConfig.APTABASE_HOST.trimEnd('/') + AptabasePayload.PATH)
                            .header("App-Key", BuildConfig.APTABASE_APP_KEY)
                            .post(body.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                    val sent =
                        withContext(Dispatchers.IO) {
                            httpClient.newCall(request).execute().use { it.isSuccessful }
                        }
                    if (!sent) break
                    queue.dropBatch(batch)
                }
                // A failed send leaves the batch (and everything behind it) queued; the
                // events expire after 24h.
            } finally {
                flushLock.unlock()
            }
        }

        private suspend fun isReportingAllowed(): Boolean =
            AnalyticsGate.isBuildConfigured(
                appKey = BuildConfig.APTABASE_APP_KEY,
                isDebug = BuildConfig.DEBUG,
                debugPreview = BuildConfig.ANALYTICS_DEBUG_PREVIEW,
            ) && consent.isCollectionAllowed()

        private fun systemContext() =
            AnalyticsSystemContext(
                // The ingest API caps locale at 10 characters. Stock Android tags all fit
                // ("zh-Hans-CN" is the longest), but a ROM locale with a variant would fail
                // validation for the entire batch, so truncate defensively.
                locale = Locale.getDefault().toLanguageTag().take(MAX_LOCALE_CHARS),
                osVersion = Build.VERSION.SDK_INT.toString(),
                deviceModel = Build.MODEL ?: UNKNOWN_DEVICE,
                appVersion = BuildConfig.VERSION_NAME,
            )

        private companion object {
            val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
            const val UNKNOWN_DEVICE = "unknown"
            const val MAX_LOCALE_CHARS = 10
        }
    }
