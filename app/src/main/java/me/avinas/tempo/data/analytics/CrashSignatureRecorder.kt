package me.avinas.tempo.data.analytics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import me.avinas.tempo.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records fatal crashes as reduced signatures, for the cases Play Console cannot see:
 * sideloaded builds, and crashes between Play's sampled, lagged reports.
 *
 * Only [CrashSignature] output is written — an obfuscated class plus one frame. The exception
 * message is never read, let alone stored, because messages routinely embed the notification
 * text Tempo parsed.
 *
 * The signature is written to a file at crash time (the process is dying, so no network call
 * or coroutine could complete) and reported on the next launch.
 */
@Singleton
class CrashSignatureRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracker: AnalyticsTracker
) {

    private val pendingFile: File
        get() = File(context.filesDir, FILE_NAME)

    /**
     * Installs the handler. Call this as early as possible in `Application.onCreate`.
     *
     * Does nothing in builds that cannot report, so a source or debug build runs no crash
     * handler of ours at all.
     */
    fun install() {
        if (!AnalyticsGate.isBuildConfigured(BuildConfig.APTABASE_APP_KEY, BuildConfig.DEBUG, BuildConfig.ANALYTICS_DEBUG_PREVIEW)) {
            return
        }

        // Refuse to install without something to delegate to. A handler that fails to
        // terminate the process would swallow crashes instead of reporting them, which is
        // far worse than having no handler at all.
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { persist(error) }
            previous.uncaughtException(thread, error)
        }
    }

    /** Reports a crash recorded by a previous run, if any. Safe to call on every start. */
    fun reportPending() {
        val file = pendingFile
        if (!file.exists()) return

        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        // Delete first: a signature that cannot be constructed must never be retried forever.
        file.delete()

        if (lines.size < 2) return
        runCatching {
            tracker.track(
                Crash(
                    exceptionClass = lines[0].trim(),
                    topFrame = lines[1].trim(),
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
        }
    }

    private fun persist(error: Throwable) {
        val (className, frame) = CrashSignature.of(error)
        pendingFile.writeText("$className\n$frame\n")
    }

    private companion object {
        const val FILE_NAME = "pending_crash_signature"
    }
}
